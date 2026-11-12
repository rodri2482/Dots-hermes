package app.dotshermes.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.SectionLabel
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import app.dotshermes.voice.CompanionState
import app.dotshermes.voice.Personality

/** Ce que l'écran des réglages peut faire de la personnalité (comptes API directs). */
class CompanionControls(
    val state: CompanionState,
    val onPersonality: (Personality) -> Unit,
)

/** Personnalité (Calme / Joueur / Taquin). La voix est retirée pour le moment. */
@Composable
fun CompanionSettings(controls: CompanionControls) {
    val state = controls.state
    val palette = LocalDotPalette.current

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.companion_personality))
        GroupedCard {
            Row(
                Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(12.dp)).background(Ink.Fill).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Personality.entries.forEach { level ->
                    val on = level == state.personality
                    Text(
                        stringResource(level.label),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) palette.deep else Ink.Secondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                            .background(if (on) Ink.Raised else Color.Transparent)
                            .clickable { controls.onPersonality(level) }
                            .padding(vertical = 9.dp),
                    )
                }
            }
            Text(stringResource(state.personality.hint), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
    }
}

private val Personality.label: Int get() = when (this) {
    Personality.Calm -> R.string.personality_calm
    Personality.Playful -> R.string.personality_playful
    Personality.Cheeky -> R.string.personality_cheeky
}

private val Personality.hint: Int get() = when (this) {
    Personality.Calm -> R.string.personality_calm_hint
    Personality.Playful -> R.string.personality_playful_hint
    Personality.Cheeky -> R.string.personality_cheeky_hint
}
