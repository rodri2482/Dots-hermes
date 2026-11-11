package app.dotshermes.push

import app.dotshermes.AppContainer
import app.dotshermes.data.direct.DirectChatRepository
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.data.model.MAIN_CHAT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Actions lancées depuis une notification (réponse à une routine, à un message), toujours vers le
 * compte [agent] qui a écrit. Compte inconnu (retiré entre-temps) : rien ne part.
 */
object QuickReply {
    suspend fun send(agent: String, text: String, chat: String = MAIN_CHAT): Boolean = withContext(Dispatchers.Main.immediate) {
        if (AppContainer.agents.store.get(agent)?.provider == null) return@withContext false
        val repo = AppContainer.agents.repo(agent) as? DirectChatRepository ?: return@withContext false
        // Processus relancé par la notification : le compte s'ouvre d'abord (clé, conversation enregistrée).
        if (repo.connection.value !is ConnectionState.Connected) repo.restore()
        if (chat != MAIN_CHAT) repo.openConversation(chat)
        repo.send(text)
        true
    }

    /** Les feux verts des comptes Claydot se donnent dans l'app (la feuille de permission). */
    @Suppress("UNUSED_PARAMETER")
    suspend fun answerApproval(agent: String, approvalId: String, approved: Boolean): Boolean = false
}
