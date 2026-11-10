package app.dotshermes.data.gateway

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class GatewayRetryTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli()

    @Test fun `server wait takes precedence over the local backoff`() {
        assertEquals(900_000L, gatewayRetryDelayMs(1_000, "900", now))
    }

    @Test fun `short server wait never shortens the local backoff`() {
        assertEquals(30_000L, gatewayRetryDelayMs(30_000, "2", now))
        assertEquals(5_000L, gatewayRetryDelayMs(5_000, "0", now))
    }

    @Test fun `HTTP date becomes a delay relative to receipt time`() {
        assertEquals(120_000L, gatewayRetryDelayMs(1_000, "Tue, 10 Nov 2026 12:02:00 GMT", now))
        assertEquals(5_000L, gatewayRetryDelayMs(5_000, "Tue, 10 Nov 2026 11:00:00 GMT", now))
    }

    @Test fun `missing or malformed headers retain the normal backoff`() {
        listOf(null, "", " ", "invalid", "-1", "1.5", "+10").forEach {
            assertEquals(10_000L, gatewayRetryDelayMs(10_000, it, now))
        }
    }

    @Test fun `large second values cannot overflow into an immediate retry`() {
        assertEquals(Long.MAX_VALUE, gatewayRetryDelayMs(1_000, "9223372036854775807", now))
        assertEquals(Long.MAX_VALUE, gatewayRetryDelayMs(1_000, "9999999999999999999999999", now))
    }

    @Test fun `surrounding whitespace is tolerated`() {
        assertEquals(120_000L, gatewayRetryDelayMs(1_000, " 120 ", now))
    }
}
