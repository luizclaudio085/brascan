package com.brastoy.brascan.ar

import android.content.Context
import android.media.Image
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import com.brastoy.brascan.scan.Layout
import com.brastoy.brascan.three.GlSceneRenderer
import com.brastoy.brascan.three.Line3
import com.brastoy.brascan.three.MsaaChooser
import com.brastoy.brascan.three.Scene
import com.brastoy.brascan.three.V3
import com.brastoy.brascan.vision.Catalog
import com.brastoy.brascan.vision.FrameProcessor
import com.brastoy.brascan.vision.FrameSample
import com.brastoy.brascan.vision.FusionMap
import com.brastoy.brascan.vision.Piece3D
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan2

/** O que a tela mostra enquanto escaneia (atualizado pela thread de GL / de processamento). */
data class ScanStatus(
    val tracking: Boolean = false,
    val hint: String = "Iniciando câmera…",
    val depth: Boolean = false,
    val observations: Int = 0,
    val pieces: List<Piece3D> = emptyList(),
    val coverage: Int = 0,
)

/**
 * SLAM com ARCore: a cada ~300 ms copia imagem + pose + profundidade + nuvem de pontos,
 * processa numa thread separada (segmentação → pontos 3D → mapa de fusão) e redesenha a reconstrução por cima da câmera.
 */
class ArScanView(ctx: Context, private val session: Session, private val onStatus: (ScanStatus) -> Unit) : GLSurfaceView(ctx), GLSurfaceView.Renderer {
    val map = FusionMap()
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val gl = GlSceneRenderer()
    private var bgProg = 0; private var tex = 0
    private val ndc = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val uv = FloatArray(8)
    private val view = FloatArray(16); private val proj = FloatArray(16)
    private var lastSample = 0L; private var lastExtract = 0L
    @Volatile private var pieces: List<Piece3D> = emptyList()
    @Volatile private var cloud: List<Pair<V3, Int>> = emptyList()
    private val sectors = BooleanArray(12)
    @Volatile private var depthOn = false
    @Volatile private var hint = "Iniciando câmera…"
    @Volatile private var tracking = false

