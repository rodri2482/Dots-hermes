package app.dotshermes.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.dotshermes.R
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette

/**
 * « Claydot veut… » : la demande d'accord avant une action sur le téléphone. Une carte qui monte du
 * bas de l'écran, la mascotte, l'action en gros, son détail dans un encadré teinté, puis un grand
 * bouton « Faire » et « Pas maintenant » juste dessous. Toucher à côté refuse.
 */
@Composable
fun PermissionSheet(title: String, detail: String, appearance: DotAppearance, onAllow: () -> Unit, onDeny: () -> Unit) {
    val palette = LocalDotPalette.current
    val rise = remember { Animatable(80f) }
    LaunchedEffect(Unit) { rise.animateTo(0f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)) }
    Dialog(onDismissRequest = onDeny, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = onDeny, indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }),
            contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.navigationBarsPadding().padding(12.dp).widthIn(max = 480.dp).fillMaxWidth()
                    .graphicsLayer { translationY = rise.value * density; alpha = 1f - rise.value / 80f }
                    .background(Ink.Raised, RoundedCornerShape(28.dp))
                    // Les touchers dans la carte ne la ferment pas.
                    .clickable(enabled = false) {}
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ClayDot(palette, Modifier.size(58.dp), accessory = appearance.accessory, species = appearance.species, showShadow = false)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.phone_confirm_title), style = MaterialTheme.typography.labelLarge, color = Ink.Secondary)
                Spacer(Modifier.height(4.dp))
                // L'action en gros, avec son icône (réveil, SMS, écran…) au lieu d'un emoji.
                val (iconKey, text) = app.dotshermes.data.direct.ToolKeys.splitTitle(title)
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    iconKey?.let(::toolIcon)?.let { icon ->
                        Box(Modifier.size(30.dp).background(palette.light, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Icon(icon, null, tint = palette.deep, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(text, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), color = Ink.Primary,
                        textAlign = TextAlign.Center)
                }
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = Ink.Primary,
                        modifier = Modifier.fillMaxWidth().background(palette.light, RoundedCornerShape(16.dp)).padding(14.dp))
                }
                Spacer(Modifier.height(20.dp))
                Box(
                    Modifier.fillMaxWidth().height(52.dp).background(palette.base, RoundedCornerShape(26.dp))
                        .clickable(role = Role.Button, onClick = onAllow),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.phone_do), style = MaterialTheme.typography.titleMedium, color = palette.onBase)
                }
                Box(
                    Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp).clickable(role = Role.Button, onClick = onDeny),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.permission_not_now), style = MaterialTheme.typography.titleMedium, color = Ink.Secondary)
                }
                Text(stringResource(R.string.permission_hint, stringResource(R.string.phone_do)), style = MaterialTheme.typography.labelSmall,
                    color = Ink.Tertiary, textAlign = TextAlign.Center)
            }
        }
    }
}
