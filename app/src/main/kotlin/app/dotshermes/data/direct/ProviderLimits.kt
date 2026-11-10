package app.dotshermes.data.direct

import kotlin.math.ceil
import java.security.MessageDigest

/** Dernière observation du fournisseur ; ce n'est ni un compteur local ni une promesse de quota gratuit. */
data class ProviderLimits(
    val observedAtMs: Long,
    val requestLimit: Long? = null, val requestsRemaining: Long? = null, val requestsResetAtMs: Long? = null,
    val tokenLimit: Long? = null, val tokensRemaining: Long? = null, val tokensResetAtMs: Long? = null,
) {
    fun exhaustedUntilMs(): Long = maxOf(
        requestsResetAtMs?.takeIf { requestsRemaining == 0L } ?: 0,
        tokensResetAtMs?.takeIf { tokensRemaining == 0L } ?: 0,
    )
}

data class DirectApiUsage(val provider: ApiProvider, val model: String, val limits: ProviderLimits?, val cooldownUntilMs: Long)

internal const val MAX_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000
internal fun cooldownSeconds(untilMs: Long, nowMs: Long): Long =
    if (untilMs <= nowMs) 0 else (untilMs - nowMs) / 1000 + if ((untilMs - nowMs) % 1000 == 0L) 0 else 1

/** Groq expose une durée composée (ex. 2m59.56s), pas un epoch ni un nombre de secondes nu. */
internal fun resetAtMs(value: String?, nowMs: Long): Long? {
    if (value.isNullOrBlank() || value.length > 100) return null
    val parts = Regex("(\\d+(?:\\.\\d+)?)(ms|s|m|h|d)").findAll(value).toList()
    if (parts.isEmpty() || parts.joinToString("") { it.value } != value) return null
    var millis = 0.0
    for (part in parts) millis += part.groupValues[1].toDoubleOrNull()?.times(when (part.groupValues[2]) {
        "ms" -> 1.0; "s" -> 1000.0; "m" -> 60_000.0; "h" -> 3_600_000.0; else -> 86_400_000.0
    }) ?: return null
    if (!millis.isFinite() || millis < 0 || millis > MAX_COOLDOWN_MS) return null
    return nowMs + ceil(millis).toLong()
}

internal fun groqLimits(header: (String) -> String?, nowMs: Long): ProviderLimits? {
    fun number(name: String) = header(name)?.toLongOrNull()?.takeIf { it >= 0 }
    val snapshot = ProviderLimits(nowMs,
        number("x-ratelimit-limit-requests"), number("x-ratelimit-remaining-requests"),
        resetAtMs(header("x-ratelimit-reset-requests"), nowMs),
        number("x-ratelimit-limit-tokens"), number("x-ratelimit-remaining-tokens"),
        resetAtMs(header("x-ratelimit-reset-tokens"), nowMs))
    return snapshot.takeUnless { it.requestLimit == null && it.requestsRemaining == null && it.tokenLimit == null && it.tokensRemaining == null }
}

/** Empreinte uniquement dans le fil chiffré : aucune clé ni empreinte dans les préférences ou l'interface. */
internal fun quotaScope(provider: ApiProvider, model: String, key: String): String {
    val bytes = "${provider.id}\u0000$model\u0000$key".toByteArray(Charsets.UTF_8)
    return try { MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
    finally { bytes.fill(0) }
}
