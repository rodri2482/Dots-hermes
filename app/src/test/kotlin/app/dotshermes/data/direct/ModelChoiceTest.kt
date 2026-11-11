package app.dotshermes.data.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelChoiceTest {
    private val groqList = listOf("whisper-large-v3-turbo", "meta-llama/llama-guard-4-12b", "playai-tts",
        "llama-3.1-8b-instant", "llama-3.3-70b-versatile", "openai/gpt-oss-120b")

    @Test fun `groq keeps only models that can chat`() =
        assertEquals(listOf("llama-3.1-8b-instant", "llama-3.3-70b-versatile", "openai/gpt-oss-120b"), chatModels(ApiProvider.Groq, groqList))

    @Test fun `groq picks the first preferred model the account has`() =
        assertEquals("openai/gpt-oss-120b", defaultModel(ApiProvider.Groq, groqList))

    @Test fun `groq falls back to a chat model when none of the preferred ones exist`() =
        assertEquals("a-new-model", defaultModel(ApiProvider.Groq, listOf("whisper-large-v3", "z-model", "a-new-model")))

    @Test fun `groq gives up when only non chat models are listed`() =
        assertNull(defaultModel(ApiProvider.Groq, listOf("whisper-large-v3", "playai-tts")))

    @Test fun `other providers are never chosen automatically`() {
        assertNull(defaultModel(ApiProvider.OpenAI, listOf("gpt-x")))
        assertEquals(listOf("gpt-x", "tts-1"), chatModels(ApiProvider.OpenAI, listOf("gpt-x", "tts-1")))
    }
}

class PersonaProtocolTest {
    private val turns = listOf(ApiTurn("system", "Be kind."), ApiTurn("user", "Hi"))

    @Test fun `groq gets the persona as the first system message`() {
        val body = org.json.JSONObject(ProviderProtocol.requestBody(ApiProvider.Groq, "m", turns))
        val first = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("system", first.getString("role"))
        assertEquals("Be kind.", first.getString("content"))
    }

    @Test fun `claude gets the persona in the system field, not in messages`() {
        val body = org.json.JSONObject(ProviderProtocol.requestBody(ApiProvider.Anthropic, "m", turns))
        assertEquals("Be kind.", body.getString("system"))
        assertEquals(1, body.getJSONArray("messages").length())
    }
}

class ReplyFooterTest {
    @Test fun `footer drops the vendor prefix and rounds the time`() {
        assertEquals("gpt-oss-120b · 1.4 s", replyFooter("openai/gpt-oss-120b", 1_420))
        assertEquals("llama-3.1-8b-instant · 12 s", replyFooter("llama-3.1-8b-instant", 12_400))
        assertEquals("gpt-oss-120b · 412 tokens · 1.4 s", replyFooter("openai/gpt-oss-120b", 1_420, 412))
        assertEquals("m · 12.3K tokens · 2.0 s", replyFooter("m", 2_000, 12_345))
    }
}

class TokenUsageTest {
    @Test fun `groq usage chunk reports the total tokens`() {
        var tokens = 0L
        val stream = "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}}\n\n" +
            "data: [DONE]\n\n"
        ProviderProtocol.consumeCounted(ApiProvider.Groq, java.io.StringReader(stream), { tokens = it }) {}
        assertEquals(25L, tokens)
    }

    @Test fun `usage window groups replies by hour and skips failed turns`() {
        val h = 3_600_000L
        val entries = listOf(
            DirectEntry("u1", "user", "a", 10 * h, DirectEntryState.Complete),
            DirectEntry("a1", "assistant", "b", 10 * h + 5, DirectEntryState.Complete, "u1", tokens = 40),
            DirectEntry("u2", "user", "c", 11 * h, DirectEntryState.Failed),
        )
        val window = usageWindow(entries, java.time.Instant.ofEpochMilli(0), java.time.Instant.ofEpochMilli(24 * h))
        assertEquals(listOf(app.dotshermes.data.model.UsageHour(10, 40, 2)), window.hours)
    }
}

class VisionTest {
    @Test fun `the strongest vision model the account has is picked`() {
        assertEquals("meta-llama/llama-4-scout-17b-16e-instruct",
            visionModel(listOf("llama-3.3-70b-versatile", "meta-llama/llama-4-scout-17b-16e-instruct")))
        assertNull(visionModel(listOf("llama-3.3-70b-versatile", "whisper-large-v3")))
    }

    @Test fun `a photo goes into the groq message as text plus image_url`() {
        val body = org.json.JSONObject(ProviderProtocol.requestBody(ApiProvider.Groq, "m",
            listOf(ApiTurn("user", "What is this?", "data:image/jpeg;base64,AAAA"))))
        val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("data:image/jpeg;base64,AAAA", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }
}
