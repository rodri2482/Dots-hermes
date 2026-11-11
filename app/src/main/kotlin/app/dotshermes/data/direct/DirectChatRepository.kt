package app.dotshermes.data.direct

import app.dotshermes.data.ChatRepository
import app.dotshermes.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import java.io.File
import java.time.Instant
import java.util.UUID

data class DirectRequestIssue(val failure: ApiFailure, val canRetry: Boolean, val waitSeconds: Long = 0, val retryAtMs: Long = 0, val cooldownOnly: Boolean = false)

/** Un compte, un fil et une requête à la fois. Aucun renvoi payant automatique après erreur ou reprise. */
class DirectChatRepository(
    private val accountId: String,
    private val provider: ApiProvider,
    private val model: String,
    private val scope: CoroutineScope,
    private val storage: DirectAccountStorage,
    private val client: ProviderClient,
    private val clockMs: () -> Long = System::currentTimeMillis,
    inheritedCooldownUntilMs: Long = 0,
    /** Personnalité choisie dans les réglages (texte « system »), relue à chaque message. */
    private val persona: () -> String = { "" },
    /** Outils proposés au modèle (Gmail en lecture seule) ; null = aucun. Groq seulement. */
    private val toolbox: () -> DirectTools? = { null },
    /** Dossier privé des photos envoyées (null : pas de photos, tests). */
    private val imageDir: File? = null,
    /** Réduit et recompresse une photo avant l'envoi (Android : BitmapFactory). */
    private val encodeImage: (File) -> ByteArray? = { null },
) : ChatRepository {
    private val lock = Any()
    private var transcript = DirectTranscript()
    private var generation = 0L
    private var job: Job? = null
    private var busy = false
    private var ready = false
    private var memoryCooldownUntilMs = inheritedCooldownUntilMs
    private var loadedQuotaScope: String? = null
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    override val messages = _messages.asStateFlow()
    private val _activity = MutableStateFlow<AgentActivity>(AgentActivity.Idle)
    override val activity = _activity.asStateFlow()
    // « Connecting » jusqu'à la lecture du coffre : l'app attend sur un fond neutre au lieu de
    // montrer l'écran de connexion une fraction de seconde à chaque lancement.
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Connecting(""))
    override val connection = _connection.asStateFlow()
    private val _failure = MutableStateFlow<DirectRequestIssue?>(null)
    val failure = _failure.asStateFlow()
    private val _directUsage = MutableStateFlow(DirectApiUsage(provider, model, null, 0))
    override val directUsage = _directUsage.asStateFlow()
    override val requestIssue get() = failure
    override val gatewayFeatures = MutableStateFlow(false).asStateFlow()
    /** Micro : Groq transcrit les vocaux (Whisper) ; les autres fournisseurs restent en texte. */
    override val voiceInput = MutableStateFlow(provider == ApiProvider.Groq).asStateFlow()
    /** Photos : les modèles de vision de Groq les lisent. */
    override val imageInput = MutableStateFlow(provider == ApiProvider.Groq && imageDir != null).asStateFlow()
    @Volatile private var visionModelId: String? = null
    override fun retryRequest() = retry()
    override val profile = MutableStateFlow(AgentProfile(name = provider.title, tagline = model)).asStateFlow()
    override val tools = MutableStateFlow<List<ToolCall>>(emptyList()).asStateFlow()
    override val screen = MutableStateFlow(ScreenState()).asStateFlow()
    override val activitySnapshot = MutableStateFlow<ActivitySnapshot?>(null).asStateFlow()
    override val hasOlder = MutableStateFlow(false).asStateFlow()
    override val dots = MutableStateFlow<List<SubDot>>(emptyList()).asStateFlow()
    override val activeChat = MutableStateFlow(MAIN_CHAT).asStateFlow()
    override val chatActivity = activity.map { mapOf(MAIN_CHAT to it) }.stateIn(scope, SharingStarted.Eagerly, mapOf(MAIN_CHAT to AgentActivity.Idle))
    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val unread = _unread.asStateFlow()
    @Volatile private var shown = false
    // Mémoire à long terme (Groq) : relue au premier besoin, puis gardée ici.
    private val _facts = MutableStateFlow<List<String>>(emptyList())
    val facts = _facts.asStateFlow()
    @Volatile private var factsLoaded = false
    private fun loadedFacts(): List<String> {
        if (!factsLoaded) { _facts.value = storage.facts(accountId); factsLoaded = true }
        return _facts.value
    }
    private fun storeFacts(next: List<String>) { storage.saveFacts(accountId, next); _facts.value = next }

    /** Oublie un fait (réglages). */
    fun forgetFact(fact: String) = scope.launch(Dispatchers.IO) { synchronized(lock) { storeFacts(loadedFacts() - fact) } }

    /** Outils « remember » / « forget » : le modèle note ce qui mérite d'être retenu d'une conversation à l'autre. */
    private val memoryTools = object : DirectTools {
        override fun definitions(): org.json.JSONArray = org.json.JSONArray()
            .put(memoryFn("remember", "Save a short, lasting fact about the user so you know it in future chats " +
                "(name, preferences, people, projects, routines). Only when the user shares something worth keeping or asks you to remember. " +
                "One fact per call, written in third person, e.g. 'Prefers to be called Rodri'.", "fact"))
            .put(memoryFn("forget", "Delete a saved fact about the user, given its exact text from the memory list.", "fact"))
        override fun label(call: ApiToolCall) = ToolKeys.of("memory")
        override suspend fun run(call: ApiToolCall): String {
            val fact = (try { org.json.JSONObject(call.arguments.ifBlank { "{}" }).optString("fact") } catch (_: org.json.JSONException) { "" })
                .trim().replace(Regex("\\s+"), " ").take(MAX_FACT_CHARS)
            if (fact.isEmpty()) return """{"error":"fact is required"}"""
            return withContext(Dispatchers.IO) { synchronized(lock) {
                val now = loadedFacts()
                when (call.name) {
                    "remember" -> {
                        if (now.none { it.equals(fact, ignoreCase = true) }) storeFacts((now + fact).takeLast(MAX_FACTS))
                        """{"status":"saved"}"""
                    }
                    "forget" -> {
                        val gone = now.filterNot { it.equals(fact, ignoreCase = true) }
                        if (gone.size == now.size) """{"error":"no such fact"}""" else { storeFacts(gone); """{"status":"forgotten"}""" }
                    }
                    else -> """{"error":"unknown tool"}"""
                }
            } }
        }
    }
    override fun setShown(shown: Boolean) { this.shown = shown; if (shown) _unread.value = emptyMap() }

    suspend fun restore() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            generation++; job?.cancel(); busy = false; ready = false
            try {
                val key = storage.key(accountId)?.takeIf { it.isNotBlank() } ?: throw ApiException(ApiFailure.MissingKey)
                val scopeId = quotaScope(provider, model, key)
                val main = storage.load(accountId)
                index = main.conversations
                current = main.activeConversation?.takeIf { id -> index.any { it.id == id } } ?: accountId
                val saved = if (current == accountId) main else storage.load(current)
                if (loadedQuotaScope != null && loadedQuotaScope != scopeId) memoryCooldownUntilMs = 0
                loadedQuotaScope = scopeId
                val scoped = if (saved.quotaScope == scopeId) saved else saved.copy(
                    limits = null, cooldownUntilMs = 0, quotaScope = scopeId,
                    failure = saved.failure?.takeUnless { it in setOf(ApiFailure.RateLimited, ApiFailure.Quota) })
                memoryCooldownUntilMs = maxOf(memoryCooldownUntilMs, scoped.cooldownUntilMs)
                val restored = scoped.copy(cooldownUntilMs = memoryCooldownUntilMs)
                val interrupted = restored.messages.any { it.state == DirectEntryState.Pending || it.state == DirectEntryState.Streaming }
                val normalized = if (interrupted) restored.copy(
                    messages = restored.messages.map { if (it.state in setOf(DirectEntryState.Pending, DirectEntryState.Streaming)) it.copy(state = DirectEntryState.Failed) else it },
                    failure = ApiFailure.Interrupted,
                ) else restored
                commit(normalized)
                _failure.value = normalized.failure?.let { issue(it) }
                    ?: ApiFailure.RateLimited.takeIf { normalized.cooldownUntilMs > clockMs() }?.let { issue(it, cooldownOnly = true) }
                _connection.value = ConnectionState.Connected(provider.title, 0)
                publishConversations()
                ready = true
            } catch (e: Exception) {
                _failure.value = issue((e as? ApiException)?.failure ?: ApiFailure.Storage)
                _connection.value = ConnectionState.Disconnected
            }
            _activity.value = AgentActivity.Idle
        }
    }

    /** Une reprise avec la même clé/modèle ne perd pas un délai dont l'écriture a échoué. */
    fun cooldownFor(provider: ApiProvider, model: String, key: String): Long = synchronized(lock) {
        if (loadedQuotaScope == quotaScope(provider, model, key)) maxOf(memoryCooldownUntilMs, transcript.cooldownUntilMs) else 0
    }

    // Le paramètre historique appartient au contrat commun ; aucune adresse/token Hermes n'est utilisé ici.
    override suspend fun connect(config: GatewayConfig) = restore()

    override fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        begin(trimmed, null)
    }

    /**
     * Photo choisie sur le téléphone : réduite, gardée dans le dossier privé du compte, puis envoyée
     * avec la légende au modèle de vision du compte. Les autres fichiers ne sont pas pris en charge.
     */
    override fun sendFile(file: File, name: String, mime: String, caption: String) {
        val dir = imageDir
        if (dir == null || provider != ApiProvider.Groq || !mime.startsWith("image/")) { file.delete(); return }
        scope.launch(Dispatchers.IO) {
            val bytes = try { encodeImage(file) } finally { file.delete() }
            if (bytes == null) { synchronized(lock) { _failure.value = DirectRequestIssue(ApiFailure.InvalidRequest, canRetry = false) }; return@launch }
            val stored = UUID.randomUUID().toString().replace("-", "").take(20) + ".jpg"
            try { dir.mkdirs(); File(dir, stored).writeBytes(bytes) }
            catch (_: Exception) { synchronized(lock) { _failure.value = issue(ApiFailure.Storage) }; return@launch }
            begin(caption.trim(), null, stored)
        }
    }

    override suspend fun attachmentFile(messageId: String): File? = synchronized(lock) {
        transcript.messages.firstOrNull { it.id == messageId }?.image?.let { name -> imageDir?.let { File(it, name) } }?.takeIf { it.isFile }
    }

    /** Le modèle de vision du compte, demandé une fois à Groq puis gardé. */
    @Volatile private var agentModelId: String? = null
    @Volatile private var agentModelChecked = false

    /** Le modèle Groq au quota par minute le plus large pour les tâches à plusieurs tours ; null si aucun. */
    private suspend fun agentModel(key: String): String? {
        if (!agentModelChecked) {
            agentModelId = runCatching { client.models(provider, key) }.getOrNull()?.let { ids -> AgentModels.firstOrNull { it in ids } }
            agentModelChecked = true
        }
        return agentModelId
    }

    private suspend fun visionModel(key: String): String =
        visionModelId ?: (visionModel(client.models(provider, key)) ?: throw ApiException(ApiFailure.ModelUnavailable)).also { visionModelId = it }

    fun retry() {
        synchronized(lock) {
            val id = transcript.retryUserId ?: return
            if (_failure.value?.canRetry != true) return
            begin(null, id)
        }
    }

    private fun begin(text: String?, retryId: String?, image: String? = null) {
        synchronized(lock) {
            if (!ready || busy) return
            if (maxOf(transcript.cooldownUntilMs, memoryCooldownUntilMs) > clockMs()) {
                _failure.value = issue(transcript.failure ?: ApiFailure.RateLimited, cooldownOnly = transcript.failure == null)
                return
            }
            memoryCooldownUntilMs = 0
            busy = true
            val token = ++generation
            _failure.value = null
            _activity.value = AgentActivity.Thinking
            job = scope.launch(Dispatchers.IO) { runTurn(token, text, retryId, image) }
        }
    }

    private suspend fun runTurn(token: Long, text: String?, retryId: String?, image: String? = null) {
        var userId: String? = retryId
        var answerId: String? = null
        try {
            val turns = synchronized(lock) {
                if (token != generation) return
                val previous = transcript.messages
                val user = if (retryId != null) previous.first { it.id == retryId && it.role == "user" }
                    else DirectEntry(UUID.randomUUID().toString(), "user", requireNotNull(text), System.currentTimeMillis(), DirectEntryState.Pending, image = image)
                userId = user.id
                val retained = if (retryId == null) previous else previous.filterNot { it.id == retryId || it.replyTo == retryId }
                val answer = DirectEntry(UUID.randomUUID().toString(), "assistant", "", System.currentTimeMillis(), DirectEntryState.Streaming, user.id)
                answerId = answer.id
                commit(transcript.copy(messages = retained + user.copy(state = DirectEntryState.Pending) + answer, retryUserId = user.id, failure = null, cooldownUntilMs = 0))
                val known = if (provider == ApiProvider.Groq) loadedFacts() else emptyList()
                // Avec des outils, le modèle doit s'en servir au lieu de répondre « je ne peux pas ».
                val toolLine = if (provider == ApiProvider.Groq && toolbox()?.definitions() != null) "\n\n" + TOOL_RULE else ""
                val memoryLine = if (known.isEmpty()) "" else "\n\nWhat you remember about the user (from earlier chats):\n" + known.joinToString("\n") { "- $it" }
                listOfNotNull(persona().trim().takeIf { it.isNotEmpty() }?.let { ApiTurn("system", it + "\n\n" + REACTION_RULE + toolLine + memoryLine + "\n\n" + nowLine()) }) +
                    completedContext(retained) + ApiTurn("user", user.text.ifBlank { if (user.image != null) "What do you see in this photo?" else user.text },
                        user.image?.let { name -> imageDir?.let { File(it, name) } }?.takeIf { it.isFile }?.let { photo ->
                            "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(photo.readBytes())
                        })
            }
            val key = storage.key(accountId) ?: throw ApiException(ApiFailure.MissingKey)
            // Une photo dans ce tour : le modèle de vision du compte répond à la place du modèle choisi.
            var turnModel = if (turns.lastOrNull()?.image != null) visionModel(key) else model
            var replyTokens: Long? = null
            val tag = ReactionTag()
            val extra = ArrayList<org.json.JSONObject>()
            // Jusqu'à MAX_TOOL_ROUNDS échanges d'outils ; le dernier tour n'en propose plus, pour obtenir une réponse.
            for (round in 0 until MAX_TOOL_ROUNDS) {
            val toolset = if (provider == ApiProvider.Groq) CombinedTools(listOfNotNull(memoryTools, toolbox())) else null
            val offered = toolset?.definitions()?.takeIf { round < MAX_TOOL_ROUNDS - 1 }
            // Tâche à plusieurs tours (écran, WhatsApp…) : un modèle au petit quota par minute (gpt-oss : 8 000
            // jetons) s'épuise dès le 2e tour. La suite passe alors par un modèle au quota large (Llama 4 Scout).
            if (round >= 1 && provider == ApiProvider.Groq && (transcript.limits?.tokenLimit ?: 0L) in 1L until AGENT_TPM) {
                agentModel(key)?.let { turnModel = it }
            }
            var calls: List<ApiToolCall> = emptyList()
            var attempt = 0
            while (true) {
            try {
            calls = client.streamTools(provider, key, turnModel, turns, extra, offered, { limits ->
                synchronized(lock) {
                    if (token == generation) {
                        memoryCooldownUntilMs = maxOf(memoryCooldownUntilMs, limits.exhaustedUntilMs())
                        commit(transcript.copy(limits = limits, cooldownUntilMs = memoryCooldownUntilMs))
                    }
                }
            }, { tokens -> replyTokens = (replyTokens ?: 0) + tokens }) { delta ->
                val visible = tag.feed(delta)
                synchronized(lock) {
                    if (token == generation) {
                        val reaction = tag.reaction
                        commit(transcript.copy(messages = transcript.messages.map {
                            when (it.id) {
                                answerId -> it.copy(text = it.text + visible)
                                userId -> it.copy(state = DirectEntryState.Complete, reaction = reaction ?: it.reaction)
                                else -> it
                            }
                        }), persist = clockMs() - lastSaveMs > STREAM_SAVE_MS)
                        _activity.value = if (visible.isEmpty() && reaction != null && transcript.messages.first { it.id == answerId }.text.isEmpty())
                            AgentActivity.Reacting else AgentActivity.Typing
                    }
                }
            }
            break
            } catch (e: ApiException) {
                // Quota par minute dépassé : courte attente puis on reprend ; sinon, le modèle au quota large.
                if (e.failure != ApiFailure.RateLimited || attempt >= 3 || provider != ApiProvider.Groq) throw e
                attempt++
                val agent = agentModel(key)?.takeIf { it != turnModel }
                if (agent != null && (round == 0 || e.retryAfterSeconds > SHORT_WAIT_S || attempt >= 2)) { turnModel = agent; continue }
                // Longue attente (quota du jour…) et pas d'autre modèle : on s'arrête, le délai s'affiche.
                // Une courte attente ne se fait qu'en pleine tâche ; sur un premier envoi, le délai s'affiche avec « Réessayer ».
                if (round == 0 || e.retryAfterSeconds !in 1..SHORT_WAIT_S) throw e
                val wait = e.retryAfterSeconds.coerceAtLeast(2)
                synchronized(lock) { if (token == generation) _activity.value = AgentActivity.Working(ToolKeys.of("wait", wait)) }
                kotlinx.coroutines.delay(wait * 1_000 + 300)
            }
            }
            if (calls.isEmpty() || toolset == null) break
            synchronized(lock) { if (token == generation) _activity.value = AgentActivity.Working(toolset.label(calls.first())) }
            val results = calls.map { keepScreenshot(toolset.run(it), answerId) }
            // Les anciens résultats (écrans déjà lus…) sont résumés : seul le dernier tour reste entier.
            for (i in extra.indices) extra[i] = shrinkOldToolResult(extra[i])
            extra += ProviderProtocol.toolExchange(calls, results)
            }
            synchronized(lock) {
                if (token == generation) {
                    val doneAt = System.currentTimeMillis()
                    val tail = tag.flush()
                    commit(transcript.copy(messages = transcript.messages.map {
                        when (it.id) {
                            answerId -> it.copy(text = it.text + tail, state = DirectEntryState.Complete, durationMs = (doneAt - it.timestampMs).coerceAtLeast(0), tokens = replyTokens)
                            userId -> it.copy(state = DirectEntryState.Complete)
                            else -> it
                        }
                    }, retryUserId = null, failure = null))
                    try { touchCurrent() } catch (_: Exception) { }
                    if (transcript.cooldownUntilMs > clockMs()) _failure.value = issue(ApiFailure.RateLimited, cooldownOnly = true)
                    if (!shown) _unread.value = mapOf(MAIN_CHAT to ((_unread.value[MAIN_CHAT] ?: 0) + 1))
                }
            }
        } catch (_: CancellationException) {
            // stopTurn/disconnect ont déjà figé le fil et invalidé les callbacks de cette génération.
        } catch (e: Exception) {
            synchronized(lock) {
                if (token == generation) {
                    val problem = (e as? ApiException) ?: ApiException(ApiFailure.InvalidResponse)
                    val waitMs = problem.retryAfterSeconds.coerceIn(0, MAX_COOLDOWN_MS / 1000) * 1000
                    val until = maxOf(memoryCooldownUntilMs, transcript.cooldownUntilMs, if (waitMs > 0) clockMs() + waitMs else 0)
                        .let { if (problem.failure == ApiFailure.RateLimited && it <= clockMs()) clockMs() + 60_000 else it }
                    memoryCooldownUntilMs = until
                    val failed = transcript.copy(messages = transcript.messages.map {
                        if (it.id == answerId || it.id == userId) it.copy(state = DirectEntryState.Failed) else it
                    }, retryUserId = userId?.takeIf { id -> transcript.messages.any { it.id == id && it.role == "user" } }, failure = problem.failure, cooldownUntilMs = until)
                    try { commit(failed) } catch (_: Exception) {
                        ready = false
                        _connection.value = ConnectionState.Disconnected
                        _directUsage.value = _directUsage.value.copy(cooldownUntilMs = until)
                        _failure.value = issue(ApiFailure.Storage)
                    }
                    if (_failure.value == null) _failure.value = issue(problem.failure)
                }
            }
        } finally {
            if (provider == ApiProvider.Groq) runCatching { toolbox()?.finished() }
            synchronized(lock) { if (token == generation) { busy = false; _activity.value = AgentActivity.Idle; job = null } }
        }
    }

    override fun stopTurn() {
        synchronized(lock) {
            if (!busy) return
            generation++; job?.cancel(); job = null; busy = false
            val stopped = transcript.copy(messages = transcript.messages.map {
                when (it.state) {
                    DirectEntryState.Streaming -> it.copy(state = DirectEntryState.Stopped)
                    DirectEntryState.Pending -> it.copy(state = DirectEntryState.Failed)
                    else -> it
                }
            }, failure = ApiFailure.Cancelled)
            try { commit(stopped); _failure.value = issue(ApiFailure.Cancelled) }
            catch (_: Exception) { _failure.value = issue(ApiFailure.Storage) }
            _activity.value = AgentActivity.Idle
        }
    }

    /**
     * Efface la conversation : messages, reprise et erreur. Les limites et le délai d'attente du
     * fournisseur restent (ils ne dépendent pas du fil), comme la clé.
     */
    // -- Consommation : la même grille mensuelle que pour un serveur Hermes, calculée sur le téléphone. --
    private val _usage = MutableStateFlow<UsageWindow?>(null)
    override val usage = _usage.asStateFlow()

    /** Consommation du compte entier : toutes ses discussions, pas seulement celle qui est ouverte. */
    override fun requestUsage(from: Instant, to: Instant) {
        scope.launch(Dispatchers.IO) {
            val all = synchronized(lock) {
                val others = (index.map { it.id } + accountId).distinct().filter { it != current }
                transcript.messages + others.flatMap { id -> runCatching { storage.load(id).messages }.getOrDefault(emptyList()) }
            }
            _usage.value = usageWindow(all, from, to)
        }
    }

    override fun clearHistory() {
        stopTurn()
        synchronized(lock) {
            if (!ready) return
            try {
                commit(transcript.copy(messages = emptyList(), retryUserId = null, failure = null))
                _failure.value = ApiFailure.RateLimited.takeIf { transcript.cooldownUntilMs > clockMs() }?.let { issue(it, cooldownOnly = true) }
            } catch (_: Exception) { _failure.value = issue(ApiFailure.Storage) }
            _unread.value = emptyMap()
        }
    }

    override fun disconnect() {
        stopTurn()
        synchronized(lock) { generation++; ready = false; _connection.value = ConnectionState.Disconnected }
    }

    private var lastSaveMs = 0L

    // -- Conversations -------------------------------------------------------------------------------
    /** Dossier chiffré de la conversation ouverte ; celui du compte pour la conversation principale. */
    private var current = accountId
    private var index: List<ConversationMeta> = emptyList()
    private val _conversations = MutableStateFlow<List<ConversationInfo>>(emptyList())
    override val conversations = _conversations.asStateFlow()

    private fun publishConversations() {
        val rows = index.ifEmpty { listOf(ConversationMeta(accountId, "", 0)) }
        _conversations.value = rows.sortedByDescending { it.updatedMs }.map { ConversationInfo(it.id, it.title, it.updatedMs, it.id == current) }
    }

    /** L'index vit dans l'enregistrement principal ; on le réécrit sans toucher aux messages qui s'y trouvent. */
    private fun saveIndex() {
        if (current == accountId) commit(transcript)
        else storage.save(accountId, storage.load(accountId).copy(conversations = index, activeConversation = current))
        publishConversations()
    }

    /** Titre et date de la conversation ouverte, d'après son premier message. */
    private fun touchCurrent() {
        val title = transcript.messages.firstOrNull { it.role == "user" }?.text?.replace(Regex("\\s+"), " ")?.trim()?.take(60).orEmpty()
        val meta = ConversationMeta(current, title, clockMs())
        index = if (index.none { it.id == current }) (if (index.isEmpty() && current != accountId) listOf(ConversationMeta(accountId, mainTitle(), 0)) else index) + meta
            else index.map { if (it.id == current) meta else it }
        saveIndex()
    }

    private fun mainTitle(): String = try {
        storage.load(accountId).messages.firstOrNull { it.role == "user" }?.text?.take(60).orEmpty()
    } catch (_: Exception) { "" }

    /**
     * Routine programmée : envoie [text] comme un message et attend la réponse (3 min au plus).
     * null si le compte n'est pas prêt, déjà occupé, en attente de quota, ou sans réponse complète.
     */
    suspend fun runRoutine(text: String): String? {
        if (!ready) restore()
        val before = synchronized(lock) { if (!ready || busy) return null; transcript.messages.size }
        begin(text, null)
        if (activity.value == AgentActivity.Idle) return null
        kotlinx.coroutines.withTimeoutOrNull(180_000) { activity.first { it == AgentActivity.Idle } } ?: return null
        return synchronized(lock) {
            transcript.messages.drop(before).lastOrNull { it.role == "assistant" && it.state == DirectEntryState.Complete }?.text
        }
    }

    /**
     * Une capture d'écran rendue par un outil : enregistrée avec les photos du compte et posée sur la
     * réponse en cours (l'utilisateur la voit dans le fil) ; le modèle n'en reçoit qu'une mention.
     */
    private fun keepScreenshot(result: String, answerId: String?): String {
        if (!result.contains(ScreenTools.SCREENSHOT_KEY)) return result
        val json = try { org.json.JSONObject(result) } catch (_: org.json.JSONException) { return result }
        val data = json.optString(ScreenTools.SCREENSHOT_KEY)
        json.remove(ScreenTools.SCREENSHOT_KEY)
        val dir = imageDir ?: return json.put("shown", false).toString()
        return try {
            val name = "shot-" + UUID.randomUUID().toString().take(12) + ".jpg"
            dir.mkdirs()
            File(dir, name).writeBytes(java.util.Base64.getDecoder().decode(data))
            synchronized(lock) {
                if (answerId != null) commit(transcript.copy(messages = transcript.messages.map { if (it.id == answerId) it.copy(image = name) else it }))
            }
            json.put("shown", "The screenshot is now shown to the user in the chat.").toString()
        } catch (_: Exception) { json.put("shown", false).toString() }
    }

    override fun newConversation() {
        stopTurn()
        synchronized(lock) {
            if (!ready || transcript.messages.isEmpty()) return
            try {
                if (index.none { it.id == current }) touchCurrent()
                current = accountId + "--c" + UUID.randomUUID().toString().replace("-", "").take(10)
                index = index + ConversationMeta(current, "", clockMs())
                commit(DirectTranscript(quotaScope = transcript.quotaScope))
                saveIndex()
                _failure.value = null
            } catch (_: Exception) { _failure.value = issue(ApiFailure.Storage) }
        }
    }

    override fun openConversation(id: String) {
        stopTurn()
        synchronized(lock) {
            if (!ready || id == current || (index.none { it.id == id } && id != accountId)) return
            try {
                val loaded = storage.load(id)
                current = id
                commit(loaded.copy(messages = loaded.messages.map {
                    if (it.state == DirectEntryState.Pending || it.state == DirectEntryState.Streaming) it.copy(state = DirectEntryState.Failed) else it
                }), persist = false)
                saveIndex()
                _failure.value = null
            } catch (_: Exception) { _failure.value = issue(ApiFailure.Storage) }
        }
    }

    override fun deleteConversation(id: String) {
        if (id == current) stopTurn()
        synchronized(lock) {
            if (!ready) return
            try {
                if (id == accountId) {
                    // La conversation principale porte l'index : on vide ses messages, le dossier reste.
                    val main = if (current == accountId) transcript else storage.load(accountId)
                    val emptied = main.copy(messages = emptyList(), retryUserId = null, failure = null)
                    if (current == accountId) commit(emptied) else storage.save(accountId, emptied.copy(conversations = index, activeConversation = current))
                    index = index.map { if (it.id == accountId) it.copy(title = "") else it }
                } else {
                    storage.remove(id)
                    index = index.filterNot { it.id == id }
                }
                if (id == current && id != accountId) {
                    val next = index.maxByOrNull { it.updatedMs }?.id ?: accountId
                    current = next
                    commit(storage.load(next), persist = false)
                }
                saveIndex()
            } catch (_: Exception) { _failure.value = issue(ApiFailure.Storage) }
        }
    }

    private fun commit(value: DirectTranscript, persist: Boolean = true) {
        if (persist) {
            storage.save(current, if (current == accountId) value.copy(conversations = index, activeConversation = null) else value)
            lastSaveMs = clockMs()
        }
        transcript = value
        memoryCooldownUntilMs = maxOf(memoryCooldownUntilMs, value.cooldownUntilMs)
        _directUsage.value = DirectApiUsage(provider, model, value.limits, value.cooldownUntilMs)
        val newestDone = value.messages.lastOrNull { it.role == "assistant" && it.state == DirectEntryState.Complete && it.durationMs != null }?.id
        // Réponse pas encore commencée (outil en cours) : pas de bulle vide, l'indicateur « écrit… » suffit.
        _messages.value = value.messages.filterNot { it.role == "assistant" && it.state == DirectEntryState.Streaming && it.text.isEmpty() }
            .map { entry -> ChatMessage(
            id = entry.id, sender = if (entry.role == "user") Sender.User else Sender.Agent,
            content = entry.image?.let { name ->
                MessageContent.File(name = "photo.jpg", mime = "image/jpeg", size = imageDir?.let { File(it, name).length() } ?: 0,
                    localPath = imageDir?.let { File(it, name).path }, caption = entry.text)
            } ?: MessageContent.Text(entry.text), sentAt = Instant.ofEpochMilli(entry.timestampMs),
            delivery = if (entry.role == "user") Delivery.Sent else Delivery.Read,
            remoteId = entry.id, turn = entry.replyTo, failed = entry.role == "user" && entry.state == DirectEntryState.Failed,
            footer = if (entry.id == newestDone) replyFooter(model, entry.durationMs ?: 0, entry.tokens) else null,
            streaming = entry.role == "assistant" && entry.state == DirectEntryState.Streaming,
            reactions = entry.reaction?.let { mapOf((if (entry.role == "user") Sender.Agent else Sender.User) to it) } ?: emptyMap(),
        ) }
    }

    private fun issue(failure: ApiFailure, cooldownOnly: Boolean = false) = DirectRequestIssue(failure,
        !cooldownOnly && transcript.retryUserId != null && failure in setOf(ApiFailure.Network, ApiFailure.Interrupted,
            ApiFailure.Unavailable, ApiFailure.RateLimited, ApiFailure.Quota, ApiFailure.Cancelled),
        cooldownSeconds(maxOf(transcript.cooldownUntilMs, memoryCooldownUntilMs), clockMs()),
        maxOf(transcript.cooldownUntilMs, memoryCooldownUntilMs), cooldownOnly)

    /** Réaction de l'utilisateur sur une réponse (double tap, appui long) : gardée sur le téléphone. */
    override fun toggleReaction(messageId: String, emoji: String) {
        synchronized(lock) {
            if (!ready || emoji !in Reactions) return
            val target = transcript.messages.firstOrNull { it.id == messageId && it.role == "assistant" } ?: return
            try {
                commit(transcript.copy(messages = transcript.messages.map {
                    if (it.id == target.id) it.copy(reaction = if (it.reaction == emoji) null else emoji) else it
                }))
            } catch (_: Exception) { _failure.value = issue(ApiFailure.Storage) }
        }
    }
    override fun answerApproval(messageId: String, approved: Boolean) {}
    override fun answerClarify(messageId: String, response: String) {}
    /**
     * Vocal : transcrit par Groq, puis envoyé comme un message écrit (la bulle montre le texte). Le
     * fichier est effacé dans tous les cas ; un échec s'affiche sans bouton Réessayer (rien n'est parti).
     */
    override fun sendVoice(file: File, durationMs: Long, waveform: List<Float>) {
        synchronized(lock) {
            if (!ready || busy || provider != ApiProvider.Groq) { file.delete(); return }
            if (maxOf(transcript.cooldownUntilMs, memoryCooldownUntilMs) > clockMs()) {
                file.delete()
                _failure.value = issue(ApiFailure.RateLimited, cooldownOnly = true)
                return
            }
            busy = true
            _failure.value = null
            _activity.value = AgentActivity.Working(VOICE_LABEL)
        }
        scope.launch(Dispatchers.IO) {
            val text = try {
                val key = storage.key(accountId) ?: throw ApiException(ApiFailure.MissingKey)
                client.transcribe(provider, key, file)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val failure = (e as? ApiException)?.failure ?: ApiFailure.Network
                synchronized(lock) { _failure.value = DirectRequestIssue(failure, canRetry = false) }
                null
            } finally {
                file.delete()
                synchronized(lock) { busy = false; _activity.value = AgentActivity.Idle }
            }
            if (!text.isNullOrBlank()) send(text)
        }
    }
}

