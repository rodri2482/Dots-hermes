package app.dotshermes.data

import android.content.Context
import app.dotshermes.data.gateway.GatewayChatRepository
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
import app.dotshermes.data.model.GatewayConfig
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
    /** Adresse de son gateway : chaque agent est un serveur (un profil Hermes) à part. */
    val url: String,
    /** Messages non lus, toutes conversations de l'agent confondues (dot principal et sous-dots). */
    val unread: Int,
    /** Une de ses conversations travaille. */
    val busy: Boolean,
)

/**
 * Les agents Hermes indépendants de l'app : une connexion ([GatewayChatRepository]) et une apparence
 * par agent appairé, et celui qui est affiché.
 *
 * L'isolation vient du serveur : chaque agent est un profil Hermes à part (processus, mémoire,
 * outils, clés), joint avec son propre jeton. Ici, rien n'est partagé entre deux agents : ni
 * socket, ni état de conversation, ni cache de fichiers. Une action (message, feu vert, réponse
 * rapide) part toujours par la connexion de l'agent qu'elle vise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRegistry(
    private val context: Context,
    private val scope: CoroutineScope,
    val store: AgentStore,
    /** Une demande de feu vert a été tranchée (ici ou ailleurs) : sa notification peut partir. */
    private val onApprovalResolved: (agentId: String, approvalId: String) -> Unit,
    private val apiStorage: DirectAccountStorage = androidApiVault(context),
    private val providerClient: ProviderClient = createProviderClient(),
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
    private val watchers = ConcurrentHashMap<String, Job>()

    /** Connexion de repli quand aucun agent n'est appairé : jamais connectée, l'écran d'appairage s'affiche. */
    private val unpaired by lazy { create(UNPAIRED) }

    @Volatile private var foreground = true
    @Volatile private var pushToken: String? = null

    /** Agent en cours d'ajout (connexion en essai, pas encore mémorisé) et celui qu'on affichait avant. */
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()
    @Volatile private var beforePending: String? = null

    private val _activeId = MutableStateFlow(store.lastActive ?: store.agents.value.firstOrNull()?.id)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    private val _connectionEditor = MutableStateFlow(editorFor(_activeId.value))
    val connectionEditor: StateFlow<ConnectionEditor> = _connectionEditor.asStateFlow()

    private fun editorFor(id: String?, gatewayHint: String = ""): ConnectionEditor {
        val link = id?.let(store::get)
        return ConnectionEditor(UUID.randomUUID().toString(), link?.id,
            // Nouveau compte : Groq d'abord, il suffit d'y coller sa clé API (Hermes reste à un onglet).
            // Déjà un compte Groq : « ajouter » sert alors à brancher un serveur Hermes.
            if (link != null) link.provider?.id ?: "hermes"
            else if (store.agents.value.any { it.provider == ApiProvider.Groq }) "hermes" else ApiProvider.Groq.id,
            link?.model.orEmpty(), link?.url?.takeIf { it.isNotBlank() } ?: gatewayHint)
    }


    init {
        // Un seul agent à l'écran : les autres comptent leurs messages comme non lus.
        scope.launch { _activeId.collect { active -> repos.forEach { (id, repo) -> repo.setShown(id == active) } } }
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

    fun gatewayRepo(id: String): GatewayChatRepository? = repo(id) as? GatewayChatRepository

    fun appearance(id: String): AppearanceStore = appearances.getOrPut(id) {
        AppearanceStore(context, if (id == AgentStore.PRIMARY_ID) "appearance" else "appearance-$id")
    }

    private fun create(id: String): ChatRepository {
        val link = store.get(id)
        if (link?.provider != null) {
            appearance(id).adoptServerName(COMPANION_NAME)
            return DirectChatRepository(id, link.provider, link.model, scope, apiStorage, providerClient,
                persona = { persona(appearance(id).state.value.displayName) }, toolbox = directTools).apply { setShown(id == _activeId.value) }
        }
        // L'agent historique garde son cache d'avant ; les autres ont le leur, à part.
        val cache = if (id == AgentStore.PRIMARY_ID) File(context.cacheDir, "voice") else File(context.cacheDir, "agents/$id/voice")
        val repo = GatewayChatRepository(context, scope, cache, onApprovalResolved = { approvalId -> onApprovalResolved(id, approvalId) })
        repo.setForeground(foreground)
        repo.setShown(id == _activeId.value)
        pushToken?.let(repo::setPushToken)
        if (id != UNPAIRED) {
            // Le gateway donne son identifiant (routage des push) et le nom de son profil (nom par défaut).
            watchers[id] = scope.launch {
                repo.serverAgent.collect { agent ->
                    if (agent == null) return@collect
                    if (store.get(id) != null) store.setRemoteId(id, agent.id)
                    appearance(id).adoptServerName(agent.name)
                }
            }
        }
        return repo
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
                if (repo is DirectChatRepository) repo.restore() else repo.connect(link.config)
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

    /**
     * Appaire [config] : l'agent en cours d'ajout, ou le premier s'il n'y en a aucun. Mémorisé
     * seulement si le gateway répond (welcome reçu) ; sinon l'écran montre l'erreur.
     */
    suspend fun connectNew(config: GatewayConfig): Boolean {
        val id = _pending.value ?: store.newId().also {
            beforePending = _activeId.value
            _pending.value = it
            _activeId.value = it
        }
        val revision = selectionRevision.get()
        val repo = gatewayRepo(id) ?: return false
        repo.connect(config)
        if (_pending.value != id || _activeId.value != id || selectionRevision.get() != revision) return false
        if (repo.connection.value !is ConnectionState.Connected) return false
        // Même gateway qu'un agent déjà appairé : pas de doublon, on ouvre celui qui existe.
        val remote = repo.serverAgent.value?.id
        store.agents.value.firstOrNull { remote != null && it.remoteId == remote }?.let { existing ->
            _pending.value = null
            drop(id)
            select(existing.id)
            return true
        }
        val first = store.agents.value.isEmpty()
        store.upsert(AgentLink(id, config.url, config.token, repo.serverAgent.value?.id))
        repo.serverAgent.value?.let { appearance(id).adoptServerName(it.name) }
        // À partir du deuxième agent, une autre mascotte que celles déjà prises.
        if (!first) {
            val taken = store.agents.value.filter { it.id != id }.map { appearance(it.id).state.value.species }.toSet()
            appearance(id).initialize(DotSpecies.entries.firstOrNull { it !in taken } ?: DotSpecies.entries[store.agents.value.size % DotSpecies.entries.size])
        }
        _pending.value = null
        select(id)
        return true
    }

    /** Nouvel appairage d'un agent existant (jeton changé, adresse déplacée) : même conversation, mêmes réglages. */
    suspend fun reconnect(id: String, config: GatewayConfig): Boolean {
        val repo = gatewayRepo(id) ?: return false
        repo.connect(config)
        if (repo.connection.value !is ConnectionState.Connected) return false
        store.get(id)?.let { store.upsert(it.copy(url = config.url, token = config.token, remoteId = repo.serverAgent.value?.id ?: it.remoteId)) }
        if (_activeId.value == id && _pending.value == null) _connectionEditor.value = editorFor(id)
        return true
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
                    inheritedCooldownUntilMs = knownCooldown, persona = { persona(appearance(id).state.value.displayName) }, toolbox = directTools)
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
            if (store.get(id)?.provider != null) apiStorage.remove(id)
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

    private fun drop(id: String) {
        watchers.remove(id)?.cancel()
        repos.remove(id)?.disconnect()
    }

    fun setForeground(foreground: Boolean) {
        this.foreground = foreground
        repos.values.forEach { it.setForeground(foreground) }
    }

    fun setPushToken(token: String) {
        pushToken = token
        repos.values.forEach { it.setPushToken(token) }
    }

    private companion object {
        const val UNPAIRED = "unpaired"
        /** Nom d'un compte direct tant qu'on ne l'a pas renommé : le personnage, pas le fournisseur. */
        const val COMPANION_NAME = "Claydot"
    }
}
