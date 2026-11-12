package app.dotshermes.data.direct

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class ProviderProtocolTest {
    private val turns = listOf(ApiTurn("user", "Hello"), ApiTurn("assistant", "Hi"), ApiTurn("user", "Again"))

    @Test fun `OpenAI requests use the chat format with tools and without server storage`() {
        val tools = org.json.JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "wikipedia")))
        val body = JSONObject(ProviderProtocol.requestBody(ApiProvider.OpenAI, "chosen-model", turns, tools = tools))
        assertEquals("chosen-model", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.getBoolean("store"))
        assertEquals(3, body.getJSONArray("messages").length())
        assertEquals("assistant", body.getJSONArray("messages").getJSONObject(1).getString("role"))
        assertEquals("Again", body.getJSONArray("messages").getJSONObject(2).getString("content"))
        assertEquals("wikipedia", body.getJSONArray("tools").getJSONObject(0).getJSONObject("function").getString("name"))
        assertFalse(body.has("input"))
    }

    @Test fun `Anthropic requests use Messages format with an explicit output bound`() {
        val body = JSONObject(ProviderProtocol.requestBody(ApiProvider.Anthropic, "chosen-model", turns))
        assertTrue(body.getBoolean("stream"))
        assertEquals(4096, body.getInt("max_tokens"))
        assertEquals("Hello", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun `OpenAI deltas are joined only until the stream is done`() {
        val chunks = mutableListOf<String>()
        ProviderProtocol.consume(ApiProvider.OpenAI, StringReader(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\r\n\r\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"lo 🌍\"}}]}\n\n" +
            "data: [DONE]\n\n"), chunks::add)
        assertEquals(listOf("Hel", "lo 🌍"), chunks)
    }

    @Test fun `Anthropic content block deltas require message_stop`() {
        val chunks = mutableListOf<String>()
        ProviderProtocol.consume(ApiProvider.Anthropic, StringReader(
            "data: {\"type\":\"message_start\",\"message\":{}}\n\n" +
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n" +
            "data: {\"type\":\"message_stop\"}\n\n"), chunks::add)
        assertEquals(listOf("Hello"), chunks)
    }

    @Test fun `truncated streaming preserves partial text but is not successful`() {
        val chunks = mutableListOf<String>()
        val failure = assertThrows(ApiException::class.java) {
            ProviderProtocol.consume(ApiProvider.OpenAI, StringReader(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n"), chunks::add)
        }
        assertEquals(ApiFailure.Interrupted, failure.failure)
        assertEquals(listOf("Hel"), chunks)
    }

    @Test fun `provider error bodies never become exception messages`() {
        val failure = assertThrows(ApiException::class.java) {
            ProviderProtocol.consume(ApiProvider.Anthropic, StringReader(
                "data: {\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"sensitive-fixture-text\"}}\n\n"), {})
        }
        assertEquals(ApiFailure.RateLimited, failure.failure)
        assertFalse(failure.toString().contains("sensitive-fixture-text"))
    }

    @Test fun `malformed stream events return a safe protocol error`() {
        val failure = assertThrows(ApiException::class.java) {
            ProviderProtocol.consume(ApiProvider.OpenAI, StringReader("data: {invalid sensitive-fixture-text}\n\n"), {})
        }
        assertEquals(ApiFailure.InvalidResponse, failure.failure)
        assertFalse(failure.toString().contains("sensitive-fixture-text"))
    }

    @Test fun `a token-limited response is distinguished from a completed reply`() {
        val failure = assertThrows(ApiException::class.java) {
            ProviderProtocol.consume(ApiProvider.OpenAI, StringReader(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n"), {})
        }
        assertEquals(ApiFailure.OutputLimit, failure.failure)
    }
}
