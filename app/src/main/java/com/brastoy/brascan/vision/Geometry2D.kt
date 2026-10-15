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

/** Vão dentro do casco: centro e extensão normalizados no sistema do OBB (-0,5..0,5), área relativa ao casco. */
data class Gap(val cu: Float, val cv: Float, val su: Float, val sv: Float, val area: Float, val enclosed: Boolean)

data class ShapeFit(
    val shape: String,
    val obb: OBB,
    val corners: List<P2>,
    /** Para triângulos: o vértice "de cima" aponta para +v do OBB (false = virado). */
    val apexPositive: Boolean = true,
    val solidity: Float,
    val rectangularity: Float,
    val gaps: List<Gap> = emptyList(),
)

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
        // faixa fina colada na borda = borda/ímã que não foi segmentado, não é forma da peça
        val gaps = gaps(m, hull, hullA, obb).filterNot { g -> (g.su < .2f && abs(g.cu) > .33f) || (g.sv < .2f && abs(g.cv) > .33f) }
        val holes = gaps.filter { it.enclosed }; val notches = gaps.filter { !it.enclosed }
        val tri = corners.size == 3 || (rect < .7f && solidity > .8f)

        val shape = when {
            holes.size >= 3 -> "janela" // janela de 4 vidros
            holes.isNotEmpty() && solidity < .9f -> {
                val g = holes.maxBy { it.area }
                val elong = max(g.su, g.sv) / max(min(g.su, g.sv), 1e-3f)
                if (elong > 1.2f || hypotF(g.cu, g.cv) > .09f) "porta" else "moldura"
            }
            tri && solidity > .8f -> triangleKind(corners, aspect)
            notches.count { it.area > .05f } >= 2 && solidity < .85f -> "E"
            notches.size == 1 && solidity < .9f && !tri -> {
                // L: vão no canto (centro em diagonal); porta sem soleira: vão no meio de um lado
                val g = notches[0]; val lo = minOf(abs(g.cu), abs(g.cv)); val hi = maxOf(abs(g.cu), abs(g.cv))
                if (lo > .035f && hi < lo * 2.2f) "L" else "porta"
            }
            tri -> triangleKind(corners, aspect)
            aspect > 1.55f -> "retangulo"
            else -> "quadrado"
        }
        // Centroide abaixo do centro do OBB (no eixo v) → base embaixo, vértice para +v.
        var sv = 0.0; var n = 0
        for (y in 0 until m.h) for (x in 0 until m.w) if (m[x, y]) { sv += (P2(x + .5f, y + .5f) - obb.c).dot(obb.vy); n++ }
        return ShapeFit(shape, obb, corners, apexPositive = sv / max(1, n) < 0, solidity = solidity, rectangularity = rect, gaps = gaps)
    }

    /**
     * Vãos dentro do casco convexo: os fechados são furos (janela), os que encostam na borda do casco
     * são entalhes (L tem 1, E tem 2). Vãos menores que 4% do casco são ruído.
     */
    private fun hypotF(a: Float, b: Float) = kotlin.math.sqrt(a * a + b * b)

    private fun gaps(m: Mask, hull: List<P2>, hullA: Float, obb: OBB): List<Gap> {
        fun inHull(x: Float, y: Float): Boolean {
            for (i in hull.indices) if ((hull[(i + 1) % hull.size] - hull[i]).cross(P2(x, y) - hull[i]) < 0) return false
            return true
        }
        val gap = Mask(m.w, m.h)
        val hullCell = Mask(m.w, m.h)
        for (y in 0 until m.h) for (x in 0 until m.w) if (inHull(x + .5f, y + .5f)) { hullCell[x, y] = true; if (!m[x, y]) gap[x, y] = true }
        val out = ArrayList<Gap>()
        for (c in gap.erode().dilate().components(minCells = max(3, (hullA * .025f).toInt()))) {
            var touches = false
            var su0 = Float.MAX_VALUE; var su1 = -Float.MAX_VALUE; var sv0 = Float.MAX_VALUE; var sv1 = -Float.MAX_VALUE; var cu = 0f; var cv = 0f; var k = 0
            for (y in 0 until c.h) for (x in 0 until c.w) if (c[x, y]) {
                if (!hullCell[x - 1, y] || !hullCell[x + 1, y] || !hullCell[x, y - 1] || !hullCell[x, y + 1]) touches = true
                val d = P2(x + .5f, y + .5f) - obb.c
                val u = d.dot(obb.ux) / max(obb.w, 1f); val v = d.dot(obb.vy) / max(obb.h, 1f)
                su0 = min(su0, u); su1 = max(su1, u); sv0 = min(sv0, v); sv1 = max(sv1, v); cu += u; cv += v; k++
            }
            out += Gap(cu / k, cv / k, su1 - su0, sv1 - sv0, k / max(hullA, 1f), enclosed = !touches)
        }
        return out
    }

    /** Pelos ângulos: 90° = triângulo retângulo; ângulo agudo (< 45°) = isósceles alto; senão equilátero. */
    private fun triangleKind(corners0: List<P2>, aspect: Float): String {
        // tira "cantos" quase retos (sobras da simplificação) até sobrar o triângulo
        val corners = corners0.toMutableList()
        while (corners.size > 3) { val a = Geo2.angles(corners); val i = a.indices.maxBy { a[it] }; if (a[i] < 150f) break; corners.removeAt(i) }
        if (corners.size != 3) return if (aspect > 1.6f) "isoceles" else "equilatero"
        val ang = Geo2.angles(corners)
        return when { ang.max() > 78f -> "retanguloTri"; ang.min() < 45f -> "isoceles"; else -> "equilatero" }
    }
}

