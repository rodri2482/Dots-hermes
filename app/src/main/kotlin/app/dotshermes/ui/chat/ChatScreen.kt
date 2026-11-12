package app.dotshermes.ui.chat

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import app.dotshermes.ui.dot.mascotElement
import app.dotshermes.ui.dot.staggerIn
import app.dotshermes.ui.theme.tabular
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.ui.ChatUiState
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.components.CircleIconButton
import app.dotshermes.ui.components.rememberDeviceOnline
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import app.dotshermes.voice.Playback
import app.dotshermes.data.model.MAIN_CHAT
import app.dotshermes.ui.dots.HeroDot
import app.dotshermes.ui.dots.HeroDots
import androidx.compose.material3.CircularProgressIndicator
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import app.dotshermes.data.model.ScreenState
import app.dotshermes.data.model.ToolKind
import app.dotshermes.voice.RecordingState
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import app.dotshermes.R
import app.dotshermes.ui.dayLabel
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Forum
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.SubDot
import app.dotshermes.ui.dot.DotSpecies
import app.dotshermes.ui.theme.DotPalettes

fun AgentActivity.toMood(): DotMood = when (this) {
    AgentActivity.Idle -> DotMood.Idle
    AgentActivity.Thinking, is AgentActivity.Working -> DotMood.Thinking
    AgentActivity.Typing -> DotMood.Talking
    AgentActivity.Reacting -> DotMood.Happy
}

@Composable
private fun AgentActivity.statusLabel(): String = when (this) {
    AgentActivity.Idle, AgentActivity.Reacting -> stringResource(R.string.status_online)
    AgentActivity.Thinking -> stringResource(R.string.status_thinking)
    AgentActivity.Typing -> stringResource(R.string.status_typing)
    is AgentActivity.Working -> label
}

/** Micro et lecteur : état et actions, regroupés pour ne pas alourdir la signature de l'écran. */
class VoiceControls(
    val recording: RecordingState?,
    val playback: Playback,
    val onStartRecording: () -> Boolean,
    val onSendRecording: () -> Unit,
    val onCancelRecording: () -> Unit,
    val onToggle: (messageId: String) -> Unit,
    val onSeek: (messageId: String, fraction: Float) -> Unit,
)

