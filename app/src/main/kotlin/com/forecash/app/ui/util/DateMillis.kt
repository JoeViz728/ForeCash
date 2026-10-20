package com.forecash.app.ui.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Date-only millis in UTC — avoids off-by-one from local midnight. */
fun LocalDate.toEpochMillisUtc(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun Long.toLocalDateUtc(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
