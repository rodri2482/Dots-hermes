package app.dotshermes.data.gateway

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectDelayTest {

    /** Une réponse de handshake 429 comme celle du guard.py, avec ou sans en-tête Retry-After. */
    private fun response429(retryAfter: String? = null): Response {
        val builder = Response.Builder()
            .code(429)
            .message("Too Many Requests")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://gateway.example/dots/ws").build())
        retryAfter?.let { builder.header("Retry-After", it) }
        return builder.build()
    }

    @Test
    fun `retry-after is read in whole seconds`() {
        assertEquals(900L, retryAfterSeconds(response429("900")))
        assertEquals(1L, retryAfterSeconds(response429(" 1 ")))
    }

    @Test
    fun `a missing or unreadable retry-after waits nothing extra`() {
        assertEquals(0L, retryAfterSeconds(null))
        assertEquals(0L, retryAfterSeconds(response429(retryAfter = null)))
        assertEquals(0L, retryAfterSeconds(response429("0")))
        assertEquals(0L, retryAfterSeconds(response429("-5")))
        assertEquals(0L, retryAfterSeconds(response429("soon")))
    }

    @Test
    fun `without 429 the usual backoff applies`() {
        assertEquals(1_000L, reconnectDelayMs(0))
        assertEquals(2_000L, reconnectDelayMs(1))
        assertEquals(30_000L, reconnectDelayMs(9))
    }

    @Test
    fun `a longer retry-after wins over the backoff`() {
        assertEquals(900_000L, reconnectDelayMs(0, 900))
        assertEquals(900_000L, reconnectDelayMs(9, 900))
    }

    @Test
    fun `a short retry-after does not slow the backoff`() {
        assertEquals(5_000L, reconnectDelayMs(2, 1))
    }
}
