package app.dotshermes

import android.content.Context
import app.dotshermes.data.AgentRegistry
import app.dotshermes.data.AgentStore
import app.dotshermes.data.RoutingChatRepository
import app.dotshermes.data.mock.MockChatRepository
import app.dotshermes.data.webpair.WebPairer
import app.dotshermes.attach.Attachments
import app.dotshermes.push.Notifications
import app.dotshermes.update.AppUpdater
import app.dotshermes.voice.CompanionVoice
import app.dotshermes.voice.VoicePlayer
import app.dotshermes.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Injection manuelle : un gateway réel par agent appairé, agent simulé pour l'adresse « demo ». */
object AppContainer {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context

    /** Une activité est visible (tenu à jour par [HermesApplication]). */
    @Volatile var inForeground = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Les agents Hermes appairés, une connexion chacun. Exposé à part pour les notifications :
     * réponse rapide et feu vert visent toujours l'agent qui a écrit, pas celui qui est affiché.
     */
    val agents: AgentRegistry by lazy {
        AgentRegistry(appContext, appScope, AgentStore(appContext), onApprovalResolved = { _, approvalId -> Notifications.cancelApproval(appContext, approvalId) },
            persona = { name -> companion.persona(name) })
    }

    val chatRepository: RoutingChatRepository by lazy {
        RoutingChatRepository(appScope, agents = agents, demo = MockChatRepository(appContext, appScope))
    }
    /** Appairage de l'app web par QR code : jetons web des gateways, dépôt chiffré sur le relais. */
    val webPairer: WebPairer by lazy { WebPairer(agents) }
    val voiceRecorder: VoiceRecorder by lazy { VoiceRecorder(appContext, appScope) }
    val voicePlayer: VoicePlayer by lazy { VoicePlayer(appScope) }
    /** Personnalité et voix (Fish Audio) des comptes API directs. */
    val companion: CompanionVoice by lazy { CompanionVoice(appContext, appScope, app.dotshermes.data.direct.androidApiVault(appContext)) }
    val attachments: Attachments by lazy { Attachments(appContext) }
    val updater: AppUpdater by lazy { AppUpdater(appContext) }
}
