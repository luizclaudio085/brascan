package com.brastoy.brascan.three

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Desenha uma [Scene] com OpenGL ES 2: z-buffer, plástico translúcido (Blinn-Phong + Fresnel + luz de céu),
 * sombra suave (várias amostras), contornos e pontos. A cena é preparada uma vez (arrays prontos) e só a
 * ordem dos translúcidos é refeita por quadro. Usado pela aba 3D e pelo AR.
 */
class GlSceneRenderer {
    private var mesh = 0; private var flat = 0
    private val mvp = FloatArray(16)
    private var buf: FloatBuffer = ByteBuffer.allocateDirect(4 * 4096).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private class Prepared(
        val opaque: FloatArray,
        val clear: List<Pair<V3, FloatArray>>,
        val shadows: FloatArray,
        val ground: FloatArray,
        val under: FloatArray, val underWidth: Float,
        val lines: FloatArray, val lineWidth: Float,
    )
    private var preparedFor: Scene? = null
    private var prepared: Prepared? = null
    private var scratch = FloatArray(0)
    /** Raio-X: só desenha o que estiver abaixo de [clipY] e à esquerda de [clipX] (unidades da cena). */
    @Volatile var clipY = 9000f
    @Volatile var clipX = 9000f

    fun init() {
        mesh = program(
            """
            uniform mat4 uMVP; attribute vec3 aPos; attribute vec3 aNor; attribute vec4 aCol;
            varying vec3 vNor; varying vec4 vCol; varying vec3 vPos;
            void main(){ vNor=aNor; vCol=aCol; vPos=aPos; gl_Position=uMVP*vec4(aPos,1.0); }
            """,
            """
            precision mediump float; uniform vec3 uLight; uniform vec3 uLight2; uniform vec3 uEye; uniform vec2 uClip;
            varying vec3 vNor; varying vec4 vCol; varying vec3 vPos;
            void main(){
              if(vPos.y>uClip.x||vPos.x>uClip.y) discard; // Raio-X: corte horizontal / vertical
              vec3 n=normalize(vNor); vec3 e=normalize(uEye-vPos); if(dot(n,e)<0.0) n=-n;
              vec3 l=normalize(uLight); vec3 h=normalize(l+e);
              float d=max(dot(n,l),0.0); float d2=max(dot(n,normalize(uLight2)),0.0)*0.30;
              float spec=pow(max(dot(n,h),0.0),70.0)*0.65;
              float hemi=mix(0.30,0.52,n.y*0.5+0.5);
              float fres=pow(1.0-max(dot(n,e),0.0),3.0);
              vec3 c=vCol.rgb*(hemi+0.58*d+d2)+vec3(spec)+vec3(0.16)*fres;
              float a=clamp(vCol.a+fres*0.35+spec*0.6,0.0,1.0);
              gl_FragColor=vec4(pow(c,vec3(0.92)),a);
            }
            """,
        )
        flat = program(
            """
            uniform mat4 uMVP; uniform float uSize; attribute vec3 aPos; attribute vec4 aCol; varying vec4 vCol; varying vec3 vPos;
            void main(){ vCol=aCol; vPos=aPos; gl_Position=uMVP*vec4(aPos,1.0); gl_PointSize=uSize; }
            """,
            "precision mediump float; uniform vec2 uClip; varying vec4 vCol; varying vec3 vPos; void main(){ if(vPos.y>uClip.x||vPos.x>uClip.y) discard; gl_FragColor=vCol; }",
        )
    }

    /** Matrizes de câmera orbital (mesma convenção do renderizador em Canvas). */
    fun orbit(cam: Camera, w: Int, h: Int, view: FloatArray, proj: FloatArray) {
        val e = cam.eye()
        Matrix.setLookAtM(view, 0, e.x, e.y, e.z, cam.target.x, cam.target.y, cam.target.z, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, Math.toDegrees(cam.fov.toDouble()).toFloat(), w.toFloat() / h, .02f, 200f)
    }

    private fun prepare(scene: Scene): Prepared {
        fun faceData(f: Face): FloatArray {
            val nrm = Triangulate.normal(f.pts)
            val tris = Triangulate.polygon(f.pts)
            val out = FloatArray(tris.size * 30); var j = 0
            for (t in tris) for (p in t) { out[j++] = p.x; out[j++] = p.y; out[j++] = p.z; out[j++] = nrm.x; out[j++] = nrm.y; out[j++] = nrm.z; out[j++] = f.color.red; out[j++] = f.color.green; out[j++] = f.color.blue; out[j++] = f.alpha }
            return out
        }
        val opaque = ArrayList<FloatArray>(); val clear = ArrayList<Pair<V3, FloatArray>>()
        for (f in scene.faces) {
            val d = faceData(f)
            if (f.alpha >= .99f) opaque += d else clear += (f.pts.fold(V3.ZERO) { a, b -> a + b } * (1f / f.pts.size)) to d
        }
        // sombra suave: o mesmo polígono deslocado em círculo, cada cópia bem clara
        val soft = scene.shadowSoftness; val k = 7
        val tris = scene.shadows.flatMap { Triangulate.polygon(it) }
        val sh = FloatArray(tris.size * k * 3 * 7); var q = 0
        for (t in tris) for (i in 0 until k) {
            val a = i * 2 * Math.PI / k; val r = if (i == 0) 0f else soft
            val ox = (cos(a) * r).toFloat(); val oz = (sin(a) * r).toFloat()
            for (p in t) { sh[q++] = p.x + ox; sh[q++] = p.y; sh[q++] = p.z + oz; sh[q++] = 0f; sh[q++] = 0f; sh[q++] = 0f; sh[q++] = scene.shadowAlpha / k * 1.6f }
        }
        val gr = FloatArray(scene.ground.size * 7); var g = 0
        for ((pt, c) in scene.ground) { gr[g++] = pt.x; gr[g++] = 0f; gr[g++] = pt.z; gr[g++] = c.red; gr[g++] = c.green; gr[g++] = c.blue; gr[g++] = c.alpha }
        fun lineData(ls: List<Line3>): FloatArray {
            val d = FloatArray(ls.size * 14); var j = 0
            for (l in ls) for (p in listOf(l.a, l.b)) { d[j++] = p.x; d[j++] = p.y; d[j++] = p.z; d[j++] = l.color.red; d[j++] = l.color.green; d[j++] = l.color.blue; d[j++] = l.color.alpha }
            return d
        }
        val outline = ArrayList<Line3>()
        for (f in scene.faces) f.outline?.let { c -> for (i in f.pts.indices) outline += Line3(f.pts[i], f.pts[(i + 1) % f.pts.size], c, 2f) }
        val all = outline + scene.lines
        val op = FloatArray(opaque.sumOf { it.size }); var o = 0
        for (d in opaque) { System.arraycopy(d, 0, op, o, d.size); o += d.size }
        return Prepared(op, clear, sh, gr, lineData(scene.under), scene.under.maxOfOrNull { it.width } ?: 1f, lineData(all), all.maxOfOrNull { it.width } ?: 1f)
    }

