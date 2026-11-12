package app.dotshermes.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.direct.ApiProvider
import app.dotshermes.data.direct.DeviceTier
import app.dotshermes.data.direct.LocalModel
import app.dotshermes.data.direct.LocalModelState
import app.dotshermes.data.direct.LocalModels
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.SectionLabel
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette

internal fun gigabytes(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)

/**
 * « Ajouter un modèle » : une feuille simple au lieu de l'écran « Nouvel agent ». Les modèles du téléphone
 * (taille, ce qu'ils savent faire, celui conseillé pour ce téléphone) se téléchargent et s'utilisent d'ici ;
 * Groq se branche avec une clé.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddModelSheet(
    active: ActiveModel,
    groqModel: String?,
    localStates: Map<String, LocalModelState>,
    onPickGroq: (String) -> Unit,
    onPickLocal: (String) -> Unit,
    onDownload: (LocalModel) -> Unit,
    onCancelDownload: (LocalModel) -> Unit,
    onAddGroqKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val recommended = remember { DeviceTier.recommended(context)?.id }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Grouped, dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.Tertiary) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.models_title), style = MaterialTheme.typography.titleLarge, color = Ink.Primary,
                modifier = Modifier.padding(start = 4.dp))
            Text(stringResource(R.string.models_subtitle), style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))

            SectionLabel(stringResource(R.string.models_on_phone))
            GroupedCard {
                LocalModels.CATALOG.forEachIndexed { i, model ->
                    if (i > 0) Hairline(startIndent = 64.dp)
                    val state = localStates[model.id] ?: LocalModelState.Missing
                    val fits = DeviceTier.fits(context, model)
                    SheetRow(
                        logo = model.logo, title = model.name,
                        subtitle = "${gigabytes(model.sizeBytes)} · ${model.maker}",
                        note = stringResource(model.note),
                        recommended = model.id == recommended && fits,
                        warning = if (!fits) stringResource(R.string.models_needs_memory) else null,
                    ) {
                        val chosen = active.provider == ApiProvider.Local && active.model == model.id
                        when (state) {
                            LocalModelState.Ready -> if (chosen) InUse() else SmallButton(stringResource(R.string.models_use), filled = true) { onPickLocal(model.id); onDismiss() }
                            LocalModelState.Missing, is LocalModelState.Failed -> DownloadButton(enabled = fits) { onDownload(model) }
                            is LocalModelState.Downloading -> DownloadProgress(state.fraction) { onCancelDownload(model) }
                        }
                    }
                }
            }

            SectionLabel(stringResource(R.string.models_online))
            GroupedCard {
                SheetRow(
                    logo = R.drawable.logo_groq, title = groqModel?.substringAfterLast('/') ?: "Groq",
                    subtitle = "Groq · " + stringResource(R.string.model_online),
                    note = stringResource(R.string.models_groq_note),
                ) {
                    when {
                        groqModel == null -> SmallButton(stringResource(R.string.models_add_key), filled = false) { onDismiss(); onAddGroqKey() }
                        active.provider == ApiProvider.Groq -> InUse()
                        else -> SmallButton(stringResource(R.string.models_use), filled = true) { onPickGroq(groqModel); onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(
    @androidx.annotation.DrawableRes logo: Int, title: String, subtitle: String, note: String,
    recommended: Boolean = false, warning: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
        LogoTile(logo, size = 38)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Titre et bouton sur la même ligne ; tout le reste prend la largeur entière en dessous.
            Row(verticalAlignment = Alignment.CenterVertically) {
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
            Text(note, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, modifier = Modifier.padding(top = 4.dp))
            warning?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, color = Ink.Danger, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalDotPalette.current
    Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1,
        color = when { !enabled -> Ink.Tertiary; filled -> palette.onBase; else -> palette.deep },
        modifier = Modifier.clip(CircleShape).background(when { !enabled -> Ink.Fill; filled -> palette.base; else -> palette.light })
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp))
}

@Composable
private fun InUse() {
    val palette = LocalDotPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Icon(Icons.Rounded.Check, null, tint = palette.deep, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.local_in_use), style = MaterialTheme.typography.labelLarge, color = palette.deep)
    }
}
