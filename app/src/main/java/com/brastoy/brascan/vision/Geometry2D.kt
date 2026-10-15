package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class P2(val x: Float, val y: Float) {
    operator fun minus(o: P2) = P2(x - o.x, y - o.y)
    operator fun plus(o: P2) = P2(x + o.x, y + o.y)
    operator fun times(k: Float) = P2(x * k, y * k)
    fun cross(o: P2) = x * o.y - y * o.x
    fun dot(o: P2) = x * o.x + y * o.y
    fun len() = hypot(x, y)
}

/** Retângulo orientado: centro, eixo principal (ângulo), largura (ao longo do eixo) e altura. */
data class OBB(val c: P2, val angle: Float, val w: Float, val h: Float) {
    val ux get() = P2(cos(angle), sin(angle))
    val vy get() = P2(-sin(angle), cos(angle))
    fun area() = w * h
}

object Geo2 {
    /** Casco convexo (monotone chain), anti-horário. */
    fun hull(pts: List<P2>): List<P2> {
        if (pts.size < 3) return pts
        val s = pts.distinct().sortedWith(compareBy<P2>({ it.x }, { it.y }))
        if (s.size < 3) return s
        val lo = ArrayList<P2>(); val hi = ArrayList<P2>()
        for (p in s) { while (lo.size >= 2 && (lo[lo.size - 1] - lo[lo.size - 2]).cross(p - lo[lo.size - 2]) <= 0) lo.removeAt(lo.size - 1); lo += p }
        for (p in s.asReversed()) { while (hi.size >= 2 && (hi[hi.size - 1] - hi[hi.size - 2]).cross(p - hi[hi.size - 2]) <= 0) hi.removeAt(hi.size - 1); hi += p }
        lo.removeAt(lo.size - 1); hi.removeAt(hi.size - 1)
        return lo + hi
    }

    fun area(poly: List<P2>): Float {
        var a = 0f
        for (i in poly.indices) a += poly[i].cross(poly[(i + 1) % poly.size])
        return abs(a) / 2
    }

    fun perimeter(poly: List<P2>) = poly.indices.sumOf { (poly[(it + 1) % poly.size] - poly[it]).len().toDouble() }.toFloat()

    /** Retângulo de área mínima (rotating calipers: um lado sempre alinhado a uma aresta do casco). */
    fun minAreaRect(hull: List<P2>): OBB {
        if (hull.size < 3) {
            val c = hull.fold(P2(0f, 0f)) { a, b -> a + b } * (1f / max(1, hull.size))
            return OBB(c, 0f, 0f, 0f)
        }
        var best: OBB? = null
        for (i in hull.indices) {
            val e = hull[(i + 1) % hull.size] - hull[i]; val l = e.len(); if (l < 1e-9f) continue
            val u = e * (1f / l); val v = P2(-u.y, u.x)
            var a0 = Float.MAX_VALUE; var a1 = -Float.MAX_VALUE; var b0 = Float.MAX_VALUE; var b1 = -Float.MAX_VALUE
            for (p in hull) { val a = p.dot(u); val b = p.dot(v); a0 = min(a0, a); a1 = max(a1, a); b0 = min(b0, b); b1 = max(b1, b) }
            val area = (a1 - a0) * (b1 - b0)
            if (best == null || area < best.area()) best = OBB(u * ((a0 + a1) / 2) + v * ((b0 + b1) / 2), atan2(u.y, u.x), a1 - a0, b1 - b0)
        }
        return best!!.let { if (it.w < it.h) it.copy(angle = it.angle + (Math.PI / 2).toFloat(), w = it.h, h = it.w) else it }
    }

    /** Douglas-Peucker num polígono fechado: devolve os "cantos" que sobram. */
    fun simplifyClosed(poly: List<P2>, eps: Float): List<P2> {
        if (poly.size <= 3) return poly
        // começa do ponto mais distante do primeiro para não cortar um canto no meio
        val far = poly.indices.maxBy { (poly[it] - poly[0]).len() }
        val a = dp(poly.subList(0, far + 1), eps)
        val b = dp(poly.subList(far, poly.size) + poly[0], eps)
        return (a.dropLast(1) + b.dropLast(1))
    }

    private fun dp(pts: List<P2>, eps: Float): List<P2> {
        if (pts.size < 3) return pts
        val s = pts.first(); val e = pts.last(); val d = e - s; val l = max(d.len(), 1e-9f)
        var idx = -1; var dist = 0f
        for (i in 1 until pts.size - 1) { val q = abs(d.cross(pts[i] - s)) / l; if (q > dist) { dist = q; idx = i } }
        return if (dist > eps) dp(pts.subList(0, idx + 1), eps).dropLast(1) + dp(pts.subList(idx, pts.size), eps) else listOf(s, e)
    }

    /** Ângulo interno (graus) em cada vértice de um polígono convexo. */
    fun angles(poly: List<P2>) = poly.indices.map { i ->
        val p = poly[i]; val a = poly[(i - 1 + poly.size) % poly.size] - p; val b = poly[(i + 1) % poly.size] - p
        Math.toDegrees(acos((a.dot(b) / max(a.len() * b.len(), 1e-9f)).coerceIn(-1f, 1f).toDouble())).toFloat()
    }
}

/** Máscara binária (peça vista de frente ou projetada no plano dela). */
class Mask(val w: Int, val h: Int, val bits: BooleanArray = BooleanArray(w * h)) {
    operator fun get(x: Int, y: Int) = x in 0 until w && y in 0 until h && bits[y * w + x]
    operator fun set(x: Int, y: Int, v: Boolean) { if (x in 0 until w && y in 0 until h) bits[y * w + x] = v }
    fun count() = bits.count { it }

