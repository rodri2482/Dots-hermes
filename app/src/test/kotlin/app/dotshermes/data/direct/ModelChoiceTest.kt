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
    }
}
