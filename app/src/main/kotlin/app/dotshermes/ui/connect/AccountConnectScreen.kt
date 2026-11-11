package app.dotshermes.ui.connect

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.material3.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import app.dotshermes.R
import app.dotshermes.data.AgentSummary
import app.dotshermes.data.direct.*
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.displayName
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.PillButton
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Écran de connexion, dans l'esprit de l'écran d'origine : la mascotte, une seule carte de champs, un
 * gros bouton, des liens discrets dessous. Par défaut, une clé API Groq ; « Serveur Hermes » est un lien.
 * Un compte OpenAI ou Claude déjà enregistré reste modifiable ici, mais on n'en crée plus de nouveau.
 * La clé reste en mémoire de cette composition, jamais dans rememberSaveable ni dans le ViewModel.
 */
@Composable
fun AccountConnectScreen(
    connection: ConnectionState, appearance: DotAppearance, initialUrl: String,
    initialProvider: String, initialModel: String, onRemove: (() -> Unit)? = null,
    recoveryFailure: ApiFailure? = null,
    /** Compte direct existant en cours de modification : on peut revenir à la conversation sans rien changer. */
    editing: Boolean = false, onCancelEdit: () -> Unit = {},
    onConnect: (String, String) -> Unit,
    onModels: suspend (ApiProvider, String) -> List<String>,
    onDirect: suspend (ApiProvider, String, String) -> ApiFailure?,
    adding: Boolean, onCancelAdd: () -> Unit, otherAgents: List<AgentSummary>, onSelectAgent: (String) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(initialProvider) }
    // Retour système : annule l'ajout ou la modification en cours au lieu de fermer l'app.
    BackHandler(enabled = adding || editing) { if (adding) onCancelAdd() else onCancelEdit() }
    // Plus de serveur Hermes dans Claydot : un ancien réglage « hermes » ouvre la clé Groq.
    val provider = ApiProvider.fromId(mode) ?: ApiProvider.Groq
    if (provider == ApiProvider.Local) {
        LocalModelScreen(appearance, app.dotshermes.AppContainer.localModels,
            currentModel = initialModel.takeIf { initialProvider == ApiProvider.Local.id },
            onUse = onDirect,
            onBack = if (editing) onCancelEdit else ({ mode = ApiProvider.Groq.id }),
            onCancel = onCancelAdd.takeIf { adding })
    } else key(provider, initialModel) {
        DirectConnectForm(provider, appearance, initialModel.takeIf { initialProvider == provider.id }.orEmpty(), onModels, onDirect,
            recoveryFailure.takeIf { initialProvider == provider.id },
            onCancelAdd.takeIf { adding }, onRemove.takeIf { initialProvider == provider.id },
            otherAgents, onSelectAgent,
            keySaved = editing && initialProvider == provider.id && recoveryFailure == null,
            onBack = onCancelEdit.takeIf { editing },
            onHermes = null,
            onOffline = if (!editing) ({ mode = ApiProvider.Local.id }) else null)
    }
}

