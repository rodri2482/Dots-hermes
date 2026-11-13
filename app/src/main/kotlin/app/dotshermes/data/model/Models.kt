package app.dotshermes.data.model

import java.time.Instant
import androidx.annotation.StringRes
import app.dotshermes.R

enum class Sender { User, Agent }

/** Ordre significatif : un accusé ne peut que progresser (Sending → Sent → Read). */
enum class Delivery { Sending, Sent, Read }

enum class StepState { Pending, Running, Done }

enum class ApprovalStatus { Pending, Approved, Declined }

data class TaskStep(val label: String, val state: StepState)

sealed interface MessageContent {
    data class Text(val text: String) : MessageContent

    /** Travail de fond de l'agent, affiché comme une carte de progression. */
    data class Task(val title: String, val steps: List<TaskStep>) : MessageContent {
        val doneCount: Int get() = steps.count { it.state == StepState.Done }
        val isComplete: Boolean get() = doneCount == steps.size
    }

    /** Action sensible que l'agent ne fera qu'avec le feu vert de l'utilisateur. */
    data class Approval(
        val title: String,
        val detail: String,
        val status: ApprovalStatus = ApprovalStatus.Pending,
    ) : MessageContent

    /** Question de l'agent ; sans [choices], la réponse se tape simplement dans la conversation. */
    /**
     * Message vocal. [localPath] : fichier sur l'appareil (enregistré ici ou déjà téléchargé) ;
     * sinon l'audio se récupère auprès du gateway. [transcript] : ce que Hermes a entendu (STT).
     */
    data class Voice(
        val durationMs: Long,
        val waveform: List<Float>,
        val localPath: String? = null,
        val transcript: String? = null,
    ) : MessageContent

    /** Pièce jointe (photo, PDF, vidéo…) dans un sens ou dans l'autre, avec sa légende éventuelle. */
    data class File(
        val name: String,
        val mime: String,
        val size: Long,
        val localPath: String? = null,
        val caption: String = "",
    ) : MessageContent {
        val isImage: Boolean get() = mime.startsWith("image/")
    }

    /** L'agent bloque sur une page (connexion, captcha…) et attend que l'utilisateur prenne la main. */
    data class Takeover(val reason: String, val pending: Boolean = true) : MessageContent

    data class Clarify(
        val question: String,
        val choices: List<String>,
        val answer: String? = null,
    ) : MessageContent

    /** Carte interactive d'un compte direct (question à choix, accord, accès Android) ; voir [ChatCard]. */
    data class Card(val card: ChatCard) : MessageContent

    /** Réponse avec des images trouvées par la recherche d'images (1 à 3), sous le texte. */
    data class Rich(val text: String, val images: List<ChatImage>) : MessageContent
}

/**
 * Une image d'une réponse : fichier local (téléchargé par l'outil de recherche, jamais une adresse écrite par le
 * modèle), sa légende et la page d'où elle vient.
 */
data class ChatImage(val path: String, val caption: String, val source: String)

/** Ce qu'une carte demande. Le modèle ne choisit que parmi ces types : jamais un intent, une URL ou une permission libre. */
/** Switch : un modèle plus capable que l'utilisateur a déjà, proposé quand celui-ci ne peut pas faire la tâche. */
enum class CardKind { Question, Confirm, Access, Handover, Switch }

/** Pending : en attente. Waiting : l'utilisateur est parti donner l'accès (réglages, dialogue Android). */
enum class CardStatus { Pending, Waiting, Answered, Declined, Expired }

/**
 * Une carte dans le fil, avec un identifiant stable. [access] : l'accès demandé (nom de [app.dotshermes.data.direct.PhoneAccess]
 * ou « Gmail »). Une carte terminée ou expirée ne relance jamais rien.
 */
