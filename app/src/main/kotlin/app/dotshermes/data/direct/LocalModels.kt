package app.dotshermes.data.direct

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Un modèle qui tourne sur le téléphone, sans clé ni Internet une fois téléchargé. */
data class LocalModel(val id: String, val name: String, val sizeBytes: Long, val url: String, val file: String, val note: String, val maker: String)

/** État du téléchargement d'un modèle. */
sealed interface LocalModelState {
    data object Missing : LocalModelState
    data class Downloading(val fraction: Float) : LocalModelState
    data object Ready : LocalModelState
    data class Failed(val reason: String) : LocalModelState
}

/**
 * Modèles hors ligne (format LiteRT-LM, publics sur Hugging Face, sans compte) : téléchargés par le
 * gestionnaire de téléchargements d'Android (reprise, notification, continue en arrière-plan) dans le
 * dossier privé de l'app sur le stockage du téléphone. Effacés avec l'app ou d'un geste dans les réglages.
 */
class LocalModels(private val context: Context, private val scope: CoroutineScope) {
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("local-models", Context.MODE_PRIVATE)
    private val _states = MutableStateFlow(CATALOG.associate { it.id to stateOf(it) })
    val states: StateFlow<Map<String, LocalModelState>> = _states.asStateFlow()
    private var watcher: Job? = null

    private fun dir() = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }
    fun fileOf(model: LocalModel) = File(dir(), model.file)
    fun fileOf(id: String) = CATALOG.firstOrNull { it.id == id }?.let(::fileOf)

    // Android réserve la taille complète du fichier dès le début : seul un téléchargement terminé compte.
    private fun pending(model: LocalModel) = prefs.getLong(model.id, -1L) >= 0

    /** Modèle complet et utilisable. */
    fun isReady(model: LocalModel) = !pending(model) && fileOf(model).let { it.isFile && it.length() == model.sizeBytes }

    private fun stateOf(model: LocalModel): LocalModelState = when {
        pending(model) -> LocalModelState.Downloading(0f)
        isReady(model) -> LocalModelState.Ready
        else -> LocalModelState.Missing
    }

    /** Assez de place ? (la taille du modèle plus 300 Mo de marge) */
    fun hasRoom(model: LocalModel) = dir().usableSpace > model.sizeBytes + 300_000_000

    fun download(model: LocalModel) {
        if (_states.value[model.id] is LocalModelState.Ready || prefs.getLong(model.id, -1L) >= 0) { watch(); return }
        fileOf(model).delete()
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle("Claydot · ${model.name}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(fileOf(model)))
            .setAllowedOverMetered(true)
        val id = downloads.enqueue(request)
        prefs.edit().putLong(model.id, id).apply()
        update(model.id, LocalModelState.Downloading(0f))
        watch()
    }

    fun cancel(model: LocalModel) {
        prefs.getLong(model.id, -1L).takeIf { it >= 0 }?.let { downloads.remove(it) }
        prefs.edit().remove(model.id).apply()
        fileOf(model).delete()
        update(model.id, LocalModelState.Missing)
    }

    fun delete(model: LocalModel) = cancel(model)

    private fun update(id: String, state: LocalModelState) { _states.value = _states.value + (id to state) }

    /** Suit les téléchargements en cours (une fois par seconde) jusqu'à leur fin. */
    fun watch() {
        if (watcher?.isActive == true) return
        watcher = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pending = CATALOG.filter { prefs.getLong(it.id, -1L) >= 0 }
                if (pending.isEmpty()) break
                for (model in pending) {
                    val id = prefs.getLong(model.id, -1L)
                    downloads.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                        if (!c.moveToFirst()) { prefs.edit().remove(model.id).apply(); update(model.id, stateOf(model)); return@use }
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                prefs.edit().remove(model.id).apply()
                                update(model.id, if (fileOf(model).length() == model.sizeBytes) LocalModelState.Ready
                                    else LocalModelState.Failed("incomplete file"))
                            }
                            DownloadManager.STATUS_FAILED -> {
                                prefs.edit().remove(model.id).apply()
                                fileOf(model).delete()
                                update(model.id, LocalModelState.Failed("download failed (${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})"))
                            }
                            else -> update(model.id, LocalModelState.Downloading((done.toFloat() / model.sizeBytes).coerceIn(0f, 1f)))
                        }
                    }
                }
                delay(1_000)
            }
        }
    }

    companion object {
        /** Clé enregistrée pour un compte hors ligne (il n'y a pas de clé, mais le coffre en attend une). */
        const val LOCAL_KEY = "on-device"

        val CATALOG = listOf(
            LocalModel("qwen3.5-2b", "Qwen 3.5 2B", 2_119_744_416,
                "https://huggingface.co/litert-community/Qwen3.5-2B/resolve/main/Qwen3.5-2B_int8.litertlm",
                "Qwen3.5-2B_int8.litertlm", "Best answers, many languages. Needs about 4 GB of RAM.", "Alibaba Qwen"),
            LocalModel("lfm2.5-1.2b", "LFM 2.5 1.2B", 736_015_744,
                "https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/resolve/main/LFM2.5-1.2B-Instruct_int4.litertlm",
                "LFM2.5-1.2B-Instruct_int4.litertlm", "Light and quick, for phones with less space.", "Liquid AI"),
        )

        fun byId(id: String) = CATALOG.firstOrNull { it.id == id }

        /** Le moteur ne tourne que sur les téléphones ARM 64 bits (la quasi-totalité des téléphones récents). */
        fun deviceSupported() = Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" || it == "x86_64" }
    }
}

