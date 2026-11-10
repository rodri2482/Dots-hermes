package app.dotshermes.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionVoiceTest {
    @Test fun `spoken text drops markdown, code and links`() {
        val said = CompanionVoice.speakable("## Hi **there**\n- see [docs](https://x.y)\n```kotlin\nval a = 1\n```\nUse `ls` now.")
        assertEquals("Hi there see docs Use ls now.", said)
    }

    @Test fun `long replies are cut at a word`() {
        val said = CompanionVoice.speakable("word ".repeat(400))
        assertTrue(said.length <= 1201)
        assertTrue(said.endsWith("…"))
    }

    @Test fun `persona carries the name, the tone and the spoken hint`() {
        val cheeky = personaPrompt(Personality.Cheeky, "Groq", spoken = true)
        assertTrue(cheeky.contains("You are Groq"))
        assertTrue(cheeky.contains("cheeky"))
        assertTrue(cheeky.contains("read aloud"))
        assertFalse(personaPrompt(Personality.Calm, "Dot", spoken = false).contains("read aloud"))
    }
}

class ClaydotPersonaTest {
    @Test fun `default name gets the full Claydot character`() {
        val prompt = personaPrompt(Personality.Playful, "Claydot", spoken = false)
        assertTrue(prompt.startsWith("You are Claydot."))
        assertTrue(prompt.contains("golden helmet"))
        assertTrue(prompt.contains("never invent facts"))
    }

    @Test fun `a renamed companion keeps the character under its new name`() {
        assertTrue(personaPrompt(Personality.Calm, "Pebble", spoken = false).startsWith("You are Pebble, a Claydot companion"))
    }

    @Test fun `the default voice is the first preset`() {
        assertEquals("Kaia", VoicePresets.first().name)
        assertTrue(VoicePresets.all { it.id.matches(Regex("[0-9a-f]{32}")) })
    }
}
