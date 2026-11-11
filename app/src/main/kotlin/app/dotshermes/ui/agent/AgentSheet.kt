package app.dotshermes.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dotshermes.data.model.AgentProfile
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.ui.DEFAULT_AGENT_NAME
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.displayName
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.SectionLabel
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.dot.DotAccessory
import app.dotshermes.ui.dot.DotSpecies
import app.dotshermes.ui.dot.labelFor
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.DotPalette
import app.dotshermes.ui.theme.DotPalettes
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import app.dotshermes.ui.theme.ThemeMode
import androidx.compose.ui.res.stringResource
import app.dotshermes.R

/** Réglages du dot principal (dans la fiche des dots) : nom, connexion, affichage, apparence, déconnexion. */
@Composable
fun AgentSettings(
    profile: AgentProfile,
    connection: ConnectionState,
    appearance: DotAppearance,
    onName: (String) -> Unit,
    onSpecies: (DotSpecies) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onPalette: (DotPalette) -> Unit,
    onAccessory: (DotAccessory) -> Unit,
    /** Version installée et état de la mise à jour, pour la ligne « Version » ; un appui vérifie ou ouvre le dialogue. */
    appVersion: String,
    updateStatus: String,
    onUpdateRow: () -> Unit,
    onDisconnect: () -> Unit,
    /** Appairer un autre agent Hermes (un autre profil, un autre gateway). */
    onAddAgent: () -> Unit = {},
    /** Plusieurs agents : « Se déconnecter » devient « Retirer cet agent » (les autres restent). */
    multipleAgents: Boolean = false,
    gatewayFeatures: Boolean = true,
    onEditDirect: () -> Unit = {},
    /** Compte API direct : personnalité et voix (Fish Audio). */
    companion: CompanionControls? = null,
    /** Compte direct : effacer la conversation (après confirmation) ; null = ligne masquée. */
    onClearConversation: (() -> Unit)? = null,
    /** Compte direct : la liste des discussions. */
    onOpenChats: (() -> Unit)? = null,
    /** App web : scanner le QR code d'un navigateur, et la liste des navigateurs appairés (null : section masquée). */
    onConnectBrowser: (() -> Unit)? = null,
    onBrowserSessions: (() -> Unit)? = null,
) {
    val palette = LocalDotPalette.current
    val connected = connection as? ConnectionState.Connected

    run {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Compte direct : le modèle est déjà dans la carte « Compte ».
            if (gatewayFeatures) Text(profile.tagline, style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary)

            SectionLabel(stringResource(R.string.settings_name))
            GroupedCard {
                NameField(appearance.name, onName)
            }

            if (!gatewayFeatures) {
                // Compte direct : une seule carte, sans jargon (fournisseur et modèle, effacer le fil).
                SectionLabel(stringResource(R.string.settings_account))
                GroupedCard {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onEditDirect).padding(horizontal = 16.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(connected?.gatewayUrl ?: "—", style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
                        Text(profile.tagline, style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        Text("  ›", style = MaterialTheme.typography.bodyLarge, color = Ink.Tertiary)
                    }
                    onOpenChats?.let {
                        Hairline()
                        LinkRow(stringResource(R.string.chats_title), it)
                    }
                    onClearConversation?.let {
                        Hairline()
                        Text(stringResource(R.string.chat_clear), style = MaterialTheme.typography.bodyLarge, color = Ink.Danger,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = it).padding(horizontal = 16.dp, vertical = 13.dp))
                    }
                }
            }

            if (gatewayFeatures) {
            SectionLabel(stringResource(R.string.settings_connection))
            GroupedCard {
                InfoRow(stringResource(if (gatewayFeatures) R.string.connect_gateway else R.string.api_provider_label), connected?.gatewayUrl ?: "—")
                Hairline()
                if (gatewayFeatures) InfoRow(stringResource(R.string.settings_latency), connected?.let { stringResource(R.string.latency_ms, it.latencyMs) } ?: "—")
                else InfoRow(stringResource(R.string.api_model_label), profile.tagline)
                Hairline()
                InfoRow(stringResource(R.string.settings_encryption), if (!gatewayFeatures) "HTTPS" else if (connected != null) "TLS" else "—")
                Hairline()
                InfoRow(stringResource(R.string.settings_source), stringResource(if (!gatewayFeatures) R.string.api_source else R.string.settings_source_gateway))
                if (connected != null) {
                    Hairline()
                    Text(
                        stringResource(R.string.agents_add_row),
                        style = MaterialTheme.typography.bodyLarge,
                        color = palette.deep,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onAddAgent).padding(horizontal = 16.dp, vertical = 13.dp),
                    )
                }
            }
            }

            if (gatewayFeatures && connected != null && onConnectBrowser != null && onBrowserSessions != null) {
                // Un navigateur reçoit tous les agents connectés, pas seulement celui-ci : section à part.
                SectionLabel(stringResource(R.string.web_section))
                GroupedCard {
                    LinkRow(stringResource(R.string.web_connect_row), onConnectBrowser)
                    Hairline()
                    LinkRow(stringResource(R.string.web_sessions_row), onBrowserSessions)
                }
            }

            SectionLabel(stringResource(R.string.settings_mascot))
            GroupedCard {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    DotSpecies.entries.forEach { species ->
                        SpeciesOption(species, appearance, selected = species == appearance.species) { onSpecies(species) }
                    }
                }
            }

            SectionLabel(stringResource(R.string.settings_display))
            GroupedCard {
                ThemePicker(appearance.theme, onTheme)
            }

            SectionLabel(stringResource(R.string.settings_color))
            GroupedCard {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DotPalettes.all.forEach { option ->
                        Swatch(option, selected = option.id == appearance.palette.id) { onPalette(option) }
                    }
                }
            }

            SectionLabel(stringResource(R.string.settings_accessory))
            GroupedCard {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    DotAccessory.entries.forEach { accessory ->
                        AccessoryOption(accessory, appearance.species, selected = accessory == appearance.accessory) { onAccessory(accessory) }
                    }
                }
            }

            // Compte direct (Claydot) : personnalité, voix et outils, sous l'apparence comme dans la fiche d'origine.
            if (!gatewayFeatures) {
                companion?.let { CompanionSettings(it) }
                // Les outils (Gmail) passent par les appels de fonction de Groq.
                if (connected?.gatewayUrl == app.dotshermes.data.direct.ApiProvider.Groq.title) {
                    GmailSettings(app.dotshermes.AppContainer.gmail)
                    PhoneSettings(app.dotshermes.AppContainer.phoneActions)
                    (app.dotshermes.AppContainer.agents.activeId.collectAsStateWithLifecycle().value?.let { app.dotshermes.AppContainer.agents.repo(it) } as? app.dotshermes.data.direct.DirectChatRepository)
                        ?.let { MemorySettings(it) }
                    RoutinesSettings(app.dotshermes.AppContainer.routines, app.dotshermes.AppContainer.agents.activeId.collectAsStateWithLifecycle().value)
                }
            }

            SectionLabel(stringResource(R.string.settings_app))
            GroupedCard {
                Box(Modifier.fillMaxWidth().clickable(onClick = onUpdateRow)) {
                    InfoRow(stringResource(R.string.settings_version, appVersion), updateStatus)
                }
            }

            Spacer(Modifier.height(24.dp))
            GroupedCard {
                if (!gatewayFeatures) {
                    Text(stringResource(R.string.agents_add_hermes), style = MaterialTheme.typography.labelLarge, color = palette.deep,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clickable(onClick = onAddAgent).padding(16.dp))
                    Hairline()
                }
                Text(
                    stringResource(if (!gatewayFeatures) R.string.api_delete_account else R.string.server_delete),
                    style = MaterialTheme.typography.labelLarge,
                    color = Ink.Danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onDisconnect).padding(16.dp),
                )
            }
        }
    }
}

