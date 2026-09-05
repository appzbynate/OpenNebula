package org.opennebula.air.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Circular launcher tiles: brand-color disc filled with the official mark.
 * Corners stay transparent so the GL mesh can be a circle.
 */
object IconSculpt {
    const val SIZE = 192
    const val MAT_BASE = 0
    const val MAT_PLATE = 1

    data class Spec(
        val plateR: Float,
        val plateG: Float,
        val plateB: Float,
        val plateW: Float = 0.50f,
        val plateH: Float = 0.36f,
        val plateD: Float = 0.075f,
        val glyph: Glyph,
        val letter: String = "",
    )

    enum class Glyph {
        SCREEN, EXTENDED, STEAMVR, DESKTOP, YOUTUBE, PARSEC, TWITCH, DISCORD,
        SPOTIFY, REDDIT, XBOX, CHATGPT, PLEX, GOOGLE, LETTER,
    }

    fun screen(): Bitmap = bitmap(Spec(0.22f, 0.86f, 0.78f, 0.52f, 0.46f, 0.07f, Glyph.SCREEN))
    fun extended(): Bitmap = bitmap(Spec(0.45f, 0.70f, 0.98f, 0.54f, 0.40f, 0.07f, Glyph.EXTENDED))
    fun steamVr(): Bitmap = bitmap(Spec(0.10f, 0.12f, 0.16f, 0.54f, 0.40f, 0.07f, Glyph.STEAMVR))
    fun remoteDesktop(): Bitmap = bitmap(Spec(0.12f, 0.42f, 0.78f, 0.54f, 0.40f, 0.07f, Glyph.DESKTOP))

    fun web(item: WebAppItem): Bitmap {
        val c = item.color
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val glyph = when (item.id) {
            "youtube" -> Glyph.YOUTUBE
            "parsec" -> Glyph.PARSEC
            "twitch" -> Glyph.TWITCH
            "discord" -> Glyph.DISCORD
            "spotify" -> Glyph.SPOTIFY
            "reddit" -> Glyph.REDDIT
            "xbox" -> Glyph.XBOX
            "chatgpt" -> Glyph.CHATGPT
            "plex" -> Glyph.PLEX
            "google" -> Glyph.GOOGLE
            else -> Glyph.LETTER
        }
        val plate = when (glyph) {
            Glyph.YOUTUBE -> Spec(r, g, b, glyph = glyph)
            Glyph.TWITCH -> Spec(r, g, b, glyph = glyph)
            else -> Spec(r, g, b, glyph = glyph, letter = item.letter)
        }
        return bitmap(plate)
    }

