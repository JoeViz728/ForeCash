package com.forecash.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forecash.app.domain.ProjectionEngine
import com.forecash.app.ui.util.formatCentsShort
import java.time.LocalDate
import java.time.YearMonth

/**
 * Custom monthly calendar — day number, projected EOD balance, activity dots, today ring.
 */
@Composable
fun MonthCalendar(
    yearMonth: YearMonth,
    accountId: Long,
    projectionEngine: ProjectionEngine,
    selectedDate: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
    today: LocalDate = LocalDate.now(),
) {
    val days = remember(yearMonth) {
        val firstDay = yearMonth.atDay(1)
        val firstDayOfWeek = firstDay.dayOfWeek.value % 7 // Sunday = 0
        val totalDays = yearMonth.lengthOfMonth()
        val list = mutableListOf<LocalDate?>()
        repeat(firstDayOfWeek) { list.add(null) }
        for (d in 1..totalDays) list.add(yearMonth.atDay(d))
        list
    }

    var dayInfo by remember(yearMonth, accountId, refreshKey) {
        mutableStateOf<Map<LocalDate, ProjectionEngine.DayBalance>?>(null)
    }

    LaunchedEffect(yearMonth, accountId, refreshKey) {
        val start = yearMonth.atDay(1)
        val end = yearMonth.atEndOfMonth()
        val list = projectionEngine.projectedBalancesInRange(accountId, start, end)
        dayInfo = list.associateBy { it.date }
    }

    Column(modifier = modifier) {
        Row(Modifier.fillMaxWidth()) {
            listOf("S", "M", "T", "W", "T", "F", "S").forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(days) { day ->
                if (day == null) {
                    Box(Modifier.aspectRatio(1f))
                } else {
                    val info = dayInfo?.get(day)
                    DayCell(
                        date = day,
                        projectedBalanceCents = info?.balanceCents,
                        isLoading = dayInfo == null,
                        hasActivity = info?.hasActivity == true,
                        isSelected = day == selectedDate,
                        isToday = day == today,
                        onClick = { onDayClick(day) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    projectedBalanceCents: Long?,
    isLoading: Boolean,
    hasActivity: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val balanceText = when {
        isLoading -> "…"
        projectedBalanceCents == null -> "—"
        else -> formatCentsShort(projectedBalanceCents)
    }
    val beforeTracking = !isLoading && projectedBalanceCents == null

    val shape = MaterialTheme.shapes.small
    var cellModifier = modifier
        .aspectRatio(1f)
        .clip(shape)
        .clickable(onClick = onClick)

    if (isToday && !isSelected) {
        cellModifier = cellModifier.border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            shape = shape
        )
    }

    Surface(
        modifier = cellModifier,
        color = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = if (beforeTracking) 0.2f else 0.4f
            )
        },
        tonalElevation = if (isSelected) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium
                ),
                color = when {
                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                    isToday -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (beforeTracking) 0.55f else 1f
                    )
                }
            )

            Spacer(Modifier.height(2.dp))

            Text(
                text = balanceText,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (beforeTracking) 0.5f else 1f
                    ),
                maxLines = 1
            )

            if (hasActivity) {
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.small
                        )
                )
            }
        }
    }
}
