package app.dotshermes.ui.agent

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        working = false
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        try {
            if (gmail.resultFrom(result.data).accessToken != null) gmail.markConnected(true) else problem = R.string.gmail_error
        } catch (_: Exception) { problem = R.string.gmail_error }
    }

    fun connect() {
        problem = null
        working = true
        scope.launch {
            try {
                val result = gmail.authorize()
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    return@launch
                }
                gmail.markConnected(result.accessToken != null)
            } catch (e: CancellationException) { throw e }
            catch (e: com.google.android.gms.common.api.ApiException) {
                // 10 (DEVELOPER_ERROR) : pas de client OAuth Android pour ce paquet et cette signature.
                problem = if (e.statusCode == 10) R.string.gmail_error_setup else R.string.gmail_error
            } catch (_: Exception) { problem = R.string.gmail_error }
            working = false
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
            Text(
                stringResource(if (connected) R.string.gmail_disconnect else R.string.gmail_connect),
                style = MaterialTheme.typography.bodyLarge,
                color = if (connected) Ink.Danger else LocalDotPalette.current.deep,
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = !working) { if (connected) scope.launch { gmail.disconnect() } else connect() }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
            )
        }
        problem?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = Ink.Danger,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp))
        }
    }
}

/** Actions sur le téléphone : un interrupteur ; chaque action reste confirmée au moment voulu. */
@Composable
fun PhoneSettings(actions: app.dotshermes.data.direct.PhoneActions) {
    val enabled by actions.enabled.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.phone_section))
        GroupedCard {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.phone_title), style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
                    Text(stringResource(R.string.phone_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                }
                androidx.compose.material3.Switch(checked = enabled, onCheckedChange = actions::setEnabled,
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = LocalDotPalette.current.base,
                        checkedThumbColor = androidx.compose.ui.graphics.Color.White))
            }
        }
    }
}

/** Routines du compte : interrupteur et suppression ; on les crée en le demandant à Claydot. */
@Composable
fun RoutinesSettings(store: app.dotshermes.data.direct.RoutineStore, accountId: String?) {
    val all by store.routines.collectAsStateWithLifecycle()
    val mine = all.filter { it.accountId == accountId }
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.routines_section))
        GroupedCard {
            if (mine.isEmpty()) {
                Text(stringResource(R.string.routines_empty), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp))
            }
            mine.forEachIndexed { i, routine ->
                if (i > 0) Hairline()
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(routine.name, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
                        Text(routine.scheduleLabel(), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
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
        }
    }
}

/** Ce dont Claydot se souvient (compte Groq) : chaque fait se supprime d'un geste. */
@Composable
fun MemorySettings(repo: app.dotshermes.data.direct.DirectChatRepository) {
    val facts by repo.facts.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.memory_section))
        GroupedCard {
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
        }
    }
}