data class ChatCard(
    val id: String,
    val kind: CardKind,
    val question: String,
    val detail: String = "",
    val options: List<String> = emptyList(),
    val multi: Boolean = false,
    val custom: Boolean = true,
    val access: String? = null,
    val status: CardStatus = CardStatus.Pending,
    val answer: String? = null,
) {
    val open: Boolean get() = status == CardStatus.Pending || status == CardStatus.Waiting

    /** Bornes : une carte reste courte, quel que soit ce que le modèle a écrit. */
    fun bounded() = copy(
        question = question.trim().take(MAX_QUESTION), detail = detail.trim().take(MAX_DETAIL),
        options = options.map { it.trim().take(MAX_OPTION) }.filter { it.isNotEmpty() }.distinct().take(MAX_OPTIONS),
        answer = answer?.take(MAX_ANSWER),
    )

    companion object {
        const val MAX_QUESTION = 300
        const val MAX_DETAIL = 500
        const val MAX_OPTION = 80
        const val MAX_OPTIONS = 6
        const val MAX_ANSWER = 1_000
    }
}

data class ChatMessage(
    /** Clé locale stable (sert de clé d'affichage). */
    val id: String,
    val sender: Sender,
    val content: MessageContent,
    val sentAt: Instant,
    val delivery: Delivery = Delivery.Read,
    /** Une réaction emoji au plus par participant. */
    val reactions: Map<Sender, String> = emptyMap(),
    /** Identifiant attribué par le gateway, connu après l'accusé de réception. */
    val remoteId: String? = null,
    /** Côté agent : identifiant distant du message utilisateur auquel il répond (rattache les outils). */
    val turn: String? = null,
    /** Rang dans l'historique du gateway : sert à demander la page précédente. */
    val seq: Long? = null,
    /** Salon de groupe : le sous-dot invité qui a dit ce message ; null = le dot de la conversation. */
    val speaker: String? = null,
    /** Son nom au moment où il a parlé : le fil le montre encore une fois ce sous-dot fermé. */
    val speakerName: String? = null,
    val failed: Boolean = false,
    /** Ligne discrète sous une réponse (compte direct : « modèle · 1,4 s ») ; null = rien. */
    val footer: String? = null,
    /** Réponse encore en train d'arriver : le texte s'affiche progressivement, sans à-coups. */
    val streaming: Boolean = false,
    /** Message auquel celui-ci répond (citation courte) ; null = message normal. */
    val quote: String? = null,
    /** Rapport d'équipe attaché à sa réponse, conservé avec l'historique chiffré. */
    val teamReport: app.dotshermes.data.direct.TeamProgress? = null,
)

enum class ToolState { Running, Done, Error }

/** Famille d'outil : choisit l'icône du sur-message (le libellé vient du gateway). */
enum class ToolKind { Terminal, Read, Search, Write, Web, Skill, Memory, Code, Delegate, Plan, Schedule, Vision, Image, Audio, Send, Tool }

/**
 * Un appel d'outil de l'agent pendant un tour (lecture de skill, commande, recherche…).
 * Ce n'est pas un message : il s'affiche en sur-message au-dessus de la réponse du tour [turn].
 */
data class ToolCall(
    val id: String,
    val turn: String?,
    val kind: ToolKind,
    val label: String,
    val detail: String,
    val state: ToolState,
    val startedAt: Instant,
    val durationMs: Long? = null,
    /** Nom technique de l'outil côté Hermes (`terminal`, `web_search`…), pour traduire son libellé. */
    val name: String = "",
)

sealed interface AgentActivity {
    data object Idle : AgentActivity
    data object Thinking : AgentActivity
    data object Typing : AgentActivity
    data object Reacting : AgentActivity
    data class Working(val label: String) : AgentActivity
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val step: String) : ConnectionState
    /** [online] passe à false pendant une reconnexion automatique : la conversation reste affichée. */
    data class Connected(
        val gatewayUrl: String,
        val latencyMs: Int,
        val online: Boolean = true,
        /** Motif d'une attente de reconnexion ; null une fois le gateway de nouveau connecté. */
        val reconnectReason: String? = null,
    ) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

data class GatewayConfig(val url: String, val token: String)

data class AgentProfile(val name: String, val tagline: String)

/** Une conversation d'un compte direct, pour la liste « Discussions ». */
data class ConversationInfo(val id: String, val title: String, val updatedMs: Long, val open: Boolean)

