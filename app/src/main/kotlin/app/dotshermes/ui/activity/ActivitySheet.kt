package app.dotshermes.ui.activity

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dotshermes.data.model.ActivitySnapshot
import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.CronAction
import app.dotshermes.data.model.CronJob
import app.dotshermes.data.model.ScreenState
import app.dotshermes.data.model.ToolCall
import app.dotshermes.data.model.ToolState
import app.dotshermes.data.model.UsageWindow
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.chat.formatDuration
import app.dotshermes.ui.chat.icon
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.PillButton
import app.dotshermes.ui.components.SectionLabel
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.screen.hostOf
import app.dotshermes.ui.screen.rememberScreenBitmap
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import app.dotshermes.R
import app.dotshermes.ui.formatDate
import app.dotshermes.ui.formatTime

/**
 * Tout ce que fait l'agent, au même endroit : sa consommation mois par mois, le tour en cours (et de quoi l'arrêter), son
 * navigateur, ses tâches planifiées (pause, reprise, lancement) et les derniers échanges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitySheet(
    agentName: String,
    appearance: DotAppearance,
    activity: AgentActivity,
    snapshot: ActivitySnapshot?,
    liveTools: List<ToolCall>,
    turns: List<TurnSummary>,
    screen: ScreenState,
    usage: UsageWindow?,
    gatewayFeatures: Boolean = true,
    directUsage: app.dotshermes.data.direct.DirectApiUsage? = null,
    onUsage: (from: Instant, to: Instant) -> Unit,
    onRefresh: () -> Unit,
    onCron: (String, CronAction) -> Unit,
    onStop: () -> Unit,
    onOpenScreen: () -> Unit,
    onDismiss: () -> Unit,
    onHideTurn: (String) -> Unit = {},
    onClearRecent: () -> Unit = {},
) {
    // Les crons bougent tout seuls (prochaine exécution, statut) : on rafraîchit tant que la vue est ouverte.
    LaunchedEffect(gatewayFeatures) {
        if (!gatewayFeatures) return@LaunchedEffect
        while (true) {
            onRefresh()
            delay(20_000)
        }
    }
    val busy = activity != AgentActivity.Idle && activity != AgentActivity.Reacting

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Grouped,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.Tertiary) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.activity_title), style = MaterialTheme.typography.headlineSmall, color = Ink.Primary, modifier = Modifier.padding(start = 4.dp))

            SectionLabel(stringResource(R.string.activity_usage))
            GroupedCard { UsageCard(usage, onUsage) }
            // Compte direct : les limites du fournisseur ne s'affichent que lorsqu'elles bloquent l'envoi.
            if (!gatewayFeatures && (directUsage?.cooldownUntilMs ?: 0) > System.currentTimeMillis()) {
                Spacer(Modifier.height(12.dp))
                GroupedCard { DirectUsageCard(directUsage) }
            }

            // Compte direct au repos : la carte ne dirait que « disponible », on la retire.
            if (gatewayFeatures || busy) {
                SectionLabel(stringResource(R.string.activity_now))
                GroupedCard { NowCard(agentName, appearance, activity, busy, liveTools, onStop) }
            }

            if (screen.hasFrame) {
                SectionLabel(stringResource(R.string.activity_browser))
                GroupedCard { BrowserCard(screen, onOpenScreen) }
            }

            if (gatewayFeatures) {
                SectionLabel(stringResource(R.string.activity_scheduled))
                GroupedCard {
                    val crons = snapshot?.crons.orEmpty().sortedWith(compareBy<CronJob> { it.finished }.thenBy { it.nextRunAt ?: Instant.MAX })
                    when {
                        snapshot == null -> Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = LocalDotPalette.current.base, strokeWidth = 2.dp)
                        }
                        crons.isEmpty() -> EmptyLine(stringResource(R.string.activity_no_crons))
                        else -> crons.forEachIndexed { i, job ->
                            if (i > 0) Hairline()
                            CronRow(job, onCron)
                        }
                    }
                }

            }

            SectionLabel(stringResource(R.string.activity_recent))
            GroupedCard {
                if (turns.isEmpty()) EmptyLine(stringResource(R.string.activity_nothing))
                turns.forEachIndexed { i, turn ->
                    if (i > 0) Hairline()
                    TurnRow(turn, onHide = { onHideTurn(turn.id) })
                }
                if (turns.size > 1) {
                    Hairline()
                    Text(stringResource(R.string.activity_clear_recent), style = MaterialTheme.typography.bodyLarge, color = Ink.Danger,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onClearRecent).padding(horizontal = 16.dp, vertical = 13.dp))
                }
            }
        }
    }
}

@Composable
private fun NowCard(agentName: String, appearance: DotAppearance, activity: AgentActivity, busy: Boolean, tools: List<ToolCall>, onStop: () -> Unit) {
    val palette = LocalDotPalette.current
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClayDot(palette, Modifier.size(52.dp), mood = if (busy) app.dotshermes.ui.dot.DotMood.Thinking else app.dotshermes.ui.dot.DotMood.Idle,
                accessory = appearance.accessory, species = appearance.species, showShadow = false)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(agentName, style = MaterialTheme.typography.titleSmall, color = Ink.Primary)
                app.dotshermes.ui.components.ToolStatusLine(
                    when (activity) {
                        AgentActivity.Thinking -> stringResource(R.string.status_thinking)
                        AgentActivity.Typing -> stringResource(R.string.activity_typing_reply)
                        is AgentActivity.Working -> activity.label
                        else -> stringResource(R.string.activity_idle)
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (busy) palette.deep else Ink.Secondary,
                )
            }
            if (busy) {
                PillButton(stringResource(R.string.action_stop), onStop, container = Ink.Danger.copy(alpha = 0.12f), content = Ink.Danger, height = 36.dp)
            }
        }
        if (busy && tools.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            tools.takeLast(5).forEach { call ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(call.kind.icon(), null, tint = palette.deep, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(call.label, style = MaterialTheme.typography.labelLarge, color = Ink.Primary)
                    Text("  ${call.detail}", style = MaterialTheme.typography.labelSmall, color = Ink.Secondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (call.state == ToolState.Running) {
                        CircularProgressIndicator(Modifier.size(12.dp), color = palette.base, strokeWidth = 1.5.dp)
                    } else {
                        Text(call.durationMs?.let(::formatDuration).orEmpty(), style = MaterialTheme.typography.labelSmall, color = Ink.Tertiary)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserCard(screen: ScreenState, onOpen: () -> Unit) {
    val bitmap = rememberScreenBitmap(screen)
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bitmap != null) {
            Image(bitmap, null, Modifier.width(112.dp).aspectRatio(bitmap.width / bitmap.height.toFloat()).clip(RoundedCornerShape(10.dp)))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(screen.title.ifBlank { hostOf(screen.url) }, style = MaterialTheme.typography.labelLarge, color = Ink.Primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(hostOf(screen.url), style = MaterialTheme.typography.labelSmall, color = Ink.Secondary, maxLines = 1)
            Text(stringResource(R.string.activity_watch_screen), style = MaterialTheme.typography.labelMedium, color = LocalDotPalette.current.deep, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun CronRow(job: CronJob, onCron: (String, CronAction) -> Unit) {
    val palette = LocalDotPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val statusColor = when {
            job.lastStatus == null -> Ink.Tertiary
            job.lastStatus == "ok" -> Ink.Success
            else -> Ink.Danger
        }
        Box(Modifier.size(8.dp).background(statusColor, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(job.name, style = MaterialTheme.typography.labelLarge, color = if (job.finished) Ink.Secondary else Ink.Primary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(cronSubtitle(job), style = MaterialTheme.typography.labelSmall, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!job.finished) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(palette.light).clickable { onCron(job.id, CronAction.Run) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, stringResource(R.string.cron_run_now), tint = palette.deep, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = !job.paused,
                onCheckedChange = { on -> onCron(job.id, if (on) CronAction.Resume else CronAction.Pause) },
                colors = SwitchDefaults.colors(checkedTrackColor = palette.base, checkedThumbColor = Color.White),
            )
        }
    }
}

@Composable
private fun TurnRow(turn: TurnSummary, onHide: () -> Unit) {
    val palette = LocalDotPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val request = turn.request.ifBlank {
                if (turn.voiceNote) stringResource(R.string.voice_message) else stringResource(R.string.turn_untitled)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Vocal ou pièce jointe : une petite icône devant, plus d'emoji.
                when (turn.media) {
                    "voice" -> Icon(androidx.compose.material.icons.Icons.Rounded.Mic, null, tint = Ink.Secondary, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                    "file" -> Icon(androidx.compose.material.icons.Icons.Rounded.AttachFile, null, tint = Ink.Secondary, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                }
                Text(request, style = MaterialTheme.typography.labelLarge, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val parts = listOfNotNull(
                relative(turn.at),
                if (turn.toolCount > 0) pluralStringResource(R.plurals.tool_actions, turn.toolCount, turn.toolCount) else null,
                if (turn.durationMs > 0) formatDuration(turn.durationMs) else null,
                if (turn.errors > 0) pluralStringResource(R.plurals.tool_failed, turn.errors, turn.errors) else null,
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = if (turn.errors > 0) Ink.Danger else Ink.Secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
            turn.kinds.forEach { kind ->
                Box(
                    Modifier.size(22.dp).clip(CircleShape).background(Ink.Raised).padding(1.5.dp).clip(CircleShape).background(palette.light),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(kind.icon(), null, tint = palette.deep, modifier = Modifier.size(12.dp))
                }
            }
        }
        if (turn.running) {
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(Modifier.size(14.dp), color = palette.base, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
        } else {
            // Retirer de « Récemment » : la conversation, elle, ne change pas.
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onHide), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Close, stringResource(R.string.activity_hide), tint = Ink.Tertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, modifier = Modifier.padding(16.dp))
}

@Composable
private fun cronSubtitle(job: CronJob): String = when {
    job.finished -> listOfNotNull(stringResource(R.string.cron_finished), job.lastRunAt?.let { relative(it) }).joinToString(" · ")
    job.paused -> stringResource(R.string.cron_paused) + " · " + humanSchedule(job.schedule)
    else -> listOfNotNull(humanSchedule(job.schedule), job.nextRunAt?.let { stringResource(R.string.cron_next, relative(it)) }).joinToString(" · ")
}

/** « every 30m » → « every 30 min » / « toutes les 30 min » : les cas courants de Hermes, sinon tel quel. */
@Composable
internal fun humanSchedule(raw: String): String {
    val every = Regex("""every (\d+)\s*(m|h|d)""").find(raw)
    if (every != null) {
        val (n, unit) = every.destructured
        val count = n.toInt()
        val plural = when (unit) {
            "m" -> R.plurals.schedule_every_minutes
            "h" -> R.plurals.schedule_every_hours
            else -> R.plurals.schedule_every_days
        }
        return pluralStringResource(plural, count, count)
    }
    return raw.replace("once in", stringResource(R.string.schedule_once_in))
        .replace("every day at", stringResource(R.string.schedule_every_day_at))
}

/** « in 12 min », « 2 h ago » / « dans 12 min », « il y a 2 h », sinon la date. */
@Composable
internal fun relative(at: Instant, now: Instant = Instant.now()): String {
    val delta = Duration.between(now, at)
    val minutes = delta.toMinutes()
    return when {
        minutes in 0..0 && !delta.isNegative -> stringResource(R.string.time_in_a_moment)
        minutes in 1..59 -> stringResource(R.string.time_in_minutes, minutes)
        minutes in 60..(18 * 60) -> stringResource(R.string.time_at, formatTime(at))
        minutes in -59..-1 -> stringResource(R.string.time_minutes_ago, -minutes)
        minutes == 0L -> stringResource(R.string.time_just_now)
        minutes in (-12 * 60)..-60 -> stringResource(R.string.time_hours_ago, -minutes / 60)
        else -> formatDate(at, "dMMMjmm")
    }
}
