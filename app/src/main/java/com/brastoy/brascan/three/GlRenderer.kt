package com.brastoy.brascan.three

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Desenha uma [Scene] com OpenGL ES 2: z-buffer (sem os erros de ordem do Canvas),
 * iluminação difusa + contorno, sombras no chão, linhas e pontos. Usado pela aba 3D e pelo AR.
 */
class GlSceneRenderer {
    private var mesh = 0; private var flat = 0
    private val mvp = FloatArray(16)

    fun init() {
        mesh = program(
            """
            uniform mat4 uMVP; attribute vec3 aPos; attribute vec3 aNor; attribute vec4 aCol;
            varying vec3 vNor; varying vec4 vCol; varying vec3 vPos;
            void main(){ vNor=aNor; vCol=aCol; vPos=aPos; gl_Position=uMVP*vec4(aPos,1.0); }
            """,
            """
            precision mediump float; uniform vec3 uLight; uniform vec3 uEye;
            varying vec3 vNor; varying vec4 vCol; varying vec3 vPos;
            void main(){
              vec3 n=normalize(vNor); vec3 e=normalize(uEye-vPos); if(dot(n,e)<0.0) n=-n;
              float d=max(dot(n,normalize(uLight)),0.0);
              float rim=pow(1.0-max(dot(n,e),0.0),3.0)*0.35;
              vec3 c=vCol.rgb*(0.40+0.60*d)+vec3(rim);
              gl_FragColor=vec4(c,vCol.a);
            }
            """,
        )
        flat = program(
            """
            uniform mat4 uMVP; uniform float uSize; attribute vec3 aPos; attribute vec4 aCol; varying vec4 vCol;
            void main(){ vCol=aCol; gl_Position=uMVP*vec4(aPos,1.0); gl_PointSize=uSize; }
            """,
            "precision mediump float; varying vec4 vCol; void main(){ gl_FragColor=vCol; }",
        )
    }

    /** Matrizes de câmera orbital (mesma convenção do renderizador em Canvas). */
    fun orbit(cam: Camera, w: Int, h: Int, view: FloatArray, proj: FloatArray) {
        val e = cam.eye()
        Matrix.setLookAtM(view, 0, e.x, e.y, e.z, cam.target.x, cam.target.y, cam.target.z, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, Math.toDegrees(cam.fov.toDouble()).toFloat(), w.toFloat() / h, .05f, 200f)
    }

    fun draw(scene: Scene, view: FloatArray, proj: FloatArray, eye: V3, points: List<Pair<V3, Int>> = emptyList(), pointSize: Float = 6f) {
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // chão e sombras: sem escrever profundidade, para não brigar com as peças
        GLES20.glDepthMask(false)
        drawLines(scene.under)
        if (scene.shadows.isNotEmpty()) {
            val tris = ArrayList<Float>()
            for (s in scene.shadows) for (t in Triangulate.polygon(s)) for (p in t) { tris += p.x; tris += p.y; tris += p.z; tris += 0f; tris += 1f; tris += 0f; tris += 0f; tris += 0f; tris += 0f; tris += .35f }
            drawTris(tris.toFloatArray(), flatShade = true)
        }
        GLES20.glDepthMask(true)

        // peças opacas primeiro, translúcidas depois (de trás para frente)
        val opaque = ArrayList<Float>(); val clear = ArrayList<Pair<Float, FloatArray>>()
        for (f in scene.faces) {
            val nrm = Triangulate.normal(f.pts)
            val buf = ArrayList<Float>()
            for (t in Triangulate.polygon(f.pts)) for (p in t) { buf += p.x; buf += p.y; buf += p.z; buf += nrm.x; buf += nrm.y; buf += nrm.z; buf += f.color.red; buf += f.color.green; buf += f.color.blue; buf += f.alpha }
            if (f.alpha >= .99f) opaque.addAll(buf) else {
                val c = f.pts.fold(V3.ZERO) { a, b -> a + b } * (1f / f.pts.size)
                clear += (c - eye).len() to buf.toFloatArray()
            }
        }
        drawTris(opaque.toFloatArray(), eye = eye)
        if (clear.isNotEmpty()) {
            GLES20.glDepthMask(false)
            drawTris(clear.sortedByDescending { it.first }.flatMap { it.second.asList() }.toFloatArray(), eye = eye)
            GLES20.glDepthMask(true)
        }

        // contornos das peças por cima com leve deslocamento
        val outline = ArrayList<Line3>()
        for (f in scene.faces) f.outline?.let { c -> for (i in f.pts.indices) outline += Line3(f.pts[i], f.pts[(i + 1) % f.pts.size], c, 2f) }
        drawLines(outline + scene.lines)
        if (points.isNotEmpty()) drawPoints(points, pointSize)
    }

