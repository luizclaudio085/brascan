package com.brastoy.brascan.three

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(dot(this))
    fun norm(): V3 { val l = len(); return if (l < 1e-6f) this else this * (1f / l) }
    companion object { val ZERO = V3(0f, 0f, 0f); val UP = V3(0f, 1f, 0f); val ZERO_UP = V3(0f, .09f, 0f) }
}

/** Polígono plano; [tag] identifica a peça para seleção por toque. */
class Face(
    val pts: List<V3>,
    val color: Color,
    val alpha: Float = 1f,
    val tag: Int = -1,
    val doubleSided: Boolean = false,
    val outline: Color? = null,
    val unlit: Boolean = false,
)

class Line3(val a: V3, val b: V3, val color: Color, val width: Float = 2f)

class Scene(
    val faces: MutableList<Face> = mutableListOf(),
    val lines: MutableList<Line3> = mutableListOf(),
    /** Linhas desenhadas antes dos polígonos (piso, sombras). */
    val under: MutableList<Line3> = mutableListOf(),
    val shadows: MutableList<List<V3>> = mutableListOf(),
) {
    fun box(c: V3, size: V3, color: Color, tag: Int = -1, alpha: Float = 1f, outline: Color? = null) {
        val (hx, hy, hz) = Triple(size.x / 2, size.y / 2, size.z / 2)
        fun p(sx: Int, sy: Int, sz: Int) = V3(c.x + sx * hx, c.y + sy * hy, c.z + sz * hz)
        listOf(
            listOf(p(-1, -1, 1), p(1, -1, 1), p(1, 1, 1), p(-1, 1, 1)),
            listOf(p(1, -1, -1), p(-1, -1, -1), p(-1, 1, -1), p(1, 1, -1)),
            listOf(p(-1, 1, 1), p(1, 1, 1), p(1, 1, -1), p(-1, 1, -1)),
            listOf(p(-1, -1, -1), p(1, -1, -1), p(1, -1, 1), p(-1, -1, 1)),
            listOf(p(1, -1, 1), p(1, -1, -1), p(1, 1, -1), p(1, 1, 1)),
            listOf(p(-1, -1, -1), p(-1, -1, 1), p(-1, 1, 1), p(-1, 1, -1)),
        ).forEach { faces += Face(it, color, alpha, tag, outline = outline) }
    }

    /** Prisma extrudado a partir de um contorno 2D ([outline] no plano local u/v) ao longo de [n]. */
    fun prism(outline: List<Pair<Float, Float>>, origin: V3, u: V3, v: V3, thickness: Float, color: Color, tag: Int, alpha: Float, edge: Color?) {
        val n = u.cross(v).norm() * (thickness / 2)
        val front = outline.map { (a, b) -> origin + u * a + v * b + n }
        val back = outline.map { (a, b) -> origin + u * a + v * b - n }
        faces += Face(front, color, alpha, tag, doubleSided = true, outline = edge)
        faces += Face(back.reversed(), color, alpha, tag, doubleSided = true, outline = edge)
        for (i in outline.indices) {
            val j = (i + 1) % outline.size
            faces += Face(listOf(front[j], front[i], back[i], back[j]), color, alpha, tag, doubleSided = true)
        }
    }

    fun arrow(from: V3, to: V3, color: Color, width: Float) {
        lines += Line3(from, to, color, width)
        val d = (to - from); val l = d.len(); if (l < 1e-4f) return
        val dir = d * (1f / l); val side = (if (kotlin.math.abs(dir.y) > .9f) V3(1f, 0f, 0f) else V3.UP).cross(dir).norm()
        val up = dir.cross(side).norm(); val head = l * .18f; val base = to - dir * head
        val r = head * .45f
        val ring = (0 until 8).map { i -> val a = i * Math.PI.toFloat() / 4; base + side * (cos(a) * r) + up * (sin(a) * r) }
        for (i in ring.indices) faces += Face(listOf(to, ring[i], ring[(i + 1) % ring.size]), color, 1f, doubleSided = true)
    }
}

class Camera(var yaw: Float = .7f, var pitch: Float = .45f, var distance: Float = 9f, var target: V3 = V3.ZERO, var fov: Float = .75f) {
    fun eye(): V3 = target + V3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)) * distance
}

class Projected(val pts: List<Offset>, val depth: Float, val face: Face, val shade: Float)

object Renderer {
    private val light = V3(-.45f, .85f, .35f).norm()

    /** Projeta e ordena (algoritmo do pintor). Retorna os polígonos de trás para frente. */
    class Frame(val faces: List<Projected>, val lines: List<Triple<Offset, Offset, Line3>>, val under: List<Triple<Offset, Offset, Line3>>, val shadows: List<List<Offset>>)

    fun project(scene: Scene, cam: Camera, w: Float, h: Float): Frame {
        val eye = cam.eye()
        val fwd = (cam.target - eye).norm()
        val right = fwd.cross(V3.UP).norm()
        val up = right.cross(fwd)
        val f = (h / 2) / kotlin.math.tan(cam.fov / 2)
        fun toCam(p: V3): V3 { val d = p - eye; return V3(d.dot(right), d.dot(up), d.dot(fwd)) }
        fun screen(c: V3) = Offset(w / 2 + c.x / c.z * f, h / 2 - c.y / c.z * f)
        val out = ArrayList<Projected>(scene.faces.size)
        for (face in scene.faces) {
            val cs = face.pts.map(::toCam)
            if (cs.any { it.z < .05f }) continue
            val normal = (face.pts[1] - face.pts[0]).cross(face.pts[2] - face.pts[0]).norm()
            val toEye = (eye - face.pts[0])
            val facing = normal.dot(toEye)
            if (facing < 0 && !face.doubleSided) continue
            val n = if (facing < 0) normal * -1f else normal
            val shade = if (face.unlit) 1f else .38f + .62f * max(0f, n.dot(light))
            out += Projected(cs.map(::screen), cs.sumOf { it.z.toDouble() }.toFloat() / cs.size, face, shade)
        }
        out.sortByDescending { it.depth }
        fun lines(src: List<Line3>) = src.mapNotNull { l ->
            val a = toCam(l.a); val b = toCam(l.b)
            if (a.z < .05f || b.z < .05f) null else Triple(screen(a), screen(b), l)
        }
        val shadows = scene.shadows.mapNotNull { poly -> val cs = poly.map(::toCam); if (cs.any { it.z < .05f }) null else cs.map(::screen) }
        return Frame(out, lines(scene.lines), lines(scene.under), shadows)
    }

    fun hit(projected: List<Projected>, p: Offset): Int {
        for (i in projected.indices.reversed()) {
            val pr = projected[i]
            if (pr.face.tag >= 0 && inside(pr.pts, p)) return pr.face.tag
        }
        return -1
    }

    private fun inside(poly: List<Offset>, p: Offset): Boolean {
        var c = false; var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }
}
