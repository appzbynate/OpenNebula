package org.opennebula.air.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PanelFramebuffer(
    val width: Int = SpatialLayout.TEX_W,
    val height: Int = SpatialLayout.TEX_H,
) {
    val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val lock = Any()
    val generation = AtomicInteger(0)
    val cursorX = AtomicReference(0.5f)
    val cursorY = AtomicReference(0.5f)

    fun drawFrom(view: View) {
        synchronized(lock) {
            bitmap.eraseColor(Color.rgb(11, 16, 32))
            view.draw(canvas)
            generation.incrementAndGet()
        }
    }

    fun withBitmap(block: (Bitmap) -> Unit) {
        synchronized(lock) { block(bitmap) }
    }
}