/** La conversation unique avec l'agent. */
@Composable
fun ChatScreen(
    state: ChatUiState,
    connection: ConnectionState.Connected?,
    appearance: DotAppearance,
    onSend: (String) -> Unit,
    /** Message trouvé par la recherche : le fil défile jusqu'à lui, une fois. */
    scrollTarget: String? = null,
    onScrolled: () -> Unit = {},
    /** Réponse à un message précis (glisser une bulle vers la gauche) ; null : geste désactivé. */
    onSendReply: ((text: String, quote: String) -> Unit)? = null,
    onReact: (messageId: String, emoji: String) -> Unit,
    onApproval: (messageId: String, approved: Boolean) -> Unit,
    onClarify: (messageId: String, response: String) -> Unit,
    onOpenAgent: () -> Unit,
    voice: VoiceControls,
    onOpenActivity: () -> Unit = {},
    onOpenScreen: () -> Unit = {},
    screen: ScreenState = ScreenState(),
    browsing: Boolean = false,
    onLoadOlder: () -> Unit = {},
    onTakeover: () -> Unit = {},
    /** Messages non lus dans les autres dots : pastille sur le bouton qui ouvre la fiche des dots. */
    otherUnread: Int = 0,
    /** Tous les dots dans l'ordre : le suivant se devine derrière la mascotte, un glissement y passe. */
    heroDots: List<HeroDot> = emptyList(),
    activeChat: String = MAIN_CHAT,
    onSwitchDot: (String) -> Unit = {},
    onSendFile: (uri: Uri, caption: String, onTooBig: () -> Unit) -> Unit = { _, _, _ -> },
    attachments: AttachmentControls? = null,
    gatewayFeatures: Boolean = true,
    /** Micro affiché : Hermes, ou un compte Groq (vocal transcrit en texte). */
    voiceInput: Boolean = gatewayFeatures,
    /** Bouton « Photo » : Hermes, ou un compte Groq (modèle de vision). */
    imageInput: Boolean = gatewayFeatures,
    /** Une réponse est lue à voix haute : le bouton Arrêter la coupe. */
    speaking: Boolean = false,
    onStopSpeaking: () -> Unit = {},
    /** Voix Fish Audio prête : « Écouter » sous la dernière réponse ; null = pas de lien. */
    onListen: ((String) -> Unit)? = null,
    requestIssue: app.dotshermes.data.direct.DirectRequestIssue? = null,
    onRetryRequest: () -> Unit = {},
    onStopRequest: () -> Unit = {},
    /** Salon de groupe : invite ces sous-dots dans la conversation affichée (liste vide : salon fermé). */
    onSetRoom: (List<String>) -> Unit = {},
    /** Compte direct : nouvelle discussion, et la liste des discussions (null : pastilles masquées). */
    onNewChat: (() -> Unit)? = null,
    onOpenChats: (() -> Unit)? = null,
    /** Compte direct : le sélecteur de modèle à la place de la pastille « En ligne » ; null = pastille. */
    modelMenu: (@Composable () -> Unit)? = null,
    /** Plusieurs agents Hermes : le nom de l'en-tête ouvre leur menu ; null avec un seul agent (en-tête d'avant). */
    onOpenAgents: (() -> Unit)? = null,
    /** Messages non lus chez les autres agents : pastille à côté du nom. */
    otherAgentsUnread: Int = 0,
) {
    val palette = LocalDotPalette.current
    val context = LocalContext.current
    var draft by rememberSaveable { mutableStateOf("") }
    var suggestionsOpen by rememberSaveable { mutableStateOf(false) }
    var roomSheetOpen by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(scrollTarget, state.rows) {
        val id = scrollTarget ?: return@LaunchedEffect
        val at = state.rows.indexOfFirst { (it as? ChatRow.Bubble)?.message?.id == id || (it as? ChatRow.Bubble)?.message?.remoteId == id }
        if (at >= 0) { listState.animateScrollToItem(at); onScrolled() }
    }
    val streamingReply = state.activity == AgentActivity.Typing
    val stopVisible = speaking || (!gatewayFeatures && state.activity != AgentActivity.Idle)
    // Place de la pastille « Arrêter » sous la dernière bulle, gagnée en douceur plutôt que d'un coup.
    val listBottom by animateDpAsState(if (stopVisible) 64.dp else 12.dp, label = "stop-room")
    var cooldownRemaining by remember(requestIssue) { androidx.compose.runtime.mutableLongStateOf(
        app.dotshermes.data.direct.cooldownSeconds(requestIssue?.retryAtMs ?: 0, System.currentTimeMillis())) }
    LaunchedEffect(requestIssue) {
        do {
            cooldownRemaining = app.dotshermes.data.direct.cooldownSeconds(requestIssue?.retryAtMs ?: 0, System.currentTimeMillis())
            if (cooldownRemaining > 0) kotlinx.coroutines.delay(250)
        } while (cooldownRemaining > 0)
    }

    /** Qui parle dans un salon : un sous-dot invité, ou le dot de la conversation ; un sous-dot fermé depuis garde son nom. */
    fun lookFor(message: ChatMessage): DotAppearance {
        val id = message.speaker ?: return appearance
        return state.speakerLooks[id] ?: DotAppearance(name = message.speakerName ?: "Dot", palette = DotPalettes.Sky, species = DotSpecies.Nuage)
    }

    // Liste inversée : l'index 0 est le plus récent. On ne ramène en bas que si l'utilisateur y est déjà.
    val newestKey = state.rows.firstOrNull()?.key
    LaunchedEffect(newestKey) {
        if (listState.firstVisibleItemIndex <= 3) listState.animateScrollToItem(0)
    }

    // Seul le sur-message le plus récent porte l'aperçu de l'écran : c'est lui qui correspond au navigateur actuel.
    val newestToolsKey = state.rows.firstOrNull { it is ChatRow.Tools }?.key

    // Pièce jointe choisie mais pas encore envoyée : on peut écrire un texte dessous, puis Envoyer.
    var pendingAttachment by remember { mutableStateOf<Uri?>(null) }
    // Message auquel on répond (glissé vers la gauche) : qui l'a écrit et son texte cité.
    var replyTo by remember { mutableStateOf<Pair<String, String>?>(null) }
    val focusComposer = remember { androidx.compose.ui.focus.FocusRequester() }
    // Partage ou widget : le texte dans le champ, la photo en pièce jointe (si le modèle voit), le clavier ouvert.
    val incoming by app.dotshermes.IncomingContent.pending.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(incoming) {
        val got = incoming ?: return@LaunchedEffect
        app.dotshermes.IncomingContent.pending.value = null
        if (got.text.isNotBlank()) draft = got.text
        got.image?.let { if (gatewayFeatures || imageInput) pendingAttachment = it }
        if (got.focus) { kotlinx.coroutines.delay(300); runCatching { focusComposer.requestFocus() } }
    }
    val youLabel = stringResource(R.string.reply_you)

    fun attach(uri: Uri) {
        if (!gatewayFeatures && !imageInput) return
        pendingAttachment = uri
        suggestionsOpen = false
    }

    fun submit(text: String) {
        if (!gatewayFeatures && (state.activity != AgentActivity.Idle || cooldownRemaining > 0)) return
        pendingAttachment?.let { uri ->
            onSendFile(uri, text.trim()) { Toast.makeText(context, R.string.chat_file_too_big, Toast.LENGTH_SHORT).show() }
            pendingAttachment = null
            draft = ""
            suggestionsOpen = false
            return
        }
        val reply = replyTo
        if (reply != null && onSendReply != null && text.isNotBlank()) onSendReply(text, reply.second) else onSend(text)
        replyTo = null
        draft = ""
        suggestionsOpen = false
    }

    // Hauteur mesurée de l'en-tête flottant : le fil commence juste dessous, puis défile sous son dégradé.
    var headerHeightPx by remember { mutableIntStateOf(0) }
    val headerHeight = with(LocalDensity.current) { headerHeightPx.toDp() }

    Box(Modifier.fillMaxSize().background(Ink.Surface)
        .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.displayCutout.only(androidx.compose.foundation.layout.WindowInsetsSides.Horizontal))) {
        Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(palette.light, Ink.Surface))))
        Column(Modifier.fillMaxSize().imePadding()) {
          Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = headerHeight, bottom = listBottom),
            ) {
                items(state.rows, key = { it.key }, contentType = { it::class }) { row ->
                    // Pendant qu'une réponse grandit, pas d'animation de position : les bulles au-dessus
                    // suivaient chaque nouveau mot avec un ressort, d'où un texte qui tremblait.
                    Box(Modifier.animateItem(fadeInSpec = tween(220), fadeOutSpec = tween(150),
                        placementSpec = if (streamingReply) null else spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)).fillMaxWidth()) {
                        when (row) {
                            is ChatRow.Day -> DaySeparator(dayLabel(row))
                            is ChatRow.Bubble -> SwipeToReply(
                                enabled = onSendReply != null && !row.message.streaming && quoteOf(row.message) != null,
                                onReply = {
                                    quoteOf(row.message)?.let { q ->
                                        replyTo = (if (row.message.sender == app.dotshermes.data.model.Sender.User) youLabel else state.agentName) to q
                                        runCatching { focusComposer.requestFocus() }
                                    }
                                },
                            ) { MessageRow(
                                row = row,
                                reactionsEnabled = true,
                                speakerLook = if (row.showSpeaker) lookFor(row.message) else null,
                                onReact = { emoji -> onReact(row.message.id, emoji) },
                                onApproval = { approved -> onApproval(row.message.id, approved) },
                                onClarify = { response -> onClarify(row.message.id, response) },
                                playback = voice.playback,
                                onToggleVoice = { voice.onToggle(row.message.id) },
                                onSeekVoice = { fraction -> voice.onSeek(row.message.id, fraction) },
                                attachments = attachments,
                                onTakeover = onTakeover,
                                onListen = onListen?.let { listen -> (row.message.content as? app.dotshermes.data.model.MessageContent.Text)?.let { { listen(it.text) } } },
                            ) }
                            is ChatRow.Tools -> ToolTrail(
                                row,
                                screen = screen.takeIf { row.key == newestToolsKey && row.calls.any { it.kind == ToolKind.Web } },
                                onOpenScreen = onOpenScreen,
                            )
                            is ChatRow.Typing -> TypingBubble(speaker = row.speaker?.let { id -> state.speakerLooks[id] })
                            is ChatRow.Older -> OlderMessages(row.hasMore, state.messageCount, onLoadOlder)
                        }
                    }
                }
            }
            ChatHeader(
                state.agentName, state.activity, connection, appearance, onOpenAgent,
                Modifier.onSizeChanged { headerHeightPx = it.height },
                onOpenActivity = onOpenActivity,
                gatewayFeatures = gatewayFeatures,
                otherUnread = otherUnread,
                heroDots = heroDots,
                activeChat = activeChat,
                onSwitchDot = onSwitchDot,
                speakingName = state.speakingName,
                onOpenAgents = onOpenAgents,
                otherAgentsUnread = otherAgentsUnread,
                modelMenu = modelMenu,
            )
            // « Arrêter » flotte au-dessus du champ, comme la pastille de navigation : le fil ne saute pas.
            androidx.compose.animation.AnimatedVisibility(stopVisible, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.85f), exit = fadeOut() + scaleOut(targetScale = 0.85f)) {
                Row(
                    Modifier.clip(CircleShape).background(Ink.Raised).border(0.5.dp, Ink.Separator, CircleShape)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                            if (speaking && state.activity == AgentActivity.Idle) onStopSpeaking() else onStopRequest()
                        }
                        .heightIn(min = 40.dp).padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Stop, null, tint = palette.deep, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.api_stop), style = MaterialTheme.typography.labelLarge, color = Ink.Primary)
                }
            }
            BrowsingPill(
                visible = browsing && screen.hasFrame && !stopVisible,
                agentName = state.agentName,
                onClick = onOpenScreen,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
          }
            if (!gatewayFeatures) {
                requestIssue?.takeUnless { it.cooldownOnly && cooldownRemaining == 0L }?.let { issue ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(app.dotshermes.ui.connect.apiFailureMessage(issue.failure)),
                            color = Ink.Secondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (issue.canRetry) androidx.compose.material3.TextButton(onClick = onRetryRequest, enabled = cooldownRemaining == 0L) {
                            Text(if (cooldownRemaining > 0) stringResource(R.string.api_retry_wait, cooldownRemaining) else stringResource(R.string.api_retry),
                                style = MaterialTheme.typography.labelLarge.tabular())
                        } else if (cooldownRemaining > 0) {
                            Text(stringResource(R.string.api_retry_wait, cooldownRemaining),
                                style = MaterialTheme.typography.bodySmall.tabular(), color = Ink.Secondary)
                        }
                    }
                }
            }
            Composer(
                mediaEnabled = gatewayFeatures,
                voiceEnabled = voiceInput,
                imageEnabled = imageInput,
                // Compte direct : pendant la réponse, le bouton d'envoi devient « Arrêter ».
                busy = speaking || (!gatewayFeatures && state.activity != AgentActivity.Idle),
                onStop = { if (speaking && state.activity == AgentActivity.Idle) onStopSpeaking() else onStopRequest() },
                sendingEnabled = gatewayFeatures || (state.activity == AgentActivity.Idle && cooldownRemaining == 0L),
                draft = draft,
                onDraftChange = { draft = it },
                agentName = state.agentName,
                suggestionsOpen = suggestionsOpen,
                onToggleSuggestions = { suggestionsOpen = !suggestionsOpen },
                onSend = ::submit,
                voice = voice,
                onAttach = ::attach,
                attachment = pendingAttachment,
                onRemoveAttachment = { pendingAttachment = null },
                replyTo = replyTo,
                onCancelReply = { replyTo = null },
                focusRequester = focusComposer,
                room = state.room,
                canOpenRoom = state.roomCandidates.isNotEmpty(),
                onNewChat = onNewChat?.let { action -> { suggestionsOpen = false; action() } },
                onOpenChats = onOpenChats?.let { action -> { suggestionsOpen = false; action() } },
                onOpenRoom = {
                    suggestionsOpen = false
                    roomSheetOpen = true
                },
                roomBar = {
                    RoomBar(state.room, state.speakerLooks, state.agentName, onOpen = { roomSheetOpen = true }, onClose = { onSetRoom(emptyList()) })
                },
            )
        }
    }
    if (roomSheetOpen) {
        RoomSheet(
            candidates = state.roomCandidates,
            invited = state.room,
            looks = state.speakerLooks,
            hostName = state.agentName,
            onApply = { ids ->
                onSetRoom(ids)
                roomSheetOpen = false
            },
            onDismiss = { roomSheetOpen = false },
        )
    }
}

