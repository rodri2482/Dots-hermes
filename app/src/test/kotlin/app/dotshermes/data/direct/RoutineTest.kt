package app.dotshermes.data.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class RoutineTest {
    private val zone = ZoneId.of("Africa/Lagos")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone)
    private fun routine(minute: Int, days: Set<Int>) = Routine("r", "a", "Mail", "Summarise", minute, days)

    @Test fun `weekday routine skips the weekend`() {
        // Vendredi 13 novembre 2026, 9 h : la routine de 8 h repart lundi.
        assertEquals(at(2026, 11, 16, 8, 0), routine(8 * 60, Routine.WEEKDAYS).nextRun(at(2026, 11, 13, 9, 0)))
    }

    @Test fun `later today when the time has not passed`() =
        assertEquals(at(2026, 11, 11, 18, 30), routine(18 * 60 + 30, Routine.ALL_DAYS).nextRun(at(2026, 11, 11, 12, 0)))

    @Test fun `days and times are read the way people write them`() {
        assertEquals(setOf(1, 3, 5), Routine.parseDays("mon, wed, fri"))
        assertEquals(Routine.WEEKDAYS, Routine.parseDays("weekdays"))
        assertEquals(8 * 60 + 5, Routine.parseTime("8:05"))
        assertNull(Routine.parseTime("25:00"))
    }

    @Test fun `schedule label reads naturally`() = assertEquals("weekdays · 08:00", routine(480, Routine.WEEKDAYS).scheduleLabel())
}
