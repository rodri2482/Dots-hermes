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
    /** Élément à droite du champ (la flèche d'une liste déroulante, par exemple). */
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalDotPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically) {
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
        trailing?.invoke()
    }
}
