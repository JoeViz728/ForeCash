package com.forecash.app.domain

import com.forecash.app.data.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Heart of ForeCash — see docs/CRITICAL_DECISIONS.md Decision #4.
 *
 * Baseline rules:
 * - Account.asOfDate + startingBalance = opening checkpoint (tracking starts).
 *   Nets on/after asOf apply on top of startingBalance.
 * - BalanceAnchor (legacy/optional) = absolute END-OF-DAY freeze when present.
 *   "Correct balance" in the UI does NOT use anchors — it inserts a normal
 *   one-time adjustment transaction (deletable to undo).
 */
class ProjectionEngine(
    private val accountDao: AccountDao,
    private val seriesDao: RecurringSeriesDao,
    private val modDao: RecurringModificationDao,
    private val txDao: TransactionDao,
    private val anchorDao: BalanceAnchorDao,
) {

    suspend fun projectedBalanceOn(accountId: Long, date: LocalDate): Long? =
        projectedBalancesInRange(accountId, date, date).firstOrNull()?.balanceCents

    suspend fun projectedBalancesInRange(
        accountId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<DayBalance> {
        if (to.isBefore(from)) return emptyList()
        val account = accountDao.getById(accountId) ?: return emptyList()

        val asOf = account.asOfDate
        if (to.isBefore(asOf)) {
            return eachDay(from, to).map { DayBalance(it, null, false) }
        }

        // Prefer latest hard anchor on/before `from`; else opening balance at asOf.
        val priorAnchor = anchorDao.getLatestAnchorOnOrBefore(accountId, from)
        val openingFromAsOf = priorAnchor == null

        val walkStart: LocalDate
        var running: Long
        val firstNetDay: LocalDate
        if (priorAnchor != null) {
            // Anchor is end-of-day truth — apply nets only on later days.
            walkStart = priorAnchor.date
            running = priorAnchor.actualBalance
            firstNetDay = walkStart.plusDays(1)
            // Record anchor day balance
        } else {
            walkStart = asOf
            running = account.startingBalance
            firstNetDay = walkStart // opening balance; same-day txs apply
        }

        val netByDate = if (!firstNetDay.isAfter(to)) {
            netForRange(accountId, firstNetDay, to)
        } else {
            emptyMap()
        }
        val anchors = anchorDao.getBetween(accountId, walkStart, to).associateBy { it.date }
        val activity = activityDatesInRange(accountId, from, to)

        val computed = HashMap<LocalDate, Long>()
        if (priorAnchor != null && !walkStart.isBefore(from) && !walkStart.isAfter(to)) {
            computed[walkStart] = running
        }

        if (!firstNetDay.isAfter(to)) {
            var cursor = firstNetDay
            while (!cursor.isAfter(to)) {
                running += (netByDate[cursor] ?: 0L)
                anchors[cursor]?.let { running = it.actualBalance }
                computed[cursor] = running
                if (cursor == to) break
                cursor = cursor.plusDays(1)
            }
        }

        // When opening from asOf, also fill asOf.. if firstNetDay walk covered it.
        // (Already covered when openingFromAsOf.)

        return eachDay(from, to).map { day ->
            DayBalance(
                date = day,
                balanceCents = computed[day], // null if before tracking / before walk
                hasActivity = day in activity,
            )
        }
    }

    suspend fun projectionSeries(
        accountId: Long,
        start: LocalDate = LocalDate.now(),
        months: Long = 6,
        sampleEveryDays: Long = 7,
    ): List<DayBalance> {
        val end = start.plusMonths(months)
        val daily = projectedBalancesInRange(accountId, start, end)
        if (daily.isEmpty()) return emptyList()
        val step = sampleEveryDays.toInt().coerceAtLeast(1)
        val sampled = daily.filterIndexed { index, _ -> index % step == 0 }.toMutableList()
        val last = daily.last()
        if (sampled.lastOrNull()?.date != last.date) sampled += last
        return sampled
    }

    suspend fun transactionsForDay(accountId: Long, date: LocalDate): List<EffectiveTransaction> {
        val result = mutableListOf<EffectiveTransaction>()

        txDao.getAffectingAccountBetween(accountId, date, date).forEach { tx ->
            result += toEffective(tx, virtual = false)
        }

        seriesDao.getAll().filter { it.accountId == accountId }.forEach { series ->
            if (series.endDate != null && date > series.endDate) return@forEach
            if (date.isBefore(series.startDate)) return@forEach
            if (txDao.getHardOverride(series.id, date) != null) return@forEach

            val template = getEffectiveTemplate(series, date) ?: return@forEach
            if (!occursOn(template, date, series.startDate)) return@forEach

            result += EffectiveTransaction(
                date = date,
                amount = template.amount,
                name = template.name,
                kind = template.kind,
                isVirtual = true,
                isHardOverride = false,
                originalTransactionId = null,
                recurringSeriesId = series.id,
                categoryId = template.categoryId,
            )
        }
        return result
    }

    private fun toEffective(tx: Transaction, virtual: Boolean) = EffectiveTransaction(
        date = tx.date,
        amount = tx.amount,
        name = tx.name,
        kind = tx.kind,
        isVirtual = virtual,
        isHardOverride = tx.isHardOverride,
        originalTransactionId = tx.id,
        recurringSeriesId = tx.recurringSeriesId,
        categoryId = tx.categoryId,
    )

    private suspend fun activityDatesInRange(
        accountId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Set<LocalDate> {
        val dates = mutableSetOf<LocalDate>()
        txDao.getAffectingAccountBetween(accountId, from, to).forEach { dates += it.date }
        seriesDao.getAll().filter { it.accountId == accountId }.forEach { series ->
            var d = firstOccurrenceOnOrAfter(series, maxOf(series.startDate, from)) ?: return@forEach
            while (!d.isAfter(to)) {
                if (series.endDate != null && d > series.endDate) break
                if (txDao.getHardOverride(series.id, d) == null) {
                    val template = getEffectiveTemplate(series, d)
                    if (template != null && occursOn(template, d, series.startDate)) dates += d
                }
                d = advanceOne(d, series.frequency)
            }
        }
        return dates
    }

    private suspend fun netForRange(
        accountId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Map<LocalDate, Long> {
        if (to.isBefore(from)) return emptyMap()
        val netByDate = mutableMapOf<LocalDate, Long>()

        txDao.getAffectingAccountBetween(accountId, from, to).forEach { tx ->
            netByDate[tx.date] = (netByDate[tx.date] ?: 0L) + deltaForAccount(tx, accountId)
        }

        seriesDao.getAll().filter { it.accountId == accountId }.forEach { series ->
            if (series.endDate != null && series.endDate < from) return@forEach
            var d = firstOccurrenceOnOrAfter(series, maxOf(series.startDate, from)) ?: return@forEach
            while (!d.isAfter(to)) {
                if (series.endDate != null && d > series.endDate) break
                val template = getEffectiveTemplate(series, d)
                if (template != null) {
                    if (txDao.getHardOverride(series.id, d) == null &&
                        occursOn(template, d, series.startDate)
                    ) {
                        netByDate[d] = (netByDate[d] ?: 0L) + signedAmount(template.kind, template.amount)
                    }
                    d = advanceOne(d, template.frequency)
                } else {
                    d = advanceOne(d, series.frequency)
                }
            }
        }
        return netByDate
    }

    private fun deltaForAccount(tx: Transaction, accountId: Long): Long = when (tx.kind) {
        TransactionKind.EXPENSE -> if (tx.accountId == accountId) -tx.amount else 0L
        TransactionKind.INCOME -> if (tx.accountId == accountId) +tx.amount else 0L
        TransactionKind.TRANSFER -> when {
            tx.accountId == accountId -> -tx.amount
            tx.toAccountId == accountId -> +tx.amount
            else -> 0L
        }
    }

    private fun signedAmount(kind: TransactionKind, amount: Long): Long = when (kind) {
        TransactionKind.EXPENSE -> -amount
        TransactionKind.INCOME -> +amount
        TransactionKind.TRANSFER -> -amount
    }

    private suspend fun getEffectiveTemplate(series: RecurringSeries, onDate: LocalDate): Template? {
        val latestMod = modDao.getLatestBeforeOrOn(series.id, onDate)
        return Template(
            amount = latestMod?.amount ?: series.amount,
            name = latestMod?.name ?: series.name,
            frequency = latestMod?.frequency ?: series.frequency,
            dayOfMonth = latestMod?.dayOfMonth ?: series.dayOfMonth,
            startDate = series.startDate,
            kind = series.kind,
            categoryId = latestMod?.categoryId ?: series.categoryId,
        )
    }

    private fun occursOn(template: Template, date: LocalDate, seriesStart: LocalDate): Boolean {
        if (date.isBefore(seriesStart)) return false
        return when (template.frequency) {
            Frequency.MONTHLY -> {
                val target = (template.dayOfMonth ?: seriesStart.dayOfMonth)
                    .coerceIn(1, date.lengthOfMonth())
                date.dayOfMonth == target
            }
            Frequency.WEEKLY ->
                date.dayOfWeek == seriesStart.dayOfWeek &&
                    ChronoUnit.DAYS.between(seriesStart, date) % 7L == 0L
            Frequency.BIWEEKLY ->
                date.dayOfWeek == seriesStart.dayOfWeek &&
                    ChronoUnit.DAYS.between(seriesStart, date) % 14L == 0L
            Frequency.QUARTERLY -> {
                val targetDom = (template.dayOfMonth ?: seriesStart.dayOfMonth)
                    .coerceIn(1, date.lengthOfMonth())
                date.dayOfMonth == targetDom &&
                    ((date.year * 12 + date.monthValue) - (seriesStart.year * 12 + seriesStart.monthValue)) % 3 == 0
            }
            Frequency.YEARLY ->
                date.month == seriesStart.month &&
                    date.dayOfMonth == (template.dayOfMonth ?: seriesStart.dayOfMonth)
                        .coerceIn(1, date.lengthOfMonth())
        }
    }

    private fun firstOccurrenceOnOrAfter(series: RecurringSeries, onOrAfter: LocalDate): LocalDate? {
        if (series.endDate != null && onOrAfter > series.endDate) return null
        var d = maxOf(series.startDate, onOrAfter)
        val limit = d.plusYears(2)
        val template = Template(
            amount = series.amount,
            name = series.name,
            frequency = series.frequency,
            dayOfMonth = series.dayOfMonth,
            startDate = series.startDate,
            kind = series.kind,
            categoryId = series.categoryId,
        )
        while (!d.isAfter(limit)) {
            if (series.endDate != null && d > series.endDate) return null
            if (occursOn(template, d, series.startDate)) return d
            d = d.plusDays(1)
        }
        return null
    }

    private fun advanceOne(date: LocalDate, freq: Frequency): LocalDate = when (freq) {
        Frequency.WEEKLY -> date.plusWeeks(1)
        Frequency.BIWEEKLY -> date.plusWeeks(2)
        Frequency.MONTHLY -> date.plusMonths(1)
        Frequency.QUARTERLY -> date.plusMonths(3)
        Frequency.YEARLY -> date.plusYears(1)
    }

    private fun eachDay(from: LocalDate, to: LocalDate): List<LocalDate> {
        val list = ArrayList<LocalDate>((ChronoUnit.DAYS.between(from, to) + 1).toInt().coerceAtLeast(0))
        var d = from
        while (!d.isAfter(to)) {
            list += d
            d = d.plusDays(1)
        }
        return list
    }

    data class DayBalance(
        val date: LocalDate,
        val balanceCents: Long?,
        val hasActivity: Boolean = false,
    )

    data class Template(
        val amount: Long,
        val name: String,
        val frequency: Frequency,
        val dayOfMonth: Int?,
        val startDate: LocalDate,
        val kind: TransactionKind,
        val categoryId: Long,
    )

    data class EffectiveTransaction(
        val date: LocalDate,
        val amount: Long,
        val name: String,
        val kind: TransactionKind,
        val isVirtual: Boolean,
        val isHardOverride: Boolean,
        val originalTransactionId: Long?,
        val recurringSeriesId: Long?,
        val categoryId: Long? = null,
    )
}