/**
 * Consommation heure par heure d'un fil direct, au format du gateway : jetons des réponses terminées,
 * messages envoyés et reçus. Effacer la conversation efface donc aussi cet historique.
 */
internal fun usageWindow(entries: List<DirectEntry>, from: Instant, to: Instant): UsageWindow {
    val start = from.toEpochMilli()
    val end = to.toEpochMilli()
    val hours = entries.filter { it.timestampMs in start until end && it.state != DirectEntryState.Failed }
        .groupBy { it.timestampMs / 3_600_000 }
        .map { (hour, rows) -> UsageHour(hour, rows.sumOf { it.tokens ?: 0 }, rows.count { it.role == "user" || it.state == DirectEntryState.Complete }) }
        .sortedBy { it.hour }
    return UsageWindow(from, to, hours)
}

/** « gpt-oss-120b · 412 tokens · 1.4 s » : le modèle sans son préfixe d'éditeur, les jetons et la durée de la réponse. */
internal fun replyFooter(model: String, durationMs: Long, tokens: Long? = null): String {
    val name = model.substringAfterLast('/')
    val seconds = durationMs / 1000.0
    val time = if (seconds < 10) String.format(java.util.Locale.ROOT, "%.1f s", seconds) else "${Math.round(seconds)} s"
    val used = tokens?.takeIf { it > 0 }?.let { if (it >= 10_000) String.format(java.util.Locale.ROOT, "%.1fK tokens", it / 1000.0) else "$it tokens" }
    return listOfNotNull(name, used, time).joinToString(" · ")
}

