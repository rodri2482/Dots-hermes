package app.dotshermes.data

import app.dotshermes.data.model.ActivitySnapshot
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.AgentProfile
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.model.GatewayConfig
import app.dotshermes.data.model.MAIN_CHAT
import app.dotshermes.data.model.ScreenState
import app.dotshermes.data.model.SubDot
import app.dotshermes.data.model.ToolCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Aucun compte encore (premier lancement, ou ajout d'un compte en cours) : jamais connecté, rien à
 * afficher. L'écran de connexion (clé Groq ou modèle du téléphone) prend la place de la conversation.
 */
class UnpairedChatRepository : ChatRepository {
    override val gatewayFeatures: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override val profile: StateFlow<AgentProfile> = MutableStateFlow(AgentProfile("", "")).asStateFlow()
    override val connection: StateFlow<ConnectionState> = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected).asStateFlow()
    override val messages: StateFlow<List<ChatMessage>> = MutableStateFlow(emptyList<ChatMessage>()).asStateFlow()
    override val activity: StateFlow<AgentActivity> = MutableStateFlow<AgentActivity>(AgentActivity.Idle).asStateFlow()
    override val tools: StateFlow<List<ToolCall>> = MutableStateFlow(emptyList<ToolCall>()).asStateFlow()
    override val screen: StateFlow<ScreenState> = MutableStateFlow(ScreenState()).asStateFlow()
    override val activitySnapshot: StateFlow<ActivitySnapshot?> = MutableStateFlow<ActivitySnapshot?>(null).asStateFlow()
    override val hasOlder: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override val dots: StateFlow<List<SubDot>> = MutableStateFlow(emptyList<SubDot>()).asStateFlow()
    override val activeChat: StateFlow<String> = MutableStateFlow(MAIN_CHAT).asStateFlow()
    override val chatActivity: StateFlow<Map<String, AgentActivity>> = MutableStateFlow(emptyMap<String, AgentActivity>()).asStateFlow()
    override val unread: StateFlow<Map<String, Int>> = MutableStateFlow(emptyMap<String, Int>()).asStateFlow()

    override suspend fun connect(config: GatewayConfig) {}
    override fun disconnect() {}
    override fun send(text: String) {}
    override fun toggleReaction(messageId: String, emoji: String) {}
    override fun answerApproval(messageId: String, approved: Boolean) {}
    override fun answerClarify(messageId: String, response: String) {}
    override fun sendVoice(file: File, durationMs: Long, waveform: List<Float>) {}
    override fun sendFile(file: File, name: String, mime: String, caption: String) {}
    override suspend fun attachmentFile(messageId: String): File? = null
}
