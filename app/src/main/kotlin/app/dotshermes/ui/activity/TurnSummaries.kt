package app.dotshermes.ui.activity

import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.MessageContent
import app.dotshermes.data.model.Sender
import app.dotshermes.data.model.ToolCall
import app.dotshermes.data.model.ToolKind
import app.dotshermes.data.model.ToolState
import java.time.Instant

/** Un échange de l'historique, vu depuis l'Activité : la demande, et le travail qu'elle a déclenché. */
data class TurnSummary(
    val id: String,
    /** Début de la demande ; vide si elle n'a pas de texte (l'écran affiche alors un libellé traduit). */
    val request: String,
    val at: Instant,
    val toolCount: Int,
    val durationMs: Long,
    val errors: Int,
    val kinds: List<ToolKind>,
    val running: Boolean,
    val answered: Boolean,
    /** Vocal sans transcription : affiché « Message vocal », avec l'icône du micro, dans la langue du téléphone. */
    val voiceNote: Boolean = false,
    /** « voice » ou « file » : une icône devant la demande ; vide pour du texte. */
    val media: String = "",
)

/**
 * Les derniers tours, du plus récent au plus ancien. Un tour = un message de l'utilisateur
 * (clé : son id distant, que le gateway reporte sur les outils et réponses comme `turn`).
 */
fun summarizeTurns(messages: List<ChatMessage>, tools: List<ToolCall>, limit: Int = 12): List<TurnSummary> {
    val toolsByTurn = tools.filter { it.turn != null }.groupBy { it.turn!! }
    val answered = messages.filter { it.sender == Sender.Agent }.mapNotNullTo(HashSet()) { it.turn }
    return messages.asReversed()
        .filter { it.sender == Sender.User }
        .take(limit)
        .map { message ->
            val key = message.remoteId ?: message.id
            val calls = toolsByTurn[key].orEmpty()
            TurnSummary(
                id = key,
                request = requestLabel(message.content),
                at = message.sentAt,
                toolCount = calls.size,
                durationMs = calls.sumOf { it.durationMs ?: 0L },
                errors = calls.count { it.state == ToolState.Error },
                kinds = calls.map { it.kind }.distinct().take(4),
                running = calls.any { it.state == ToolState.Running },
                answered = key in answered,
                voiceNote = (message.content as? MessageContent.Voice)?.transcript == null && message.content is MessageContent.Voice,
                media = when (message.content) { is MessageContent.Voice -> "voice"; is MessageContent.File -> "file"; else -> "" },
            )
        }
}

/**
 * Outils du tour en cours : celui du dernier message de l'utilisateur, et seulement tant que
 * l'agent travaille. Un tour précédent (même plein de navigation) n'en fait jamais partie.
 */
fun currentTurnTools(messages: List<ChatMessage>, tools: List<ToolCall>, busy: Boolean): List<ToolCall> {
    if (!busy) return emptyList()
    val last = messages.lastOrNull { it.sender == Sender.User } ?: return emptyList()
    val turn = last.remoteId ?: last.id
    return tools.filter { it.turn == turn }
}

/** L'agent est sur son navigateur en ce moment : sa dernière action du tour en cours est web. */
fun isBrowsingNow(currentTools: List<ToolCall>): Boolean = currentTools.lastOrNull()?.kind == ToolKind.Web

private fun requestLabel(content: MessageContent): String = when (content) {
    is MessageContent.Text -> content.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
    is MessageContent.Voice -> content.transcript.orEmpty()
    is MessageContent.File -> content.caption.ifBlank { content.name }
    else -> ""
}
