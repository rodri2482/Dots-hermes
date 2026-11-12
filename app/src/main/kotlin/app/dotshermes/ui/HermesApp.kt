package app.dotshermes.ui

import app.dotshermes.ui.dots.appearance
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.dotshermes.ui.theme.ThemeMode
import app.dotshermes.ui.dots.DotEditor
import app.dotshermes.ui.dots.DotsSheet
import app.dotshermes.ui.dots.DotsCarousel
import app.dotshermes.ui.dots.heroDots
import androidx.compose.runtime.remember
import androidx.compose.runtime.key

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dotshermes.AppContainer
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.ui.agent.AgentSettings
import app.dotshermes.analytics.Analytics
import app.dotshermes.ui.chat.ChatScreen
import app.dotshermes.ui.chat.VoiceControls
import app.dotshermes.ui.chat.AttachmentControls
import app.dotshermes.ui.activity.ActivitySheet
import app.dotshermes.ui.screen.ScreenViewer
import app.dotshermes.ui.agents.AgentMenu
import app.dotshermes.ui.theme.DotsTheme
import androidx.compose.ui.res.stringResource
import app.dotshermes.R
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.dotshermes.update.Release
import app.dotshermes.update.UpdateState
import app.dotshermes.ui.update.UpdateDialog
import app.dotshermes.ui.update.updateStatusText
import kotlinx.coroutines.launch

