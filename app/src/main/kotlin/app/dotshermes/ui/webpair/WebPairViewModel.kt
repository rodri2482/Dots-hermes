package app.dotshermes.ui.webpair

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.dotshermes.BuildConfig
import app.dotshermes.data.AgentRegistry
import app.dotshermes.data.AgentSummary
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.webpair.PairQr
import app.dotshermes.data.webpair.PairQrError
import app.dotshermes.data.webpair.PairQrParse
import app.dotshermes.data.webpair.PairReport
import app.dotshermes.data.webpair.WebPairer
import app.dotshermes.data.webpair.WebPairing
import app.dotshermes.data.webpair.WebSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Où en est l'appairage d'un navigateur. */
sealed interface WebPairStep {
    /** Caméra ouverte ; [error] : le dernier QR code lu n'était pas valable (affiché sous le viseur). */
    data class Scanning(val error: PairQrError? = null) : WebPairStep

    /** QR code valable : on demande confirmation avant de transmettre quoi que ce soit. */
    data class Confirm(val qr: PairQr) : WebPairStep

    /** Jetons demandés aux gateways, puis dépôt chiffré sur le relais. */
    data class Working(val qr: PairQr) : WebPairStep

    /** Terminé : réussi ou non, avec le détail par agent. */
    data class Done(val qr: PairQr, val report: PairReport) : WebPairStep
}

/** L'écran « Connecter un navigateur » et la liste des sessions web, pour tous les agents appairés. */
class WebPairViewModel(private val agents: AgentRegistry, private val pairer: WebPairer) : ViewModel() {

    private val _step = MutableStateFlow<WebPairStep>(WebPairStep.Scanning())
    val step: StateFlow<WebPairStep> = _step.asStateFlow()

    val agentList: StateFlow<List<AgentSummary>> = agents.summaries

    private var pairing: Job? = null

    /** Dernier texte refusé : la caméra le relit plusieurs fois par seconde, inutile de le réanalyser. */
    private var lastRejected: String? = null

    /** Nouvelle ouverture de l'écran : on repart du scanner. */
    fun reset() {
        pairing?.cancel()
        lastRejected = null
        _step.value = WebPairStep.Scanning()
    }

    fun onScanned(raw: String) {
        if (_step.value !is WebPairStep.Scanning || raw == lastRejected) return
        // `http://` local seulement en debug : l'app web servie par `vite` sur le poste de développement.
        when (val parsed = WebPairing.parse(raw, allowLocalHttp = BuildConfig.DEBUG)) {
            is PairQrParse.Valid -> _step.value = WebPairStep.Confirm(parsed.qr)
            is PairQrParse.Invalid -> {
                lastRejected = raw
                _step.value = WebPairStep.Scanning(parsed.error)
            }
        }
    }

    fun confirm() {
        val qr = (_step.value as? WebPairStep.Confirm)?.qr ?: return
        _step.value = WebPairStep.Working(qr)
        pairing = viewModelScope.launch { _step.value = WebPairStep.Done(qr, pairer.pair(qr)) }
    }

    /** Retour au viseur (annulation, ou nouvel essai avec un QR code frais). */
    fun rescan() {
        pairing?.cancel()
        lastRejected = null
        _step.value = WebPairStep.Scanning()
    }

    // -- Sessions web ------------------------------------------------------------------------------

    fun sessions(agentId: String): StateFlow<List<WebSession>?> = agents.gatewayRepo(agentId)?.webSessions ?: kotlinx.coroutines.flow.MutableStateFlow(null)

    /** Redemande la liste à chaque agent connecté. */
    fun refreshSessions() {
        agents.store.agents.value.forEach { link ->
            val repo = agents.gatewayRepo(link.id) ?: return@forEach
            if (repo.connection.value is ConnectionState.Connected) repo.requestWebSessions()
        }
    }

    fun revoke(agentId: String, sessionId: String) {
        agents.gatewayRepo(agentId)?.revokeWebSession(sessionId)
    }

    override fun onCleared() {
        pairing?.cancel()
    }
}
