package app.dotshermes.data.direct

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mémoire du modèle du téléphone : contexte borné par palier, outils décrits brièvement. */
class LocalMemoryTest {
    @Test fun `each phone tier reserves a bounded context and releases the model sooner`() {
        assertEquals(listOf(2_048, 3_072, 4_096), DeviceTier.Level.entries.map { it.contextTokens })
        assertTrue(DeviceTier.Level.entries.all { it.idleReleaseMs <= 3 * 60_000L })
    }

    @Test fun `tool descriptions for the phone model are cut to one short sentence`() {
        val long = JSONObject().put("type", "function").put("function", JSONObject().put("name", "play_music")
            .put("description", "Play a song in a music app. It starts playing right away. Use it whenever the user asks.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("query", JSONObject().put("type", "string").put("description", "x".repeat(200))))))
        val short = localToolset(JSONArray().put(long)).getJSONObject(0).getJSONObject("function")
        assertEquals("Play a song in a music app", short.getString("description"))
        assertEquals(60, short.getJSONObject("parameters").getJSONObject("properties").getJSONObject("query").getString("description").length)
        // L'original (envoyé à Groq) ne change pas.
        assertTrue(long.getJSONObject("function").getString("description").endsWith("asks."))
    }
}
