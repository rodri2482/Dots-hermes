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
