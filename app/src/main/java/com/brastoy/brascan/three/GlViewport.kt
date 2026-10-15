package com.brastoy.brascan.three

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI

/** EGL com antisserrilhado 4× quando o aparelho suporta (senão cai para o padrão). */
class MsaaChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        fun pick(samples: Int): EGLConfig? {
            val attrs = intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_SAMPLE_BUFFERS, if (samples > 0) 1 else 0, EGL10.EGL_SAMPLES, samples, EGL10.EGL_NONE)
            val n = IntArray(1); val out = arrayOfNulls<EGLConfig>(1)
            return if (egl.eglChooseConfig(display, attrs, out, 1, n) && n[0] > 0) out[0] else null
        }
        return pick(4) ?: pick(0) ?: error("Sem configuração EGL")
    }
}

/** Cena 3D com OpenGL: arraste gira, pinça dá zoom, toque duplo recentraliza, toque seleciona peça. */
class OrbitGlView(ctx: Context) : GLSurfaceView(ctx), GLSurfaceView.Renderer {
    var camera = Camera()
    var build: (Scene) -> Unit = {}
    /** A cena só é refeita quando a chave muda (peças, seleção…); girar a câmera não reconstrói nada. */
    @Volatile var key: Any? = Unit
    private var builtKey: Any? = null
    private var scene: Scene = Scene()
    var autoRotate = true
    @Volatile var lastTouch = 0L
    @Volatile var lastScene: Scene = Scene()
    private val gl = GlSceneRenderer()
    private val view = FloatArray(16); private val proj = FloatArray(16)
    private var w = 1; private var h = 1; private var last = 0L

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaChooser())
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSurfaceCreated(g: GL10?, c: EGLConfig?) { gl.init() }
    override fun onSurfaceChanged(g: GL10?, width: Int, height: Int) { w = width; h = height; GLES20.glViewport(0, 0, w, h) }

    override fun onDrawFrame(g: GL10?) {
        val now = System.nanoTime()
        if (last != 0L && autoRotate && System.currentTimeMillis() - lastTouch > 2500) camera.yaw += (now - last) / 1e9f * .25f
        last = now
        GLES20.glClearColor(.02f, .03f, .06f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        gl.background(0x0B1730, 0x04070E)
        val k = key
        if (k != builtKey || builtKey == null) { scene = Scene().also(build); builtKey = k ?: Any() }
        lastScene = scene
        gl.orbit(camera, w, h, view, proj)
        gl.draw(scene, view, proj, camera.eye())
    }

    /** Toque → peça (usa a projeção em CPU, igual à do Canvas). */
    fun pick(p: Offset): Int {
        val f = com.brastoy.brascan.three.Renderer.project(lastScene, camera, width.toFloat(), height.toFloat())
        return com.brastoy.brascan.three.Renderer.hit(f.faces, p)
    }
}

@Composable
fun GlViewport(modifier: Modifier, camera: Camera, key: Any?, autoRotate: Boolean = true, onTap: ((Int) -> Unit)? = null, build: (Scene) -> Unit) {
    val buildState = rememberUpdatedState(build)
    val tapState = rememberUpdatedState(onTap)
    val home = remember { Triple(camera.yaw, camera.pitch, camera.distance) }
    val ref = remember { arrayOfNulls<OrbitGlView>(1) }
    Box(modifier) {
        AndroidView(
            factory = { ctx -> OrbitGlView(ctx).also { it.camera = camera; ref[0] = it } },
            update = { it.camera = camera; it.autoRotate = autoRotate; it.build = { s -> buildState.value(s) }; it.key = key; ref[0] = it },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        ref[0]?.lastTouch = System.currentTimeMillis()
                        camera.yaw -= pan.x * .008f
                        camera.pitch = (camera.pitch + pan.y * .006f).coerceIn(-.15f, (PI / 2 - .05).toFloat())
                        camera.distance = (camera.distance / zoom).coerceIn(.3f, 40f)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { camera.yaw = home.first; camera.pitch = home.second; camera.distance = home.third; ref[0]?.lastTouch = System.currentTimeMillis() },
                        onTap = { p -> ref[0]?.let { v -> v.lastTouch = System.currentTimeMillis(); tapState.value?.invoke(v.pick(p)) } },
                    )
                }
        )
    }
    DisposableEffect(Unit) { onDispose { ref[0]?.onPause() } }
}
