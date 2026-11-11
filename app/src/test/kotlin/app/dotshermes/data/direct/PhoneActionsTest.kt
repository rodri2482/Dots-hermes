package app.dotshermes.data.direct

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PhoneActionsTest {
    @Test fun `local times are read with or without seconds and zone`() {
        assertEquals(LocalDateTime.of(2026, 11, 12, 15, 0), PhoneActions.parseLocal("2026-11-12T15:00"))
        assertEquals(LocalDateTime.of(2026, 11, 12, 15, 0, 30), PhoneActions.parseLocal("2026-11-12T15:00:30Z"))
        assertNull(PhoneActions.parseLocal("tomorrow"))
    }

    @Test fun `timer durations read naturally`() {
        assertEquals("1 h 30 min", PhoneActions.formatDuration(5400))
        assertEquals("45 s", PhoneActions.formatDuration(45))
    }

    @Test fun `the system turn tells the model the date`() =
        assertEquals("Now: Wednesday 2026-11-11 10:40 (UTC).",
            nowLine(java.time.ZonedDateTime.of(2026, 11, 11, 10, 40, 0, 0, java.time.ZoneId.of("UTC"))))

    private class Fake(val name: String) : DirectTools {
        override fun definitions() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", name)))
        override fun label(call: ApiToolCall) = name
        override suspend fun run(call: ApiToolCall) = "ran $name"
    }

    @Test fun `combined tools merge definitions and route calls by name`() = runBlocking {
        val tools = CombinedTools(listOf(Fake("search_gmail"), Fake("set_alarm")))
        assertEquals(2, tools.definitions()!!.length())
        assertEquals("ran set_alarm", tools.run(ApiToolCall("1", "set_alarm", "{}")))
        assertTrue(tools.run(ApiToolCall("2", "nope", "{}")).contains("unknown"))
    }
}
