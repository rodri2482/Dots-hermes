package app.dotshermes.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.model.ChatMessage
import app.dotshermes.data.model.MessageContent
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Glisser un message vers la gauche pour y répondre : la bulle suit le doigt
 * (avec un peu de résistance), une flèche « Répondre » apparaît, une vibration marque le seuil ;
 * relâchée au-delà, la réponse est préparée dans le champ.
 */
@Composable
fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val max = with(density) { 76.dp.toPx() }
    val trigger = with(density) { 56.dp.toPx() }
    var armed by remember { mutableStateOf(false) }
    val palette = LocalDotPalette.current
    Box(Modifier.fillMaxWidth().pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { armed = false },
            onDragEnd = {
                if (armed) onReply()
                armed = false
                scope.launch { offset.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)) }
            },
            onDragCancel = { armed = false; scope.launch { offset.animateTo(0f) } },
        ) { change, drag ->
            // Vers la gauche seulement : le décalage va de 0 à -max ; un glissement vers la droite ne fait rien.
            val next = (offset.value + drag * 0.55f).coerceIn(-max, 0f)
            if (next >= 0f && offset.value >= 0f) return@detectHorizontalDragGestures
            change.consume()
            if (!armed && -next >= trigger) { armed = true; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            else if (armed && -next < trigger) armed = false
            scope.launch { offset.snapTo(next) }
        }
    }) {
        val progress = (-offset.value / trigger).coerceIn(0f, 1f)
        // La flèche apparaît à droite, à l'endroit que la bulle libère.
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).size(30.dp)
            .graphicsLayer { alpha = progress; scaleX = 0.6f + 0.4f * progress; scaleY = 0.6f + 0.4f * progress }
            .clip(CircleShape).background(if (armed) palette.light else Ink.Fill), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.Reply, null, tint = palette.deep, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }) { content() }
    }
}

/** Texte cité d'un message (texte, ou légende d'une photo), court et sans balises markdown. */
fun quoteOf(message: ChatMessage): String? {
    val raw = when (val c = message.content) {
        is MessageContent.Text -> c.text
        is MessageContent.File -> c.caption.ifBlank { "📷" }
        else -> return null
    }
    val clean = raw.replace(Regex("[#*`>_]+"), "").replace(Regex("\\s+"), " ").trim()
    return clean.take(160).ifBlank { null }
}

/** Le bandeau « Répondre à … » au-dessus du champ de saisie. */
@Composable
fun ReplyBar(name: String, quote: String, onCancel: () -> Unit) {
    val palette = LocalDotPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Fill),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(44.dp).background(palette.base))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = palette.deep, maxLines = 1)
            Text(quote, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(8.dp)) {
            Icon(Icons.Rounded.Close, stringResource(R.string.reply_cancel), tint = Ink.Secondary, modifier = Modifier.size(18.dp))
        }
    }
}

/** La citation en tête d'une bulle : barre de couleur, texte sur deux lignes au plus. */
@Composable
fun QuoteBlock(quote: String, fromUser: Boolean, ink: Color) {
    Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(ink.copy(alpha = if (fromUser) 0.16f else 0.07f))) {
        Box(Modifier.width(3.dp).height(36.dp).background(ink.copy(alpha = 0.7f)))
        Spacer(Modifier.width(8.dp))
        Text(quote, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 10.dp, top = 4.dp, bottom = 4.dp))
    }
}
