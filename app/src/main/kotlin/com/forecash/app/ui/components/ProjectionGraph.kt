package com.forecash.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.forecash.app.domain.ProjectionEngine
import com.forecash.app.ui.util.formatCents
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Compact balance projection line for the default (no day selected) bottom panel.
 */
@Composable
fun ProjectionGraph(
    accountId: Long,
    engine: ProjectionEngine,
    refreshKey: Int,
    modifier: Modifier = Modifier,
    months: Long = 6,
) {
    var points by remember { mutableStateOf<List<ProjectionEngine.DayBalance>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(accountId, refreshKey, months) {
        loaded = false
        points = engine.projectionSeries(
            accountId = accountId,
            start = LocalDate.now(),
            months = months,
            sampleEveryDays = 7,
        )
        loaded = true
    }

    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val known = points.mapNotNull { p -> p.balanceCents?.let { p.date to it } }

    Column(modifier = modifier) {
        Text("Projected balance · next $months months", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))

        when {
            !loaded -> {
                Text("Loading…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            known.size < 2 -> {
                Text(
                    "Add a starting balance and some activity to see the projection curve.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> {
                val minB = known.minOf { it.second }
                val maxB = known.maxOf { it.second }
                val startLabel = known.first().first.format(DateTimeFormatter.ofPattern("MMM d"))
                val endLabel = known.last().first.format(DateTimeFormatter.ofPattern("MMM d"))
                val endBal = formatCents(known.last().second)

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    val w = size.width
                    val h = size.height
                    val padY = 8f
                    val range = (maxB - minB).toFloat().let { if (it < 1f) 1f else it }

                    // baseline grid
                    drawLine(gridColor, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f)

                    fun xAt(i: Int): Float =
                        if (known.size == 1) w / 2f else i * (w / (known.size - 1).toFloat())

                    fun yAt(cents: Long): Float {
                        val t = (cents - minB).toFloat() / range
                        return h - padY - t * (h - 2 * padY)
                    }

                    val path = Path()
                    known.forEachIndexed { i, (_, cents) ->
                        val x = xAt(i)
                        val y = yAt(cents)
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = lineColor,
                        style = Stroke(width = 4f, cap = StrokeCap.Round)
                    )
                    // end dot
                    val last = known.last()
                    drawCircle(
                        color = lineColor,
                        radius = 6f,
                        center = Offset(xAt(known.lastIndex), yAt(last.second))
                    )
                }

                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(startLabel, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("→ $endBal", style = MaterialTheme.typography.labelMedium)
                    Text(endLabel, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap a day above for details. Worst-case band coming soon.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
