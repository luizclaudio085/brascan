package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Classificação por MOLDE: compara a máscara da peça com o contorno exato de CADA forma do catálogo (polígono +
 * furos, os mesmos do simulador), em todos os giros, com escala livre (pela área) e centroides alinhados, e devolve
 * o IoU de cada uma. As regras do [ShapeClassifier] (casco, retangularidade, vãos) erram onde a forma é decidida por
 * detalhe (furo da moldura × janela × porta, E e L pequenos, tipo de triângulo); o molde inteiro olha tudo de uma vez.
 *
 * Espelho: só entra para forma quiral (peça virada no chão espelha o contorno). Todas as do catálogo atual têm eixo
 * de simetria, então o espelho é um giro e não custa nada; a conferência é feita na inicialização.
 *
 * Ímãs: barras escuras coladas na borda que não são primeiro plano; na máscara viram entalhes rasos. No molde a
 * região deles é neutra (não conta nem como acerto nem como erro).
 *
 * [warp] (opcional): matriz 2×2 (linha a linha) aplicada aos deslocamentos em pixels antes da comparação — tira a
 * perspectiva quando se sabe a inclinação do chão (gravidade) ou ela foi estimada pela cena.
 */
object ShapeMatch {
    /**
     * [angle]: giro (rad, coordenadas retificadas) do eixo +x do molde; [scale]: pixels (retificados) por lado da peça;
     * [iou]: peça × máscara (0..1); [holeIou]: furos do molde × furos fechados da máscara (0 sem furo dos dois lados).
     */
    data class Score(val shape: String, val iou: Float, val angle: Float, val scale: Float, val mirror: Boolean, val holeIou: Float = 0f)

    /** Formas soltas que o molde distingue. Encaixe é um quadrado sem ímãs: pelo contorno é igual ao quadrado. */
    val LOOSE = Catalog.SHAPES - "encaixe"

    /**
     * Variações das peças REAIS que o polígono do simulador não cobre (fotos de out/2026): o L de verdade tem braços
     * de ~0,55 do lado (no simulador, 0,35) — medido na foto "todas" (braço 26 px num lado de 44 px).
     */
    private val REAL_VARIANTS: Map<String, List<List<Pair<Float, Float>>>> = mapOf(
        "L" to listOf(listOf(-.5f to -.5f, .5f to -.5f, .5f to .05f, .05f to .05f, .05f to .5f, -.5f to .5f)),
    )

    private const val RES = 64f // células por lado da peça no molde rasterizado
    private const val OUT: Byte = 0; private const val IN: Byte = 1; private const val MAG: Byte = 2; private const val HOLE: Byte = 3

    /** Ímãs pela regra do simulador (Catalog.magnets) para um polígono qualquer, no tamanho grande (o que tem mais). */
    private fun magnetsOf(poly: List<Pair<Float, Float>>): List<Catalog.Magnet> {
        val out = ArrayList<Catalog.Magnet>()
        for (i in poly.indices) {
            val (px, py) = poly[i]; val (qx, qy) = poly[(i + 1) % poly.size]
            val dx = qx - px; val dy = qy - py; val len = hypot(dx, dy)
            if (len < .24f) continue
            val ux = dx / len; val uy = dy / len
            for (t in floatArrayOf(.275f, .725f)) out += Catalog.Magnet(px + dx * t - uy * .035f, py + dy * t + ux * .035f, ux, uy)
        }
        return out
    }

    /** Molde rasterizado: [OUT], [IN] (peça), [MAG] (ímã, neutro), [HOLE] (furo). */
    private class Tpl(val shape: String, poly0: List<Pair<Float, Float>>, holes0: List<List<Pair<Float, Float>>>, val mirror: Boolean) {
        val nx: Int; val ny: Int; val x0: Float; val y0: Float; val bits: ByteArray
        /** Áreas (lados²) das células [IN], [HOLE] e [MAG]. */
        val area: Float; val holeArea: Float; val magArea: Float
        /** Período de giro (rad) da forma: depois dele o molde se repete. */
        val period: Float = Math.toRadians(when (shape) { "quadrado", "moldura", "janela", "encaixe" -> 90.0; "retangulo" -> 180.0; "equilatero" -> 120.0; else -> 360.0 }).toFloat()

