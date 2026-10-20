package com.forecash.app.ui.util

fun formatCents(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign$${String.format("%,.2f", abs / 100.0)}"
}

fun formatCentsShort(cents: Long): String {
    val abs = kotlin.math.abs(cents)
    val sign = if (cents < 0) "-" else ""
    val dollars = abs / 100.0
    return when {
        dollars >= 1000 -> "$sign$${String.format("%.1f", dollars / 1000.0)}k"
        abs % 100L == 0L -> "$sign$${String.format("%,.0f", dollars)}"
        else -> "$sign$${String.format("%,.2f", dollars)}"
    }
}

/** Parse user dollar input like "12.34" or "12" into positive cents. */
fun parseDollarsToCents(text: String): Long? {
    val value = text.trim().replace(",", "").toDoubleOrNull() ?: return null
    if (value < 0) return null
    return Math.round(value * 100.0)
}