    init {
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaChooser())
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        depthOn = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        session.configure(Config(session).apply {
            depthMode = if (depthOn) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            lightEstimationMode = Config.LightEstimationMode.DISABLED
        })
    }

    fun reset() { map.clear(); pieces = emptyList(); cloud = emptyList(); sectors.fill(false); publish() }
    fun result() = pieces
    fun shutdown() { worker.shutdownNow() }

    private fun publish() = onStatus(ScanStatus(tracking, hint, depthOn, map.observations, pieces, sectors.count { it }))

    override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); tex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        bgProg = GlSceneRenderer.program(
            "attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ vUv=aUv; gl_Position=vec4(aPos,0.0,1.0); }",
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vUv; uniform samplerExternalOES uTex; void main(){ gl_FragColor=texture2D(uTex,vUv); }",
        )
        gl.init()
        session.setCameraTextureName(tex)
    }

    override fun onSurfaceChanged(g: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        val rot = if (Build.VERSION.SDK_INT >= 30) context.display?.rotation ?: 0 else @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        session.setDisplayGeometry(rot, w, h)
    }

    override fun onDrawFrame(g: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val frame = try { session.update() } catch (e: Exception) { return }
        if (frame.timestamp == 0L) return
        if (frame.hasDisplayGeometryChanged()) frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc, Coordinates2d.TEXTURE_NORMALIZED, uv)
        drawBackground()

        val cam = frame.camera
        tracking = cam.trackingState == TrackingState.TRACKING
        if (!tracking) {
            hint = when (cam.trackingFailureReason) {
                TrackingFailureReason.INSUFFICIENT_LIGHT -> "Pouca luz: acenda uma luz"
                TrackingFailureReason.EXCESSIVE_MOTION -> "Movendo rápido demais: vá mais devagar"
                TrackingFailureReason.INSUFFICIENT_FEATURES -> "Aponte para a mesa com a construção (superfície lisa demais)"
                else -> "Mova o celular devagar para começar o rastreamento"
            }
            publish(); return
        }
        cam.getViewMatrix(view, 0)
        cam.getProjectionMatrix(proj, 0, .03f, 30f)

        val now = System.currentTimeMillis()
        if (now - lastSample > 280 && busy.compareAndSet(false, true)) {
            lastSample = now
            val sample = capture(frame)
            if (sample == null) busy.set(false) else worker.execute {
                try {
                    FrameProcessor.process(sample, map)
                    coverage(sample.camToWorld)
                    if (System.currentTimeMillis() - lastExtract > 1000) {
                        lastExtract = System.currentTimeMillis()
                        pieces = map.extract()
                        cloud = map.cloud(2).let { c -> if (c.size > 6000) c.filterIndexed { i, _ -> i % (c.size / 6000 + 1) == 0 } else c }
                    }
                    hint = when {
                        map.observations < 500 -> "Aponte para as peças coloridas"
                        sectors.count { it } < 5 -> "Ótimo! Agora ande em volta para ver outros lados"
                        pieces.isEmpty() -> "Juntando pontos… chegue um pouco mais perto"
                        else -> "${pieces.size} peças reconstruídas — continue em volta para refinar"
                    }
                } finally { busy.set(false); publish() }
            }
        }

        val scene = Scene()
        for (p in pieces) {
            val o = Layout.outline(p.shape, Catalog.EDGE_M.getValue(p.size))
            scene.prism(o, p.center, p.u, p.v, Catalog.THICKNESS_M, Color(p.color), -1, .55f, Color.White)
            scene.lines += Line3(p.center, p.center + p.n * .025f, Color(0xFF7FD6FF), 3f)
        }
        val pose = cam.pose
        gl.draw(scene, view, proj, V3(pose.tx(), pose.ty(), pose.tz()), cloud, pointSize = 5f)
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false)
        GLES20.glUseProgram(bgProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(bgProg, "uTex"), 0)
        GlSceneRenderer.attr(bgProg, "aPos", GlSceneRenderer.buffer(ndc), 0, 2, 8)
        GlSceneRenderer.attr(bgProg, "aUv", GlSceneRenderer.buffer(uv), 0, 2, 8)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        for (i in 0 until 4) GLES20.glDisableVertexAttribArray(i)
        GLES20.glDepthMask(true)
    }

    /** Copia tudo o que o processamento precisa (as imagens do ARCore precisam ser fechadas já). */
    private fun capture(frame: Frame): FrameSample? {
        val cam = frame.camera
        val (rgb, w, h) = try { frame.acquireCameraImage().use { yuvToArgb(it, 240) } } catch (e: NotYetAvailableException) { return null } catch (e: Exception) { return null }
        val intr = cam.imageIntrinsics
        val k = w.toFloat() / intr.imageDimensions[0]
        val m = FloatArray(16); cam.pose.toMatrix(m, 0)
        var depth: ShortArray? = null; var dw = 0; var dh = 0
        if (depthOn) try {
            frame.acquireDepthImage16Bits().use { d ->
                dw = d.width; dh = d.height
                val pl = d.planes[0]; val buf = pl.buffer.order(ByteOrder.LITTLE_ENDIAN)
                depth = ShortArray(dw * dh) { i -> buf.getShort((i / dw) * pl.rowStride + (i % dw) * pl.pixelStride) }
            }
        } catch (_: Exception) { depth = null }
        val cloud = try { frame.acquirePointCloud().use { pc -> FloatArray(pc.points.remaining()).also { pc.points.get(it) } } } catch (_: Exception) { null }
        return FrameSample(rgb, w, h, intr.focalLength[0] * k, intr.focalLength[1] * k, intr.principalPoint[0] * k, intr.principalPoint[1] * k, m, depth, dw, dh, cloud)
    }

    /** Em quantos dos 12 setores (30°) em volta da construção a câmera já esteve. */
    private fun coverage(m: FloatArray) {
        val c = cloud.takeIf { it.isNotEmpty() } ?: return
        var x = 0f; var z = 0f; for ((p, _) in c) { x += p.x; z += p.z }
        x /= c.size; z /= c.size
        val a = atan2(m[14] - z, m[12] - x)
        sectors[(((a + Math.PI) / (2 * Math.PI) * 12).toInt()).coerceIn(0, 11)] = true
    }

    companion object {
        /** YUV_420_888 → ARGB reduzido (amostragem direta, rápido o bastante para 240 px de largura). */
        fun yuvToArgb(img: Image, outW: Int): Triple<IntArray, Int, Int> {
            val w = img.width; val h = img.height; val oh = h * outW / w
            val yp = img.planes[0]; val up = img.planes[1]; val vp = img.planes[2]
            val yb: ByteBuffer = yp.buffer; val ub = up.buffer; val vb = vp.buffer
            val out = IntArray(outW * oh)
            for (oy in 0 until oh) {
                val sy = oy * h / oh
                for (ox in 0 until outW) {
                    val sx = ox * w / outW
                    val yy = (yb.get(sy * yp.rowStride + sx * yp.pixelStride).toInt() and 255).toFloat()
                    val u = (ub.get((sy / 2) * up.rowStride + (sx / 2) * up.pixelStride).toInt() and 255) - 128f
                    val v = (vb.get((sy / 2) * vp.rowStride + (sx / 2) * vp.pixelStride).toInt() and 255) - 128f
                    val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
                    val g = (yy - .344f * u - .714f * v).toInt().coerceIn(0, 255)
                    val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
                    out[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Triple(out, outW, oh)
        }
    }
}
