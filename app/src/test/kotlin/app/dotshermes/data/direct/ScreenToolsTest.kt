package app.dotshermes.data.direct

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenToolsTest {
    private class FakeScreen(var pkg: String = "com.example.notes") : ScreenDriver {
        var on = true
        val actions = mutableListOf<String>()
        override fun connected() = on
        override fun snapshot() = ScreenSnapshot(pkg, listOf(
            ScreenElement(1, "New note", "button", "0,0,10,10"),
            ScreenElement(2, "", "edit", "0,20,10,30", password = true),
            ScreenElement(3, "Title", "edit", "0,40,10,50"),
        ))
        override fun tap(id: Int) = (id in 1..3).also { if (it) actions += "tap $id" }
        override fun type(id: Int, text: String) = true.also { actions += "type $id $text" }
        override fun scroll(down: Boolean) = true.also { actions += "scroll $down" }
        override fun press(key: String) = (key == "back").also { if (it) actions += "press $key" }
        override fun launch(name: String) = "Notes".also { actions += "launch $name" }
        override fun longPress(id: Int) = (id in 1..3).also { if (it) actions += "hold $id" }
        override fun swipe(direction: String) = (direction in setOf("up", "down", "left", "right")).also { if (it) actions += "swipe $direction" }
        override fun openUrl(url: String) = true.also { actions += "open $url" }
        var shown = mutableListOf<Boolean>()
        override fun showActive(on: Boolean) { shown += on }
    }

    private var now = 1_000L
    private fun tools(screen: FakeScreen, answer: Boolean = true, asked: MutableList<String> = mutableListOf()) =
        ScreenTools(screen, enabled = { true }, confirm = { title, _ -> asked += title; answer }, ownPackage = "app.claydot",
            clockMs = { now }, settleMs = 0, grantMs = 60_000, consent = { "consent" })

    private fun call(name: String, args: String = "{}") = ApiToolCall("c", name, args)

    @Test fun `no tools while the accessibility service is off`() {
        val screen = FakeScreen().apply { on = false }
        assertNull(tools(screen).definitions())
    }

    @Test fun `nothing happens before the user approves the task`() = runBlocking {
        val screen = FakeScreen()
        val t = tools(screen)
        assertTrue(t.run(call("tap", """{"id":1}""")).contains("start_screen_control"))
        // Voir est permis sans accord ; agir ne l'est pas.
        assertTrue(JSONObject(t.run(call("read_screen"))).has("elements"))
        assertTrue(screen.actions.isEmpty())
    }

    @Test fun `declined task stays locked`() = runBlocking {
        val screen = FakeScreen(); val asked = mutableListOf<String>()
        val t = tools(screen, answer = false, asked = asked)
        assertEquals("declined", JSONObject(t.run(call("start_screen_control", """{"goal":"Write a note"}"""))).getString("status"))
        assertEquals(listOf(ToolKeys.title("screen", "Write a note")), asked)
        assertTrue(t.run(call("tap", """{"id":1}""")).contains("error"))
        assertTrue(screen.actions.isEmpty())
    }

    @Test fun `approved task reads, taps and types, then expires`() = runBlocking {
        val screen = FakeScreen()
        val t = tools(screen)
        t.run(call("start_screen_control", """{"goal":"Write a note"}"""))
        val read = JSONObject(t.run(call("read_screen")))
        assertEquals("com.example.notes", read.getString("app"))
        assertEquals("2|edit|(password field)", read.getJSONArray("elements").getString(1))
        assertTrue(read.getString("note").contains("not instructions"))
        // Une action renvoie directement l'écran suivant.
        assertTrue(JSONObject(t.run(call("tap", """{"id":1}"""))).has("elements"))
        t.run(call("type_text", """{"id":3,"text":"Milk"}"""))
        assertEquals(listOf("tap 1", "type 3 Milk"), screen.actions)
        now += 61_000
        assertTrue(t.run(call("tap", """{"id":1}""")).contains("start_screen_control"))
    }

    @Test fun `passwords, Claydot itself and Android settings are off limits`() = runBlocking {
        val screen = FakeScreen()
        val t = tools(screen)
        t.run(call("start_screen_control", """{"goal":"x"}"""))
        assertTrue(t.run(call("type_text", """{"id":2,"text":"hunter2"}""")).contains("password"))
        screen.pkg = "app.claydot"
        assertTrue(t.run(call("tap", """{"id":1}""")).contains("Claydot itself"))
        screen.pkg = "com.android.settings"
        assertTrue(t.run(call("tap", """{"id":1}""")).contains("off limits"))
        // Revenir en arrière reste possible : c'est la sortie.
        assertFalse(t.run(call("press_key", """{"key":"back"}""")).contains("error"))
        assertEquals(listOf("press back"), screen.actions)
    }

    @Test fun `permission ends with the reply`() = runBlocking {
        val screen = FakeScreen()
        val t = tools(screen)
        t.run(call("start_screen_control", """{"goal":"x"}"""))
        assertTrue(t.active())
        CombinedTools(listOf(t)).finished()
        assertFalse(t.active())
    }

    @Test fun `long press, swipe and links, with the cursor shown during the task`() = runBlocking {
        val screen = FakeScreen()
        val t = tools(screen)
        t.run(call("start_screen_control", """{"goal":"x"}"""))
        t.run(call("long_press", """{"id":1}"""))
        t.run(call("swipe", """{"direction":"left"}"""))
        assertTrue(t.run(call("open_link", """{"url":"intent://settings"}""")).contains("only http"))
        t.run(call("open_link", """{"url":"https://maps.google.com/?q=Lagos"}"""))
        assertEquals(listOf("hold 1", "swipe left", "open https://maps.google.com/?q=Lagos"), screen.actions)
        t.finished()
        assertEquals(listOf(true, false), screen.shown)
    }
}
