package org.opennebula.air.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap

/** Software glyph plates for hovered launcher names. */
object IconLabels {
    const val TEX_H = 64

    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun displayName(raw: String): String = raw.trim()

    fun render(name: String): Bitmap? {
        val text = displayName(name)
        if (text.isEmpty()) return null
        return cache.getOrPut(text) { paintPlate(text) }
    }

    private fun paintPlate(text: String): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF4F1EA.toInt()
            textSize = 32f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            letterSpacing = 0.10f
            isSubpixelText = true
        }
        val padX = 26f
        val measured = paint.measureText(text)
        val w = (measured + padX * 2f).toInt().coerceIn(72, 560)
        val h = TEX_H
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99101820.toInt()
        }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x33FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 1.4f
        }
        val top = 10f
        val bot = h - 10f
        val rr = (bot - top) * 0.48f
        canvas.drawRoundRect(2f, top, w - 2f, bot, rr, rr, plate)
        canvas.drawRoundRect(2f, top, w - 2f, bot, rr, rr, rim)
        val fm = paint.fontMetrics
        val ty = h / 2f - (fm.ascent + fm.descent) / 2f
        val tx = (w - measured) / 2f
        canvas.drawText(text, tx, ty, paint)
        return bmp
    }
}