/**
 * Le moteur LiteRT-LM : un seul à la fois dans l'app (recommandation des modèles), chargé au premier
 * message puis gardé tant que l'app vit. Chaque réponse part d'une conversation neuve, préremplie avec
 * la personnalité et les derniers échanges.
 */
class LocalProviderClient(private val context: Context, private val models: () -> LocalModels) : ProviderClient {
    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null
    /** Conversation en cours d'une tâche à outils : reprise au tour suivant au lieu d'être relue. */
    private var taskConversation: com.google.ai.edge.litertlm.Conversation? = null
    private var taskKey: Int? = null
    private val idle = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var releaseJob: Job? = null
    private val guard = DeviceGuard(context)

    /** Au repos depuis quelques minutes : le moteur est fermé (mémoire rendue, processeur au calme). */
    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = idle.launch {
            delay(IDLE_RELEASE_MS)
            lock.withLock {
                runCatching { taskConversation?.close() }; taskConversation = null; taskKey = null
                runCatching { engine?.close() }; engine = null; loadedPath = null
            }
        }
    }

    override suspend fun models(provider: ApiProvider, key: String): List<String> =
        LocalModels.CATALOG.filter { models().isReady(it) }.map { it.id }

    private fun engineFor(path: String): Engine {
        engine?.takeIf { loadedPath == path }?.let { return it }
        runCatching { engine?.close() }
        engine = null
        // Quatre fils au plus : les cœurs restants gardent le téléphone fluide et moins chaud.
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { minOf(4, it - 1).coerceAtLeast(2) }
        val fresh = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(threads, null), cacheDir = context.cacheDir.path))
        fresh.initialize()
        engine = fresh; loadedPath = path
        return fresh
    }

    override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) {
        streamTools(provider, key, model, turns, emptyList(), null, {}, {}, onDelta)
    }

    override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        extra: List<org.json.JSONObject>, tools: org.json.JSONArray?,
        onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
        if (!LocalModels.deviceSupported()) throw ApiException(ApiFailure.ModelUnavailable)
        val entry = LocalModels.byId(model)?.takeIf { models().isReady(it) } ?: throw ApiException(ApiFailure.ModelUnavailable)
        // Protections : trop chaud, batterie presque vide, pas assez de mémoire → on s'arrête proprement.
        val mode = guard.check(entry.sizeBytes, loaded = loadedPath == models().fileOf(entry).path)
        // Téléphone tiède : une courte pause entre deux tours d'outils, et des réponses plus courtes.
        if (mode == DeviceGuard.Mode.Gentle && extra.isNotEmpty()) delay(GENTLE_PAUSE_MS)
        releaseJob?.cancel()
        try {
            return lock.withLock { withContext(Dispatchers.IO) { runTurn(entry, turns, extra, tools, mode, onDelta) } }
        } finally { scheduleRelease() }
    }

    private suspend fun runTurn(entry: LocalModel, turns: List<ApiTurn>, extra: List<org.json.JSONObject>, tools: org.json.JSONArray?,
        mode: DeviceGuard.Mode, onDelta: (String) -> Unit): List<ApiToolCall> {
        val engine = try { engineFor(models().fileOf(entry).path) } catch (_: Throwable) { throw ApiException(ApiFailure.ModelUnavailable) }
        val history = turns.filter { it.role != "system" }
        val last = history.lastOrNull { it.role == "user" } ?: return emptyList()
        val key = turns.hashCode()
        // Tour suivant d'une même tâche : on répond aux outils dans la conversation déjà ouverte.
        val conversation: com.google.ai.edge.litertlm.Conversation
        val message: Message
        val reuse = extra.isNotEmpty() && taskKey == key && taskConversation?.isAlive == true
        if (reuse) {
            conversation = taskConversation!!
            val results = extra.takeLastWhile { it.optString("role") == "tool" }
            val names = extra.lastOrNull { it.optString("role") == "assistant" }?.optJSONArray("tool_calls")
            message = Message.tool(Contents.of(results.mapIndexed { i, r ->
                val name = names?.optJSONObject(i)?.optJSONObject("function")?.optString("name").orEmpty()
                Content.ToolResponse(name, parseJson(r.optString("content")))
            }))
        } else {
            runCatching { taskConversation?.close() }; taskConversation = null
            val system = turns.filter { it.role == "system" }.joinToString("\n\n") { it.text }
            val earlier = history.dropLast(1).takeLast(6).map { if (it.role == "user") Message.user(it.text) else Message.model(it.text) }
            val providers = tools?.let { defs -> (0 until defs.length()).mapNotNull { defs.optJSONObject(it)?.optJSONObject("function") }
                .map { fn -> com.google.ai.edge.litertlm.tool(LocalTool(fn)) } }.orEmpty()
            val config = ConversationConfig(
                systemInstruction = Contents.of(system.ifBlank { "You are Claydot, a friendly assistant." } + "\n\n" +
                    (if (providers.isEmpty()) OFFLINE_NOTE else OFFLINE_TOOLS_NOTE)),
                initialMessages = earlier,
                tools = providers,
                samplerConfig = SamplerConfig(40, 0.95, 0.6, 0),
                automaticToolCalling = false,
                maxOutputToken = if (mode == DeviceGuard.Mode.Gentle) 256 else 640,
            )
            conversation = engine.createConversation(config)
            message = Message.user(last.text)
        }
        val thinking = ThinkFilter()
        val calls = ArrayList<ApiToolCall>()
        try {
            conversation.sendMessageAsync(message).collect { chunk ->
                val text = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                thinking.feed(text).takeIf { it.isNotEmpty() }?.let(onDelta)
                chunk.toolCalls.forEach { call ->
                    calls += ApiToolCall("local-" + java.util.UUID.randomUUID().toString().take(8), call.name,
                        org.json.JSONObject(call.arguments.mapValues { (_, v) -> v ?: org.json.JSONObject.NULL }).toString())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { conversation.cancelProcess() }; runCatching { conversation.close() }
            taskConversation = null; taskKey = null
            throw e
        }
        if (calls.isEmpty()) { runCatching { conversation.close() }; taskConversation = null; taskKey = null }
        else { taskConversation = conversation; taskKey = key }
        return calls
    }

    private fun parseJson(text: String): Any = try { org.json.JSONObject(text).toMap() } catch (_: Exception) { mapOf("result" to text) }

    private fun org.json.JSONObject.toMap(): Map<String, Any?> = keys().asSequence().associateWith { k ->
        when (val v = get(k)) { is org.json.JSONObject -> v.toMap(); is org.json.JSONArray -> (0 until v.length()).map { v.get(it).toString() }; org.json.JSONObject.NULL -> null; else -> v }
    }

    companion object {
        private const val IDLE_RELEASE_MS = 3 * 60_000L
        private const val GENTLE_PAUSE_MS = 2_500L
        private const val OFFLINE_TOOLS_NOTE = "You run on the user's phone, offline. You can act on the phone with your tools " +
            "(apps, music, alarms, texting, the screen with the user's approval). Keep tool use to the few steps needed, then answer briefly."
        private const val OFFLINE_NOTE = "You run fully offline on the user's phone: you cannot browse the web, see the screen, send messages " +
            "or use apps. If asked, say so kindly and suggest a Groq account in Claydot's settings for those."
    }
}

/** Retire un éventuel raisonnement « <think>…</think> » du texte qui arrive par morceaux. */
internal class ThinkFilter {
    private var inside = false
    private val buffer = StringBuilder()

    fun feed(text: String): String {
        buffer.append(text)
        val out = StringBuilder()
        while (true) {
            val s = buffer.toString()
            if (inside) {
                val end = s.indexOf("</think>")
                if (end < 0) { buffer.setLength(0); buffer.append(s.takeLast(7)); return out.toString() }
                buffer.setLength(0); buffer.append(s.substring(end + 8)); inside = false
            } else {
                val start = s.indexOf("<think>")
                if (start < 0) {
                    // Garder un début possible de balise pour le morceau suivant.
                    val keep = (1..6).firstOrNull { s.endsWith("<think>".take(it)) } ?: 0
                    out.append(s.dropLast(keep)); buffer.setLength(0); buffer.append(s.takeLast(keep)); return out.toString()
                }
                out.append(s.substring(0, start)); buffer.setLength(0); buffer.append(s.substring(start + 7)); inside = true
            }
        }
    }
}

/** Envoie chaque fournisseur vers son client : le modèle du téléphone pour « Local », le réseau sinon. */
class RoutingProviderClient(private val remote: ProviderClient, private val local: ProviderClient) : ProviderClient {
    private fun of(provider: ApiProvider) = if (provider == ApiProvider.Local) local else remote
    override suspend fun models(provider: ApiProvider, key: String) = of(provider).models(provider, key)
    override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) =
        of(provider).stream(provider, key, model, turns, onDelta)
    override suspend fun streamWithUsage(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        onLimits: (ProviderLimits) -> Unit, onDelta: (String) -> Unit) = of(provider).streamWithUsage(provider, key, model, turns, onLimits, onDelta)
    override suspend fun streamCounted(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit) =
        of(provider).streamCounted(provider, key, model, turns, onLimits, onTokens, onDelta)
    override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        extra: List<org.json.JSONObject>, tools: org.json.JSONArray?,
        onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit) =
        of(provider).streamTools(provider, key, model, turns, extra, tools, onLimits, onTokens, onDelta)
    override suspend fun transcribe(provider: ApiProvider, key: String, audio: File) = of(provider).transcribe(provider, key, audio)
}


