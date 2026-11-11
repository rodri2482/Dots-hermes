package app.dotshermes.ui.connect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dotshermes.data.AgentSummary
import app.dotshermes.data.model.ConnectionState
import app.dotshermes.ui.displayName
import app.dotshermes.ui.DotAppearance
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.PillButton
import app.dotshermes.ui.dot.ClayDot
import app.dotshermes.ui.dot.DotMood
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import androidx.compose.ui.res.stringResource
import app.dotshermes.R
import app.dotshermes.ui.dot.mascotElement
import app.dotshermes.ui.theme.balanced

/**
 * Écran d'appairage avec le gateway d'un agent Hermes (plugin « dots »). Sert au premier agent, à
 * un agent de plus ([adding] : on peut annuler) et à ré-appairer l'agent affiché s'il ne répond plus
 * ([otherAgents] : on peut alors passer à un autre agent, qui lui est connecté).
 */
@Composable
fun ConnectScreen(
    connection: ConnectionState,
    appearance: DotAppearance,
    initialUrl: String,
    onConnect: (url: String, token: String) -> Unit,
    adding: Boolean = false,
    onCancelAdd: () -> Unit = {},
    otherAgents: List<AgentSummary> = emptyList(),
    onSelectAgent: (String) -> Unit = {},
    /** Lien discret vers l'autre façon de se connecter (une clé API Groq). */
    switchLabel: String? = null,
    onSwitch: () -> Unit = {},
) {
    val palette = LocalDotPalette.current
    // Clé : l'adresse proposée change quand on passe d'un agent à l'autre ou à un ajout.
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var token by rememberSaveable(initialUrl) { mutableStateOf("") }
    val connecting = connection is ConnectionState.Connecting

    val mood = when (connection) {
        is ConnectionState.Connecting -> DotMood.Thinking
        is ConnectionState.Failed -> DotMood.Sleeping
        is ConnectionState.Connected -> DotMood.Happy
        ConnectionState.Disconnected -> DotMood.Idle
    }

    ConnectLayout(
        appearance = appearance,
        mood = mood,
        title = if (adding) stringResource(R.string.agents_add_title) else appearance.displayName,
        tagline = stringResource(if (adding) R.string.agents_add_tagline else R.string.connect_tagline),
    ) { tight ->
        GroupedCard(color = Ink.Grouped) {
            FieldRow(stringResource(R.string.connect_gateway), url, { url = it }, enabled = !connecting,
                keyboardType = KeyboardType.Uri, placeholder = stringResource(R.string.connect_gateway_hint))
            Hairline()
            FieldRow(stringResource(R.string.connect_token), token, { token = it }, enabled = !connecting,
                secret = true, placeholder = stringResource(R.string.connect_token_hint))
        }
        if (!tight) {
            Text(stringResource(R.string.connect_token_help), style = MaterialTheme.typography.bodySmall,
                color = Ink.Secondary,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 16.dp))
        }
        if (connection is ConnectionState.Connecting || connection is ConnectionState.Failed) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(targetState = connection, contentKey = { it::class },
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                    label = "connect-status") { state ->
                    when (state) {
                        is ConnectionState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), color = palette.base, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(state.step, style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        is ConnectionState.Failed -> Text(state.reason, style = MaterialTheme.typography.bodyMedium,
                            color = Ink.Danger, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        else -> Unit
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        PillButton(
            text = stringResource(if (connecting) R.string.connect_connecting else R.string.connect_button),
            onClick = { onConnect(url, token) }, modifier = Modifier.fillMaxWidth(),
            container = palette.base, content = palette.onBase,
            enabled = !connecting && url.isNotBlank() && token.isNotBlank(), height = 54.dp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (adding) TextLink(stringResource(R.string.agents_add_cancel), enabled = true, onClick = onCancelAdd)
            switchLabel?.let { TextLink(it, enabled = !connecting, color = Ink.Secondary, onClick = onSwitch) }
        }
        if (!adding && otherAgents.isNotEmpty()) OtherAgentsLink(otherAgents, onSelectAgent)
    }
}

/**
 * Mise en page commune des écrans de connexion (celle de l'écran d'origine) : le personnage et le titre
 * prennent la place libre et rétrécissent sur un écran court ou avec le clavier ; le formulaire, lui,
 * ne défile jamais. [form] reçoit `tight` (écran court) pour masquer les textes d'aide.
 */
@Composable
internal fun ConnectLayout(
    appearance: DotAppearance,
    mood: DotMood,
    title: String,
    tagline: String,
    form: @Composable ColumnScope.(tight: Boolean) -> Unit,
) {
    val palette = LocalDotPalette.current
    BoxWithConstraints(
        Modifier.fillMaxSize().connectionBackground().statusBarsPadding()
            .navigationBarsPadding().imePadding().padding(horizontal = 20.dp),
    ) {
        val tight = maxHeight < 520.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val headerHeight = maxHeight
                val heroSize = (headerHeight - 104.dp).coerceIn(0.dp, 220.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (heroSize >= 48.dp) {
                        ClayDot(palette, Modifier.mascotElement().size(heroSize), mood = mood,
                            accessory = appearance.accessory, species = appearance.species)
                    }
                    if (headerHeight >= 44.dp) {
                        Text(title,
                            style = if (tight) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall,
                            color = Ink.Primary, textAlign = TextAlign.Center, maxLines = 1)
                    }
                    if (!tight && headerHeight >= 140.dp) {
                        Spacer(Modifier.height(6.dp))
                        Text(tagline, style = MaterialTheme.typography.bodyLarge.balanced(), color = Ink.Secondary,
                            textAlign = TextAlign.Center)
                    }
                }
            }
            form(tight)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** « Ou ouvrir un autre agent » : un lien qui déroule les agents connectés. */
@Composable
internal fun OtherAgentsLink(otherAgents: List<AgentSummary>, onSelectAgent: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextLink(stringResource(R.string.agents_switch_to), enabled = true, color = Ink.Secondary) { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            otherAgents.forEach { agent ->
                DropdownMenuItem(text = { Text(agent.appearance.displayName) }, onClick = {
                    expanded = false; onSelectAgent(agent.id)
                })
            }
        }
    }
}

@Composable
private fun Modifier.connectionBackground(): Modifier {
    val palette = LocalDotPalette.current
    return background(Ink.Surface).drawBehind {
        drawRect(Brush.radialGradient(
            listOf(palette.light, Ink.Surface.copy(alpha = 0f)),
            center = Offset(size.width / 2f, size.height * 0.24f),
            radius = size.width * 0.85f,
        ))
    }
}

@Composable
internal fun TextLink(text: String, enabled: Boolean, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (color == Color.Unspecified) LocalDotPalette.current.deep else color,
        maxLines = 1,
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
internal fun FieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String = "",
) {
    val palette = LocalDotPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Ink.Primary, modifier = Modifier.width(84.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = Ink.Tertiary, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Secondary),
                cursorBrush = SolidColor(palette.base),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
