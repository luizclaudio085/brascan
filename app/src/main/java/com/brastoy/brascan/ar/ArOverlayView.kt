package com.brastoy.brascan.ar

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import androidx.compose.ui.graphics.Color
import com.brastoy.brascan.three.GlSceneRenderer
import com.brastoy.brascan.three.MsaaChooser
import com.brastoy.brascan.three.Scene
import com.brastoy.brascan.three.TileMesh
import com.brastoy.brascan.three.V3
import com.brastoy.brascan.vision.Catalog
import com.brastoy.brascan.vision.PieceSlam
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

/**
 * Realidade aumentada sem ARCore: as peças reconhecidas, desenhadas em 3D exatamente onde a câmera as vê.
 * Superfície transparente por cima da pré-visualização; a projeção usa os intrínsecos reais da câmera e o
 * mesmo recorte FILL_CENTER da pré-visualização, então o desenho cai em cima da peça de verdade.
 */
class ArOverlayView(ctx: Context, onTopOfWindow: Boolean) : GLSurfaceView(ctx), GLSurfaceView.Renderer {
    @Volatile var frame: OverlayFrame? = null
    private val gl = GlSceneRenderer()
    private var w = 1; private var h = 1
    private var scene = Scene()
    private var sceneKey: Any? = null
    private var lastBuild = 0L
    private val view = FloatArray(16); private val proj = FloatArray(16)

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaChooser())
        holder.setFormat(PixelFormat.TRANSLUCENT)
        // pré-visualização em SurfaceView: a RA fica entre a câmera e os botões; em TextureView só dá por cima da janela toda
        if (onTopOfWindow) setZOrderOnTop(true) else setZOrderMediaOverlay(true)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) { gl.init() }
    override fun onSurfaceChanged(g: GL10?, width: Int, height: Int) { w = width; h = height; GLES20.glViewport(0, 0, w, h) }

    override fun onDrawFrame(g: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val f = frame ?: return
        val cam = f.cam ?: return
        // a cena só é refeita quando alguma peça muda de lugar (> 5 mm / ~3°) ou de estado, no máximo ~6×/s
        val key = f.pieces.map { (p, n) -> listOf(p.shape, p.size, p.color, n >= PieceSlam.MIN_VIEWS, q(p.center, 200f), q(p.u, 20f), q(p.v, 20f)) } + f.lost
        val now = System.currentTimeMillis()
        if (key != sceneKey && (sceneKey == null || now - lastBuild > 150)) {
            scene = Scene().also { s -> for ((p, n) in f.pieces) TileMesh.build(s, p.shape, p.size, p.center, p.u, p.v, Catalog.edgeM(p.size), Color(p.color), -1, false, shadow = false, ghost = if (f.lost) .3f else if (n >= PieceSlam.MIN_VIEWS) .85f else .4f) }
            sceneKey = key; lastBuild = now
        }
        OverlayMath.viewMatrix(cam, view)
        OverlayMath.projection(f.k, f.w, f.h, f.rot, w, h, proj)
        gl.clipY = 9000f; gl.clipX = 9000f
        gl.draw(scene, view, proj, V3(cam.t[0].toFloat(), cam.t[1].toFloat(), cam.t[2].toFloat()))
    }

    private fun q(v: V3, s: Float) = listOf((v.x * s).roundToInt(), (v.y * s).roundToInt(), (v.z * s).roundToInt())
}
