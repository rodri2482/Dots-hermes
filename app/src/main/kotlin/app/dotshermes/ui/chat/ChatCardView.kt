package app.dotshermes.ui.chat

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.direct.CardReply
import app.dotshermes.data.direct.ChatCards
import app.dotshermes.data.direct.ToolKeys
import app.dotshermes.data.model.CardKind
import app.dotshermes.data.model.CardStatus
import app.dotshermes.data.model.ChatCard
import app.dotshermes.ui.components.PillButton
import app.dotshermes.ui.components.toolIcon
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette

/** Réponse à une carte : fournie par l'écran de l'app (qui ouvre aussi les dialogues et réglages Android). */
val LocalCardReply = staticCompositionLocalOf<(ChatCard, CardReply) -> Unit> { { _, _ -> } }

private val CardShape = RoundedCornerShape(22.dp)

/**
 * Une carte dans le fil : question (choix simple ou multiple, réponse libre), accord avant une action, accès
 * Android ou Gmail à donner, main passée sur l'écran. Rien n'est présélectionné ; accepter et refuser ont le même poids.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatCardView(card: ChatCard, modifier: Modifier = Modifier) {
    val palette = LocalDotPalette.current
    val onReply = LocalCardReply.current
    // Un toucher suffit : les boutons se figent jusqu'à ce que la carte change d'état (pas de double envoi).
    var sent by remember(card.id, card.status) { mutableStateOf(false) }
    fun send(reply: CardReply) { if (!sent) { sent = true; onReply(card, reply) } }
    val (iconKey, title) = ToolKeys.splitTitle(card.question)

    Column(modifier.clip(CardShape).background(Ink.Fill).border(0.5.dp, Ink.Separator, CardShape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (card.open) palette.base else Ink.Tertiary, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(when (card.kind) {
                CardKind.Question -> R.string.clarify_question
                CardKind.Confirm -> R.string.approval_required
                CardKind.Access -> R.string.card_access
                CardKind.Handover -> R.string.card_handover
                CardKind.Switch -> R.string.card_switch
            }), style = MaterialTheme.typography.labelMedium, color = Ink.Secondary)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            iconKey?.let(::toolIcon)?.let { Icon(it, null, tint = palette.deep, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ink.Primary)
        }
        if (card.detail.isNotBlank()) {
            Text(card.detail, style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        when (card.status) {
            CardStatus.Pending -> when (card.kind) {
                CardKind.Question -> QuestionBody(card, enabled = !sent, onSend = { send(CardReply.Answer(it)) }, onSkip = { send(CardReply.Decline) })
                CardKind.Confirm -> TwoButtons(stringResource(R.string.action_decline), stringResource(R.string.action_approve), !sent,
                    onFirst = { send(CardReply.Decline) }, onSecond = { send(CardReply.Answer(ChatCards.APPROVE)) })
                CardKind.Access -> TwoButtons(stringResource(R.string.permission_not_now), stringResource(R.string.card_continue), !sent,
                    onFirst = { send(CardReply.Decline) }, onSecond = { send(CardReply.Continue) })
                CardKind.Handover -> TwoButtons(stringResource(R.string.action_stop), stringResource(R.string.takeover_done_answer), !sent,
                    onFirst = { send(CardReply.Decline) }, onSecond = { send(CardReply.Answer(ChatCards.DONE)) })
                CardKind.Switch -> TwoButtons(stringResource(R.string.permission_not_now), stringResource(R.string.card_switch_action), !sent,
                    onFirst = { send(CardReply.Decline) }, onSecond = { send(CardReply.Answer(ChatCards.APPROVE)) })
            }
            CardStatus.Waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = palette.base, strokeWidth = 2.dp, trackColor = palette.light)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.card_waiting), style = MaterialTheme.typography.bodySmall, color = Ink.Secondary, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge, color = palette.deep,
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { onReply(card, CardReply.Decline) }
                        .heightIn(min = 40.dp).padding(horizontal = 10.dp, vertical = 10.dp))
            }
            CardStatus.Answered -> StatusRow(Icons.Rounded.CheckCircle, when (card.kind) {
                CardKind.Question -> card.answer.orEmpty()
                CardKind.Confirm -> stringResource(R.string.approval_approved)
                CardKind.Access -> stringResource(R.string.card_granted)
                CardKind.Handover -> stringResource(R.string.takeover_resumed)
                CardKind.Switch -> stringResource(R.string.card_switched, card.question)
            }, palette.deep)
            CardStatus.Declined -> StatusRow(Icons.Rounded.Cancel, stringResource(when (card.kind) {
                CardKind.Confirm -> R.string.approval_declined
                CardKind.Access -> R.string.card_not_granted
                else -> R.string.card_declined
            }), Ink.Secondary)
            CardStatus.Expired -> StatusRow(Icons.Rounded.HourglassTop, stringResource(R.string.card_expired), Ink.Tertiary)
        }
    }
}

/** Les choix (rien de coché d'avance), « Écris ta réponse », puis Passer / Envoyer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionBody(card: ChatCard, enabled: Boolean, onSend: (String) -> Unit, onSkip: () -> Unit) {
    val palette = LocalDotPalette.current
    var picked by rememberSaveable(card.id) { mutableStateOf(listOf<String>()) }
    var typed by rememberSaveable(card.id) { mutableStateOf("") }
    if (card.options.isNotEmpty()) {
        if (card.multi) Text(stringResource(R.string.card_pick_several), style = MaterialTheme.typography.labelSmall, color = Ink.Tertiary,
            modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.options.forEach { option ->
                val on = option in picked
                val chip = Modifier.clip(CircleShape)
                    .background(if (on) palette.light else Ink.Surface)
                    .border(1.dp, if (on) palette.base else Ink.Separator, CircleShape)
                val select = if (card.multi) chip.toggleable(on, enabled = enabled, role = Role.Checkbox) {
                    picked = if (on) picked - option else picked + option
                } else chip.selectable(on, enabled = enabled, role = Role.RadioButton) { picked = if (on) emptyList() else listOf(option); typed = "" }
                Row(select.heightIn(min = 44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (on) { Icon(Icons.Rounded.Check, null, tint = palette.deep, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(option, style = MaterialTheme.typography.labelLarge, color = if (on) palette.deep else Ink.Primary)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
    if (card.custom) {
        val hint = stringResource(R.string.card_custom_hint)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Surface).border(1.dp, Ink.Separator, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics { contentDescription = hint }) {
            if (typed.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = Ink.Tertiary)
            BasicTextField(typed, { typed = it.take(ChatCard.MAX_ANSWER); if (!card.multi && it.isNotEmpty()) picked = emptyList() },
                enabled = enabled, textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink.Primary),
                cursorBrush = SolidColor(palette.base), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
    }
    val answer = (picked + listOfNotNull(typed.trim().takeIf { it.isNotEmpty() })).joinToString(", ")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton(stringResource(R.string.permission_not_now), onSkip, Modifier.weight(1f), container = Ink.Surface, content = Ink.Primary,
            enabled = enabled, height = 44.dp)
        PillButton(stringResource(R.string.card_send), { onSend(answer) }, Modifier.weight(1f), container = palette.base, content = palette.onBase,
            enabled = enabled && answer.isNotEmpty(), height = 44.dp)
    }
}

/** Deux choix de même poids : aucun n'est mis en avant (accord, accès, main passée). */
@Composable
private fun TwoButtons(first: String, second: String, enabled: Boolean, onFirst: () -> Unit, onSecond: () -> Unit) {
    val palette = LocalDotPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton(first, onFirst, Modifier.weight(1f), container = Ink.Surface, content = Ink.Primary, enabled = enabled, height = 44.dp)
        PillButton(second, onSecond, Modifier.weight(1f), container = Ink.Surface, content = Ink.Primary, enabled = enabled, height = 44.dp)
    }
}

@Composable
private fun StatusRow(icon: ImageVector, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
