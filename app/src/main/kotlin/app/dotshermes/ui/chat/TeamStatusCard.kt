package app.dotshermes.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.direct.*
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette

/** Une seule carte : effort, avancement, résultat du contrôle et vraies pages sources. */
@Composable
fun TeamStatusCard(report: TeamProgress?, effort: TeamEffort, busy: Boolean,
    onEffort: (TeamEffort) -> Unit, onStop: () -> Unit, showEffort: Boolean = true) {
    val uri = LocalUriHandler.current
    val accent = LocalDotPalette.current.deep
    var details by remember(report?.stage, report?.checker) { mutableStateOf(false) }
    GroupedCard {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.team_title), style = MaterialTheme.typography.labelLarge, color = Ink.Primary)
            if (showEffort) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TeamEffort.entries.forEach { choice ->
                    FilterChip(selected = choice == effort, enabled = !busy,
                        onClick = { onEffort(choice) }, label = {
                            Text(stringResource(if (choice == TeamEffort.Quick) R.string.team_quick else R.string.team_deep))
                        })
                }
            }
            if (showEffort) Text(stringResource(R.string.team_limits, effort.calls, effort.timeoutMs / 1000),
                style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
            report?.let { state ->
                val stage = when (state.stage) {
                    TeamStage.Planning -> R.string.team_planning
                    TeamStage.Research -> R.string.team_research
                    TeamStage.Discussion -> R.string.team_discussion
                    TeamStage.Checking -> R.string.team_checking
                    TeamStage.Correction -> R.string.team_correction
                    TeamStage.Done -> R.string.team_done
                    TeamStage.Stopped -> R.string.team_stopped
                }
                Text(stringResource(stage), style = MaterialTheme.typography.titleSmall, color = Ink.Primary)
                if (state.members.isNotEmpty()) Text(state.members.joinToString(" · "), color = Ink.Secondary,
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.team_calls, state.calls, state.limit), color = Ink.Secondary,
                    style = MaterialTheme.typography.bodySmall)
                if (state.running) TextButton(onClick = onStop) { Text(stringResource(R.string.team_stop), color = Ink.Danger) }
                state.verification?.let { status ->
                    Text(stringResource(when (status) {
                        TeamVerification.Verified -> R.string.team_verified
                        TeamVerification.Partial -> R.string.team_partial
                        TeamVerification.NeedsReview -> R.string.team_review
                    }), style = MaterialTheme.typography.labelLarge, color = if (status == TeamVerification.Verified) accent else Ink.Primary)
                    Text(stringResource(R.string.team_not_guaranteed), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                }
                state.limited?.let { limit -> Text(stringResource(when (limit) {
                    TeamLimit.Calls -> R.string.team_call_limit
                    TeamLimit.Time -> R.string.team_time_limit
                    TeamLimit.Rounds -> R.string.team_round_limit
                }),
                    style = MaterialTheme.typography.bodySmall, color = Ink.Danger) }
                if (state.sources.isNotEmpty()) {
                    Text(stringResource(R.string.team_sources), style = MaterialTheme.typography.labelMedium, color = Ink.Primary)
                    state.sources.take(if (details) 24 else 3).forEach { source ->
                        Text(source.title, style = MaterialTheme.typography.bodySmall, color = accent,
                            modifier = Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(source.url) } }.padding(vertical = 8.dp))
                    }
                }
                if (state.checker.isNotBlank() || state.sources.size > 3) {
                    TextButton(onClick = { details = !details }) {
                        Text(stringResource(if (details) R.string.team_less else R.string.team_details))
                    }
                    if (details && state.checker.isNotBlank()) Text(state.checker, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                }
            }
        }
    }
}
