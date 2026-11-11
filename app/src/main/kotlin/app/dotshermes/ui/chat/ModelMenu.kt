package app.dotshermes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.direct.ApiProvider
import app.dotshermes.data.direct.LocalModel
import app.dotshermes.data.direct.LocalModelState
import app.dotshermes.data.direct.LocalModels
import app.dotshermes.ui.components.rememberDeviceOnline
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette

/** Le modèle qui répond en ce moment : son fournisseur (Groq, téléphone) et son identifiant. */
data class ActiveModel(val provider: ApiProvider?, val model: String)

/** Nom court d'un modèle : « gpt-oss-120b », « Qwen 3.5 2B ». */
fun shortModelName(active: ActiveModel): String =
    if (active.provider == ApiProvider.Local) LocalModels.byId(active.model)?.name ?: active.model
    else active.model.substringAfterLast('/')

/**
 * À la place de la pastille « En ligne » : le modèle actuel (point vert en ligne, rouge sans réseau) et,
 * au toucher, un menu des modèles. Groq en ligne en haut, les modèles du téléphone en dessous, avec
 * leur bouton de téléchargement et la progression, sans quitter la conversation.
 */
@Composable
fun ModelMenu(
    active: ActiveModel,
    hasGroq: Boolean,
    loadGroqModels: suspend () -> List<String>,
    localStates: Map<String, LocalModelState>,
    onPickGroq: (String) -> Unit,
    onPickLocal: (String) -> Unit,
    onDownload: (LocalModel) -> Unit,
    onCancelDownload: (LocalModel) -> Unit,
    onAddGroq: () -> Unit,
) {
    val palette = LocalDotPalette.current
    var open by remember { mutableStateOf(false) }
    var groqModels by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(open, hasGroq) { if (open && hasGroq && groqModels == null) groqModels = runCatching { loadGroqModels() }.getOrDefault(emptyList()) }
    val deviceOnline = rememberDeviceOnline()
    val local = active.provider == ApiProvider.Local
    // Hors ligne, un modèle du téléphone marche quand même : son point reste vert.
    val dot = if (local || deviceOnline) Ink.Success else Ink.Danger

    Box {
        Row(
            Modifier.clip(CircleShape).background(Ink.Raised.copy(alpha = 0.85f)).border(0.5.dp, Ink.Separator, CircleShape)
                .clickable(role = Role.Button) { open = true }
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(shortModelName(active).ifEmpty { stringResource(R.string.conn_online) }, style = MaterialTheme.typography.labelMedium,
                color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 104.dp))
            Icon(Icons.Rounded.ExpandMore, stringResource(R.string.model_menu_open), tint = Ink.Secondary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(18.dp), containerColor = Ink.Raised,
            modifier = Modifier.widthIn(min = 248.dp, max = 300.dp)) {
            Section(stringResource(R.string.model_menu_online))
            if (!hasGroq) {
                MenuRow(stringResource(R.string.model_add_groq), null, onClick = { open = false; onAddGroq() }) {
                    Icon(Icons.Rounded.Add, null, tint = palette.deep, modifier = Modifier.size(18.dp))
                }
            } else when (val list = groqModels) {
                null -> Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = palette.base, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.model_loading), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                }
                else -> (list.ifEmpty { listOfNotNull(active.model.takeIf { active.provider == ApiProvider.Groq }) }).forEach { id ->
                    val chosen = active.provider == ApiProvider.Groq && active.model == id
                    MenuRow(id.substringAfterLast('/'), null, onClick = { open = false; if (!chosen) onPickGroq(id) }) {
                        if (chosen) Icon(Icons.Rounded.Check, null, tint = palette.deep, modifier = Modifier.size(18.dp))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Ink.Separator)
            Section(stringResource(R.string.model_menu_offline))
            LocalModels.CATALOG.forEach { model ->
                val state = localStates[model.id] ?: LocalModelState.Missing
                val chosen = local && active.model == model.id
                val size = String.format(java.util.Locale.ROOT, "%.1f GB", model.sizeBytes / 1_000_000_000.0)
                MenuRow(model.name, size, onClick = {
                    when (state) {
                        LocalModelState.Ready -> { open = false; if (!chosen) onPickLocal(model.id) }
                        LocalModelState.Missing, is LocalModelState.Failed -> onDownload(model)
                        is LocalModelState.Downloading -> Unit
                    }
                }) {
                    when (state) {
                        LocalModelState.Ready -> if (chosen) Icon(Icons.Rounded.Check, null, tint = palette.deep, modifier = Modifier.size(18.dp))
                        LocalModelState.Missing, is LocalModelState.Failed -> Box(
                            Modifier.size(30.dp).clip(CircleShape).background(palette.light).clickable { onDownload(model) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Download, stringResource(R.string.local_download), tint = palette.deep, modifier = Modifier.size(18.dp)) }
                        is LocalModelState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(progress = { state.fraction }, modifier = Modifier.size(18.dp), color = palette.base,
                                trackColor = Ink.Fill, strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("${(state.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Ink.Secondary)
                            Box(Modifier.size(28.dp).clip(CircleShape).clickable { onCancelDownload(model) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, stringResource(R.string.local_cancel), tint = Ink.Tertiary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.model_menu_hint), style = MaterialTheme.typography.labelSmall, color = Ink.Tertiary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink.Secondary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp))
}

@Composable
private fun MenuRow(title: String, subtitle: String?, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(start = 16.dp, end = 10.dp, top = 9.dp, bottom = 9.dp).widthIn(min = 228.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary) }
        }
        trailing()
    }
}