    fun bitmap(spec: Spec, size: Int = SIZE): Bitmap {
        val pixels = raster(spec, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        val canvas = Canvas(bmp)
        drawGlyph(canvas, spec, size)
        return bmp
    }

    fun raster(spec: Spec, size: Int = SIZE): IntArray {
        val out = IntArray(size * size)
        val cx = size * 0.50f
        val cy = size * 0.50f
        val radius = size * 0.50f - 0.5f
        val white = spec.glyph == Glyph.GOOGLE
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d = sqrt(dx * dx + dy * dy)
                val edge = d - radius
                if (edge > 0.6f) {
                    out[y * size + x] = 0
                    continue
                }
                val nx = dx / radius
                val ny = dy / radius
                val light = (1.04f - nx * 0.08f - ny * 0.12f).coerceIn(0.86f, 1.08f)
                val r = if (white) 0.98f * light else spec.plateR * light
                val g = if (white) 0.98f * light else spec.plateG * light
                val b = if (white) 0.99f * light else spec.plateB * light
                val a = when {
                    edge <= -0.8f -> 255
                    else -> ((0.6f - edge) / 1.4f * 255f).toInt().coerceIn(0, 255)
                }
                out[y * size + x] = argb(a, r, g, b)
            }
        }
        return out
    }

    fun sdRoundBox(px: Float, py: Float, pz: Float, hx: Float, hy: Float, hz: Float, r: Float): Float {
        val bx = max(hx - r, 0.001f)
        val by = max(hy - r, 0.001f)
        val bz = max(hz - r, 0.001f)
        val qx = abs(px) - bx
        val qy = abs(py) - by
        val qz = abs(pz) - bz
        val ox = max(qx, 0f)
        val oy = max(qy, 0f)
        val oz = max(qz, 0f)
        val outside = sqrt(ox * ox + oy * oy + oz * oz)
        val inside = min(max(qx, max(qy, qz)), 0f)
        return outside + inside - r
    }

    fun sdRoundBox2(px: Float, py: Float, hx: Float, hy: Float, r: Float): Float {
        val bx = max(hx - r, 0.001f)
        val by = max(hy - r, 0.001f)
        val qx = abs(px) - bx
        val qy = abs(py) - by
        val ox = max(qx, 0f)
        val oy = max(qy, 0f)
        val outside = sqrt(ox * ox + oy * oy)
        val inside = min(max(qx, qy), 0f)
        return outside + inside - r
    }

    private fun march(origin: V, dir: V, spec: Spec, light: V, cam: V): Int {
        var t = 0f
        var hit = false
        var p = origin
        var mat = MAT_BASE
        for (i in 0 until 36) {
            val d = map(p, spec)
            mat = d.mat
            if (d.dist < 0.0018f) {
                hit = true
                break
            }
            t += d.dist
            if (t > 5.5f) break
            p = origin + dir * t
        }
        val shadow2 = contactShadow(origin, dir)
        if (!hit) {
            val a = (shadow2 * 255f).toInt().coerceIn(0, 110)
            return if (a <= 0) 0 else (a shl 24)
        }
        val n = normal(p, spec)
        val ndl = max(0.16f, n.dot(light))
        val view = (cam - p).norm()
        val half = (light + view).norm()
        val specAmt = if (ndl > 0.02f) {
            val shine = max(0f, n.dot(half))
            shine * shine * shine * shine * shine * 0.62f
        } else {
            0f
        }
        val ao = ao(p, n, spec)
        val sh = shadow(p, n, light, spec)
        val rim = (1f - max(0f, n.dot(view))).let { it * it * 0.14f }
        val under = if (n.y < 0f) 0.72f + 0.28f * (n.y + 1f) else 1f
        val albedo = V(
            0.90f * 0.86f + spec.plateR * 0.14f,
            0.92f * 0.86f + spec.plateG * 0.14f,
            0.96f * 0.86f + spec.plateB * 0.14f,
        )
        val lit = ndl * ao * sh * under
        var r = albedo.x * lit + specAmt + rim
        var g = albedo.y * lit + specAmt + rim
        var b = albedo.z * lit + specAmt + rim
        if (n.x < -0.15f || n.y < -0.2f) {
            val edge = 0.88f
            r *= edge
            g *= edge
            b *= edge
        }
        val top = max(0f, n.y) * 0.10f
        r += top
        g += top
        b += top * 0.95f
        val a = 255
        return argb(a, r, g, b)
    }

    private data class Hit(val dist: Float, val mat: Int)

    private fun map(p: V, spec: Spec): Hit {
        val depth = 0.19f + spec.plateD * 0.10f
        val glass = sdRoundBox(p.x, p.y, p.z, 0.70f, 0.70f, depth, 0.28f)
        return Hit(glass, MAT_BASE)
    }

    private fun normal(p: V, spec: Spec): V {
        val e = 0.0024f
        val dx = map(V(p.x + e, p.y, p.z), spec).dist - map(V(p.x - e, p.y, p.z), spec).dist
        val dy = map(V(p.x, p.y + e, p.z), spec).dist - map(V(p.x, p.y - e, p.z), spec).dist
        val dz = map(V(p.x, p.y, p.z + e), spec).dist - map(V(p.x, p.y, p.z - e), spec).dist
        return V(dx, dy, dz).norm()
    }

    private fun ao(p: V, n: V, spec: Spec): Float {
        var occ = 0f
        var w = 1f
        var t = 0.03f
        for (i in 0 until 4) {
            val d = map(p + n * t, spec).dist
            occ += w * max(0f, t - d) / t
            w *= 0.7f
            t += 0.045f
        }
        return (1f - occ * 0.55f).coerceIn(0.42f, 1f)
    }

    private fun shadow(p: V, n: V, light: V, spec: Spec): Float {
        var t = 0.03f
        var res = 1f
        val start = p + n * 0.02f
        for (i in 0 until 14) {
            val q = start + light * t
            val d = map(q, spec).dist
            res = min(res, 10f * d / t)
            t += d.coerceIn(0.02f, 0.14f)
            if (res < 0.08f || t > 2.8f) break
        }
        return res.coerceIn(0.38f, 1f)
    }

    private fun contactShadow(origin: V, dir: V): Float {
        val tHit = if (abs(dir.y) < 1e-4f) return 0f else {
            val planeY = -0.78f
            (planeY - origin.y) / dir.y
        }
        if (tHit < 0f || tHit > 6f) return 0f
        val q = origin + dir * tHit
        val d = sdRoundBox2(q.x * 0.92f, q.z * 0.55f + 0.12f, 0.70f, 0.22f, 0.16f)
        return exp(-max(d, 0f) * 7.5f) * 0.40f
    }

    private fun argb(a: Int, r: Float, g: Float, b: Float): Int {
        val rr = (r * 255f).toInt().coerceIn(0, 255)
        val gg = (g * 255f).toInt().coerceIn(0, 255)
        val bb = (b * 255f).toInt().coerceIn(0, 255)
        return (a shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    private fun drawGlyph(canvas: Canvas, spec: Spec, size: Int) {
        val glyph = spec.glyph
        val cx = size * 0.50f
        val cy = size * 0.50f
        val s = size * 0.52f
        val onDark = android.graphics.Color.argb(255, 248, 250, 252)
        val onLight = android.graphics.Color.argb(255, 24, 24, 27)
        val lum = spec.plateR * 0.2126f + spec.plateG * 0.7152f + spec.plateB * 0.0722f
        val mark = if (glyph == Glyph.GOOGLE || glyph == Glyph.PLEX || lum > 0.62f) onLight else onDark
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = mark
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = mark
            strokeWidth = size * 0.055f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        when (glyph) {
            Glyph.YOUTUBE -> {
                val tri = Path().apply {
                    moveTo(cx - s * 0.22f, cy - s * 0.28f)
                    lineTo(cx - s * 0.22f, cy + s * 0.28f)
                    lineTo(cx + s * 0.36f, cy)
                    close()
                }
                canvas.drawPath(tri, fill)
            }
            Glyph.SCREEN -> {
                val phone = RectF(cx - s * 0.28f, cy - s * 0.42f, cx + s * 0.28f, cy + s * 0.28f)
                canvas.drawRoundRect(phone, s * 0.10f, s * 0.10f, stroke)
                canvas.drawLine(cx - s * 0.10f, cy - s * 0.26f, cx + s * 0.10f, cy - s * 0.26f, stroke)
                canvas.drawCircle(cx - s * 0.16f, cy + s * 0.36f, s * 0.07f, stroke)
                canvas.drawCircle(cx + s * 0.16f, cy + s * 0.36f, s * 0.07f, stroke)
                canvas.drawLine(cx - s * 0.09f, cy + s * 0.36f, cx + s * 0.09f, cy + s * 0.36f, stroke)
            }
            Glyph.EXTENDED -> {
                val back = RectF(cx - s * 0.34f, cy - s * 0.26f, cx + s * 0.08f, cy + s * 0.08f)
                val front = RectF(cx - s * 0.10f, cy - s * 0.08f, cx + s * 0.34f, cy + s * 0.28f)
                canvas.drawRoundRect(back, s * 0.06f, s * 0.06f, stroke)
                canvas.drawRoundRect(front, s * 0.06f, s * 0.06f, stroke)
            }
            Glyph.STEAMVR -> {
                // Steam-like circle + VR bars (not trademarked wordmark).
                canvas.drawCircle(cx - s * 0.12f, cy + s * 0.06f, s * 0.28f, stroke)
                canvas.drawCircle(cx + s * 0.18f, cy - s * 0.10f, s * 0.16f, stroke)
                canvas.drawLine(cx - s * 0.12f, cy + s * 0.06f, cx + s * 0.18f, cy - s * 0.10f, stroke)
                canvas.drawCircle(cx - s * 0.12f, cy + s * 0.06f, s * 0.10f, fill)
                val bar = Paint(fill).apply { strokeWidth = size * 0.045f; style = Paint.Style.STROKE }
                canvas.drawLine(cx - s * 0.34f, cy - s * 0.34f, cx - s * 0.10f, cy - s * 0.34f, bar)
                canvas.drawLine(cx + s * 0.06f, cy - s * 0.34f, cx + s * 0.34f, cy - s * 0.34f, bar)
            }
            Glyph.DESKTOP -> {
                val frame = RectF(cx - s * 0.36f, cy - s * 0.28f, cx + s * 0.36f, cy + s * 0.18f)
                canvas.drawRoundRect(frame, s * 0.06f, s * 0.06f, stroke)
                canvas.drawRect(cx - s * 0.30f, cy - s * 0.22f, cx + s * 0.30f, cy + s * 0.08f, fill)
                canvas.drawRect(cx - s * 0.10f, cy + s * 0.18f, cx + s * 0.10f, cy + s * 0.28f, fill)
                canvas.drawRect(cx - s * 0.22f, cy + s * 0.28f, cx + s * 0.22f, cy + s * 0.34f, fill)
            }
            Glyph.PARSEC -> {
                val pad = RectF(cx - s * 0.38f, cy - s * 0.20f, cx + s * 0.38f, cy + s * 0.20f)
                canvas.drawRoundRect(pad, s * 0.18f, s * 0.18f, stroke)
                canvas.drawCircle(cx - s * 0.16f, cy, s * 0.07f, fill)
                canvas.drawCircle(cx + s * 0.12f, cy - s * 0.08f, s * 0.045f, fill)
                canvas.drawCircle(cx + s * 0.22f, cy, s * 0.045f, fill)
            }
            Glyph.TWITCH -> {
                val path = Path().apply {
                    moveTo(cx - s * 0.28f, cy - s * 0.30f)
                    lineTo(cx + s * 0.22f, cy - s * 0.30f)
                    lineTo(cx + s * 0.22f, cy + s * 0.10f)
                    lineTo(cx + s * 0.04f, cy + s * 0.28f)
                    lineTo(cx - s * 0.08f, cy + s * 0.10f)
                    lineTo(cx - s * 0.28f, cy + s * 0.10f)
                    close()
                }
                canvas.drawPath(path, stroke)
                canvas.drawRect(cx - s * 0.10f, cy - s * 0.18f, cx - s * 0.02f, cy + 0f, fill)
                canvas.drawRect(cx + s * 0.04f, cy - s * 0.18f, cx + s * 0.12f, cy + 0f, fill)
            }
            Glyph.DISCORD -> {
                val body = Path().apply {
                    addRoundRect(
                        RectF(cx - s * 0.36f, cy - s * 0.22f, cx + s * 0.36f, cy + s * 0.22f),
                        s * 0.22f, s * 0.22f, Path.Direction.CW,
                    )
                }
                canvas.drawPath(body, stroke)
                canvas.drawCircle(cx - s * 0.12f, cy - s * 0.02f, s * 0.08f, fill)
                canvas.drawCircle(cx + s * 0.12f, cy - s * 0.02f, s * 0.08f, fill)
            }
            Glyph.SPOTIFY -> {
                val arcs = Paint(stroke).apply { strokeWidth = size * 0.032f }
                canvas.drawArc(RectF(cx - s * 0.32f, cy - s * 0.22f, cx + s * 0.32f, cy + s * 0.12f), 200f, 140f, false, arcs)
                canvas.drawArc(RectF(cx - s * 0.26f, cy - s * 0.10f, cx + s * 0.26f, cy + s * 0.18f), 200f, 140f, false, arcs)
                canvas.drawArc(RectF(cx - s * 0.20f, cy + s * 0.02f, cx + s * 0.20f, cy + s * 0.24f), 200f, 140f, false, arcs)
            }
            Glyph.REDDIT -> {
                canvas.drawCircle(cx, cy + s * 0.04f, s * 0.26f, stroke)
                canvas.drawCircle(cx - s * 0.08f, cy, s * 0.055f, fill)
                canvas.drawCircle(cx + s * 0.08f, cy, s * 0.055f, fill)
                canvas.drawCircle(cx + s * 0.22f, cy - s * 0.22f, s * 0.055f, fill)
                canvas.drawLine(cx, cy - s * 0.20f, cx + s * 0.18f, cy - s * 0.22f, stroke)
            }
            Glyph.XBOX -> {
                canvas.drawCircle(cx, cy, s * 0.30f, stroke)
                val xPath = Path().apply {
                    moveTo(cx - s * 0.16f, cy - s * 0.18f)
                    lineTo(cx - s * 0.05f, cy)
                    lineTo(cx - s * 0.16f, cy + s * 0.18f)
                    moveTo(cx + s * 0.16f, cy - s * 0.18f)
                    lineTo(cx + s * 0.05f, cy)
                    lineTo(cx + s * 0.16f, cy + s * 0.18f)
                }
                canvas.drawPath(xPath, stroke)
            }
            Glyph.CHATGPT -> {
                for (i in 0 until 6) {
                    val a = i * (Math.PI / 3.0) - 0.3
                    val x = cx + cos(a).toFloat() * s * 0.22f
                    val y = cy + sin(a).toFloat() * s * 0.22f
                    canvas.drawCircle(x, y, s * 0.07f, stroke)
                }
                canvas.drawCircle(cx, cy, s * 0.08f, fill)
            }
            Glyph.PLEX -> {
                val chev = Path().apply {
                    moveTo(cx - s * 0.18f, cy - s * 0.24f)
                    lineTo(cx + s * 0.22f, cy)
                    lineTo(cx - s * 0.18f, cy + s * 0.24f)
                    lineTo(cx - s * 0.04f, cy)
                    close()
                }
                canvas.drawPath(chev, fill)
            }
            Glyph.GOOGLE -> {
                val colors = intArrayOf(
                    0xFFEA4335.toInt(), 0xFFFBBC05.toInt(),
                    0xFF34A853.toInt(), 0xFF4285F4.toInt(),
                )
                val dots = arrayOf(
                    cx - s * 0.14f to cy - s * 0.12f,
                    cx + s * 0.14f to cy - s * 0.12f,
                    cx - s * 0.14f to cy + s * 0.14f,
                    cx + s * 0.14f to cy + s * 0.14f,
                )
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                dots.forEachIndexed { i, (x, y) ->
                    p.color = colors[i]
                    canvas.drawCircle(x, y, s * 0.10f, p)
                }
            }
            Glyph.LETTER -> {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = mark
                    textAlign = Paint.Align.CENTER
                    textSize = size * 0.42f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
                }
                val text = spec.letter.ifBlank { "?" }
                canvas.drawText(text, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
            }
        }
    }

    private fun shifted(src: Path, dx: Float, dy: Float): Path {
        val copy = Path(src)
        copy.offset(dx, dy)
        return copy
    }

    private fun shifted(src: RectF, dx: Float, dy: Float): RectF {
        return RectF(src.left + dx, src.top + dy, src.right + dx, src.bottom + dy)
    }

    private class V(val x: Float, val y: Float, val z: Float) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Float) = V(x * s, y * s, z * s)
        fun length() = sqrt(x * x + y * y + z * z)
        fun norm(): V {
            val l = length().coerceAtLeast(1e-6f)
            return this * (1f / l)
        }
        fun dot(o: V) = x * o.x + y * o.y + z * o.z
        fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun rotate(yaw: Float, pitch: Float): V {
            val cy = cos(yaw)
            val sy = sin(yaw)
            val cp = cos(pitch)
            val sp = sin(pitch)
            val y1 = y * cp - z * sp
            val z1 = y * sp + z * cp
            return V(x * cy - z1 * sy, y1, x * sy + z1 * cy)
        }
    }
}
