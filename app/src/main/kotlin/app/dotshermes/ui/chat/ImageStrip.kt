package app.dotshermes.ui.chat

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.dotshermes.R
import app.dotshermes.attach.decodePreview
import app.dotshermes.data.model.ChatImage
import app.dotshermes.ui.theme.Ink
import app.dotshermes.ui.theme.LocalDotPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Chargement d'une image : en cours, prête, ou illisible (fichier absent ou abîmé). */
private sealed interface Picture {
    data object Loading : Picture
    data class Ready(val bitmap: ImageBitmap) : Picture
    data object Broken : Picture
}

@Composable
private fun rememberPicture(path: String, maxSide: Int): Picture {
    val state by produceState<Picture>(Picture.Loading, path, maxSide) {
        value = withContext(Dispatchers.IO) {
            runCatching { decodePreview(File(path), maxSide)?.asImageBitmap() }.getOrNull()?.let { Picture.Ready(it) } ?: Picture.Broken
        }
    }
    return state
}

/**
 * Les images d'une réponse (1 à 3), sous le texte : une seule prend la largeur de la bulle, deux ou trois se
 * partagent la ligne. Légende et lien vers la page d'origine dessous ; un toucher ouvre l'image en grand.
 */
@Composable
fun ImageStrip(images: List<ChatImage>, modifier: Modifier = Modifier) {
    var viewing by remember { mutableStateOf<ChatImage?>(null) }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { image ->
            ImageTile(image, ratio = if (images.size == 1) 16f / 10f else 0.85f, modifier = Modifier.weight(1f)) { viewing = image }
        }
    }
    viewing?.let { ImageViewer(it) { viewing = null } }
}

@Composable
private fun ImageTile(image: ChatImage, ratio: Float, modifier: Modifier, onOpen: () -> Unit) {
    val picture = rememberPicture(image.path, 720)
    val label = image.caption.ifBlank { stringResource(R.string.image_picture) }
    val openLabel = stringResource(R.string.image_open)
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Ink.Fill)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Ink.Fill)
            .clickable(enabled = picture is Picture.Ready, role = Role.Image, onClickLabel = openLabel, onClick = onOpen)
            .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            when (picture) {
                Picture.Loading -> CircularProgressIndicator(Modifier.size(20.dp), color = LocalDotPalette.current.base, strokeWidth = 2.dp, trackColor = Color.Transparent)
                is Picture.Ready -> Image(picture.bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Picture.Broken -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.BrokenImage, null, tint = Ink.Tertiary, modifier = Modifier.size(24.dp))
                    Text(stringResource(R.string.image_unavailable), style = MaterialTheme.typography.labelSmall, color = Ink.Tertiary,
                        modifier = Modifier.padding(top = 4.dp, start = 6.dp, end = 6.dp))
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
            if (image.caption.isNotBlank()) Text(image.caption, style = MaterialTheme.typography.labelLarge, color = Ink.Primary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            SourceLink(image.source)
        }
    }
}

/** Le site d'origine (« fandom.com »), qui s'ouvre dans le navigateur ; seulement une adresse http(s). */
@Composable
private fun SourceLink(source: String, modifier: Modifier = Modifier, color: Color = LocalDotPalette.current.deep) {
    val context = LocalContext.current
    val host = remember(source) { runCatching { source.toUri().host?.removePrefix("www.") }.getOrNull() }
    if (host.isNullOrBlank() || !(source.startsWith("https://") || source.startsWith("http://"))) return
    val label = stringResource(R.string.image_source, host)
    Text(host, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = label) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, source.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }.padding(vertical = 4.dp).semantics { contentDescription = label })
}

/** L'image en grand, sur fond noir : pincer pour zoomer, glisser pour se déplacer ; légende et source en bas. */
@Composable
private fun ImageViewer(image: ChatImage, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val picture = rememberPicture(image.path, 2048)
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        LaunchedEffect(image.path) { scale = 1f; offset = Offset.Zero }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (picture) {
                is Picture.Ready -> Image(picture.bitmap, image.caption.ifBlank { null }, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
                Picture.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 2.dp)
                Picture.Broken -> Text(stringResource(R.string.image_unavailable), color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
            Box(Modifier.statusBarsPadding().padding(12.dp).size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.5f))
                .clickable(role = Role.Button, onClick = onClose).align(Alignment.TopEnd), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, stringResource(R.string.action_close), tint = Color.White, modifier = Modifier.size(22.dp))
            }
            if (image.caption.isNotBlank() || image.source.isNotBlank()) Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (image.caption.isNotBlank()) Text(image.caption, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                Spacer(Modifier.height(2.dp))
                SourceLink(image.source, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}
