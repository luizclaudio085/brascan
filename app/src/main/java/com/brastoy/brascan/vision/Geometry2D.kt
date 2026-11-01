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
    /** Ordem (x, depois y) de compareBy({ x }, { y }): Float.compare nos dois. */
    private val XY = Comparator<P2> { a, b -> val c = a.x.compareTo(b.x); if (c != 0) c else a.y.compareTo(b.y) }

    /** (b - a) × (p - a), as mesmas contas de P2.minus/cross. */
    private fun turn(b: P2, a: P2, p: P2): Float = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

    /**
     * Casco convexo (monotone chain), anti-horário. Ordena e tira os repetidos vizinhos (iguais pela ordem = iguais
     * para o data class), o mesmo que distinct() + sortedWith; a cadeia faz as mesmas contas sem criar pontos.
     */
    fun hull(pts: List<P2>): List<P2> {
        if (pts.size < 3) return pts
        val s = pts.toTypedArray()
        java.util.Arrays.sort(s, XY)
        var m = 0
        for (p in s) if (m == 0 || XY.compare(s[m - 1], p) != 0) s[m++] = p
        if (m < 3) return s.asList().subList(0, m).toList()
        val lo = arrayOfNulls<P2>(m); var nl = 0
        for (i in 0 until m) { val p = s[i]; while (nl >= 2 && turn(lo[nl - 1]!!, lo[nl - 2]!!, p) <= 0) nl--; lo[nl++] = p }
        val hi = arrayOfNulls<P2>(m); var nh = 0
        for (i in m - 1 downTo 0) { val p = s[i]; while (nh >= 2 && turn(hi[nh - 1]!!, hi[nh - 2]!!, p) <= 0) nh--; hi[nh++] = p }
        val out = ArrayList<P2>(nl + nh - 2)
        for (i in 0 until nl - 1) out += lo[i]!!
        for (i in 0 until nh - 1) out += hi[i]!!
        return out
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
        // as mesmas contas de antes (u = e·(1/|e|), v = (-u.y, u.x), produtos escalares), sem criar pontos
        val n = hull.size
        val xs = FloatArray(n) { hull[it].x }; val ys = FloatArray(n) { hull[it].y }
        var best: OBB? = null; var bestArea = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ex = xs[j] - xs[i]; val ey = ys[j] - ys[i]; val l = hypot(ex, ey); if (l < 1e-9f) continue
            val inv = 1f / l; val ux = ex * inv; val uy = ey * inv; val vx = -uy; val vy = ux
            var a0 = Float.MAX_VALUE; var a1 = -Float.MAX_VALUE; var b0 = Float.MAX_VALUE; var b1 = -Float.MAX_VALUE
            for (k in 0 until n) { val a = xs[k] * ux + ys[k] * uy; val b = xs[k] * vx + ys[k] * vy; a0 = min(a0, a); a1 = max(a1, a); b0 = min(b0, b); b1 = max(b1, b) }
            val area = (a1 - a0) * (b1 - b0)
            if (best == null || area < bestArea) {
                val ma = (a0 + a1) / 2; val mb = (b0 + b1) / 2
                best = OBB(P2(ux * ma + vx * mb, uy * ma + vy * mb), atan2(uy, ux), a1 - a0, b1 - b0); bestArea = best.area()
            }
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
    fun count(): Int { var c = 0; for (b in bits) if (b) c++; return c }

    /** Dilatação em cruz (fora do recorte = vazio). */
    fun dilate(): Mask {
        val o = Mask(w, h); val ob = o.bits
        for (y in 0 until h) { val r = y * w; for (x in 0 until w) { val i = r + x
            ob[i] = bits[i] || (x > 0 && bits[i - 1]) || (x < w - 1 && bits[i + 1]) || (y > 0 && bits[i - w]) || (y < h - 1 && bits[i + w]) } }
        return o
    }

    /** Erosão em cruz (fora do recorte = vazio, então a borda do recorte sempre sai). */
    fun erode(): Mask {
        val o = Mask(w, h); val ob = o.bits
        for (y in 0 until h) { val r = y * w; for (x in 0 until w) { val i = r + x
            ob[i] = bits[i] && x > 0 && bits[i - 1] && x < w - 1 && bits[i + 1] && y > 0 && bits[i - w] && y < h - 1 && bits[i + w] } }
        return o
    }

    /**
     * Pontos para o casco convexo: os 4 cantos do primeiro e do último pixel de cada linha. O casco é o mesmo dos cantos
     * de todos os pixels de borda (todo pixel fica entre os dois extremos da linha dele, e os extremos são de borda);
     * com coordenadas inteiras o casco sai idêntico.
     */
    fun edgePoints(): List<P2> {
        val out = ArrayList<P2>()
        for (y in 0 until h) {
            val r = y * w; var x0 = -1; var x1 = -1
            for (x in 0 until w) if (bits[r + x]) { x0 = x; break }
            if (x0 < 0) continue
            for (x in w - 1 downTo x0) if (bits[r + x]) { x1 = x; break }
            out += P2(x0.toFloat(), y.toFloat()); out += P2(x0.toFloat(), y + 1f)
            out += P2(x1 + 1f, y.toFloat()); out += P2(x1 + 1f, y + 1f)
        }
        return out
    }

    /** Componentes conexas (4-vizinhança), maiores primeiro (empate: ordem em que foram achadas). */
    fun components(minCells: Int): List<Mask> {
        val seen = BooleanArray(w * h); val out = ArrayList<Mask>(); val size = ArrayList<Int>(); val q = IntArray(w * h)
        for (s in bits.indices) {
            if (!bits[s] || seen[s]) continue
            val m = Mask(w, h); val mb = m.bits; var head = 0; var tail = 0; q[tail++] = s; seen[s] = true
            while (head < tail) {
                val i = q[head++]; mb[i] = true; val x = i % w
                if (x > 0) { val k = i - 1; if (bits[k] && !seen[k]) { seen[k] = true; q[tail++] = k } }
                if (x < w - 1) { val k = i + 1; if (bits[k] && !seen[k]) { seen[k] = true; q[tail++] = k } }
                if (i >= w) { val k = i - w; if (bits[k] && !seen[k]) { seen[k] = true; q[tail++] = k } }
                if (i + w < w * h) { val k = i + w; if (bits[k] && !seen[k]) { seen[k] = true; q[tail++] = k } }
            }
            if (tail >= minCells) { out += m; size += tail }
        }
        if (out.size < 2) return out
        return out.indices.sortedByDescending { size[it] }.map { out[it] }
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
    @JvmField var realCornerRule = true
    /**
     * [occluded]: células (mesmo recorte de [m]) ocupadas por OUTRA peça. Um vão do casco que é na verdade outra
     * peça na frente não é entalhe da forma (parede atrás de parede virava "L"/"porta"/"E").
     */
    fun classify(m: Mask, occluded: Mask? = null): ShapeFit? {
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
        val gaps = gaps(m, hull, hullA, obb, occluded).filterNot { g -> (g.su < .2f && abs(g.cu) > .33f) || (g.sv < .2f && abs(g.cv) > .33f) }
        val holes = gaps.filter { it.enclosed }; val notches = gaps.filter { !it.enclosed }
        // triângulo = casco com 3 cantos de verdade (canto quase reto, > 145°, é hipotenusa digitalizada ou canto arredondado).
        // Um quadrado visto de viés vira paralelogramo (retangularidade baixa) com 4 cantos de verdade e NÃO é
        // triângulo: a regra antiga (só rect < .7) transformava paredes em "isósceles"
        val realCorners = corners.indices.count { i ->
            val p = corners[(i - 1 + corners.size) % corners.size]; val c = corners[i]; val q = corners[(i + 1) % corners.size]
            val a = p - c; val b = q - c; a.dot(b) / kotlin.math.max(a.len() * b.len(), 1e-6f) > -0.819f // ângulo < 145° (canto arredondado partido em dois dá ~150°)
        }
        // paralelogramo = 4 cantos de verdade com lados opostos paralelos (≤ 15°): quadrado/retângulo visto de viés
        // (lados opostos também de comprimento parecido e casco bem preenchido: um triângulo com um 4º canto
        // espúrio tem um lado minúsculo e falha aqui)
        val parallelogram = realCornerRule && realCorners == 4 && corners.size == 4 && solidity > .88f && run {
            fun par(a: P2, b: P2): Boolean { val c = abs(a.cross(b)) / max(a.len() * b.len(), 1e-6f); return c < .18f }
            fun similar(a: P2, b: P2): Boolean { val r = a.len() / max(b.len(), 1e-6f); return r in .6f..1.67f }
            val e0 = corners[1] - corners[0]; val e1 = corners[2] - corners[1]; val e2 = corners[3] - corners[2]; val e3 = corners[0] - corners[3]
            par(e0, e2) && par(e1, e3) && similar(e0, e2) && similar(e1, e3)
        }
        val tri = !parallelogram && (corners.size == 3 || (rect < .7f && solidity > .8f))

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
        val vyx = -sin(obb.angle); val vyy = cos(obb.angle); val ocx = obb.c.x; val ocy = obb.c.y
        for (y in 0 until m.h) for (x in 0 until m.w) if (m.bits[y * m.w + x]) { sv += ((x + .5f - ocx) * vyx + (y + .5f - ocy) * vyy).toDouble(); n++ }
        return ShapeFit(shape, obb, corners, apexPositive = sv / max(1, n) < 0, solidity = solidity, rectangularity = rect, gaps = gaps)
    }

    /**
     * Vãos dentro do casco convexo: os fechados são furos (janela), os que encostam na borda do casco
     * são entalhes (L tem 1, E tem 2). Vãos menores que 4% do casco são ruído.
     */
    private fun hypotF(a: Float, b: Float) = kotlin.math.sqrt(a * a + b * b)

    /**
     * Pixels cujo centro está no casco convexo [hull] (anti-horário): o teste "nenhum produto vetorial < 0" de cada
     * pixel. Com cantos inteiros e recorte de até ~2000 px as contas em float são exatas, o conjunto é convexo e cada
     * linha é um trecho: a interseção da linha com o casco dá o trecho e o próprio teste confere as duas pontas.
     */
    internal fun hullCells(hull: List<P2>, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(w * h); val n = hull.size
        val hx = FloatArray(n) { hull[it].x }; val hy = FloatArray(n) { hull[it].y }
        val ex = FloatArray(n) { hx[(it + 1) % n] - hx[it] }; val ey = FloatArray(n) { hy[(it + 1) % n] - hy[it] }
        fun inside(x: Float, y: Float): Boolean { for (i in 0 until n) if (ex[i] * (y - hy[i]) - ey[i] * (x - hx[i]) < 0) return false; return true }
        // cantos inteiros dentro do recorte [0, w] × [0, h]: produtos ≤ 2000 · 2000 com meio pixel cabem nos 24 bits do float
        val exact = max(w, h) <= 2000 && (0 until n).all { i -> hx[i] == kotlin.math.floor(hx[i]) && hy[i] == kotlin.math.floor(hy[i]) && hx[i] >= 0f && hx[i] <= w && hy[i] >= 0f && hy[i] <= h }
        if (!exact) {
            for (y in 0 until h) for (x in 0 until w) out[y * w + x] = inside(x + .5f, y + .5f)
            return out
        }
        for (y in 0 until h) {
            val yc = y + .5; val yf = y + .5f
            var xa = Double.POSITIVE_INFINITY; var xb = Double.NEGATIVE_INFINITY
            for (i in 0 until n) {
                val ay = hy[i].toDouble(); val by = hy[(i + 1) % n].toDouble()
                if ((ay > yc) != (by > yc)) { val ax = hx[i].toDouble(); val bx = hx[(i + 1) % n].toDouble(); val xi = ax + (yc - ay) * (bx - ax) / (by - ay); xa = min(xa, xi); xb = max(xb, xi) }
            }
            // nenhuma aresta cruza (cantos em y inteiro, centro em meio): a linha passa fora do casco
            if (xa > xb) continue
            fun ins(x: Int) = inside(x + .5f, yf)
            var x0 = Math.ceil(xa - .5).toInt().coerceIn(0, w - 1); var x1 = Math.floor(xb - .5).toInt().coerceIn(0, w - 1)
            if (x0 > x1) { if (ins(x1)) x0 = x1 else if (ins(x0)) x1 = x0 else continue }
            while (x0 <= x1 && !ins(x0)) x0++
            while (x1 >= x0 && !ins(x1)) x1--
            if (x0 > x1) continue
            while (x0 > 0 && ins(x0 - 1)) x0--
            while (x1 < w - 1 && ins(x1 + 1)) x1++
            for (x in x0..x1) out[y * w + x] = true
        }
        return out
    }

    private fun gaps(m: Mask, hull: List<P2>, hullA: Float, obb: OBB, occluded: Mask? = null): List<Gap> {
        val w = m.w; val h = m.h
        val hullCell = Mask(w, h, hullCells(hull, w, h)); val hc = hullCell.bits
        val gap = Mask(w, h)
        for (i in 0 until w * h) if (hc[i] && !m.bits[i]) gap.bits[i] = true
        val out = ArrayList<Gap>()
        // eixos do OBB (as mesmas contas de obb.ux / obb.vy, uma vez só)
        val uxx = cos(obb.angle); val uxy = sin(obb.angle); val vyx = -sin(obb.angle); val vyy = cos(obb.angle)
        val ocx = obb.c.x; val ocy = obb.c.y; val ow = max(obb.w, 1f); val oh = max(obb.h, 1f)
        for (c in gap.erode().dilate().components(minCells = max(3, (hullA * .025f).toInt()))) {
            val cb = c.bits
            // vão ocupado por outra peça (≥ 50% das células): oclusão, não forma
            if (occluded != null) { var occ = 0; var tot = 0; for (i in 0 until w * h) if (cb[i]) { tot++; if (occluded.bits[i]) occ++ }; if (tot > 0 && occ * 2 >= tot) continue }
            var touches = false
            var su0 = Float.MAX_VALUE; var su1 = -Float.MAX_VALUE; var sv0 = Float.MAX_VALUE; var sv1 = -Float.MAX_VALUE; var cu = 0f; var cv = 0f; var k = 0
            for (y in 0 until h) for (x in 0 until w) { val i = y * w + x; if (!cb[i]) continue
                if (x == 0 || !hc[i - 1] || x == w - 1 || !hc[i + 1] || y == 0 || !hc[i - w] || y == h - 1 || !hc[i + w]) touches = true
                val dx = x + .5f - ocx; val dy = y + .5f - ocy
                val u = (dx * uxx + dy * uxy) / ow; val v = (dx * vyx + dy * vyy) / oh
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
 *
 * Velocidade (mesmo resultado, bit a bit, do teste ponto-no-polígono em ponto flutuante em cada pixel da caixa com
 * folga): cada linha de pixels é uma reta no molde; só os pixels a até [MARGIN] de alguma aresta (onde o
 * arredondamento do teste pode decidir) passam pelo teste original; entre eles, dentro ou fora é a paridade dos
 * cruzamentos da reta com as arestas, e o trecho conta de uma vez (a máscara pela soma acumulada da linha). Pixel
 * fora do casco do polígono nem entra. Giro candidato que não pode passar do melhor (limite superior do IoU com as
 * linhas já vistas) é abandonado.
 */
object TemplateFit {
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

    /** Forma do catálogo: polígono, furos (anéis), casco convexo e área/centroide. */
    private class Region(shape: String) {
        val poly = Catalog.polygon(shape); val holes = Catalog.holes(shape)
        val pa: Float; val pcx: Float; val pcy: Float
        /** Casco convexo do polígono (anti-horário): cantos ([hx], [hy]) e arestas ([hex], [hey], [hel]); fora dele é fora do molde. */
        val hx: DoubleArray; val hy: DoubleArray; val hex: DoubleArray; val hey: DoubleArray; val hel: DoubleArray
        /** Anéis (polígono e furos) em float, para o teste original, e em double, para a reta de cada linha. */
        val rx: Array<FloatArray>; val ry: Array<FloatArray>; val dx: Array<DoubleArray>; val dy: Array<DoubleArray>
        val edges: Int

        init {
            val (a0, a1, a2) = areaCentroid(poly, holes); pa = a0; pcx = a1; pcy = a2
            val ch = Geo2.hull(poly.map { P2(it.first, it.second) }); val nh = ch.size
            hx = DoubleArray(nh) { ch[it].x.toDouble() }; hy = DoubleArray(nh) { ch[it].y.toDouble() }
            hex = DoubleArray(nh) { hx[(it + 1) % nh] - hx[it] }; hey = DoubleArray(nh) { hy[(it + 1) % nh] - hy[it] }
            hel = DoubleArray(nh) { kotlin.math.sqrt(hex[it] * hex[it] + hey[it] * hey[it]) }
            val rings = listOf(poly) + holes
            rx = Array(rings.size) { r -> FloatArray(rings[r].size) { rings[r][it].first } }
            ry = Array(rings.size) { r -> FloatArray(rings[r].size) { rings[r][it].second } }
            dx = Array(rings.size) { r -> DoubleArray(rings[r].size) { rx[r][it].toDouble() } }
            dy = Array(rings.size) { r -> DoubleArray(rings[r].size) { ry[r][it].toDouble() } }
            edges = rings.sumOf { it.size }
        }

        private fun ring(r: Int, x: Float, y: Float): Boolean {
            val xs = rx[r]; val ys = ry[r]
            var c = false; var j = xs.size - 1
            for (i in xs.indices) { val xi = xs[i]; val yi = ys[i]; val xj = xs[j]; val yj = ys[j]; if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c; j = i }
            return c
        }

        /** O teste original: inPoly(polígono) && nenhum inPoly(furo). */
        fun exact(a: Float, b: Float): Boolean {
            if (!ring(0, a, b)) return false
            for (r in 1 until rx.size) if (ring(r, a, b)) return false
            return true
        }
    }

    /**
     * Folga (unidades do molde) em volta das arestas em que o pixel passa pelo teste original: o ponto em float fica a
     * ~1e-6 da reta em double, e o teste de cruzamentos em float só erra a ~1e-7 da aresta.
     */
    private const val MARGIN = 2e-4
    private val regions = java.util.concurrent.ConcurrentHashMap<String, Region>()
    private fun region(shape: String) = regions.getOrPut(shape) { Region(shape) }
    private val SQUARE = setOf("quadrado", "moldura", "janela", "encaixe")

    fun fit(m: Mask, obb: OBB, shape: String, vUp: Boolean, corners: List<P2> = emptyList()): Pair<Float, Float> {
        val rg = region(shape)
        val pa = rg.pa; val pcx = rg.pcx; val pcy = rg.pcy
        // escala única pela área; centroides alinhados
        val w = m.w; val h = m.h; val bits = m.bits
        var n = 0; var mx = 0f; var my = 0f
        // soma acumulada de cada linha da máscara: pixels da linha y com x < i em pre[y·(w+1) + i]
        val pre = IntArray(h * (w + 1))
        for (y in 0 until h) { var s = 0; val o = y * (w + 1); for (x in 0 until w) { pre[o + x] = s; if (bits[y * w + x]) { s++; n++; mx += x + .5f; my += y + .5f } }; pre[o + w] = s }
        if (n == 0) return 0f to 0f
        val cX = mx / n; val cY = my / n; val sc = kotlin.math.sqrt(n / pa)
        var best = 0f; var bestIou = -1f
        // candidatos: múltiplos de 45° do retângulo mínimo + direções dos lados da peça (triângulos empatam a caixa em todo lado)
        val q = (Math.PI / 2).toFloat()
        // peças com simetria de 90° só giram alinhadas ao retângulo mínimo (a diagonal empata por ruído)
        val square = shape in SQUARE
        val cands = if (square) (0 until 4).map { obb.angle + it * q } else (0 until 8).map { obb.angle + it * (Math.PI / 4).toFloat() } +
            corners.indices.flatMap { i -> val e = corners[(i + 1) % corners.size] - corners[i]; val a = kotlin.math.atan2(e.y, e.x); (0 until 4).map { a + it * q } }
        val pad = (sc * .3f).toInt() + 2
        // casco do polígono com folga (unidades do molde): ponto fora dele está longe de toda aresta e fora do molde
        val slack = 2.0 / 256
        val nh = rg.hx.size; val hx = rg.hx; val hy = rg.hy; val hex = rg.hex; val hey = rg.hey; val hel = rg.hel
        val scd = sc.toDouble()
        val nr = rg.dx.size; val maxRing = rg.dx.maxOf { it.size }
        val dist = DoubleArray(maxRing); val xc = DoubleArray(rg.edges); val zl = IntArray(rg.edges); val zr = IntArray(rg.edges)
        for (th in cands) {
            val ux = cos(th); val uy = sin(th)
            val vx: Float; val vy: Float
            if (vUp) { vx = sin(th); vy = -cos(th) } else { vx = -sin(th); vy = cos(th) }
            val thr = bestIou + 1e-4f
            var inter = 0; var tc = 0; var seen = 0; var cut = false
            // a linha de pixels x é a reta P(x) = (a0 + a1·x, b0 + b1·x) do molde
            val a1 = ux / scd; val b1 = vx / scd; val dn = kotlin.math.sqrt(a1 * a1 + b1 * b1)
            for (y in -pad until h + pad) {
                val dy = (y + .5f) - cY
                val inRow = y in 0 until h
                // trecho da linha em que P(x) cai no casco do polígono com folga: e × (P - h) ≥ -folga·|e|, linear em x
                var xs = (-pad).toDouble(); var xe = (w + pad - 1).toDouble()
                val a0 = pcx + ((.5 - cX) * ux + dy.toDouble() * uy) / scd; val b0 = pcy + ((.5 - cX) * vx + dy.toDouble() * vy) / scd
                for (i in 0 until nh) {
                    val al = hex[i] * b1 - hey[i] * a1; val be = hex[i] * (b0 - hy[i]) - hey[i] * (a0 - hx[i]) + slack * hel[i]
                    if (abs(al) < 1e-12) { if (be < 0) { xe = xs - 1; break } } else if (al > 0) xs = max(xs, -be / al) else xe = min(xe, -be / al)
                }
                if (xs <= xe) {
                    val xa = max(-pad, Math.floor(xs).toInt() - 1); val xb = min(w + pad - 1, Math.ceil(xe).toInt() + 1)
                    // cruzamentos da reta com as arestas (lado de cada canto: distância com sinal, zero conta como +) e
                    // zonas em que o pixel fica a até MARGIN de alguma aresta
                    var nc = 0; var nz = 0
                    for (r in 0 until nr) {
                        val px = rg.dx[r]; val py = rg.dy[r]; val nv = px.size
                        for (j in 0 until nv) dist[j] = (a1 * (py[j] - b0) - b1 * (px[j] - a0)) / dn
                        for (j in 0 until nv) {
                            val k = if (j + 1 == nv) 0 else j + 1
                            val dj = dist[j]; val dk = dist[k]
                            val cross = (dj >= 0.0) != (dk >= 0.0)
                            if (!cross && min(abs(dj), abs(dk)) > MARGIN) continue // longe da reta: nem cruza nem tem pixel perto
                            val ex = px[k] - px[j]; val ey = py[k] - py[j]; val len = kotlin.math.sqrt(ex * ex + ey * ey)
                            val q1 = (ex * a1 + ey * b1) / len; val q0 = (ex * (a0 - px[j]) + ey * (b0 - py[j])) / len
                            if (cross) {
                                // posição do cruzamento; com a aresta quase paralela à reta, presa ao trecho da reta que
                                // se projeta na aresta (o cruzamento fica nele). As duas contas não zeram juntas (|D| > 0).
                                val den = ex * b1 - ey * a1
                                var x = (ex * (py[j] - b0) - ey * (px[j] - a0)) / den
                                if (abs(q1) > 1e-15) {
                                    val tj = -q0 / q1; val tk = (len - q0) / q1; val lo = min(tj, tk); val hi = max(tj, tk)
                                    x = if (!x.isFinite()) (lo + hi) / 2 else x.coerceIn(lo, hi)
                                }
                                xc[nc++] = x
                            }
                            // zona: |distância à reta da aresta| ≤ MARGIN e projeção na aresta em [-MARGIN, len + MARGIN]
                            val s0 = (ex * (b0 - py[j]) - ey * (a0 - px[j])) / len; val s1 = (ex * b1 - ey * a1) / len
                            var lo = xa.toDouble(); var hi = xb.toDouble()
                            if (abs(s1) < 1e-15) { if (abs(s0) > MARGIN) continue } else {
                                val u0 = (-MARGIN - s0) / s1; val u1 = (MARGIN - s0) / s1; lo = max(lo, min(u0, u1)); hi = min(hi, max(u0, u1))
                            }
                            if (abs(q1) < 1e-15) { if (q0 < -MARGIN || q0 > len + MARGIN) continue } else {
                                val u0 = (-MARGIN - q0) / q1; val u1 = (len + MARGIN - q0) / q1; lo = max(lo, min(u0, u1)); hi = min(hi, max(u0, u1))
                            }
                            if (lo > hi) continue
                            val il = Math.ceil(lo).toInt(); val ir = Math.floor(hi).toInt()
                            if (il <= ir) { zl[nz] = il; zr[nz] = ir; nz++ }
                        }
                    }
                    // ordena cruzamentos e zonas (poucos)
                    for (i in 1 until nc) { val v = xc[i]; var j = i - 1; while (j >= 0 && xc[j] > v) { xc[j + 1] = xc[j]; j-- }; xc[j + 1] = v }
                    for (i in 1 until nz) { val l = zl[i]; val rr = zr[i]; var j = i - 1; while (j >= 0 && zl[j] > l) { zl[j + 1] = zl[j]; zr[j + 1] = zr[j]; j-- }; zl[j + 1] = l; zr[j + 1] = rr }
                    var ci = 0; var cur = xa; var zi = 0
                    val ro = y * w; val po = y * (w + 1)
                    while (cur <= xb) {
                        // próxima zona (juntando as que se encostam)
                        var zs = xb + 1; var ze = xb
                        if (zi < nz) { zs = zl[zi]; ze = zr[zi]; zi++; while (zi < nz && zl[zi] <= ze + 1) { ze = max(ze, zr[zi]); zi++ } }
                        // trecho sem zona [cur, zs - 1]: dentro/fora pela paridade dos cruzamentos à esquerda
                        val re = min(zs - 1, xb)
                        var s = cur
                        while (ci < nc && xc[ci] < s) ci++
                        while (s <= re) {
                            // até o próximo cruzamento (pixels x < c) ou o fim do trecho; ci = cruzamentos à esquerda de s
                            val e = if (ci < nc && xc[ci] <= re) min(re, Math.ceil(xc[ci]).toInt() - 1) else re
                            if (e >= s) {
                                if ((ci and 1) == 1) {
                                    tc += e - s + 1
                                    if (inRow) { val l = max(s, 0); val rr = min(e, w - 1); if (l <= rr) inter += pre[po + rr + 1] - pre[po + l] }
                                }
                                s = e + 1
                            }
                            if (ci < nc && xc[ci] <= re) ci++ else break
                        }
                        // pixels da zona: o teste original
                        for (x in max(zs, cur)..min(ze, xb)) {
                            val ddx = (x + .5f) - cX
                            val a = pcx + (ddx * ux + dy * uy) / sc; val b = pcy + (ddx * vx + dy * vy) / sc
                            if (rg.exact(a, b)) { tc++; if (inRow && x >= 0 && x < w && bits[ro + x]) inter++ }
                        }
                        cur = max(cur, ze + 1)
                        if (zs > xb) break
                    }
                }
                if (inRow) seen += pre[y * (w + 1) + w]
                // limite superior do IoU: pixel da máscara já visto fora do molde não volta, e o do molde fora da máscara
                // já conta na união
                if (thr > 0f && (n - (seen - inter)).toDouble() / (n + (tc - inter)) < thr - 1e-6) { cut = true; break }
            }
            if (cut) continue
            val union = n + tc - inter
            val iou = inter / max(1, union).toFloat()
            if (iou > bestIou + 1e-4f) { bestIou = iou; best = th }
        }
        return best to bestIou
    }
}
