package app.dotshermes.data.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutineCheckTest {
    private val known = { name: String -> if (name.equals("team", true)) "Team" else null }
    private fun check(trigger: String, time: String = "08:00", days: String = "daily", every: Int = 0, chat: String = "",
        prompt: String = "Say hi", allow: String = "", notif: Boolean = true) =
        RoutineCheck.check(trigger, time, days, every, chat, prompt, allow, known, notif)

    @Test fun `daily attendance at 8 gets whatsapp permission added`() {
        val r = check("time", prompt = "Post today's attendance in the Team WhatsApp group")
        assertNull(r.error)
        assertEquals(8 * 60, r.minute)
        assertEquals(Routine.ALL_DAYS, r.days)
        assertTrue("whatsapp" in r.allowed)
    }

    @Test fun `whatsapp routines need notification access`() =
        assertTrue(check("time", prompt = "attendance in my WhatsApp group", notif = false).error!!.contains("notification access"))

    @Test fun `bad time, bad days, too frequent`() {
        assertTrue(check("time", time = "25:00").error!!.contains("HH:MM"))
        assertTrue(check("time", days = "someday").error!!.contains("days"))
        assertTrue(check("every", every = 5).error!!.contains("15"))
        assertEquals(60, check("every", every = 60).everyMinutes)
    }

    @Test fun `message trigger needs a group Claydot has seen`() {
        assertTrue(check("message", chat = "Family").error!!.contains("no messages seen"))
        assertEquals("Team", check("message", chat = "team").chat)
    }

    @Test fun `every-N routines come back after their interval`() {
        val now = ZonedDateTime.of(2026, 11, 12, 9, 0, 0, 0, ZoneId.of("Africa/Lagos"))
        val r = Routine("r", "a", "n", "p", 0, Routine.ALL_DAYS, trigger = Routine.EVERY, everyMinutes = 30)
        assertEquals(now.plusMinutes(30), r.nextRun(now))
        assertNull(r.copy(trigger = Routine.MESSAGE).nextRun(now))
        assertEquals("every 30 min", r.scheduleLabel())
    }
}
