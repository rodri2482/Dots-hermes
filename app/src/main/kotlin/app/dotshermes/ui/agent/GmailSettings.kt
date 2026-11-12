package app.dotshermes.ui.agent

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.graphics.graphicsLayer
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dotshermes.R
import app.dotshermes.data.gmail.GmailAccess
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.SectionLabel
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Gmail en lecture seule : brancher (écran d'accord de Google), ou débrancher (accès révoqué).
 * Claydot ne peut ni envoyer, ni supprimer, ni modifier un e-mail : la portée demandée ne le permet pas.
 */
@Composable
fun GmailSettings(gmail: GmailAccess) {
    val connected by gmail.connected.collectAsStateWithLifecycle()
    var problem by remember { mutableStateOf<Int?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Écran de Google préparé à l'avance : le toucher sur « Connecter » l'ouvre sans attente.
    var prepared by remember { mutableStateOf<com.google.android.gms.auth.api.identity.AuthorizationResult?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        working = false
        prepared = null
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        try {
            val token = gmail.resultFrom(result.data).accessToken
            if (token != null) gmail.markConnected(true, token) else problem = R.string.gmail_error
        } catch (_: Exception) { problem = R.string.gmail_error }
    }

    fun failure(e: Exception) {
        // 10 (DEVELOPER_ERROR) : pas de client OAuth Android pour ce paquet et cette signature.
        problem = if (e is com.google.android.gms.common.api.ApiException && e.statusCode == 10) R.string.gmail_error_setup else R.string.gmail_error
    }

    androidx.compose.runtime.LaunchedEffect(connected) {
        if (!connected) try { prepared = gmail.prepare() } catch (e: CancellationException) { throw e } catch (e: Exception) { failure(e) }
    }

    fun open(result: com.google.android.gms.auth.api.identity.AuthorizationResult): Boolean {
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) {
            consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            return true
        }
        result.accessToken?.let { gmail.markConnected(true, it); working = false; return true }
        return false
    }

    fun connect() {
        problem = null
        working = true
        // Prêt d'avance : on ouvre tout de suite ; sinon on demande à Google, avec le cercle de chargement.
        prepared?.let { ready -> if (runCatching { open(ready) }.getOrDefault(false)) return }
        scope.launch {
            try {
                if (!open(gmail.authorize())) problem = R.string.gmail_error
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
            if (problem != null) working = false
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.gmail_section))
        GroupedCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(stringResource(if (connected) R.string.gmail_connected else R.string.gmail_title),
                    style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
                Text(stringResource(R.string.gmail_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
            }
            Hairline()
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = !working) { if (connected) scope.launch { gmail.disconnect() } else connect() }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(if (working) R.string.gmail_opening else if (connected) R.string.gmail_disconnect else R.string.gmail_connect),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (connected) Ink.Danger else LocalDotPalette.current.deep,
                    modifier = Modifier.weight(1f),
                )
                if (working) androidx.compose.material3.CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp),
                    color = LocalDotPalette.current.base, strokeWidth = 2.dp)
            }
        }
        problem?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = Ink.Danger,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp))
            // Client Google manquant : l'empreinte exacte à déclarer, sélectionnable pour la copier.
            if (it == R.string.gmail_error_setup) GmailAccess.signingSha1(context)?.let { sha ->
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text("app.claydot · SHA-1 $sha", style = MaterialTheme.typography.labelSmall, color = Ink.Secondary,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 16.dp))
                }
            }
        }
    }
}

/**
 * Actions sur le téléphone : une ligne par réglage (icône, nom, état en un mot). Un toucher l'ouvre :
 * l'explication et le bouton qui va avec (autoriser, réglages Android…). Une seule ouverte à la fois.
 */