/**
 * Encaixa o molde exato da peça (polígono + furos do catálogo) na máscara, testando as 4 rotações alinhadas
 * ao retângulo mínimo; devolve o ângulo (rad, coordenadas da imagem) do eixo +u do molde e o IoU.
 * [vUp]: o eixo +v do molde aponta para cima na imagem (foto de frente) ou para baixo (foto de cima).
 */
object TemplateFit {
    private fun inPoly(p: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var c = false; var j = p.size - 1
        for (i in p.indices) { val (xi, yi) = p[i]; val (xj, yj) = p[j]; if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c; j = i }
        return c
    }

    /** Área e centroide de polígono (shoelace), com furos subtraídos. */
    private fun areaCentroid(poly: List<Pair<Float, Float>>, holes: List<List<Pair<Float, Float>>>): Triple<Float, Float, Float> {
        fun one(p: List<Pair<Float, Float>>): Triple<Float, Float, Float> {
            var a = 0f; var cx = 0f; var cy = 0f
            for (i in p.indices) { val (x0, y0) = p[i]; val (x1, y1) = p[(i + 1) % p.size]; val c = x0 * y1 - x1 * y0; a += c; cx += (x0 + x1) * c; cy += (y0 + y1) * c }
            a /= 2; return Triple(abs(a), cx / (6 * a), cy / (6 * a))
        }
        val (ao, xo, yo) = one(poly); var A = ao; var X = xo * ao; var Y = yo * ao
        for (h in holes) { val (ah, xh, yh) = one(h); A -= ah; X -= xh * ah; Y -= yh * ah }
        return Triple(A, X / A, Y / A)
    }

    fun fit(m: Mask, obb: OBB, shape: String, vUp: Boolean, corners: List<P2> = emptyList()): Pair<Float, Float> {
        val poly = Catalog.polygon(shape); val holes = Catalog.holes(shape)
        val (pa, pcx, pcy) = areaCentroid(poly, holes)
        // escala única pela área; centroides alinhados
        var n = 0; var mx = 0f; var my = 0f
        for (y in 0 until m.h) for (x in 0 until m.w) if (m[x, y]) { n++; mx += x + .5f; my += y + .5f }
        if (n == 0) return 0f to 0f
        val c = P2(mx / n, my / n); val sc = kotlin.math.sqrt(n / pa)
        var best = 0f; var bestIou = -1f
        // candidatos: múltiplos de 45° do retângulo mínimo + direções dos lados da peça (triângulos empatam a caixa em todo lado)
        val q = (Math.PI / 2).toFloat()
        // peças com simetria de 90° só giram alinhadas ao retângulo mínimo (a diagonal empata por ruído)
        val square = shape in setOf("quadrado", "moldura", "janela", "encaixe")
        val cands = if (square) (0 until 4).map { obb.angle + it * q } else (0 until 8).map { obb.angle + it * (Math.PI / 4).toFloat() } +
            corners.indices.flatMap { i -> val e = corners[(i + 1) % corners.size] - corners[i]; val a = kotlin.math.atan2(e.y, e.x); (0 until 4).map { a + it * q } }
        for (th in cands) {
            val U = P2(cos(th), sin(th)); val V = if (vUp) P2(sin(th), -cos(th)) else P2(-sin(th), cos(th))
            var inter = 0; var union = 0
            val pad = (sc * .3f).toInt() + 2
            for (y in -pad until m.h + pad) for (x in -pad until m.w + pad) {
                val d = P2(x + .5f, y + .5f) - c
                val a = pcx + d.dot(U) / sc; val b = pcy + d.dot(V) / sc
                val t = inPoly(poly, a, b) && holes.none { inPoly(it, a, b) }
                val mm = m[x, y]
                if (t && mm) inter++
                if (t || mm) union++
            }
            val iou = inter / max(1, union).toFloat()
            if (iou > bestIou + 1e-4f) { bestIou = iou; best = th }
        }
        return best to bestIou
    }
}