        init {
            val sx = if (mirror) -1f else 1f
            val poly = poly0.map { it.first * sx to it.second }
            val holes = holes0.map { h -> h.map { it.first * sx to it.second } }
            val mags = magnetsOf(poly0)
            // centroide da área (com furos): é o ponto que se alinha ao centroide da máscara
            fun one(p: List<Pair<Float, Float>>): Triple<Float, Float, Float> {
                var a = 0f; var cx = 0f; var cy = 0f
                for (i in p.indices) { val (x0, y0) = p[i]; val (x1, y1) = p[(i + 1) % p.size]; val c = x0 * y1 - x1 * y0; a += c; cx += (x0 + x1) * c; cy += (y0 + y1) * c }
                a /= 2; return Triple(abs(a), cx / (6 * a), cy / (6 * a))
            }
            val (ao, xo, yo) = one(poly); var A = ao; var X = xo * ao; var Y = yo * ao
            for (h in holes) { val (ah, xh, yh) = one(h); A -= ah; X -= xh * ah; Y -= yh * ah }
            val gx = X / A; val gy = Y / A
            x0 = poly.minOf { it.first } - gx; y0 = poly.minOf { it.second } - gy
            nx = ((poly.maxOf { it.first } - gx - x0) * RES).toInt() + 1; ny = ((poly.maxOf { it.second } - gy - y0) * RES).toInt() + 1
            bits = ByteArray(nx * ny); var cnt = 0; var hc = 0; var mc = 0
            for (j in 0 until ny) for (i in 0 until nx) {
                val a = x0 + (i + .5f) / RES + gx; val b = y0 + (j + .5f) / RES + gy
                if (!inPoly(poly, a, b)) continue
                if (holes.any { inPoly(it, a, b) }) { bits[j * nx + i] = HOLE; hc++; continue }
                // ímã (coordenadas do molde sem espelho): barra de ±0,11 ao longo da aresta, da borda até ~0,06 para dentro
                val ma = a * sx
                val magnet = mags.any { mg -> val lx = (ma - mg.x) * mg.dx + (b - mg.y) * mg.dy; val ly = -(ma - mg.x) * mg.dy + (b - mg.y) * mg.dx; abs(lx) < .12f && ly < .028f && ly > -.06f }
                if (magnet) { bits[j * nx + i] = MAG; mc++ } else { bits[j * nx + i] = IN; cnt++ }
            }
            area = cnt / (RES * RES); holeArea = hc / (RES * RES); magArea = mc / (RES * RES)
        }

        fun at(a: Float, b: Float): Byte {
            val fx = (a - x0) * RES; val fy = (b - y0) * RES
            if (fx < 0f || fy < 0f) return OUT
            val ix = fx.toInt(); val iy = fy.toInt()
            return if (ix < nx && iy < ny) bits[iy * nx + ix] else OUT
        }
    }

    private fun inPoly(p: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var c = false; var j = p.size - 1
        for (i in p.indices) { val (xi, yi) = p[i]; val (xj, yj) = p[j]; if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c; j = i }
        return c
    }

    /** Pontos (relativos ao centroide) já retificados; [area] = área deles no plano retificado. */
    private class Pts(val x: FloatArray, val y: FloatArray, val n: Int, val area: Float)

    private fun iou(t: Tpl, p: Pts, ang: Float, sc: Float, ox: Float, oy: Float, stride: Int): Float {
        val cs = cos(ang) / sc; val sn = sin(ang) / sc
        var inter = 0; var ign = 0; var cnt = 0; var i = 0
        while (i < p.n) {
            val dx = p.x[i] - ox; val dy = p.y[i] - oy
            when (t.at(dx * cs + dy * sn, -dx * sn + dy * cs)) { IN -> inter++; MAG -> ign++ }
            cnt++; i += stride
        }
        val k = p.area / cnt
        val ia = inter * k; val ma = (cnt - ign) * k
        return ia / (ma + t.area * sc * sc - ia)
    }

    /** IoU dos furos: pontos de furo fechado da máscara × células [HOLE] do molde, na mesma pose. */
    private fun holeIou(t: Tpl, h: Pts?, ang: Float, sc: Float, ox: Float, oy: Float): Float {
        val ta = t.holeArea * sc * sc
        if (h == null || h.n == 0 || ta <= 0f) return 0f
        val cs = cos(ang) / sc; val sn = sin(ang) / sc
        var inter = 0
        for (i in 0 until h.n) { val dx = h.x[i] - ox; val dy = h.y[i] - oy; if (t.at(dx * cs + dy * sn, -dx * sn + dy * cs) == HOLE) inter++ }
        val ia = inter * h.area / h.n
        return ia / (h.area + ta - ia)
    }

    private val templates: Map<String, List<Tpl>> by lazy {
        Catalog.SHAPES.associateWith { s ->
            (listOf(Catalog.polygon(s)) + (REAL_VARIANTS[s] ?: emptyList())).flatMap { poly ->
                val holes = Catalog.holes(s)
                val t = Tpl(s, poly, holes, false); val m = Tpl(s, poly, holes, true)
                // espelho só se não coincide com nenhum giro do original (forma quiral)
                listOf(t) + if (selfMatch(t, m) > .97f) emptyList() else listOf(m)
            }
        }
    }

