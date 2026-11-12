package app.dotshermes.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import app.dotshermes.data.direct.DeviceTier
import app.dotshermes.ui.chat.DownloadButton
import app.dotshermes.ui.chat.DownloadProgress
import app.dotshermes.ui.chat.InUse
import app.dotshermes.ui.chat.SheetRow
import app.dotshermes.ui.chat.SmallButton
import app.dotshermes.ui.chat.gigabytes
import androidx.compose.ui.platform.LocalContext
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
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
    val context = LocalContext.current
    val recommended = remember { DeviceTier.recommended(context)?.id }
    LaunchedEffect(Unit) { models.watch() }

    ConnectLayout(appearance, DotMood.Idle, stringResource(R.string.local_title), stringResource(R.string.local_tagline)) { _ ->
        if (!LocalModels.deviceSupported()) {
            Text(stringResource(R.string.local_unsupported), style = MaterialTheme.typography.bodyMedium, color = Ink.Danger,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
        }
        GroupedCard {
            LocalModels.CATALOG.forEachIndexed { i, model ->
                if (i > 0) Hairline(startIndent = 62.dp)
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

/** Une ligne compacte, comme la feuille « Modèles » : logo, nom, taille, une ligne de note ; l'action à droite. */
@Composable
private fun ModelRow(
    model: LocalModel, state: LocalModelState, current: Boolean, enoughRoom: Boolean, busy: Boolean, recommended: Boolean, fits: Boolean,
    onDownload: () -> Unit, onCancel: () -> Unit, onUse: () -> Unit,
) {
    val warning = when {
        !fits -> stringResource(R.string.models_needs_memory)
        state is LocalModelState.Failed -> stringResource(R.string.local_failed)
        state !is LocalModelState.Ready && !enoughRoom -> stringResource(R.string.local_no_room)
        else -> null
    }
    val subtitle = if (state is LocalModelState.Downloading)
        stringResource(R.string.local_progress, gigabytes((model.sizeBytes * state.fraction).toLong()), gigabytes(model.sizeBytes))
        else "${gigabytes(model.sizeBytes)} · ${model.maker}"
    SheetRow(logo = model.logo, title = model.name, subtitle = subtitle, note = stringResource(model.note),
        recommended = recommended && fits, warning = warning) {
        when (state) {
            LocalModelState.Ready -> if (current) InUse() else SmallButton(stringResource(R.string.models_use), filled = true, enabled = !busy, onClick = onUse)
            LocalModelState.Missing, is LocalModelState.Failed -> DownloadButton(enabled = enoughRoom && fits && !busy, onClick = onDownload)
            is LocalModelState.Downloading -> DownloadProgress(state.fraction, onCancel)
        }
    }
}