/** Pendant une réponse, le fil chiffré est réécrit au plus à cet intervalle (et toujours à la fin). */
private const val STREAM_SAVE_MS = 750L

/** Les réactions possibles, celles de la barre de réactions de l'app. */
internal val Reactions = linkedSetOf("❤️", "👍", "😂", "😮", "🎉", "👀", "🔥", "😢", "🙏", "💯", "😍", "🤔", "👏", "🥳", "😎", "🤯",
    "😅", "🙌", "💪", "✅", "😭", "🤣", "💀", "🫶", "⚡", "🎶", "😴", "🤝", "👎", "😡", "🥺", "😇")

/** « Now: Wednesday 2026-11-11 10:40 (Africa/Lagos) » : le modèle ne connaît pas la date du jour. */
internal fun nowLine(now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): String =
    "Now: " + now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH)) + " (" + now.zone.id + ")."

/** Consigne ajoutée à la personnalité : une réaction en tête de chaque réponse, retirée du texte. */
/** Sous ce quota (jetons par minute), une tâche à plusieurs tours passe au modèle « agent ». */
private const val AGENT_TPM = 20_000L
/** Attente acceptée avant de réessayer après un « rate limit » (secondes). */
private const val SHORT_WAIT_S = 20L
/** Modèles Groq aux quotas larges qui savent appeler des outils, du préféré au suivant. */
private val AgentModels = listOf("meta-llama/llama-4-scout-17b-16e-instruct", "llama-3.3-70b-versatile", "meta-llama/llama-4-maverick-17b-128e-instruct")

