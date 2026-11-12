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
    }

    @Test fun `other uses of play are left to the model`() {
        assertEquals(null, musicRequest("Play chess with me"))
        assertEquals(null, musicRequest("play a game"))
        assertEquals(null, musicRequest("Who plays in the World Cup?"))
        assertEquals(null, musicRequest("pause"))
        assertEquals(null, musicRequest("play"))
    }
}
