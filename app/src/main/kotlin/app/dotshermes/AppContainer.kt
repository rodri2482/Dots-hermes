package app.dotshermes

import android.content.Context
import app.dotshermes.data.AgentRegistry
import app.dotshermes.data.AgentStore
import app.dotshermes.data.RoutingChatRepository
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

/** Injection manuelle : une connexion par agent appairé (gateway Hermes ou compte API direct). */
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
            persona = { name -> companion.persona(name) },
            directTools = { app.dotshermes.data.direct.CombinedTools(listOf(app.dotshermes.data.direct.GmailTools(gmail), phoneActions,
                app.dotshermes.data.direct.RoutineTools(routines, accountId = { agents.activeId.value }, confirm = phoneActions::ask), screen)) })
    }

    val chatRepository: RoutingChatRepository by lazy {
        RoutingChatRepository(appScope, agents = agents)
    }
    /** Appairage de l'app web par QR code : jetons web des gateways, dépôt chiffré sur le relais. */
    val webPairer: WebPairer by lazy { WebPairer(agents) }
    val voiceRecorder: VoiceRecorder by lazy { VoiceRecorder(appContext, appScope) }
    val voicePlayer: VoicePlayer by lazy { VoicePlayer(appScope) }
    /** Personnalité et voix (Fish Audio) des comptes API directs. */
    /** Actions sur le téléphone (réveil, minuteur, app, agenda), toujours confirmées par l'utilisateur. */
    /** Contrôle de l'écran (service d'accessibilité), après accord pour chaque tâche. */
    val screen: app.dotshermes.data.direct.ScreenTools by lazy {
        app.dotshermes.data.direct.ScreenTools(screenDriver,
            enabled = { phoneActions.enabled.value }, confirm = phoneActions::ask, ownPackage = appContext.packageName,
            consent = { appContext.getString(app.dotshermes.R.string.screen_consent) })
    }
    val phoneActions: app.dotshermes.data.direct.PhoneActions by lazy { app.dotshermes.data.direct.PhoneActions(appContext) { screenDriver } }
    private val screenDriver: app.dotshermes.data.direct.ScreenDriver by lazy { app.dotshermes.screen.AccessibilityScreenDriver(appContext, phoneActions) }
    /** Routines programmées (déclencheur horaire + consigne), exécutées par WorkManager. */
    val routines: app.dotshermes.data.direct.RoutineStore by lazy { app.dotshermes.data.direct.RoutineStore(appContext) }
    /** Gmail en lecture seule pour les comptes Groq (outils de recherche et de lecture). */
    val gmail: app.dotshermes.data.gmail.GmailAccess by lazy { app.dotshermes.data.gmail.GmailAccess(appContext) }
    val companion: CompanionVoice by lazy { CompanionVoice(appContext, appScope, app.dotshermes.data.direct.androidApiVault(appContext)) }
    val attachments: Attachments by lazy { Attachments(appContext) }
    val updater: AppUpdater by lazy { AppUpdater(appContext) }
}
