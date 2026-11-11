package app.dotshermes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dotshermes.BuildConfig
import app.dotshermes.data.AgentRegistry
import app.dotshermes.data.AgentSummary
import app.dotshermes.data.RoutingChatRepository
import app.dotshermes.data.AgentStore
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.AgentProfile
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.model.MessageContent
import app.dotshermes.data.model.ToolState
import app.dotshermes.data.model.SubDot
import app.dotshermes.data.model.SubDotDraft
import app.dotshermes.ui.dots.appearance
import app.dotshermes.ui.dots.orderedDots
import app.dotshermes.ui.chat.ChatRow
import app.dotshermes.ui.chat.buildChatRows
import app.dotshermes.ui.dot.DotAccessory
import app.dotshermes.ui.dot.DotSpecies
import app.dotshermes.ui.theme.ThemeMode
import app.dotshermes.ui.theme.DotPalette
import java.time.Instant
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import android.net.Uri
import app.dotshermes.attach.Attachments
import app.dotshermes.data.model.ActivitySnapshot
import app.dotshermes.data.model.UsageWindow
import app.dotshermes.data.model.CronAction
import app.dotshermes.data.model.ScreenInput
import app.dotshermes.data.model.ScreenState
import app.dotshermes.data.model.ToolCall
import app.dotshermes.ui.activity.TurnSummary
import app.dotshermes.ui.activity.summarizeTurns
import app.dotshermes.ui.activity.currentTurnTools
import app.dotshermes.ui.activity.isBrowsingNow
import kotlinx.coroutines.flow.map
import java.io.File
import app.dotshermes.voice.Playback
import app.dotshermes.voice.RecordingState
import app.dotshermes.voice.VoicePlayer
import app.dotshermes.voice.VoiceRecorder

/** Le dot dont la conversation est affichée : le principal, ou un sous-dot avec son apparence à lui. */
data class ActiveLook(val appearance: DotAppearance, val subDot: SubDot?)

data class ChatUiState(
    val agentName: String = "Claydot",
    val activity: AgentActivity = AgentActivity.Idle,
    val rows: List<ChatRow> = emptyList(),
    val messageCount: Int = 0,
    /** Salon de groupe : les sous-dots invités dans cette conversation (vide : conversation normale). */
    val room: List<SubDot> = emptyList(),
    /** Ceux qu'on peut inviter : tous les sous-dots, sauf celui dont c'est la conversation. */
    val roomCandidates: List<SubDot> = emptyList(),
    /** Apparence de chaque sous-dot, pour la mascotte au-dessus de ses bulles. */
    val speakerLooks: Map<String, DotAppearance> = emptyMap(),
    /** Salon en plein débat : le sous-dot qui a la parole, annoncé par l'en-tête (« Pebble a la parole »). */
    val speakingName: String? = null,
)

/** Le salon de la conversation affichée, tel que le dépôt le décrit, avant mise en forme. */
private data class RoomInfo(val invited: List<SubDot>, val candidates: List<SubDot>, val speaker: String?, val looks: Map<String, DotAppearance>)

private val DoneWords = listOf("fait", "done", "finished")

