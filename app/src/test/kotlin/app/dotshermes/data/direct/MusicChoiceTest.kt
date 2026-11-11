package app.dotshermes.data.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicChoiceTest {
    private val spotify = PhoneActions.MUSIC_APPS.first { it.name == "Spotify" }
    private val youtube = PhoneActions.MUSIC_APPS.first { it.name == "YouTube" }
    private val ytMusic = PhoneActions.MUSIC_APPS.first { it.name == "YouTube Music" }
    private val installed = listOf(spotify, ytMusic, youtube)

    @Test fun `no music app installed`() = assertNull(PhoneActions.chooseMusicApp(emptyList(), "", null))

    @Test fun `first installed app by default`() = assertEquals(spotify, PhoneActions.chooseMusicApp(installed, "", null))

    @Test fun `remembered choice wins over the default`() =
        assertEquals(youtube, PhoneActions.chooseMusicApp(installed, "", youtube.packageName))

    @Test fun `the app the user names wins, exact name before a partial match`() {
        assertEquals(youtube, PhoneActions.chooseMusicApp(installed, "youtube", spotify.packageName))
        assertEquals(ytMusic, PhoneActions.chooseMusicApp(installed, "YouTube Music", null))
    }

    @Test fun `an app that is not installed falls back`() =
        assertEquals(spotify, PhoneActions.chooseMusicApp(installed, "Boomplay", null))
}

class PlayTargetTest {
    // Écran de résultats façon Spotify : barre de recherche en haut, artiste, puis les titres, mini-lecteur en bas.
    private val results = listOf(
        ScreenElement(1, "Unavailable Davido", "edit", "0,100,1080,200"),
        ScreenElement(2, "Davido · Artist", "button", "0,400,1080,560"),
        ScreenElement(3, "Unavailable · Davido, Musa Keys", "button", "0,600,1080,760"),
        ScreenElement(4, "Fall · Davido", "button", "0,800,1080,960"),
        ScreenElement(5, "Play", "button", "900,2100,1000,2200"),
    )

    @Test fun `the song row that matches most words, not the search box or the mini player`() =
        assertEquals(3, PhoneActions.pickPlayTarget(results, "Unavailable Davido", preferPlayButton = false)?.id)

    @Test fun `on the opened page, the play button`() =
        assertEquals(5, PhoneActions.pickPlayTarget(results, "Davido", preferPlayButton = true)?.id)

    @Test fun `nothing that matches and no play button`() =
        assertNull(PhoneActions.pickPlayTarget(results.take(1) + ScreenElement(9, "Home", "button", "0,2200,100,2300"), "Burna Boy", false))
}

class TimerSecondsTest {
    private fun t(json: String) = PhoneActions.timerSeconds(org.json.JSONObject(json))
    @Test fun `seconds, minutes, hours and plain text`() {
        assertEquals(90, t("""{"seconds":90}"""))
        assertEquals(60, t("""{"minutes":1}"""))
        assertEquals(5400, t("""{"hours":1.5}"""))
        assertEquals(60, t("""{"duration":"1 minute"}"""))
        assertEquals(150, t("""{"duration":"2 min 30 s"}"""))
        assertEquals(60, t("""{"seconds":"60"}"""))
    }
}
