package app.dotshermes.ui.dot

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * Transition d'élément partagé : la mascotte de l'écran de connexion « vole » jusqu'à l'en-tête de la
 * conversation (et inversement) au lieu de disparaître d'un côté pour réapparaître de l'autre.
 * La racine fournit ses deux portées ; ailleurs (aperçus, tests), le modificateur ne fait rien.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
class MascotTransition(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

val LocalMascotTransition = compositionLocalOf<MascotTransition?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.mascotElement(): Modifier {
    val scopes = LocalMascotTransition.current ?: return this
    return with(scopes.shared) {
        this@mascotElement.sharedElement(rememberSharedContentState(key = "mascot"), animatedVisibilityScope = scopes.visibility)
    }
}

/**
 * Entrée « en cascade » : chaque élément d'une rangée arrive un peu après le précédent (35 ms),
 * en glissant de quelques pixels. Interruptible : si la rangée se referme, rien n'attend.
 */
@Composable
fun Modifier.staggerIn(index: Int): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index.coerceAtMost(8) * 35L)
        progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    return graphicsLayer {
        alpha = progress.value
        translationX = (1f - progress.value) * 24f
    }
}
