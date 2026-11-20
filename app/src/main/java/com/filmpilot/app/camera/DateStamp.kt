package com.filmpilot.app.camera

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The orange date imprint of compact film cameras ('26 11 20), burned into the
 * bottom-right corner with a soft glow, as if exposed onto the film from behind.
 */
object DateStamp {
    /** e.g. '26 11 20. A new formatter each time: SimpleDateFormat isn't thread-safe. */
    fun text(date: Date): String = SimpleDateFormat("''yy MM dd", Locale.US).format(date)

    fun draw(bitmap: Bitmap, date: Date) {
        val short = minOf(bitmap.width, bitmap.height).toFloat()
        val size = short * 0.045f
        val margin = short * 0.05f
        val text = text(date)
        val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF9A2E.toInt()
            textSize = size
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.08f
            textAlign = Paint.Align.RIGHT
        }
        val glow = Paint(core).apply {
            color = 0xFFFF5A0A.toInt()
            maskFilter = BlurMaskFilter(size * 0.35f, BlurMaskFilter.Blur.NORMAL)
        }
        val canvas = Canvas(bitmap)
        val x = bitmap.width - margin
        val y = bitmap.height - margin
        canvas.drawText(text, x, y, glow)
        canvas.drawText(text, x, y, core)
    }
}