/** Racine : écran d'appairage tant que le gateway n'est pas connecté, puis la conversation unique. */
@Composable
fun HermesApp(
    viewModel: HermesViewModel = viewModel {
        HermesViewModel(AppContainer.chatRepository, AppContainer.agents, AppContainer.voiceRecorder, AppContainer.voicePlayer, AppContainer.attachments, AppContainer.companion)
    },
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val gatewayFeatures by viewModel.gatewayFeatures.collectAsStateWithLifecycle()
    val voiceInput by viewModel.voiceInput.collectAsStateWithLifecycle()
    val imageInput by viewModel.imageInput.collectAsStateWithLifecycle()
    val companion by AppContainer.companion.state.collectAsStateWithLifecycle()
    val requestIssue by viewModel.requestIssue.collectAsStateWithLifecycle()
    val accountFailure by viewModel.accountFailure.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val snapshot by viewModel.activitySnapshot.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val directUsage by viewModel.directUsage.collectAsStateWithLifecycle()
    val turns by viewModel.turns.collectAsStateWithLifecycle()
    val liveTools by viewModel.liveTools.collectAsStateWithLifecycle()
    val browsing by viewModel.browsing.collectAsStateWithLifecycle()
    var agentSheetOpen by rememberSaveable { mutableStateOf(false) }
    var agentMenuOpen by rememberSaveable { mutableStateOf(false) }
    var activityOpen by rememberSaveable { mutableStateOf(false) }
    var screenOpen by rememberSaveable { mutableStateOf(false) }
    var screenTakeover by rememberSaveable { mutableStateOf(false) }
    val look by viewModel.activeLook.collectAsStateWithLifecycle()
    val dots by viewModel.dots.collectAsStateWithLifecycle()
    val activeChat by viewModel.activeChat.collectAsStateWithLifecycle()
    val chatActivity by viewModel.chatActivity.collectAsStateWithLifecycle()
    val unread by viewModel.unread.collectAsStateWithLifecycle()
    val agentList by viewModel.agentList.collectAsStateWithLifecycle()
    val activeAgent by viewModel.activeAgent.collectAsStateWithLifecycle()
    val connectionEditor by viewModel.connectionEditor.collectAsStateWithLifecycle()
    val addingAgent by viewModel.addingAgent.collectAsStateWithLifecycle()
    val otherAgentsUnread by viewModel.otherAgentsUnread.collectAsStateWithLifecycle()
    var deletingAccount by remember { mutableStateOf<String?>(null) }
    var clearingChat by remember { mutableStateOf(false) }
    var chatsOpen by rememberSaveable { mutableStateOf(false) }
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    var creatingDot by rememberSaveable { mutableStateOf(false) }
    // Apparence saisie dans l'éditeur d'un sous-dot (ou du nouveau) : le carrousel l'affiche en direct.
    var editPreview by remember { mutableStateOf<DotAppearance?>(null) }

    // Mise à jour : vérifiée à l'ouverture ; le dialogue s'ouvre pour une nouvelle version (une fois
    // par version, sauf si l'on touche la notification ou la ligne « Version » des réglages).
    val updater = AppContainer.updater
    val updateState by updater.state.collectAsStateWithLifecycle()
    val updatePrompt by updater.promptRequested.collectAsStateWithLifecycle()
    var updateDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        updater.check()?.let { if (updater.shouldPrompt(it)) updateDialog = true }
    }
    LaunchedEffect(updatePrompt) {
        if (!updatePrompt) return@LaunchedEffect
        updater.clearPrompt()
        if (updateState is UpdateState.Available || updater.check(force = true) != null) updateDialog = true
    }
    // Retour des réglages « sources inconnues » : si l'accord est donné, l'installation reprend seule.
    LifecycleResumeEffect(updateState) {
        val waiting = updateState as? UpdateState.NeedsPermission
        if (waiting != null && updater.canInstall()) scope.launch { updater.install(waiting.release) }
        onPauseOrDispose { }
    }

    // Statistiques d'usage : l'écran au premier plan (le plus haut des panneaux ouverts).
    val analyticsScreen = when {
        screenOpen -> "live_screen"
        activityOpen -> "activity"
        agentSheetOpen -> if (creatingDot || look.subDot != null) "dot_editor" else "agent_settings"
        agentMenuOpen -> "agent_menu"
        connection !is ConnectionState.Connected -> "connect"
        else -> "chat"
    }
    LaunchedEffect(analyticsScreen) { Analytics.screen(context, analyticsScreen) }

    val dark = when (appearance.theme) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    SystemBarsFor(dark)
    // Cartes du fil : « Continuer » sur une carte d'accès ouvre le vrai dialogue ou réglage Android (jamais
    // d'accès donné par un bouton du fil) ; la tâche vérifie ensuite que l'accès est vraiment là.
    var contactsCard by rememberSaveable { mutableStateOf<String?>(null) }
    val askPermissions = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val id = contactsCard ?: return@rememberLauncherForActivityResult
        contactsCard = null
        if (result.isNotEmpty() && result.values.all { it }) return@rememberLauncherForActivityResult
        // Refus définitif (Android ne montre plus la demande) : la page de l'app dans les réglages ; sinon, la carte est refusée.
        val activity = context as? android.app.Activity
        val blocked = activity != null && app.dotshermes.data.direct.MessagingTools.PERMISSIONS.any {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                !activity.shouldShowRequestPermissionRationale(it)
        }
        if (blocked) runCatching {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } else viewModel.replyCard(id, app.dotshermes.data.direct.CardReply.Decline)
    }
    val cardReply: (app.dotshermes.data.model.ChatCard, app.dotshermes.data.direct.CardReply) -> Unit = { card, reply ->
        viewModel.replyCard(card.id, reply)
        // « Changer » sur une carte « Modèle conseillé » : l'agent ou le modèle du téléphone proposé.
        if (card.kind == app.dotshermes.data.model.CardKind.Switch && reply is app.dotshermes.data.direct.CardReply.Answer) {
            val target = card.access.orEmpty()
            when {
                target.startsWith("agent:") -> viewModel.selectAgent(target.removePrefix("agent:"))
                target.startsWith("local:") -> viewModel.switchModel(app.dotshermes.data.direct.ApiProvider.Local, target.removePrefix("local:"))
            }
        }
        if (reply == app.dotshermes.data.direct.CardReply.Continue && card.kind == app.dotshermes.data.model.CardKind.Access) {
            val access = app.dotshermes.data.direct.PhoneAccess.entries.firstOrNull { it.name == card.access }
            when {
                card.access == app.dotshermes.data.direct.CardTools.GMAIL -> agentSheetOpen = true
                access == app.dotshermes.data.direct.PhoneAccess.Contacts -> {
                    contactsCard = card.id
                    askPermissions.launch(app.dotshermes.data.direct.MessagingTools.PERMISSIONS)
                }
                access != null -> app.dotshermes.data.direct.PhoneActions.settingsIntent(context, access)?.let { intent ->
                    runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        }
    }
    // La conversation affichée donne sa couleur à toute l'interface : on sait toujours à quel dot on parle.
    DotsTheme(look.appearance.palette, dark) {
        val root = when {
            connection is ConnectionState.Connected -> RootScreen.Chat
            // Compte direct en cours d'ouverture (lecture locale, quelques millisecondes) : fond neutre.
            connection is ConnectionState.Connecting && !gatewayFeatures -> RootScreen.Launch
            else -> RootScreen.Connect
        }
        @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
        androidx.compose.animation.SharedTransitionLayout {
        val sharedScope = this
        AnimatedContent(
            targetState = root,
            transitionSpec = { (fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 0.97f)) togetherWith fadeOut(tween(200)) },
            label = "root",
        ) { shown ->
            androidx.compose.runtime.CompositionLocalProvider(
                app.dotshermes.ui.dot.LocalMascotTransition provides app.dotshermes.ui.dot.MascotTransition(sharedScope, this),
                app.dotshermes.ui.chat.LocalCardReply provides cardReply,
            ) {
            if (shown == RootScreen.Chat) {
                key(activeAgent, activeChat) {
                ChatScreen(
                    state = chat,
                    gatewayFeatures = gatewayFeatures, voiceInput = false, imageInput = imageInput, requestIssue = requestIssue,
                    speaking = false, onStopSpeaking = {},
                    onNewChat = if (!gatewayFeatures) viewModel::newConversation else null,
                    onOpenChats = if (!gatewayFeatures) ({ chatsOpen = true }) else null,
                    modelMenu = if (gatewayFeatures) null else ({
                        val activeModel by viewModel.activeModel.collectAsStateWithLifecycle()
                        val groqModel by viewModel.groqModel.collectAsStateWithLifecycle()
                        val localStates by AppContainer.localModels.states.collectAsStateWithLifecycle()
                        app.dotshermes.ui.chat.ModelMenu(
                            active = activeModel, groqModel = groqModel, localStates = localStates,
                            onPickGroq = { viewModel.switchModel(app.dotshermes.data.direct.ApiProvider.Groq, it) },
                            onPickLocal = { viewModel.switchModel(app.dotshermes.data.direct.ApiProvider.Local, it) },
                            onDownload = AppContainer.localModels::download,
                            onCancelDownload = AppContainer.localModels::cancel,
                            onAddGroqKey = viewModel::addAgent,
                        )
                    }),
                    onRetryRequest = viewModel::retryRequest, onStopRequest = viewModel::stopTurn,
                    connection = connection as? ConnectionState.Connected,
                    appearance = look.appearance,
                    onSend = viewModel::send,
                    scrollTarget = viewModel.scrollTarget.collectAsStateWithLifecycle().value, onScrolled = viewModel::scrolled,
                    onSendReply = if (!gatewayFeatures) viewModel::sendReply else null,
                    onEdit = if (!gatewayFeatures) viewModel::editMessage else null,
                    agentCall = run {
                        val calls by app.dotshermes.data.direct.AgentCalls.active.collectAsStateWithLifecycle()
                        val dots by AppContainer.agents.repo(activeAgent ?: "").dots.collectAsStateWithLifecycle()
                        // Seulement dans le fil concerné : celui qui appelle, ou celui du sous-dot appelé.
                        calls.firstOrNull { c -> (c.from == activeAgent && (activeChat == c.chat || c.to == "dot:$activeChat")) || c.to == activeAgent }?.let { c ->
                            val peerId = if (c.from == activeAgent) c.to else c.from
                            val dot = peerId.removePrefix("dot:").takeIf { peerId.startsWith("dot:") }?.let { id -> dots.firstOrNull { it.id == id } }
                            if (dot != null) return@let app.dotshermes.ui.chat.AgentCallUi(dot.name,
                                dot.appearance(appearance), c.mode, outgoing = c.from == activeAgent, connected = c.connected)
                            val look = AppContainer.agents.appearance(peerId).state.collectAsStateWithLifecycle().value
                            val link = AppContainer.agents.store.get(peerId)
                            val model = link?.model.orEmpty().let { m -> app.dotshermes.data.direct.LocalModels.byId(m)?.name ?: m.substringAfterLast('/') }
                            app.dotshermes.ui.chat.AgentCallUi(model.ifBlank { look.displayName }, look, c.mode, outgoing = c.from == activeAgent, connected = c.connected)
                        }
                    },
                    onReact = viewModel::toggleReaction,
                    onApproval = viewModel::answerApproval,
                    onClarify = viewModel::answerClarify,
                    onOpenAgent = { agentSheetOpen = true },
                    onOpenActivity = { activityOpen = true },
                    onOpenScreen = { screenOpen = true },
                    screen = screen,
                    browsing = browsing,
                    onLoadOlder = viewModel::loadOlder,
                    otherUnread = unread.filterKeys { it != activeChat }.values.sum(),
                    onOpenAgents = if (agentList.size > 1) ({ agentMenuOpen = true }) else null,
                    otherAgentsUnread = otherAgentsUnread,
                    heroDots = heroDots(appearance, dots),
                    activeChat = activeChat,
                    onSwitchDot = viewModel::selectChat,
                    onTakeover = {
                        screenTakeover = true
                        screenOpen = true
                    },
                    onSendFile = viewModel::sendAttachment,
                    onSetRoom = viewModel::setRoom,
                    attachments = AttachmentControls(load = viewModel::attachmentFile, open = viewModel::openAttachment),
                    voice = VoiceControls(
                        recording = recording,
                        playback = playback,
                        onStartRecording = viewModel::startRecording,
                        onSendRecording = viewModel::sendRecording,
                        onCancelRecording = viewModel::cancelRecording,
                        onToggle = viewModel::toggleVoice,
                        onSeek = viewModel::seekVoice,
                    ),
                )
                }
            } else if (shown == RootScreen.Launch) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(app.dotshermes.ui.theme.Ink.Surface))
            } else {
                val editor = connectionEditor
                key(editor.key) {
                app.dotshermes.ui.connect.AccountConnectScreen(
                    initialProvider = editor.provider, initialModel = editor.model,
                    onRemove = if (addingAgent) null else editor.accountId?.let { id -> ({ deletingAccount = id }) },
                    editing = !addingAgent && editor.accountId != null,
                    onCancelEdit = viewModel::cancelEditDirect,
                    recoveryFailure = requestIssue?.failure?.takeIf { it in setOf(
                        app.dotshermes.data.direct.ApiFailure.MissingKey,
                        app.dotshermes.data.direct.ApiFailure.InvalidKey,
                        app.dotshermes.data.direct.ApiFailure.Storage,
                    ) },
                    onModels = viewModel::availableModels,
                    onDirect = { provider, model, key -> viewModel.connectApi(editor.key, provider, model, key) },
                    connection = connection,
                    appearance = appearance,
                    adding = addingAgent,
                    onCancelAdd = viewModel::cancelAddAgent,
                    otherAgents = agentList.filter { it.id != activeAgent && it.connection is ConnectionState.Connected },
                    onSelectAgent = viewModel::selectAgent,
                )
                }
            }
        }
            }
        }


        deletingAccount?.let { id ->
            val server = false
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deletingAccount = null },
                title = { androidx.compose.material3.Text(stringResource(if (server) R.string.server_delete_title else R.string.api_delete_account)) },
                text = { androidx.compose.material3.Text(stringResource(if (server) R.string.server_delete_warning else R.string.api_delete_warning)) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { deletingAccount = null; viewModel.removeAccount(id) }) {
                    androidx.compose.material3.Text(stringResource(if (server) R.string.server_delete_confirm else R.string.api_delete_account),
                        color = app.dotshermes.ui.theme.Ink.Danger)
                } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { deletingAccount = null }) {
                    androidx.compose.material3.Text(stringResource(android.R.string.cancel))
                } },
            )
        }
        if (clearingChat) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { clearingChat = false },
                title = { androidx.compose.material3.Text(stringResource(R.string.chat_clear_title)) },
                text = { androidx.compose.material3.Text(stringResource(R.string.chat_clear_warning)) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = {
                    clearingChat = false; agentSheetOpen = false; viewModel.clearConversation()
                }) { androidx.compose.material3.Text(stringResource(R.string.chat_clear_confirm)) } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { clearingChat = false }) {
                    androidx.compose.material3.Text(stringResource(android.R.string.cancel))
                } },
            )
        }
        accountFailure?.let { failure ->
            androidx.compose.material3.AlertDialog(onDismissRequest = viewModel::clearAccountFailure,
                text = { androidx.compose.material3.Text(stringResource(app.dotshermes.ui.connect.apiFailureMessage(failure))) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = viewModel::clearAccountFailure) { androidx.compose.material3.Text("OK") } })
        }
        if (chatsOpen) {
            app.dotshermes.ui.chat.ConversationsSheet(
                conversations = conversations,
                onNew = viewModel::newConversation,
                onOpen = viewModel::openConversation,
                onDelete = viewModel::deleteConversation,
                onSearch = viewModel::searchMessages,
                onOpenHit = viewModel::openSearchHit,
                onDismiss = { chatsOpen = false },
            )
        }
        if (activityOpen) {
            ActivitySheet(
                agentName = chat.agentName,
                appearance = appearance,
                activity = chat.activity,
                snapshot = snapshot,
                liveTools = liveTools,
                turns = turns,
                screen = screen,
                usage = usage,
                gatewayFeatures = gatewayFeatures, directUsage = directUsage,
                onUsage = viewModel::requestUsage,
                onRefresh = viewModel::refreshActivity,
                onCron = viewModel::cronAction,
                onStop = viewModel::stopTurn,
                onOpenScreen = { screenOpen = true },
                onDismiss = { activityOpen = false },
                onHideTurn = viewModel::hideTurn,
                onClearRecent = viewModel::clearRecent,
            )
        }

        if (screenOpen) {
            val handBackAnswer = stringResource(R.string.takeover_done_answer)
            val handBackMessage = stringResource(R.string.takeover_done_message)
            ScreenViewer(
                screen, chat.agentName, appearance,
                onWatch = viewModel::watchScreen,
                onInput = viewModel::screenInput,
                onHandBack = { viewModel.handBackScreen(handBackAnswer, handBackMessage) },
                startInTakeover = screenTakeover,
                onClose = {
                    screenOpen = false
                    screenTakeover = false
                },
            )
        }

        if (agentSheetOpen) {
            DotsSheet(
                onDismiss = {
                    agentSheetOpen = false
                    creatingDot = false
                    editPreview = null
                },
                strip = {
                    // Sous-dots (expérimental) : aussi pour les comptes Groq et le modèle du téléphone.
                    DotsCarousel(
                        main = appearance, dots = dots, activeChat = activeChat, creating = creatingDot,
                        preview = editPreview.takeIf { creatingDot || look.subDot != null },
                        activities = chatActivity, unread = unread,
                        showSubDots = true,
                        onSelect = { chat ->
                            creatingDot = false
                            editPreview = null
                            viewModel.selectChat(chat)
                        },
                        onCreate = {
                            editPreview = null
                            creatingDot = true
                        },
                    )
                },
            ) {
                val sub = look.subDot
                when {
                    // La clé repart de zéro à chaque dot : le formulaire ne garde pas la saisie d'un autre.
                    creatingDot -> key("new-dot") {
                        DotEditor(existing = null, theme = appearance, onSave = { draft ->
                            viewModel.createDot(draft)
                            creatingDot = false
                            editPreview = null
                        }, onDelete = null, onPreview = { editPreview = it },
                            modelChoices = remember(activeAgent) { activeAgent?.let { AppContainer.agents.modelChoices(it) }.orEmpty() },
                            takenNames = activeAgent?.let { AppContainer.agents.repo(it).dots.value.map { d -> d.name } }.orEmpty())
                    }
                    sub != null -> key(sub.id) {
                        DotEditor(existing = sub, theme = appearance, onSave = { draft -> viewModel.updateDot(sub.id, draft) },
                            onDelete = { viewModel.deleteDot(sub.id) }, onPreview = { editPreview = it },
                            modelChoices = remember(activeAgent) { activeAgent?.let { AppContainer.agents.modelChoices(it) }.orEmpty() })
                    }
                    else -> AgentSettings(
                        gatewayFeatures = gatewayFeatures,
                        onEditDirect = { agentSheetOpen = false; viewModel.editDirectAccount() },
                        onClearConversation = if (!gatewayFeatures) ({ clearingChat = true }) else null,
                        onOpenChats = if (!gatewayFeatures) ({ agentSheetOpen = false; chatsOpen = true }) else null,
                        companion = app.dotshermes.ui.agent.CompanionControls(
                            state = companion,
                            onPersonality = { viewModel.setPersonality(it) },
                        ),
                        profile = profile,
                        connection = connection,
                        appearance = appearance,
                        onName = viewModel::setAgentName,
                        onSpecies = viewModel::setSpecies,
                        onTheme = viewModel::setTheme,
                        onPalette = viewModel::setPalette,
                        onAccessory = viewModel::setAccessory,
                        appVersion = updater.currentVersion,
                        updateStatus = updateStatusText(updateState, updater.enabled),
                        onUpdateRow = {
                            if (updateState is UpdateState.Available || updateState is UpdateState.Failed) updateDialog = true
                            else scope.launch { if (updater.check(force = true) != null) updateDialog = true }
                        },
                        onDisconnect = {
                            agentSheetOpen = false
                            // Serveur Hermes ou compte direct : toujours une confirmation, puis tout est effacé.
                            deletingAccount = activeAgent
                        },
                        onRoutineTemplate = { text -> agentSheetOpen = false; viewModel.send(text) },
                        onAddAgent = {
                            agentSheetOpen = false
                            viewModel.addAgent()
                        },
                        multipleAgents = agentList.size > 1,
                    )
                }
            }
        }
        if (agentMenuOpen) {
            AgentMenu(
                agents = agentList, activeId = activeAgent,
                onSelect = { id ->
                    agentMenuOpen = false
                    viewModel.selectAgent(id)
                },
                onAdd = {
                    agentMenuOpen = false
                    viewModel.addAgent()
                },
                onDismiss = { agentMenuOpen = false },
            )
        }
        if (updateDialog) {
            UpdateDialog(
                state = updateState,
                currentVersion = updater.currentVersion,
                onUpdate = { scope.launch { updater.install(updateRelease(updateState) ?: return@launch) } },
                onLater = {
                    updateRelease(updateState)?.let(updater::dismiss)
                    updateDialog = false
                },
                onOpenSettings = { context.startActivity(updater.permissionSettingsIntent()) },
                onClose = { updateDialog = false },
            )
        }
    }
}

/** Ce que montre la racine : la conversation, l'attente d'ouverture d'un compte direct, ou la connexion. */
private enum class RootScreen { Chat, Launch, Connect }

/** Icônes de la barre d'état et de navigation : sombres sur fond clair, claires sur fond sombre. */
@Composable
private fun SystemBarsFor(dark: Boolean) {
    val activity = androidx.activity.compose.LocalActivity.current as? ComponentActivity ?: return
    LaunchedEffect(dark) {
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        // Le fond de la fenêtre suit le thème choisi dans l'app (sombre même si le téléphone est en clair) :
        // plus d'éclair blanc entre deux écrans ou à l'ouverture d'une feuille.
        activity.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (dark) 0xFF0E0E11.toInt() else AndroidColor.WHITE))
    }
}

private fun updateRelease(state: UpdateState): Release? = when (state) {
    is UpdateState.Available -> state.release
    is UpdateState.NeedsPermission -> state.release
    is UpdateState.Failed -> state.release
    else -> null
}
