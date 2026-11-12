package app.dotshermes.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dotshermes.data.model.ToolCall
import app.dotshermes.data.model.ToolKind
import app.dotshermes.data.model.ToolState
import app.dotshermes.data.model.ScreenState
import app.dotshermes.ui.screen.hostOf
import app.dotshermes.ui.screen.rememberScreenBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import java.util.Locale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import app.dotshermes.R

/**
 * Sur-message d'outils : une capsule discrète posée au-dessus de la réponse de l'agent, jamais une bulle.
 * En direct, elle montre l'outil en cours ; une fois le tour fini, elle se résume (« 3 actions · 2,4 s »)
 * et se déplie d'un tap en frise chronologique.
 */
@Composable
fun ToolTrail(
    row: ChatRow.Tools,
    modifier: Modifier = Modifier,
    /** Aperçu de l'écran du navigateur, quand ce sur-message contient de la navigation web. */
    screen: ScreenState? = null,
    onOpenScreen: () -> Unit = {},
) {
    val palette = LocalDotPalette.current
    var expanded by rememberSaveable(row.key) { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, label = "trail-chevron")
    val current = row.calls.lastOrNull { it.state == ToolState.Running }

    Column(modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp)) {
        Row(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(CircleShape)
                .background(Ink.Raised.copy(alpha = 0.78f))
                .border(0.5.dp, Ink.Separator, CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
                .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                .animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindStack(row.calls, running = current != null)
            Spacer(Modifier.width(8.dp))
            AnimatedContent(
                targetState = current?.id ?: "summary",
                transitionSpec = {
                    (slideInVertically { it / 2 } + fadeIn(tween(220))) togetherWith (slideOutVertically { -it / 2 } + fadeOut(tween(150)))
                },
                label = "trail-headline",
                modifier = Modifier.weight(1f, fill = false),
            ) { key ->
                val live = row.calls.firstOrNull { it.id == key }
                if (live != null) {
                    Headline(live.label, live.detail, palette.deep)
                } else {
                    val single = row.calls.singleOrNull()
                    if (single != null) Headline(single.label, single.detail, Ink.Primary)
                    else Headline(summary(row.calls), null, Ink.Primary)
                }
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand),
                tint = Ink.Secondary,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = chevron },
            )
        }
        if (screen != null && screen.hasFrame) ScreenThumbnail(screen, onOpenScreen)
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(0.86f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Ink.Grouped)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                row.calls.forEachIndexed { index, call -> TimelineStep(call, isFirst = index == 0, isLast = index == row.calls.lastIndex) }
            }
        }
    }
}

