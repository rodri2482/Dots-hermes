package app.dotshermes.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.direct.ApiException
import app.dotshermes.data.direct.ApiFailure
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.components.SectionLabel
import app.dotshermes.ui.connect.FieldRow
import app.dotshermes.ui.connect.apiFailureMessage
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import app.dotshermes.voice.CompanionState
import app.dotshermes.voice.CompanionVoice
import app.dotshermes.voice.Personality
import app.dotshermes.voice.SpeechStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Ce que l'écran des réglages peut faire de la personnalité et de la voix (comptes API directs). */
class CompanionControls(
    val state: CompanionState,
    val status: SpeechStatus,
    val onPersonality: (Personality) -> Unit,
    val onSpeak: (Boolean) -> Unit,
    val onVoiceId: (String) -> Unit,
    val onFishKey: suspend (String) -> Unit,
    val onForgetFishKey: () -> Unit,
)

/** Personnalité (Calme / Joueur / Taquin) puis voix : lire les réponses avec une voix Fish Audio. */
@Composable
fun CompanionSettings(controls: CompanionControls) {
    val state = controls.state
    val palette = LocalDotPalette.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var fishKey by remember { mutableStateOf("") }
    var keyFailure by remember { mutableStateOf<ApiFailure?>(null) }
    val ready = state.fishKeySaved && state.voiceId.isNotBlank()

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

        SectionLabel(stringResource(R.string.companion_voice))
        GroupedCard {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.companion_speak), style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
                    Text(stringResource(if (ready) R.string.companion_speak_hint else R.string.companion_speak_setup),
                        style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.speakReplies && ready, enabled = ready, onCheckedChange = controls.onSpeak,
                    colors = SwitchDefaults.colors(checkedTrackColor = palette.base, checkedThumbColor = Color.White))
            }
            Hairline()
            FieldRow(stringResource(R.string.companion_voice_id), state.voiceId, controls.onVoiceId, enabled = true,
                placeholder = stringResource(R.string.companion_voice_id_hint))
            Hairline()
            FieldRow(stringResource(R.string.companion_fish_key), fishKey, { fishKey = it; keyFailure = null }, enabled = true, secret = true,
                placeholder = stringResource(if (state.fishKeySaved) R.string.api_key_saved_hint else R.string.companion_fish_key_hint))
            if (fishKey.isNotBlank()) {
                Hairline()
                LinkRowText(stringResource(R.string.companion_fish_key_save)) {
                    scope.launch {
                        try { controls.onFishKey(fishKey.trim()); fishKey = "" }
                        catch (e: CancellationException) { throw e }
                        catch (e: ApiException) { keyFailure = e.failure }
                        catch (_: Exception) { keyFailure = ApiFailure.Storage }
                    }
                }
            } else if (state.fishKeySaved) {
                Hairline()
                LinkRowText(stringResource(R.string.companion_fish_key_forget), Ink.Danger, controls.onForgetFishKey)
            }
        }
        (keyFailure ?: controls.status.failure)?.let {
            Text(stringResource(apiFailureMessage(it)), style = MaterialTheme.typography.bodySmall, color = Ink.Danger,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp))
        }
        Text(
            stringResource(R.string.companion_fish_get_key),
            style = MaterialTheme.typography.labelLarge, color = palette.deep,
            modifier = Modifier.padding(start = 8.dp, top = 6.dp).clip(RoundedCornerShape(12.dp))
                .clickable { uri.openUri(CompanionVoice.FISH_KEYS_URL) }.padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun LinkRowText(label: String, color: Color = LocalDotPalette.current.deep, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyLarge, color = color,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp))
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
