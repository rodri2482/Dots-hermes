package app.dotshermes.ui.activity

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
import java.time.Instant

class TurnSummariesTest {
    private val t0 = Instant.parse("2026-11-02T10:00:00Z")

    private fun user(id: String, content: MessageContent, min: Long) =
        ChatMessage(id, Sender.User, content, t0.plusSeconds(min * 60), remoteId = "r-$id")

    private fun tool(id: String, turn: String, kind: ToolKind, state: ToolState, ms: Long?) =
        ToolCall(id, turn, kind, "x", "", state, t0, ms)

    @Test
    fun `turns are newest first with their tools and status`() {
        val messages = listOf(
            user("a", MessageContent.Text("Ouvre le site\npuis résume"), 0),
            ChatMessage("b", Sender.Agent, MessageContent.Text("Fait"), t0.plusSeconds(90), turn = "r-a"),
            user("c", MessageContent.Voice(4000, emptyList(), transcript = "Quelle heure ?"), 5),
        )
        val tools = listOf(
            tool("t1", "r-a", ToolKind.Web, ToolState.Done, 1200),
            tool("t2", "r-a", ToolKind.Web, ToolState.Error, 300),
            tool("t3", "r-c", ToolKind.Terminal, ToolState.Running, null),
        )
        val turns = summarizeTurns(messages, tools)
        assertEquals(listOf("r-c", "r-a"), turns.map { it.id })
        assertEquals("Quelle heure ?", turns[0].request)
        assertFalse(turns[0].voiceNote)
        assertEquals("voice", turns[0].media)
        assertTrue(turns[0].running)
        assertFalse(turns[0].answered)
        with(turns[1]) {
            assertEquals("Ouvre le site", request)
            assertEquals(2, toolCount)
            assertEquals(1500L, durationMs)
            assertEquals(1, errors)
            assertEquals(listOf(ToolKind.Web), kinds)
            assertTrue(answered)
        }
    }

    @Test
    fun `a voice note without transcript leaves the label to the screen`() {
        val turns = summarizeTurns(listOf(user("a", MessageContent.Voice(3000, emptyList()), 0)), emptyList())
        assertEquals("", turns.single().request)
        assertTrue(turns.single().voiceNote)
    }

    @Test
    fun `an earlier browsing turn never makes the next turn look like browsing`() {
        val messages = mutableListOf(
            user("a", MessageContent.Text("Va sur wikipedia"), 0),
            ChatMessage("b", Sender.Agent, MessageContent.Text("Fait"), t0.plusSeconds(60), turn = "r-a"),
        )
        val tools = mutableListOf(tool("t1", "r-a", ToolKind.Web, ToolState.Done, 900))
        // Pendant le tour de navigation : oui.
        assertTrue(isBrowsingNow(currentTurnTools(messages.take(1), tools, busy = true)))
        // Nouveau message, l'agent réfléchit sans navigateur : non, même si l'image d'avant existe.
        messages += user("c", MessageContent.Text("Merci !"), 3)
        assertFalse(isBrowsingNow(currentTurnTools(messages, tools, busy = true)))
        tools += tool("t2", "r-c", ToolKind.Terminal, ToolState.Running, null)
        assertFalse(isBrowsingNow(currentTurnTools(messages, tools, busy = true)))
        assertEquals(listOf("t2"), currentTurnTools(messages, tools, busy = true).map { it.id })
        // Au repos : jamais.
        assertTrue(currentTurnTools(messages, tools, busy = false).isEmpty())
    }
}