@Composable
private fun Headline(label: String, detail: String?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(app.dotshermes.ui.components.toolText(label), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
        if (!detail.isNullOrBlank()) {
            Text(
                "  $detail",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = Ink.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Jusqu'à trois pastilles d'icônes qui se chevauchent, une par famille d'outil utilisée. */
@Composable
private fun KindStack(calls: List<ToolCall>, running: Boolean) {
    val palette = LocalDotPalette.current
    val kinds = calls.asReversed().map { it.kind }.distinct().take(3)
    val pulse by rememberInfiniteTransition(label = "trail-pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "trail-pulse-alpha",
    )
    Box {
        kinds.asReversed().forEachIndexed { index, kind ->
            val front = index == kinds.lastIndex
            Box(
                Modifier
                    .offset(x = (index * 14).dp)
                    .size(24.dp)
                    .border(1.5.dp, Ink.Raised, CircleShape)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(if (front) palette.light else Ink.Fill)
                    .graphicsLayer { alpha = if (front && running) pulse else 1f },
                contentAlignment = Alignment.Center,
            ) {
                Icon(kind.icon(), contentDescription = null, tint = if (front) palette.deep else Ink.Secondary, modifier = Modifier.size(13.dp))
            }
        }
        Spacer(Modifier.width((24 + (kinds.size - 1) * 14).dp))
    }
}

@Composable
private fun TimelineStep(call: ToolCall, isFirst: Boolean, isLast: Boolean) {
    val palette = LocalDotPalette.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // Rail vertical : relie les pastilles, la frise se lit de haut en bas.
        Box(Modifier.width(26.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(1.5.dp).height(6.dp).background(if (isFirst) Color.Transparent else Ink.Separator))
                Box(Modifier.size(26.dp).clip(CircleShape).background(palette.light), contentAlignment = Alignment.Center) {
                    Icon(call.kind.icon(), contentDescription = null, tint = palette.deep, modifier = Modifier.size(14.dp))
                }
                Box(Modifier.width(1.5.dp).weight(1f).background(if (isLast) Color.Transparent else Ink.Separator))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(top = 8.dp, bottom = 8.dp)) {
            Text(app.dotshermes.ui.components.toolText(call.label), style = MaterialTheme.typography.labelLarge, color = Ink.Primary)
            if (call.detail.isNotBlank()) {
                Text(
                    call.detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = Ink.Secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.padding(top = 10.dp, start = 8.dp)) {
            when (call.state) {
                ToolState.Running -> CircularProgressIndicator(Modifier.size(14.dp), color = palette.base, strokeWidth = 2.dp, trackColor = palette.light)
                ToolState.Error -> Icon(Icons.Rounded.ErrorOutline, contentDescription = stringResource(R.string.cd_failed), tint = Ink.Danger, modifier = Modifier.size(16.dp))
                ToolState.Done -> Text(call.durationMs?.let(::formatDuration).orEmpty(), style = MaterialTheme.typography.labelSmall, color = Ink.Tertiary)
            }
        }
    }
}

@Composable
private fun summary(calls: List<ToolCall>): String {
    val total = calls.sumOf { it.durationMs ?: 0L }
    val failed = calls.count { it.state == ToolState.Error }
    return listOfNotNull(
        pluralStringResource(R.plurals.tool_actions, calls.size, calls.size),
        if (total > 0) formatDuration(total) else null,
        if (failed > 0) pluralStringResource(R.plurals.tool_failed, failed, failed) else null,
    ).joinToString(" · ")
}

/** « 1.2 s » / « 1,2 s », « 2 min 05 » : séparateur décimal de la langue du téléphone. */
internal fun formatDuration(ms: Long): String = when {
    ms < 60_000 -> String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)
    else -> "${ms / 60_000} min ${String.format(Locale.getDefault(), "%02d", (ms / 1000) % 60)}"
}

internal fun ToolKind.icon(): ImageVector = when (this) {
    ToolKind.Terminal -> Icons.Rounded.Terminal
    ToolKind.Read -> Icons.Rounded.Description
    ToolKind.Search -> Icons.Rounded.Search
    ToolKind.Write -> Icons.Rounded.EditNote
    ToolKind.Web -> Icons.Rounded.Language
    ToolKind.Skill -> Icons.Rounded.AutoStories
    ToolKind.Memory -> Icons.Rounded.Psychology
    ToolKind.Code -> Icons.Rounded.Code
    ToolKind.Delegate -> Icons.Rounded.Groups
    ToolKind.Plan -> Icons.Rounded.Checklist
    ToolKind.Schedule -> Icons.Rounded.Schedule
    ToolKind.Vision -> Icons.Rounded.Visibility
    ToolKind.Image -> Icons.Rounded.Image
    ToolKind.Audio -> Icons.Rounded.GraphicEq
    ToolKind.Send -> Icons.AutoMirrored.Rounded.Send
    ToolKind.Tool -> Icons.Rounded.Build
}

/** Vignette de l'écran du navigateur sous le sur-message : un tap ouvre la vue en direct. */
@Composable
private fun ScreenThumbnail(screen: ScreenState, onOpen: () -> Unit) {
    val bitmap = rememberScreenBitmap(screen) ?: return
    Box(
        Modifier
            .padding(top = 6.dp)
            .width(200.dp)
            .aspectRatio(bitmap.width / bitmap.height.toFloat())
            .clip(RoundedCornerShape(14.dp))
            .border(0.5.dp, Ink.Separator, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen),
    ) {
        Image(bitmap, stringResource(R.string.cd_browser_screen), Modifier.fillMaxWidth())
        Row(
            Modifier.align(Alignment.BottomStart).padding(6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (screen.live) {
                Box(Modifier.size(6.dp).background(Ink.Danger, CircleShape))
                Spacer(Modifier.width(5.dp))
            }
            Text(hostOf(screen.url), style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
        }
    }
}