/** En-tête Dots : le personnage au centre, vivant, avec son nom et ce qu'il est en train de faire. */
@Composable
private fun ChatHeader(
    name: String,
    activity: AgentActivity,
    connection: ConnectionState.Connected?,
    appearance: DotAppearance,
    onOpenAgent: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenActivity: () -> Unit = {},
    otherUnread: Int = 0,
    heroDots: List<HeroDot> = emptyList(),
    activeChat: String = MAIN_CHAT,
    onSwitchDot: (String) -> Unit = {},
    speakingName: String? = null,
    onOpenAgents: (() -> Unit)? = null,
    otherAgentsUnread: Int = 0,
    modelMenu: (@Composable () -> Unit)? = null,
    gatewayFeatures: Boolean = true,
) {
    val palette = LocalDotPalette.current
    Column(
        modifier
            .fillMaxWidth()
            // Pas de fond : l'en-tête flotte, les messages défilent dessous (seules les pastilles ont leur fond).
            .statusBarsPadding()
            .padding(top = 4.dp, bottom = 18.dp),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // À gauche, à la place de la pastille « En ligne » : le modèle (un toucher ouvre la liste au milieu).
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { modelMenu?.invoke() ?: ConnectionPill(connection, gatewayFeatures) }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onOpenAgent)
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            if (heroDots.size > 1) {
                HeroDots(heroDots, activeChat, activity.toMood(), 68.dp, onSwitch = onSwitchDot)
            } else {
                ClayDot(palette, Modifier.mascotElement().size(68.dp), mood = activity.toMood(), accessory = appearance.accessory, species = appearance.species)
            }
            if (onOpenAgents == null) {
                Text(name, style = MaterialTheme.typography.titleSmall, color = Ink.Primary)
            } else {
                // Changer d'agent (un autre profil Hermes) : par le nom, à part de la mascotte et du « … »,
                // qui restent ceux des dots de l'agent affiché.
                val switchLabel = stringResource(R.string.cd_switch_agent)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClickLabel = switchLabel, onClick = onOpenAgents)
                        .padding(start = 8.dp, end = 2.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.titleSmall, color = Ink.Primary)
                    Icon(Icons.Rounded.ExpandMore, switchLabel, tint = Ink.Secondary, modifier = Modifier.size(18.dp))
                    if (otherAgentsUnread > 0) {
                        Box(Modifier.size(8.dp).background(Ink.Danger, CircleShape))
                    }
                }
            }
            AnimatedContent(
                // En plein débat, l'en-tête annonce qui a la parole ; le dot de la conversation reste au centre.
                targetState = speakingName?.let { stringResource(R.string.room_speaking, it) } ?: if (!gatewayFeatures && (activity == AgentActivity.Idle || activity == AgentActivity.Reacting)) "" else activity.statusLabel(),
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "status",
            ) { label ->
                app.dotshermes.ui.components.ToolStatusLine(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (activity == AgentActivity.Idle || activity == AgentActivity.Reacting) Ink.Secondary else palette.deep,
                )
            }
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            CircleIconButton(Icons.Rounded.Insights, stringResource(R.string.activity_title), onOpenActivity, container = Ink.Raised.copy(alpha = 0.85f))
            Box {
                CircleIconButton(Icons.Rounded.MoreHoriz, stringResource(R.string.cd_my_dots), onOpenAgent, container = Ink.Raised.copy(alpha = 0.85f))
                if (otherUnread > 0) {
                    Box(Modifier.align(Alignment.TopEnd).size(10.dp).background(Ink.Danger, CircleShape).border(1.5.dp, Ink.Surface, CircleShape))
                }
            }
        }
    }
    }
}

