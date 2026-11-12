package app.dotshermes.data.direct

import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.MessageContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Réponses avec images : images venues de l'outil seulement, gardées avec la réponse, texte seul hors ligne. */
class ImagesTest {
    private class Storage : DirectAccountStorage {
        var transcript = DirectTranscript()
        override fun key(accountId: String) = "fixture-key"
        override fun saveKey(accountId: String, key: String) {}
        override fun load(accountId: String) = transcript
        override fun save(accountId: String, transcript: DirectTranscript) { this.transcript = transcript }
        override fun removeKey(accountId: String) {}
        override fun remove(accountId: String) {}
    }

    /** Appelle show_images (deux fois si [twice]), puis répond — avec une image markdown inventée, qui doit disparaître. */
    private class Asker(private val twice: Boolean = false) : ProviderClient {
        val toolResults = mutableListOf<String>()
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            val rounds = extra.count { it.optString("role") == "tool" }
            if (rounds > 0) toolResults += extra.last().optString("content")
            if (rounds == 0 || (twice && rounds == 1)) return listOf(ApiToolCall("i$rounds", "show_images", """{"query":"Naruto","count":3}"""))
            onDelta("Naruto is a ninja.\n\n![naruto](https://invented.example/naruto.jpg)")
            return emptyList()
        }
    }

    /** Une recherche d'images simulée : [n] images (quelques octets chacune), légende et page source. */
    private class Pictures(private val n: Int) : DirectTools {
        override fun definitions() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "show_images")))
        override fun label(call: ApiToolCall) = ToolKeys.of("image")
        override suspend fun run(call: ApiToolCall): String {
            val found = JSONArray()
            repeat(n) { i ->
                found.put(JSONObject().put("jpeg", java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, i.toByte())))
                    .put("caption", "Naruto $i").put("source", if (i == 0) "https://naruto.fandom.com/wiki/Naruto" else "javascript:alert(1)"))
            }
            return JSONObject().put("status", "shown").put(WebTools.IMAGES_KEY, found).toString()
        }
    }

    private fun answer(client: ProviderClient, tools: DirectTools, check: (Storage, DirectChatRepository, File) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = Files.createTempDirectory("claydot-pics").toFile()
        try {
            val storage = Storage()
            val repo = DirectChatRepository("a", ApiProvider.Groq, "m", scope, storage, client, toolbox = { tools }, imageDir = dir)
            repo.restore(); repo.send("Who is Naruto?")
            withTimeout(10_000) {
                while (storage.transcript.messages.lastOrNull()?.state != DirectEntryState.Complete || repo.activity.value != AgentActivity.Idle) delay(20)
            }
            check(storage, repo, dir)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun `pictures from the search tool appear under the answer with captions and sources`() {
        val client = Asker()
        answer(client, Pictures(2)) { storage, repo, dir ->
            val entry = storage.transcript.messages.last()
            assertEquals(listOf("Naruto 0", "Naruto 1"), entry.images.map { it.caption })
            assertTrue(entry.images.all { File(dir, it.name).isFile })
            // Seule une adresse http(s) sert de lien source.
            assertEquals(listOf("https://naruto.fandom.com/wiki/Naruto", ""), entry.images.map { it.source })
            val content = repo.messages.value.last().content as MessageContent.Rich
            assertEquals("Naruto is a ninja.", content.text)          // l'image markdown inventée est retirée
            assertEquals(2, content.images.size)
            // Le modèle ne reçoit pas les images elles-mêmes, seulement leur mention.
            assertFalse(client.toolResults.single().contains(WebTools.IMAGES_KEY))
            assertTrue(client.toolResults.single().contains("2 picture(s) are now shown"))
        }
    }

    @Test fun `an answer keeps three pictures at most`() {
        answer(Asker(twice = true), Pictures(2)) { storage, _, dir ->
            assertEquals(3, storage.transcript.messages.last().images.size)
            assertEquals(3, dir.listFiles()!!.count { it.name.startsWith("pic-") })
        }
    }

    @Test fun `saved pictures only accept safe file names and web sources`() {
        assertEquals(null, storedImageOf(JSONObject().put("name", "../../secret.jpg")))
        assertEquals("", storedImageOf(JSONObject().put("name", "pic-a1.jpg").put("source", "file:///sdcard/x"))!!.source)
        assertEquals("https://x.org/p", storedImageOf(JSONObject().put("name", "pic-a1.jpg").put("source", "https://x.org/p"))!!.source)
    }

    @Test fun `offline, the picture search says so and the reply stays in text`() = runBlocking {
        val offline = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        val out = WebTools(offline).run(ApiToolCall("x", "show_images", """{"query":"Naruto"}"""))
        assertTrue(out.contains("Pictures could not be retrieved"))
        assertFalse(out.contains(WebTools.IMAGES_KEY))
    }

    /** Un modèle qui répond sans jamais appeler d'outil (cas habituel du modèle du téléphone). */
    private class Plain : ProviderClient {
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            onDelta("Naruto is a ninja."); return emptyList()
        }
    }

    @Test fun `pictures come even when the model never asks for them`() {
        answer(Plain(), Pictures(2)) { storage, _, _ ->
            runBlocking { withTimeout(5_000) { while (storage.transcript.messages.last().images.isEmpty()) delay(20) } }
            assertEquals(listOf("Naruto 0", "Naruto 1"), storage.transcript.messages.last().images.map { it.caption })
        }
    }

    @Test fun `only questions about someone or something look for pictures`() {
        assertEquals("Naruto", pictureQuery("Who is Naruto?"))
        assertEquals("Naruto Uzumaki", pictureQuery("who was Naruto Uzumaki"))
        assertEquals("tour Eiffel", pictureQuery("Montre-moi la tour Eiffel"))
        assertEquals("a red panda", pictureQuery("show me a picture of a red panda"))
        assertEquals("Lionel Messi", pictureQuery("Qui est Lionel Messi ?"))
        assertEquals(null, pictureQuery("Who are you?"))
        assertEquals(null, pictureQuery("who is calling me"))
        assertEquals(null, pictureQuery("When is GTA VI releasing?"))
        assertEquals(null, pictureQuery("Post today's attendance in my WhatsApp group"))
    }

    @Test fun `replies are tidied for a phone screen`() {
        assertEquals("Steps:\n\n- one\n- two\n\n1. first\n2. second", tidyMarkdown("Steps:\n• one\n• two\n\n1) first\n2) second"))
        assertEquals("A **bold** word and a stray one", tidyMarkdown("A **bold** word and a stray** one"))
        assertEquals("Line one\nLine two", tidyMarkdown("Line one<br>Line two   "))
        assertEquals("```\n• keep() // code\n```", tidyMarkdown("```\n• keep() // code\n```"))
    }

    @Test fun `general-knowledge questions name their topic`() {
        assertEquals("black hole", factQuery("What is a black hole?"))
        assertEquals("Lagos", factQuery("Tell me about Lagos"))
        assertEquals(null, factQuery("What is your name?"))
        assertEquals(null, factQuery("Play Burna Boy"))
    }

    @Test fun `broken reaction tags from a small model never show`() {
        assertEquals("Minecraft is a top pick.\n\nCheck the latest updates.",
            withoutMarkdownImages("Minecraft is a top pick.\n[\uD83C\uDF89 That's a solid pick!]\n\nCheck the latest updates.\n[\uD83E\uDD14 Need help with anything else?]]"))
        assertEquals("Hi there", withoutMarkdownImages("Hi [[react:\uD83D\uDE02]]there").replace("  ", " "))
        assertEquals("See [the docs](https://x.org) for more", withoutMarkdownImages("See [the docs](https://x.org) for more"))
    }

    @Test fun `markdown images written by the model never show`() {
        assertEquals("Hello\n\nthere", withoutMarkdownImages("Hello\n\n![x](https://made.up/x.png)\n\n\nthere"))
        assertEquals("No image here", withoutMarkdownImages("No image here"))
    }
}
