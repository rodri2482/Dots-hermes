package app.dotshermes.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dotshermes.R
import app.dotshermes.ui.chat.DownloadButton
import app.dotshermes.ui.chat.DownloadProgress
import app.dotshermes.ui.chat.gigabytes
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import app.dotshermes.voice.OfflineSpeech

/**
 * Sous « Voix » : la voix naturelle hors ligne et la dictée hors ligne (sherpa-onnx). Une ligne chacune,
 * avec sa taille ; télécharger, suivre la progression, ou supprimer une fois installée.
 */
@Composable
fun OfflineSpeechSettings(speech: OfflineSpeech) {
    val states by speech.states.collectAsStateWithLifecycle()
    Spacer(Modifier.padding(top = 12.dp))
    GroupedCard {
        PackRow(speech, OfflineSpeech.Pack.Voice, states, R.string.speech_voice_title, R.string.speech_voice_hint)
        Hairline()
        PackRow(speech, OfflineSpeech.Pack.Typing, states, R.string.speech_typing_title, R.string.speech_typing_hint)
    }
}

@Composable
private fun PackRow(speech: OfflineSpeech, pack: OfflineSpeech.Pack, states: Map<OfflineSpeech.Pack, OfflineSpeech.State>, title: Int, hint: Int) {
    val state = states[pack] ?: OfflineSpeech.State.Missing
    val palette = LocalDotPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = Ink.Primary)
            Text(stringResource(hint) + " · " + gigabytes(speech.sizeBytes(pack)), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary)
            if (state is OfflineSpeech.State.Failed) Text(stringResource(R.string.local_failed), style = MaterialTheme.typography.bodySmall, color = Ink.Danger)
        }
        Spacer(Modifier.width(8.dp))
        when (state) {
            OfflineSpeech.State.Missing, OfflineSpeech.State.Failed -> DownloadButton { speech.download(pack) }
            is OfflineSpeech.State.Downloading -> DownloadProgress(state.fraction) { speech.cancel(pack) }
            OfflineSpeech.State.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, tint = palette.deep, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.speech_delete), style = MaterialTheme.typography.labelLarge, color = Ink.Danger,
                    modifier = Modifier.clip(CircleShape).clickable { speech.delete(pack) }.padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }
    }
}
