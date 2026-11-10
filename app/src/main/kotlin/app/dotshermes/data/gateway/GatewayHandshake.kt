package app.dotshermes.data.gateway

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface HandshakeOutcome {
    data class Finished(val error: String) : HandshakeOutcome
    data class RateLimited(val waitMs: Long) : HandshakeOutcome
}

/** Attente initiale, extraite pour vérifier le cycle de vie sans dépendre d'Android. */
internal suspend fun awaitGatewayHandshake(
    outcomes: ReceiveChannel<HandshakeOutcome>, timeoutError: String, timeoutMs: Long = 15_000,
): String {
    var windowMs = timeoutMs
    while (true) {
        when (val outcome = withTimeoutOrNull(windowMs) { outcomes.receive() } ?: return timeoutError) {
            is HandshakeOutcome.Finished -> return outcome.error
            is HandshakeOutcome.RateLimited -> {
                // Le prochain essai garde ses 15 secondes entières après le délai imposé.
                windowMs = if (outcome.waitMs > Long.MAX_VALUE - timeoutMs) Long.MAX_VALUE else outcome.waitMs + timeoutMs
            }
        }
    }
}
