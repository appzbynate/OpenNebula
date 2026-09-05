package org.opennebula.air.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import org.opennebula.air.browser.SpatialBrowser
import org.opennebula.air.imu.Orientation
import org.opennebula.air.logging.LogTags
import org.opennebula.air.mirror.MirrorPipeline
import org.opennebula.air.shell.ChromeHandle
import org.opennebula.air.shell.IconGrid
import org.opennebula.air.shell.IconGridState
import org.opennebula.air.shell.IconLabels
import org.opennebula.air.shell.IconSprite
import org.opennebula.air.shell.SpatialLayer
import org.opennebula.air.shell.SpatialLayout
import org.opennebula.air.shell.SpatialWindow
import org.opennebula.air.shell.WindowChrome
import org.opennebula.air.shell.WindowChromeState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class WorldRenderer(
    private val poseRef: AtomicReference<Orientation>,
    private val layers: List<SpatialLayer>,
    private val focusedId: AtomicReference<String>,
    private val showMarkers: AtomicReference<Boolean>,
    private val stereoEnabled: AtomicBoolean,
    private val hardware3d: AtomicBoolean,
    private val ipdMeters: AtomicReference<Float>,
    private val pointerBeam: AtomicReference<PointerBeam>,
    private val mirror: MirrorPipeline,
    private val chrome: WindowChromeState,
    private val iconGrid: IconGridState,
    private val gridOpen: AtomicBoolean,
    private val gridPose: AtomicReference<SpatialWindow>,
    private val browser: MirrorPipeline,
    private val steamVr: MirrorPipeline,
    private val desktop: MirrorPipeline,
) : GLSurfaceView.Renderer {
    private var colorProg = 0
    private var texProg = 0
    private var uColorMvp = 0
    private var aColorPos = 0
    private var aColorCol = 0
    private var uTexMvp = 0
    private var uTexSamp = 0
    private var uTexOpaque = 0
    private var uTexBright = 0
    private var aTexPos = 0
    private var aTexUv = 0
    private var surfaceW = 1
    private var surfaceH = 1

    private lateinit var envTris: FloatBuffer
    private var envTriCount = 0
    private lateinit var markerTris: FloatBuffer
    private var markerTriCount = 0
    private lateinit var gridLines: FloatBuffer
    private var gridLineCount = 0
    private lateinit var cursorTris: FloatBuffer
    private var cursorCount = 0
    private lateinit var laserLines: FloatBuffer
    private lateinit var laserDot: FloatBuffer
    private var laserDotCount = 0
    private lateinit var chromeTris: FloatBuffer
    private var chromeCount = 0

    private val gpu = ArrayList<GpuLayer>()
    private var mirrorTex = 0
    private var lastMirrorGen = -1
    private var lastMirrorPose: SpatialWindow? = null
    private var browserTex = 0
    private var lastBrowserGen = -1
    private var steamVrTex = 0
    private var lastSteamVrGen = -1
    private var desktopTex = 0
    private var lastDesktopGen = -1
    private lateinit var mirrorPanelTris: FloatBuffer
    private var mirrorPanelCount = 0
    private lateinit var mirrorBezelTris: FloatBuffer
    private var mirrorBezelCount = 0
    private lateinit var browserPanelTris: FloatBuffer
    private var browserPanelCount = 0
    private lateinit var browserBezelTris: FloatBuffer
    private var browserBezelCount = 0
    private var lastBrowserPose: SpatialWindow? = null
    private lateinit var steamVrPanelTris: FloatBuffer
    private var steamVrPanelCount = 0
    private lateinit var steamVrBezelTris: FloatBuffer
    private var steamVrBezelCount = 0
    private var lastSteamVrPose: SpatialWindow? = null
    private lateinit var desktopPanelTris: FloatBuffer
    private var desktopPanelCount = 0
    private lateinit var desktopBezelTris: FloatBuffer
    private var desktopBezelCount = 0
    private var lastDesktopPose: SpatialWindow? = null
    /** NDC fullscreen quad for immersive SteamVR (no floating AR panel). */
    private lateinit var immersiveQuad: FloatBuffer
    private var immersiveQuadCount = 0
    private val identityMvp = FloatArray(16) { 0f }.also {
        it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
    }
    private val iconTex = HashMap<String, Int>()
    private val iconDraw = ArrayList<IconMesh>()
    private var lastIconGen = -1
    private var lastIconHover: String? = UNSET_HOVER
    private var maxAniso = 1f
    private var labelTex = 0
    private lateinit var labelFront: FloatBuffer
    private var labelCount = 0
    private lateinit var iconDecorTris: FloatBuffer
    private var iconDecorCount = 0

    private class IconMesh(
        val tex: Int,
        val front: FloatBuffer,
        val frontCount: Int,
        val sides: FloatBuffer,
        val sideCount: Int,
        val bright: Float,
        val glow: FloatBuffer,
        val glowCount: Int,
    )

    private class GpuLayer(
        val layer: SpatialLayer,
        val tex: Int,
        var lastGen: Int = -1,
        var lastPose: SpatialWindow? = null,
        var panelTris: FloatBuffer,
        var bezelTris: FloatBuffer,
        var panelCount: Int = 0,
        var bezelCount: Int = 0,
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        maxAniso = queryMaxAniso()
        envTris = pack(emptyList())
        envTriCount = 0
        markerTris = pack(buildMarkers())
        markerTriCount = markerTris.capacity() / COLOR_STRIDE
        gridLines = pack(emptyList())
        gridLineCount = 0
        cursorTris = pack(FloatArray(CURSOR_FLOATS).toList())
        laserLines = pack(FloatArray(LASER_FLOATS).toList())
        laserDot = pack(FloatArray(HAND_FLOATS).toList())
        chromeTris = pack(FloatArray(CHROME_FLOATS).toList())
        colorProg = link(COLOR_VS, COLOR_FS)
        texProg = link(TEX_VS, TEX_FS)
        uColorMvp = GLES20.glGetUniformLocation(colorProg, "uMvp")
        aColorPos = GLES20.glGetAttribLocation(colorProg, "aPos")
        aColorCol = GLES20.glGetAttribLocation(colorProg, "aColor")
        uTexMvp = GLES20.glGetUniformLocation(texProg, "uMvp")
        uTexSamp = GLES20.glGetUniformLocation(texProg, "uTex")
        uTexOpaque = GLES20.glGetUniformLocation(texProg, "uOpaque")
        uTexBright = GLES20.glGetUniformLocation(texProg, "uBright")
        aTexPos = GLES20.glGetAttribLocation(texProg, "aPos")
        aTexUv = GLES20.glGetAttribLocation(texProg, "aUv")
        gpu.clear()
        // Home/Notes Compose panels stay off-screen; the icon grid and ImageReader browser replace them.
        mirrorTex = genTex()
        browserTex = genTex()
        steamVrTex = genTex()
        desktopTex = genTex()
        val mp = mirror.pose.get()
        lastMirrorPose = mp
        mirrorBezelTris = pack(buildBezel(mp))
        mirrorBezelCount = mirrorBezelTris.capacity() / COLOR_STRIDE
        mirrorPanelTris = packUv(buildPanel(mp))
        mirrorPanelCount = mirrorPanelTris.capacity() / UV_STRIDE
        val bp = browser.pose.get()
        lastBrowserPose = bp
        browserBezelTris = pack(buildBezel(bp))
        browserBezelCount = browserBezelTris.capacity() / COLOR_STRIDE
        browserPanelTris = packUv(buildPanel(bp))
        browserPanelCount = browserPanelTris.capacity() / UV_STRIDE
        val sp = steamVr.pose.get()
        lastSteamVrPose = sp
        steamVrBezelTris = pack(buildBezel(sp))
        steamVrBezelCount = steamVrBezelTris.capacity() / COLOR_STRIDE
        steamVrPanelTris = packUv(buildPanel(sp))
        steamVrPanelCount = steamVrPanelTris.capacity() / UV_STRIDE
        val dp = desktop.pose.get()
        lastDesktopPose = dp
        desktopBezelTris = pack(buildBezel(dp))
        desktopBezelCount = desktopBezelTris.capacity() / COLOR_STRIDE
        desktopPanelTris = packUv(buildPanel(dp))
        desktopPanelCount = desktopPanelTris.capacity() / UV_STRIDE
        // Fullscreen NDC: x,y,z,u,v — covers the eye viewport.
        immersiveQuad = packUv(
            listOf(
                -1f, -1f, 0f, 0f, 1f,
                1f, -1f, 0f, 1f, 1f,
                -1f, 1f, 0f, 0f, 0f,
                -1f, 1f, 0f, 0f, 0f,
                1f, -1f, 0f, 1f, 1f,
                1f, 1f, 0f, 1f, 0f,
            ),
        )
        immersiveQuadCount = 6
        iconTex.clear()
        iconDraw.clear()
        lastIconGen = -1
        lastIconHover = UNSET_HOVER
        labelTex = genTex()
        labelFront = pack(FloatArray(40).toList())
        labelCount = 0
        iconDecorTris = pack(FloatArray(DECOR_FLOATS).toList())
        iconDecorCount = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width.coerceAtLeast(1)
        surfaceH = height.coerceAtLeast(1)
        if (stereoEnabled.get()) {
            Log.i(
                LogTags.DISPLAY,
                "stereo surface ${surfaceW}x$surfaceH half=${surfaceW / 2} " +
                    "aspect=${"%.3f".format(surfaceW.toFloat() / surfaceH)} hw3d=${hardware3d.get()}",
            )
        }
        GLES20.glViewport(0, 0, surfaceW, surfaceH)
    }

    override fun onDrawFrame(gl: GL10?) {
        try {
            drawWorld()
        } catch (t: Throwable) {
            Log.e(LogTags.DISPLAY, "onDrawFrame failed", t)
        }
    }

    private fun drawWorld() {
        uploadLayers()
        uploadMirror()
        uploadBrowser()
        uploadSteamVr()
        uploadDesktop()
        uploadIcons()
        // Raw fusion → view (motion-smooth 0.8.93 caused complaint; restore baseline display).
        // Tag baseline-0.8.92 remains the full restore point.
        val raw = poseRef.get()
        val centerView = PoseMath.viewFromQuat(raw.qw, raw.qx, raw.qy, raw.qz)
        val panelX = SpatialLayout.PANEL_X
        val panelY = SpatialLayout.PANEL_Y
        val panelZ = SpatialLayout.PANEL_Z
        val obj = PoseMath.transformPoint(centerView, panelX, panelY, panelZ)
        DisplayPoseBridge.publish(
            DisplayPoseBridge.Snapshot(
                raw = raw,
                display = raw,
                holding = false,
                lagDeg = 0f,
                speedHatDps = 0f,
                objX = obj[0],
                objY = obj[1],
                objZ = obj[2],
                rawObjX = obj[0],
                rawObjY = obj[1],
                rawObjZ = obj[2],
                frameNs = System.nanoTime(),
            ),
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        // SteamVR immersive: PC sends packed Half-SBS (L|R). Blit once across the
        // full framebuffer and let MCU mode 8 split eyes — do NOT software-split
        // viewports (that duplicated the whole SBS into each eye).
        if (steamVr.enabled.get() && steamVr.generation.get() > 0) {
            uploadSteamVr()
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glViewport(0, 0, surfaceW, surfaceH)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            drawTex(identityMvp, steamVrTex, immersiveQuad, immersiveQuadCount, opaque = true)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            return
        }
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        val stereo = stereoEnabled.get() && surfaceW >= 2
        if (stereo) {
            val half = surfaceW / 2
            val surfaceAspect = surfaceW.toFloat() / surfaceH.toFloat().coerceAtLeast(1f)
            // Ultrawide SBS (~3840×1080): each half is already 16:9 — use buffer eye aspect.
            // HalfSBS (~1920×1080, MCU mode 8): firmware stretches each half to full eye FOV —
            // project with Air optical 16:9 (same as mono), not half-buffer aspect.
            val eyeAspect = if (surfaceAspect >= 3.0f) {
                half.toFloat() / surfaceH.toFloat().coerceAtLeast(1f)
            } else {
                StereoConfig.OPTICAL_ASPECT
            }
            val proj = PoseMath.perspective(
                StereoConfig.FOVY_DEG, eyeAspect, StereoConfig.NEAR, StereoConfig.FAR,
            )
            val ipd = ipdMeters.get()
            val leftX = StereoConfig.leftEyeX(ipd)
            val rightX = StereoConfig.rightEyeX(ipd)
            drawEye(0, 0, half, surfaceH, proj, PoseMath.viewForEye(centerView, leftX))
            drawEye(half, 0, surfaceW - half, surfaceH, proj, PoseMath.viewForEye(centerView, rightX))
        } else {
            // Mono: prefer measured surface; fall back to Air 16:9 if the surface is nonsense.
            val aspect = (surfaceW.toFloat() / surfaceH.toFloat().coerceAtLeast(1f))
                .let { if (it in 1.2f..2.2f) it else StereoConfig.OPTICAL_ASPECT }
            val proj = PoseMath.perspective(
                StereoConfig.FOVY_DEG, aspect, StereoConfig.NEAR, StereoConfig.FAR,
            )
            drawEye(0, 0, surfaceW, surfaceH, proj, centerView)
        }
    }

    private fun drawEye(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        proj: FloatArray,
        centerView: FloatArray,
    ) {
        GLES20.glViewport(x, y, w, h)
        // Immersive SteamVR is drawn once in drawWorld() as packed SBS.
        if (steamVr.enabled.get() && steamVr.generation.get() > 0) {
            return
        }
        val mvp = PoseMath.multiply(proj, centerView)
        if (showMarkers.get()) {
            drawColor(mvp, markerTris, markerTriCount, GLES20.GL_TRIANGLES)
        }
        if (gridOpen.get()) {
            drawIcons(mvp)
        }
        drawWindows(mvp)
        val hasWindow = browser.enabled.get() || mirror.enabled.get() || desktop.enabled.get()
        if (hasWindow) {
            placeCursor()
            drawColor(mvp, cursorTris, cursorCount, GLES20.GL_TRIANGLES)
            chromeCount = writeChrome()
            drawColor(mvp, chromeTris, chromeCount, GLES20.GL_TRIANGLES)
        }
        val beam = pointerBeam.get()
        if (beam.active) {
            placeLaser(beam)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            drawColor(mvp, laserLines, LASER_VERTS, GLES20.GL_TRIANGLES)
            drawColor(mvp, laserDot, laserDotCount, GLES20.GL_TRIANGLES)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }
    }

    private fun drawWindows(mvp: FloatArray) {
        data class Pass(
            val z: Float,
            val bezel: FloatBuffer,
            val bezelCount: Int,
            val tex: Int,
            val panel: FloatBuffer,
            val panelCount: Int,
        )
        val passes = ArrayList<Pass>(3)
        if (browser.enabled.get()) {
            passes += Pass(
                browser.pose.get().z,
                browserBezelTris, browserBezelCount,
                browserTex, browserPanelTris, browserPanelCount,
            )
        }
        if (mirror.enabled.get()) {
            passes += Pass(
                mirror.pose.get().z,
                mirrorBezelTris, mirrorBezelCount,
                mirrorTex, mirrorPanelTris, mirrorPanelCount,
            )
        }
        if (steamVr.enabled.get() && lastSteamVrGen > 0) {
            passes += Pass(
                steamVr.pose.get().z,
                steamVrBezelTris, steamVrBezelCount,
                steamVrTex, steamVrPanelTris, steamVrPanelCount,
            )
        }
        if (desktop.enabled.get() && lastDesktopGen > 0) {
            passes += Pass(
                desktop.pose.get().z,
                desktopBezelTris, desktopBezelCount,
                desktopTex, desktopPanelTris, desktopPanelCount,
            )
        }
        if (passes.isEmpty()) return
        passes.sortBy { it.z }
        GLES20.glDepthMask(true)
        for (pass in passes) {
            drawTex(mvp, pass.tex, pass.panel, pass.panelCount, opaque = true)
        }
    }

    private fun focusedWindow(): SpatialWindow {
        val id = focusedId.get()
        if (id == SpatialBrowser.ID && browser.enabled.get()) return browser.pose.get()
        if (id == MirrorPipeline.ID && mirror.enabled.get()) return mirror.pose.get()
        if (id == MirrorPipeline.STEAMVR_ID && steamVr.enabled.get()) return steamVr.pose.get()
        if (id == MirrorPipeline.DESKTOP_ID && desktop.enabled.get()) return desktop.pose.get()
        if (gridOpen.get()) return gridPose.get()
        return layers.firstOrNull { it.id == id }?.pose?.get() ?: gridPose.get()
    }

    private fun uploadLayers() {
        for (g in gpu) {
            val gen = g.layer.panel.generation.get()
            if (gen != g.lastGen) {
                g.layer.panel.withBitmap { bmp ->
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, g.tex)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                }
                g.lastGen = gen
            }
            val pose = g.layer.pose.get()
            if (pose != g.lastPose) {
                g.lastPose = pose
                val bezel = buildBezel(pose)
                write(g.bezelTris, bezel)
                g.bezelCount = minOf(bezel.size, g.bezelTris.capacity()) / COLOR_STRIDE
                val panel = buildPanel(pose)
                writeUv(g.panelTris, panel)
                g.panelCount = minOf(panel.size, g.panelTris.capacity()) / UV_STRIDE
            }
        }
    }

    private fun uploadMirror() {
        if (!mirror.enabled.get()) return
        val gen = mirror.generation.get()
        if (gen != lastMirrorGen) {
            mirror.withBitmap { bmp ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mirrorTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            }
            lastMirrorGen = gen
        }
        val pose = mirror.pose.get()
        if (pose != lastMirrorPose) {
            lastMirrorPose = pose
            val bezel = buildBezel(pose)
            write(mirrorBezelTris, bezel)
            mirrorBezelCount = minOf(bezel.size, mirrorBezelTris.capacity()) / COLOR_STRIDE
            val panel = buildPanel(pose)
            writeUv(mirrorPanelTris, panel)
            mirrorPanelCount = minOf(panel.size, mirrorPanelTris.capacity()) / UV_STRIDE
        }
    }

    private fun uploadBrowser() {
        if (!browser.enabled.get()) return
        val gen = browser.generation.get()
        if (gen != lastBrowserGen) {
            browser.withBitmap { bmp ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, browserTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            }
            lastBrowserGen = gen
        }
        val pose = browser.pose.get()
        if (pose != lastBrowserPose) {
            lastBrowserPose = pose
            val bezel = buildBezel(pose)
            write(browserBezelTris, bezel)
            browserBezelCount = minOf(bezel.size, browserBezelTris.capacity()) / COLOR_STRIDE
            val panel = buildPanel(pose)
            writeUv(browserPanelTris, panel)
            browserPanelCount = minOf(panel.size, browserPanelTris.capacity()) / UV_STRIDE
        }
    }

    private fun uploadSteamVr() {
        if (!steamVr.enabled.get()) return
        val gen = steamVr.generation.get()
        if (gen != lastSteamVrGen) {
            var uploaded = false
            steamVr.withBitmap { bmp ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, steamVrTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                uploaded = true
            }
            // Only advance gen when a bitmap was actually uploaded (avoid stuck black tex).
            if (uploaded) lastSteamVrGen = gen
        }
        val pose = steamVr.pose.get()
        if (pose != lastSteamVrPose) {
            lastSteamVrPose = pose
            val bezel = buildBezel(pose)
            write(steamVrBezelTris, bezel)
            steamVrBezelCount = minOf(bezel.size, steamVrBezelTris.capacity()) / COLOR_STRIDE
            val panel = buildPanel(pose)
            writeUv(steamVrPanelTris, panel)
            steamVrPanelCount = minOf(panel.size, steamVrPanelTris.capacity()) / UV_STRIDE
        }
    }

    private fun uploadDesktop() {
        if (!desktop.enabled.get()) return
        val gen = desktop.generation.get()
        if (gen != lastDesktopGen) {
            var uploaded = false
            desktop.withBitmap { bmp ->
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, desktopTex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                uploaded = true
            }
            if (uploaded) lastDesktopGen = gen
        }
        val pose = desktop.pose.get()
        if (pose != lastDesktopPose) {
            lastDesktopPose = pose
            val bezel = buildBezel(pose)
            write(desktopBezelTris, bezel)
            desktopBezelCount = minOf(bezel.size, desktopBezelTris.capacity()) / COLOR_STRIDE
            val panel = buildPanel(pose)
            writeUv(desktopPanelTris, panel)
            desktopPanelCount = minOf(panel.size, desktopPanelTris.capacity()) / UV_STRIDE
        }
    }

    private fun uploadIcons() {
        if (!gridOpen.get()) return
        val gen = iconGrid.generation.get()
        if (gen != lastIconGen) {
            lastIconGen = gen
            for (tile in iconGrid.tiles.get()) {
                val bmp = tile.bitmap ?: continue
                val tex = iconTex.getOrPut(tile.component) { genTex(mipmaps = true) }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                applyAniso()
            }
            rebuildIconMeshes()
        }
        val hover = iconGrid.hoverComponent.get()
        if (hover != lastIconHover) {
            rebuildIconMeshes()
        }
    }

    private fun rebuildIconMeshes() {
        lastIconHover = iconGrid.hoverComponent.get()
        val hover = lastIconHover
        val hovered = hover?.let { h -> iconGrid.tiles.get().firstOrNull { it.component == h } }
        iconDraw.clear()
        for (tile in iconGrid.tiles.get()) {
            val tex = iconTex[tile.component] ?: continue
            val lit = tile.component == hover
            val scale = if (lit) 1.08f else 1f
            val n = tile.pose.normal()
            val pull = if (lit) 0.022f else 0f
            val window = tile.pose.copy(
                x = tile.pose.x + n[0] * pull,
                y = tile.pose.y + n[1] * pull,
                z = tile.pose.z + n[2] * pull,
                width = tile.pose.width * scale,
                height = tile.pose.height * scale,
            )
            val thick = IconGrid.THICKNESS * scale
            val (front, sides) = buildIconTile(window, thick, hovered = lit)
            val glow = if (lit) buildIconGlow(window, thick) else emptyList()
            iconDraw += IconMesh(
                tex,
                packUv(front),
                front.size / UV_STRIDE,
                pack(sides),
                sides.size / COLOR_STRIDE,
                if (lit) 1.12f else 1f,
                pack(glow),
                glow.size / COLOR_STRIDE,
            )
        }
        rebuildIconLabel(hovered)
        rebuildPageArrows()
    }

    private fun rebuildIconLabel(tile: IconSprite?) {
        labelCount = 0
        if (tile == null) return
        val bmp = IconLabels.render(tile.name) ?: return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelTex)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        val scale = 1.08f
        val n = tile.pose.normal()
        val pull = 0.022f
        val faceH = tile.pose.height * scale
        val aspect = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
        val h = IconGrid.LABEL_H
        val w = (h * aspect).coerceIn(IconGrid.FACE * 0.70f, IconGrid.FACE * 1.85f)
        val window = tile.pose.copy(
            x = tile.pose.x + n[0] * pull,
            y = tile.pose.y + n[1] * pull,
            z = tile.pose.z + n[2] * pull,
            width = tile.pose.width * scale,
            height = faceH,
        )
        val ly = -faceH / 2f - IconGrid.ICON_LABEL_GAP - h / 2f
        val world = window.localToWorld(0f, ly, IconGrid.THICKNESS * scale * 0.52f)
        val plate = SpatialWindow(
            x = world[0],
            y = world[1],
            z = world[2],
            width = w,
            height = h,
            yaw = window.yaw,
            pitch = window.pitch,
            roll = window.roll,
        )
        val front = buildPanel(plate, zOffset = 0.002f)
        writeUv(labelFront, front)
        labelCount = minOf(front.size, labelFront.capacity()) / UV_STRIDE
    }

    private fun rebuildPageArrows() {
        val field = gridPose.get()
        val data = ArrayList<Float>(256)
        val page = iconGrid.page.get()
        val pan = iconGrid.pan.get()
        val pages = iconGrid.pages.get()
        if (IconGrid.canSwipeLeft(page, pan, pages)) {
            appendPageChevron(data, field, -1)
        }
        if (IconGrid.canSwipeRight(page, pan, pages)) {
            appendPageChevron(data, field, 1)
        }
        write(iconDecorTris, data)
        iconDecorCount = minOf(data.size, iconDecorTris.capacity()) / COLOR_STRIDE
    }

    private fun drawIcons(mvp: FloatArray) {
        GLES20.glDepthMask(false)
        for (mesh in iconDraw) {
            if (mesh.glowCount > 0) {
                drawColor(mvp, mesh.glow, mesh.glowCount, GLES20.GL_TRIANGLES)
            }
        }
        GLES20.glDepthMask(true)
        for (mesh in iconDraw) {
            drawColor(mvp, mesh.sides, mesh.sideCount, GLES20.GL_TRIANGLES)
            drawTex(mvp, mesh.tex, mesh.front, mesh.frontCount, opaque = false, bright = mesh.bright)
        }
        GLES20.glDepthMask(false)
        drawColor(mvp, iconDecorTris, iconDecorCount, GLES20.GL_TRIANGLES)
        if (labelCount > 0) {
            drawTex(mvp, labelTex, labelFront, labelCount, opaque = false, bright = 1f)
        }
        GLES20.glDepthMask(true)
    }

    private fun placeCursor() {
        val id = focusedId.get()
        val window: SpatialWindow
        val u: Float
        val v: Float
        when {
            id == SpatialBrowser.ID && browser.enabled.get() -> {
                window = browser.pose.get()
                u = browser.cursorX.get()
                v = browser.cursorY.get()
            }
            id == MirrorPipeline.ID && mirror.enabled.get() -> {
                window = mirror.pose.get()
                u = mirror.cursorX.get()
                v = mirror.cursorY.get()
            }
            id == MirrorPipeline.STEAMVR_ID && steamVr.enabled.get() -> {
                window = steamVr.pose.get()
                u = steamVr.cursorX.get()
                v = steamVr.cursorY.get()
            }
            id == MirrorPipeline.DESKTOP_ID && desktop.enabled.get() -> {
                window = desktop.pose.get()
                u = desktop.cursorX.get()
                v = desktop.cursorY.get()
            }
            else -> {
                cursorCount = 0
                return
            }
        }
        val lx = (u - 0.5f) * window.width
        val ly = (0.5f - v) * window.height
        val s = SpatialLayout.CURSOR * (window.width / SpatialLayout.PANEL_W)
        val data = ArrayList<Float>(CURSOR_FLOATS)
        appendPointer(data, window, lx, ly, s)
        write(cursorTris, data)
        cursorCount = minOf(data.size, cursorTris.capacity()) / COLOR_STRIDE
    }

    private fun drawTex(
        mvp: FloatArray,
        tex: Int,
        buf: FloatBuffer,
        count: Int,
        opaque: Boolean,
        bright: Float = 1f,
    ) {
        if (count <= 0 || tex == 0) return
        GLES20.glUseProgram(texProg)
        GLES20.glUniformMatrix4fv(uTexMvp, 1, false, mvp, 0)
        GLES20.glUniform1f(uTexOpaque, if (opaque) 1f else 0f)
        if (uTexBright >= 0) {
            GLES20.glUniform1f(uTexBright, bright)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(uTexSamp, 0)
        buf.position(0)
        GLES20.glVertexAttribPointer(aTexPos, 3, GLES20.GL_FLOAT, false, UV_BYTES, buf)
        GLES20.glEnableVertexAttribArray(aTexPos)
        buf.position(3)
        GLES20.glVertexAttribPointer(aTexUv, 2, GLES20.GL_FLOAT, false, UV_BYTES, buf)
        GLES20.glEnableVertexAttribArray(aTexUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
    }

    private fun writeChrome(): Int {
        val data = ArrayList<Float>(CHROME_FLOATS)
        val expandedId = chrome.expandedLayer.get()
        val hoverLayer = chrome.hoverLayer.get()
        val hoverHandle = chrome.hoverHandle.get()
        val grab = chrome.grabHandle.get()
        val focus = focusedId.get()
        if (browser.enabled.get()) {
            appendChrome(
                data, SpatialBrowser.ID, browser.pose.get(), hoverLayer, hoverHandle, grab, focus, expandedId,
            )
        }
        if (mirror.enabled.get()) {
            appendChrome(
                data, MirrorPipeline.ID, mirror.pose.get(), hoverLayer, hoverHandle, grab, focus, expandedId,
            )
        }
        if (desktop.enabled.get()) {
            appendChrome(
                data, MirrorPipeline.DESKTOP_ID, desktop.pose.get(), hoverLayer, hoverHandle, grab, focus, expandedId,
            )
        }
        // No chrome in immersive SteamVR.
        if (data.isEmpty()) return 0
        write(chromeTris, data)
        return minOf(data.size, chromeTris.capacity()) / COLOR_STRIDE
    }

    private fun appendChrome(
        data: ArrayList<Float>,
        id: String,
        window: SpatialWindow,
        hoverLayer: String?,
        hoverHandle: ChromeHandle?,
        grab: ChromeHandle?,
        focus: String,
        expandedId: String?,
    ) {
        val expanded = expandedId == id || (grab != null && focus == id)
        if (expanded) {
            appendGlassPill(data, window)
        }
        for (handle in WindowChrome.handles(expanded)) {
            val grabbed = grab != null && focus == id && grab == handle
            val hovered = hoverLayer == id && hoverHandle == handle
            val lx = WindowChrome.localIconX(handle, expanded, window)
            val ly = WindowChrome.localIconY(handle, window, expanded)
            if (handle == ChromeHandle.CLOSE) {
                appendClose(data, window, lx, ly, hovered, grabbed)
                continue
            }
            if (handle == ChromeHandle.BACK) {
                appendBack(data, window, lx, ly, hovered, grabbed)
                continue
            }
            if (handle == ChromeHandle.HUB) {
                appendGrabHandle(data, window, lx, ly, hovered, grabbed)
                continue
            }
            appendToolGlyph(data, window, handle, lx, ly, hovered, grabbed)
        }
    }

    private fun appendGlassPill(data: ArrayList<Float>, window: SpatialWindow) {
        val ly = WindowChrome.localIconY(ChromeHandle.HEIGHT, window, expanded = true)
        val w = WindowChrome.pillWidth()
        val h = WindowChrome.pillHeight()
        windowCapsule(data, window, 0f, ly, 0.028f, w * 1.04f, h * 1.18f, 0.55f, 0.58f, 0.64f, 0.16f)
        windowCapsule(data, window, 0f, ly, 0.030f, w, h, 0.86f, 0.89f, 0.94f, 0.30f)
        windowCapsule(data, window, 0f, ly + h * 0.22f, 0.032f, w * 0.86f, h * 0.18f, 0.98f, 0.99f, 1f, 0.22f)
        windowCapsuleStroke(data, window, 0f, ly, 0.033f, w, h, 0.96f, 0.97f, 0.99f, 0.42f)
    }

    private fun appendGrabHandle(
        data: ArrayList<Float>,
        window: SpatialWindow,
        lx: Float,
        ly: Float,
        hovered: Boolean,
        grabbed: Boolean,
    ) {
        val lit = hovered || grabbed
        val a = if (lit) 0.88f else 0.48f
        val c = if (lit) 0.98f else 0.90f
        val w = WindowChrome.handleVisualWidth(hovered, grabbed)
        val h = WindowChrome.handleVisualHeight(hovered, grabbed)
        windowCapsule(data, window, lx, ly, 0.030f, w * 1.08f, h * 1.35f, 0.50f, 0.52f, 0.58f, 0.20f)
        windowCapsule(data, window, lx, ly, 0.032f, w, h, c, c, c, a)
        windowCapsuleStroke(data, window, lx, ly, 0.034f, w, h, 1f, 1f, 1f, if (lit) 0.55f else 0.28f)
    }

    private fun appendToolGlyph(
        data: ArrayList<Float>,
        window: SpatialWindow,
        handle: ChromeHandle,
        lx: Float,
        ly: Float,
        hovered: Boolean,
        grabbed: Boolean,
    ) {
        val lit = hovered || grabbed
        if (lit) {
            windowCapsule(
                data, window, lx, ly, 0.034f,
                WindowChrome.toolPlateWidth(hovered, grabbed),
                WindowChrome.toolPlateHeight(hovered, grabbed),
                1f, 1f, 1f, if (grabbed) 0.28f else 0.16f,
            )
        }
        val c = if (lit) 1f else 0.92f
        val a = if (lit) 0.96f else 0.78f
        val scale = WindowChrome.glyphScale(hovered, grabbed)
        val s = WindowChrome.ICON * 0.42f * scale
        val z = 0.036f
        val t = WindowChrome.ICON * 0.055f * scale
        when (handle) {
            ChromeHandle.OFFSET -> {
                windowBar(data, window, lx, ly, z, s * 1.45f, t, 0f, c, c, c, a)
                windowBar(data, window, lx - s * 0.52f, ly + s * 0.28f, z, s * 0.50f, t, 0.70f, c, c, c, a)
                windowBar(data, window, lx - s * 0.52f, ly - s * 0.28f, z, s * 0.50f, t, -0.70f, c, c, c, a)
                windowBar(data, window, lx + s * 0.52f, ly + s * 0.28f, z, s * 0.50f, t, -0.70f, c, c, c, a)
                windowBar(data, window, lx + s * 0.52f, ly - s * 0.28f, z, s * 0.50f, t, 0.70f, c, c, c, a)
            }
            ChromeHandle.HEIGHT -> {
                windowBar(data, window, lx, ly, z, s * 1.45f, t, 1.5708f, c, c, c, a)
                windowBar(data, window, lx - s * 0.28f, ly + s * 0.50f, z, s * 0.50f, t, 0.70f, c, c, c, a)
                windowBar(data, window, lx + s * 0.28f, ly + s * 0.50f, z, s * 0.50f, t, -0.70f, c, c, c, a)
                windowBar(data, window, lx - s * 0.28f, ly - s * 0.50f, z, s * 0.50f, t, -0.70f, c, c, c, a)
                windowBar(data, window, lx + s * 0.28f, ly - s * 0.50f, z, s * 0.50f, t, 0.70f, c, c, c, a)
            }
            ChromeHandle.PITCH -> {
                windowRing(data, window, lx, ly, z, s * 0.72f, s * 0.52f, c, c, c, a, segs = 16)
                windowBar(data, window, lx + s * 0.62f, ly, z, s * 0.55f, t, 1.15f, c, c, c, a)
            }
            ChromeHandle.YAW -> {
                windowRing(data, window, lx, ly, z, s * 0.72f, s * 0.52f, c, c, c, a, segs = 16)
                windowBar(data, window, lx, ly + s * 0.62f, z, s * 0.55f, t, 0.40f, c, c, c, a)
            }
            ChromeHandle.SIZE -> {
                windowBar(data, window, lx - s * 0.42f, ly + s * 0.42f, z, s * 0.55f, t, 0f, c, c, c, a)
                windowBar(data, window, lx - s * 0.42f, ly + s * 0.42f, z, s * 0.55f, t, 1.5708f, c, c, c, a)
                windowBar(data, window, lx + s * 0.42f, ly + s * 0.42f, z, s * 0.55f, t, 0f, c, c, c, a)
                windowBar(data, window, lx + s * 0.42f, ly + s * 0.42f, z, s * 0.55f, t, 1.5708f, c, c, c, a)
                windowBar(data, window, lx - s * 0.42f, ly - s * 0.42f, z, s * 0.55f, t, 0f, c, c, c, a)
                windowBar(data, window, lx - s * 0.42f, ly - s * 0.42f, z, s * 0.55f, t, 1.5708f, c, c, c, a)
                windowBar(data, window, lx + s * 0.42f, ly - s * 0.42f, z, s * 0.55f, t, 0f, c, c, c, a)
                windowBar(data, window, lx + s * 0.42f, ly - s * 0.42f, z, s * 0.55f, t, 1.5708f, c, c, c, a)
            }
            ChromeHandle.DISTANCE -> {
                windowBar(data, window, lx, ly + s * 0.12f, z, s * 0.95f, t, 0.55f, c, c, c, a)
                windowBar(data, window, lx, ly + s * 0.12f, z, s * 0.95f, t, -0.55f, c, c, c, a)
                windowBar(data, window, lx, ly - s * 0.22f, z, s * 0.70f, t, 0.55f, c, c, c, a)
                windowBar(data, window, lx, ly - s * 0.22f, z, s * 0.70f, t, -0.55f, c, c, c, a)
            }
            ChromeHandle.HUB, ChromeHandle.CLOSE, ChromeHandle.BACK -> Unit
        }
    }

    private fun drawColor(mvp: FloatArray, buf: FloatBuffer, count: Int, mode: Int) {
        if (count <= 0) return
        GLES20.glUseProgram(colorProg)
        GLES20.glUniformMatrix4fv(uColorMvp, 1, false, mvp, 0)
        buf.position(0)
        GLES20.glVertexAttribPointer(aColorPos, 3, GLES20.GL_FLOAT, false, COLOR_BYTES, buf)
        GLES20.glEnableVertexAttribArray(aColorPos)
        buf.position(3)
        GLES20.glVertexAttribPointer(aColorCol, 4, GLES20.GL_FLOAT, false, COLOR_BYTES, buf)
        GLES20.glEnableVertexAttribArray(aColorCol)
        GLES20.glDrawArrays(mode, 0, count)
    }

    private fun buildFloor(): ArrayList<Float> {
        val data = ArrayList<Float>(64)
        xzQuad(data, 0f, -1.05f, SpatialLayout.ICON_Z, 3.4f, 2.8f, 0.05f, 0.07f, 0.10f, 0.22f)
        return data
    }

    private fun buildBezel(@Suppress("UNUSED_PARAMETER") window: SpatialWindow): ArrayList<Float> {
        return ArrayList(0)
    }

    private fun buildMarkers(): ArrayList<Float> {
        val data = ArrayList<Float>(512)
        cube(data, -1.55f, -0.15f, SpatialLayout.PANEL_Z, 0.14f, 0.90f, 0.22f, 0.22f)
        cube(data, 1.55f, -0.15f, SpatialLayout.PANEL_Z, 0.14f, 0.18f, 0.85f, 0.38f)
        return data
    }

    private fun buildGrid(): ArrayList<Float> {
        val data = ArrayList<Float>(1024)
        val y = -1.16f
        val r = 0.18f
        val g = 0.28f
        val b = 0.40f
        val a = 0.55f
        for (i in -5..5) {
            val x = i * 0.8f
            line(data, x, y, 0.4f, x, y, -8f, r, g, b, a)
            val z = -i * 0.8f
            line(data, -5f, y, z, 5f, y, z, r, g, b, a)
        }
        return data
    }

    private fun buildPanel(window: SpatialWindow, zOffset: Float = 0.004f): ArrayList<Float> {
        val data = ArrayList<Float>(40)
        val x0 = -window.width / 2
        val x1 = window.width / 2
        val y0 = -window.height / 2
        val y1 = window.height / 2
        val p00 = window.localToWorld(x0, y0, zOffset)
        val p10 = window.localToWorld(x1, y0, zOffset)
        val p11 = window.localToWorld(x1, y1, zOffset)
        val p01 = window.localToWorld(x0, y1, zOffset)
        uvTri(data, p00[0], p00[1], p00[2], 0f, 1f, p10[0], p10[1], p10[2], 1f, 1f, p11[0], p11[1], p11[2], 1f, 0f)
        uvTri(data, p00[0], p00[1], p00[2], 0f, 1f, p11[0], p11[1], p11[2], 1f, 0f, p01[0], p01[1], p01[2], 0f, 0f)
        return data
    }

    private fun buildIconTile(
        window: SpatialWindow,
        thickness: Float,
        hovered: Boolean,
    ): Pair<ArrayList<Float>, ArrayList<Float>> {
        val hw = window.width / 2f
        val hh = window.height / 2f
        val r = min(hw, hh) * 0.98f
        val zf = thickness * 0.48f
        val zb = -thickness * 0.50f
        val ring = roundRectRing(hw, hh, r, 5)
        val n = ring.size / 2
        val front = ArrayList<Float>(n * 15 + 16)
        val sides = ArrayList<Float>(n * 56 + 64)
        val cFront = window.localToWorld(0f, 0f, zf)
        val cBack = window.localToWorld(0f, 0f, zb)
        val boost = if (hovered) 1.42f else 1f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = ring[i * 2]
            val y0 = ring[i * 2 + 1]
            val x1 = ring[j * 2]
            val y1 = ring[j * 2 + 1]
            val a = window.localToWorld(x0, y0, zf)
            val b = window.localToWorld(x1, y1, zf)
            uvTri(
                front,
                cFront[0], cFront[1], cFront[2], 0.5f, 0.5f,
                a[0], a[1], a[2], x0 / window.width + 0.5f, 0.5f - y0 / window.height,
                b[0], b[1], b[2], x1 / window.width + 0.5f, 0.5f - y1 / window.height,
            )
            val c = window.localToWorld(x1, y1, zb)
            val d = window.localToWorld(x0, y0, zb)
            val nx = (x0 + x1) * 0.5f
            val ny = (y0 + y1) * 0.5f
            val nl = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-4f)
            val wr = window.localToWorld(nx / nl, ny / nl, 0f)
            val origin = window.localToWorld(0f, 0f, 0f)
            var sx = wr[0] - origin[0]
            var sy = wr[1] - origin[1]
            var sz = wr[2] - origin[2]
            val sl = sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-4f)
            sx /= sl
            sy /= sl
            sz /= sl
            val ndl = max(0.22f, sx * -0.42f + sy * 0.74f + sz * 0.52f) * boost
            val cr = 0.42f * ndl
            val cg = 0.44f * ndl
            val cb = 0.48f * ndl
            colorTri(sides, a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], cr, cg, cb, 1f)
            colorTri(sides, a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2], cr, cg, cb, 1f)
            val backA = window.localToWorld(x0, y0, zb)
            val backB = window.localToWorld(x1, y1, zb)
            colorTri(
                sides,
                cBack[0], cBack[1], cBack[2],
                backB[0], backB[1], backB[2],
                backA[0], backA[1], backA[2],
                cr * 0.55f, cg * 0.55f, cb * 0.55f, 1f,
            )
        }
        return front to sides
    }

    private fun buildIconGlow(window: SpatialWindow, thickness: Float): ArrayList<Float> {
        val radius = min(window.width, window.height) * 0.50f
        val z = -thickness * 0.08f
        val glow = ArrayList<Float>(512)
        val rings = floatArrayOf(1.18f, 1.38f, 1.62f, 1.92f, 2.28f)
        val alphas = floatArrayOf(0.28f, 0.18f, 0.11f, 0.06f, 0.028f)
        for (i in rings.indices) {
            appendDisc(
                glow, window, radius * rings[i], z - i * 0.0015f,
                0.82f, 0.90f, 1f, alphas[i],
            )
        }
        return glow
    }

    private fun appendDisc(
        data: ArrayList<Float>,
        window: SpatialWindow,
        radius: Float,
        z: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        segs: Int = 22,
    ) {
        val c = window.localToWorld(0f, 0f, z)
        var prev = window.localToWorld(radius, 0f, z)
        for (i in 1..segs) {
            val t = i / segs.toFloat() * (Math.PI * 2.0).toFloat()
            val p = window.localToWorld(cos(t) * radius, sin(t) * radius, z)
            colorTri(data, c[0], c[1], c[2], prev[0], prev[1], prev[2], p[0], p[1], p[2], r, g, b, a)
            prev = p
        }
    }

    private fun appendPageChevron(data: ArrayList<Float>, field: SpatialWindow, side: Int) {
        val tipX = side * (field.width / 2f + 0.062f)
        val inset = -side * 0.016f
        val lz = 0.018f
        val arm = 0.050f
        val thick = 0.0064f
        val a = 0.30f
        windowBar(
            data, field, tipX + inset, 0.015f, lz, arm, thick, side * 0.62f,
            0.96f, 0.98f, 1f, a,
        )
        windowBar(
            data, field, tipX + inset, -0.015f, lz, arm, thick, side * -0.62f,
            0.96f, 0.98f, 1f, a,
        )
        windowBar(
            data, field, tipX + inset, 0.015f, lz + 0.002f, arm * 0.86f, thick * 0.55f, side * 0.62f,
            1f, 1f, 1f, a * 0.55f,
        )
        windowBar(
            data, field, tipX + inset, -0.015f, lz + 0.002f, arm * 0.86f, thick * 0.55f, side * -0.62f,
            1f, 1f, 1f, a * 0.55f,
        )
    }

    private fun roundRectRing(hw: Float, hh: Float, radius: Float, segs: Int): FloatArray {
        val r = min(radius, min(hw, hh) * 0.92f)
        val out = FloatArray((segs + 1) * 8)
        var k = 0
        fun corner(cx: Float, cy: Float, a0: Float, a1: Float) {
            for (i in 0..segs) {
                val t = i / segs.toFloat()
                val a = a0 + (a1 - a0) * t
                out[k++] = cx + cos(a) * r
                out[k++] = cy + sin(a) * r
            }
        }
        corner(hw - r, -hh + r, (-Math.PI / 2.0).toFloat(), 0f)
        corner(hw - r, hh - r, 0f, (Math.PI / 2.0).toFloat())
        corner(-hw + r, hh - r, (Math.PI / 2.0).toFloat(), Math.PI.toFloat())
        corner(-hw + r, -hh + r, Math.PI.toFloat(), (Math.PI * 1.5).toFloat())
        return out
    }

    private fun placeLaser(beam: PointerBeam) {
        write(laserLines, buildLaser(beam))
        val data = ArrayList<Float>(HAND_FLOATS)
        appendPointingHand(data, beam.hitX, beam.hitY, beam.hitZ, 0.052f)
        write(laserDot, data)
        laserDotCount = minOf(data.size, laserDot.capacity()) / COLOR_STRIDE
    }

    private fun appendPointingHand(
        out: ArrayList<Float>,
        hx: Float,
        hy: Float,
        hz: Float,
        size: Float,
    ) {
        val len = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-4f)
        val zx = -hx / len
        val zy = -hy / len
        val zz = -hz / len
        var xx = zz
        var xy = 0f
        var xz = -zx
        val xl = sqrt(xx * xx + xy * xy + xz * xz).coerceAtLeast(1e-4f)
        xx /= xl
        xy /= xl
        xz /= xl
        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx
        fun vert(u: Float, v: Float): FloatArray {
            val px = hx + xx * u * size + yx * v * size + zx * 0.018f
            val py = hy + xy * u * size + yy * v * size + zy * 0.018f
            val pz = hz + xz * u * size + yz * v * size + zz * 0.018f
            return floatArrayOf(px, py, pz)
        }
        fun tri(
            u0: Float, v0: Float, u1: Float, v1: Float, u2: Float, v2: Float,
            r: Float, g: Float, b: Float, a: Float,
        ) {
            val p0 = vert(u0, v0)
            val p1 = vert(u1, v1)
            val p2 = vert(u2, v2)
            colorTri(out, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], r, g, b, a)
        }
        val outline = floatArrayOf(
            0.00f, 0.70f, -0.10f, 0.04f, 0.10f, 0.04f,
            -0.10f, 0.04f, 0.10f, 0.04f, 0.09f, -0.10f,
            0.08f, 0.00f, 0.22f, -0.08f, 0.20f, -0.46f,
            0.08f, 0.00f, 0.20f, -0.46f, 0.05f, -0.40f,
            0.18f, -0.10f, 0.32f, -0.18f, 0.29f, -0.52f,
            0.18f, -0.10f, 0.29f, -0.52f, 0.16f, -0.44f,
            0.28f, -0.20f, 0.40f, -0.28f, 0.36f, -0.54f,
            0.28f, -0.20f, 0.36f, -0.54f, 0.26f, -0.46f,
            -0.14f, 0.00f, 0.34f, -0.18f, 0.32f, -0.68f,
            -0.14f, 0.00f, 0.32f, -0.68f, -0.16f, -0.62f,
            -0.16f, -0.62f, 0.32f, -0.68f, 0.10f, -0.86f,
            -0.16f, -0.62f, 0.10f, -0.86f, -0.08f, -0.86f,
            -0.14f, -0.04f, -0.46f, -0.20f, -0.38f, -0.46f,
            -0.14f, -0.04f, -0.38f, -0.46f, -0.10f, -0.40f,
        )
        val fill = floatArrayOf(
            0.00f, 0.62f, -0.07f, 0.06f, 0.07f, 0.06f,
            -0.07f, 0.06f, 0.07f, 0.06f, 0.06f, -0.08f,
            0.07f, 0.00f, 0.18f, -0.08f, 0.16f, -0.42f,
            0.07f, 0.00f, 0.16f, -0.42f, 0.05f, -0.36f,
            0.16f, -0.10f, 0.26f, -0.16f, 0.24f, -0.48f,
            0.16f, -0.10f, 0.24f, -0.48f, 0.14f, -0.42f,
            0.24f, -0.18f, 0.33f, -0.24f, 0.30f, -0.50f,
            0.24f, -0.18f, 0.30f, -0.50f, 0.22f, -0.44f,
            -0.10f, 0.00f, 0.28f, -0.16f, 0.26f, -0.62f,
            -0.10f, 0.00f, 0.26f, -0.62f, -0.12f, -0.56f,
            -0.12f, -0.56f, 0.26f, -0.62f, 0.08f, -0.78f,
            -0.12f, -0.56f, 0.08f, -0.78f, -0.06f, -0.78f,
            -0.10f, -0.06f, -0.38f, -0.20f, -0.32f, -0.42f,
            -0.10f, -0.06f, -0.32f, -0.42f, -0.08f, -0.36f,
        )
        var i = 0
        while (i < outline.size) {
            tri(
                outline[i], outline[i + 1], outline[i + 2], outline[i + 3],
                outline[i + 4], outline[i + 5], 0.10f, 0.11f, 0.14f, 0.88f,
            )
            i += 6
        }
        i = 0
        while (i < fill.size) {
            tri(
                fill[i], fill[i + 1], fill[i + 2], fill[i + 3],
                fill[i + 4], fill[i + 5], 0.96f, 0.97f, 0.99f, 0.94f,
            )
            i += 6
        }
    }

    private fun buildLaser(beam: PointerBeam): ArrayList<Float> {
        val pose = poseRef.get()
        val view = PoseMath.viewFromQuat(pose.qw, pose.qx, pose.qy, pose.qz)
        val origin = PoseMath.worldFromViewLocal(view, PHONE_X, PHONE_Y, PHONE_Z)
        val data = ArrayList<Float>(LASER_FLOATS)
        beamQuad(
            data,
            origin[0], origin[1], origin[2],
            beam.hitX, beam.hitY, beam.hitZ,
            PHONE_GLOW, HIT_GLOW,
            0.35f, 1f, 0.92f, 0.28f,
        )
        beamQuad(
            data,
            origin[0], origin[1], origin[2],
            beam.hitX, beam.hitY, beam.hitZ,
            PHONE_CORE, HIT_CORE,
            0.75f, 1f, 0.98f, 0.95f,
        )
        return data
    }

    private fun beamQuad(
        out: MutableList<Float>,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        widthA: Float,
        widthB: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        val dx = bx - ax
        val dy = by - ay
        val dz = bz - az
        var sx = dy * az - dz * ay
        var sy = dz * ax - dx * az
        var sz = dx * ay - dy * ax
        var sl = kotlin.math.sqrt(sx * sx + sy * sy + sz * sz)
        if (sl < 1e-5f) {
            sx = 1f
            sy = 0f
            sz = 0f
            sl = 1f
        }
        val inv = 1f / sl
        sx *= inv
        sy *= inv
        sz *= inv
        val hx0 = sx * widthA * 0.5f
        val hy0 = sy * widthA * 0.5f
        val hz0 = sz * widthA * 0.5f
        val hx1 = sx * widthB * 0.5f
        val hy1 = sy * widthB * 0.5f
        val hz1 = sz * widthB * 0.5f
        val x0 = ax - hx0
        val y0 = ay - hy0
        val z0 = az - hz0
        val x1 = ax + hx0
        val y1 = ay + hy0
        val z1 = az + hz0
        val x2 = bx + hx1
        val y2 = by + hy1
        val z2 = bz + hz1
        val x3 = bx - hx1
        val y3 = by - hy1
        val z3 = bz - hz1
        colorTri(out, x0, y0, z0, x1, y1, z1, x2, y2, z2, r, g, b, a)
        colorTri(out, x0, y0, z0, x2, y2, z2, x3, y3, z3, r, g, b, a)
    }

    private fun appendClose(
        data: ArrayList<Float>,
        window: SpatialWindow,
        lx: Float,
        ly: Float,
        hovered: Boolean,
        grabbed: Boolean,
    ) {
        val lit = hovered || grabbed
        val a = if (lit) 0.92f else 0.42f
        val c = if (lit) 1f else 0.88f
        val outer = WindowChrome.closeVisual(hovered, grabbed)
        val inner = outer * 0.82f
        windowRing(data, window, lx, ly, 0.034f, outer, inner, c, c, c, a)
        val bar = outer * 1.55f
        val thick = outer * 0.14f
        windowBar(data, window, lx, ly, 0.036f, bar, thick, 0.785398f, c, c, c, a)
        windowBar(data, window, lx, ly, 0.036f, bar, thick, -0.785398f, c, c, c, a)
    }

    private fun appendBack(
        data: ArrayList<Float>,
        window: SpatialWindow,
        lx: Float,
        ly: Float,
        hovered: Boolean,
        grabbed: Boolean,
    ) {
        val lit = hovered || grabbed
        val a = if (lit) 0.92f else 0.42f
        val c = if (lit) 1f else 0.88f
        val outer = WindowChrome.closeVisual(hovered, grabbed)
        val inner = outer * 0.82f
        windowRing(data, window, lx, ly, 0.034f, outer, inner, c, c, c, a)
        val bar = outer * 1.15f
        val thick = outer * 0.16f
        windowBar(data, window, lx - outer * 0.06f, ly, 0.036f, bar, thick, 0.55f, c, c, c, a)
        windowBar(data, window, lx - outer * 0.06f, ly, 0.036f, bar, thick, -0.55f, c, c, c, a)
    }

    private fun appendPointer(
        data: ArrayList<Float>,
        window: SpatialWindow,
        lx: Float,
        ly: Float,
        s: Float,
    ) {
        pointerMesh(data, window, lx, ly, s * 1.14f, 0.023f, 0.07f, 0.08f, 0.10f, 0.78f)
        pointerMesh(data, window, lx, ly, s, 0.025f, 0.96f, 0.97f, 0.99f, 0.94f)
    }

    private fun pointerMesh(
        data: ArrayList<Float>,
        window: SpatialWindow,
        lx: Float,
        ly: Float,
        s: Float,
        z: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        val p1 = window.localToWorld(lx, ly, z)
        val p2 = window.localToWorld(lx + s * 0.22f, ly - s * 0.78f, z)
        val p3 = window.localToWorld(lx + s * 0.38f, ly - s * 0.42f, z)
        val p4 = window.localToWorld(lx + s * 0.72f, ly - s * 0.70f, z)
        val p5 = window.localToWorld(lx + s * 0.82f, ly - s * 0.52f, z)
        val p6 = window.localToWorld(lx + s * 0.48f, ly - s * 0.28f, z)
        val p7 = window.localToWorld(lx + s * 0.70f, ly - s * 0.08f, z)
        colorTri(data, p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2], r, g, b, a)
        colorTri(data, p1[0], p1[1], p1[2], p3[0], p3[1], p3[2], p6[0], p6[1], p6[2], r, g, b, a)
        colorTri(data, p1[0], p1[1], p1[2], p6[0], p6[1], p6[2], p7[0], p7[1], p7[2], r, g, b, a)
        colorTri(data, p3[0], p3[1], p3[2], p4[0], p4[1], p4[2], p5[0], p5[1], p5[2], r, g, b, a)
        colorTri(data, p3[0], p3[1], p3[2], p5[0], p5[1], p5[2], p6[0], p6[1], p6[2], r, g, b, a)
    }

    private fun windowRing(
        out: MutableList<Float>,
        window: SpatialWindow,
        lx: Float,
        ly: Float,
        lz: Float,
        outer: Float,
        inner: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        segs: Int = 22,
    ) {
        for (i in 0 until segs) {
            val a0 = i * (Math.PI * 2.0 / segs)
            val a1 = (i + 1) * (Math.PI * 2.0 / segs)
            val c0 = cos(a0).toFloat()
            val s0 = sin(a0).toFloat()
            val c1 = cos(a1).toFloat()
            val s1 = sin(a1).toFloat()
            val o0 = window.localToWorld(lx + c0 * outer, ly + s0 * outer, lz)
            val o1 = window.localToWorld(lx + c1 * outer, ly + s1 * outer, lz)
            val i0 = window.localToWorld(lx + c0 * inner, ly + s0 * inner, lz)
            val i1 = window.localToWorld(lx + c1 * inner, ly + s1 * inner, lz)
            colorTri(out, o0[0], o0[1], o0[2], o1[0], o1[1], o1[2], i1[0], i1[1], i1[2], r, g, b, a)
            colorTri(out, o0[0], o0[1], o0[2], i1[0], i1[1], i1[2], i0[0], i0[1], i0[2], r, g, b, a)
        }
    }

    private fun windowBar(
        out: MutableList<Float>,
        window: SpatialWindow,
        lx: Float,
        ly: Float,
        lz: Float,
        length: Float,
        thickness: Float,
        angle: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        val c = cos(angle)
        val s = sin(angle)
        val hl = length / 2f
        val ht = thickness / 2f
        fun corner(px: Float, py: Float): FloatArray {
            return window.localToWorld(lx + c * px - s * py, ly + s * px + c * py, lz)
        }
        val p00 = corner(-hl, -ht)
        val p10 = corner(hl, -ht)
        val p11 = corner(hl, ht)
        val p01 = corner(-hl, ht)
        colorTri(out, p00[0], p00[1], p00[2], p10[0], p10[1], p10[2], p11[0], p11[1], p11[2], r, g, b, a)
        colorTri(out, p00[0], p00[1], p00[2], p11[0], p11[1], p11[2], p01[0], p01[1], p01[2], r, g, b, a)
    }

    private fun windowCapsule(
        out: MutableList<Float>,
        window: SpatialWindow,
        lx: Float,
        ly: Float,
        lz: Float,
        width: Float,
        height: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        val rad = min(width, height) * 0.5f
        val body = max(width - 2f * rad, 0f)
        if (body > 1e-4f) {
            windowQuad(out, window, lx, ly, lz, body, height, r, g, b, a)
        }
        windowDisc(out, window, lx - body / 2f, ly, lz, rad, r, g, b, a)
        windowDisc(out, window, lx + body / 2f, ly, lz, rad, r, g, b, a)
    }

    private fun windowCapsuleStroke(
        out: MutableList<Float>,
        window: SpatialWindow,
        lx: Float,
        ly: Float,
        lz: Float,
        width: Float,
        height: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        val rad = min(width, height) * 0.5f
        val body = max(width - 2f * rad, 0f)
        val thick = min(rad * 0.22f, 0.0038f)
        windowRing(out, window, lx - body / 2f, ly, lz, rad, rad - thick, r, g, b, a, segs = 16)
        windowRing(out, window, lx + body / 2f, ly, lz, rad, rad - thick, r, g, b, a, segs = 16)
        if (body > 1e-4f) {
            windowQuad(out, window, lx, ly + rad - thick / 2f, lz, body, thick, r, g, b, a)
            windowQuad(out, window, lx, ly - rad + thick / 2f, lz, body, thick, r, g, b, a)
        }
    }

    private fun windowDisc(
        out: MutableList<Float>,
        window: SpatialWindow,
        lx: Float,
        ly: Float,
        lz: Float,
        radius: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        segs: Int = 14,
    ) {
        val c = window.localToWorld(lx, ly, lz)
        for (i in 0 until segs) {
            val a0 = i * (Math.PI * 2.0 / segs)
            val a1 = (i + 1) * (Math.PI * 2.0 / segs)
            val p0 = window.localToWorld(lx + cos(a0).toFloat() * radius, ly + sin(a0).toFloat() * radius, lz)
            val p1 = window.localToWorld(lx + cos(a1).toFloat() * radius, ly + sin(a1).toFloat() * radius, lz)
            colorTri(out, c[0], c[1], c[2], p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], r, g, b, a)
        }
    }

    private fun windowQuad(
        out: MutableList<Float>,
        window: SpatialWindow,
        lx: Float,
        ly: Float,
        lz: Float,
        w: Float,
        h: Float,
        r: Float,
        g: Float,
        b: Float,
        a: Float,
    ) {
        val x0 = lx - w / 2
        val x1 = lx + w / 2
        val y0 = ly - h / 2
        val y1 = ly + h / 2
        val p00 = window.localToWorld(x0, y0, lz)
        val p10 = window.localToWorld(x1, y0, lz)
        val p11 = window.localToWorld(x1, y1, lz)
        val p01 = window.localToWorld(x0, y1, lz)
        colorTri(out, p00[0], p00[1], p00[2], p10[0], p10[1], p10[2], p11[0], p11[1], p11[2], r, g, b, a)
        colorTri(out, p00[0], p00[1], p00[2], p11[0], p11[1], p11[2], p01[0], p01[1], p01[2], r, g, b, a)
    }

    private fun quadColor(
        out: MutableList<Float>,
        cx: Float, cy: Float, cz: Float,
        w: Float, h: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        val x0 = cx - w / 2
        val x1 = cx + w / 2
        val y0 = cy - h / 2
        val y1 = cy + h / 2
        colorTri(out, x0, y0, cz, x1, y0, cz, x1, y1, cz, r, g, b, a)
        colorTri(out, x0, y0, cz, x1, y1, cz, x0, y1, cz, r, g, b, a)
    }

    private fun xzQuad(
        out: MutableList<Float>,
        cx: Float, y: Float, cz: Float,
        w: Float, d: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        val x0 = cx - w / 2
        val x1 = cx + w / 2
        val z0 = cz - d / 2
        val z1 = cz + d / 2
        colorTri(out, x0, y, z0, x1, y, z0, x1, y, z1, r, g, b, a)
        colorTri(out, x0, y, z0, x1, y, z1, x0, y, z1, r, g, b, a)
    }

    private fun cube(
        out: MutableList<Float>,
        cx: Float, cy: Float, cz: Float, s: Float,
        r: Float, g: Float, b: Float,
    ) {
        val h = s / 2
        val x0 = cx - h
        val x1 = cx + h
        val y0 = cy - h
        val y1 = cy + h
        val z0 = cz - h
        val z1 = cz + h
        colorTri(out, x0, y0, z1, x1, y0, z1, x1, y1, z1, r, g, b, 1f)
        colorTri(out, x0, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, 1f)
        colorTri(out, x1, y0, z0, x0, y0, z0, x0, y1, z0, r * 0.6f, g, b, 1f)
        colorTri(out, x1, y0, z0, x0, y1, z0, x1, y1, z0, r * 0.6f, g, b, 1f)
        colorTri(out, x0, y0, z0, x0, y0, z1, x0, y1, z1, r * 0.8f, g, b, 1f)
        colorTri(out, x0, y0, z0, x0, y1, z1, x0, y1, z0, r * 0.8f, g, b, 1f)
        colorTri(out, x1, y0, z1, x1, y0, z0, x1, y1, z0, r * 0.8f, g, b, 1f)
        colorTri(out, x1, y0, z1, x1, y1, z0, x1, y1, z1, r * 0.8f, g, b, 1f)
        colorTri(out, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g * 1.05f, b, 1f)
        colorTri(out, x0, y1, z1, x1, y1, z0, x0, y1, z0, r, g * 1.05f, b, 1f)
    }

    private fun line(
        out: MutableList<Float>,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        colorVert(out, x0, y0, z0, r, g, b, a)
        colorVert(out, x1, y1, z1, r, g, b, a)
    }

    private fun colorTri(
        out: MutableList<Float>,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        colorVert(out, x0, y0, z0, r, g, b, a)
        colorVert(out, x1, y1, z1, r, g, b, a)
        colorVert(out, x2, y2, z2, r, g, b, a)
    }

    private fun colorVert(out: MutableList<Float>, x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
        out.add(x); out.add(y); out.add(z)
        out.add(r); out.add(g); out.add(b); out.add(a)
    }

    private fun uvTri(
        out: MutableList<Float>,
        x0: Float, y0: Float, z0: Float, u0: Float, v0: Float,
        x1: Float, y1: Float, z1: Float, u1: Float, v1: Float,
        x2: Float, y2: Float, z2: Float, u2: Float, v2: Float,
    ) {
        out.add(x0); out.add(y0); out.add(z0); out.add(u0); out.add(v0)
        out.add(x1); out.add(y1); out.add(z1); out.add(u1); out.add(v1)
        out.add(x2); out.add(y2); out.add(z2); out.add(u2); out.add(v2)
    }

    private fun pack(data: List<Float>): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (v in data) buf.put(v)
        buf.position(0)
        return buf
    }

    private fun packUv(data: List<Float>): FloatBuffer = pack(data)

    private fun write(buf: FloatBuffer, data: List<Float>) {
        buf.position(0)
        val n = minOf(data.size, buf.capacity())
        for (i in 0 until n) buf.put(data[i])
        buf.position(0)
    }

    private fun writeUv(buf: FloatBuffer, data: List<Float>) = write(buf, data)

    private fun genTex(mipmaps: Boolean = false): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        val minFilter = if (mipmaps) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, minFilter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (mipmaps) applyAniso()
        return ids[0]
    }

    private fun queryMaxAniso(): Float {
        val exts = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: return 1f
        if (!exts.contains("GL_EXT_texture_filter_anisotropic")) return 1f
        val max = FloatArray(1)
        GLES20.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, max, 0)
        return max[0].coerceAtLeast(1f)
    }

    private fun applyAniso() {
        if (maxAniso <= 1f) return
        GLES20.glTexParameterf(
            GLES20.GL_TEXTURE_2D,
            GL_TEXTURE_MAX_ANISOTROPY_EXT,
            min(8f, maxAniso),
        )
    }

    private fun link(vsSrc: String, fsSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(LogTags.DISPLAY, "shader link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        }
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(LogTags.DISPLAY, "shader compile failed: ${GLES20.glGetShaderInfoLog(sh)}")
        }
        return sh
    }

    companion object {
        private const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
        private const val GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF
        private const val COLOR_STRIDE = 7
        private const val COLOR_BYTES = COLOR_STRIDE * 4
        private const val UV_STRIDE = 5
        private const val UV_BYTES = UV_STRIDE * 4
        private const val LASER_VERTS = 12
        private const val LASER_FLOATS = LASER_VERTS * COLOR_STRIDE
        private const val CURSOR_FLOATS = 256
        private const val HAND_FLOATS = 588
        private const val CHROME_FLOATS = 24576
        private const val DECOR_FLOATS = 1024
        private const val UNSET_HOVER = "\u0001"
        private const val PHONE_X = 0.05f
        private const val PHONE_Y = -0.40f
        private const val PHONE_Z = -0.20f
        private const val PHONE_GLOW = 0.028f
        private const val HIT_GLOW = 0.010f
        private const val PHONE_CORE = 0.007f
        private const val HIT_CORE = 0.0028f
        private const val COLOR_VS = """
            uniform mat4 uMvp;
            attribute vec3 aPos;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vColor = aColor;
            }
        """
        private const val COLOR_FS = """
            precision mediump float;
            varying vec4 vColor;
            void main() {
                gl_FragColor = vColor;
            }
        """
        private const val TEX_VS = """
            uniform mat4 uMvp;
            attribute vec3 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vUv = aUv;
            }
        """
        private const val TEX_FS = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uOpaque;
            uniform float uBright;
            varying vec2 vUv;
            void main() {
                vec4 c = texture2D(uTex, vUv);
                if (c.a < 0.12) discard;
                gl_FragColor = vec4(min(c.rgb * uBright, 1.0), mix(c.a, 1.0, uOpaque));
            }
        """
    }
}
