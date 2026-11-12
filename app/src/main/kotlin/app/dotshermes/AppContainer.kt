package app.dotshermes

import android.content.Context
import app.dotshermes.data.AgentRegistry
import app.dotshermes.data.AgentStore
import app.dotshermes.data.RoutingChatRepository
import app.dotshermes.attach.Attachments
import app.dotshermes.push.Notifications
import app.dotshermes.update.AppUpdater
import app.dotshermes.voice.CompanionVoice
import app.dotshermes.voice.VoicePlayer
import app.dotshermes.voice.VoiceRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Injection manuelle : une connexion par agent appairé (gateway Hermes ou compte API direct). */
object AppContainer {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context

    /** Une activité est visible (tenu à jour par [HermesApplication]). */
    @Volatile var inForeground = false
        set(value) { field = value; foreground.value = value }
    private val foreground = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun init(context: Context) {
        appContext = context.applicationContext
        // Pastille de l'écran : hors de l'app, ce que Claydot dit et demande, au plus quatre fois par seconde.
        appScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            kotlinx.coroutines.flow.combine(app.dotshermes.data.direct.ChatCards.live, foreground) { note, shown -> note to shown }
                .sample(250)
                .collect { (note, shown) ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        app.dotshermes.screen.ClaydotAccessibilityService.instance?.overlay?.setNote(note, shown)
                    }
                }
        }
    }

    /**
     * Les comptes (Groq, modèle du téléphone), une conversation chacun. Exposé à part pour les notifications :
     * réponse rapide et feu vert visent toujours l'agent qui a écrit, pas celui qui est affiché.
     */
    val agents: AgentRegistry by lazy {
        AgentRegistry(appContext, appScope, AgentStore(appContext),
            persona = { name -> companion.persona(name) },
            directTools = { app.dotshermes.data.direct.CombinedTools(listOf(app.dotshermes.data.direct.GmailTools(gmail), phoneActions,
                app.dotshermes.data.direct.RoutineTools(appContext, routines, accountId = { agents.activeId.value }, confirm = phoneActions::ask,
                    access = { phoneActions.requestAccess(it) }), screen,
                app.dotshermes.data.direct.MessagingTools(appContext, enabled = { phoneActions.enabled.value }, confirm = phoneActions::ask, screen = { screenDriver },
                    access = { phoneActions.requestAccess(it) }),
                app.dotshermes.data.direct.WebTools(),
                // Cartes du fil : questions à choix, accès demandés au moment voulu, main passée à l'utilisateur.
                app.dotshermes.data.direct.CardTools(requestAccess = phoneActions::requestAccess, gmailConnected = { gmail.connected.value }))) })
    }

    val chatRepository: RoutingChatRepository by lazy {
        RoutingChatRepository(appScope, agents = agents)
    }
    val voiceRecorder: VoiceRecorder by lazy { VoiceRecorder(appContext, appScope) }
    val voicePlayer: VoicePlayer by lazy { VoicePlayer(appScope) }
    /** Personnalité et voix (Fish Audio) des comptes API directs. */
    /** Actions sur le téléphone (réveil, minuteur, app, agenda), toujours confirmées par l'utilisateur. */
    /** Contrôle de l'écran (service d'accessibilité), après accord pour chaque tâche. */
    val screen: app.dotshermes.data.direct.ScreenTools by lazy {
        app.dotshermes.data.direct.ScreenTools(screenDriver,
            enabled = { phoneActions.enabled.value }, confirm = phoneActions::ask, ownPackage = appContext.packageName,
            consent = { appContext.getString(app.dotshermes.R.string.screen_consent) }, autoGlm = { autoGlm },
            access = { phoneActions.requestAccess(app.dotshermes.data.direct.PhoneAccess.Screen) })
            .also { tools -> app.dotshermes.screen.ClaydotAccessibilityService.onStopRequested = { tools.revoke(); chatRepository.stopTurn() } }
    }
    /** Agent de téléphone AutoGLM (Open-AutoGLM), avec la clé Z.ai ou Novita de l'utilisateur. */
    val autoGlm: app.dotshermes.data.direct.AutoGlm by lazy { app.dotshermes.data.direct.AutoGlm(appContext, app.dotshermes.data.direct.androidApiVault(appContext)) }
    /** Modèles hors ligne téléchargés sur le téléphone (compte « Sur ce téléphone »). */
    val localModels: app.dotshermes.data.direct.LocalModels by lazy { app.dotshermes.data.direct.LocalModels(appContext, appScope) }
    val phoneActions: app.dotshermes.data.direct.PhoneActions by lazy { app.dotshermes.data.direct.PhoneActions(appContext) { screenDriver } }
    private val screenDriver: app.dotshermes.data.direct.ScreenDriver by lazy { app.dotshermes.screen.AccessibilityScreenDriver(appContext, phoneActions) }
    /** Routines programmées (déclencheur horaire + consigne), exécutées par WorkManager. */
    val routines: app.dotshermes.data.direct.RoutineStore by lazy { app.dotshermes.data.direct.RoutineStore(appContext) }
    /** Gmail en lecture seule pour les comptes Groq (outils de recherche et de lecture). */
    val gmail: app.dotshermes.data.gmail.GmailAccess by lazy { app.dotshermes.data.gmail.GmailAccess(appContext) }
    val companion: CompanionVoice by lazy { CompanionVoice(appContext) }
    val attachments: Attachments by lazy { Attachments(appContext) }
    val updater: AppUpdater by lazy { AppUpdater(appContext) }
}
