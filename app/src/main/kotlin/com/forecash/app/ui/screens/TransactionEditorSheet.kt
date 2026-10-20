package com.forecash.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.forecash.app.data.*
import com.forecash.app.domain.ProjectionEngine
import com.forecash.app.ui.util.parseDollarsToCents
import com.forecash.app.ui.util.toEpochMillisUtc
import com.forecash.app.ui.util.toLocalDateUtc
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Create / edit transactions — one-time or recurring.
 *
 * Virtual recurring instances:
 * - "This instance only" → hard override Transaction
 * - "This and future" → RecurringModification (+ update series template)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionEditorSheet(
    accountId: Long,
    categories: List<Category>,
    repository: ForeCashRepository,
    target: TxEditorTarget,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var name by remember {
        mutableStateOf(
            when (target) {
                is TxEditorTarget.Create -> ""
                is TxEditorTarget.EditHard -> target.tx.name
                is TxEditorTarget.EditVirtual -> target.effective.name
            }
        )
    }
    var amountText by remember {
        mutableStateOf(
            when (target) {
                is TxEditorTarget.Create -> ""
                is TxEditorTarget.EditHard -> String.format("%.2f", target.tx.amount / 100.0)
                is TxEditorTarget.EditVirtual -> String.format("%.2f", target.effective.amount / 100.0)
            }
        )
    }
    var date by remember {
        mutableStateOf(
            when (target) {
                is TxEditorTarget.Create -> target.initialDate
                is TxEditorTarget.EditHard -> target.tx.date
                is TxEditorTarget.EditVirtual -> target.effective.date
            }
        )
    }
    var kind by remember {
        mutableStateOf(
            when (target) {
                is TxEditorTarget.Create -> TransactionKind.EXPENSE
                is TxEditorTarget.EditHard -> target.tx.kind
                is TxEditorTarget.EditVirtual -> target.effective.kind
            }
        )
    }
    var selectedCategory by remember {
        mutableStateOf(
            when (target) {
                is TxEditorTarget.Create -> null
                is TxEditorTarget.EditHard -> categories.find { it.id == target.tx.categoryId }
                is TxEditorTarget.EditVirtual -> categories.find { it.id == target.effective.categoryId }
            }
        )
    }
    var isRecurring by remember {
        mutableStateOf(target is TxEditorTarget.EditVirtual)
    }
    var frequency by remember { mutableStateOf(Frequency.MONTHLY) }
    var recurringScope by remember { mutableStateOf(RecurringEditScope.THIS_ONLY) }
    var showDatePicker by remember { mutableStateOf(false) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var freqExpanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load series frequency when editing virtual
    LaunchedEffect(target) {
        if (target is TxEditorTarget.EditVirtual) {
            val series = repository.getSeries(target.effective.recurringSeriesId!!)
            if (series != null) frequency = series.frequency
            isRecurring = true
        }
        if (target is TxEditorTarget.EditHard && target.tx.recurringSeriesId != null) {
            isRecurring = false // hard row; editing the stored override/one-time
        }
    }

    val isEdit = target !is TxEditorTarget.Create
    val isHardOverride = target is TxEditorTarget.EditHard && target.tx.isHardOverride
    val isSkipOverride = isHardOverride && (target as TxEditorTarget.EditHard).tx.amount == 0L
    val isAdjustment = target is TxEditorTarget.EditHard &&
        !target.tx.isHardOverride &&
        target.tx.name.equals("Balance adjustment", ignoreCase = true)

    val title = when {
        target is TxEditorTarget.Create -> "Add transaction"
        isSkipOverride -> "Skipped recurring"
        isHardOverride -> "Recurring override"
        isAdjustment -> "Balance adjustment"
        target is TxEditorTarget.EditHard -> "Edit transaction"
        target is TxEditorTarget.EditVirtual -> "Edit recurring"
        else -> "Transaction"
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name / Description") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount") },
                prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Date — user selectable
            OutlinedCard(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Date", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text(
                        date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Tap to change",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == TransactionKind.EXPENSE,
                    onClick = { kind = TransactionKind.EXPENSE },
                    label = { Text("Expense") }
                )
                FilterChip(
                    selected = kind == TransactionKind.INCOME,
                    onClick = { kind = TransactionKind.INCOME },
                    label = { Text("Income") }
                )
            }

            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = !categoryExpanded }
            ) {
                OutlinedTextField(
                    value = selectedCategory?.let { "${it.icon} ${it.name}" } ?: "Select category",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Category") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) },
                    modifier = Modifier
                        .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false }
                ) {
                    categories.forEach { cat ->
                        DropdownMenuItem(
                            text = { Text("${cat.icon} ${cat.name}") },
                            onClick = {
                                selectedCategory = cat
                                categoryExpanded = false
                            }
                        )
                    }
                }
            }

            // Recurring controls
            when (target) {
                is TxEditorTarget.Create -> {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = isRecurring, onCheckedChange = { isRecurring = it })
                        Text("Recurring")
                    }
                    if (isRecurring) {
                        FrequencyDropdown(frequency, freqExpanded, { freqExpanded = it }) {
                            frequency = it
                            freqExpanded = false
                        }
                    }
                }
                is TxEditorTarget.EditVirtual -> {
                    Text("Apply changes to", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = recurringScope == RecurringEditScope.THIS_ONLY,
                            onClick = { recurringScope = RecurringEditScope.THIS_ONLY },
                            label = { Text("This instance") }
                        )
                        FilterChip(
                            selected = recurringScope == RecurringEditScope.THIS_AND_FUTURE,
                            onClick = { recurringScope = RecurringEditScope.THIS_AND_FUTURE },
                            label = { Text("This + future") }
                        )
                    }
                    if (recurringScope == RecurringEditScope.THIS_AND_FUTURE) {
                        FrequencyDropdown(frequency, freqExpanded, { freqExpanded = it }) {
                            frequency = it
                            freqExpanded = false
                        }
                    }
                }
                is TxEditorTarget.EditHard -> {
                    when {
                        isSkipOverride -> {
                            Text(
                                "This occurrence was skipped. Undo restores the normal recurring amount on this day.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        isHardOverride -> {
                            Text(
                                "★ One-time override of a recurring series. Undo restores the template for this day only.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        isAdjustment -> {
                            Text(
                                "⚖ Created by Correct balance. Delete this entry to undo the correction.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }

            // Primary undo for overrides / skips — always visible and obvious
            if (isHardOverride) {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            repository.deleteTransaction((target as TxEditorTarget.EditHard).tx)
                            busy = false
                            onSaved()
                            onDismiss()
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isSkipOverride) "Undo skip · restore recurring" else "Undo override · restore recurring")
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    // Overrides already have a primary Undo above — Cancel here
                    isHardOverride -> {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                    }
                    isEdit -> {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    when (target) {
                                        is TxEditorTarget.EditHard ->
                                            repository.deleteTransaction(target.tx)
                                        is TxEditorTarget.EditVirtual -> {
                                            val seriesId = target.effective.recurringSeriesId!!
                                            val baseName = target.effective.name.removeSuffix(" (skipped)")
                                            repository.addTransaction(
                                                Transaction(
                                                    date = target.effective.date,
                                                    amount = 0,
                                                    kind = target.effective.kind,
                                                    name = "$baseName (skipped)",
                                                    categoryId = target.effective.categoryId,
                                                    accountId = accountId,
                                                    recurringSeriesId = seriesId,
                                                    isHardOverride = true,
                                                )
                                            )
                                        }
                                        else -> Unit
                                    }
                                    busy = false
                                    onSaved()
                                    onDismiss()
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                when {
                                    target is TxEditorTarget.EditVirtual -> "Skip this day"
                                    isAdjustment -> "Delete adjustment"
                                    else -> "Delete"
                                }
                            )
                        }
                    }
                    else -> {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                    }
                }

                // Save — for overrides, lets you change the hard amount (incl. un-skip)
                Button(
                    onClick = {
                        scope.launch {
                            val cents = parseDollarsToCents(amountText)
                            if (cents == null || cents < 0L) {
                                error = "Enter a valid amount"
                                return@launch
                            }
                            if (cents == 0L && !isHardOverride) {
                                error = "Enter a valid amount"
                                return@launch
                            }
                            if (name.isBlank()) {
                                error = "Name is required"
                                return@launch
                            }
                            if (selectedCategory == null) {
                                error = "Pick a category"
                                return@launch
                            }
                            busy = true
                            error = null
                            try {
                                saveTransaction(
                                    repository = repository,
                                    accountId = accountId,
                                    target = target,
                                    name = name.trim(),
                                    cents = cents,
                                    date = date,
                                    kind = kind,
                                    categoryId = selectedCategory!!.id,
                                    isRecurring = isRecurring,
                                    frequency = frequency,
                                    recurringScope = recurringScope,
                                )
                                onSaved()
                                onDismiss()
                            } catch (e: Exception) {
                                error = e.message ?: "Save failed"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (busy) "…" else "Save")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date.toEpochMillisUtc())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { date = it.toLocalDateUtc() }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrequencyDropdown(
    frequency: Frequency,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (Frequency) -> Unit,
) {
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
        OutlinedTextField(
            value = frequency.label(),
            onValueChange = {},
            readOnly = true,
            label = { Text("Frequency") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            Frequency.entries.forEach { f ->
                DropdownMenuItem(
                    text = { Text(f.label()) },
                    onClick = { onSelect(f) }
                )
            }
        }
    }
}

private fun Frequency.label(): String = when (this) {
    Frequency.WEEKLY -> "Weekly"
    Frequency.BIWEEKLY -> "Bi-weekly"
    Frequency.MONTHLY -> "Monthly"
    Frequency.QUARTERLY -> "Quarterly"
    Frequency.YEARLY -> "Yearly"
}

private suspend fun saveTransaction(
    repository: ForeCashRepository,
    accountId: Long,
    target: TxEditorTarget,
    name: String,
    cents: Long,
    date: LocalDate,
    kind: TransactionKind,
    categoryId: Long,
    isRecurring: Boolean,
    frequency: Frequency,
    recurringScope: RecurringEditScope,
) {
    when (target) {
        is TxEditorTarget.Create -> {
            if (isRecurring) {
                repository.createSeries(
                    RecurringSeries(
                        accountId = accountId,
                        amount = cents,
                        name = name,
                        categoryId = categoryId,
                        kind = kind,
                        frequency = frequency,
                        startDate = date,
                        dayOfMonth = date.dayOfMonth,
                    )
                )
            } else {
                repository.addTransaction(
                    Transaction(
                        date = date,
                        amount = cents,
                        kind = kind,
                        name = name,
                        categoryId = categoryId,
                        accountId = accountId,
                    )
                )
            }
        }
        is TxEditorTarget.EditHard -> {
            repository.updateTransaction(
                target.tx.copy(
                    date = date,
                    amount = cents,
                    kind = kind,
                    name = name,
                    categoryId = categoryId,
                )
            )
        }
        is TxEditorTarget.EditVirtual -> {
            val seriesId = target.effective.recurringSeriesId
                ?: error("Virtual tx missing series id")
            when (recurringScope) {
                RecurringEditScope.THIS_ONLY -> {
                    repository.addTransaction(
                        Transaction(
                            date = date,
                            amount = cents,
                            kind = kind,
                            name = name,
                            categoryId = categoryId,
                            accountId = accountId,
                            recurringSeriesId = seriesId,
                            isHardOverride = true,
                        )
                    )
                }
                RecurringEditScope.THIS_AND_FUTURE -> {
                    repository.addModification(
                        RecurringModification(
                            seriesId = seriesId,
                            effectiveDate = date,
                            amount = cents,
                            name = name,
                            categoryId = categoryId,
                            frequency = frequency,
                            dayOfMonth = date.dayOfMonth,
                        )
                    )
                    // Keep series "current template" in sync for clarity
                    val series = repository.getSeries(seriesId)
                    if (series != null) {
                        repository.updateSeries(
                            series.copy(
                                amount = cents,
                                name = name,
                                categoryId = categoryId,
                                kind = kind,
                                frequency = frequency,
                                dayOfMonth = date.dayOfMonth,
                            )
                        )
                    }
                }
            }
        }
    }
}

enum class RecurringEditScope { THIS_ONLY, THIS_AND_FUTURE }

sealed class TxEditorTarget {
    data class Create(val initialDate: LocalDate) : TxEditorTarget()
    data class EditHard(val tx: Transaction) : TxEditorTarget()
    data class EditVirtual(val effective: ProjectionEngine.EffectiveTransaction) : TxEditorTarget()
}