@Composable
fun PhoneSettings(actions: app.dotshermes.data.direct.PhoneActions) {
    val enabled by actions.enabled.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    fun toggle(key: String) { open = if (open == key) null else key }
    // Retour des réglages d'Android : les états (accès aux notifications, batterie, écran…) sont relus.
    var resumed by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { resumed++; onPauseOrDispose { } }
    val switchColors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = LocalDotPalette.current.base,
        checkedThumbColor = androidx.compose.ui.graphics.Color.White)
    fun launch(intent: android.content.Intent) {
        runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.phone_section))
        GroupedCard {
            CollapsedHeader(Icons.Rounded.PhoneAndroid, stringResource(R.string.phone_title),
                stringResource(if (enabled) R.string.setting_on else R.string.setting_off), open == "phone", { toggle("phone") }) {
                androidx.compose.material3.Switch(checked = enabled, onCheckedChange = actions::setEnabled, colors = switchColors)
            }
            SettingDetail(open == "phone") { SettingHint(stringResource(R.string.phone_hint)) }
            if (enabled) {
                fun textingAllowed() = app.dotshermes.data.direct.MessagingTools.PERMISSIONS.all {
                    androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
                var texting by remember { mutableStateOf(textingAllowed()) }
                androidx.compose.runtime.LaunchedEffect(resumed) { texting = textingAllowed() }
                val ask = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { texting = textingAllowed() }
                Hairline(startIndent = 60.dp)
                CollapsedHeader(Icons.Rounded.Sms, stringResource(R.string.texting_title),
                    stringResource(if (texting) R.string.setting_allowed else R.string.setting_not_allowed), open == "texting", { toggle("texting") })
                SettingDetail(open == "texting") {
                    SettingHint(stringResource(if (texting) R.string.texting_on else R.string.texting_hint))
                    if (!texting) SettingAction(stringResource(R.string.texting_allow)) { ask.launch(app.dotshermes.data.direct.MessagingTools.PERMISSIONS) }
                }

                // Notifications WhatsApp : pour faire l'appel d'un groupe et répondre téléphone verrouillé.
                val watching = remember(enabled, resumed) { app.dotshermes.data.direct.ChatLog.accessGranted(context) }
                Hairline(startIndent = 60.dp)
                CollapsedHeader(Icons.Rounded.Notifications, stringResource(R.string.chatwatch_title),
                    stringResource(if (watching) R.string.setting_on else R.string.setting_off), open == "watch", { toggle("watch") })
                SettingDetail(open == "watch") {
                    SettingHint(stringResource(if (watching) R.string.chatwatch_on else R.string.chatwatch_hint))
                    SettingAction(stringResource(R.string.open_android_settings)) {
                        launch(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                }

                if (watching) {
                    // Ne jamais mettre Claydot en veille : l'écoute de WhatsApp et les routines tiennent toute la journée.
                    val awake = remember(enabled, resumed) { app.dotshermes.data.direct.ChatLog.neverSleeps(context) }
                    Hairline(startIndent = 60.dp)
                    CollapsedHeader(Icons.Rounded.BatteryChargingFull, stringResource(R.string.keep_listening_title),
                        stringResource(if (awake) R.string.setting_on else R.string.setting_off), open == "awake", { toggle("awake") })
                    SettingDetail(open == "awake") {
                        SettingHint(stringResource(if (awake) R.string.keep_listening_on else R.string.keep_listening_hint))
                        if (!awake) SettingAction(stringResource(R.string.keep_listening_action)) {
                            launch(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + context.packageName)))
                        }
                    }

                    // Toujours actif (24 h/24) et le guide de la marque du téléphone (dontkillmyapp.com).
                    var alwaysOn by remember { mutableStateOf(app.dotshermes.data.direct.AlwaysOnService.enabled(context)) }
                    Hairline(startIndent = 60.dp)
                    CollapsedHeader(Icons.Rounded.AllInclusive, stringResource(R.string.always_on_title),
                        stringResource(if (alwaysOn) R.string.setting_on else R.string.setting_off), open == "always", { toggle("always") }) {
                        androidx.compose.material3.Switch(checked = alwaysOn, onCheckedChange = {
                            alwaysOn = it; app.dotshermes.data.direct.AlwaysOnService.set(context, it)
                        }, colors = switchColors)
                    }
                    SettingDetail(open == "always") {
                        SettingHint(stringResource(R.string.always_on_hint))
                        SettingAction(stringResource(R.string.always_on_guide, android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() })) {
                            launch(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(app.dotshermes.data.direct.AlwaysOnService.guideUrl())))
                        }
                    }
                }

                // Contrôle de l'écran : seul Android peut l'activer (Réglages > Accessibilité).
                val screenOn by app.dotshermes.screen.ClaydotAccessibilityService.running.collectAsStateWithLifecycle()
                Hairline(startIndent = 60.dp)
                CollapsedHeader(Icons.Rounded.TouchApp, stringResource(R.string.screen_title),
                    stringResource(if (screenOn) R.string.setting_on else R.string.setting_off), open == "screen", { toggle("screen") })
                SettingDetail(open == "screen") {
                    SettingHint(stringResource(if (screenOn) R.string.screen_on_hint else R.string.screen_hint))
                    SettingAction(stringResource(if (screenOn) R.string.screen_turn_off else R.string.screen_turn_on)) {
                        launch(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
            }
        }
    }
}

/** Le contenu d'une ligne ouverte, aligné sous son titre. */
@Composable
private fun SettingDetail(visible: Boolean, content: @Composable () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(visible) {
        Column(Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 12.dp)) { content() }
    }
}

@Composable
private fun SettingHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
}

