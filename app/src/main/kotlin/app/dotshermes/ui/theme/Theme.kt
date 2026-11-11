package app.dotshermes.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Palette du dot courant, déjà animée : changer de couleur fait glisser toute l'interface. */
val LocalDotPalette = compositionLocalOf { DotPalettes.Tangerine }

@Composable
fun DotsTheme(palette: DotPalette, dark: Boolean = false, content: @Composable () -> Unit) {
    // Avant le contenu : chaque couleur Ink lue pendant cette composition est déjà la bonne.
    Ink.dark = dark
    val spec = tween<Color>(durationMillis = 450)
    val base by animateColorAsState(palette.base, spec, label = "base")
    // En sombre, le pastel devient une teinte profonde de la couleur, et l'accent s'éclaircit pour rester lisible.
    val deep by animateColorAsState(if (dark) lerp(palette.base, Color.White, 0.3f) else palette.deep, spec, label = "deep")
    val light by animateColorAsState(if (dark) lerp(Color(0xFF1C1C20), palette.base, 0.26f) else palette.light, spec, label = "light")
    val onBase by animateColorAsState(palette.onBase, spec, label = "onBase")
    val animated = palette.copy(base = base, deep = deep, light = light, onBase = onBase)

    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = base,
        onPrimary = onBase,
        primaryContainer = light,
        secondary = deep,
        background = Ink.Surface,
        onBackground = Ink.Primary,
        surface = Ink.Raised,
        onSurface = Ink.Primary,
        surfaceVariant = Ink.Fill,
        onSurfaceVariant = Ink.Secondary,
        outline = Ink.Separator,
        outlineVariant = Ink.Separator,
        surfaceContainerLow = Ink.Grouped,
    )

    CompositionLocalProvider(LocalDotPalette provides animated) {
        MaterialTheme(colorScheme = scheme, typography = DotsTypography) {
            // Pas de voile gris au toucher, nulle part : ni sur les zones cliquables, ni sur les boutons Material.
            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.foundation.LocalIndication provides NoIndication,
                androidx.compose.material3.LocalRippleConfiguration provides null,
                content = content,
            )
        }
    }
}

/** Aucun effet visuel au toucher (remplace l'onde grise de Material). */
private object NoIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        object : androidx.compose.ui.Modifier.Node() {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0
}
