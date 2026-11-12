package app.dotshermes.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import app.dotshermes.voice.VoicePresets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Ce que l'écran des réglages peut faire de la personnalité et de la voix (comptes API directs). */
class CompanionControls(
    val state: CompanionState,
    val status: SpeechStatus,
    val onPersonality: (Personality) -> Unit,
    val onSpeak: (Boolean) -> Unit,
    val onHandsFree: (Boolean) -> Unit = {},
    val onVoiceId: (String) -> Unit,
    val onFishKey: suspend (String) -> Unit,
    val onForgetFishKey: () -> Unit,
)

/** Personnalité (Calme / Joueur / Taquin) puis voix : lire les réponses avec une voix Fish Audio. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompanionSettings(controls: CompanionControls) {
    val state = controls.state
    val palette = LocalDotPalette.current
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var fishKey by remember { mutableStateOf("") }
    var keyFailure by remember { mutableStateOf<ApiFailure?>(null) }
    // La voix du téléphone suffit : la lecture marche aussi sans clé Fish Audio.
    val ready = state.voiceId.isNotBlank()

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
                    Text(stringResource(R.string.companion_hands_free), style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
                    Text(stringResource(R.string.companion_hands_free_hint), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.handsFree, onCheckedChange = controls.onHandsFree,
                    colors = SwitchDefaults.colors(checkedTrackColor = palette.base, checkedThumbColor = Color.White))
            }
            Hairline()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.companion_speak), style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
                    if (!state.fishKeySaved) {
                        Text(stringResource(R.string.companion_speak_setup), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Switch(checked = state.speakReplies && ready, enabled = ready, onCheckedChange = controls.onSpeak,
                    colors = SwitchDefaults.colors(checkedTrackColor = palette.base, checkedThumbColor = Color.White))
            }
            if (!state.fishKeySaved) {
                // Pas encore de clé : un seul champ, et le lien pour en obtenir une.
                Hairline()
                FieldRow(stringResource(R.string.companion_fish_key), fishKey, { fishKey = it; keyFailure = null }, enabled = true, secret = true,
                    placeholder = stringResource(R.string.companion_fish_key_hint))
                Hairline()
                if (fishKey.isNotBlank()) {
                    LinkRowText(stringResource(R.string.companion_fish_key_save)) {
                        scope.launch {
                            try { controls.onFishKey(fishKey.trim()); fishKey = "" }
                            catch (e: CancellationException) { throw e }
                            catch (e: ApiException) { keyFailure = e.failure }
                            catch (_: Exception) { keyFailure = ApiFailure.Storage }
                        }
                    }
                } else {
                    LinkRowText(stringResource(R.string.companion_fish_get_key)) { uri.openUri(CompanionVoice.FISH_KEYS_URL) }
                }
            } else {
                // Clé enregistrée : le choix de la voix ; « Autre » révèle l'identifiant, pour une voix à soi.
                var custom by remember { mutableStateOf(state.voiceId != app.dotshermes.voice.MASCOT_VOICE && VoicePresets.none { it.id == state.voiceId }) }
                Hairline()
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // D'abord : la voix de la mascotte (elle change avec la mascotte choisie).
                    VoiceChip(stringResource(R.string.companion_voice_mascot), on = !custom && state.voiceId == app.dotshermes.voice.MASCOT_VOICE) {
                        custom = false; controls.onVoiceId(app.dotshermes.voice.MASCOT_VOICE)
                    }
                    VoicePresets.forEach { preset ->
                        VoiceChip(preset.name, on = !custom && preset.id == state.voiceId,
                            onLongClick = { uri.openUri(CompanionVoice.voicePage(preset.id)) }) {
                            custom = false; controls.onVoiceId(preset.id)
                        }
                    }
                    VoiceChip(stringResource(R.string.companion_voice_other), on = custom) { custom = true }
                }
                if (custom) {
                    Hairline()
                    FieldRow(stringResource(R.string.companion_voice_id), state.voiceId, controls.onVoiceId, enabled = true,
                        placeholder = stringResource(R.string.companion_voice_id_hint))
                }
                Hairline()
                LinkRowText(stringResource(R.string.companion_fish_key_forget), Ink.Danger, controls.onForgetFishKey)
            }
        }
        (keyFailure ?: controls.status.failure)?.let {
            Text(stringResource(apiFailureMessage(it)), style = MaterialTheme.typography.bodySmall, color = Ink.Danger,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 16.dp))
        }
    }
}

/** Pastille de voix ; un appui long ouvre sa fiche Fish Audio pour l'écouter. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VoiceChip(label: String, on: Boolean, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val palette = LocalDotPalette.current
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) palette.onBase else palette.deep,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) palette.base else palette.light)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
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