/** Une fonction de Claydot présentée au moteur LiteRT-LM (exécutée par Claydot, pas par le moteur). */
private class LocalTool(private val function: org.json.JSONObject) : com.google.ai.edge.litertlm.OpenApiTool {
    override fun getToolDescriptionJsonString(): String = function.toString()
    override fun execute(p0: String): String = "{}"
}

/**
 * Garde-fous du modèle du téléphone, vérifiés avant chaque tour :
 * - chaleur (statut thermique d'Android) : tiède → réponses plus courtes et pauses ; chaud → arrêt ;
 * - batterie : sous 10 % hors charge → arrêt ; sous 20 % → mode doux ;
 * - mémoire : pas de chargement si le téléphone n'a pas assez de mémoire libre.
 */
class DeviceGuard(private val context: Context) {
    enum class Mode { Normal, Gentle }

    fun check(modelBytes: Long, loaded: Boolean): Mode {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus ?: 0 else 0
        if (thermal >= android.os.PowerManager.THERMAL_STATUS_SEVERE) throw ApiException(ApiFailure.DeviceHot, 60)
        val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 100) * 100 / it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 100
        val charging = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        // Température de la batterie en dixièmes de degré : au-delà de 43 °C, on laisse refroidir.
        val batteryTemp = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10
        if (batteryTemp >= 43) throw ApiException(ApiFailure.DeviceHot, 60)
        if (level <= 10 && !charging) throw ApiException(ApiFailure.LowBattery)
        if (!loaded) {
            val memory = android.app.ActivityManager.MemoryInfo()
            context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(memory)
            if (memory.lowMemory || memory.availMem < modelBytes / 2 + 400_000_000L) throw ApiException(ApiFailure.LowMemory)
        }
        return if (thermal >= android.os.PowerManager.THERMAL_STATUS_MODERATE || batteryTemp >= 40 || (level <= 20 && !charging)) Mode.Gentle else Mode.Normal
    }
}
