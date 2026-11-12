package app.dotshermes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette

/**
 * « Claydot veut… » : une feuille du bas, comme les autres feuilles de l'app (fond groupé, poignée).
 * En tête la mascotte et « Claydot veut », puis l'action avec son icône, son détail sur une carte
 * blanche, un grand bouton (« Faire », ou « Autoriser » pour un accès Android) et « Pas maintenant ».
 * Glisser vers le bas ou toucher à côté refuse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionSheet(title: String, detail: String, appearance: DotAppearance, onAllow: () -> Unit, onDeny: () -> Unit, access: Boolean = false) {
    val palette = LocalDotPalette.current
    ModalBottomSheet(onDismissRequest = onDeny, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Grouped, dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.Tertiary) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                ClayDot(palette, Modifier.size(40.dp), accessory = appearance.accessory, species = appearance.species, showShadow = false)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.phone_confirm_title), style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary)
            }
            Spacer(Modifier.height(12.dp))
            // La carte de l'action : icône, titre, puis le détail (le message, l'heure, ce qui sera fait…).
            val (iconKey, text) = app.dotshermes.data.direct.ToolKeys.splitTitle(title)
            Column(Modifier.fillMaxWidth().background(Ink.Raised, RoundedCornerShape(20.dp)).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    iconKey?.let(::toolIcon)?.let { icon ->
                        Box(Modifier.size(36.dp).background(palette.light, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = palette.deep, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Ink.Primary,
                        modifier = Modifier.weight(1f))
                }
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary, modifier = Modifier.padding(top = 10.dp))
                }
            }
            if (access) {
                Text(stringResource(R.string.permission_restricted), style = MaterialTheme.typography.labelSmall, color = Ink.Tertiary,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().height(50.dp).background(palette.base, CircleShape).clickable(role = Role.Button, onClick = onAllow),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(if (access) R.string.permission_allow else R.string.phone_do), style = MaterialTheme.typography.titleMedium,
                    color = palette.onBase)
            }
            Box(
                Modifier.fillMaxWidth().height(46.dp).padding(top = 4.dp).clickable(role = Role.Button, onClick = onDeny),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.permission_not_now), style = MaterialTheme.typography.titleMedium, color = Ink.Secondary)
            }
        }
    }
}
