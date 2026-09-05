package org.opennebula.air.mirror

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opennebula.air.logging.LogTags
import org.opennebula.air.shell.SpatialLayout
import org.opennebula.air.shell.SpatialWindow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * MediaProjection frames via ImageReader. The glasses Presentation uses a
 * different EGL context, so OES SurfaceTexture there never receives buffers.
 */
class MirrorPipeline {
    val enabled = AtomicBoolean(false)
    val pose = AtomicReference(
        SpatialWindow.fromControls(
            -SpatialLayout.PANEL_Z,
            SpatialLayout.PANEL_Y,
            1.1f,
            -1.55f,
            9f / 20f,
        ),
    )
    val cursorX = AtomicReference(0.5f)
    val cursorY = AtomicReference(0.5f)
    val bufferW = AtomicInteger(1080)
    val bufferH = AtomicInteger(1920)
    val generation = AtomicInteger(0)
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val lock = Any()
    private var bitmap: Bitmap? = null
    private var opaqueScratch: IntArray? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private val frames = AtomicInteger(0)
    @Volatile var surface: Surface? = null
        private set
    @Volatile var onSurfaceReady: ((Surface?) -> Unit)? = null
    @Volatile var onLog: ((String) -> Unit)? = null
    @Volatile var onCaptureSize: ((Int, Int) -> Unit)? = null

    fun frameCount(): Int = frames.get()

    fun setActive(running: Boolean) {
        enabled.set(running)
        _active.value = running
        if (!running) frames.set(0)
    }

    fun setCaptureSize(width: Int, height: Int) {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        if (bufferW.get() == w && bufferH.get() == h && reader != null) {
            mainPost { onCaptureSize?.invoke(w, h) }
            return
        }
        bufferW.set(w)
        bufferH.set(h)
        mainPost { onCaptureSize?.invoke(w, h) }
        if (enabled.get()) openCapture(w, h)
    }

    fun openCapture(width: Int = bufferW.get(), height: Int = bufferH.get()) {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        bufferW.set(w)
        bufferH.set(h)
        closeReaderLocked()
        synchronized(lock) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(0xFF4A2458.toInt())
            }
            generation.set(1)
        }
        ensureThread()
        val r = newReader(w, h)
        r.setOnImageAvailableListener({ drain(it) }, Handler(thread!!.looper))
        reader = r
        val surf = r.surface
        surface = surf
        log("ImageReader ${w}x$h valid=${surf.isValid}")
        mainPost { onSurfaceReady?.invoke(surf) }
        mainPostDelayed(2000) {
            if (enabled.get() && frames.get() == 0) {
                log("no ImageReader frames after 2s")
            }
        }
    }

    fun closeCapture() {
        closeReaderLocked()
        surface = null
        thread?.quitSafely()
        thread = null
        synchronized(lock) {
            bitmap?.recycle()
            bitmap = null
        }
        opaqueScratch = null
        generation.set(0)
        frames.set(0)
    }

    fun withBitmap(block: (Bitmap) -> Unit) {
        synchronized(lock) {
            val bmp = bitmap ?: return
            block(bmp)
        }
    }

    /**
     * Software frame path (XR bridge MJPEG).
     * Takes ownership of [src] when mutable ARGB — skips Canvas blit + opaque scan.
     * JPEG frames are already opaque; forceOpaque would cost ~2M pixels/frame.
     */
    fun pushBitmap(src: Bitmap) {
        val w = src.width.coerceAtLeast(2)
        val h = src.height.coerceAtLeast(2)
        synchronized(lock) {
            bufferW.set(w)
            bufferH.set(h)
            if (bitmap !== src) {
                bitmap?.recycle()
                bitmap = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) {
                    src
                } else {
                    src.copy(Bitmap.Config.ARGB_8888, true).also {
                        if (!src.isRecycled) src.recycle()
                    }
                }
            }
            bitmap!!.setHasAlpha(false)
            generation.incrementAndGet()
        }
        val n = frames.incrementAndGet()
        if (n == 1 || n % 120 == 0) {
            log("xr frames=$n ${w}x$h")
        }
    }

    fun log(message: String) {
        Log.i(LogTags.MIRROR, message)
        mainPost { onLog?.invoke(message) }
    }

    private fun drain(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            copyImage(image)
        } catch (err: Exception) {
            log("copy frame failed: ${err.message}")
        } finally {
            image.close()
        }
    }

    private fun copyImage(image: Image) {
        val plane = image.planes.firstOrNull() ?: return
        val src = plane.buffer
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        synchronized(lock) {
            var bmp = bitmap
            if (bmp == null || bmp.width != w || bmp.height != h) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap = bmp
            }
            src.rewind()
            if (pixelStride == 4 && rowStride == w * 4 && src.remaining() >= w * h * 4) {
                bmp.copyPixelsFromBuffer(src)
            } else {
                val row = ByteArray(rowStride)
                val argb = IntArray(w)
                for (y in 0 until h) {
                    src.position(y * rowStride)
                    val toRead = minOf(rowStride, src.remaining())
                    if (toRead <= 0) break
                    src.get(row, 0, toRead)
                    for (x in 0 until w) {
                        val i = x * pixelStride
                        if (i + 2 >= toRead) break
                        val r = row[i].toInt() and 0xff
                        val g = row[i + 1].toInt() and 0xff
                        val b = row[i + 2].toInt() and 0xff
                        argb[x] = opaqueArgb(r, g, b)
                    }
                    bmp.setPixels(argb, 0, w, 0, y, w, 1)
                }
            }
            forceOpaque(bmp)
            bmp.setHasAlpha(false)
            generation.incrementAndGet()
        }
        val n = frames.incrementAndGet()
        if (n == 1 || n % 120 == 0) {
            log("mirror frames=$n ${w}x$h stride=$rowStride px=$pixelStride")
        }
    }

    private fun forceOpaque(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        var px = opaqueScratch
        if (px == null || px.size < n) {
            px = IntArray(n)
            opaqueScratch = px
        }
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in 0 until n) px[i] = px[i] or OPAQUE
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        return if (Build.VERSION.SDK_INT >= 29) {
            ImageReader.newInstance(
                w,
                h,
                PixelFormat.RGBA_8888,
                3,
                HardwareBuffer.USAGE_CPU_READ_OFTEN,
            )
        } else {
            ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        }
    }

    private fun closeReaderLocked() {
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        reader = null
        surface = null
    }

    private fun ensureThread() {
        if (thread?.isAlive == true) return
        thread = HandlerThread("opennebula-mirror").also { it.start() }
    }

    private fun mainPost(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }

    private fun mainPostDelayed(ms: Long, block: () -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed(block, ms)
    }

    companion object {
        const val ID = "screen"
        const val STEAMVR_ID = "steamvr"
        private const val OPAQUE = 0xFF000000.toInt()

        fun opaqueArgb(r: Int, g: Int, b: Int): Int {
            return OPAQUE or ((r and 0xff) shl 16) or ((g and 0xff) shl 8) or (b and 0xff)
        }

        fun captureSize(widthPx: Int, heightPx: Int, maxLong: Int = 720): Pair<Int, Int> {
            val w = widthPx.coerceAtLeast(2)
            val h = heightPx.coerceAtLeast(2)
            val long = max(w, h)
            if (long <= maxLong) return even(w) to even(h)
            val k = maxLong / long.toFloat()
            return even((w * k).roundToInt()) to even((h * k).roundToInt())
        }

        private fun even(v: Int): Int = (v / 2 * 2).coerceAtLeast(2)
    }
}
