package app.dotshermes.ui.chat

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
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
    onSearch: suspend (String) -> List<app.dotshermes.data.model.SearchHit> = { emptyList() },
    onOpenHit: (app.dotshermes.data.model.SearchHit) -> Unit = {},
) {
    val palette = LocalDotPalette.current
    var deleting by remember { mutableStateOf<ConversationInfo?>(null) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<app.dotshermes.data.model.SearchHit>?>(null) }
    // Recherche à la frappe, après une courte pause.
    androidx.compose.runtime.LaunchedEffect(query) {
        if (query.trim().length < 2) { hits = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        hits = runCatching { onSearch(query) }.getOrDefault(emptyList())
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ink.Grouped,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Ink.Tertiary) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            Text(stringResource(R.string.chats_title), style = MaterialTheme.typography.headlineSmall, color = Ink.Primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
            // Champ de recherche : tous les messages de toutes les discussions, sur le téléphone.
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Ink.Fill).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Search, null, tint = Ink.Secondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(stringResource(R.string.chats_search), style = MaterialTheme.typography.bodyLarge, color = Ink.Secondary)
                    androidx.compose.foundation.text.BasicTextField(query, { query = it }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink.Primary),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.base), modifier = Modifier.fillMaxWidth())
                }
                if (query.isNotEmpty()) Icon(androidx.compose.material.icons.Icons.Rounded.Close, null, tint = Ink.Secondary,
                    modifier = Modifier.size(20.dp).clip(CircleShape).clickable { query = "" })
            }
            Spacer(Modifier.size(12.dp))
            hits?.let { found ->
                if (found.isEmpty()) Text(stringResource(R.string.chats_search_none), style = MaterialTheme.typography.bodyMedium, color = Ink.Secondary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                else GroupedCard {
                    found.forEachIndexed { i, hit ->
                        if (i > 0) Hairline()
                        Column(Modifier.fillMaxWidth().clickable { onOpenHit(hit); onDismiss() }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(hit.conversationTitle.ifBlank { stringResource(R.string.chats_untitled) }, style = MaterialTheme.typography.labelLarge,
                                    color = palette.deep, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(formatDate(Instant.ofEpochMilli(hit.timeMs), "d MMM"), style = MaterialTheme.typography.labelSmall, color = Ink.Tertiary)
                            }
                            Text(androidx.compose.ui.text.buildAnnotatedString {
                                append(hit.snippet)
                                val end = (hit.matchStart + hit.matchLength).coerceAtMost(hit.snippet.length)
                                if (hit.matchStart in 0 until end) addStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    background = palette.light), hit.matchStart, end)
                            }, style = MaterialTheme.typography.bodyMedium, color = Ink.Primary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
                return@Column
            }
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
