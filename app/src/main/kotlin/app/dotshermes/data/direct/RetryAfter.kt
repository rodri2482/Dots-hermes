package app.dotshermes.data.direct

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Retry-After (secondes ou date HTTP) ne raccourcit jamais le backoff local. */
internal fun retryDelayMs(backoffMs: Long, retryAfter: String?, nowMs: Long): Long {
    val header = retryAfter?.trim().orEmpty()
    val serverDelay = if (header.isNotEmpty() && header.all { it in '0'..'9' }) {
        // Un délai immense doit rester une attente, jamais déborder vers une reconnexion immédiate.
        val seconds = header.toLongOrNull() ?: Long.MAX_VALUE
        if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds * 1_000
    } else {
        try {
            val instant = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (instant.toEpochMilli() - nowMs).coerceAtLeast(0)
        } catch (_: DateTimeParseException) {
            0L
        } catch (_: ArithmeticException) {
            0L
        }
    }
    return maxOf(backoffMs, serverDelay)
}
