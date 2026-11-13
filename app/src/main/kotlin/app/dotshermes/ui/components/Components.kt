package app.dotshermes.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.DotsDimensions
import app.dotshermes.ui.theme.DotsMotion

/** Carte de liste groupée iOS (fond uni, coins arrondis, lignes séparées par [Hairline]). */
@Composable
fun GroupedCard(
    modifier: Modifier = Modifier,
    color: Color = Ink.Raised,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(DotsDimensions.CardRadius)).background(color), content = content)
}

@Composable
fun Hairline(startIndent: Dp = 16.dp) {
    Box(Modifier.padding(start = startIndent).fillMaxWidth().height(0.5.dp).background(Ink.Separator))
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Ink.Secondary,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

/**
 * Retour d'appui : l'élément descend à 0,97 dès que le doigt se pose (sans s'attarder pendant qu'on tient), puis
 * revient en ease-out au relâchement.
 */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f,
        if (pressed) androidx.compose.animation.core.tween(DotsMotion.Press) else androidx.compose.animation.core.tween(DotsMotion.Release, easing = androidx.compose.animation.core.LinearOutSlowInEasing),
        label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Bouton pilule sans ripple : retour tactile par léger rétrécissement, à la manière d'iOS. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color,
    content: Color,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(interaction)
            .heightIn(min = height)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.45f))
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, modifier = Modifier.padding(vertical = 12.dp))
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = DotsDimensions.IconButton,
    container: Color = Ink.Fill,
    tint: Color = Ink.Primary,
    enabled: Boolean = true,
    iconSize: Dp = size * 0.5f,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier.sizeIn(minWidth = DotsDimensions.TouchTarget, minHeight = DotsDimensions.TouchTarget)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.pressScale(interaction).size(size).clip(CircleShape).background(container)
            .then(if (focused) Modifier.border(1.dp, Ink.Secondary, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}
