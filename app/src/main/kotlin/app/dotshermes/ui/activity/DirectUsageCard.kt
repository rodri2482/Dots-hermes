package app.dotshermes.ui.activity

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.direct.*
import app.dotshermes.ui.formatDate
import app.dotshermes.ui.theme.Ink
import kotlinx.coroutines.delay
import java.time.Instant

/** Les soldes sont une photo de la dernière réponse, jamais une estimation du forfait de l'utilisateur. */
@Composable
internal fun DirectUsageCard(usage: DirectApiUsage?) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(usage) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(listOfNotNull(usage?.provider?.title, usage?.model).joinToString(" · "),
            style = MaterialTheme.typography.titleSmall, color = Ink.Primary)
        val remaining = cooldownSeconds(usage?.cooldownUntilMs ?: 0, now)
        Text(if (remaining > 0) stringResource(R.string.api_retry_wait, remaining) else stringResource(R.string.api_limits_ready),
            style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary)
        val limits = usage?.limits
        if (limits == null) {
            Text(stringResource(R.string.api_limits_unknown), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
        } else {
            LimitLine(stringResource(R.string.api_limits_requests), limits.requestsRemaining, limits.requestLimit, limits.requestsResetAtMs, now)
            LimitLine(stringResource(R.string.api_limits_tokens), limits.tokensRemaining, limits.tokenLimit, limits.tokensResetAtMs, now)
            Text(stringResource(R.string.api_limits_observed,
                formatDate(Instant.ofEpochMilli(limits.observedAtMs), "MMM d, HH:mm:ss")),
                style = MaterialTheme.typography.labelSmall, color = Ink.Secondary)
            Text(stringResource(R.string.api_limits_shared), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
        }
    }
}

@Composable
private fun LimitLine(label: String, remaining: Long?, limit: Long?, resetAt: Long?, now: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
        val value = when {
            resetAt != null && now >= resetAt -> stringResource(R.string.api_limits_refresh)
            remaining == null -> stringResource(R.string.api_limits_not_reported)
            limit == null -> stringResource(R.string.api_limits_remaining, remaining)
            else -> stringResource(R.string.api_limits_balance, remaining, limit)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, color = Ink.Primary)
        resetAt?.takeIf { it > now }?.let {
            Text(stringResource(R.string.api_limits_resets, formatDate(Instant.ofEpochMilli(it), "MMM d, HH:mm:ss")),
                style = MaterialTheme.typography.labelSmall, color = Ink.Secondary)
        }
    }
}
