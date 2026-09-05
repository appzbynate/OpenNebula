package org.opennebula.air.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

object BrandIcons {
    const val SIZE = IconSculpt.SIZE

    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun load(context: Context, item: WebAppItem): Bitmap {
        return cache.getOrPut(item.component) {
            installedIcon(context, item) ?: IconSculpt.web(item)
        }
    }

    fun rimFromColor(color: Int): Triple<Float, Float, Float> {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return Triple(r, g, b)
    }

    fun screenMirror(): Bitmap = cache.getOrPut("shell:screen") { IconSculpt.screen() }

    fun extendedDisplay(): Bitmap = cache.getOrPut("shell:extended") { IconSculpt.extended() }

    fun steamVr(): Bitmap = cache.getOrPut("shell:steamvr") { IconSculpt.steamVr() }
    fun remoteDesktop(): Bitmap = cache.getOrPut("shell:desktop") { IconSculpt.remoteDesktop() }

    internal fun knownPackage(item: WebAppItem, packageName: String): Boolean {
        return item.packages.any { it.equals(packageName, ignoreCase = true) }
    }

    private fun installedIcon(context: Context, item: WebAppItem): Bitmap? {
        val pm = context.packageManager
        for (pkg in item.packages) {
            val icon = runCatching { pm.getApplicationIcon(pkg) }.getOrNull() ?: continue
            return circularFill(icon, SIZE)
        }
        return null
    }

    internal fun circularFill(drawable: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val path = Path()
        path.addCircle(size * 0.5f, size * 0.5f, size * 0.5f, Path.Direction.CW)
        canvas.clipPath(path)
        val zoom = (size * 0.10f).toInt()
        drawable.setBounds(-zoom, -zoom, size + zoom, size + zoom)
        drawable.draw(canvas)
        return bmp
    }
}
