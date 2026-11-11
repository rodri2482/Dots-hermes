package app.dotshermes.data

import android.content.Context
import androidx.core.content.edit
import app.dotshermes.data.direct.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.ui.AppearanceStore
import app.dotshermes.ui.displayName
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.dot.DotSpecies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Métadonnées cohérentes d'une session d'édition ; aucun secret ni brouillon n'y entre. */
data class ConnectionEditor(val key: String, val accountId: String?, val provider: String, val model: String, val gatewayUrl: String)

data class DirectAccountTarget(val activeId: String?, val pendingId: String?, val revision: Long)

/** Un agent tel que le sélecteur l'affiche : son apparence, sa connexion, ce qui l'occupe. */
data class AgentSummary(
    val id: String,
    val appearance: DotAppearance,
    val connection: ConnectionState,
    /** Vide pour les comptes de Claydot (gardé pour le format des réglages). */
    val url: String,
    /** Messages non lus, toutes conversations de l'agent confondues (dot principal et sous-dots). */
    val unread: Int,
    /** Une de ses conversations travaille. */
    val busy: Boolean,
)

/**
 * Les comptes de l'app (une clé API Groq, un modèle sur le téléphone) : une conversation
 * ([DirectChatRepository]) et une apparence par compte, et celui qui est affiché. Une action
 * (message, feu vert, réponse rapide) part toujours par le compte qu'elle vise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRegistry(
    private val context: Context,
    private val scope: CoroutineScope,
    val store: AgentStore,
    private val apiStorage: DirectAccountStorage = androidApiVault(context),
    private val providerClient: ProviderClient = RoutingProviderClient(createProviderClient(),
        LocalProviderClient(context) { app.dotshermes.AppContainer.localModels }),
    /** Texte « system » d'un compte direct, d'après le nom de son dot ; relu à chaque message. */
    private val persona: (name: String) -> String = { "" },
    /** Outils des comptes directs (Gmail en lecture seule), relus à chaque message. */
    private val directTools: () -> DirectTools? = { null },
) {
    private val selectionRevision = AtomicLong()
    private val accountWrites = Mutex()
    private val repoRevision = MutableStateFlow(0L)
    private val repos = ConcurrentHashMap<String, ChatRepository>()
    private val appearances = ConcurrentHashMap<String, AppearanceStore>()

    /** Connexion de repli quand aucun agent n'est appairé : jamais connectée, l'écran d'appairage s'affiche. */
    private val unpaired by lazy { create(UNPAIRED) }

    @Volatile private var foreground = true

    /** Agent en cours d'ajout (connexion en essai, pas encore mémorisé) et celui qu'on affichait avant. */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()
    @Volatile private var beforePending: String? = null

    // « Récemment » (vue Activité) : les tours que l'utilisateur a retirés, par agent. Le fil n'est pas touché.
    private val hiddenPrefs = context.getSharedPreferences("activity-hidden", Context.MODE_PRIVATE)
    private val _hiddenTurns = MutableStateFlow(hiddenPrefs.all.mapValues { (_, v) -> (v as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty() })
    val hiddenTurns: StateFlow<Map<String, Set<String>>> = _hiddenTurns.asStateFlow()

    fun hideTurns(agentId: String, keys: Collection<String>) {
        val next = (_hiddenTurns.value[agentId].orEmpty() + keys).toList().takeLast(500).toSet()
        hiddenPrefs.edit { putStringSet(agentId, next) }
        _hiddenTurns.value = _hiddenTurns.value + (agentId to next)
    }

    private val _activeId = MutableStateFlow(store.lastActive?.takeIf { store.get(it)?.provider != null }
        ?: store.agents.value.firstOrNull { it.provider != null }?.id)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    private val _connectionEditor = MutableStateFlow(editorFor(_activeId.value))
    val connectionEditor: StateFlow<ConnectionEditor> = _connectionEditor.asStateFlow()

    private fun editorFor(id: String?, gatewayHint: String = ""): ConnectionEditor {
        val link = id?.let(store::get)
        return ConnectionEditor(UUID.randomUUID().toString(), link?.id,
            // Claydot : une clé API (Groq) ou un modèle sur le téléphone ; plus de serveur Hermes à brancher.
            // Déjà un compte Groq : « ajouter » propose alors le modèle hors ligne.
            if (link != null) link.provider?.id ?: ApiProvider.Groq.id
            else if (store.agents.value.any { it.provider == ApiProvider.Groq }) ApiProvider.Local.id else ApiProvider.Groq.id,
            link?.model.orEmpty(), link?.url?.takeIf { it.isNotBlank() } ?: gatewayHint)
    }


    init {
        store.agents.value.filter { it.provider == null }.forEach { store.remove(it.id) }
        // Un seul agent à l'écran : les autres comptent leurs messages comme non lus.
        scope.launch { _activeId.collect { active -> repos.forEach { (id, repo) -> repo.setShown(id == active) } } }
        // Compte « sur ce téléphone » à l'écran : le modèle se charge tout de suite, la réponse part plus vite.
        scope.launch {
            combine(_activeId, store.agents) { id, _ -> id?.let(store::get) }.collect { link ->
                if (link?.provider == ApiProvider.Local) LocalProviderClient.instance?.warmUp(link.model)
            }
        }
    }

    val activeRepo: StateFlow<ChatRepository> =
        combine(_activeId, store.agents, repoRevision) { id, _, _ -> id?.let(::repo) ?: unpaired }.stateIn(scope, SharingStarted.Eagerly, _activeId.value?.let(::repo) ?: unpaired)

    /** Apparence de l'agent affiché ; le thème est commun à toute l'app (celui de l'agent historique). */
    val activeAppearance: StateFlow<DotAppearance> =
        combine(_activeId.flatMapLatest { id -> appearance(id ?: AgentStore.PRIMARY_ID).state }, theme.state) { look, global ->
            look.copy(theme = global.theme)
        }.stateIn(scope, SharingStarted.Eagerly, appearance(_activeId.value ?: AgentStore.PRIMARY_ID).state.value)

    /** Le thème (clair, sombre, système) vit dans les préférences de l'agent historique. */
    val theme: AppearanceStore get() = appearance(AgentStore.PRIMARY_ID)

    /** Tous les agents appairés, dans l'ordre d'ajout, avec de quoi dessiner le sélecteur. */
    val summaries: StateFlow<List<AgentSummary>> = combine(store.agents, repoRevision) { links, _ -> links }.flatMapLatest { links ->
        if (links.isEmpty()) flowOf(emptyList())
        else combine(links.map { summaryOf(it.id, it.url) }) { it.toList() }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun summaryOf(id: String, url: String) = repo(id).let { r ->
        combine(appearance(id).state, r.connection, r.unread, r.chatActivity) { look, connection, unread, activities ->
            AgentSummary(
                id, look, connection, url, unread.values.sum(),
                busy = activities.values.any { it != AgentActivity.Idle && it != AgentActivity.Reacting },
            )
        }
    }

    fun repo(id: String): ChatRepository = repos.getOrPut(id) { create(id) }

    fun appearance(id: String): AppearanceStore = appearances.getOrPut(id) {
        AppearanceStore(context, if (id == AgentStore.PRIMARY_ID) "appearance" else "appearance-$id")
    }

    private fun create(id: String): ChatRepository {
        val link = store.get(id)
        if (link?.provider != null) {
            appearance(id).adoptServerName(COMPANION_NAME)
            return DirectChatRepository(id, link.provider, link.model, scope, apiStorage, providerClient,
                persona = { persona(appearance(id).state.value.displayName) }, toolbox = directTools,
                imageDir = File(context.filesDir, "direct-images/$id"), encodeImage = { downscaleJpeg(it) }, localFallback = ::readyLocalModel).apply { setShown(id == _activeId.value) }
        }
        // Pas encore de compte (premier lancement, ajout en cours) : l'écran de connexion s'affiche.
        return UnpairedChatRepository()
    }

    fun select(id: String) {
        if (store.get(id) == null && id != _pending.value) return
        _pending.value?.takeIf { it != id && store.get(id) != null }?.let { abandoned ->
            _pending.value = null
            drop(abandoned)
            beforePending = null
        }
        selectionRevision.incrementAndGet()
        _activeId.value = id
        if (store.get(id) != null) {
            store.setActive(id)
            _connectionEditor.value = editorFor(id)
        }
    }

    /** Au lancement : chaque agent appairé se reconnecte, en parallèle. */
    fun connectAll() {
        store.agents.value.forEach { link ->
            val repo = repo(link.id)
            // Un compte direct naît « Connecting » (fond neutre au lancement) : il s'ouvre ici, comme un compte déconnecté.
            val closed = repo.connection.value == ConnectionState.Disconnected ||
                (repo is DirectChatRepository && repo.connection.value is ConnectionState.Connecting)
            if (closed) scope.launch {
                if (repo is DirectChatRepository) repo.restore()
            }
        }
    }

    /** Ouvre l'écran d'appairage pour un agent de plus ; [cancelAdd] revient à l'agent d'avant. */
    fun beginAdd(preserveEditor: Boolean = false) {
        cancelAdd()
        selectionRevision.incrementAndGet()
        val id = store.newId()
        beforePending = _activeId.value
        _pending.value = id
        _activeId.value = id
        if (!preserveEditor) _connectionEditor.value = editorFor(null,
            beforePending?.let(store::get)?.url ?: store.agents.value.lastOrNull()?.url.orEmpty())
    }

    fun cancelAdd() {
        val id = _pending.value ?: return
        selectionRevision.incrementAndGet()
        _pending.value = null
        drop(id)
        _activeId.value = beforePending?.takeIf { store.get(it) != null } ?: store.agents.value.firstOrNull()?.id
        _connectionEditor.value = editorFor(_activeId.value)
        beforePending = null
    }

    /** Champ de clé vide pendant la modification d'un compte : la clé déjà enregistrée sert, sans jamais passer par l'interface. */
    suspend fun availableModels(provider: ApiProvider, key: String): List<String> = withContext(Dispatchers.IO) {
        chatModels(provider, providerClient.models(provider, key.trim().ifEmpty { savedKey(provider) ?: throw ApiException(ApiFailure.MissingKey) }))
    }

    private fun savedKey(provider: ApiProvider): String? = _activeId.value?.takeIf { _pending.value == null }
        ?.let(store::get)?.takeIf { it.provider == provider }?.let { apiStorage.key(it.id) }?.takeIf { it.isNotBlank() }

    /** Abandon de la modification d'un compte direct : il repart avec sa clé enregistrée, rien n'est changé. */
    suspend fun resumeDirect(id: String) { (repos[id] as? DirectChatRepository)?.restore() }

    /** Ajout explicite : aucune inférence n'est faite par la création d'un compte. */
    fun directTarget() = DirectAccountTarget(_activeId.value, _pending.value, selectionRevision.get())

    suspend fun connectDirect(provider: ApiProvider, model: String, key: String, target: DirectAccountTarget): Boolean = withContext(Dispatchers.IO) {
        accountWrites.withLock {
            require(model.isNotBlank())
            if (directTarget() != target) return@withLock false
            val activeBefore = target.activeId
            val pendingBefore = target.pendingId
            // Un seul compte par fournisseur : une clé Groq de plus remplace celle du compte Groq existant
            // (conversation, voix et réglages gardés) au lieu de créer un deuxième « Claydot ».
            val existing = activeBefore?.let(store::get)?.takeIf { it.provider == provider }
                ?: store.agents.value.firstOrNull { it.provider == provider }
            // Identité unique : une opération annulée ne peut effacer le coffre d'une suivante.
            val id = existing?.id ?: "api-" + UUID.randomUUID().toString()
            val oldKey = existing?.let { apiStorage.key(id) }
            // Clé laissée vide : on ne change que le modèle, la clé enregistrée reste.
            val key = key.trim().ifEmpty { oldKey?.takeIf { it.isNotBlank() } ?: throw ApiException(ApiFailure.MissingKey) }
            var published = false
            try {
                currentCoroutineContext().ensureActive()
                apiStorage.saveKey(id, key)
                val knownCooldown = (repos[id] as? DirectChatRepository)?.cooldownFor(provider, model.trim(), key) ?: 0
                val direct = DirectChatRepository(id, provider, model.trim(), scope, apiStorage, providerClient,
                    inheritedCooldownUntilMs = knownCooldown, persona = { persona(appearance(id).state.value.displayName) }, toolbox = directTools,
                    imageDir = File(context.filesDir, "direct-images/$id"), encodeImage = { downscaleJpeg(it) }, localFallback = ::readyLocalModel)
                direct.restore()
                currentCoroutineContext().ensureActive()
                if (direct.connection.value !is ConnectionState.Connected) return@withLock false
                // Même dispatcher que les gestes UI : aucun changement de sélection entre contrôle et publication.
                withContext(Dispatchers.Main.immediate) publication@ {
                    if (directTarget() != target) return@publication false
                    drop(id)
                    direct.setShown(true)
                    repos[id] = direct
                    appearance(id).adoptServerName(COMPANION_NAME)
                    store.upsert(AgentLink(id, "", "", provider = provider, model = model.trim()))
                    repoRevision.update { it + 1 }
                    pendingBefore?.let(::drop)
                    _pending.value = null
                    select(id)
                    published = true
                    true
                }
            } finally {
                if (!published) {
                    if (existing == null) apiStorage.remove(id)
                    else if (oldKey != null) apiStorage.saveKey(id, oldKey) else apiStorage.removeKey(id)
                }
            }
        }
    }

    /** Oublie un agent : sa connexion se ferme, son jeton et son apparence sont effacés de l'appareil. */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        accountWrites.withLock {
            drop(id)
            if (store.get(id)?.provider != null) {
                runCatching { apiStorage.load(id).conversations.filter { it.id != id }.forEach { apiStorage.remove(it.id) } }
                File(context.filesDir, "direct-images/$id").deleteRecursively()
                runCatching { app.dotshermes.AppContainer.routines.deleteAccount(id) }
                apiStorage.remove(id)
            }
            // Tout ce que l'agent a laissé sur le téléphone : cache des vocaux, entrées masquées de l'activité.
            File(context.cacheDir, "agents/$id").deleteRecursively()
            hiddenPrefs.edit { remove(id) }
            _hiddenTurns.value = _hiddenTurns.value - id
            // Navigation et métadonnées d'éditeur publiées ensemble, comme les gestes utilisateur.
            withContext(Dispatchers.Main.immediate) {
                selectionRevision.incrementAndGet()
                val wasActive = _activeId.value == id
                store.remove(id)
                appearance(id).forget()
                if (wasActive) {
                    val next = store.agents.value.firstOrNull()?.id
                    _activeId.value = next
                    next?.let(store::setActive)
                    _connectionEditor.value = editorFor(next)
                }
            }
        }
    }

    /** Un modèle du téléphone téléchargé, pour répondre quand Groq est à court de quota. */
    private fun readyLocalModel(): String? = runCatching {
        LocalModels.CATALOG.firstOrNull { app.dotshermes.AppContainer.localModels.isReady(it) }?.id
    }.getOrNull()

    private fun drop(id: String) {
        repos.remove(id)?.disconnect()
    }

    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        repos.values.forEach { it.setForeground(foreground) }
    }

    private companion object {
        const val UNPAIRED = "unpaired"
        /** Nom d'un compte direct tant qu'on ne l'a pas renommé : le personnage, pas le fournisseur. */
        const val COMPANION_NAME = "Claydot"
    }
}
