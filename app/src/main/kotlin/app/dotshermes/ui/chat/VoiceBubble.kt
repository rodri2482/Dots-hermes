package app.dotshermes.ui.chat

import app.dotshermes.ui.theme.tabular
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import app.dotshermes.data.model.MessageContent
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import app.dotshermes.voice.Playback
import androidx.compose.ui.res.stringResource
import app.dotshermes.R

/**
 * Bulle vocale : lecture/pause, onde (la partie écoutée se colore, un tap y déplace la lecture),
 * durée restante. Sous la bulle, ce que Hermes a compris — la transcription de son STT.
 */
@Composable
fun VoiceBubble(
    messageId: String,
    voice: MessageContent.Voice,
    fromUser: Boolean,
    playback: Playback,
    onToggle: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    val palette = LocalDotPalette.current
    val mine = playback.messageId == messageId
    val progress = playback.progressOf(messageId)
    val container = if (fromUser) palette.base else Ink.Fill
    val ink = if (fromUser) palette.onBase else Ink.Primary
    val remaining = if (mine && playback.durationMs > 0) playback.durationMs - playback.positionMs else voice.durationMs

    Column(horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(container)
                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(if (fromUser) Color.White.copy(alpha = 0.22f) else Ink.Raised).clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = when {
                        mine && playback.loading -> 2
                        mine && playback.playing -> 1
                        else -> 0
                    },
                    transitionSpec = { (scaleIn(initialScale = 0.6f) + fadeIn()) togetherWith (scaleOut(targetScale = 0.6f) + fadeOut()) },
                    label = "voice-button",
                ) { state ->
                    when (state) {
                        2 -> CircularProgressIndicator(Modifier.size(16.dp), color = if (fromUser) ink else palette.base, strokeWidth = 2.dp)
                        1 -> Icon(Icons.Rounded.Pause, stringResource(R.string.cd_pause), tint = if (fromUser) ink else palette.deep, modifier = Modifier.size(22.dp))
                        // Centre optique : un triangle centré « par les maths » paraît trop à gauche.
                        else -> Icon(Icons.Rounded.PlayArrow, stringResource(R.string.cd_play), tint = if (fromUser) ink else palette.deep, modifier = Modifier.offset(x = 1.5.dp).size(24.dp))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Waveform(
                bars = voice.waveform,
                progress = progress,
                played = ink,
                pending = ink.copy(alpha = 0.38f),
                modifier = Modifier
                    .width(132.dp)
                    .height(28.dp)
                    .pointerInput(messageId, mine) { detectTapGestures { if (mine) onSeek(it.x / size.width) else onToggle() } },
            )
            Spacer(Modifier.width(10.dp))
            Text(formatClock(remaining), style = MaterialTheme.typography.labelMedium.tabular(), color = ink.copy(alpha = 0.85f))
        }
        AnimatedVisibility(visible = !voice.transcript.isNullOrBlank(), enter = expandVertically() + fadeIn()) {
            Text(
                stringResource(R.string.voice_transcript, voice.transcript.orEmpty()),
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = Ink.Secondary,
                modifier = Modifier.widthIn(max = 280.dp).padding(top = 4.dp, start = 6.dp, end = 6.dp),
            )
        }
    }
}

/** Barres arrondies ; celles déjà écoutées prennent la couleur pleine. */
@Composable
fun Waveform(bars: List<Float>, progress: Float, played: Color, pending: Color, modifier: Modifier = Modifier) {
    val values = bars.ifEmpty { List(32) { 0.15f } }
    Canvas(modifier) {
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1.5f)
        values.forEachIndexed { i, v ->
            val h = (size.height * v.coerceIn(0.1f, 1f)).coerceAtLeast(barWidth)
            val x = i * (barWidth + gap)
            val color = if ((i + 0.5f) / values.size <= progress) played else pending
            drawRoundRect(color, Offset(x, (size.height - h) / 2), Size(barWidth, h), CornerRadius(barWidth / 2))
        }
    }
}

/** Point rouge qui pulse pendant l'enregistrement. */
@Composable
fun RecordingDot() {
    val pulse by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "rec-alpha",
    )
    Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse }.background(Ink.Danger, CircleShape))
}

internal fun formatClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 500) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

