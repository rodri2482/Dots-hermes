package app.dotshermes.push

import app.dotshermes.AppContainer
import app.dotshermes.data.gateway.GatewayProtocol
import app.dotshermes.data.model.MAIN_CHAT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID

/**
 * Actions lancées depuis une notification (réponse, feu vert), toujours vers l'agent [agent] qui a
 * écrit. Si l'app tourne et que cet agent est connecté, elles passent par sa socket ; sinon,
 * connexion éclair avec l'appairage mémorisé de cet agent. Agent inconnu (retiré entre-temps) : rien ne part.
 */
object QuickReply {
    private val client by lazy { OkHttpClient() }

    suspend fun send(agent: String, text: String, chat: String = MAIN_CHAT): Boolean {
        if (AppContainer.agents.store.get(agent)?.provider != null || AppContainer.agents.store.get(agent) == null) return false
        if (AppContainer.agents.gatewayRepo(agent)?.sendIfConnected(text, chat) == true) return true
        val clientId = UUID.randomUUID().toString()
        return oneShot(agent, GatewayProtocol.message(clientId, text, chat)) { it.optString("type") == "ack" && it.optString("client_id") == clientId }
    }

    /** Approuver / refuser une commande : terminé quand le gateway confirme la résolution. */
    suspend fun answerApproval(agent: String, approvalId: String, approved: Boolean): Boolean {
        if (AppContainer.agents.store.get(agent)?.provider != null || AppContainer.agents.store.get(agent) == null) return false
        if (AppContainer.agents.gatewayRepo(agent)?.answerApprovalIfConnected(approvalId, approved) == true) return true
        return oneShot(agent, GatewayProtocol.approval(approvalId, approved)) {
            it.optString("type") == "approval_resolved" && it.optString("approval_id") == approvalId
        }
    }

    /**
     * Connexion éclair : `hello` sans rattrapage (last_seq immense, rien à rejouer), une trame,
     * puis attente de la trame de confirmation [done] avant de fermer.
     */
    private suspend fun oneShot(agent: String, frame: String, done: (JSONObject) -> Boolean): Boolean {
        val config = AppContainer.agents.store.get(agent)?.config ?: return false
        val confirmed = CompletableDeferred<Boolean>()
        val request = Request.Builder().url(config.url).header("Authorization", "Bearer ${config.token}").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(GatewayProtocol.hello(Long.MAX_VALUE / 2, foreground = false, pushToken = null))
                webSocket.send(frame)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val reply = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (done(reply)) confirmed.complete(true)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                confirmed.complete(false)
            }
        })
        val ok = withTimeoutOrNull(15_000) { confirmed.await() } ?: false
        socket.close(1000, null)
        return ok
    }
}
