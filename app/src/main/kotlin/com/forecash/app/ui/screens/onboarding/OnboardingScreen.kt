package com.forecash.app.ui.screens.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.forecash.app.ForeCashApp
import com.forecash.app.data.ForeCashRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * First-run experience.
 * User must create at least one account before they can use the calendar.
 *
 * The starting balance opens tracking on an explicit "as of" date (default: today).
 * Projected balances begin there — earlier days stay blank on purpose.
 * Later corrections use a deletable one-time adjustment TX ("Correct balance").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onAccountCreated: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ForeCashApp
    val repository = remember { ForeCashRepository(app.database) }
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("Checking") }
    var balanceText by remember { mutableStateOf("2500.00") }
    var asOfDate by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    val dateLabel = remember(asOfDate) {
        asOfDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Welcome to ForeCash", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Let's start with your first account.\nThis is usually your main checking account.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Account name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = balanceText,
            onValueChange = { balanceText = it },
            label = { Text("Balance") },
            supportingText = { Text("What the account actually holds on the date below") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        // As-of date = starting checkpoint (not a blanket across history)
        OutlinedCard(
            onClick = { showDatePicker = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Balance as of",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    dateLabel,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tracking starts here — like a checkpoint. Days before this stay empty on purpose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap to change date",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                scope.launch {
                    isLoading = true
                    val cents = ((balanceText.toDoubleOrNull() ?: 0.0) * 100).toLong()
                    repository.createAccount(
                        name = name.trim(),
                        balanceCents = cents,
                        asOf = asOfDate,
                    )
                    repository.ensureDefaultCategories()
                    isLoading = false
                    onAccountCreated()
                }
            },
            enabled = name.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Get started")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "You can add more accounts later from settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = asOfDate.toEpochMillisUtc(),
            // Allow a reasonable past window; future as-of is unusual but allowed.
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            asOfDate = millis.toLocalDateUtc()
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(
                state = pickerState,
                title = {
                    Text(
                        "When does this balance take effect?",
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)
                    )
                },
                headline = {
                    Text(
                        "Starting checkpoint",
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            )
        }
    }
}

/** Date-only millis in UTC — avoids off-by-one from local midnight. */
private fun LocalDate.toEpochMillisUtc(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDateUtc(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
