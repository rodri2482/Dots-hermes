package app.dotshermes.data.gateway

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayHandshakeTest {
    @Test fun `initial rate limit waits for welcome instead of completing a failed connection`() = runBlocking {
        val outcomes = Channel<HandshakeOutcome>(Channel.UNLIMITED)
        outcomes.send(HandshakeOutcome.RateLimited(900_000))
        outcomes.send(HandshakeOutcome.Finished(""))
        assertEquals("", awaitGatewayHandshake(outcomes, "timeout"))
    }

    @Test fun `retry wait does not consume the timeout of the next handshake`() = runBlocking {
        val outcomes = Channel<HandshakeOutcome>(Channel.UNLIMITED)
        outcomes.send(HandshakeOutcome.RateLimited(1_000))
        val response = launch { delay(200); outcomes.send(HandshakeOutcome.Finished("")) }
        assertEquals("", awaitGatewayHandshake(outcomes, "timeout", timeoutMs = 50))
        response.join()
    }

    @Test fun `disconnect ends a rate limited initial attempt immediately`() = runBlocking {
        val outcomes = Channel<HandshakeOutcome>(Channel.UNLIMITED)
        outcomes.send(HandshakeOutcome.RateLimited(Long.MAX_VALUE))
        val pending = async { awaitGatewayHandshake(outcomes, "timeout") }
        outcomes.send(HandshakeOutcome.Finished("cancelled"))
        assertEquals("cancelled", pending.await())
    }

    @Test fun `a silent gateway still times out`() = runBlocking {
        assertEquals("timeout", awaitGatewayHandshake(Channel(Channel.UNLIMITED), "timeout", timeoutMs = 1))
    }
}
