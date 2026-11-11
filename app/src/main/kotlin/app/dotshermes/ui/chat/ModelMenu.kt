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
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
 * À la place de la pastille « En ligne » : le modèle actuel et, au toucher, une liste simple — une ligne
 * par modèle (icône, nom, « taille · éditeur »), une coche sur celui qui répond, la flèche de téléchargement
 * sur ceux qui manquent, et « Ajouter un modèle » en bas.
 */
@Composable
fun ModelMenu(
    active: ActiveModel,
    groqModel: String?,
    localStates: Map<String, LocalModelState>,
    onPickGroq: (String) -> Unit,
    onPickLocal: (String) -> Unit,
    onDownload: (LocalModel) -> Unit,
    onCancelDownload: (LocalModel) -> Unit,
    onAddModel: () -> Unit,
) {
    val palette = LocalDotPalette.current
    var open by remember { mutableStateOf(false) }
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
        DropdownMenu(open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp), containerColor = Ink.Raised,
            modifier = Modifier.widthIn(min = 260.dp, max = 320.dp)) {
            groqModel?.let { id ->
                val chosen = active.provider == ApiProvider.Groq
                ModelRow(Icons.Rounded.Cloud, id.substringAfterLast('/'), "Groq · " + stringResource(R.string.model_online),
                    onClick = { open = false; if (!chosen) onPickGroq(id) }) {
                    if (chosen) Icon(Icons.Rounded.Check, null, tint = palette.deep, modifier = Modifier.size(20.dp))
                }
            }
            LocalModels.CATALOG.forEach { model ->
                val state = localStates[model.id] ?: LocalModelState.Missing
                val chosen = local && active.model == model.id
                val size = String.format(java.util.Locale.ROOT, "%.1f GB", model.sizeBytes / 1_000_000_000.0)
                ModelRow(Icons.Rounded.PhoneAndroid, model.name, "$size · ${model.maker}", onClick = {
                    when (state) {
                        LocalModelState.Ready -> { open = false; if (!chosen) onPickLocal(model.id) }
                        LocalModelState.Missing, is LocalModelState.Failed -> onDownload(model)
                        is LocalModelState.Downloading -> onCancelDownload(model)
                    }
                }) {
                    when (state) {
                        LocalModelState.Ready -> if (chosen) Icon(Icons.Rounded.Check, null, tint = palette.deep, modifier = Modifier.size(20.dp))
                        LocalModelState.Missing, is LocalModelState.Failed ->
                            Icon(Icons.Rounded.Download, stringResource(R.string.local_download), tint = Ink.Secondary, modifier = Modifier.size(20.dp))
                        is LocalModelState.Downloading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${(state.fraction * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Ink.Secondary)
                            Spacer(Modifier.width(6.dp))
                            CircularProgressIndicator(progress = { state.fraction }, modifier = Modifier.size(18.dp), color = palette.base,
                                trackColor = Ink.Fill, strokeWidth = 2.5.dp)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Ink.Separator)
            ModelRow(Icons.Rounded.Add, stringResource(R.string.model_add), null, onClick = { open = false; onAddModel() }, plain = true) {}
        }
    }
}

@Composable
private fun ModelRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, plain: Boolean = false, trailing: @Composable () -> Unit) {
    val palette = LocalDotPalette.current
    Row(Modifier.clickable(onClick = onClick).padding(start = 14.dp, end = 16.dp, top = 10.dp, bottom = 10.dp).widthIn(min = 236.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (plain) Ink.Raised else palette.light), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (plain) Ink.Primary else palette.deep, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, maxLines = 1) }
        }
        trailing()
    }
}
