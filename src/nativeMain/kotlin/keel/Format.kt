package keel

import kotlin.time.Duration

fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.inWholeMilliseconds / 100 / 10.0
    val minutes = (totalSeconds / 60).toInt()
    val seconds = totalSeconds - minutes * 60
    val secStr = "${(seconds * 10).toInt() / 10.0}s"
    return if (minutes > 0) "${minutes}m $secStr" else secStr
}