    private fun drawTris(data: FloatArray, flatShade: Boolean = false, eye: V3 = V3.ZERO) {
        if (data.isEmpty()) return
        if (flatShade) {
            // reaproveita o programa de linhas: só posição + cor
            val d = FloatArray(data.size / 10 * 7)
            var j = 0
            for (i in data.indices step 10) { d[j++] = data[i]; d[j++] = data[i + 1]; d[j++] = data[i + 2]; d[j++] = data[i + 6]; d[j++] = data[i + 7]; d[j++] = data[i + 8]; d[j++] = data[i + 9] }
            flatDraw(d, GLES20.GL_TRIANGLES, 1f); return
        }
        GLES20.glUseProgram(mesh)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(mesh, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(mesh, "uLight"), -.45f, .85f, .35f)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(mesh, "uEye"), eye.x, eye.y, eye.z)
        val fb = buffer(data)
        val stride = 10 * 4
        attr(mesh, "aPos", fb, 0, 3, stride); attr(mesh, "aNor", fb, 3, 3, stride); attr(mesh, "aCol", fb, 6, 4, stride)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, data.size / 10)
        disableAll()
    }

    /** Fundo em degradê (coordenadas de tela, sem profundidade). */
    fun background(top: Int, bottom: Int) {
        fun c(v: Int) = floatArrayOf((v shr 16 and 255) / 255f, (v shr 8 and 255) / 255f, (v and 255) / 255f, 1f)
        val t = c(top); val b = c(bottom)
        val d = floatArrayOf(-1f, -1f, 0f, *b, 1f, -1f, 0f, *b, 1f, 1f, 0f, *t, -1f, -1f, 0f, *b, 1f, 1f, 0f, *t, -1f, 1f, 0f, *t)
        Matrix.setIdentityM(mvp, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        flatDraw(d, GLES20.GL_TRIANGLES, 1f)
    }

    private fun drawLines(lines: List<Line3>) {
        if (lines.isEmpty()) return
        val d = FloatArray(lines.size * 14); var j = 0
        for (l in lines) for (p in listOf(l.a, l.b)) { d[j++] = p.x; d[j++] = p.y; d[j++] = p.z; d[j++] = l.color.red; d[j++] = l.color.green; d[j++] = l.color.blue; d[j++] = l.color.alpha }
        GLES20.glLineWidth(lines.maxOf { it.width }.coerceIn(1f, 8f))
        flatDraw(d, GLES20.GL_LINES, 1f)
    }

    private fun drawPoints(pts: List<Pair<V3, Int>>, size: Float) {
        val d = FloatArray(pts.size * 7); var j = 0
        for ((p, c) in pts) { d[j++] = p.x; d[j++] = p.y; d[j++] = p.z; d[j++] = (c shr 16 and 255) / 255f; d[j++] = (c shr 8 and 255) / 255f; d[j++] = (c and 255) / 255f; d[j++] = 1f }
        flatDraw(d, GLES20.GL_POINTS, size)
    }

    private fun flatDraw(d: FloatArray, mode: Int, size: Float) {
        GLES20.glUseProgram(flat)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(flat, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(flat, "uSize"), size)
        val fb = buffer(d)
        attr(flat, "aPos", fb, 0, 3, 28); attr(flat, "aCol", fb, 3, 4, 28)
        GLES20.glDrawArrays(mode, 0, d.size / 7)
        disableAll()
    }

    private fun disableAll() { for (i in 0 until 4) GLES20.glDisableVertexAttribArray(i) }

    companion object {
        fun buffer(d: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(d.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(d); position(0) }

        fun attr(prog: Int, name: String, fb: FloatBuffer, offset: Int, size: Int, stride: Int) {
            val loc = GLES20.glGetAttribLocation(prog, name); if (loc < 0) return
            fb.position(offset)
            GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, stride, fb)
            GLES20.glEnableVertexAttribArray(loc)
        }

        fun program(vs: String, fs: String): Int {
            fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, src); GLES20.glCompileShader(it)
                val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(it) }
            }
            return GLES20.glCreateProgram().also { p ->
                GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(p)
            }
        }
    }
}

