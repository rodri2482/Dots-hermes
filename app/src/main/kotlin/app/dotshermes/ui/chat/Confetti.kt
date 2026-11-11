package app.dotshermes.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Un morceau de confetti : départ en bas au centre, lancé vers le haut, retombe en tournant. */
private class Piece(val angle: Float, val speed: Float, val spin: Float, val color: Color, val w: Float, val h: Float)

private val confettiColors = listOf(
    Color(0xFFFF6B6B), Color(0xFFFFC145), Color(0xFF4ECDC4), Color(0xFF5B8DEF), Color(0xFFB07CF5), Color(0xFF7ED957),
)

/**
 * Pluie de confettis quand Claydot (ou l'utilisateur) réagit avec 🎉. [trigger] change à chaque fête ;
 * 0 = rien. Dessinée en une seule passe sur un Canvas, sans bibliothèque ni recomposition par image.
 */
@Composable
fun ConfettiBurst(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger == 0) return
    val progress = remember(trigger) { Animatable(0f) }
    val pieces = remember(trigger) {
        val rnd = Random(trigger)
        List(70) {
            // Éventail de -60° à +60° autour de la verticale.
            val a = (-PI / 2 + (rnd.nextFloat() - 0.5f) * (2 * PI / 3)).toFloat()
            Piece(a, 0.9f + rnd.nextFloat() * 0.8f, (rnd.nextFloat() - 0.5f) * 1440f, confettiColors[rnd.nextInt(confettiColors.size)],
                6f + rnd.nextFloat() * 6f, 10f + rnd.nextFloat() * 8f)
        }
    }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(1800, easing = LinearEasing)) }
    Canvas(modifier) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val origin = Offset(size.width / 2f, size.height * 0.92f)
        val launch = size.height * 1.6f
        val gravity = size.height * 3.4f
        val alpha = if (t < 0.75f) 1f else (1f - t) / 0.25f
        val density = density
        pieces.forEach { p ->
            val x = origin.x + cos(p.angle) * p.speed * launch * t
            val y = origin.y + sin(p.angle) * p.speed * launch * t + gravity * t * t / 2f
            rotate(p.spin * t, Offset(x, y)) {
                drawRect(p.color.copy(alpha = alpha), Offset(x - p.w * density / 2, y - p.h * density / 2), Size(p.w * density, p.h * density))
            }
        }
    }
}
