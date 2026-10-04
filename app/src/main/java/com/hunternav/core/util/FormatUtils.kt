package com.hunternav.core.util

/**
 * Navigation-oriented formatting. Distances: meters under 1 km, kilometers above, rounded for
 * at-a-glance readability. Durations: minutes under an hour, hours+minutes above.
 */
object FormatUtils {

    fun distance(meters: Double, metric: Boolean = true): String {
        if (!metric) {
            val feet = meters * 3.28084
            return if (feet < 1000) "${feet.roundToStep(25)} ft" else "${(meters / 1609.344).roundToDigits(if (meters < 160_934) 1 else 0)} mi"
        }
        return when {
            meters < 10 -> "<10 m"
            meters < 1000 -> "${meters.roundToStep(10)} m"
            meters < 10_000 -> "${(meters / 1000).roundToDigits(1)} km"
            else -> "${(meters / 1000).roundToStep(1)} km"
        }
    }

    fun duration(seconds: Double): String {
        val totalMinutes = (seconds / 60).toInt().coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> "${hours} h ${minutes.toString().padStart(2, '0')} min"
            totalMinutes >= 1 -> "$totalMinutes min"
            else -> "<1 min"
        }
    }

    /** Clock-style ETA string given remaining seconds and the current epoch millis. */
    fun eta(remainingSeconds: Double, nowEpochMs: Long): String {
        val arrival = java.util.Calendar.getInstance().apply {
            timeInMillis = nowEpochMs + (remainingSeconds * 1000).toLong()
        }
        val h = arrival.get(java.util.Calendar.HOUR_OF_DAY)
        val m = arrival.get(java.util.Calendar.MINUTE)
        return "%02d:%02d".format(h, m)
    }

    private fun Double.roundToStep(step: Int): Int {
        if (step <= 0) return toInt()
        return (this / step).toInt() * step
    }

    private fun Double.roundToDigits(digits: Int): Double =
        kotlin.math.round(this * Math.pow(10.0, digits.toDouble())) / Math.pow(10.0, digits.toDouble())
}
