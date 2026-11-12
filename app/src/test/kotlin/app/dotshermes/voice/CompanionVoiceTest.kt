package app.dotshermes.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionVoiceTest {


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

}
