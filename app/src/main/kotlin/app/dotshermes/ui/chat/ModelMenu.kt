package app.dotshermes.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.dotshermes.R
import app.dotshermes.data.direct.ApiProvider
import app.dotshermes.data.direct.DeviceTier
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
 * À la place de la pastille « En ligne » : le modèle actuel. Au toucher, une carte au milieu, sous
 * l'en-tête, sur toute la largeur : une ligne par modèle (logo de l'éditeur, nom, « taille · éditeur »),
 * « Recommandé » sur le meilleur pour ce téléphone, une coche sur celui qui répond. Seuls les modèles
 * prêts y figurent ; « Ajouter un modèle », en bas, ouvre la liste complète pour en télécharger.
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
    onAddGroqKey: () -> Unit,
) {
    val palette = LocalDotPalette.current
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    val local = active.provider == ApiProvider.Local
    val deviceOnline = rememberDeviceOnline()
    // Hors ligne, un modèle du téléphone marche quand même : son point reste vert.
    val dot = if (local || deviceOnline) Ink.Success else Ink.Danger
    val recommended = remember { DeviceTier.recommended(context)?.id }
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = open
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "model-chevron")
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }

    Box {
        Row(
            Modifier.clip(CircleShape).background(Ink.Raised.copy(alpha = 0.85f)).border(0.5.dp, Ink.Separator, CircleShape)
                .clickable(role = Role.Button) { open = !open }
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(shortModelName(active).ifEmpty { stringResource(R.string.conn_online) }, style = MaterialTheme.typography.labelMedium,
                color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 104.dp))
            Icon(Icons.Rounded.ExpandMore, stringResource(R.string.model_menu_open), tint = Ink.Secondary,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turn })
        }
        if (shown.currentState || shown.targetState) Popup(
            // Centrée sur l'écran (et non sous la pastille), juste sous l'en-tête.
            popupPositionProvider = remember(gap) {
                object : PopupPositionProvider {
                    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                        IntOffset((windowSize.width - popupContentSize.width) / 2, anchorBounds.bottom + gap)
                }
            },
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = true),
        ) {
            AnimatedVisibility(shown,
                enter = fadeIn(tween(160)) + scaleIn(initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0f)),
                exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f)),
            ) {
                Column(
                    Modifier.width(screenWidth - 32.dp).heightIn(max = screenHeight * 0.7f)
                        .shadow(16.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
                        .clip(RoundedCornerShape(24.dp)).background(Ink.Raised).border(0.5.dp, Ink.Separator, RoundedCornerShape(24.dp))
                        .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
                ) {
                    groqModel?.let { id ->
                        val chosen = active.provider == ApiProvider.Groq
                        ModelRow(R.drawable.logo_groq, id.substringAfterLast('/'), "Groq · " + stringResource(R.string.model_online),
                            onClick = { open = false; if (!chosen) onPickGroq(id) }) {
                            if (chosen) Icon(Icons.Rounded.Check, null, tint = palette.deep, modifier = Modifier.size(20.dp))
                        }
                    }
                    // Seulement les modèles déjà téléchargés : le téléchargement se fait dans « Ajouter un modèle ».
                    LocalModels.CATALOG.filter { localStates[it.id] == LocalModelState.Ready }.forEach { model ->
                        val chosen = local && active.model == model.id
                        ModelRow(model.logo, model.name, "${gigabytes(model.sizeBytes)} · ${model.maker}",
                            recommended = model.id == recommended,
                            onClick = { open = false; if (!chosen) onPickLocal(model.id) }) {
                            if (chosen) Icon(Icons.Rounded.Check, null, tint = palette.deep, modifier = Modifier.size(20.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Ink.Separator)
                    PlainRow(Icons.Rounded.Add, stringResource(R.string.model_add)) { open = false; sheetOpen = true }
                }
            }
        }
    }
    if (sheetOpen) {
        AddModelSheet(active, groqModel, localStates, onPickGroq, onPickLocal, onDownload, onCancelDownload,
            onAddGroqKey = onAddGroqKey, onDismiss = { sheetOpen = false })
    }
}

/** Le logo de l'éditeur sur une tuile blanche (lisible en thème sombre comme en clair). */
@Composable
internal fun LogoTile(@DrawableRes logo: Int, size: Int = 36) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.28f).dp)).background(Color.White)
        .border(0.5.dp, Ink.Separator, RoundedCornerShape((size * 0.28f).dp)), contentAlignment = Alignment.Center) {
        Image(painterResource(logo), null, modifier = Modifier.size((size * 0.58f).dp))
    }
}

/** « Recommandé » : petite pastille à côté du nom. */
@Composable
internal fun RecommendedBadge() {
    val palette = LocalDotPalette.current
    Text(stringResource(R.string.models_recommended), style = MaterialTheme.typography.labelSmall, color = palette.deep, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(palette.light).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** Télécharger : un rond teinté avec une flèche, comme un bouton (et non une simple icône grise). */
@Composable
internal fun DownloadButton(enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalDotPalette.current
    Box(Modifier.size(32.dp).clip(CircleShape).background(if (enabled) palette.light else Ink.Fill)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.FileDownload, stringResource(R.string.local_download), tint = if (enabled) palette.deep else Ink.Tertiary,
            modifier = Modifier.size(18.dp))
    }
}

/** Téléchargement en cours : l'anneau se remplit ; un toucher l'annule. */
@Composable
internal fun DownloadProgress(fraction: Float, onCancel: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onCancel), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(28.dp), color = LocalDotPalette.current.base,
            trackColor = Ink.Fill, strokeWidth = 2.5.dp)
        Icon(Icons.Rounded.Close, stringResource(R.string.local_cancel), tint = Ink.Secondary, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun ModelRow(@DrawableRes logo: Int, title: String, subtitle: String, onClick: () -> Unit, recommended: Boolean = false,
    trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LogoTile(logo)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (recommended) { Spacer(Modifier.width(8.dp)); RecommendedBadge() }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun PlainRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Ink.Primary, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
    }
}
