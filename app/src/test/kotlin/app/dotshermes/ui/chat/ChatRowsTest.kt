package app.dotshermes.ui.chat

import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.MessageContent
import app.dotshermes.data.model.Sender
import app.dotshermes.data.model.ToolCall
import app.dotshermes.data.model.ToolKind
import app.dotshermes.data.model.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ChatRowsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 2)

    private fun msg(id: String, sender: Sender, hour: Int, minute: Int, day: LocalDate = today) =
        ChatMessage(id, sender, MessageContent.Text(id), day.atTime(hour, minute).toInstant(zone))

    @Test
    fun `rows are newest first with day separators`() {
        val rows = buildChatRows(
            listOf(msg("a", Sender.Agent, 18, 0, today.minusDays(1)), msg("b", Sender.User, 9, 0)),
            AgentActivity.Idle, zone, today,
        )
        assertEquals(listOf("b", "day-$today", "a", "day-${today.minusDays(1)}"), rows.map { it.key })
        val day = rows.last() as ChatRow.Day
        assertEquals(today.minusDays(1), day.date)
        assertEquals(today.minusDays(1).atTime(18, 0).toInstant(zone), day.firstAt)
    }

    @Test
    fun `close messages from same sender are grouped`() {
        val rows = buildChatRows(
            listOf(msg("a", Sender.Agent, 9, 0), msg("b", Sender.Agent, 9, 1), msg("c", Sender.Agent, 9, 10)),
            AgentActivity.Idle, zone, today,
        ).filterIsInstance<ChatRow.Bubble>().associateBy { it.key }
        assertTrue(rows.getValue("a").groupedWithNext)
        assertTrue(rows.getValue("b").groupedWithPrev)
        assertFalse(rows.getValue("c").groupedWithPrev)
    }

    @Test
    fun `typing row and receipt on last user message`() {
        val rows = buildChatRows(
            listOf(msg("u1", Sender.User, 9, 0), msg("u2", Sender.User, 9, 5), msg("a", Sender.Agent, 9, 6)),
            AgentActivity.Typing, zone, today,
        )
        assertEquals(ChatRow.Typing(), rows.first())
        val receipts = rows.filterIsInstance<ChatRow.Bubble>().filter { it.showReceipt }.map { it.key }
        assertEquals(listOf("u2"), receipts)
    }

    private fun said(id: String, speaker: String?, minute: Int) =
        msg(id, Sender.Agent, 10, minute).copy(turn = "q", speaker = speaker)

    @Test
    fun `room shows who speaks each time the voice changes, conclusion included`() {
        val question = msg("q", Sender.User, 10, 0).copy(remoteId = "q")
        val rows = buildChatRows(
            listOf(question, said("p1", "pebble", 1), said("p2", "pebble", 1), said("c1", "cobble", 2), said("h", null, 3)),
            AgentActivity.Idle, zone, today,
        ).filterIsInstance<ChatRow.Bubble>().associateBy { it.key }
        assertTrue(rows.getValue("p1").showSpeaker)
        // Same speaker, same minute: one mascot for both bubbles, glued together.
        assertFalse(rows.getValue("p2").showSpeaker)
        assertTrue(rows.getValue("p2").groupedWithPrev)
        // Another voice never sticks to the previous one.
        assertTrue(rows.getValue("c1").showSpeaker)
        assertFalse(rows.getValue("c1").groupedWithPrev)
        // The host's conclusion is in a room turn: its mascot shows too.
        assertTrue(rows.getValue("h").showSpeaker)
        assertFalse(rows.getValue("q").showSpeaker)
    }

    @Test
    fun `outside a room, agent bubbles carry no mascot`() {
        val rows = buildChatRows(listOf(msg("u", Sender.User, 9, 0), msg("a", Sender.Agent, 9, 1)), AgentActivity.Idle, zone, today)
        assertTrue(rows.filterIsInstance<ChatRow.Bubble>().none { it.showSpeaker })
    }

    @Test
    fun `a thinking room speaker already shows its typing bubble`() {
        val rows = buildChatRows(listOf(msg("u", Sender.User, 9, 0)), AgentActivity.Thinking, zone, today, speaker = "pebble")
        assertEquals(ChatRow.Typing("pebble"), rows.first())
        val plain = buildChatRows(listOf(msg("u", Sender.User, 9, 0)), AgentActivity.Thinking, zone, today)
        assertTrue(plain.none { it is ChatRow.Typing })
    }

    private fun tool(id: String, turn: String, hour: Int, minute: Int, state: ToolState = ToolState.Done) =
        ToolCall(id, turn, ToolKind.Terminal, "Exécute une commande", "ls", state, today.atTime(hour, minute).toInstant(zone))

    @Test
    fun `tools sit above the reply they precede, never as bubbles`() {
        val rows = buildChatRows(
            listOf(
                msg("u", Sender.User, 9, 0),
                msg("a1", Sender.Agent, 9, 2).copy(turn = "u"),
                msg("a2", Sender.Agent, 9, 4).copy(turn = "u"),
            ),
            AgentActivity.Idle, zone, today,
            tools = listOf(tool("t1", "u", 9, 1), tool("t2", "u", 9, 3), tool("t3", "u", 9, 3)),
        ).asReversed()
        assertEquals(listOf("day-$today", "u", "tools-t1", "a1", "tools-t2", "a2"), rows.map { it.key })
        assertEquals(2, (rows[4] as ChatRow.Tools).calls.size)
        assertTrue((rows[5] as ChatRow.Bubble).afterTools)
        assertFalse((rows[3] as ChatRow.Bubble).groupedWithNext) // le sur-message coupe le groupe
    }

    @Test
    fun `running tools without a reply yet trail the conversation`() {
        val rows = buildChatRows(
            listOf(msg("u", Sender.User, 9, 0)),
            AgentActivity.Thinking, zone, today,
            tools = listOf(tool("t1", "u", 9, 1, ToolState.Running)),
        )
        val live = rows.first() as ChatRow.Tools
        assertTrue(live.running)
    }
    @Test fun `failed older user messages retain a visible failure receipt`() {
        val rows = buildChatRows(listOf(msg("failed", Sender.User, 9, 0).copy(failed = true), msg("new", Sender.User, 9, 5)), AgentActivity.Idle, zone, today)
        assertTrue(rows.filterIsInstance<ChatRow.Bubble>().single { it.key == "failed" }.showReceipt)
    }

}

class StreamingTypingTest {
    private fun msg(id: String, sender: app.dotshermes.data.model.Sender) = app.dotshermes.data.model.ChatMessage(
        id = id, sender = sender, content = app.dotshermes.data.model.MessageContent.Text("x"), sentAt = java.time.Instant.now())

    @Test fun `no typing bubble under a reply that streams in place`() {
        val messages = listOf(msg("u", app.dotshermes.data.model.Sender.User), msg("a", app.dotshermes.data.model.Sender.Agent))
        val rows = buildChatRows(messages, app.dotshermes.data.model.AgentActivity.Typing, streamsInPlace = true)
        org.junit.Assert.assertTrue(rows.none { it is ChatRow.Typing })
        val gateway = buildChatRows(messages, app.dotshermes.data.model.AgentActivity.Typing)
        org.junit.Assert.assertTrue(gateway.any { it is ChatRow.Typing })
    }
}
