package app.dotshermes.data.direct

import org.junit.Assert.*
import org.junit.Test

class ProviderLimitsTest {
    @Test fun `reset duration supports fractional mixed units and rounds up`() {
        assertEquals(180_560L, resetAtMs("2m59.56s", 1000))
        assertEquals(1001L, resetAtMs("0.1ms", 1000))
        assertEquals(86_401_000L, resetAtMs("1d", 1000))
    }
    @Test fun `unknown malformed negative and excessive durations are not invented`() {
        for (value in listOf(null, "", "123", "-1s", "1e3s", "garbage", "8d", "999999999999999999999999h")) {
            assertNull(value, resetAtMs(value, 1000))
        }
        assertNull(groqLimits({ null }, 1000))
    }
    @Test fun `only exhausted windows hold the client and longest reset wins`() {
        val headers = mapOf("x-ratelimit-limit-requests" to "1000", "x-ratelimit-remaining-requests" to "0",
            "x-ratelimit-reset-requests" to "1h", "x-ratelimit-limit-tokens" to "8000",
            "x-ratelimit-remaining-tokens" to "0", "x-ratelimit-reset-tokens" to "2s")
        val limits = requireNotNull(groqLimits(headers::get, 1000))
        assertEquals(3_601_000L, limits.exhaustedUntilMs())
        assertEquals(0L, limits.copy(requestsRemaining = 1, tokensRemaining = 1).exhaustedUntilMs())
        assertEquals(2L, cooldownSeconds(2001, 1000))
        assertEquals(0L, cooldownSeconds(1000, 1001))
    }
}