/** Triangulação de polígonos planos (convexos por leque; côncavos — L, E — por recorte de orelhas). */
object Triangulate {
    fun normal(p: List<V3>): V3 {
        var n = V3.ZERO
        for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; n = n + V3((a.y - b.y) * (a.z + b.z), (a.z - b.z) * (a.x + b.x), (a.x - b.x) * (a.y + b.y)) }
        return n.norm()
    }

    fun polygon(p: List<V3>): List<List<V3>> {
        if (p.size < 3) return emptyList()
        if (p.size == 3) return listOf(p)
        val n = normal(p)
        // projeta no eixo dominante
        val ax = abs(n.x); val ay = abs(n.y); val az = abs(n.z)
        val pts = p.map { when { ax >= ay && ax >= az -> floatArrayOf(it.y, it.z); ay >= az -> floatArrayOf(it.z, it.x); else -> floatArrayOf(it.x, it.y) } }
        fun cross(o: FloatArray, a: FloatArray, b: FloatArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        var area = 0f; for (i in pts.indices) { val a = pts[i]; val b = pts[(i + 1) % pts.size]; area += a[0] * b[1] - b[0] * a[1] }
        val sign = if (area >= 0) 1f else -1f
        val convex = pts.indices.all { cross(pts[it], pts[(it + 1) % pts.size], pts[(it + 2) % pts.size]) * sign >= -1e-9f }
        if (convex) return (1 until p.size - 1).map { listOf(p[0], p[it], p[it + 1]) }
        val idx = p.indices.toMutableList(); val out = ArrayList<List<V3>>()
        var guard = 0
        while (idx.size > 3 && guard++ < 500) {
            var cut = false
            for (k in idx.indices) {
                val i0 = idx[(k - 1 + idx.size) % idx.size]; val i1 = idx[k]; val i2 = idx[(k + 1) % idx.size]
                if (cross(pts[i0], pts[i1], pts[i2]) * sign <= 1e-12f) continue
                val inside = idx.any { j -> j != i0 && j != i1 && j != i2 && inTri(pts[j], pts[i0], pts[i1], pts[i2], sign) }
                if (inside) continue
                out += listOf(p[i0], p[i1], p[i2]); idx.removeAt(k); cut = true; break
            }
            if (!cut) break
        }
        if (idx.size == 3) out += listOf(p[idx[0]], p[idx[1]], p[idx[2]])
        return out
    }

    private fun inTri(q: FloatArray, a: FloatArray, b: FloatArray, c: FloatArray, s: Float): Boolean {
        fun cr(o: FloatArray, x: FloatArray, y: FloatArray) = ((x[0] - o[0]) * (y[1] - o[1]) - (x[1] - o[1]) * (y[0] - o[0])) * s
        return cr(a, b, q) >= 0 && cr(b, c, q) >= 0 && cr(c, a, q) >= 0
    }
}