/** Un résultat d'outil d'un tour précédent, réduit à son début (le modèle en a déjà tiré parti). */
internal fun shrinkOldToolResult(message: org.json.JSONObject): org.json.JSONObject {
    if (message.optString("role") != "tool") return message
    val content = message.optString("content")
    if (content.length <= 600) return message
    return org.json.JSONObject(message.toString()).put("content", content.take(400) + " …(older result shortened)")
}

private const val MAX_FACTS = 60
private const val MAX_FACT_CHARS = 200

private fun memoryFn(name: String, description: String, arg: String) = org.json.JSONObject()
    .put("type", "function").put("function", org.json.JSONObject().put("name", name).put("description", description)
        .put("parameters", org.json.JSONObject().put("type", "object")
            .put("properties", org.json.JSONObject().put(arg, org.json.JSONObject().put("type", "string")))
            .put("required", org.json.JSONArray().put(arg))))

internal const val TOOL_RULE = "You run on the user's Android phone and have tools that act on it: play music, pause or skip, " +
    "alarms, timers, opening apps, calendar events, phone status, texting (SMS, WhatsApp), reading Gmail, searching the web and news, " +
    "routines, remembering facts, reading the screen and, with the user's approval, operating it (tap, type, Enter, swipe, scroll). " +
    "For anything recent or that you are unsure about (news, prices, scores, releases, tips), search the web instead of guessing. " +
    "When a request matches a tool, call it instead of saying you can't; only say you can't when no tool fits. " +
    "Finish the job: if an app opens but the task is not done yet (a song still needs a tap, a message still needs typing), " +
    "When a screen task ends or gets stuck, call take_screenshot so the user sees where it stopped and can continue. " +
    "For questions about something (a person, place, food, how-to, comparison), answer like a polished assistant: a direct first " +
    "sentence, then ## headings or **bold** labels, short bullet points, a table when comparing, and a one-line tip at the end; " +
    "call show_image when a picture helps. Keep small talk short and casual. " +
    "use the screen tools to complete it when they are available, otherwise tell the user the one step left. " +
    "After a tool runs, say briefly what happened."

