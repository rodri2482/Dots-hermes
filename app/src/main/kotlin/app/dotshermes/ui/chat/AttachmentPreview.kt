package app.dotshermes.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dotshermes.R
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * La pièce jointe choisie, au-dessus du champ, avant l'envoi (comme ChatGPT) : la vignette de la photo
 * ou le nom du fichier, et une croix pour la retirer. Le texte tapé ensuite part avec elle.
 */
@Composable
internal fun AttachmentPreview(uri: Uri, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview by produceState<Pair<ImageBitmap?, String>?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull().orEmpty()
            val image = if (resolver.getType(uri)?.startsWith("image/") == true) runCatching {
                // Vignette légère : on lit d'abord la taille, puis l'image réduite (~256 px).
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 256) sample *= 2
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                    ?.asImageBitmap()
            }.getOrNull() else null
            image to name
        }
    }
    Box(modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp)) {
        val image = preview?.first
        if (image != null) {
            Image(image, preview?.second, contentScale = ContentScale.Crop,
                modifier = Modifier.padding(top = 6.dp, end = 6.dp).size(76.dp).clip(RoundedCornerShape(14.dp)).border(0.5.dp, Ink.Separator, RoundedCornerShape(14.dp)))
        } else {
            Row(Modifier.padding(top = 6.dp, end = 6.dp).clip(RoundedCornerShape(14.dp)).background(Ink.Fill).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Description, null, tint = LocalDotPalette.current.deep, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(preview?.second.orEmpty().ifEmpty { "…" }, style = MaterialTheme.typography.labelLarge, color = Ink.Primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            }
        }
        Box(Modifier.align(Alignment.TopEnd).size(24.dp).clip(CircleShape).background(Ink.Primary).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, stringResource(R.string.attach_remove), tint = Ink.Surface, modifier = Modifier.size(14.dp))
        }
    }
}
