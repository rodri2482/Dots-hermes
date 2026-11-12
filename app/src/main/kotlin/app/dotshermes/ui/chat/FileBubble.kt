package app.dotshermes.ui.chat

import app.dotshermes.data.model.ChatImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dotshermes.attach.decodePreview
import app.dotshermes.attach.formatSize
import app.dotshermes.data.model.MessageContent
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.platform.LocalContext

/** Accès aux fichiers des bulles : récupération (locale ou gateway) et ouverture dans l'app adaptée. */
class AttachmentControls(
    val load: suspend (messageId: String) -> File?,
    val open: (File, mime: String) -> Unit,
)

/**
 * Pièce jointe. Une image s'affiche en aperçu (téléchargée d'office si elle vient d'ailleurs) ;
 * les autres fichiers prennent la forme d'une carte nom + taille. Un tap ouvre le fichier.
 */
@Composable
fun FileBubble(messageId: String, attachment: MessageContent.File, fromUser: Boolean, controls: AttachmentControls) {
    val palette = LocalDotPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var preview by remember(messageId) { mutableStateOf<ImageBitmap?>(null) }
    // Photo touchée : la visionneuse de Claydot, en plein écran.
    var viewing by remember(messageId) { mutableStateOf<File?>(null) }

    if (attachment.isImage) {
        LaunchedEffect(messageId, attachment.localPath) {
            val file = controls.load(messageId) ?: return@LaunchedEffect
            preview = withContext(Dispatchers.IO) { decodePreview(file)?.asImageBitmap() }
        }
    }

    fun open() {
        if (loading) return
        scope.launch {
            loading = true
            controls.load(messageId)?.let { file -> if (attachment.isImage) viewing = file else controls.open(file, attachment.mime) }
            loading = false
        }
    }

    viewing?.let { file -> ImageViewer(ChatImage(file.path, "", "")) { viewing = null } }
    Column(horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start) {
        if (attachment.isImage) {
            val image = preview
            Box(
                Modifier
                    .widthIn(max = 240.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Ink.Fill)
                    .clickable(onClick = ::open),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) {
                    Image(image, attachment.name, Modifier.width(240.dp).aspectRatio((image.width / image.height.coerceAtLeast(1).toFloat()).coerceIn(0.3f, 3f)),
                        contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.size(200.dp, 150.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = palette.base, strokeWidth = 2.dp)
                    }
                }
            }
        } else {
            val container = if (fromUser) palette.base else Ink.Fill
            val ink = if (fromUser) palette.onBase else Ink.Primary
            Row(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(container)
                    .clickable(onClick = ::open)
                    .padding(start = 10.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (fromUser) ink.copy(alpha = 0.18f) else palette.light),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = if (fromUser) ink else palette.base, strokeWidth = 2.dp)
                    } else {
                        Icon(fileIcon(attachment.mime, attachment.name), null, tint = if (fromUser) ink else palette.deep, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(attachment.name, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(typeLabel(attachment.mime, attachment.name), attachment.size.takeIf { it > 0 }?.let { formatSize(context, it) }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = ink.copy(alpha = 0.7f),
                    )
                }
            }
        }
        if (attachment.caption.isNotBlank()) {
            Text(
                attachment.caption,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.Primary,
                modifier = Modifier.widthIn(max = 280.dp).padding(top = 4.dp, start = 6.dp, end = 6.dp),
            )
        }
    }
}

private fun fileIcon(mime: String, name: String): ImageVector = when {
    mime == "application/pdf" || name.endsWith(".pdf", true) -> Icons.Rounded.PictureAsPdf
    mime.startsWith("image/") -> Icons.Rounded.Image
    mime.startsWith("video/") -> Icons.Rounded.Movie
    mime.startsWith("audio/") -> Icons.Rounded.MusicNote
    mime.startsWith("text/") -> Icons.Rounded.Description
    else -> Icons.Rounded.InsertDriveFile
}

private fun typeLabel(mime: String, name: String): String =
    name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase()
        ?: mime.substringAfter('/').uppercase().take(8)
