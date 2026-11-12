package app.dotshermes.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dotshermes.R
import app.dotshermes.data.direct.ApiFailure
import app.dotshermes.data.direct.ApiProvider
import app.dotshermes.data.direct.LocalModel
import app.dotshermes.data.direct.LocalModelState
import app.dotshermes.data.direct.LocalModels
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.PillButton
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import app.dotshermes.ui.theme.tabular
import kotlinx.coroutines.launch

/**
 * « Claydot hors ligne » : on télécharge un modèle une fois, il répond ensuite sur le téléphone, sans
 * clé d'API ni Internet. Une carte par modèle : sa taille, Télécharger (avec la progression), puis Utiliser.
 */
@Composable
internal fun LocalModelScreen(
    appearance: DotAppearance,
    models: LocalModels,
    currentModel: String?,
    onUse: suspend (ApiProvider, String, String) -> ApiFailure?,
    onBack: (() -> Unit)?,
    onCancel: (() -> Unit)?,
) {
    val states by models.states.collectAsStateWithLifecycle()
    var working by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<ApiFailure?>(null) }
    val scope = rememberCoroutineScope()
    val palette = LocalDotPalette.current
    LaunchedEffect(Unit) { models.watch() }

    ConnectLayout(appearance, DotMood.Idle, stringResource(R.string.local_title), stringResource(R.string.local_tagline)) { _ ->
        if (!LocalModels.deviceSupported()) {
            Text(stringResource(R.string.local_unsupported), style = MaterialTheme.typography.bodyMedium, color = Ink.Danger,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        }
        GroupedCard {
            LocalModels.CATALOG.forEachIndexed { i, model ->
                if (i > 0) Hairline()
                ModelRow(model, states[model.id] ?: LocalModelState.Missing, current = model.id == currentModel,
                    enoughRoom = models.hasRoom(model), busy = working != null,
                    onDownload = { models.download(model) }, onCancel = { models.cancel(model) },
                    onUse = {
                        scope.launch {
                            working = model.id; failure = null
                            failure = try { onUse(ApiProvider.Local, model.id, LocalModels.LOCAL_KEY) } finally { working = null }
                        }
                    })
            }
        }
        failure?.let {
            Text(stringResource(apiFailureMessage(it)), style = MaterialTheme.typography.bodyMedium, color = Ink.Danger,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        Text(stringResource(R.string.local_limits), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            onBack?.let { TextLink(stringResource(R.string.local_back), enabled = working == null, onClick = it) }
            onCancel?.let { TextLink(stringResource(R.string.agents_add_cancel), enabled = working == null, color = Ink.Secondary, onClick = it) }
        }
        if (working != null) {
            Text(stringResource(R.string.local_loading), style = MaterialTheme.typography.bodySmall, color = palette.deep,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

@Composable
private fun ModelRow(
    model: LocalModel, state: LocalModelState, current: Boolean, enoughRoom: Boolean, busy: Boolean,
    onDownload: () -> Unit, onCancel: () -> Unit, onUse: () -> Unit,
) {
    val palette = LocalDotPalette.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
                Text("${gigabytes(model.sizeBytes)} · ${stringResource(model.note)}", style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
            }
        }
        Spacer(Modifier.height(10.dp))
        when (state) {
            LocalModelState.Missing, is LocalModelState.Failed -> {
                if (state is LocalModelState.Failed) Text(stringResource(R.string.local_failed), style = MaterialTheme.typography.bodySmall,
                    color = Ink.Danger, modifier = Modifier.padding(bottom = 6.dp))
                PillButton(stringResource(if (enoughRoom) R.string.local_download else R.string.local_no_room), onDownload,
                    modifier = Modifier.fillMaxWidth(), container = palette.light, content = palette.deep, height = 44.dp,
                    enabled = enoughRoom && !busy)
            }
            is LocalModelState.Downloading -> {
                LinearProgressIndicator(progress = { state.fraction }, color = palette.base, trackColor = Ink.Fill,
                    modifier = Modifier.fillMaxWidth().height(6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.local_progress, gigabytes((model.sizeBytes * state.fraction).toLong()), gigabytes(model.sizeBytes)),
                        style = MaterialTheme.typography.bodySmall.tabular(), color = Ink.Secondary, modifier = Modifier.weight(1f))
                    TextLink(stringResource(R.string.local_cancel), enabled = true, color = Ink.Secondary, onClick = onCancel)
                }
            }
            LocalModelState.Ready -> PillButton(stringResource(if (current) R.string.local_in_use else R.string.local_use), onUse,
                modifier = Modifier.fillMaxWidth(), container = palette.base, content = palette.onBase, height = 44.dp, enabled = !busy && !current)
        }
    }
}

private fun gigabytes(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