@Composable
private fun DirectConnectForm(
    provider: ApiProvider, appearance: DotAppearance, initialModel: String,
    onModels: suspend (ApiProvider, String) -> List<String>,
    onDirect: suspend (ApiProvider, String, String) -> ApiFailure?,
    recoveryFailure: ApiFailure?,
    onCancel: (() -> Unit)?, onRemove: (() -> Unit)?, otherAgents: List<AgentSummary>, onSelectAgent: (String) -> Unit,
    /** Une clé est déjà enregistrée pour ce compte : le champ peut rester vide (modèle seul à changer). */
    keySaved: Boolean = false,
    onBack: (() -> Unit)? = null,
    onHermes: (() -> Unit)? = null,
    onOffline: (() -> Unit)? = null,
) {
    var apiKey by remember { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf(initialModel) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var expanded by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ApiFailure?>(null) }
    val scope = rememberCoroutineScope()
    val palette = LocalDotPalette.current
    val autoModel = provider == ApiProvider.Groq
    val existing = onBack != null || onRemove != null
    // Le choix du modèle ne sert qu'à modifier un compte (ou pour un fournisseur sans choix automatique).
    val showModel = existing || !autoModel
    val uri = LocalUriHandler.current

    fun save() {
        scope.launch {
            working = true; failure = null
            try { failure = onDirect(provider, model.trim(), apiKey.trim()); if (failure == null) apiKey = "" }
            catch (e: CancellationException) { throw e }
            finally { working = false }
        }
    }

    fun loadModels() {
        scope.launch {
            working = true; failure = null
            try { models = onModels(provider, apiKey.trim()); expanded = models.isNotEmpty() }
            catch (e: CancellationException) { throw e }
            catch (e: ApiException) { failure = e.failure }
            catch (_: Exception) { failure = ApiFailure.Network }
            finally { working = false }
        }
    }

    ConnectLayout(
        appearance = appearance,
        mood = if (working) DotMood.Thinking else if (failure != null) DotMood.Sleeping else DotMood.Idle,
        // Compte à créer : le nom de l'app ; « Hermes » (nom par défaut d'un agent) n'aurait pas de sens ici.
        title = if (existing) appearance.displayName else if (onCancel != null) stringResource(R.string.agents_add_title) else stringResource(R.string.app_name),
        tagline = if (autoModel && !existing) stringResource(R.string.api_connect_tagline_groq) else stringResource(R.string.api_connect_title, provider.title),
    ) { tight ->
        GroupedCard(color = Ink.Grouped) {
            FieldRow(stringResource(R.string.api_key_label), apiKey, { apiKey = it; models = emptyList() }, !working, secret = true,
                placeholder = if (keySaved) stringResource(R.string.api_key_saved_hint) else stringResource(R.string.api_key_hint, provider.title))
            if (showModel) {
                Hairline()
                Box {
                    FieldRow(stringResource(R.string.api_model_label), model, { model = it }, !working,
                        placeholder = stringResource(if (autoModel) R.string.api_model_auto else R.string.api_model_hint),
                        trailing = {
                            Box(Modifier.size(44.dp).clip(CircleShape)
                                .clickable(enabled = !working && (apiKey.isNotBlank() || keySaved), onClickLabel = stringResource(R.string.api_choose_model)) { loadModels() },
                                contentAlignment = Alignment.Center) {
                                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Rounded.ExpandMore, stringResource(R.string.api_choose_model), tint = Ink.Secondary)
                            }
                        })
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        models.forEach { id -> DropdownMenuItem(text = { Text(id) }, onClick = { model = id; expanded = false }) }
                    }
                }
            }
        }
        if (!tight) {
            Text(stringResource(R.string.api_key_help_short, provider.title), style = MaterialTheme.typography.bodySmall,
                color = Ink.Secondary, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp))
        }
        (failure ?: recoveryFailure)?.let {
            Text(stringResource(apiFailureMessage(it)), style = MaterialTheme.typography.bodyMedium, color = Ink.Danger,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        PillButton(
            text = stringResource(if (working) R.string.connect_connecting else if (existing) R.string.api_save_account else R.string.connect_button),
            modifier = Modifier.fillMaxWidth(), container = palette.base, content = palette.onBase, height = 54.dp,
            enabled = !working && (apiKey.isNotBlank() || keySaved) && (model.isNotBlank() || autoModel), onClick = ::save,
        )
        // Liens discrets, comme « Essayer la démo » à l'origine : quelques mots, jamais une pile de boutons.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            when {
                onBack != null -> TextLink(stringResource(R.string.api_back_to_chat), enabled = !working, onClick = onBack)
                onCancel != null -> TextLink(stringResource(R.string.agents_add_cancel), enabled = !working, onClick = onCancel)
            }
            if (autoModel && !keySaved) TextLink(stringResource(R.string.api_get_key_groq), enabled = !working) { uri.openUri(GROQ_KEYS_URL) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            // Supprimer le compte vit dans les réglages ; ici seulement si la clé enregistrée est illisible.
            onRemove?.takeIf { recoveryFailure != null }?.let { TextLink(stringResource(R.string.api_delete_account), enabled = !working, color = Ink.Danger, onClick = it) }
        }
        // Pas de clé d'API : un modèle téléchargé une fois, qui répond sur le téléphone.
        onOffline?.let { TextLink(stringResource(R.string.local_link), enabled = !working, color = Ink.Secondary, onClick = it) }
        if (otherAgents.isNotEmpty() && onBack == null && onCancel == null) OtherAgentsLink(otherAgents, onSelectAgent)
    }
}

private const val GROQ_KEYS_URL = "https://console.groq.com/keys"

fun apiFailureMessage(failure: ApiFailure): Int = when (failure) {
    ApiFailure.MissingKey, ApiFailure.InvalidKey -> R.string.api_error_key
    ApiFailure.Forbidden -> R.string.api_error_access
    ApiFailure.ModelUnavailable -> R.string.api_error_model
    ApiFailure.Quota -> R.string.api_error_quota
    ApiFailure.RateLimited -> R.string.api_error_rate
    ApiFailure.Storage -> R.string.api_error_storage
    ApiFailure.Cancelled -> R.string.api_error_stopped
    ApiFailure.ContextLimit, ApiFailure.OutputLimit -> R.string.api_error_limit
    ApiFailure.Network, ApiFailure.Unavailable, ApiFailure.Interrupted -> R.string.api_error_network
    ApiFailure.DeviceHot -> R.string.local_error_hot
    ApiFailure.LowBattery -> R.string.local_error_battery
    ApiFailure.LowMemory -> R.string.local_error_memory
    else -> R.string.api_error_response
}
