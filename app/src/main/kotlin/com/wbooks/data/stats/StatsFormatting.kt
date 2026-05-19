package com.wbooks.data.stats

/**
 * Compact "Xm" / "Xh Ym" rendering of a minute count. Shared by the reading-time
 * tile, the complication, and the Tools-page ETA so the three surfaces don't
 * drift on whether to include a space between the hours and minutes.
 */
fun formatMinutes(totalMinutes: Long): String {
    val safe = totalMinutes.coerceAtLeast(0)
    val hours = safe / 60
    val minutes = safe % 60
    return when {
        hours <= 0 -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}

/** Convenience: format a millisecond duration. */
fun formatDurationMs(ms: Long): String = formatMinutes(ms / 60_000L)