    /** Melhor IoU do molde [m] (pontos das células) contra [t] girado, mesma escala. */
    private fun selfMatch(t: Tpl, m: Tpl): Float {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        for (j in 0 until m.ny step 2) for (i in 0 until m.nx step 2) { val v = m.bits[j * m.nx + i]; if (v == IN || v == MAG) { xs += m.x0 + (i + .5f) / RES; ys += m.y0 + (j + .5f) / RES } }
        val p = Pts(xs.toFloatArray(), ys.toFloatArray(), xs.size, xs.size * 4 / (RES * RES))
        var best = 0f
        for (d in 0 until 360) best = max(best, iou(t, p, Math.toRadians(d.toDouble()).toFloat(), 1f, 0f, 0f, 1))
        return best
    }

    /**
     * IoU de cada forma de [shapes] com a máscara [m], do maior para o menor. Busca grossa de 5° com a máscara
     * subamostrada (~300 pontos) em todos os moldes; refino de giro, escala e deslocamento (subpixel) só nos que ficaram
     * a até 0,1 do melhor (~1500 pontos), e o IoU final com todos os pontos. Peça de ~50 px: ~5 ms no PC.
     */
    fun match(m: Mask, shapes: Collection<String> = LOOSE, warp: FloatArray? = null): List<Score> {
        var n = 0; var sx = 0.0; var sy = 0.0
        for (y in 0 until m.h) for (x in 0 until m.w) if (m.bits[y * m.w + x]) { n++; sx += x + .5; sy += y + .5 }
        if (n < 6) return emptyList()
        val cx = (sx / n).toFloat(); val cy = (sy / n).toFloat()
        val a = warp?.get(0) ?: 1f; val b = warp?.get(1) ?: 0f; val c = warp?.get(2) ?: 0f; val d = warp?.get(3) ?: 1f
        val det = abs(a * d - b * c)
        fun pts(sel: BooleanArray, count: Int): Pts {
            val xs = FloatArray(count); val ys = FloatArray(count); var k = 0
            for (y in 0 until m.h) for (x in 0 until m.w) if (sel[y * m.w + x]) { val dx = x + .5f - cx; val dy = y + .5f - cy; xs[k] = a * dx + b * dy; ys[k] = c * dx + d * dy; k++ }
            return Pts(xs, ys, k, k * det)
        }
        val p = pts(m.bits, n)
        val holeMask = enclosedHoles(m); val nh = holeMask.count()
        val h = if (nh > 0) pts(holeMask.bits, nh) else null
        val coarse = max(1, n / 300); val fine = max(1, n / 1500)
        // 1) busca grossa em todos os moldes: giro de 5° × escala (com e sem os ímãs), máscara subamostrada
        class Cand(val s: String, val t: Tpl, val seeds: List<Pair<Float, Float>>, val iou: Float)
        val cands = ArrayList<Cand>()
        for (s in shapes) for (t in templates[s] ?: continue) {
            // escala inicial pela área: a máscara pode ter os ímãs (fechamento cobriu) ou não (entalhe) — no E e no L
            // os ímãs são boa parte da peça, então as duas hipóteses entram na busca grossa
            val scs = listOf(sqrt(p.area / t.area), sqrt(p.area / (t.area + t.magArea))).let { if (it[0] / it[1] < 1.03f) it.take(1) else it }
            val step0 = Math.toRadians(5.0).toFloat()
            val nCoarse = max(1, Math.round(t.period / step0))
            val coarseIou = FloatArray(nCoarse * scs.size) { iou(t, p, (it % nCoarse) * step0, scs[it / nCoarse], 0f, 0f, coarse) }
            // os 2 melhores (giro, escala) distintos (não vizinhos) vão para o refino
            val order = coarseIou.indices.sortedByDescending { coarseIou[it] }
            val seeds = ArrayList<Int>()
            for (i in order) {
                if (seeds.none { it / nCoarse == i / nCoarse && (abs(it - i) <= 1 || abs(it % nCoarse - i % nCoarse) >= nCoarse - 1) }) seeds += i
                if (seeds.size == 2) break
            }
            cands += Cand(s, t, seeds.map { (it % nCoarse) * step0 to scs[it / nCoarse] }, coarseIou[order[0]])
        }
        // 2) refino (giro, escala, deslocamento subpixel) só dos moldes que podem ganhar: até 0,1 do melhor da busca grossa
        val top = cands.maxOfOrNull { it.iou } ?: return emptyList()
        val refineSet = cands.sortedByDescending { it.iou }.filterIndexed { i, cd -> i < 2 || cd.iou >= top - .1f }.toSet()
        val out = ArrayList<Score>()
        for (cd in cands) {
            val t = cd.t
            var best: Score? = null
            if (cd !in refineSet) best = Score(cd.s, cd.iou, cd.seeds[0].first, cd.seeds[0].second, t.mirror, holeIou(t, h, cd.seeds[0].first, cd.seeds[0].second, 0f, 0f))
            else for ((ang0, sc0) in cd.seeds) {
                var ang = ang0; var sc = sc0; var ox = 0f; var oy = 0f
                var cur = iou(t, p, ang, sc, ox, oy, fine)
                // subida de encosta: giro (2,5° → 0,3°), escala (±4% → ±0,5%), deslocamento (1 → 0,125 px)
                var stepA = Math.toRadians(2.5).toFloat(); var stepS = .04f; var stepT = 1f
                repeat(4) {
                    for (dir in floatArrayOf(-1f, 1f)) { val v = iou(t, p, ang + dir * stepA, sc, ox, oy, fine); if (v > cur) { cur = v; ang += dir * stepA } }
                    for (dir in floatArrayOf(-1f, 1f)) { val s2 = sc * (1 + dir * stepS); val v = iou(t, p, ang, s2, ox, oy, fine); if (v > cur) { cur = v; sc = s2 } }
                    for ((ddx, ddy) in listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)) {
                        val v = iou(t, p, ang, sc, ox + ddx * stepT, oy + ddy * stepT, fine); if (v > cur) { cur = v; ox += ddx * stepT; oy += ddy * stepT }
                    }
                    stepA /= 2; stepS /= 2; stepT /= 2
                }
                if (fine > 1) cur = iou(t, p, ang, sc, ox, oy, 1)
                if (best == null || cur > best.iou) best = Score(cd.s, cur, ang, sc, t.mirror, holeIou(t, h, ang, sc, ox, oy))
            }
            best?.let { bs -> val prev = out.indexOfFirst { it.shape == cd.s }; if (prev < 0) out += bs else if (bs.iou > out[prev].iou) out[prev] = bs }
        }
        return out.sortedByDescending { it.iou }
    }

    /**
     * Alongamento do MAIOR furo fechado (raiz da razão dos momentos de 2ª ordem, já retificado por [warp]): o vão da
     * porta (0,52 × 0,73 com arco) dá ~1,4; o octógono da moldura, 1,0. Sem furo, 0.
     */
    fun holeElongation(m: Mask, warp: FloatArray? = null): Float {
        val big = enclosedHoles(m).components(minCells = 1).firstOrNull() ?: return 0f
        return elongation(big, warp)
    }

    /** Raiz da razão dos momentos de 2ª ordem (1 = redondo/quadrado; faixa 0,15 × 1 ≈ 6,7), já retificado por [warp]. */
    fun elongation(m: Mask, warp: FloatArray? = null): Float {
        val a = warp?.get(0) ?: 1f; val b = warp?.get(1) ?: 0f; val c = warp?.get(2) ?: 0f; val d = warp?.get(3) ?: 1f
        var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (y in 0 until m.h) for (x in 0 until m.w) if (m[x, y]) {
            val u = (a * (x + .5f) + b * (y + .5f)).toDouble(); val v = (c * (x + .5f) + d * (y + .5f)).toDouble()
            n++; sx += u; sy += v; sxx += u * u; syy += v * v; sxy += u * v
        }
        if (n < 4) return 0f
        val mx = sx / n; val my = sy / n
        val cxx = sxx / n - mx * mx; val cyy = syy / n - my * my; val cxy = sxy / n - mx * my
        val tr = cxx + cyy; val disc = sqrt(((cxx - cyy) * (cxx - cyy) / 4 + cxy * cxy).coerceAtLeast(0.0))
        val l1 = tr / 2 + disc; val l2 = (tr / 2 - disc).coerceAtLeast(1e-9)
        return sqrt(l1 / l2).toFloat()
    }

    /** Furos fechados (vãos que não encostam na borda do recorte), cada um ≥ 0,8% da peça. */
    fun enclosedHoles(m: Mask): Mask {
        val inv = Mask(m.w, m.h, BooleanArray(m.w * m.h) { !m.bits[it] })
        val cnt = m.count(); val out = Mask(m.w, m.h)
        for (c in inv.components(minCells = 1)) {
            var border = false
            for (y in 0 until c.h) if (c[0, y] || c[c.w - 1, y]) { border = true; break }
            if (!border) for (x in 0 until c.w) if (c[x, 0] || c[x, c.h - 1]) { border = true; break }
            if (border) continue
            val k = c.count(); if (k >= max(4f, (cnt + k) * .008f)) for (i in c.bits.indices) if (c.bits[i]) out.bits[i] = true
        }
        return out
    }
}