internal val REACTION_RULE = "Start every reply with a reaction to the user's latest message, written exactly as " +
    "[[react:EMOJI]] where EMOJI is one of ${Reactions.joinToString(" ")}, or [[react:none]] when nothing fits. " +
    "React like a friend would and vary it (😂 or 🤣 for a joke, 🎉 or 🥳 for good news, ❤️ or 🙏 for thanks, 😢 for sad news, " +
    "👀 or 🤔 for a curious question, 🔥 or 💯 for something impressive, 🎶 for music, 💪 for effort). " +
    "Then write your reply. The tag is hidden from the user: never mention it."

/**
 * Retire la balise `[[react:…]]` du début d'une réponse qui arrive par morceaux et en garde l'emoji.
 * Tant que le début peut encore être une balise, rien n'est montré ; sinon, tout passe tel quel.
 */
internal class ReactionTag {
    private val pending = StringBuilder()
    private var done = false
    var reaction: String? = null
        private set

    fun feed(delta: String): String {
        if (done) return delta
        pending.append(delta)
        val text = pending.toString().trimStart()
        if (text.length < PREFIX.length) {
            if (PREFIX.startsWith(text)) return ""
            return release()
        }
        if (!text.startsWith(PREFIX)) return release()
        val end = text.indexOf("]]")
        if (end < 0) return if (text.length > 48) release() else ""
        reaction = text.substring(PREFIX.length, end).trim().takeIf { it in Reactions }
        done = true
        return text.substring(end + 2).trimStart()
    }

