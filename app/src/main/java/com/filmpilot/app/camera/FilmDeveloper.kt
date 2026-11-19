package com.filmpilot.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.filmpilot.fx.FilmParams
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The result of developing one frame. */
class DevelopedPhoto(val uri: Uri, val thumbnail: Bitmap)

/**
 * "Develops" a captured JPEG: decode → film effects → JPEG with the original
 * camera metadata → gallery. Runs entirely off the main thread.
 */
class FilmDeveloper(private val context: Context) {

    suspend fun develop(jpeg: ByteArray, rotationDegrees: Int, params: FilmParams): DevelopedPhoto {
        val bitmap = decodeMutable(jpeg)
        try {
            BitmapFilm.apply(bitmap, params)

            val tmp = File(context.cacheDir, "develop-${System.nanoTime()}.jpg")
            try {
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                writeExif(source = jpeg, target = tmp, rotationDegrees = rotationDegrees)
                val name = "FP_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"
                val uri = GallerySaver.saveJpeg(context, tmp, name)
                return DevelopedPhoto(uri, thumbnail(bitmap, rotationDegrees))
            } finally {
                tmp.delete()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Full-resolution decode; halves the size if memory is tight rather than failing. */
    private fun decodeMutable(jpeg: ByteArray): Bitmap {
        var sample = 1
        while (true) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inMutable = true
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = sample
                }
                return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                    ?: error("Could not decode the captured image")
            } catch (oom: OutOfMemoryError) {
                if (sample >= 4) throw oom
                sample *= 2
            }
        }
    }

    /**
     * Pixels stay in sensor orientation (no extra full-size copy to rotate); the
     * EXIF orientation tag tells galleries how to display them. Camera metadata
     * (date, exposure, lens…) is copied from the original capture.
     */
    private fun writeExif(source: ByteArray, target: File, rotationDegrees: Int) {
        val src = runCatching { ExifInterface(ByteArrayInputStream(source)) }.getOrNull()
        val dst = ExifInterface(target)
        if (src != null) {
            for (tag in COPIED_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
        }
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, orientationFor(rotationDegrees).toString())
        dst.setAttribute(ExifInterface.TAG_SOFTWARE, "FilmPilot")
        dst.saveAttributes()
    }

    private fun thumbnail(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val scale = THUMB_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val m = android.graphics.Matrix().apply {
            postScale(scale, scale)
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    private fun orientationFor(degrees: Int) = when ((degrees % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private companion object {
        const val JPEG_QUALITY = 95
        const val THUMB_SIZE = 256
        val COPIED_TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
        )
    }
}