@Composable
private fun SettingAction(text: String, onClick: () -> Unit) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = LocalDotPalette.current.deep,
        modifier = Modifier.padding(top = 8.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(LocalDotPalette.current.light).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

/** Routines du compte : interrupteur et suppression ; on les crée en le demandant à Claydot. */
@Composable
fun RoutinesSettings(store: app.dotshermes.data.direct.RoutineStore, accountId: String?, onTemplate: (String) -> Unit = {}) {
    val all by store.routines.collectAsStateWithLifecycle()
    val mine = all.filter { it.accountId == accountId }
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        val context = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        GroupedCard {
            CollapsedHeader(androidx.compose.material.icons.Icons.Rounded.Schedule, stringResource(R.string.routines_section),
                if (mine.isEmpty()) stringResource(R.string.routines_none)
                else androidx.compose.ui.res.pluralStringResource(R.plurals.routines_summary, mine.size, mine.size, mine.count { it.enabled }), expanded, onToggle = { expanded = !expanded })
            androidx.compose.animation.AnimatedVisibility(expanded) { Column {
            Hairline()
            // Android 12+ : sans « alarmes et rappels », une routine peut partir quelques minutes en retard.
            if (mine.isNotEmpty() && !app.dotshermes.data.direct.RoutineAlarms.canBeExact(context) && android.os.Build.VERSION.SDK_INT >= 31) {
                Text(stringResource(R.string.routines_exact), style = MaterialTheme.typography.bodyLarge, color = LocalDotPalette.current.deep,
                    modifier = Modifier.fillMaxWidth().clickable {
                        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:" + context.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.padding(horizontal = 16.dp, vertical = 13.dp))
                Hairline()
            }
            if (mine.isEmpty()) {
                Text(stringResource(R.string.routines_empty), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
            }
            // Modèles prêts : un toucher envoie la demande à Claydot, qui demande ce qui manque (groupe, heure…).
            val templates = androidx.compose.ui.res.stringArrayResource(R.array.routine_templates)
            val prompts = androidx.compose.ui.res.stringArrayResource(R.array.routine_template_prompts)
            androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 10.dp)) {
                items(templates.size) { i ->
                    Text(templates[i], style = MaterialTheme.typography.labelLarge, color = LocalDotPalette.current.deep,
                        modifier = Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(LocalDotPalette.current.light)
                            .clickable { onTemplate(prompts[i]) }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Hairline()
            mine.forEachIndexed { i, routine ->
                if (i > 0) Hairline()
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(routine.name, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
                        Text(routine.scheduleLabel(), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                    }
                    // Lancer maintenant : on voit tout de suite si la routine marche, sans attendre son heure.
                    androidx.compose.foundation.layout.Box(Modifier.clickable { app.dotshermes.data.direct.RoutineWorker.runNow(context, routine.id) }.padding(10.dp)) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.PlayArrow,
                            stringResource(R.string.routines_run_now), tint = LocalDotPalette.current.deep)
                    }
                    androidx.compose.material3.Switch(checked = routine.enabled, onCheckedChange = { store.setEnabled(routine.id, it) },
                        colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = LocalDotPalette.current.base,
                            checkedThumbColor = androidx.compose.ui.graphics.Color.White))
                    androidx.compose.foundation.layout.Box(Modifier.padding(start = 4.dp).clickable { store.delete(routine.id) }.padding(10.dp)) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.DeleteOutline,
                            stringResource(R.string.chats_delete), tint = Ink.Tertiary)
                    }
                }
            }
            } }
        }
    }
}

/** Ce dont Claydot se souvient (compte Groq) : chaque fait se supprime d'un geste. */
@Composable
fun MemorySettings(repo: app.dotshermes.data.direct.DirectChatRepository) {
    val facts by repo.facts.collectAsStateWithLifecycle()
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        GroupedCard {
            CollapsedHeader(androidx.compose.material.icons.Icons.Rounded.Psychology, stringResource(R.string.memory_section),
                if (facts.isEmpty()) stringResource(R.string.memory_none) else androidx.compose.ui.res.pluralStringResource(R.plurals.memory_summary, facts.size, facts.size), expanded, onToggle = { expanded = !expanded })
            androidx.compose.animation.AnimatedVisibility(expanded) { Column {
            Hairline()
            if (facts.isEmpty()) {
                Text(stringResource(R.string.memory_empty), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp))
            }
            facts.forEachIndexed { i, fact ->
                if (i > 0) Hairline()
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(fact, style = MaterialTheme.typography.bodyMedium, color = Ink.Primary, modifier = Modifier.weight(1f))
                    androidx.compose.foundation.layout.Box(Modifier.clickable { repo.forgetFact(fact) }.padding(10.dp)) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.DeleteOutline,
                            stringResource(R.string.memory_forget), tint = Ink.Tertiary)
                    }
                }
            }
            } }
        }
    }
}

/** Une ligne repliée : icône, titre, résumé d'un mot ; la flèche tourne quand la section s'ouvre. */
@Composable
private fun CollapsedHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, summary: String, expanded: Boolean, onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null) {
    val palette = LocalDotPalette.current
    val turn by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 14.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(9.dp)).background(palette.light),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.material3.Icon(icon, null, tint = palette.deep, modifier = Modifier.size(18.dp))
        }
        androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, maxLines = 1)
        }
        trailing?.let { it(); androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp)) }
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.ExpandMore, null, tint = Ink.Tertiary,
            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = turn })
    }
}

