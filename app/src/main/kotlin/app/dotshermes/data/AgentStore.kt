package app.dotshermes.data

import androidx.core.content.edit
import android.content.Context
import app.dotshermes.data.model.GatewayConfig
import app.dotshermes.data.direct.ApiProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Un agent Hermes appairé : un gateway (un profil Hermes) avec son adresse et son jeton.
 *
 * [id] est local à l'app (clé des préférences, des notifications, des caches). [remoteId] est
 * l'identifiant que le gateway annonce dans `welcome` et dans ses push : il sert à retrouver
 * l'agent d'une notification. Null tant que le gateway ne l'a pas donné (plugin d'avant 1.2).
 */
data class AgentLink(val id: String, val url: String, val token: String, val remoteId: String? = null,
                     val provider: ApiProvider? = null, val model: String = "") {
    init { require(provider == null || (token.isEmpty() && model.isNotBlank())) }
    val config: GatewayConfig get() { check(provider == null); return GatewayConfig(url, token) }
}

/**
 * Agent auquel rattacher une notification push, d'après l'identifiant d'agent qu'elle porte.
 *
 * Une réponse rapide ou un feu vert partent vers l'agent choisi ici : dans le doute, on ne choisit
 * personne (la notification n'est pas affichée) plutôt que de risquer l'agent d'à côté.
 * - identifiant connu : cet agent ;
 * - identifiant inconnu : l'agent qui n'a pas encore donné le sien (gateway mis à jour depuis la
 *   dernière connexion), s'il est seul dans ce cas ; sinon personne (agent retiré de l'app) ;
 * - pas d'identifiant (plugin d'avant 1.2) : l'agent unique, ou le seul agent sans identifiant.
 */
fun routePush(agents: List<AgentLink>, remoteId: String?): AgentLink? {
    val gateways = agents.filter { it.provider == null }
    if (remoteId.isNullOrEmpty()) return gateways.singleOrNull() ?: gateways.singleOrNull { it.remoteId == null }
    return gateways.firstOrNull { it.remoteId == remoteId } ?: gateways.singleOrNull { it.remoteId == null }
}

/**
 * Les agents appairés et celui qui est affiché, dans les préférences privées de l'app (sauvegarde
 * Android désactivée dans le manifeste : les jetons ne quittent pas le téléphone).
 *
 * Reprend l'appairage unique des versions précédentes (préférences « gateway ») comme premier
 * agent, sous l'identifiant [PRIMARY_ID] : son apparence et ses caches restent ceux d'avant.
 */
class AgentStore(context: Context) {
    private val prefs = context.getSharedPreferences("agents", Context.MODE_PRIVATE)

    private val _agents = MutableStateFlow(load(context))
    val agents: StateFlow<List<AgentLink>> = _agents.asStateFlow()

    /** Dernier agent affiché, pour rouvrir l'app au même endroit. */
    val lastActive: String? get() = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> _agents.value.any { it.id == id } }

    fun get(id: String): AgentLink? = _agents.value.firstOrNull { it.id == id }

    /** Agent d'une notification push (voir [routePush]). */
    fun forPush(remoteId: String?): AgentLink? = routePush(_agents.value, remoteId)

    /** Identifiant d'un nouvel agent : le premier reprend celui de l'appairage historique. */
    fun newId(): String = if (_agents.value.none { it.id == PRIMARY_ID }) PRIMARY_ID else "a-" + UUID.randomUUID().toString().take(8)

    fun upsert(link: AgentLink) = save { all ->
        if (all.any { it.id == link.id }) all.map { if (it.id == link.id) link else it } else all + link
    }

    fun setRemoteId(id: String, remoteId: String) = save { all ->
        all.map { if (it.id == id && it.remoteId != remoteId) it.copy(remoteId = remoteId) else it }
    }

    fun remove(id: String) = save { all -> all.filterNot { it.id == id } }

    fun setActive(id: String) = prefs.edit { putString(KEY_ACTIVE, id) }

    private fun save(transform: (List<AgentLink>) -> List<AgentLink>) {
        _agents.update(transform)
        prefs.edit { putString(KEY_LIST, encode(_agents.value)) }
    }

    private fun load(context: Context): List<AgentLink> {
        prefs.getString(KEY_LIST, null)?.let { return decode(it) }
        // Première ouverture après la mise à jour : l'appairage unique devient le premier agent.
        val legacy = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val url = legacy.getString("url", null)
        val token = legacy.getString("token", null)
        val migrated = if (url != null && token != null) listOf(AgentLink(PRIMARY_ID, url, token)) else emptyList()
        prefs.edit { putString(KEY_LIST, encode(migrated)) }
        legacy.edit { clear() }
        return migrated
    }

    private fun encode(agents: List<AgentLink>): String = AgentLinkCodec.encode(agents)
    private fun decode(raw: String): List<AgentLink> = AgentLinkCodec.decode(raw)

    companion object {
        const val PRIMARY_ID = "main"
        private const val KEY_LIST = "list"
        private const val KEY_ACTIVE = "active"
    }
}

/** Métadonnées non secrètes des comptes directs ; les clés API restent exclusivement dans le coffre. */
internal object AgentLinkCodec {
    fun encode(agents: List<AgentLink>): String = JSONArray().apply {
        agents.forEach { a ->
            put(JSONObject().put("id", a.id).put("url", a.url).put("token", a.token).apply {
                a.remoteId?.let { put("remote_id", it) }
                a.provider?.let { put("provider", it.id); put("model", a.model) }
            })
        }
    }.toString()

    fun decode(raw: String): List<AgentLink> = runCatching {
        val array = JSONArray(raw)
        List(array.length()) { array.getJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            runCatching {
                val provider = if (o.has("provider")) ApiProvider.fromId(o.getString("provider")) ?: return@mapNotNull null else null
                AgentLink(id, o.optString("url"), o.optString("token"), o.optString("remote_id").takeIf { it.isNotEmpty() }, provider, o.optString("model"))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

}
