package com.filmpilot.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.filmpilot.app.gl.GpuDeveloper
import com.filmpilot.fx.FilmParams
import com.filmpilot.fx.FrameFormat
import com.filmpilot.fx.LensParams
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything needed to develop one frame. */
class DevelopRequest(
    val film: FilmParams,
    val lens: LensParams,
    val format: FrameFormat,
    /** Date to imprint in the corner, or null for none. */
    val dateStamp: Date?,
    /** Return a reduced copy for half-frame pairing. */
    val keepForPair: Boolean,
)

/** The result of developing one frame. */
class DevelopedPhoto(val uri: Uri, val thumbnail: Bitmap, val pairHalf: Bitmap?)

/**
 * "Develops" a captured JPEG, in the order a real camera and lab would:
 *  1. frame: crop to the body's format and turn upright;
 *  2. lens: distortion, colour fringing, soft corners;
 *  3. date imprint (exposed onto the film from behind, so it gets the film look too);
 *  4. film: exposure, stock, vignette, grain;
 * then saves a JPEG with the original camera metadata to the gallery.
 *
 * Steps 2 and 4 run on the GPU; if that fails, the CPU reference pipeline takes over.
 */
class FilmDeveloper(private val context: Context, private val gpu: GpuDeveloper?) {

    /** Set after the GPU path fails once, so later frames go straight to the CPU. */
    @Volatile private var gpuFailed = false

    suspend fun develop(jpeg: ByteArray, rotationDegrees: Int, req: DevelopRequest): DevelopedPhoto {
        val start = SystemClock.elapsedRealtime()
        var frame = decodeFrame(jpeg, rotationDegrees, req.format)
        try {
            var path = "CPU"
            if (gpu != null && !gpuFailed) {
                try {
                    process(frame, req, gpu)
                    path = "GPU"
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU developing failed, using the CPU from now on", t)
                    gpuFailed = true
                    // The frame may be partly processed: start again from the original.
                    frame.recycle()
                    frame = decodeFrame(jpeg, rotationDegrees, req.format)
                }
            }
            if (path == "CPU") process(frame, req, null)
            Log.i(TAG, "Developed ${frame.width}x${frame.height} on $path in ${SystemClock.elapsedRealtime() - start} ms")

            val uri = save(frame, jpeg, suffix = "")
            Log.i(TAG, "Saved ${frame.width}x${frame.height}")
            return DevelopedPhoto(
                uri = uri,
                thumbnail = scaled(frame, THUMB_SIZE),
                pairHalf = if (req.keepForPair) scaled(frame, PAIR_HALF_SIZE) else null,
            )
        } finally {
            frame.recycle()
        }
    }

    /** Saves two half-frames side by side, as they sit on the negative. Recycles neither. */
    suspend fun savePair(first: Bitmap, second: Bitmap): Uri {
        val h = minOf(first.height, second.height)
        val w1 = first.width * h / first.height
        val w2 = second.width * h / second.height
        val gap = (h * 0.025f).toInt().coerceAtLeast(2)
        val pair = Bitmap.createBitmap(w1 + gap + w2 + 2 * gap, h + 2 * gap, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(pair)
            canvas.drawColor(Color.rgb(12, 11, 10)) // unexposed film base between frames
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(first, null, RectF(gap.toFloat(), gap.toFloat(), (gap + w1).toFloat(), (gap + h).toFloat()), paint)
            val x2 = (2 * gap + w1).toFloat()
            canvas.drawBitmap(second, null, RectF(x2, gap.toFloat(), x2 + w2, (gap + h).toFloat()), paint)
            return save(pair, exifSource = null, suffix = "_pair")
        } finally {
            pair.recycle()
        }
    }

    private suspend fun process(frame: Bitmap, req: DevelopRequest, gpu: GpuDeveloper?) {
        if (!req.lens.isNeutral) {
            if (gpu != null) gpu.applyLens(frame, req.lens) else BitmapFilm.applyLens(frame, req.lens)
        }
        req.dateStamp?.let { DateStamp.draw(frame, it) }
        if (gpu != null) gpu.develop(frame, req.film) else BitmapFilm.apply(frame, req.film)
    }

    /**
     * Decodes the capture, crops it to [format] and rotates it upright, in one copy.
     * The result is a mutable ARGB_8888 bitmap whose pixels are already in display orientation.
     */
    private fun decodeFrame(jpeg: ByteArray, rotationDegrees: Int, format: FrameFormat): Bitmap {
        val src = decodeMutable(jpeg)
        val crop = format.crop(src.width, src.height)
        val degrees = ((rotationDegrees % 360) + 360) % 360
        if (degrees == 0 && crop.width == src.width && crop.height == src.height) return src
        try {
            val m = Matrix()
            m.postTranslate(-crop.left.toFloat(), -crop.top.toFloat())
            m.postRotate(degrees.toFloat())
            val bounds = RectF(0f, 0f, crop.width.toFloat(), crop.height.toFloat())
            Matrix().apply { postRotate(degrees.toFloat()) }.mapRect(bounds)
            m.postTranslate(-bounds.left, -bounds.top)
            val rotated = degrees == 90 || degrees == 270
            val out = Bitmap.createBitmap(
                if (rotated) crop.height else crop.width,
                if (rotated) crop.width else crop.height,
                Bitmap.Config.ARGB_8888,
            )
            Canvas(out).drawBitmap(src, m, null)
            return out
        } finally {
            src.recycle()
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

    private suspend fun save(bitmap: Bitmap, exifSource: ByteArray?, suffix: String): Uri {
        val tmp = File(context.cacheDir, "develop-${System.nanoTime()}.jpg")
        try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            writeExif(exifSource, tmp)
            val name = "FP_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + suffix + ".jpg"
            return GallerySaver.saveJpeg(context, tmp, name)
        } finally {
            tmp.delete()
        }
    }

    /** Copies the original camera metadata (date, exposure, lens, GPS); pixels are already upright. */
    private fun writeExif(source: ByteArray?, target: File) {
        val src = source?.let { runCatching { ExifInterface(ByteArrayInputStream(it)) }.getOrNull() }
        val dst = ExifInterface(target)
        if (src != null) {
            for (tag in COPIED_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
        }
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        dst.setAttribute(ExifInterface.TAG_SOFTWARE, "FilmPilot")
        dst.saveAttributes()
    }

    private fun scaled(bitmap: Bitmap, longSide: Int): Bitmap {
        val scale = minOf(1f, longSide.toFloat() / maxOf(bitmap.width, bitmap.height))
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private companion object {
        const val TAG = "FilmPilot"
        const val JPEG_QUALITY = 95
        const val THUMB_SIZE = 256
        const val PAIR_HALF_SIZE = 2400
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
