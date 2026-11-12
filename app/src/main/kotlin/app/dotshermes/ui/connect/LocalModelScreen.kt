package app.dotshermes.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dotshermes.R
import app.dotshermes.data.direct.ApiFailure
import app.dotshermes.data.direct.ApiProvider
import app.dotshermes.data.direct.DeviceTier
import app.dotshermes.data.direct.LocalModel
import app.dotshermes.data.direct.LocalModelState
import app.dotshermes.data.direct.LocalModels
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.chat.DownloadButton
import app.dotshermes.ui.chat.DownloadProgress
import app.dotshermes.ui.chat.InUse
import app.dotshermes.ui.chat.LogoTile
import app.dotshermes.ui.chat.SmallButton
import app.dotshermes.ui.chat.gigabytes
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.SectionLabel
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import kotlinx.coroutines.launch

/**
 * « Claydot hors ligne » : les modèles déjà sur le téléphone en haut, ceux à télécharger dessous, puis les modèles
 * en ligne (Groq, ChatGPT, Claude) avec une clé. Une ligne par modèle : logo, nom entier, taille, une note ; l'action
 * à droite (Utiliser, Télécharger, progression). « Conseillé » est une ligne, pas une pastille qui coupe le nom.
 */
@Composable
internal fun LocalModelScreen(
    appearance: DotAppearance,
    models: LocalModels,
    currentModel: String?,
    onUse: suspend (ApiProvider, String, String) -> ApiFailure?,
    onBack: (() -> Unit)?,
    onCancel: (() -> Unit)?,
    /** Un modèle en ligne choisi : le formulaire de clé de ce fournisseur. */
    onOnline: ((ApiProvider) -> Unit)? = null,
) {
    val states by models.states.collectAsStateWithLifecycle()
    var working by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<ApiFailure?>(null) }
    val scope = rememberCoroutineScope()
    val palette = LocalDotPalette.current
    val context = LocalContext.current
    val recommended = remember { DeviceTier.recommended(context)?.id }
    LaunchedEffect(Unit) { models.watch() }
    val ready = LocalModels.CATALOG.filter { states[it.id] == LocalModelState.Ready }
    val rest = LocalModels.CATALOG - ready.toSet()

    @Composable
    fun Rows(list: List<LocalModel>) = GroupedCard {
        list.forEachIndexed { i, model ->
            if (i > 0) Hairline(startIndent = 68.dp)
            ModelRow(model, states[model.id] ?: LocalModelState.Missing, current = model.id == currentModel,
                enoughRoom = models.hasRoom(model), busy = working != null,
                recommended = model.id == recommended, fits = remember(model.id) { DeviceTier.fits(context, model) },
                onDownload = { models.download(model) }, onCancel = { models.cancel(model) },
                onUse = {
                    scope.launch {
                        working = model.id; failure = null
                        failure = try { onUse(ApiProvider.Local, model.id, LocalModels.LOCAL_KEY) } finally { working = null }
                    }
                })
        }
    }

    ConnectLayout(appearance, DotMood.Idle, stringResource(R.string.local_title), stringResource(R.string.local_tagline)) { _ ->
        if (!LocalModels.deviceSupported()) {
            Text(stringResource(R.string.local_unsupported), style = MaterialTheme.typography.bodyMedium, color = Ink.Danger,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        }
        if (ready.isNotEmpty()) {
            SectionLabel(stringResource(R.string.local_section_ready))
            Rows(ready)
        }
        if (rest.isNotEmpty()) {
            SectionLabel(stringResource(R.string.local_section_download))
            Rows(rest)
        }
        if (working != null) {
            Text(stringResource(R.string.local_loading), style = MaterialTheme.typography.bodySmall, color = palette.deep,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        failure?.let {
            Text(stringResource(apiFailureMessage(it)), style = MaterialTheme.typography.bodyMedium, color = Ink.Danger,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Text(stringResource(R.string.local_limits), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp))

        onOnline?.let { pick ->
            SectionLabel(stringResource(R.string.local_section_online))
            GroupedCard {
                OnlineRow({ LogoTile(R.drawable.logo_groq, 40) }, "Groq", stringResource(R.string.online_groq_note), enabled = working == null) { pick(ApiProvider.Groq) }
                Hairline(startIndent = 68.dp)
                OnlineRow({ IconTile(Icons.Rounded.AutoAwesome) }, stringResource(R.string.online_chatgpt_title), stringResource(R.string.online_chatgpt_note),
                    enabled = working == null) { pick(ApiProvider.OpenAI) }
                Hairline(startIndent = 68.dp)
                OnlineRow({ IconTile(Icons.Rounded.Psychology) }, stringResource(R.string.online_claude_title), stringResource(R.string.online_claude_note),
                    enabled = working == null) { pick(ApiProvider.Anthropic) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
            onBack?.takeIf { onOnline == null }?.let { TextLink(stringResource(R.string.local_back), enabled = working == null, onClick = it) }
            onCancel?.let { TextLink(stringResource(R.string.agents_add_cancel), enabled = working == null, color = Ink.Secondary, onClick = it) }
        }
    }
}

/** Une ligne de modèle du téléphone : logo, nom entier, « taille · éditeur », puis « Conseillé » ou la note (deux lignes). */
@Composable
private fun ModelRow(
    model: LocalModel, state: LocalModelState, current: Boolean, enoughRoom: Boolean, busy: Boolean, recommended: Boolean, fits: Boolean,
    onDownload: () -> Unit, onCancel: () -> Unit, onUse: () -> Unit,
) {
    val palette = LocalDotPalette.current
    val warning = when {
        !fits -> stringResource(R.string.models_needs_memory)
        state is LocalModelState.Failed -> stringResource(R.string.local_failed)
        state !is LocalModelState.Ready && !enoughRoom -> stringResource(R.string.local_no_room)
        else -> null
    }
    val subtitle = if (state is LocalModelState.Downloading)
        stringResource(R.string.local_progress, gigabytes((model.sizeBytes * state.fraction).toLong()), gigabytes(model.sizeBytes))
        else "${gigabytes(model.sizeBytes)} · ${model.maker}"
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LogoTile(model.logo, 40)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(model.name, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = Ink.Primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, maxLines = 1)
            when {
                warning != null -> Text(warning, style = MaterialTheme.typography.bodySmall, color = Ink.Danger, maxLines = 2)
                recommended && fits && !current -> Text(stringResource(R.string.local_recommended_line), style = MaterialTheme.typography.labelMedium,
                    color = palette.deep, maxLines = 1)
                else -> Text(stringResource(model.note), style = MaterialTheme.typography.bodySmall, color = Ink.Tertiary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        when (state) {
            LocalModelState.Ready -> if (current) InUse() else SmallButton(stringResource(R.string.models_use), filled = true, enabled = !busy, onClick = onUse)
            LocalModelState.Missing, is LocalModelState.Failed -> DownloadButton(enabled = enoughRoom && fits && !busy, onClick = onDownload)
            is LocalModelState.Downloading -> DownloadProgress(state.fraction, onCancel)
        }
    }
}

/** Un modèle en ligne : son logo, son nom, la clé qu'il demande ; un toucher ouvre le formulaire de clé. */
@Composable
private fun OnlineRow(logo: @Composable () -> Unit, title: String, note: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        logo()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = Ink.Primary, maxLines = 1)
            Text(note, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, maxLines = 2)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Ink.Tertiary, modifier = Modifier.size(22.dp))
    }
}

/** Une tuile neutre avec une icône, de la taille des logos (pas d'imitation de logo de marque). */
@Composable
private fun IconTile(icon: ImageVector) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Ink.Fill), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Ink.Primary, modifier = Modifier.size(22.dp))
    }
}
