package app.dotshermes.data.gateway

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.dotshermes.R
import app.dotshermes.data.ChatRepository
import app.dotshermes.data.toolLabel
import app.dotshermes.data.model.ActivitySnapshot
import app.dotshermes.data.model.UsageWindow
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.AgentProfile
import app.dotshermes.data.model.ApprovalStatus
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.model.CronAction
import app.dotshermes.data.model.Delivery
import app.dotshermes.data.model.GatewayConfig
import app.dotshermes.data.model.MAIN_CHAT
import app.dotshermes.data.model.MessageContent
import app.dotshermes.data.model.ScreenInput
import app.dotshermes.data.model.ScreenState
import app.dotshermes.data.model.Sender
import app.dotshermes.data.model.SubDot
import app.dotshermes.data.model.SubDotDraft
import app.dotshermes.data.model.ToolCall
import app.dotshermes.data.webpair.WebSession
import app.dotshermes.data.webpair.WebTokenResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val TAG = "GatewayChat"
private val ReconnectBackoffMs = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

/** Patience demandée par un 429 (en-tête `Retry-After`, en secondes — guard.py) ; 0 si absente ou illisible. */
internal fun retryAfterSeconds(response: Response?): Long =
    response?.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0 } ?: 0L

/** Délai avant la prochaine tentative : backoff habituel, sauf un `Retry-After` plus long (429 du guard.py). */
internal fun reconnectDelayMs(attempt: Int, retryAfterSeconds: Long = 0): Long =
    maxOf(ReconnectBackoffMs[minOf(attempt, ReconnectBackoffMs.lastIndex)], retryAfterSeconds * 1000)

/** L'agent servi par un gateway : identifiant stable (routage des push) et nom de son profil Hermes. */
data class ServerAgent(val id: String, val name: String)

/** Tout ce que l'app sait d'une conversation (dot principal ou sous-dot). */
private data class ChatData(
    val messages: List<ChatMessage> = emptyList(),
    val tools: List<ToolCall> = emptyList(),
    val activity: AgentActivity = AgentActivity.Idle,
    val hasOlder: Boolean = false,
    val oldestSeq: Long = 0,
    /** Première page reçue ; sinon elle est demandée à l'ouverture de la conversation. */
    val loaded: Boolean = false,
    val loadingOlder: Boolean = false,
    val unread: Int = 0,
    /** Salon de groupe : sous-dots invités ; chaque message tapé ici leur est soumis. */
    val room: List<String> = emptyList(),
    /** Sous-dot qui parle en ce moment dans le salon (trame `activity`) ; null : le dot lui-même. */
    val speaker: String? = null,
)

/**
 * Client WebSocket du plugin « dots » du gateway Hermes (docs/protocole-gateway.md).
 *
 * Une seule socket pour toutes les conversations : chaque événement porte sa `chat`, rangé dans
 * [chats] ; les flux publics (`messages`, `tools`…) décrivent la conversation affichée ([activeChat]).
 * Le gateway est la source de vérité : à chaque (re)connexion, `hello` rejoue ce qui a été manqué
 * depuis [lastSeq], et les messages tapés hors ligne partent dès le `welcome`.
 */
