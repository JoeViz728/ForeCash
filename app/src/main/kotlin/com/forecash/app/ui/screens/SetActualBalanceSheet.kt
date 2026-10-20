package com.forecash.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.forecash.app.ui.util.formatCents
import com.forecash.app.ui.util.parseDollarsToCents
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Correct the day's projected balance by creating a one-time adjustment TX
 * for the difference. Undo = delete that TX from the day list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetActualBalanceSheet(
    date: LocalDate,
    projectedCents: Long?,
    onDismiss: () -> Unit,
    onConfirm: (desiredCents: Long) -> Unit,
) {
    var text by remember {
        mutableStateOf(
            projectedCents?.let { String.format("%.2f", it / 100.0) } ?: ""
        )
    }
    val desired = parseDollarsToCents(text)
    val dateLabel = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    val delta = if (desired != null && projectedCents != null) desired - projectedCents else null

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Correct balance", style = MaterialTheme.typography.titleLarge)
            Text(
                dateLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Enter what the balance should be. ForeCash adds a one-time adjustment " +
                    "transaction for the difference. Change your mind later? Just delete that TX.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (projectedCents != null) {
                Text(
                    "Currently projected: ${formatCents(projectedCents)}",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Correct balance") },
                prefix = { Text("$") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            when {
                projectedCents == null -> {
                    Text(
                        "No projected balance on this day yet (before tracking).",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                delta == null -> Unit
                delta == 0L -> {
                    Text(
                        "Already matches — nothing to adjust.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                delta > 0L -> {
                    Text(
                        "Will add income adjustment: +${formatCents(delta)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                else -> {
                    Text(
                        "Will add expense adjustment: −${formatCents(kotlin.math.abs(delta))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = { desired?.let(onConfirm) },
                    enabled = desired != null && projectedCents != null && delta != 0L,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Apply adjustment")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