class HermesViewModel(
    /** L'agent affiché (ou la démo) : toutes les actions de l'écran passent par lui. */
    private val repository: RoutingChatRepository,
    private val agents: AgentRegistry,
    private val recorder: VoiceRecorder,
    private val player: VoicePlayer,
    private val attachments: Attachments,
    private val companion: app.dotshermes.voice.CompanionVoice? = null,
) : ViewModel() {

    val screen: StateFlow<ScreenState> = repository.screen
    val activitySnapshot: StateFlow<ActivitySnapshot?> = repository.activitySnapshot
    val usage: StateFlow<UsageWindow?> = repository.usage
    val directUsage = repository.directUsage

    /** Les derniers échanges et le travail qu'ils ont déclenché, pour la vue Activité. */
    val turns: StateFlow<List<TurnSummary>> = combine(repository.messages, repository.tools, agents.hiddenTurns, agents.activeId) { messages, tools, hidden, active ->
        val gone = active?.let { hidden[it] }.orEmpty()
        summarizeTurns(messages, tools, limit = 12 + gone.size).filterNot { it.id in gone }.take(12)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Outils du tour en cours (le dernier tour qui a des outils), pour « En ce moment ». */
    val liveTools: StateFlow<List<ToolCall>> = combine(repository.messages, repository.tools, repository.activity) { messages, tools, activity ->
        currentTurnTools(messages, tools, busy = activity != AgentActivity.Idle)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Pastille « navigue » : seulement si le tour en cours est sur le navigateur, pas parce qu'une image d'avant existe. */
    val browsing: StateFlow<Boolean> = liveTools.map(::isBrowsingNow)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val recording: StateFlow<RecordingState?> = recorder.state
    val playback: StateFlow<Playback> = player.state

    init {
        // Agents mémorisés : chacun se reconnecte au lancement, en parallèle.
        agents.connectAll()
        // Compte direct : une réponse terminée sans erreur est lue à voix haute (si la voix est réglée).
        companion?.let { voice ->
            viewModelScope.launch {
                var previous: AgentActivity = AgentActivity.Idle
                var lastSpoken: String? = repository.messages.value.lastOrNull()?.id
                repository.activity.collect { now ->
                    val finished = previous != AgentActivity.Idle && now == AgentActivity.Idle
                    previous = now
                    if (!finished || repository.gatewayFeatures.value || repository.requestIssue.value != null) return@collect
                    val reply = repository.messages.value.lastOrNull() ?: return@collect
                    val text = (reply.content as? MessageContent.Text)?.text ?: return@collect
                    if (reply.sender != app.dotshermes.data.model.Sender.Agent || reply.id == lastSpoken) return@collect
                    lastSpoken = reply.id
                    voice.speak(text)
                }
            }
        }
    }

    // Mains libres : pendant un enregistrement, la fin de la parole envoie le vocal.
    private val silence = app.dotshermes.voice.SilenceDetector()
    init {
        viewModelScope.launch {
            var lastElapsed = -1L
            recorder.state.collect { state ->
                if (state == null) { silence.reset(); lastElapsed = -1; return@collect }
                if (state.elapsedMs == lastElapsed || state.levels.isEmpty()) return@collect
                lastElapsed = state.elapsedMs
                if (companion?.state?.value?.handsFree != false && silence.feed(state.levels.last())) {
                    silence.reset()
                    sendRecording()
                }
            }
        }
    }

    // -- Personnalité et voix ------------------------------------------------------------------------
    val companionState = companion?.state
    val speech = companion?.status
    fun setPersonality(value: app.dotshermes.voice.Personality) = companion?.setPersonality(value)
    fun setSpeakReplies(on: Boolean) = companion?.setSpeakReplies(on)
    fun setHandsFree(on: Boolean) = companion?.setHandsFree(on)
    fun setVoiceId(id: String) = companion?.setVoiceId(id)
    suspend fun saveFishKey(key: String) { companion?.saveFishKey(key) }
    fun forgetFishKey() { viewModelScope.launch { companion?.forgetFishKey() } }
    fun stopSpeaking() = companion?.stop()
    fun listen(text: String) { companion?.speak(text, force = true) }
    val conversations = repository.conversations
    fun newConversation() { companion?.stop(); val t = repository.currentTarget(); viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { t.newConversation() } }
    fun openConversation(id: String) { companion?.stop(); val t = repository.currentTarget(); viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { t.openConversation(id) } }
    fun deleteConversation(id: String) { val t = repository.currentTarget(); viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { t.deleteConversation(id) } }
    fun clearConversation() {
        companion?.stop()
        val target = repository.currentTarget()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { target.clearHistory() }
    }

    val connection: StateFlow<ConnectionState> = repository.connection
    val profile: StateFlow<AgentProfile> = repository.profile
    private val _accountFailure = kotlinx.coroutines.flow.MutableStateFlow<app.dotshermes.data.direct.ApiFailure?>(null)
    val accountFailure: StateFlow<app.dotshermes.data.direct.ApiFailure?> = _accountFailure
    fun clearAccountFailure() { _accountFailure.value = null }
    fun editDirectAccount() { val target = repository.currentTarget(); viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { target.disconnect() } }
    /** Retour depuis l'écran de modification sans rien changer : le compte direct se rouvre avec sa clé enregistrée. */
    fun cancelEditDirect() { agents.activeId.value?.let { id -> viewModelScope.launch { agents.resumeDirect(id) } } }
    val gatewayFeatures = repository.gatewayFeatures
    val voiceInput = repository.voiceInput
    val imageInput = repository.imageInput
    val requestIssue = repository.requestIssue
    fun retryRequest() { val target = repository.currentTarget(); viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { target.retryRequest() } }
    suspend fun availableModels(provider: app.dotshermes.data.direct.ApiProvider, key: String) = agents.availableModels(provider, key)
    suspend fun connectApi(editorKey: String, provider: app.dotshermes.data.direct.ApiProvider, model: String, key: String): app.dotshermes.data.direct.ApiFailure? {
        if (agents.connectionEditor.value.key != editorKey) return app.dotshermes.data.direct.ApiFailure.Cancelled
        val target = agents.directTarget()
        return viewModelScope.async(kotlinx.coroutines.Dispatchers.IO) {
        try {
            // Modèle laissé vide (Groq) : on prend le meilleur modèle de conversation que ce compte propose.
            val chosen = model.trim().ifEmpty {
                app.dotshermes.data.direct.defaultModel(provider, agents.availableModels(provider, key))
                    ?: return@async app.dotshermes.data.direct.ApiFailure.ModelUnavailable
            }
            if (agents.connectDirect(provider, chosen, key, target)) null else app.dotshermes.data.direct.ApiFailure.Storage
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: app.dotshermes.data.direct.ApiException) { e.failure }
        catch (_: Exception) { app.dotshermes.data.direct.ApiFailure.Storage }
    }.await()
    }
    val connectionEditor = agents.connectionEditor
    /** Apparence de l'agent affiché (son dot principal) ; le thème est commun à tous. */
    val appearance: StateFlow<DotAppearance> = agents.activeAppearance

    // -- Agents ----------------------------------------------------------------------------------

    /** Les agents appairés, pour le sélecteur (affiché à partir de deux). */
    val agentList: StateFlow<List<AgentSummary>> = agents.summaries
    val activeAgent: StateFlow<String?> = agents.activeId
    /** Un agent est en cours d'ajout : l'écran d'appairage propose d'annuler. */
    val addingAgent: StateFlow<Boolean> = agents.pending.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, agents.pending.value != null)

    /** Non-lus des autres agents : la pastille de l'en-tête les compte avec ceux des sous-dots. */
    val otherAgentsUnread: StateFlow<Int> = combine(agents.summaries, agents.activeId) { all, active ->
        all.filter { it.id != active }.sumOf { it.unread }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Adresse proposée sur l'écran d'appairage : celle de l'agent affiché ; pour un agent de plus,
     * celle de l'agent d'avant (souvent le même domaine, une autre route) ; sinon le serveur par défaut.
     */
    fun gatewayUrlHint(): String {
        val links = agents.store.agents.value
        return agents.activeId.value?.let(agents.store::get)?.url
            ?: links.lastOrNull()?.url
            ?: DEFAULT_GATEWAY_URL
    }

    fun selectAgent(id: String) = agents.select(id)

    /** Le modèle qui répond pour l'agent affiché (compte direct) ; vide pour un serveur Hermes. */
    val activeModel: StateFlow<app.dotshermes.ui.chat.ActiveModel> = kotlinx.coroutines.flow.combine(agents.store.agents, agents.activeId) { list, id ->
        list.firstOrNull { it.id == id }?.let { app.dotshermes.ui.chat.ActiveModel(it.provider, it.model) } ?: app.dotshermes.ui.chat.ActiveModel(null, "")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, app.dotshermes.ui.chat.ActiveModel(null, ""))
    /** Le modèle du compte Groq (null sans compte Groq), pour le menu des modèles. */
    val groqModel: StateFlow<String?> = agents.store.agents.map { list -> list.firstOrNull { it.provider == app.dotshermes.data.direct.ApiProvider.Groq }?.model }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)


    /**
     * Choix dans le menu des modèles : un modèle Groq (le compte Groq garde sa clé et sa conversation)
     * ou un modèle du téléphone (compte « Sur ce téléphone », créé au premier choix).
     */
    fun switchModel(provider: app.dotshermes.data.direct.ApiProvider, model: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val key = if (provider == app.dotshermes.data.direct.ApiProvider.Local) app.dotshermes.data.direct.LocalModels.LOCAL_KEY else ""
            try { if (!agents.connectDirect(provider, model, key, agents.directTarget())) _accountFailure.value = app.dotshermes.data.direct.ApiFailure.Storage }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: app.dotshermes.data.direct.ApiException) { _accountFailure.value = e.failure }
            catch (_: Exception) { _accountFailure.value = app.dotshermes.data.direct.ApiFailure.Storage }
        }
    }

    fun addAgent() {
        agents.beginAdd()
    }

    fun cancelAddAgent() = agents.cancelAdd()

    val dots: StateFlow<List<SubDot>> = repository.dots
    val activeChat: StateFlow<String> = repository.activeChat
    val chatActivity: StateFlow<Map<String, AgentActivity>> = repository.chatActivity
    val unread: StateFlow<Map<String, Int>> = repository.unread

    val activeLook: StateFlow<ActiveLook> =
        combine(appearance, repository.dots, repository.activeChat) { main, dots, chat ->
            val dot = dots.firstOrNull { it.id == chat }
            ActiveLook(dot?.appearance(main) ?: main, dot)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ActiveLook(appearance.value, null))

    private val roomInfo = combine(repository.room, repository.speaker, repository.dots, repository.activeChat, appearance) { invited, speaker, dots, chat, main ->
        val byId = dots.associateBy { it.id }
        RoomInfo(
            invited = invited.mapNotNull(byId::get),
            candidates = orderedDots(dots).filter { it.id != chat },
            speaker = speaker,
            looks = dots.associate { it.id to it.appearance(main) },
        )
    }

    private val thread = combine(repository.messages, repository.tools, repository.hasOlder) { messages, tools, hasOlder -> Triple(messages, tools, hasOlder) }

    val chat: StateFlow<ChatUiState> =
        combine(activeLook, thread, repository.activity, roomInfo) { look, (messages, tools, hasOlder), activity, room ->
            val appearance = look.appearance
            // Un outil en cours prime sur « Réfléchit… » : l'en-tête dit ce que fait vraiment l'agent.
            val running = tools.lastOrNull { it.state == ToolState.Running }
            val shown = if (running != null && activity != AgentActivity.Typing) AgentActivity.Working("${running.label}…") else activity
            ChatUiState(
                agentName = appearance.displayName,
                activity = shown,
                rows = buildChatRows(messages, activity, tools = tools, hasOlder = hasOlder, speaker = room.speaker,
                    streamsInPlace = !repository.gatewayFeatures.value),
                messageCount = messages.size,
                room = room.invited,
                roomCandidates = room.candidates,
                speakerLooks = room.looks,
                speakingName = room.speaker?.takeIf { activity != AgentActivity.Idle }?.let { id -> room.looks[id]?.displayName },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    /** Salon de groupe de la conversation affichée : ces sous-dots débattront de chaque message (vide : fermé). */
    fun setRoom(dots: List<String>) = repository.setRoom(dots)

    /**
     * Déconnexion volontaire : on quitte la démo, ou on oublie l'agent affiché (jeton, apparence) pour
     * ne pas s'y reconnecter au lancement. Les autres agents ne sont pas touchés ; le suivant s'affiche.
     */
    fun disconnect() {
        agents.activeId.value?.let { id -> viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { agents.remove(id) } catch (_: Exception) { _accountFailure.value = app.dotshermes.data.direct.ApiFailure.Storage }
        } }
    }

    /** Retire un tour de « Récemment » (la conversation reste intacte). */
    fun hideTurn(key: String) { agents.activeId.value?.let { agents.hideTurns(it, listOf(key)) } }
    fun clearRecent() { agents.activeId.value?.let { id -> agents.hideTurns(id, turns.value.map { it.id }) } }
    fun isDirectAccount(): Boolean = agents.activeId.value?.let(agents.store::get)?.provider != null
    fun removeAccount(id: String) { viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        try { agents.remove(id) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { _accountFailure.value = app.dotshermes.data.direct.ApiFailure.Storage }
    } }

    fun send(text: String) {
        companion?.stop()
        if (text.isNotBlank()) repository.send(text.trim())
    }

    /** Micro : la permission est demandée par l'écran avant l'appel. */
    fun startRecording(): Boolean {
        player.stop()
        companion?.stop()
        return recorder.start()
    }

    fun sendRecording() {
        recorder.stop()?.let { repository.sendVoice(it.file, it.durationMs, it.waveform) }
    }

    fun cancelRecording() = recorder.cancel()

    /** Lecture d'un vocal : fichier local tout de suite, sinon téléchargé une fois auprès du gateway. */
    fun toggleVoice(messageId: String) {
        val current = player.state.value
        if (current.messageId == messageId && current.loading) return
        viewModelScope.launch {
            if (current.messageId != messageId) player.markLoading(messageId)
            val file = repository.attachmentFile(messageId)
            if (file == null) player.stop() else player.toggle(messageId, file)
        }
    }

    fun seekVoice(messageId: String, fraction: Float) = player.seek(messageId, fraction)

    /** Fichier choisi sur le téléphone : copié en privé puis envoyé, avec le brouillon comme légende. */
    fun sendAttachment(uri: Uri, caption: String, onTooBig: () -> Unit) {
        val target = repository.currentTarget()
        if (!target.gatewayFeatures.value && !target.imageInput.value) return
        viewModelScope.launch {
            attachments.import(uri, onTooBig)?.let { target.sendFile(it.file, it.name, it.mime, caption.trim()) }
        }
    }

    suspend fun attachmentFile(messageId: String): File? = repository.attachmentFile(messageId)

    fun openAttachment(file: File, mime: String) = attachments.open(file, mime)

    fun watchScreen(on: Boolean, takeover: Boolean) = repository.watchScreen(on, takeover)

    fun screenInput(input: ScreenInput) = repository.screenInput(input)

    /**
     * Fin de la prise en main. L'agent attend d'ordinaire sur une question (outil clarify, qui garde
     * son tour — et donc son navigateur — ouvert) : on y répond. Sinon, on le lui dit dans la conversation.
     */
    /** [doneAnswer] / [doneMessage] : réponse et message envoyés à l'agent, dans la langue du téléphone. */
    fun handBackScreen(doneAnswer: String, doneMessage: String) {
        // Demande explicite de l'agent (outil browser_request_takeover) : c'est elle qui retient son tour.
        repository.messages.value.lastOrNull { (it.content as? MessageContent.Takeover)?.pending == true }?.let {
            repository.finishTakeover(it.id)
            return
        }
        val pending = repository.messages.value.lastOrNull { (it.content as? MessageContent.Clarify)?.answer == null && it.content is MessageContent.Clarify }
        if (pending != null) {
            val choices = (pending.content as MessageContent.Clarify).choices
            // Le choix proposé par l'agent qui veut dire « c'est fait » (il peut répondre en français ou en anglais).
            val done = choices.firstOrNull { c -> DoneWords.any { c.contains(it, ignoreCase = true) } }
            repository.answerClarify(pending.id, done ?: doneAnswer)
        } else {
            repository.send(doneMessage)
        }
    }

    fun refreshActivity() = repository.refreshActivity()

    fun requestUsage(from: Instant, to: Instant) = repository.requestUsage(from, to)

    fun cronAction(id: String, action: CronAction) = repository.cronAction(id, action)

    fun stopTurn() { val target = repository.currentTarget(); viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { target.stopTurn() } }

    fun loadOlder() = repository.loadOlder()

    fun selectChat(chat: String) = repository.selectChat(chat)

    fun createDot(draft: SubDotDraft) = repository.createDot(draft)

    fun updateDot(id: String, draft: SubDotDraft) = repository.updateDot(id, draft)

    fun deleteDot(id: String) = repository.deleteDot(id)

    override fun onCleared() {
        recorder.cancel()
        player.stop()
    }

    fun toggleReaction(messageId: String, emoji: String) = repository.toggleReaction(messageId, emoji)

    fun answerApproval(messageId: String, approved: Boolean) = repository.answerApproval(messageId, approved)

    fun answerClarify(messageId: String, response: String) = repository.answerClarify(messageId, response)

    /** Réglages du dot principal de l'agent affiché : chaque agent a son nom, sa mascotte, sa couleur. */
    private val activeStore get() = agents.appearance(agents.activeId.value ?: AgentStore.PRIMARY_ID)

    fun setPalette(palette: DotPalette) = activeStore.setPalette(palette)

    fun setAgentName(name: String) = activeStore.setName(name)

    fun setSpecies(species: DotSpecies) = activeStore.setSpecies(species)

    fun setTheme(theme: ThemeMode) = agents.theme.setTheme(theme)

    fun setAccessory(accessory: DotAccessory) = activeStore.setAccessory(accessory)

    private companion object {
        /** Fixée à la construction (variable DEFAULT_GATEWAY_URL) ; vide par défaut. */
        val DEFAULT_GATEWAY_URL: String = BuildConfig.DEFAULT_GATEWAY_URL
    }
}