class GatewayChatRepository(
    /** Pour les messages de statut et d'erreur, dans la langue du téléphone. */
    private val context: Context,
    private val scope: CoroutineScope,
    /** Où ranger les vocaux et pièces jointes téléchargés depuis le gateway (cache : rechargeables). */
    private val audioCacheDir: File,
    /** Une demande de feu vert a été tranchée (ici ou ailleurs) : sa notification peut partir. */
    private val onApprovalResolved: (approvalId: String) -> Unit = {},
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build(),
) : ChatRepository {

    override val profile: StateFlow<AgentProfile> =
        MutableStateFlow(AgentProfile(name = "Hermes", tagline = context.getString(R.string.profile_tagline_gateway))).asStateFlow()

    /** Identité annoncée par le gateway dans `welcome` (plugin 1.2+) ; null avant, ou avec un plugin plus ancien. */
    private val _serverAgent = MutableStateFlow<ServerAgent?>(null)
    val serverAgent: StateFlow<ServerAgent?> = _serverAgent.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val chats = MutableStateFlow(mapOf(MAIN_CHAT to ChatData()))
    private val _activeChat = MutableStateFlow(MAIN_CHAT)
    override val activeChat: StateFlow<String> = _activeChat.asStateFlow()

    private fun <T> ofActive(pick: (ChatData) -> T): StateFlow<T> =
        combine(chats, _activeChat) { all, active -> pick(all[active] ?: ChatData()) }
            .stateIn(scope, SharingStarted.Eagerly, pick(ChatData()))

    override val messages: StateFlow<List<ChatMessage>> = ofActive { it.messages }
    override val tools: StateFlow<List<ToolCall>> = ofActive { it.tools }
    override val activity: StateFlow<AgentActivity> = ofActive { it.activity }
    override val hasOlder: StateFlow<Boolean> = ofActive { it.hasOlder }
    override val room: StateFlow<List<String>> = ofActive { it.room }
    override val speaker: StateFlow<String?> = ofActive { it.speaker }

    override val chatActivity: StateFlow<Map<String, AgentActivity>> =
        chats.map { all -> all.mapValues { it.value.activity } }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    override val unread: StateFlow<Map<String, Int>> =
        chats.map { all -> all.mapValues { it.value.unread }.filterValues { it > 0 } }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _dots = MutableStateFlow<List<SubDot>>(emptyList())
    override val dots: StateFlow<List<SubDot>> = _dots.asStateFlow()

    /** Création en cours : la conversation du nouveau dot s'ouvre dès que le gateway la confirme. */
    @Volatile private var pendingDotRef: String? = null

    @Volatile private var foreground = true

    /** Cet agent est celui affiché (voir [setShown]). */
    @Volatile private var shown = true

    /**
     * L'agent passe à l'écran (ou le quitte, quand on en choisit un autre). Caché, ses messages
     * comptent comme non lus même dans sa conversation ouverte ; affiché, celle-ci redevient lue.
     */
    fun setShown(shown: Boolean) {
        this.shown = shown
        if (shown) updateChat(_activeChat.value) { it.copy(unread = 0) }
    }
    @Volatile private var pushToken: String? = null

    private val _screen = MutableStateFlow(ScreenState())
    override val screen: StateFlow<ScreenState> = _screen.asStateFlow()

    private val _activitySnapshot = MutableStateFlow<ActivitySnapshot?>(null)
    override val activitySnapshot: StateFlow<ActivitySnapshot?> = _activitySnapshot.asStateFlow()

    private val _usage = MutableStateFlow<UsageWindow?>(null)
    override val usage: StateFlow<UsageWindow?> = _usage.asStateFlow()

    /** Le flux d'écran est demandé : à renvoyer après une reconnexion. */
    @Volatile private var watchingScreen = false

    /** Téléchargements de vocaux et pièces jointes en cours, par identifiant distant. */
    private val audioRequests = ConcurrentHashMap<String, CompletableDeferred<ByteArray?>>()

    /** Jetons web demandés (appairage d'un navigateur), par `ref` : la réponse `web_token` la reprend. */
    private val webTokenRequests = ConcurrentHashMap<String, CompletableDeferred<WebTokenResult>>()

    /** Navigateurs appairés avec cet agent ; null tant que la liste n'a pas été demandée ([requestWebSessions]). */
    private val _webSessions = MutableStateFlow<List<WebSession>?>(null)
    val webSessions: StateFlow<List<WebSession>?> = _webSessions.asStateFlow()

    @Volatile private var config: GatewayConfig? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var lastSeq = 0L
    @Volatile private var helloSentAt = 0L
    private var attempt = 0
    private var reconnectJob: Job? = null

    /** Issue de la première connexion : "" si `welcome` reçu, sinon le message d'erreur. */
    @Volatile private var firstOutcome: CompletableDeferred<String>? = null

    override suspend fun connect(config: GatewayConfig) {
        closeSocket()
        this.config = config
        lastSeq = 0
        attempt = 0
        resetChats()
        _connection.value = ConnectionState.Connecting(context.getString(R.string.gateway_connecting))
        val outcome = CompletableDeferred<String>().also { firstOutcome = it }
        try {
            open(config)
        } catch (e: IllegalArgumentException) {
            outcome.complete(context.getString(R.string.gateway_error_invalid_url))
        }
        val error = withTimeoutOrNull(15_000) { outcome.await() } ?: context.getString(R.string.gateway_error_timeout)
        firstOutcome = null
        if (error.isNotEmpty()) {
            this.config = null
            closeSocket()
            _connection.value = ConnectionState.Failed(error)
        }
    }

    override fun disconnect() {
        config = null
        closeSocket()
        firstOutcome?.complete(context.getString(R.string.gateway_error_cancelled))
        resetChats()
        _connection.value = ConnectionState.Disconnected
    }

    private fun resetChats() {
        chats.value = mapOf(MAIN_CHAT to ChatData())
        _activeChat.value = MAIN_CHAT
        _dots.value = emptyList()
    }

    // -- Conversations ------------------------------------------------------------------------------

    /** Conversation demandée avant que la liste des dots soit connue (ouverture depuis une notification). */
    @Volatile private var requestedChat: String? = null

    override fun selectChat(chat: String) {
        if (chat != MAIN_CHAT && _dots.value.none { it.id == chat }) {
            requestedChat = chat
            return
        }
        requestedChat = null
        _activeChat.value = chat
        updateChat(chat) { it.copy(unread = 0) }
        requestFirstPage(chat)
    }

    /** Première ouverture d'une conversation : sa page la plus récente. */
    private fun requestFirstPage(chat: String) {
        val data = chats.value[chat] ?: ChatData()
        if (data.loaded || data.loadingOlder) return
        val ws = socket ?: return
        updateChat(chat) { it.copy(loadingOlder = true) }
        ws.send(GatewayProtocol.historyPage(0, chat))
    }

    override fun setRoom(dots: List<String>) {
        val chat = _activeChat.value
        val known = _dots.value.mapTo(HashSet()) { it.id }
        updateChat(chat) { it.copy(room = dots.distinct().filter { id -> id in known && id != chat }) }
    }

    override fun createDot(draft: SubDotDraft) {
        val ref = UUID.randomUUID().toString()
        pendingDotRef = ref
        socket?.send(GatewayProtocol.dotCreate(ref, draft))
    }

    override fun updateDot(id: String, draft: SubDotDraft) {
        socket?.send(GatewayProtocol.dotUpdate(id, draft))
    }

    override fun deleteDot(id: String) {
        socket?.send(GatewayProtocol.dotDelete(id))
    }

    // -- Envoi --------------------------------------------------------------------------------------

    override fun send(text: String) = sendTo(_activeChat.value, text)

    private fun sendTo(chat: String, text: String) {
        val message = userMessage(MessageContent.Text(text))
        updateMessages(chat) { it + message }
        // Hors ligne, le message reste « Envoi… » et part au prochain welcome (sans salon : le débat
        // n'aurait plus de sens des heures plus tard, le dot répond seul).
        socket?.send(GatewayProtocol.message(message.id, text, chat, room = chats.value[chat]?.room.orEmpty()))
    }

    override fun sendVoice(file: File, durationMs: Long, waveform: List<Float>) {
        val chat = _activeChat.value
        val message = userMessage(MessageContent.Voice(durationMs, waveform, localPath = file.path))
        updateMessages(chat) { it + message }
        scope.launch(Dispatchers.IO) { sendVoiceFrame(chat, message) }
    }

    private fun sendVoiceFrame(chat: String, message: ChatMessage) {
        val voice = message.content as? MessageContent.Voice ?: return
        val bytes = voice.localPath?.let { runCatching { File(it).readBytes() }.getOrNull() } ?: return
        socket?.send(GatewayProtocol.voice(message.id, bytes, voice.durationMs, voice.waveform, chat))
    }

    override fun sendFile(file: File, name: String, mime: String, caption: String) {
        val chat = _activeChat.value
        val message = userMessage(MessageContent.File(name, mime, file.length(), localPath = file.path, caption = caption))
        updateMessages(chat) { it + message }
        scope.launch(Dispatchers.IO) { sendFileFrame(chat, message) }
    }

    private fun sendFileFrame(chat: String, message: ChatMessage) {
        val attachment = message.content as? MessageContent.File ?: return
        val bytes = attachment.localPath?.let { runCatching { File(it).readBytes() }.getOrNull() } ?: return
        socket?.send(GatewayProtocol.file(message.id, bytes, attachment.name, attachment.mime, attachment.caption, chat))
    }

    private fun userMessage(content: MessageContent) = ChatMessage(
        id = UUID.randomUUID().toString(), sender = Sender.User, content = content, sentAt = Instant.now(), delivery = Delivery.Sending,
    )

    override suspend fun attachmentFile(messageId: String): File? {
        val message = findMessage(messageId) ?: return null
        val (localPath, cacheName, isVoice) = when (val c = message.content) {
            is MessageContent.Voice -> Triple(c.localPath, "${message.remoteId}.m4a", true)
            is MessageContent.File -> Triple(c.localPath, "${message.remoteId}-${c.name.replace('/', '_')}", false)
            else -> return null
        }
        localPath?.let { File(it) }?.takeIf { it.exists() }?.let { return it }
        val remoteId = message.remoteId ?: return null
        val cached = File(audioCacheDir, cacheName)
        if (cached.exists()) return cached
        val ws = socket ?: return null
        val request = audioRequests.getOrPut(remoteId) { CompletableDeferred() }
        ws.send(if (isVoice) GatewayProtocol.audioGet(remoteId) else GatewayProtocol.fileGet(remoteId))
        val bytes = withTimeoutOrNull(60_000) { request.await() }
        if (bytes == null) {
            audioRequests.remove(remoteId)
            return null
        }
        return withContext(Dispatchers.IO) {
            audioCacheDir.mkdirs()
            cached.writeBytes(bytes)
            cached
        }
    }

    /** Feu vert donné depuis une notification, quand l'app est déjà connectée. */
    fun answerApprovalIfConnected(approvalId: String, approved: Boolean): Boolean {
        if (socket == null || _connection.value !is ConnectionState.Connected) return false
        socket?.send(GatewayProtocol.approval(approvalId, approved))
        updateWhere({ it.remoteId == approvalId }) { m ->
            val approval = m.content as? MessageContent.Approval ?: return@updateWhere m
            m.copy(content = approval.copy(status = if (approved) ApprovalStatus.Approved else ApprovalStatus.Declined))
        }
        return true
    }

    override fun watchScreen(on: Boolean, takeover: Boolean) {
        watchingScreen = on
        socket?.send(GatewayProtocol.screenWatch(on, takeover))
        if (!on) _screen.update { it.copy(live = false) }
    }

    override fun loadOlder() {
        val chat = _activeChat.value
        val data = chats.value[chat] ?: return
        if (!data.hasOlder || data.loadingOlder || data.oldestSeq <= 1) return
        val ws = socket ?: return
        updateChat(chat) { it.copy(loadingOlder = true) }
        ws.send(GatewayProtocol.historyPage(data.oldestSeq, chat))
    }

    override fun screenInput(input: ScreenInput) {
        socket?.send(GatewayProtocol.screenInput(input))
    }

    override fun refreshActivity() {
        socket?.send(GatewayProtocol.activityGet())
    }

    override fun requestUsage(from: Instant, to: Instant) {
        socket?.send(GatewayProtocol.usageGet(from, to))
    }

    override fun cronAction(id: String, action: CronAction) {
        socket?.send(GatewayProtocol.cronAction(id, action.wire))
    }

    override fun finishTakeover(messageId: String) {
        val target = findMessage(messageId) ?: return
        val takeover = target.content as? MessageContent.Takeover ?: return
        updateWhere({ it.id == messageId }) { it.copy(content = takeover.copy(pending = false)) }
        target.remoteId?.let { socket?.send(GatewayProtocol.takeoverDone(it)) }
    }

    override fun stopTurn() {
        socket?.send(GatewayProtocol.stop(_activeChat.value))
    }

    override fun toggleReaction(messageId: String, emoji: String) {
        val target = findMessage(messageId) ?: return
        val next = if (target.reactions[Sender.User] == emoji) null else emoji
        updateWhere({ it.id == messageId }) { m ->
            m.copy(reactions = if (next == null) m.reactions - Sender.User else m.reactions + (Sender.User to next))
        }
        target.remoteId?.let { socket?.send(GatewayProtocol.reaction(it, next)) }
    }

    override fun answerApproval(messageId: String, approved: Boolean) {
        val target = findMessage(messageId) ?: return
        val approval = target.content as? MessageContent.Approval ?: return
        if (approval.status != ApprovalStatus.Pending) return
        val status = if (approved) ApprovalStatus.Approved else ApprovalStatus.Declined
        updateWhere({ it.id == messageId }) { it.copy(content = approval.copy(status = status)) }
        socket?.send(GatewayProtocol.approval(target.remoteId ?: messageId, approved))
    }

    override fun answerClarify(messageId: String, response: String) {
        val target = findMessage(messageId) ?: return
        val clarify = target.content as? MessageContent.Clarify ?: return
        if (clarify.answer != null) return
        updateWhere({ it.id == messageId }) { it.copy(content = clarify.copy(answer = response)) }
        socket?.send(GatewayProtocol.clarify(target.remoteId ?: messageId, response))
    }

    override fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        socket?.send(GatewayProtocol.presence(foreground))
    }

    override fun setPushToken(token: String) {
        if (token == pushToken) return
        pushToken = token
        socket?.send(GatewayProtocol.pushToken(token))
    }

    /**
     * Envoi ponctuel hors interface (réponse rapide depuis une notification) dans la conversation
     * [chat] : passe par la socket vivante si l'app tourne déjà, sinon l'appelant ouvre la sienne.
     */
    fun sendIfConnected(text: String, chat: String = MAIN_CHAT): Boolean {
        if (socket == null || _connection.value !is ConnectionState.Connected) return false
        sendTo(chat, text)
        return true
    }

    // -- Sessions web (docs/appairage-web.md) -------------------------------------------------------

    /**
     * Demande au gateway un jeton web pour le navigateur d'[origin] (appairage par QR code). Seule
     * l'app, avec le jeton maître, peut en obtenir ; le `DOTS_TOKEN` ne quitte jamais le téléphone.
     * [WebTokenResult.Unavailable] si l'agent n'est pas connecté ou ne répond pas en [timeoutMs].
     */
    suspend fun createWebToken(origin: String, label: String, timeoutMs: Long = 10_000): WebTokenResult {
        val ws = socket ?: return WebTokenResult.Unavailable
        if (_connection.value !is ConnectionState.Connected) return WebTokenResult.Unavailable
        val ref = UUID.randomUUID().toString()
        val reply = CompletableDeferred<WebTokenResult>()
        webTokenRequests[ref] = reply
        try {
            if (!ws.send(GatewayProtocol.webTokenCreate(ref, origin, label))) return WebTokenResult.Unavailable
            return withTimeoutOrNull(timeoutMs) { reply.await() } ?: WebTokenResult.Unavailable
        } finally {
            webTokenRequests.remove(ref)
        }
    }

    /** Redemande la liste des navigateurs appairés ; elle arrive dans [webSessions]. */
    fun requestWebSessions(): Boolean {
        if (_connection.value !is ConnectionState.Connected) return false
        return socket?.send(GatewayProtocol.webSessionsGet()) == true
    }

    /** Révoque un navigateur : le gateway ferme ses sockets et renvoie la liste à jour. */
    fun revokeWebSession(id: String): Boolean {
        if (_connection.value !is ConnectionState.Connected) return false
        return socket?.send(GatewayProtocol.webRevoke(id)) == true
    }

    private fun onWebToken(frame: JSONObject) {
        val reply = webTokenRequests[frame.optString("ref")] ?: return
        val token = frame.optString("token")
        reply.complete(
            when {
                frame.optString("error") == "origin_not_allowed" -> WebTokenResult.OriginNotAllowed(frame.optString("origin"))
                token.isNotEmpty() -> WebTokenResult.Granted(token, frame.optString("id"))
                else -> WebTokenResult.Unavailable
            },
        )
    }

    // -- Transport ---------------------------------------------------------------------------------

    private fun open(config: GatewayConfig) {
        val request = Request.Builder()
            .url(config.url.trim())
            .header("Authorization", "Bearer ${config.token.trim()}")
            .build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun closeSocket() {
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, null)
        socket = null
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== socket) return
            if (_connection.value is ConnectionState.Connecting) {
                _connection.value = ConnectionState.Connecting(context.getString(R.string.gateway_syncing))
            }
            helloSentAt = SystemClock.elapsedRealtime()
            webSocket.send(GatewayProtocol.hello(lastSeq, foreground, pushToken))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            try {
                handleFrame(JSONObject(text))
            } catch (e: JSONException) {
                Log.w(TAG, "Trame illisible", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket === socket) onDropped(null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== socket) return
            val code = response?.code
            // Un 429 (garde-fou anti force-brute, guard.py) n'est pas un gateway injoignable : le token est
            // bon, l'appairage reste, et la prochaine tentative attend au moins le Retry-After demandé.
            val retryAfter = retryAfterSeconds(response)
            val reason = when (code) {
                401 -> context.getString(R.string.gateway_error_token_refused)
                404 -> context.getString(R.string.gateway_error_not_found)
                429 -> context.getString(R.string.gateway_error_rate_limited, retryAfter)
                else -> context.getString(R.string.gateway_error_unreachable, t.message ?: t.javaClass.simpleName)
            }
            onDropped(reason, tokenRefused = code == 401, retryAfterSeconds = retryAfter)
        }
    }

    /** Coupure : échec de la première connexion, ou reconnexion avec backoff sans quitter le chat. */
    private fun onDropped(reason: String?, tokenRefused: Boolean = false, retryAfterSeconds: Long = 0) {
        socket = null
        firstOutcome?.let { pending ->
            pending.complete(reason ?: context.getString(R.string.gateway_error_closed))
            return
        }
        val current = config ?: return
        chats.update { all -> all.mapValues { it.value.copy(activity = AgentActivity.Idle, speaker = null, loadingOlder = false) } }
        if (tokenRefused) {
            config = null
            _connection.value = ConnectionState.Failed(reason!!)
            return
        }
        (_connection.value as? ConnectionState.Connected)?.let { _connection.value = it.copy(online = false) }
        val wait = reconnectDelayMs(attempt, retryAfterSeconds)
        attempt++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            if (config === current && socket == null) {
                try {
                    open(current)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Reconnexion impossible", e)
                }
            }
        }
    }

    /** Le plugin envoie ses libellés d'outils en français : on les remplace par ceux de l'app, traduits. */
    private fun tool(event: JSONObject): ToolCall? =
        GatewayProtocol.toTool(event) { name -> toolLabel(name)?.let(context::getString) }

    private fun handleFrame(frame: JSONObject) {
        when (frame.optString("type")) {
            "welcome" -> onWelcome(frame)
            "ack" -> {
                val remoteId = frame.optString("id")
                updateWhere({ it.id == frame.optString("client_id") }) {
                    it.copy(remoteId = remoteId, delivery = maxOf(it.delivery, Delivery.Sent), seq = frame.optLong("seq").takeIf { s -> s > 0 })
                }
                bumpSeq(frame)
            }
            "read" -> updateWhere({ it.remoteId == frame.optString("id") }) { it.copy(delivery = Delivery.Read) }
            "activity" -> updateChat(GatewayProtocol.chatOf(frame)) {
                val activity = GatewayProtocol.activity(frame.optString("state"))
                it.copy(activity = activity, speaker = GatewayProtocol.speakerOf(frame).takeIf { activity != AgentActivity.Idle })
            }
            "message", "approval", "clarify" -> {
                GatewayProtocol.toMessage(frame)?.let { message ->
                    val chat = GatewayProtocol.chatOf(frame)
                    upsert(chat, listOf(message))
                    // Un message de l'agent dans une conversation cachée : pastille sur son dot.
                    // Agent pas affiché (un autre agent est à l'écran) : même sa conversation ouverte compte.
                    if ((chat != _activeChat.value || !shown) && message.sender == Sender.Agent) updateChat(chat) { it.copy(unread = it.unread + 1) }
                }
                bumpSeq(frame)
            }
            "tool" -> {
                tool(frame)?.let { upsertTools(GatewayProtocol.chatOf(frame), listOf(it)) }
                bumpSeq(frame)
            }
            "tool_update" -> chats.update { all ->
                all.mapValues { (_, data) ->
                    if (data.tools.none { it.id == frame.optString("id") }) data
                    else data.copy(tools = data.tools.map { tool ->
                        if (tool.id != frame.optString("id")) tool
                        else tool.copy(
                            state = GatewayProtocol.toolState(frame.optString("state")),
                            durationMs = frame.optLong("duration_ms", -1).takeIf { it >= 0 },
                        )
                    })
                }
            }
            "transcript" -> updateWhere({ it.remoteId == frame.optString("id") }) { m ->
                val voice = m.content as? MessageContent.Voice ?: return@updateWhere m
                m.copy(content = voice.copy(transcript = frame.optString("text")))
            }
            "screen_frame" -> {
                val bytes = runCatching { Base64.getDecoder().decode(frame.optString("data")) }.getOrNull()
                if (bytes != null) {
                    _screen.value = ScreenState(
                        frame = bytes, url = frame.optString("url"), title = frame.optString("title"),
                        live = frame.optBoolean("live"), receivedAt = Instant.now(),
                    )
                }
            }
            "screen_idle" -> _screen.update { it.copy(live = false, idle = true) }
            "history_page" -> onHistoryPage(frame)
            "dots" -> onDots(GatewayProtocol.toDots(frame.optJSONArray("dots")))
            "dot_created" -> if (frame.optString("ref") == pendingDotRef) {
                pendingDotRef = null
                val id = frame.optString("id")
                // La liste arrive juste après : on prépare la conversation (vide, donc chargée) et on l'ouvre.
                updateChat(id) { it.copy(loaded = true) }
                _activeChat.value = id
            }
            "dot_deleted" -> {
                val id = frame.optString("id")
                if (_activeChat.value == id) _activeChat.value = MAIN_CHAT
                chats.update { it - id }
            }
            "takeover_done" -> updateWhere({ it.remoteId == frame.optString("id") }) { m ->
                (m.content as? MessageContent.Takeover)?.let { m.copy(content = it.copy(pending = false)) } ?: m
            }
            "activity_snapshot" -> _activitySnapshot.value = GatewayProtocol.toActivity(frame)
            "usage" -> _usage.value = GatewayProtocol.toUsage(frame)
            "audio", "file_data" -> {
                val bytes = frame.optString("data").takeIf { it.isNotEmpty() }?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                audioRequests.remove(frame.optString("id"))?.complete(bytes)
            }
            "edit" -> updateWhere({ it.remoteId == frame.optString("id") }) {
                it.copy(content = MessageContent.Text(frame.optString("text")))
            }
            "reaction" -> {
                val emoji = frame.optString("emoji").takeIf { !frame.isNull("emoji") && it.isNotEmpty() }
                updateWhere({ it.remoteId == frame.optString("message_id") }) { m ->
                    m.copy(reactions = if (emoji == null) m.reactions - Sender.Agent else m.reactions + (Sender.Agent to emoji))
                }
            }
            "approval_resolved" -> updateWhere({ it.remoteId == frame.optString("approval_id") }) { m ->
                onApprovalResolved(frame.optString("approval_id"))
                val approval = m.content as? MessageContent.Approval ?: return@updateWhere m
                val status = if (frame.optString("choice") == "deny") ApprovalStatus.Declined else ApprovalStatus.Approved
                m.copy(content = approval.copy(status = status))
            }
            "web_token" -> onWebToken(frame)
            "web_sessions" -> _webSessions.value = GatewayProtocol.toWebSessions(frame)
            "error" -> {
                Log.w(TAG, "Erreur gateway : ${frame.optString("code")}")
                // Plugin d'avant les sessions web : la trame est inconnue, l'erreur ne porte pas de `ref`.
                // Seules nos demandes de jeton peuvent la provoquer ici : on les clôt sans attendre le délai.
                if (frame.optString("code") == "unknown_type") webTokenRequests.values.forEach { it.complete(WebTokenResult.Unsupported) }
            }
        }
    }

    private fun onDots(dots: List<SubDot>) {
        _dots.value = dots
        val known = dots.mapTo(HashSet()) { it.id } + MAIN_CHAT
        // Dot fermé ailleurs (autre appareil) : sa conversation disparaît aussi ici.
        if (_activeChat.value !in known) _activeChat.value = MAIN_CHAT
        // Un sous-dot fermé quitte aussi les salons où il était invité.
        chats.update { all -> all.filterKeys { it in known }.mapValues { (_, data) -> data.copy(room = data.room.filter { it in known }) } }
        requestedChat?.takeIf { it in known }?.let(::selectChat)
    }

    private fun onWelcome(frame: JSONObject) {
        val current = config ?: return
        attempt = 0
        frame.optString("agent_id").takeIf { it.isNotEmpty() }?.let { _serverAgent.value = ServerAgent(it, frame.optString("agent")) }
        val history = frame.optJSONArray("history")
        val replayed = if (history == null) emptyList() else List(history.length()) { history.getJSONObject(it) }
        onDots(GatewayProtocol.toDots(frame.optJSONArray("dots")))
        if (frame.optBoolean("reset")) {
            // Nouvelle fenêtre (première connexion ou trop de retard) : le dot principal repart de sa page
            // la plus récente, les sous-dots rechargeront la leur à l'ouverture. On garde le non-envoyé.
            chats.update { all ->
                all.mapValues { (chat, data) ->
                    ChatData(messages = data.messages.filter { it.sender == Sender.User && it.remoteId == null }, loaded = false, room = data.room)
                        .let { if (chat == MAIN_CHAT) it.copy(hasOlder = frame.optBoolean("has_more"), loaded = true) else it }
                }
            }
            val mainMessages = replayed.mapNotNull(GatewayProtocol::toMessage)
            updateMessages(MAIN_CHAT) { unsent -> mainMessages + unsent }
            upsertTools(MAIN_CHAT, replayed.filter { it.optString("type") == "tool" }.mapNotNull(::tool))
            updateChat(MAIN_CHAT) { it.copy(oldestSeq = replayed.minOfOrNull { e -> e.optLong("seq") } ?: 0L) }
        } else {
            replayed.groupBy(GatewayProtocol::chatOf).forEach { (chat, events) ->
                upsert(chat, events.mapNotNull(GatewayProtocol::toMessage))
                upsertTools(chat, events.filter { it.optString("type") == "tool" }.mapNotNull(::tool))
            }
        }
        lastSeq = maxOf(lastSeq, frame.optLong("seq"))
        val activities = frame.optJSONObject("activities")
        val speakers = frame.optJSONObject("speakers")
        chats.update { all ->
            all.mapValues { (chat, data) ->
                data.copy(activity = GatewayProtocol.activity(activities?.optString(chat).orEmpty()), speaker = GatewayProtocol.speakerOf(speakers, chat))
            }
        }
        _connection.value = ConnectionState.Connected(
            gatewayUrl = current.url,
            latencyMs = (SystemClock.elapsedRealtime() - helloSentAt).toInt(),
        )
        firstOutcome?.complete("")
        if (watchingScreen) socket?.send(GatewayProtocol.screenWatch(true))
        requestFirstPage(_activeChat.value)
        // Renvoie ce qui a été tapé pendant la coupure ; le plugin déduplique par client_id.
        chats.value.forEach { (chat, data) ->
            data.messages.filter { it.sender == Sender.User && it.remoteId == null }.forEach { pending ->
                when (val content = pending.content) {
                    is MessageContent.Text -> socket?.send(GatewayProtocol.message(pending.id, content.text, chat))
                    is MessageContent.Voice -> scope.launch(Dispatchers.IO) { sendVoiceFrame(chat, pending) }
                    is MessageContent.File -> scope.launch(Dispatchers.IO) { sendFileFrame(chat, pending) }
                    else -> Unit
                }
            }
        }
    }

    /** Insère ou remplace par identifiant distant, en gardant la clé locale des messages déjà affichés. */
    private fun upsert(chat: String, incoming: List<ChatMessage>) {
        if (incoming.isEmpty()) return
        updateMessages(chat) { list ->
            val byRemote = list.withIndex().associate { (i, m) -> m.remoteId to i }
            val result = list.toMutableList()
            for (message in incoming) {
                val index = byRemote[message.remoteId]
                if (index == null) {
                    result += message
                } else {
                    val existing = result[index]
                    result[index] = message.copy(
                        id = existing.id,
                        reactions = existing.reactions + message.reactions,
                        content = keepLocalAudio(existing.content, message.content),
                    )
                }
            }
            result
        }
    }

    /** Page d'historique (première d'une conversation ou plus ancienne) : insérée en tête, sans doublon. */
    private fun onHistoryPage(frame: JSONObject) {
        val chat = GatewayProtocol.chatOf(frame)
        val events = frame.optJSONArray("events")
        val page = if (events == null) emptyList() else List(events.length()) { events.getJSONObject(it) }
        val older = page.mapNotNull(GatewayProtocol::toMessage)
        val olderTools = page.filter { it.optString("type") == "tool" }.mapNotNull(::tool)
        updateChat(chat) { data ->
            val knownMessages = data.messages.mapNotNullTo(HashSet()) { it.remoteId }
            val knownTools = data.tools.mapTo(HashSet()) { it.id }
            val pageOldest = page.minOfOrNull { it.optLong("seq") }
            data.copy(
                messages = older.filter { it.remoteId !in knownMessages } + data.messages,
                tools = olderTools.filter { it.id !in knownTools } + data.tools,
                oldestSeq = when {
                    pageOldest == null -> data.oldestSeq
                    data.oldestSeq == 0L -> pageOldest
                    else -> minOf(data.oldestSeq, pageOldest)
                },
                hasOlder = frame.optBoolean("has_more") && page.isNotEmpty(),
                loaded = true,
                loadingOlder = false,
            )
        }
    }

    /** Le gateway ne connaît pas nos chemins locaux : on garde le fichier déjà présent sur l'appareil. */
    private fun keepLocalAudio(existing: MessageContent, incoming: MessageContent): MessageContent {
        if (existing is MessageContent.File && incoming is MessageContent.File) {
            return incoming.copy(localPath = incoming.localPath ?: existing.localPath)
        }
        if (existing !is MessageContent.Voice || incoming !is MessageContent.Voice) return incoming
        return incoming.copy(localPath = incoming.localPath ?: existing.localPath, transcript = incoming.transcript ?: existing.transcript)
    }

    private fun upsertTools(chat: String, incoming: List<ToolCall>) {
        if (incoming.isEmpty()) return
        updateChat(chat) { data ->
            val byId = incoming.associateBy { it.id }
            data.copy(tools = data.tools.map { byId[it.id] ?: it } + incoming.filter { new -> data.tools.none { it.id == new.id } })
        }
    }

    private fun bumpSeq(frame: JSONObject) {
        lastSeq = maxOf(lastSeq, frame.optLong("seq"))
    }

    private fun updateChat(chat: String, transform: (ChatData) -> ChatData) =
        chats.update { all -> all + (chat to transform(all[chat] ?: ChatData())) }

    private fun updateMessages(chat: String, transform: (List<ChatMessage>) -> List<ChatMessage>) =
        updateChat(chat) { it.copy(messages = transform(it.messages)) }

    private fun findMessage(id: String): ChatMessage? =
        chats.value.values.firstNotNullOfOrNull { data -> data.messages.firstOrNull { it.id == id } }

    /** Les identifiants sont uniques sur toutes les conversations : une mise à jour par id les parcourt toutes. */
    private fun updateWhere(predicate: (ChatMessage) -> Boolean, transform: (ChatMessage) -> ChatMessage) =
        chats.update { all ->
            all.mapValues { (_, data) ->
                if (data.messages.none(predicate)) data else data.copy(messages = data.messages.map { if (predicate(it)) transform(it) else it })
            }
        }
}