@Composable
private fun ConnectionPill(connection: ConnectionState.Connected?, gatewayFeatures: Boolean = true) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_200), RepeatMode.Reverse),
        label = "pulse-alpha",
    )
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Ink.Raised.copy(alpha = 0.85f))
            .border(0.5.dp, Ink.Separator, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Vert : en ligne ; orange : le gateway se reconnecte ; rouge : le téléphone n'a plus Internet.
        val deviceOnline = rememberDeviceOnline()
        val (dot, label) = when {
            !deviceOnline || connection == null -> Ink.Danger to stringResource(R.string.conn_offline)
            !connection.online -> Ink.Warning to (connection.reconnectReason ?: stringResource(R.string.conn_reconnecting))
            !gatewayFeatures -> Ink.Success to stringResource(R.string.conn_online)
            else -> Ink.Success to stringResource(R.string.latency_ms, connection.latencyMs)
        }
        Box(Modifier.size(7.dp).graphicsLayer { alpha = pulse }.background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ink.Primary)
    }
}

@Composable
private fun DaySeparator(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Ink.Secondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun Composer(
    draft: String,
    mediaEnabled: Boolean = true,
    voiceEnabled: Boolean = mediaEnabled,
    imageEnabled: Boolean = mediaEnabled,
    sendingEnabled: Boolean = true,
    busy: Boolean = false,
    onStop: () -> Unit = {},
    onDraftChange: (String) -> Unit,
    agentName: String,
    suggestionsOpen: Boolean,
    onToggleSuggestions: () -> Unit,
    onSend: (String) -> Unit,
    voice: VoiceControls,
    onAttach: (Uri) -> Unit,
    attachment: Uri? = null,
    onRemoveAttachment: () -> Unit = {},
    replyTo: Pair<String, String>? = null,
    onCancelReply: () -> Unit = {},
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    room: List<SubDot> = emptyList(),
    canOpenRoom: Boolean = false,
    onOpenRoom: () -> Unit = {},
    onNewChat: (() -> Unit)? = null,
    onOpenChats: (() -> Unit)? = null,
    roomBar: @Composable () -> Unit = {},
) {
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(onAttach) }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onAttach) }
    val palette = LocalDotPalette.current
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.onStartRecording()
    }
    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.onStartRecording()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val plusRotation by animateFloatAsState(if (suggestionsOpen) 45f else 0f, label = "plus-rotation")
    // Les amorces Hermes (« Qu'as-tu fait cette nuit ? ») n'ont pas de sens pour un compte API direct.
    val suggestions = stringArrayResource(if (mediaEnabled) R.array.chat_suggestions else R.array.chat_suggestions_direct)

    Column(Modifier.fillMaxWidth().background(Ink.Surface).navigationBarsPadding()) {
        AnimatedVisibility(
            visible = suggestionsOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                if (mediaEnabled || imageEnabled) item {
                    AttachChip(Modifier.staggerIn(0), Icons.Rounded.PhotoLibrary, stringResource(R.string.attach_photo)) {
                        pickMedia.launch(PickVisualMediaRequest(if (mediaEnabled) ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            else ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                if (mediaEnabled) item { AttachChip(Modifier.staggerIn(1), Icons.Rounded.AttachFile, stringResource(R.string.attach_file)) { pickDocument.launch(arrayOf("*/*")) } }
                if (canOpenRoom) item { AttachChip(Modifier.staggerIn(2), Icons.Rounded.Groups, stringResource(R.string.room_chip), onClick = onOpenRoom) }
                onNewChat?.let { action -> item { AttachChip(Modifier.staggerIn(0), Icons.Rounded.Add, stringResource(R.string.chats_new), onClick = action) } }
                onOpenChats?.let { action -> item { AttachChip(Modifier.staggerIn(1), Icons.Rounded.Forum, stringResource(R.string.chats_title), onClick = action) } }
                itemsIndexed(suggestions) { index, suggestion ->
                    Text(
                        suggestion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.deep,
                        modifier = Modifier
                            .staggerIn(index + 2)
                            .clip(CircleShape)
                            .background(palette.light)
                            // Compte direct : l'amorce se place dans le champ, à compléter ; rien ne part (ni n'est facturé) sans envoi.
                            .clickable { if (mediaEnabled) onSend(suggestion) else { onDraftChange("$suggestion "); onToggleSuggestions() } }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        // Salon ouvert : le bandeau reste visible au-dessus du champ, on sait à qui l'on écrit.
        AnimatedVisibility(visible = room.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            roomBar()
        }
        // Pendant l'enregistrement, la barre de vocal remplace le champ de saisie.
        voice.recording?.let { recording ->
            RecordingBar(recording, onCancel = voice.onCancelRecording, onSend = voice.onSendRecording)
            return@Column
        }
        AnimatedVisibility(replyTo != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            replyTo?.let { (name, quote) -> ReplyBar(name, quote, onCancelReply) }
        }
        AnimatedVisibility(attachment != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            attachment?.let { AttachmentPreview(it, onRemoveAttachment) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            CircleIconButton(
                icon = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.cd_suggestions),
                onClick = onToggleSuggestions,
                size = 40.dp,
                modifier = Modifier.graphicsLayer { rotationZ = plusRotation },
            )
            Spacer(Modifier.width(8.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ink.Fill)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 9.dp)) {
                    if (draft.isEmpty()) {
                        Text(
                            when {
                                attachment != null -> stringResource(R.string.composer_caption)
                                room.isEmpty() -> stringResource(R.string.composer_placeholder, agentName)
                                else -> stringResource(R.string.room_placeholder)
                            },
                            style = MaterialTheme.typography.bodyLarge, color = Ink.Secondary,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Primary),
                        cursorBrush = SolidColor(palette.base),
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
                    )
                }
                // Une seule commande, cible de 44 dp : Arrêter pendant la réponse, Envoyer dès qu'il y a du texte, sinon le micro.
                val action = when {
                    draft.isNotBlank() || attachment != null -> ComposerAction.Send
                    voiceEnabled && sendingEnabled -> ComposerAction.Mic
                    else -> ComposerAction.Idle
                }
                Box(Modifier.padding(bottom = 4.dp).size(32.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = action,
                    transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                    label = "composer-action",
                ) { shown ->
                    when (shown) {
                        // Envoi impossible (délai d'attente du fournisseur) : le bouton s'efface au lieu de rester muet.
                        ComposerAction.Send -> ComposerButton(Icons.Rounded.ArrowUpward, stringResource(R.string.cd_send), filled = true,
                            enabled = sendingEnabled) { onSend(draft) }
                        ComposerAction.Mic -> ComposerButton(Icons.Rounded.Mic, stringResource(R.string.cd_record_voice), filled = false, onClick = ::startRecording)
                        ComposerAction.Idle -> ComposerButton(Icons.Rounded.ArrowUpward, stringResource(R.string.cd_send), filled = true, enabled = false) {}
                    }
                }
                }
            }
        }
    }
}

private enum class ComposerAction { Send, Mic, Idle }

/** Bouton rond de 32 dp dans le champ de saisie, comme dans l'app d'origine (envoi plein, micro nu). */
@Composable
private fun ComposerButton(icon: ImageVector, label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalDotPalette.current
    Box(
        Modifier.size(32.dp).clip(CircleShape).graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .then(if (filled) Modifier.background(palette.base) else Modifier)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (filled) palette.onBase else palette.deep, modifier = Modifier.size(if (filled) 18.dp else 22.dp))
    }
}

/** Enregistrement en cours : corbeille, point rouge et chrono, onde en direct, envoi. */
@Composable
private fun RecordingBar(recording: RecordingState, onCancel: () -> Unit, onSend: () -> Unit) {
    val palette = LocalDotPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(icon = Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.cd_cancel_voice), onClick = onCancel, size = 40.dp)
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(20.dp)).background(Ink.Fill).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecordingDot()
            Spacer(Modifier.width(8.dp))
            Text(formatClock(recording.elapsedMs), style = MaterialTheme.typography.labelLarge.tabular(), color = Ink.Primary)
            Spacer(Modifier.width(12.dp))
            // Les derniers niveaux défilent de droite à gauche, la barre la plus récente au bord.
            Waveform(
                bars = List((48 - recording.levels.size).coerceAtLeast(0)) { 0.06f } + recording.levels,
                progress = 1f,
                played = palette.base,
                pending = palette.base,
                modifier = Modifier.weight(1f).height(24.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(palette.base).clickable(onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.cd_send_voice), tint = palette.onBase, modifier = Modifier.size(20.dp))
        }
    }
}

