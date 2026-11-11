package app.dotshermes.data.direct

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AttendanceTest {
    private val zone = ZoneId.of("Africa/Lagos")
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 12, h, m).atZone(zone).toInstant().toEpochMilli()
    private fun post(sender: String, h: Int, m: Int) = ChatPost("com.whatsapp", "Team", sender, "present", at(h, m), true)

    @Test fun `everyone once, in order of their first post, without you`() {
        val list = ChatLog.attendance(listOf(
            post("Tola", 7, 41), post("Ada", 7, 5), post("Tola", 7, 59), post("You", 7, 30), post("Musa", 7, 50),
        ), zone)
        assertEquals(listOf("Ada" to "07:05", "Tola" to "07:41", "Musa" to "07:50"), list)
    }

    @Test fun `nobody yet`() = assertEquals(emptyList<Pair<String, String>>(), ChatLog.attendance(emptyList(), zone))
}
