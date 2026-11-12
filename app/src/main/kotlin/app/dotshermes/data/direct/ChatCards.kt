package app.dotshermes.data.direct

import app.dotshermes.data.model.CardKind
import app.dotshermes.data.model.CardStatus
import app.dotshermes.data.model.ChatCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** Réponse de l'utilisateur à une carte, depuis le fil ou la pastille de l'écran. */
sealed interface CardReply {
    data class Answer(val text: String) : CardReply
    data object Decline : CardReply
    /** Carte d'accès : l'utilisateur part donner l'accès (dialogue Android, réglages, Gmail). */
    data object Continue : CardReply
}

/** La conversation qui travaille en ce moment : elle pose les cartes dans son fil et attend la réponse. */
interface CardHost {
    /** Pose [card] et attend la première décision ; null : délai dépassé, tâche arrêtée ou pas de tâche en cours. */
    suspend fun ask(card: ChatCard, timeoutMs: Long): ChatCard?
    /** Met à jour une carte déjà posée (accès donné ou non). */
    fun update(id: String, status: CardStatus, answer: String? = null)
    fun card(id: String): ChatCard?
    /** Réponse de l'utilisateur (fil ou pastille) ; sans effet sur une carte déjà fermée. */
    fun reply(id: String, reply: CardReply)
}

/** Ce que la pastille de l'écran montre pendant une tâche : la dernière phrase de Claydot et la carte ouverte. */
data class LiveNote(val text: String, val card: ChatCard?, val done: Boolean = false)

object ChatCards {
    /** Posé au début de chaque réponse par la conversation qui travaille ; null hors tâche. */
    @Volatile var host: CardHost? = null
    internal val _live = MutableStateFlow<LiveNote?>(null)
    val live: StateFlow<LiveNote?> = _live.asStateFlow()

    fun newId() = "card-" + UUID.randomUUID().toString().replace("-", "").take(16)

    /**
     * Carte d'accès : « Continuer » ([onContinue] : ce que Claydot règle lui-même), puis vérification réelle de
     * l'accès ([granted]) ; true seulement s'il est vraiment donné. Refus, délai ou annulation : false.
     */
    suspend fun askAccess(card: ChatCard, waitMs: Long = ACCESS_WAIT_MS, onContinue: () -> Unit = {}, granted: () -> Boolean): Boolean {
        val host = host ?: return false
        val reply = host.ask(card, QUESTION_TIMEOUT_MS)
        if (reply?.status != CardStatus.Waiting) return false
        onContinue()
        return CardTools.waitFor(host, card.id, waitMs, granted)
    }

    const val QUESTION_TIMEOUT_MS = 10 * 60_000L
    const val ACCESS_WAIT_MS = 3 * 60_000L
    const val APPROVE = "approve"
    const val DONE = "done"
}

/**
 * Outils des cartes : poser une question (choix simple ou multiple, réponse libre), demander un accès précis,
 * passer la main à l'utilisateur sur l'écran (connexion, code, captcha). Les accès sont une liste fermée.
 */
