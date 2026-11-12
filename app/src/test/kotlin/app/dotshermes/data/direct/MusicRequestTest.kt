package app.dotshermes.data.direct

import org.junit.Assert.assertEquals
import org.junit.Test

/** « Mets … sur Spotify » : reconnu par l'app, pour que la musique parte même si le petit modèle n'appelle pas l'outil. */
class MusicRequestTest {
    @Test fun `music requests are recognised with the app the user named`() {
        assertEquals("Blinding Lights by The Weeknd" to "Spotify", musicRequest("Play Blinding Lights by The Weeknd on Spotify"))
        assertEquals("Unavailable Davido" to null, musicRequest("play Unavailable Davido"))
        assertEquals("Burna Boy" to "YouTube Music", musicRequest("Can you play Burna Boy on YouTube Music please?"))
        assertEquals("du Aya Nakamura" to "Spotify", musicRequest("Mets du Aya Nakamura sur Spotify"))
        assertEquals("davido unavailable" to "Spotify", musicRequest("Open spotify and play me davido unavailable"))
        assertEquals("Tems" to null, musicRequest("I want to listen to Tems"))
    }

    @Test fun `short player commands go straight to the player`() {
        assertEquals("play", mediaRequest("play it"))
        assertEquals("pause", mediaRequest("Pause the music please"))
        assertEquals("next", mediaRequest("next song!"))
        assertEquals("volume_up", mediaRequest("Monte le son"))
        assertEquals(null, mediaRequest("play it cool when you talk to her"))
        assertEquals(null, mediaRequest("stop"))
    }

    @Test fun `timers, alarms and searches are recognised for every phone model`() {
        assertEquals("set_timer" to "5 minutes", quickToolRequest("Set a timer for 5 minutes")?.let { it.first to it.second.optString("duration") })
        assertEquals(listOf(19, 30), quickToolRequest("set an alarm for 7:30 pm")!!.second.let { listOf(it.optInt("hour"), it.optInt("minute")) })
        assertEquals(listOf(7, 0), quickToolRequest("Wake me up at 7 am")!!.second.let { listOf(it.optInt("hour"), it.optInt("minute")) })
        assertEquals("gta vi release date", quickToolRequest("search the web for GTA VI release date")!!.second.optString("query"))
        assertEquals(null, quickToolRequest("set a timer"))
        assertEquals(null, quickToolRequest("what time is it"))
    }

    @Test fun `requests to talk to another agent name it, for models without tools`() {
        val others = listOf(Triple("g", "Claydot (gpt-oss-120b)", "gpt-oss-120b · Groq"), Triple("p", "Claydot (Gemma 4 E2B)", "Gemma 4 E2B · On this phone"))
        assertEquals(Triple("Claydot (gpt-oss-120b)", "what time it is in Tokyo", "message"), agentRequest("Ask Groq what time it is in Tokyo", others))
        assertEquals(Triple("Claydot (Gemma 4 E2B)", "for a fun fact", "call"), agentRequest("Call the phone agent and ask it for a fun fact", others))
        assertEquals("call", agentRequest("call the other agent", others)?.third)
        assertEquals(null, agentRequest("Ask me anything", others))
        assertEquals(null, agentRequest("Ask Groq something", emptyList()))
    }

    @Test fun `open requests name the app`() {
        assertEquals("tiktok", appRequest("open tiktok"))
        assertEquals("WhatsApp", appRequest("Please open the WhatsApp app"))
        assertEquals("Calculatrice", appRequest("ouvre la Calculatrice"))
        assertEquals(null, appRequest("open it"))
        assertEquals(null, appRequest("Open your heart to the people you meet every day"))
    }

    @Test fun `other uses of play are left to the model`() {
        assertEquals(null, musicRequest("Play chess with me"))
        assertEquals(null, musicRequest("play a game"))
        assertEquals(null, musicRequest("Who plays in the World Cup?"))
        assertEquals(null, musicRequest("pause"))
        assertEquals(null, musicRequest("play"))
    }
}
