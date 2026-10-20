package com.forecash.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.forecash.app.ForeCashApp
import com.forecash.app.data.Category
import com.forecash.app.data.ForeCashRepository
import com.forecash.app.domain.ProjectionEngine
import com.forecash.app.ui.components.MonthCalendar
import com.forecash.app.ui.components.ProjectionGraph
import com.forecash.app.ui.util.formatCents
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Main experience: calendar + morphing bottom panel (graph ↔ day detail).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarMainScreen(repository: ForeCashRepository) {
    val context = LocalContext.current
    val app = context.applicationContext as ForeCashApp

    val engine = remember {
        ProjectionEngine(
            accountDao = app.database.accountDao(),
            seriesDao = app.database.recurringSeriesDao(),
            modDao = app.database.recurringModificationDao(),
            txDao = app.database.transactionDao(),
            anchorDao = app.database.balanceAnchorDao()
        )
    }

    val today = LocalDate.now()
    var currentMonth by remember { mutableStateOf(YearMonth.from(today)) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    var primaryAccountId by remember { mutableStateOf<Long?>(null) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var dataVersion by remember { mutableIntStateOf(0) }

    var editorTarget by remember { mutableStateOf<TxEditorTarget?>(null) }
    var showSetActual by remember { mutableStateOf(false) }
    var setActualSuggested by remember { mutableStateOf<Long?>(null) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        primaryAccountId = repository.getPrimaryAccount()?.id
        repository.ensureDefaultCategories()
        categories = repository.getAllCategories()
    }

    val accountId = primaryAccountId
    if (accountId == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    fun jumpToToday() {
        currentMonth = YearMonth.from(today)
        selectedDate = today
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    editorTarget = TxEditorTarget.Create(
                        initialDate = selectedDate ?: today
                    )
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add transaction")
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                if (currentMonth != YearMonth.from(today) || selectedDate != today) {
                    TextButton(onClick = { jumpToToday() }) {
                        Text("Today")
                    }
                }
                TextButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) {
                    Text("←")
                }
                TextButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
                    Text("→")
                }
            }

            MonthCalendar(
                yearMonth = currentMonth,
                accountId = accountId,
                projectionEngine = engine,
                selectedDate = selectedDate,
                today = today,
                refreshKey = dataVersion,
                onDayClick = { day ->
                    selectedDate = if (selectedDate == day) null else day
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.58f)
                    .padding(horizontal = 8.dp)
            )

            HorizontalDivider()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.42f)
                    .padding(16.dp)
            ) {
                val date = selectedDate
                if (date != null) {
                    DayDetailPanel(
                        date = date,
                        accountId = accountId,
                        engine = engine,
                        refreshKey = dataVersion,
                        onSetActualBalance = { suggested ->
                            setActualSuggested = suggested
                            showSetActual = true
                        },
                        onAddTransaction = {
                            editorTarget = TxEditorTarget.Create(initialDate = date)
                        },
                        onEditTransaction = { effective ->
                            scope.launch {
                                editorTarget = if (effective.isVirtual) {
                                    TxEditorTarget.EditVirtual(effective)
                                } else {
                                    val id = effective.originalTransactionId
                                    val tx = id?.let { repository.getTransaction(it) }
                                    if (tx != null) TxEditorTarget.EditHard(tx)
                                    else null
                                }
                            }
                        },
                    )
                } else {
                    ProjectionGraph(
                        accountId = accountId,
                        engine = engine,
                        refreshKey = dataVersion,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    editorTarget?.let { target ->
        TransactionEditorSheet(
            accountId = accountId,
            categories = categories,
            repository = repository,
            target = target,
            onDismiss = { editorTarget = null },
            onSaved = { dataVersion++ },
        )
    }

    if (showSetActual && selectedDate != null) {
        SetActualBalanceSheet(
            date = selectedDate!!,
            projectedCents = setActualSuggested,
            onDismiss = { showSetActual = false },
            onConfirm = { desiredCents ->
                scope.launch {
                    val projected = setActualSuggested
                    if (projected != null) {
                        repository.correctBalanceWithAdjustment(
                            accountId = accountId,
                            date = selectedDate!!,
                            projectedCents = projected,
                            desiredCents = desiredCents,
                        )
                    }
                    showSetActual = false
                    dataVersion++
                }
            }
        )
    }
}

@Composable
private fun DayDetailPanel(
    date: LocalDate,
    accountId: Long,
    engine: ProjectionEngine,
    refreshKey: Int,
    onSetActualBalance: (suggestedCents: Long?) -> Unit,
    onAddTransaction: () -> Unit,
    onEditTransaction: (ProjectionEngine.EffectiveTransaction) -> Unit,
    modifier: Modifier = Modifier
) {
    var loaded by remember { mutableStateOf(false) }
    var projected by remember { mutableStateOf<Long?>(null) }
    var transactions by remember {
        mutableStateOf<List<ProjectionEngine.EffectiveTransaction>>(emptyList())
    }

    LaunchedEffect(date, accountId, refreshKey) {
        loaded = false
        projected = engine.projectedBalanceOn(accountId, date)
        transactions = engine.transactionsForDay(accountId, date)
        loaded = true
    }

    val beforeTracking = loaded && projected == null

    Column(modifier.fillMaxSize()) {
        Text(
            if (beforeTracking) "Before tracking started"
            else "Projected balance · ${date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))

        Text(
            text = when {
                !loaded -> "…"
                projected == null -> "—"
                else -> formatCents(projected!!)
            },
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(Modifier.height(6.dp))

        if (beforeTracking) {
            Text(
                "Before your tracking start date — pick a day on/after as-of, or correct balance once tracking applies.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(8.dp))

        Text("On this day · tap to edit", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))

        if (!loaded) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (transactions.isEmpty()) {
            Text("No transactions", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(transactions, key = {
                    "${it.originalTransactionId}-${it.recurringSeriesId}-${it.name}-${it.amount}-${it.isHardOverride}"
                }) { tx ->
                    TransactionRow(tx = tx, onClick = { onEditTransaction(tx) })
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onSetActualBalance(projected) },
                enabled = projected != null
            ) {
                Text("Correct balance")
            }
            Button(onClick = onAddTransaction) {
                Text("Add")
            }
        }
    }
}

@Composable
private fun TransactionRow(
    tx: ProjectionEngine.EffectiveTransaction,
    onClick: () -> Unit,
) {
    val isSkip = tx.isHardOverride && tx.amount == 0L
    val isOverride = tx.isHardOverride && !isSkip
    val isAdjustment = !tx.isVirtual && !tx.isHardOverride &&
        tx.name.equals("Balance adjustment", ignoreCase = true)

    val prefix = when {
        tx.isVirtual -> "⟳ "
        isSkip -> "⊘ "
        isOverride -> "★ "
        isAdjustment -> "⚖ "
        else -> ""
    }

    val amountLabel = when {
        isSkip -> "skipped"
        tx.kind == com.forecash.app.data.TransactionKind.INCOME -> "+${formatCents(tx.amount)}"
        else -> "−${formatCents(tx.amount)}"
    }

    val subtitle = when {
        isSkip -> "Skipped recurring · tap to undo"
        isOverride -> "Hard override · tap to edit or undo"
        isAdjustment -> "Balance correction · delete to undo"
        tx.isVirtual -> "Recurring · tap to change"
        else -> tx.kind.name.lowercase().replaceFirstChar { it.titlecase() }
    }

    ListItem(
        headlineContent = { Text("$prefix${tx.name.removeSuffix(" (skipped)")}") },
        supportingContent = {
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        },
        trailingContent = {
            Text(
                amountLabel,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isSkip -> MaterialTheme.colorScheme.onSurfaceVariant
                    tx.kind == com.forecash.app.data.TransactionKind.INCOME ->
                        MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    )
}