    fun draw(scene: Scene, view: FloatArray, proj: FloatArray, eye: V3, points: List<Pair<V3, Int>> = emptyList(), pointSize: Float = 6f) {
        if (preparedFor !== scene) { prepared = prepare(scene); preparedFor = scene }
        val p = prepared!!
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // chão e sombras sem escrever profundidade
        GLES20.glDepthMask(false)
        if (p.ground.isNotEmpty()) flatDraw(p.ground, GLES20.GL_TRIANGLES, 1f)
        if (p.under.isNotEmpty()) { GLES20.glLineWidth(p.underWidth); flatDraw(p.under, GLES20.GL_LINES, 1f) }
        if (p.shadows.isNotEmpty()) flatDraw(p.shadows, GLES20.GL_TRIANGLES, 1f)
        GLES20.glDepthMask(true)

        meshDraw(p.opaque, p.opaque.size, eye)
        if (p.clear.isNotEmpty()) {
            // translúcidos de trás para frente, sem escrever profundidade (o de trás aparece através)
            val order = p.clear.sortedByDescending { (it.first - eye).len() }
            val total = order.sumOf { it.second.size }
            if (scratch.size < total) scratch = FloatArray(total)
            var j = 0; for ((_, d) in order) { System.arraycopy(d, 0, scratch, j, d.size); j += d.size }
            GLES20.glDepthMask(false)
            meshDraw(scratch, total, eye)
            GLES20.glDepthMask(true)
        }
        if (p.lines.isNotEmpty()) { GLES20.glLineWidth(p.lineWidth.coerceIn(1f, 6f)); flatDraw(p.lines, GLES20.GL_LINES, 1f) }
        if (points.isNotEmpty()) {
            val d = FloatArray(points.size * 7); var j = 0
            for ((q, c) in points) { d[j++] = q.x; d[j++] = q.y; d[j++] = q.z; d[j++] = (c shr 16 and 255) / 255f; d[j++] = (c shr 8 and 255) / 255f; d[j++] = (c and 255) / 255f; d[j++] = 1f }
            flatDraw(d, GLES20.GL_POINTS, pointSize)
        }
    }

    /** Fundo em degradê (coordenadas de tela, sem profundidade). */
    fun background(top: Int, bottom: Int) {
        fun c(v: Int) = floatArrayOf((v shr 16 and 255) / 255f, (v shr 8 and 255) / 255f, (v and 255) / 255f, 1f)
        val t = c(top); val b = c(bottom)
        val d = floatArrayOf(-1f, -1f, 0f, *b, 1f, -1f, 0f, *b, 1f, 1f, 0f, *t, -1f, -1f, 0f, *b, 1f, 1f, 0f, *t, -1f, 1f, 0f, *t)
        Matrix.setIdentityM(mvp, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        val cy = clipY; val cx = clipX; clipY = 9000f; clipX = 9000f // o fundo nunca é cortado
        flatDraw(d, GLES20.GL_TRIANGLES, 1f)
        clipY = cy; clipX = cx
    }

    private fun upload(d: FloatArray, n: Int): FloatBuffer {
        if (buf.capacity() < n) buf = ByteBuffer.allocateDirect(n * 4 * 2).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.clear(); buf.put(d, 0, n); buf.position(0)
        return buf
    }

    private fun meshDraw(data: FloatArray, n: Int, eye: V3) {
        if (n == 0) return
        GLES20.glUseProgram(mesh)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(mesh, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(mesh, "uLight"), -.45f, .85f, .35f)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(mesh, "uLight2"), .6f, .3f, -.5f)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(mesh, "uEye"), eye.x, eye.y, eye.z)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(mesh, "uClip"), clipY, clipX)
        val fb = upload(data, n)
        attr(mesh, "aPos", fb, 0, 3, 40); attr(mesh, "aNor", fb, 3, 3, 40); attr(mesh, "aCol", fb, 6, 4, 40)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n / 10)
        disableAll()
    }

    private fun flatDraw(d: FloatArray, mode: Int, size: Float) {
        GLES20.glUseProgram(flat)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(flat, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(flat, "uSize"), size)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(flat, "uClip"), clipY, clipX)
        val fb = upload(d, d.size)
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