    /** Fin du flux : ce qui attendait encore (une réponse qui ressemblait au début d'une balise). */
    fun flush(): String = if (done) "" else release()

    private fun release(): String { done = true; return pending.toString() }

    private companion object { const val PREFIX = "[[react:" }
}

/** Échanges d'outils au plus par message (recherche, lecture, …), pour borner le coût. */
// Assez pour une petite tâche à l'écran (lire, toucher, écrire…) ; le dernier tour force une réponse.
private const val MAX_TOOL_ROUNDS = 12

/** Libellé de l'en-tête pendant la transcription ; neutre, pour les deux langues. */
private val VOICE_LABEL = ToolKeys.of("voice")

/** Seuls les tours complets sont rejoués ; un prompt arrêté/interrompu ne repart pas implicitement. */
internal fun completedContext(entries: List<DirectEntry>): List<ApiTurn> {
    val completeReplies = entries.filter { it.role == "assistant" && it.state == DirectEntryState.Complete }.associateBy { it.replyTo }
    return entries.filter { it.role == "user" && it.state == DirectEntryState.Complete }.flatMap { user ->
        completeReplies[user.id]?.let { listOf(ApiTurn("user", if (user.image != null) "[photo] ${user.text}".trim() else user.text), ApiTurn("assistant", it.text)) } ?: emptyList()
    }
}
