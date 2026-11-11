package app.dotshermes.data.direct

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Photo envoyée à un modèle de vision : réduite à [maxSide] px sur son plus grand côté, remise droite
 * (orientation EXIF), recompressée en JPEG. Une photo de téléphone (4 à 12 Mo) passe ainsi sous
 * 400 Ko, bien en dessous de la limite de Groq (4 Mo en base64), et coûte moins de jetons.
 */
fun downscaleJpeg(file: File, maxSide: Int = 1280, quality: Int = 82): ByteArray? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    decoded?.let { bitmap ->
        val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        val rotation = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val ready = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        ByteArrayOutputStream().use { out ->
            ready.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
    }
} catch (_: Exception) { null }