/** Nom affiché dans l'en-tête, le champ de saisie et les notifications ; vide = nom par défaut. */
@Composable
private fun NameField(name: String, onName: (String) -> Unit) {
    val palette = LocalDotPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_agent_name), style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
        Spacer(Modifier.weight(1f).padding(horizontal = 8.dp))
        Box(Modifier.weight(1.4f), contentAlignment = Alignment.CenterEnd) {
            if (name.isEmpty()) {
                Text(DEFAULT_AGENT_NAME, style = MaterialTheme.typography.bodyLarge, color = Ink.Tertiary, textAlign = TextAlign.End)
            }
            BasicTextField(
                value = name,
                onValueChange = onName,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.deep, textAlign = TextAlign.End),
                cursorBrush = SolidColor(palette.base),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = LocalDotPalette.current.deep,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

@Composable
private fun Swatch(palette: DotPalette, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) palette.base else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(5.dp)
            .background(
                Brush.radialGradient(listOf(lerp(palette.base, Color.White, 0.4f), palette.base, palette.deep)),
                CircleShape,
            ),
    )
}

@Composable
private fun AccessoryOption(accessory: DotAccessory, species: DotSpecies, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalDotPalette.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) palette.light else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        ClayDot(palette, Modifier.size(64.dp), accessory = accessory, showShadow = false, species = species)
        Text(stringResource(accessory.labelFor(species)), style = MaterialTheme.typography.labelSmall, color = if (selected) palette.deep else Ink.Secondary)
    }
}

/** Chaque mascotte se présente dans sa propre couleur, pour qu'on la reconnaisse au premier coup d'œil. */
@Composable
private fun SpeciesOption(species: DotSpecies, appearance: DotAppearance, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalDotPalette.current
    val own = if (selected) appearance.palette else DotPalettes.all.firstOrNull { it.id == species.signaturePalette } ?: palette
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) palette.light else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        ClayDot(own, Modifier.size(76.dp), mood = if (selected) DotMood.Happy else DotMood.Idle,
            accessory = appearance.accessory, showShadow = false, species = species)
        Text(stringResource(species.label), style = MaterialTheme.typography.labelMedium, color = if (selected) palette.deep else Ink.Secondary)
    }
}

/** Clair / sombre / auto (suit le réglage du téléphone), en contrôle segmenté façon iOS. */
@Composable
private fun ThemePicker(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(12.dp)).background(Ink.Fill).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ThemeMode.entries.forEach { mode ->
            val on = mode == selected
            Text(
                stringResource(mode.label),
                style = MaterialTheme.typography.labelLarge,
                color = if (on) Ink.Primary else Ink.Secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) Ink.Raised else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}