    fun dilate(): Mask { val o = Mask(w, h); for (y in 0 until h) for (x in 0 until w) o[x, y] = this[x, y] || this[x - 1, y] || this[x + 1, y] || this[x, y - 1] || this[x, y + 1]; return o }
    fun erode(): Mask { val o = Mask(w, h); for (y in 0 until h) for (x in 0 until w) o[x, y] = this[x, y] && this[x - 1, y] && this[x + 1, y] && this[x, y - 1] && this[x, y + 1]; return o }

    /** Pontos de borda por linha (início e fim de cada trecho) — suficiente para o casco convexo. */
    fun edgePoints(): List<P2> {
        val out = ArrayList<P2>()
        for (y in 0 until h) for (x in 0 until w) if (this[x, y] && (!this[x - 1, y] || !this[x + 1, y] || !this[x, y - 1] || !this[x, y + 1])) {
            out += P2(x.toFloat(), y.toFloat()); out += P2(x + 1f, y.toFloat()); out += P2(x.toFloat(), y + 1f); out += P2(x + 1f, y + 1f)
        }
        return out
    }

    /** Componentes conexas (4-vizinhança), maiores primeiro. */
    fun components(minCells: Int): List<Mask> {
        val seen = BooleanArray(w * h); val out = ArrayList<Mask>(); val q = IntArray(w * h)
        for (s in bits.indices) {
            if (!bits[s] || seen[s]) continue
            val m = Mask(w, h); var head = 0; var tail = 0; q[tail++] = s; seen[s] = true
            while (head < tail) {
                val i = q[head++]; m.bits[i] = true; val x = i % w; val y = i / w
                for (n in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1))
                    if (n >= 0 && bits[n] && !seen[n]) { seen[n] = true; q[tail++] = n }
            }
            if (tail >= minCells) out += m
        }
        return out.sortedByDescending { it.count() }
    }
}

data class ShapeFit(
    val shape: String,
    val obb: OBB,
    val corners: List<P2>,
    /** Para triângulos: o vértice "de cima" aponta para +v do OBB (false = virado). */
    val apexPositive: Boolean = true,
    val solidity: Float,
    val rectangularity: Float,
)

/**
 * Classifica a forma por geometria: solidez (área / casco), retangularidade (casco / retângulo mínimo),
 * número de cantos do casco e quais lados do retângulo estão preenchidos (anel = janela, 2 lados = L, 3 = E).
 */
object ShapeClassifier {
    fun classify(m: Mask): ShapeFit? {
        val cells = m.count(); if (cells < 6) return null
        val hull = Geo2.hull(m.edgePoints()); if (hull.size < 3) return null
        val hullA = Geo2.area(hull); val obb = Geo2.minAreaRect(hull)
        val solidity = cells / max(hullA, 1f)
        val rect = hullA / max(obb.area(), 1f)
        val corners = Geo2.simplifyClosed(hull, Geo2.perimeter(hull) * .035f)
        val aspect = obb.w / max(obb.h, 1e-3f)

        fun filled(a: Float, b: Float): Boolean { // a,b em [-0.5, 0.5] no sistema do OBB
            val p = obb.c + obb.ux * (a * obb.w) + obb.vy * (b * obb.h)
            var hit = 0
            for (dx in -1..1) for (dy in -1..1) if (m[(p.x + dx).toInt(), (p.y + dy).toInt()]) hit++
            return hit >= 3
        }
        val (holes, notches) = gaps(m, hull, hullA)

        val shape = when {
            holes >= 1 && solidity < .85f -> "janela"
            notches >= 2 && solidity < .88f -> "E"
            notches == 1 && solidity < .88f && rect > .6f -> "L"
            corners.size == 3 || (rect < .68f && solidity > .82f) -> triangleKind(corners)
            aspect > 1.55f -> "retangulo"
            else -> "quadrado"
        }
        // Centroide abaixo do centro do OBB (no eixo v) → base embaixo, vértice para +v.
        var sv = 0.0; var n = 0
        for (y in 0 until m.h) for (x in 0 until m.w) if (m[x, y]) { sv += (P2(x + .5f, y + .5f) - obb.c).dot(obb.vy); n++ }
        return ShapeFit(shape, obb, corners, apexPositive = sv / max(1, n) < 0, solidity = solidity, rectangularity = rect)
    }

    /**
     * Vãos dentro do casco convexo: os fechados são furos (janela), os que encostam na borda do casco
     * são entalhes (L tem 1, E tem 2). Vãos menores que 4% do casco são ruído.
     */
    private fun gaps(m: Mask, hull: List<P2>, hullA: Float): Pair<Int, Int> {
        fun inHull(x: Float, y: Float): Boolean {
            for (i in hull.indices) if ((hull[(i + 1) % hull.size] - hull[i]).cross(P2(x, y) - hull[i]) < 0) return false
            return true
        }
        val gap = Mask(m.w, m.h)
        val hullCell = Mask(m.w, m.h)
        for (y in 0 until m.h) for (x in 0 until m.w) if (inHull(x + .5f, y + .5f)) { hullCell[x, y] = true; if (!m[x, y]) gap[x, y] = true }
        var holes = 0; var notches = 0
        for (c in gap.erode().dilate().components(minCells = max(3, (hullA * .04f).toInt()))) {
            var touches = false
            loop@ for (y in 0 until c.h) for (x in 0 until c.w) if (c[x, y] && (!hullCell[x - 1, y] || !hullCell[x + 1, y] || !hullCell[x, y - 1] || !hullCell[x, y + 1])) { touches = true; break@loop }
            if (touches) notches++ else holes++
        }
        return holes to notches
    }

    private fun triangleKind(corners: List<P2>): String {
        if (corners.size != 3) return "equilatero"
        return if (Geo2.angles(corners).max() > 78f) "retanguloTri" else "equilatero"
    }
}
