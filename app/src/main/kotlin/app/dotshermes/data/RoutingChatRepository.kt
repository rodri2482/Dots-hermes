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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.time.Instant

/**
 * Aiguille l'interface vers l'agent affiché (sa connexion, voir [AgentRegistry]) ou vers l'agent
 * simulé quand l'adresse saisie est « demo ». Changer d'agent change tous les flux d'un coup ;
 * les autres agents restent connectés en arrière-plan, avec leurs conversations intactes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingChatRepository(
    scope: CoroutineScope,
    private val agents: AgentRegistry,
    private val demo: ChatRepository,
) : ChatRepository {

    private val demoOn = MutableStateFlow(false)

    private val active: StateFlow<ChatRepository> =
        combine(demoOn, agents.activeRepo) { onDemo, gateway -> if (onDemo) demo else gateway }
            .stateIn(scope, SharingStarted.Eagerly, agents.activeRepo.value)

    private fun <T> follow(scope: CoroutineScope, pick: (ChatRepository) -> StateFlow<T>): StateFlow<T> =
        active.flatMapLatest(pick).stateIn(scope, SharingStarted.Eagerly, pick(active.value).value)

    override val profile: StateFlow<AgentProfile> = follow(scope) { it.profile }
    override val gatewayFeatures = follow(scope) { it.gatewayFeatures }
    override val voiceInput = follow(scope) { it.voiceInput }
    override val directUsage = follow(scope) { it.directUsage }
    override val requestIssue = follow(scope) { it.requestIssue }
    override fun retryRequest() = active.value.retryRequest()
    override val connection: StateFlow<ConnectionState> = follow(scope) { it.connection }
    override val messages: StateFlow<List<ChatMessage>> = follow(scope) { it.messages }
    override val activity: StateFlow<AgentActivity> = follow(scope) { it.activity }
    override val tools: StateFlow<List<ToolCall>> = follow(scope) { it.tools }
    override val screen: StateFlow<ScreenState> = follow(scope) { it.screen }
    override val activitySnapshot: StateFlow<ActivitySnapshot?> = follow(scope) { it.activitySnapshot }
    override val hasOlder: StateFlow<Boolean> = follow(scope) { it.hasOlder }
    override val dots: StateFlow<List<SubDot>> = follow(scope) { it.dots }
    override val activeChat: StateFlow<String> = follow(scope) { it.activeChat }
    override val chatActivity: StateFlow<Map<String, AgentActivity>> = follow(scope) { it.chatActivity }
    override val unread: StateFlow<Map<String, Int>> = follow(scope) { it.unread }
    override val room: StateFlow<List<String>> = follow(scope) { it.room }
    override val speaker: StateFlow<String?> = follow(scope) { it.speaker }
    override val usage: StateFlow<UsageWindow?> = follow(scope) { it.usage }

    /** Démo seulement : les vrais agents s'appairent par [AgentRegistry] (un par gateway). */
    override suspend fun connect(config: GatewayConfig) {
        if (config.isDemo) {
            demoOn.value = true
            demo.connect(config)
        } else {
            leaveDemo()
            agents.activeRepo.value.connect(config)
        }
    }

    fun currentTarget(): ChatRepository = active.value

    val isDemo: Boolean get() = demoOn.value

    fun leaveDemo() {
        if (!demoOn.value) return
        demo.disconnect()
        demoOn.value = false
    }

    override fun disconnect() = active.value.disconnect()
    override fun send(text: String) = active.value.send(text)
    override fun toggleReaction(messageId: String, emoji: String) = active.value.toggleReaction(messageId, emoji)
    override fun answerApproval(messageId: String, approved: Boolean) = active.value.answerApproval(messageId, approved)
    override fun answerClarify(messageId: String, response: String) = active.value.answerClarify(messageId, response)
    override fun sendVoice(file: File, durationMs: Long, waveform: List<Float>) = active.value.sendVoice(file, durationMs, waveform)
    override fun sendFile(file: File, name: String, mime: String, caption: String) = active.value.sendFile(file, name, mime, caption)
    override suspend fun attachmentFile(messageId: String): File? = active.value.attachmentFile(messageId)
    override fun watchScreen(on: Boolean, takeover: Boolean) = active.value.watchScreen(on, takeover)
    override fun screenInput(input: ScreenInput) = active.value.screenInput(input)
    override fun refreshActivity() = active.value.refreshActivity()
    override fun loadOlder() = active.value.loadOlder()
    override fun selectChat(chat: String) = active.value.selectChat(chat)
    override fun setRoom(dots: List<String>) = active.value.setRoom(dots)
    override fun requestUsage(from: Instant, to: Instant) = active.value.requestUsage(from, to)
    override fun createDot(draft: SubDotDraft) = active.value.createDot(draft)
    override fun updateDot(id: String, draft: SubDotDraft) = active.value.updateDot(id, draft)
    override fun deleteDot(id: String) = active.value.deleteDot(id)
    override fun cronAction(id: String, action: CronAction) = active.value.cronAction(id, action)
    override fun stopTurn() = active.value.stopTurn()
    override fun finishTakeover(messageId: String) = active.value.finishTakeover(messageId)

    // Présence et jeton push concernent tous les vrais agents, même pendant une démo.
    override fun setForeground(foreground: Boolean) = agents.setForeground(foreground)
    override fun setPushToken(token: String) = agents.setPushToken(token)
}
