package app.dotshermes.ui.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.AgentSummary
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.SectionLabel
import app.dotshermes.ui.displayName
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette

/**
 * Le choix de l'agent Hermes, ouvert en touchant le nom dans l'en-tête (à partir de deux agents).
 *
 * Volontairement à part de la fiche des dots : un agent est un autre profil Hermes, sur son propre
 * gateway, avec sa mémoire, ses outils et ses clés ; un sous-dot n'est qu'une conversation de plus
 * dans le même agent. Chaque ligne montre donc l'adresse de son gateway, pas un carrousel de mascottes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMenu(
    agents: List<AgentSummary>,
    activeId: String?,
    onSelect: (String) -> Unit,
    /** Appairer un agent dont le gateway est déjà installé (rien n'est créé sur le serveur). */
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Grouped,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.Tertiary) },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            SectionLabel(stringResource(R.string.agents_title))
            GroupedCard {
                agents.forEachIndexed { index, agent ->
                    if (index > 0) Hairline(startIndent = 72.dp)
                    AgentRow(agent, selected = agent.id == activeId) { onSelect(agent.id) }
                }
            }
            Spacer(Modifier.size(16.dp))
            GroupedCard {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onAdd).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, null, tint = LocalDotPalette.current.deep, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.agents_add_row), style = MaterialTheme.typography.bodyLarge, color = LocalDotPalette.current.deep)
                }
            }
            Text(
                stringResource(R.string.agents_add_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Ink.Secondary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun AgentRow(agent: AgentSummary, selected: Boolean, onClick: () -> Unit) {
    val look = agent.appearance
    val connected = agent.connection as? ConnectionState.Connected
    val status = when {
        connected == null -> stringResource(R.string.conn_offline)
        !connected.online -> connected.reconnectReason ?: stringResource(R.string.conn_reconnecting)
        agent.busy -> stringResource(R.string.agents_busy)
        agent.url.isBlank() -> stringResource(R.string.conn_online)
        else -> stringResource(R.string.status_online)
    }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClayDot(
            look.palette, Modifier.size(48.dp),
            mood = when {
                connected == null || !connected.online -> DotMood.Sleeping
                agent.busy -> DotMood.Thinking
                else -> DotMood.Idle
            },
            accessory = look.accessory, species = look.species, showShadow = false,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(look.displayName, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$status · ${if (agent.url.isBlank()) connected?.gatewayUrl ?: "API" else gatewayLabel(agent.url)}",
                style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            selected -> Icon(Icons.Rounded.Check, null, tint = look.palette.deep, modifier = Modifier.size(22.dp))
            agent.unread > 0 -> Box(
                Modifier.heightIn(min = 22.dp).clip(CircleShape).background(Ink.Danger).padding(horizontal = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (agent.unread > 9) "9+" else "${agent.unread}", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}

/** « hermes.example.com/dots/ash » : le serveur et la route, sans le protocole ni le « /ws » final. */
internal fun gatewayLabel(url: String): String =
    url.trim().substringAfter("://").removeSuffix("/").removeSuffix("/ws")