class CardTools(
    private val requestAccess: suspend (PhoneAccess, String) -> Boolean,
    private val gmailConnected: () -> Boolean,
) : DirectTools {
    override fun definitions(): JSONArray = JSONArray()
        .put(fn("ask_user", "Ask the user a short question in a card when you need missing information, a choice or a clarification " +
            "to continue a task. Give 2 to 6 short suggested answers when they help; the user can also type their own. " +
            "Do not use it for normal conversation, greetings or questions you can answer yourself.",
            JSONObject().put("question", str("The question, one short sentence"))
                .put("options", JSONObject().put("type", "array").put("items", str("A suggested answer")).put("maxItems", 6))
                .put("multi_select", JSONObject().put("type", "boolean").put("description", "true when several answers can be picked"))
                .put("explanation", str("Optional one-line context")), "question"))
        .put(fn("request_access", "Ask the user for an access you need right now and wait until it is really given. " +
            "contacts: contacts and SMS; whatsapp: read WhatsApp notifications; screen: operate the screen; all_day: listen all day " +
            "for routines; gmail: connect Gmail read-only.",
            JSONObject().put("access", JSONObject().put("type", "string").put("enum", JSONArray(ACCESS.keys.toList())))
                .put("reason", str("Why, in one short sentence")), "access"))
        .put(fn("hand_over_to_user", "When the screen needs a human (login, password, 2FA code, captcha, payment), hand the phone " +
            "to the user and wait until they tap Done. Never ask them to tell you a password.",
            JSONObject().put("reason", str("What the user should do, one short sentence")), "reason"))

    override fun label(call: ApiToolCall) = ToolKeys.of("ask")

    override suspend fun run(call: ApiToolCall): String {
        val args = try { JSONObject(call.arguments.ifBlank { "{}" }) } catch (_: JSONException) { JSONObject() }
        val host = ChatCards.host ?: return err("cards are not available right now: ask in plain text")
        return when (call.name) {
            "ask_user" -> {
                val question = args.optString("question").trim().ifEmpty { return err("question is required") }
                val options = args.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                val card = ChatCard(ChatCards.newId(), CardKind.Question, question, args.optString("explanation"), options,
                    multi = args.optBoolean("multi_select") && options.size > 1).bounded()
                val reply = host.ask(card, ChatCards.QUESTION_TIMEOUT_MS)
                when (reply?.status) {
                    CardStatus.Answered -> JSONObject().put("status", "answered").put("answer", reply.answer.orEmpty()).toString()
                    CardStatus.Declined -> JSONObject().put("status", "declined").put("note", "The user chose not to answer.").toString()
                    else -> JSONObject().put("status", "no_answer").toString()
                }
            }
            "request_access" -> {
                val name = args.optString("access")
                val reason = args.optString("reason").trim().take(200)
                if (name == "gmail") return gmail(host, reason)
                val access = ACCESS[name] ?: return err("unknown access")
                JSONObject().put("status", if (requestAccess(access, reason)) "granted" else "not_granted").toString()
            }
            "hand_over_to_user" -> {
                val reason = args.optString("reason").trim().ifEmpty { return err("reason is required") }
                val card = ChatCard(ChatCards.newId(), CardKind.Handover, reason, custom = false).bounded()
                val reply = host.ask(card, ChatCards.QUESTION_TIMEOUT_MS)
                JSONObject().put("status", if (reply?.status == CardStatus.Answered) "done" else "stopped").toString()
            }
            else -> err("unknown tool")
        }
    }

    /** Gmail : « Connecter » ouvre les réglages Gmail ; la tâche reprend une fois Gmail vraiment connecté. */
    private suspend fun gmail(host: CardHost, reason: String): String {
        if (gmailConnected()) return JSONObject().put("status", "granted").toString()
        val card = ChatCard(ChatCards.newId(), CardKind.Access, "Gmail", reason, access = GMAIL, custom = false).bounded()
        val granted = ChatCards.askAccess(card, waitMs = 5 * 60_000L, granted = gmailConnected)
        return JSONObject().put("status", if (granted) "granted" else "not_granted").toString()
    }

    private fun err(message: String) = JSONObject().put("error", message).toString()
    private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
    private fun fn(name: String, description: String, properties: JSONObject, vararg required: String) = JSONObject()
        .put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required.toList()))))

    companion object {
        const val GMAIL = "Gmail"
        val ACCESS = linkedMapOf("contacts" to PhoneAccess.Contacts, "whatsapp" to PhoneAccess.Notifications,
            "screen" to PhoneAccess.Screen, "all_day" to PhoneAccess.AllDay, "gmail" to null)

        /**
         * Après « Continuer » : on vérifie vraiment l'accès (toutes les 0,7 s) jusqu'à [timeoutMs] ou jusqu'à ce que
         * l'utilisateur annule la carte ; la carte passe ensuite à « Accordé » ou « Non accordé ».
         */
        suspend fun waitFor(host: CardHost, id: String, timeoutMs: Long, granted: () -> Boolean): Boolean {
            val ok = withTimeoutOrNull(timeoutMs) {
                while (!granted()) {
                    if (host.card(id)?.open != true) return@withTimeoutOrNull false
                    delay(700)
                }
                true
            } ?: false
            host.update(id, if (ok) CardStatus.Answered else CardStatus.Declined, if (ok) "granted" else null)
            return ok
        }
    }
}
