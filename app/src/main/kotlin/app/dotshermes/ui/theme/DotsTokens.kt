package app.dotshermes.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Mesures du messager d'origine, partagées sans changer sa silhouette. */
object DotsDimensions {
    val CardRadius = 18.dp
    val BubbleRadius = 20.dp
    val GroupedBubbleRadius = 6.dp
    val BubbleHorizontalPadding = 14.dp
    val BubbleVerticalPadding = 9.dp
    val ThreadMargin = 14.dp
    val OppositeGutter = 32.dp
    val PhoneBubbleMax = 296.dp
    val ExpandedBubbleMax = 720.dp
    val ExpandedWidth = 600.dp
    val HeaderMascot = 68.dp
    val HeaderSideMin = 100.dp
    val HeaderIdentityMax = 160.dp
    val ComposerHeight = 40.dp
    val ComposerRadius = 20.dp
    val ComposerAction = 32.dp
    val IconButton = 38.dp
    val TouchTarget = 48.dp
}

object DotsMotion {
    const val Press = 70
    const val Release = 180
    const val MessageEnter = 220
    const val MessageExit = 150
    const val StatusEnter = 200
    const val StatusExit = 150
    const val Palette = 450
}

/** La largeur réelle de la fenêtre, après les découpes ; jamais celle du seul écran physique. */
fun chatBubbleMaxWidth(availableWidth: Dp): Dp {
    val cap = if (availableWidth < DotsDimensions.ExpandedWidth) DotsDimensions.PhoneBubbleMax
        else minOf(availableWidth * 0.72f, DotsDimensions.ExpandedBubbleMax)
    val room = (availableWidth - DotsDimensions.ThreadMargin * 2 - DotsDimensions.OppositeGutter).coerceAtLeast(0.dp)
    return minOf(cap, room)
}

/** Calculée une seule fois par le fil, partagée par toutes ses bulles. */
val LocalChatBubbleMaxWidth = compositionLocalOf { DotsDimensions.PhoneBubbleMax }
