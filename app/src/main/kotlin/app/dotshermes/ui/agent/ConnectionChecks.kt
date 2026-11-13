package app.dotshermes.ui.agent

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import app.dotshermes.AppContainer
import app.dotshermes.R
import app.dotshermes.data.direct.*
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.SectionLabel
import app.dotshermes.ui.theme.Ink
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Diagnostic demandé par l'utilisateur ; les corrections ouvrent les réglages sans changer les autorisations. */
@Composable
fun ConnectionChecks(connection: ConnectionState, failure: ApiFailure?, model: String, onReviewAccount: () -> Unit,
    onCheckModel: suspend () -> ModelDiagnosticResult) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gmail = AppContainer.gmail
    val phone = AppContainer.phoneActions
    val connected by gmail.connected.collectAsStateWithLifecycle()
    val imapEmail by gmail.imapEmail.collectAsStateWithLifecycle()
    val phoneEnabled by phone.enabled.collectAsStateWithLifecycle()
    val gmailAvailable = (connection as? ConnectionState.Connected)?.gatewayUrl in setOf(ApiProvider.Groq.title, ApiProvider.Local.title)
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    var gmailState by remember(connected, imapEmail) { mutableStateOf<GmailDiagnostic?>(null) }
    var modelState by remember { mutableStateOf<ModelDiagnosticResult?>(null) }
    var phoneState by remember { mutableStateOf<Map<PhoneAccess, Boolean>>(emptyMap()) }
    fun settings(access: PhoneAccess) {
        val intent = PhoneActions.settingsIntent(context, access) ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri())
        if (runCatching { context.startActivity(intent) }.isFailure)
            Toast.makeText(context, R.string.diagnostics_settings_missing, Toast.LENGTH_SHORT).show()
    }
    SectionLabel(stringResource(R.string.diagnostics_title))
    GroupedCard {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.diagnostics_note), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
            TextButton(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    try {
                        phoneState = PhoneAccess.entries.associateWith { phone.hasAccess(it) }
                        coroutineScope {
                            val modelCheck = async { withTimeoutOrNull(15_000) { onCheckModel() }
                                ?: ModelDiagnosticResult(DiagnosticState.UnableToCheck) }
                            val gmailCheck = async { if (gmailAvailable) withTimeoutOrNull(15_000) { gmail.diagnose() }
                                ?: GmailDiagnostic.Unavailable else null }
                            modelState = modelCheck.await()
                            gmailState = gmailCheck.await()
                        }
                        checked = true
                    } finally { checking = false }
                }
            }) { Text(stringResource(if (checking) R.string.diagnostics_running else R.string.diagnostics_run)) }
            if (checked) {
                Text(stringResource(R.string.diagnostics_model) + " · " + model, style = MaterialTheme.typography.labelLarge, color = Ink.Primary)
                val result = modelState ?: ModelDiagnosticResult(modelDiagnostic(connection is ConnectionState.Connected, failure), failure)
                val state = result.state
                Text(stringResource(when (state) {
                    DiagnosticState.Configured -> if (result.listed == false) R.string.diagnostics_model_unlisted else R.string.diagnostics_configured
                    DiagnosticState.Verified -> if ((connection as? ConnectionState.Connected)?.gatewayUrl == ApiProvider.Local.title)
                        R.string.diagnostics_model_local_ready else R.string.diagnostics_model_verified
                    DiagnosticState.Missing -> R.string.diagnostics_missing
                    DiagnosticState.NeedsAttention -> if ((connection as? ConnectionState.Connected)?.gatewayUrl == ApiProvider.Local.title && result.listed == false)
                        R.string.diagnostics_model_local_missing else R.string.diagnostics_attention
                    DiagnosticState.UnableToCheck -> R.string.diagnostics_unable
                }), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                result.failure?.let { Text(stringResource(app.dotshermes.ui.connect.apiFailureMessage(it)),
                    style = MaterialTheme.typography.bodySmall, color = Ink.Secondary) }
                if (failure != null && failure != result.failure && failure != ApiFailure.Cancelled) Text(
                    stringResource(R.string.diagnostics_last_request, stringResource(app.dotshermes.ui.connect.apiFailureMessage(failure))),
                    style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                if ((state != DiagnosticState.Verified || failure != null) && (connection as? ConnectionState.Connected)?.gatewayUrl != ApiProvider.Local.title)
                    TextButton(onClick = onReviewAccount) { Text(stringResource(R.string.diagnostics_model_action)) }
                Text(stringResource(R.string.diagnostics_gmail), style = MaterialTheme.typography.labelLarge, color = Ink.Primary)
                Text(stringResource(if (!gmailAvailable) R.string.diagnostics_gmail_provider else when (gmailState) {
                    GmailDiagnostic.NotConnected, null -> R.string.diagnostics_gmail_missing
                    GmailDiagnostic.Authorized -> R.string.diagnostics_gmail_verified
                    GmailDiagnostic.Reconnect -> R.string.diagnostics_gmail_reconnect
                    GmailDiagnostic.SetupMissing -> R.string.diagnostics_gmail_setup
                    GmailDiagnostic.Unavailable -> R.string.diagnostics_unable
                    GmailDiagnostic.AppPasswordConfigured -> R.string.diagnostics_gmail_password
                }), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                Text(stringResource(R.string.diagnostics_phone), style = MaterialTheme.typography.labelLarge, color = Ink.Primary)
                if (!phoneEnabled) Text(stringResource(R.string.diagnostics_phone_off), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                phoneState.forEach { (access, granted) ->
                    val title = when (access) {
                        PhoneAccess.Contacts -> R.string.diagnostics_phone_contacts
                        PhoneAccess.Notifications -> R.string.diagnostics_phone_notifications
                        PhoneAccess.Screen -> R.string.diagnostics_phone_screen
                        PhoneAccess.AllDay -> R.string.diagnostics_phone_allday
                    }
                    Text(stringResource(title), style = MaterialTheme.typography.labelMedium, color = Ink.Primary)
                    Text(stringResource(if (granted) R.string.diagnostics_granted else R.string.diagnostics_not_granted),
                        style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                    if (!granted) TextButton(onClick = { settings(access) }) { Text(stringResource(R.string.diagnostics_settings)) }
                }
            }
        }
    }
}
