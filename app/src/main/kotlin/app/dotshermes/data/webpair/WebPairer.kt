package app.dotshermes.data.webpair

import app.dotshermes.data.AgentRegistry
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.displayName
import app.dotshermes.ui.dot.DotAccessory
import app.dotshermes.ui.dot.DotSpecies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Pourquoi un agent n'a pas été transmis au navigateur. */
sealed interface AgentIssue {
    val name: String

    /** Pas connecté en ce moment : seul un agent joignable peut délivrer un jeton web. */
    data class Offline(override val name: String) : AgentIssue

    /** Le gateway refuse l'origine : il faut l'ajouter à `DOTS_WEB_ORIGINS` (ou `install.sh --web-origin`). */
    data class OriginNotAllowed(override val name: String, val origin: String) : AgentIssue

    /** Plugin trop ancien pour l'app web. */
    data class Unsupported(override val name: String) : AgentIssue

    /** Connecté, mais pas de réponse dans les 10 s. */
    data class NoReply(override val name: String) : AgentIssue
}

/** Échec de l'appairage dans son ensemble. */
enum class PairFailure {
    /** Aucun agent n'a délivré de jeton : rien à transmettre (le détail est dans les [AgentIssue]). */
    NothingShared,

    /** Le relais a déjà un dépôt pour ce QR code (409) : il a expiré ou servi ; en afficher un nouveau. */
    Expired,

    /** Le relais refuse l'identifiant (400) : QR code invalide. */
    InvalidQr,

    /** Relais injoignable ou réponse inattendue. */
    Network,
}

/** Issue d'un appairage : les agents transmis, ceux qui ne l'ont pas été et pourquoi, l'échec éventuel. */
data class PairReport(val shared: List<String>, val issues: List<AgentIssue>, val failure: PairFailure?)

/**
 * Appaire un navigateur (docs/appairage-web.md, section 2) : un jeton web par agent connecté, puis
 * la liste des agents chiffrée pour la clé du navigateur et déposée sur le relais de l'app web.
 */
class WebPairer(
    private val agents: AgentRegistry,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build(),
) {
    private data class Granted(val agentId: String, val url: String, val remoteId: String?, val look: DotAppearance, val token: String, val sessionId: String)

    suspend fun pair(qr: PairQr): PairReport = coroutineScope {
        val label = label(qr)
        val results = agents.store.agents.value.filter { it.provider == null }.map { link ->
            async {
                val repo = requireNotNull(agents.gatewayRepo(link.id))
                val look = agents.appearance(link.id).state.value
                val name = look.displayName
                if (repo.connection.value !is ConnectionState.Connected) return@async AgentResult(issue = AgentIssue.Offline(name))
                when (val r = repo.createWebToken(qr.origin, label)) {
                    is WebTokenResult.Granted -> AgentResult(
                        granted = Granted(link.id, link.url, repo.serverAgent.value?.id ?: link.remoteId, look, r.token, r.sessionId),
                    )
                    is WebTokenResult.OriginNotAllowed -> AgentResult(issue = AgentIssue.OriginNotAllowed(name, r.origin.ifEmpty { qr.origin }))
                    WebTokenResult.Unsupported -> AgentResult(issue = AgentIssue.Unsupported(name))
                    WebTokenResult.Unavailable -> AgentResult(issue = AgentIssue.NoReply(name))
                }
            }
        }.awaitAll()
        val granted = results.mapNotNull { it.granted }
        val issues = results.mapNotNull { it.issue }
        if (granted.isEmpty()) return@coroutineScope PairReport(emptyList(), issues, PairFailure.NothingShared)

        val deposit = WebPairing.encrypt(qr, payload(granted).toByteArray(Charsets.UTF_8))
        val failure = post(qr, deposit)
        if (failure != null) {
            // Le navigateur ne recevra jamais ces jetons : on ne laisse pas de sessions orphelines sur les gateways.
            granted.forEach { agents.gatewayRepo(it.agentId)?.revokeWebSession(it.sessionId) }
        }
        PairReport(granted.map { it.look.displayName }, issues, failure)
    }

    private data class AgentResult(val granted: Granted? = null, val issue: AgentIssue? = null)

    /** Message en clair (section 3) : de quoi ouvrir chaque gateway et dessiner chaque mascotte. */
    private fun payload(granted: List<Granted>): String = JSONObject()
        .put("v", WebPairing.VERSION)
        .put("agents", JSONArray().apply {
            granted.forEach { g ->
                put(
                    JSONObject()
                        .put("url", socketUrl(g.url))
                        .put("token", g.token)
                        .put("session_id", g.sessionId)
                        .put("agent_id", g.remoteId ?: JSONObject.NULL)
                        .put("name", g.look.displayName)
                        .put("species", g.look.species.wireId)
                        .put("palette", g.look.palette.id)
                        .put("accessory", g.look.accessory.wireId),
                )
            }
        })
        .put("lang", Locale.getDefault().language)
        .put("issued_at", System.currentTimeMillis())
        .toString()

    /**
     * Le navigateur ouvre un `WebSocket` : il lui faut `wss://` (ou `ws://` en local). OkHttp accepte
     * aussi `https://` saisi à l'appairage ; l'app web, elle, refuserait le message entier.
     */
    private fun socketUrl(url: String): String {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
            else -> trimmed
        }
    }

    /** Dépose le message chiffré ; null si le relais l'a gardé (201). */
    private suspend fun post(qr: PairQr, deposit: PairDeposit): PairFailure? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(qr.relayUrl)
            .post(deposit.toJson().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    201, 200 -> null
                    409 -> PairFailure.Expired
                    400 -> PairFailure.InvalidQr
                    else -> PairFailure.Network
                }
            }
        } catch (e: IOException) {
            PairFailure.Network
        } catch (e: IllegalArgumentException) {
            PairFailure.InvalidQr
        }
    }

    /** Nom de la session dans la liste des navigateurs : « dots.vercel.app · 4 nov. 2026 18:05 » (80 caractères au plus côté gateway). */
    private fun label(qr: PairQr): String {
        val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()).format(ZonedDateTime.now())
        return "${qr.host} · $date".take(80)
    }
}

/** Identifiants du message d'appairage : en minuscules, indépendants des noms d'énumération Kotlin. */
val DotSpecies.wireId: String
    get() = when (this) {
        DotSpecies.Galet -> "galet"
        DotSpecies.Nuage -> "nuage"
        DotSpecies.Pave -> "pave"
    }

val DotAccessory.wireId: String
    get() = when (this) {
        DotAccessory.WingedCap -> "winged_cap"
        DotAccessory.None -> "none"
    }
