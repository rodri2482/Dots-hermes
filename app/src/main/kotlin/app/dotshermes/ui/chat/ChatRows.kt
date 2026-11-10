package app.dotshermes.ui.chat

import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.MessageContent
import app.dotshermes.data.model.Sender
import app.dotshermes.data.model.ToolCall
import app.dotshermes.data.model.ToolState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Lignes prêtes à afficher : la logique de regroupement reste hors des composables (et testable). */
sealed interface ChatRow {
    val key: String

    /** Séparateur de jour : [date] et l'heure du premier message [firstAt], mis en forme à l'affichage (langue du téléphone). */
    data class Day(override val key: String, val date: LocalDate, val firstAt: Instant) : ChatRow

    data class Bubble(
        val message: ChatMessage,
        val groupedWithPrev: Boolean,
        val groupedWithNext: Boolean,
        val showReceipt: Boolean,
        /** Juste sous un sur-message d'outils : la bulle s'y accroche au lieu de s'en écarter. */
        val afterTools: Boolean = false,
        /**
         * Salon de groupe : la mascotte et le nom de qui parle, au-dessus de la bulle. Seulement quand
         * la voix change (chaque intervenant, puis le dot qui conclut), pas sur chaque bulle.
         */
        val showSpeaker: Boolean = false,
    ) : ChatRow {
        override val key: String get() = message.id
    }

    /** Sur-message : les outils que l'agent a utilisés avant la réponse qui suit (ou en cours). */
    data class Tools(val calls: List<ToolCall>) : ChatRow {
        override val key: String get() = "tools-${calls.first().id}"
        val running: Boolean get() = calls.any { it.state == ToolState.Running }
    }

    /** Tout en haut du fil : charge la page précédente dès qu'elle apparaît, ou marque le début. */
    data class Older(val hasMore: Boolean) : ChatRow {
        override val key: String = "older"
    }

    /** L'agent écrit ; dans un salon, [speaker] dit quel sous-dot (null : le dot de la conversation). */
    data class Typing(val speaker: String? = null) : ChatRow {
        override val key: String = "typing"
    }
}

private val GroupWindow: Duration = Duration.ofMinutes(3)

/**
 * Construit les lignes du fil, **de la plus récente à la plus ancienne** : la liste est affichée
 * en `reverseLayout`, ce qui garde le dernier message collé au champ de saisie, clavier ouvert ou non.
 */
fun buildChatRows(
    messages: List<ChatMessage>,
    activity: AgentActivity,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
    tools: List<ToolCall> = emptyList(),
    hasOlder: Boolean? = null,
    /** Salon : le sous-dot qui parle en ce moment, montré dans la bulle « écrit… ». */
    speaker: String? = null,
): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 8)
    // null : pas d'historique paginé (démo) ; sinon, une ligne de tête pour charger ou dire « début ».
    if (hasOlder != null && messages.isNotEmpty()) rows += ChatRow.Older(hasOlder)
    val lastUserId = messages.lastOrNull { it.sender == Sender.User }?.id
    val trail = ToolTrail(tools)
    var openTurn: String? = null
    // Tours joués en salon : là, chaque réponse dit qui parle, y compris celle du dot qui conclut.
    val roomTurns = messages.mapNotNullTo(HashSet()) { m -> m.turn.takeIf { m.speaker != null } }

    messages.forEachIndexed { index, message ->
        val previous = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)
        if (message.sender == Sender.User) {
            // Nouveau tour : les outils restants du précédent (utilisés après sa réponse) se referment ici.
            openTurn?.let { trail.rest(it)?.let(rows::add) }
            openTurn = message.remoteId ?: message.id
        }
        val day = message.sentAt.atZone(zone).toLocalDate()
        if (previous == null || previous.sentAt.atZone(zone).toLocalDate() != day) {
            rows += ChatRow.Day(key = "day-$day", date = day, firstAt = message.sentAt)
        }
        val tools = if (message.sender == Sender.Agent) message.turn?.let { trail.before(it, message.sentAt) } else null
        tools?.let(rows::add)
        val inRoom = message.sender == Sender.Agent && (message.speaker != null || message.turn in roomTurns)
        val sameVoice = previous != null && previous.sender == Sender.Agent && previous.speaker == message.speaker
        rows += ChatRow.Bubble(
            message = message,
            groupedWithPrev = tools == null && previous != null && sameGroup(previous, message, zone),
            groupedWithNext = next != null && sameGroup(message, next, zone) && trail.isEmptyBefore(next.turn, next.sentAt),
            showReceipt = message.id == lastUserId || message.failed,
            afterTools = tools != null,
            showSpeaker = inRoom && (!sameVoice || tools != null),
        )
    }
    trail.remaining().forEach(rows::add)
    // En salon, la mascotte de qui parle « écrit » dès qu'il réfléchit : on le regarde prendre la parole.
    if (activity == AgentActivity.Typing || (speaker != null && activity != AgentActivity.Idle)) rows += ChatRow.Typing(speaker)
    return rows.asReversed()
}

/** Outils regroupés par tour et triés : chaque réponse de l'agent « consomme » ceux lancés avant elle. */
private class ToolTrail(tools: List<ToolCall>) {
    private val pending: Map<String, ArrayDeque<ToolCall>> = tools
        .filter { it.turn != null }
        .sortedBy { it.startedAt }
        .groupByTo(LinkedHashMap()) { it.turn!! }
        .mapValues { ArrayDeque(it.value) }

    fun before(turn: String, at: Instant): ChatRow.Tools? {
        val queue = pending[turn] ?: return null
        val taken = ArrayList<ToolCall>()
        while (queue.isNotEmpty() && !queue.first().startedAt.isAfter(at)) taken += queue.removeFirst()
        return taken.takeIf { it.isNotEmpty() }?.let(ChatRow::Tools)
    }

    fun isEmptyBefore(turn: String?, at: Instant): Boolean =
        turn == null || pending[turn]?.firstOrNull()?.startedAt?.isAfter(at) != false

    fun rest(turn: String): ChatRow.Tools? =
        pending[turn]?.takeIf { it.isNotEmpty() }?.let { queue -> ChatRow.Tools(queue.toList()).also { queue.clear() } }

    fun remaining(): List<ChatRow.Tools> = pending.keys.mapNotNull(::rest)
}

/** Deux bulles se collent si même auteur (même intervenant en salon), même jour, < 3 min d'écart, et toutes deux du texte. */
private fun sameGroup(a: ChatMessage, b: ChatMessage, zone: ZoneId): Boolean =
    a.sender == b.sender && a.speaker == b.speaker &&
        a.content is MessageContent.Text && b.content is MessageContent.Text &&
        a.sentAt.atZone(zone).toLocalDate() == b.sentAt.atZone(zone).toLocalDate() &&
        Duration.between(a.sentAt, b.sentAt) < GroupWindow

