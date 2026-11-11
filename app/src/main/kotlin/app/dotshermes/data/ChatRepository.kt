package app.dotshermes.data

import app.dotshermes.data.model.ActivitySnapshot
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.CronAction
import app.dotshermes.data.model.ScreenInput
import app.dotshermes.data.model.ScreenState
import app.dotshermes.data.model.SubDot
import app.dotshermes.data.model.SubDotDraft
import app.dotshermes.data.model.AgentProfile
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.model.GatewayConfig
import app.dotshermes.data.model.ToolCall
import app.dotshermes.data.model.UsageWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.Instant

private val NoRoom: StateFlow<List<String>> = MutableStateFlow(emptyList<String>()).asStateFlow()
private val NoSpeaker: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
private val NoUsage: StateFlow<UsageWindow?> = MutableStateFlow<UsageWindow?>(null).asStateFlow()

/**
 * Seul point de contact entre l'interface et l'agent Hermes.
 *
 * Implémentations : [app.dotshermes.data.gateway.GatewayChatRepository] (WebSocket vers le
 * plugin « dots » du gateway) et [app.dotshermes.data.direct.DirectChatRepository] (compte API direct),
 * aiguillées par [RoutingChatRepository].
 */
interface ChatRepository {
    val gatewayFeatures: StateFlow<Boolean> get() = GatewayFeatures
    /** Micro disponible : le gateway Hermes, ou un compte direct qui sait transcrire (Groq). */
    val voiceInput: StateFlow<Boolean> get() = gatewayFeatures
    val directUsage: StateFlow<app.dotshermes.data.direct.DirectApiUsage?> get() = NoDirectUsage
    val requestIssue: StateFlow<app.dotshermes.data.direct.DirectRequestIssue?> get() = NoRequestIssue
    fun retryRequest() {}
    /** Efface le fil enregistré sur le téléphone (comptes directs) ; sans effet ailleurs. */
    fun clearHistory() {}
    fun setShown(shown: Boolean) {}
    val profile: StateFlow<AgentProfile>
    val connection: StateFlow<ConnectionState>
    val messages: StateFlow<List<ChatMessage>>
    val activity: StateFlow<AgentActivity>
    /** Outils utilisés par l'agent, dans l'ordre ; affichés en sur-messages, jamais comme des messages. */
    val tools: StateFlow<List<ToolCall>>
    /** Écran du navigateur de l'agent (vignette après chaque action, flux quand on le regarde). */
    val screen: StateFlow<ScreenState>
    /** Vue Activité ; null tant qu'elle n'a pas été demandée. */
    val activitySnapshot: StateFlow<ActivitySnapshot?>
    /** Des messages plus anciens restent à charger (défilement vers le haut). */
    val hasOlder: StateFlow<Boolean>

    /** Les sous-dots ; le dot principal n'en fait pas partie (conversation [app.dotshermes.data.model.MAIN_CHAT]). */
    val dots: StateFlow<List<SubDot>>
    /** Conversation affichée : `messages`, `tools`, `activity`… décrivent celle-ci. */
    val activeChat: StateFlow<String>
    /** Activité de chaque conversation, pour animer les dots qui travaillent en arrière-plan. */
    val chatActivity: StateFlow<Map<String, AgentActivity>>
    /** Messages de l'agent arrivés dans une conversation qui n'était pas affichée. */
    val unread: StateFlow<Map<String, Int>>

    /**
     * Salon de groupe de la conversation affichée : les sous-dots invités, dans l'ordre où ils parlent.
     * Vide : conversation normale. Le mode démo n'a pas de sous-dots, donc pas de salon.
     */
    val room: StateFlow<List<String>> get() = NoRoom
    /** Qui parle en ce moment dans ce salon ; null : le dot de la conversation (ou pas de débat en cours). */
    val speaker: StateFlow<String?> get() = NoSpeaker

    /** Invite ces sous-dots dans le salon de la conversation affichée (liste vide : salon fermé). */
    fun setRoom(dots: List<String>) {}

    /** Dernière fenêtre de consommation reçue (carte « Consommation » de la vue Activité) ; null : rien encore. */
    val usage: StateFlow<UsageWindow?> get() = NoUsage

    /** Demande la consommation heure par heure entre [from] et [to] ; la réponse arrive dans [usage]. */
    fun requestUsage(from: Instant, to: Instant) {}

    suspend fun connect(config: GatewayConfig)
    fun disconnect()
    fun send(text: String)
    fun toggleReaction(messageId: String, emoji: String)
    fun answerApproval(messageId: String, approved: Boolean)
    fun answerClarify(messageId: String, response: String)

    /** Vocal enregistré au micro (AAC dans un conteneur MP4), avec son enveloppe pour l'affichage. */
    fun sendVoice(file: File, durationMs: Long, waveform: List<Float>)

    /** Pièce jointe envoyée par l'utilisateur ; [caption] part avec elle comme texte du message. */
    fun sendFile(file: File, name: String, mime: String, caption: String)

    /** Fichier d'un vocal ou d'une pièce jointe : local, ou téléchargé depuis le gateway ; null si introuvable. */
    suspend fun attachmentFile(messageId: String): File?

    /** Ouvre (ou ferme) le flux en direct de l'écran du navigateur ; [takeover] : l'utilisateur pilote (flux plus rapide). */
    fun watchScreen(on: Boolean, takeover: Boolean = false) {}

    /** Clic, défilement, frappe ou touche, rejoués sur la page de l'agent. */
    fun screenInput(input: ScreenInput) {}

    fun refreshActivity() {}

    /** Charge la page d'historique précédente ; sans effet si rien ne reste ou si une page arrive déjà. */
    fun loadOlder() {}

    fun cronAction(id: String, action: CronAction) {}

    fun selectChat(chat: String) {}

    fun createDot(draft: SubDotDraft) {}

    fun updateDot(id: String, draft: SubDotDraft) {}

    /** Ferme un sous-dot : sa conversation est effacée côté gateway. */
    fun deleteDot(id: String) {}

    /** Rend la main à l'agent après une prise en main qu'il avait demandée. */
    fun finishTakeover(messageId: String) {}

    /** Interrompt le tour en cours (équivalent de /stop). */
    fun stopTurn() {}

    /** L'app est-elle à l'écran ? Sinon, le gateway prévient par notification push. */
    fun setForeground(foreground: Boolean) {}

    /** Jeton FCM de cet appareil, transmis au gateway pour les notifications. */
    fun setPushToken(token: String) {}
}

private val GatewayFeatures = MutableStateFlow(true).asStateFlow()
private val NoRequestIssue = MutableStateFlow<app.dotshermes.data.direct.DirectRequestIssue?>(null).asStateFlow()

private val NoDirectUsage = MutableStateFlow<app.dotshermes.data.direct.DirectApiUsage?>(null).asStateFlow()
