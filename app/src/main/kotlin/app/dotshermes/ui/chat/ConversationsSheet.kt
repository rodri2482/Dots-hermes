package app.dotshermes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.data.model.ConversationInfo
import app.dotshermes.ui.components.GroupedCard
import app.dotshermes.ui.components.Hairline
import app.dotshermes.ui.formatDate
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Les discussions d'un compte direct : la plus récente en haut, « Nouvelle discussion » au-dessus.
 * Un appui ouvre une discussion ; la corbeille la supprime (après confirmation, sans retour possible).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsSheet(
    conversations: List<ConversationInfo>,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalDotPalette.current
    var deleting by remember { mutableStateOf<ConversationInfo?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Grouped,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.Tertiary) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.chats_title), style = MaterialTheme.typography.headlineSmall, color = Ink.Primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            GroupedCard {
                Row(Modifier.fillMaxWidth().clickable { onNew(); onDismiss() }.padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(palette.base), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, tint = palette.onBase, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.chats_new), style = MaterialTheme.typography.bodyLarge, color = palette.deep)
                }
            }
            Spacer(Modifier.size(16.dp))
            GroupedCard {
                conversations.forEachIndexed { i, chat ->
                    if (i > 0) Hairline()
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(chat.id); onDismiss() }
                            .background(if (chat.open) palette.light.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(chat.title.ifBlank { stringResource(R.string.chats_untitled) }, style = MaterialTheme.typography.bodyLarge,
                                color = if (chat.open) palette.deep else Ink.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (chat.updatedMs > 0) Text(when (val day = Instant.ofEpochMilli(chat.updatedMs).atZone(ZoneId.systemDefault()).toLocalDate()) {
                                LocalDate.now() -> formatDate(Instant.ofEpochMilli(chat.updatedMs), "HH:mm")
                                else -> formatDate(Instant.ofEpochMilli(chat.updatedMs), if (day.year == LocalDate.now().year) "d MMM" else "d MMM yyyy")
                            }, style = MaterialTheme.typography.labelSmall, color = Ink.Secondary)
                        }
                        Box(Modifier.size(44.dp).clip(CircleShape).clickable { deleting = chat }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.chats_delete), tint = Ink.Tertiary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
    deleting?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.chats_delete_title)) },
            text = { Text(stringResource(R.string.chats_delete_warning)) },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(chat.id) }) { Text(stringResource(R.string.chats_delete), color = Ink.Danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