/** Un message trouvé par la recherche : sa conversation, un extrait autour du mot cherché. */
data class SearchHit(val conversationId: String, val conversationTitle: String, val messageId: String,
    val snippet: String, val matchStart: Int, val matchLength: Int, val timeMs: Long, val fromUser: Boolean)

/** Dernière image de l'écran du navigateur de l'agent. [live] : flux en cours (vue ouverte). */
class ScreenState(
    val frame: ByteArray? = null,
    val url: String = "",
    val title: String = "",
    val live: Boolean = false,
    /** Le gateway n'a aucun navigateur ouvert en ce moment. */
    val idle: Boolean = false,
    val receivedAt: Instant? = null,
) {
    val hasFrame: Boolean get() = frame != null

    fun copy(live: Boolean = this.live, idle: Boolean = this.idle) = ScreenState(frame, url, title, live, idle, receivedAt)
}

/** Tâche planifiée (cron) de l'agent, telle que la décrit Hermes. */
data class CronJob(
    val id: String,
    val name: String,
    val schedule: String,
    val state: String,
    val enabled: Boolean,
    val nextRunAt: Instant?,
    val lastRunAt: Instant?,
    val lastStatus: String?,
    val prompt: String,
) {
    val finished: Boolean get() = state == "completed"
    val paused: Boolean get() = state == "paused" || (!enabled && !finished)
}

/** Ce que fait l'agent en arrière-plan : tâches planifiées, tour en cours, navigateur ouvert. */
data class ActivitySnapshot(
    val crons: List<CronJob>,
    val busyTurn: String?,
    val browserOpen: Boolean,
)

/**
 * Consommation d'une heure UTC (trame `usage`) : [hour] compte les heures depuis l'époque Unix,
 * [tokens] tout ce que l'agent a dépensé (tâches planifiées comprises), [messages] ceux échangés dans l'app.
 */
data class UsageHour(val hour: Long, val tokens: Long, val messages: Int)

/** Réponse du gateway pour la fenêtre [from, to) : seules les heures actives y figurent. */
data class UsageWindow(val from: Instant, val to: Instant, val hours: List<UsageHour>)

/** Geste de l'utilisateur sur l'écran du navigateur de l'agent (prise en main). x, y : fractions de l'image. */
sealed interface ScreenInput {
    data class Tap(val x: Float, val y: Float) : ScreenInput
    data class Scroll(val x: Float, val y: Float, val dy: Float) : ScreenInput
    data class Type(val text: String) : ScreenInput
    data class Key(val key: String) : ScreenInput
}

enum class CronAction(val wire: String) { Pause("pause"), Resume("resume"), Run("run") }

/** Conversation du dot principal ; chaque sous-dot a la sienne (et donc sa propre session Hermes). */
const val MAIN_CHAT = "owner"

/** Temporaire : une mission, puis on le ferme. Permanent : un spécialiste durable, avec son nom et sa mascotte. */
enum class DotKind(val wire: String, @StringRes val label: Int) {
    Temporary("temporary", R.string.dot_kind_temporary),
    Permanent("permanent", R.string.dot_kind_permanent),
}

data class SubDot(
    val id: String,
    val name: String,
    val kind: DotKind,
    /** Mission (temporaire) ou spécialisation (permanent) : devient la consigne de sa conversation. */
    val mission: String,
    val species: String,
    val palette: String,
    val accessory: String,
    val createdAt: Instant,
    val lastActivityAt: Instant?,
    /** Modèle qui fait tourner ce sous-dot, « fournisseur:modèle » ; null = le même que le dot principal. */
    val model: String? = null,
)

/** Ce que l'utilisateur saisit pour créer ou modifier un sous-dot. */
data class SubDotDraft(
    val name: String,
    val kind: DotKind,
    val mission: String,
    val species: String,
    val palette: String,
    val accessory: String,
    /** Modèle choisi, « fournisseur:modèle » ; null = le même que le dot principal. */
    val model: String? = null,
)
