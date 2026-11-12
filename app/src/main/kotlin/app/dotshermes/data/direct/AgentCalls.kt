package app.dotshermes.data.direct

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Appel (échange vivant) ou message (une question, une réponse) entre deux agents de l'utilisateur. */
enum class CallMode { Call, Message }

/** Un échange en cours entre deux agents : [from] appelle ou écrit à [to] ; [connected] dès que [to] répond. */
data class AgentCall(val id: String, val from: String, val to: String, val mode: CallMode, val connected: Boolean = false,
    val startedAt: Long = System.currentTimeMillis())

/**
 * Les échanges en cours entre agents, pour l'en-tête (les deux mascottes, l'icône verte au milieu) et la liste
 * d'actions. Plusieurs à la fois : un agent peut confier des parties d'une tâche à plusieurs autres.
 */
object AgentCalls {
    private val _active = MutableStateFlow<List<AgentCall>>(emptyList())
    val active: StateFlow<List<AgentCall>> = _active.asStateFlow()

    @Synchronized fun start(from: String, to: String, mode: CallMode): String {
        val id = UUID.randomUUID().toString()
        _active.value = _active.value + AgentCall(id, from, to, mode)
        return id
    }

    @Synchronized fun connect(id: String) { _active.value = _active.value.map { if (it.id == id) it.copy(connected = true) else it } }

    /**
     * Raccroché : l'en-tête garde les deux mascottes encore un instant (au moins [MIN_SHOWN_MS] en tout, puis
     * [LINGER_MS]) pour que l'animation se voie même quand l'autre agent répond en une seconde.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    fun end(id: String) {
        val call = synchronized(this) { _active.value.firstOrNull { it.id == id } } ?: return
        val wait = maxOf(LINGER_MS, MIN_SHOWN_MS - (System.currentTimeMillis() - call.startedAt))
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.delay(wait)
            synchronized(this@AgentCalls) { _active.value = _active.value.filterNot { it.id == id } }
        }
    }

    private const val MIN_SHOWN_MS = 2_500L
    private const val LINGER_MS = 1_200L
}

/**
 * Garde-fou des échanges entre agents : pas de boucle (A demande à B qui redemande à A), pas d'agent sollicité deux fois
 * en même temps ; un agent peut en revanche interroger plusieurs autres agents en parallèle.
 */
internal object AgentTalk {
    private val asking = HashMap<String, Int>()
    private val asked = HashSet<String>()

    @Synchronized fun start(from: String, to: String): Boolean {
        if (from == to || (asking[to] ?: 0) > 0 || to in asked) return false
        asking[from] = (asking[from] ?: 0) + 1
        asked += to
        return true
    }

    @Synchronized fun end(from: String, to: String) {
        asking[from] = ((asking[from] ?: 1) - 1).coerceAtLeast(0)
        asked -= to
    }
}

/** Un modèle vers lequel basculer : [target] « agent:<id> » (autre agent) ou « local:<modèle> » (modèle du téléphone). */
data class SwitchOption(val target: String, val name: String, val detail: String, val strong: Boolean)