/** Pièce jointe : pastille au début des suggestions du bouton « + ». */
@Composable
private fun AttachChip(modifier: Modifier, icon: ImageVector, label: String, onClick: () -> Unit) {
    val palette = LocalDotPalette.current
    Row(
        modifier.clip(CircleShape).background(palette.base).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = palette.onBase, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.onBase)
    }
}

/** Pastille flottante tant que l'agent navigue : un tap ouvre son écran en direct. */
@Composable
private fun BrowsingPill(visible: Boolean, agentName: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalDotPalette.current
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
        Row(
            Modifier.clip(CircleShape).background(Ink.Raised).border(0.5.dp, Ink.Separator, CircleShape)
                .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RecordingDot()
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Language, null, tint = palette.deep, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.chat_browsing, agentName), style = MaterialTheme.typography.labelLarge, color = Ink.Primary)
        }
    }
}

/**
 * Tête du fil. Tant qu'il reste de l'historique, sa simple apparition à l'écran demande la page
 * précédente ; la clé sur le nombre de messages relance la demande après chaque page reçue.
 */
@Composable
private fun OlderMessages(hasMore: Boolean, messageCount: Int, onLoadOlder: () -> Unit) {
    val palette = LocalDotPalette.current
    if (hasMore) {
        LaunchedEffect(messageCount) { onLoadOlder() }
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), color = palette.base, strokeWidth = 2.dp)
        }
    } else {
        Text(
            stringResource(R.string.chat_start),
            style = MaterialTheme.typography.labelSmall,
            color = Ink.Tertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        )
    }
}
