package app.dotshermes.attach

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.content.FileProvider
import app.dotshermes.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Même limite que le plugin : au-delà, la trame WebSocket serait refusée. */
const val MAX_ATTACHMENT_BYTES = 20L * 1024 * 1024

class ImportedFile(val file: File, val name: String, val mime: String)

/**
 * Pièces jointes côté appareil : copie des fichiers choisis dans le stockage privé (le contenu
 * d'un `content://` peut disparaître), aperçus d'images et ouverture dans l'app adaptée.
 */
class Attachments(private val context: Context) {

    /** null si illisible ou trop lourd ; [onTooBig] prévient l'utilisateur. */
    suspend fun import(uri: Uri, onTooBig: () -> Unit): ImportedFile? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = context.getString(R.string.file_default_name)
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)?.takeIf { it.isNotBlank() } ?: name
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        if (size > MAX_ATTACHMENT_BYTES) {
            withContext(Dispatchers.Main) { onTooBig() }
            return@withContext null
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val dir = File(context.filesDir, "attachments/${UUID.randomUUID()}").apply { mkdirs() }
        val target = File(dir, name.replace('/', '_'))
        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        }.isSuccess && target.exists()
        when {
            !copied -> null
            target.length() > MAX_ATTACHMENT_BYTES -> {
                target.delete()
                withContext(Dispatchers.Main) { onTooBig() }
                null
            }
            else -> ImportedFile(target, name, mime)
        }
    }

    /** Ouvre le fichier dans l'app qui sait le lire (galerie, lecteur PDF…). */
    fun open(file: File, mime: String) {
        val uri = try { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }
            catch (_: IllegalArgumentException) { Toast.makeText(context, R.string.file_no_app, Toast.LENGTH_SHORT).show(); return }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.file_no_app, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(context, R.string.file_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}

/** Aperçu réduit (jamais l'image pleine résolution en mémoire pour une bulle). */
fun decodePreview(file: File, maxSide: Int = 720): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** « 12 kB », « 1.4 MB » / « 12 ko », « 1,4 Mo » : unités et séparateur décimal de la langue du téléphone. */
fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
