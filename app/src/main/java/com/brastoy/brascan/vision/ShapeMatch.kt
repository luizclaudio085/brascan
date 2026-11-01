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
        /**
         * [bits] com uma borda de 1 célula "fora" em volta, valendo 1 em [IN], 2 em [MAG] e 0 no resto: a conta do IoU
         * sem desvio (ponto fora do molde cai na borda pelo grampo do índice).
         */
        val code: ByteArray
        /**
         * Classe (de [code]) da célula do centroide e raio (unidades do molde) do disco em volta dele em que toda célula
         * tem essa classe; [rOut]: além dele toda célula é fora (nem peça nem ímã).
         */
        val c0: Int; val rIn: Double; val rOut: Double
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
            code = ByteArray((nx + 2) * (ny + 2))
            for (j in 0 until ny) for (i in 0 until nx) code[(j + 1) * (nx + 2) + i + 1] = when (bits[j * nx + i]) { IN -> 1; MAG -> 2; else -> 0 }
            // disco em volta do centroide (origem do molde) com uma classe só e raio além do qual não há peça nem ímã
            val r = RES.toDouble(); val ox0 = x0.toDouble(); val oy0 = y0.toDouble()
            val ci = Math.floor(-ox0 * r).toInt(); val cj = Math.floor(-oy0 * r).toInt()
            c0 = if (ci in 0 until nx && cj in 0 until ny) code[(cj + 1) * (nx + 2) + ci + 1].toInt() else 0
            var rin = if (c0 != 0) minOf(-ox0, ox0 + nx / r, -oy0, oy0 + ny / r) else Double.MAX_VALUE
            var rout = 0.0
            for (j in 0 until ny) for (i in 0 until nx) {
                val c = code[(j + 1) * (nx + 2) + i + 1].toInt()
                val a0 = ox0 + i / r; val a1 = ox0 + (i + 1) / r; val b0 = oy0 + j / r; val b1 = oy0 + (j + 1) / r
                if (c != c0) { val dx = max(max(a0, -a1), 0.0); val dy = max(max(b0, -b1), 0.0); rin = kotlin.math.min(rin, kotlin.math.sqrt(dx * dx + dy * dy)) }
                if (c != 0) { val fx = max(abs(a0), abs(a1)); val fy = max(abs(b0), abs(b1)); rout = max(rout, kotlin.math.sqrt(fx * fx + fy * fy)) }
            }
            rIn = rin; rOut = rout
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
    private class Pts(val x: FloatArray, val y: FloatArray, val n: Int, val area: Float) {
        /** Maior |x|, |y| dos pontos (NaN se houver NaN): limite das contas em [count]. */
        val lim: Float = run { var m = 0f; for (i in 0 until n) m = max(m, max(abs(x[i]), abs(y[i]))); m }
    }

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

    /**
     * Os mesmos pontos que [iou] visita com passo [stride], com o raio de cada um ([r]: distância ao centroide, já
     * retificado). As contagens no fim não dependem da ordem e [k] = área / pontos é o mesmo de [iou], então o IoU sai
     * igual bit a bit. [spread]: ordem ESPALHADA (cada começo da lista é uma amostra da peça inteira, e a conta parcial
     * mostra cedo que uma pose não tem como ganhar); senão, raio crescente (o disco de dentro do molde e o que fica além
     * dele são as duas pontas da lista).
     */
    private class Sub(p: Pts, stride: Int, spread: Boolean) {
        val n = (p.n + stride - 1) / stride
        val x = FloatArray(n); val y = FloatArray(n); val r = FloatArray(n)
        val k = p.area / n
        val lim = p.lim
        init {
            val ord = IntArray(n)
            if (spread) {
                val seen = BooleanArray(n); var j = 0
                var s = Integer.highestOneBit(max(1, n))
                while (s >= 1) { var i = 0; while (i < n) { if (!seen[i]) { seen[i] = true; ord[j++] = i }; i += s }; s = s shr 1 }
            } else {
                // raio ≥ 0: a ordem dos bits do float é a ordem do número
                val key = LongArray(n) { i -> val q = i * stride; (java.lang.Float.floatToIntBits(radius(p.x[q], p.y[q])).toLong() shl 32) or i.toLong() }
                key.sort(); for (j in 0 until n) ord[j] = (key[j] and 0xFFFFFFFFL).toInt()
            }
            for (j in 0 until n) { val q = ord[j] * stride; x[j] = p.x[q]; y[j] = p.y[q]; r[j] = radius(x[j], y[j]) }
        }
    }

    private fun radius(x: Float, y: Float) = kotlin.math.sqrt(x.toDouble() * x + y.toDouble() * y).toFloat()

    /**
     * Folga (unidades do molde) do disco de classe única e do raio além do qual tudo é fora: cobre o arredondamento do
     * giro em float (~1e-7 relativo), do raio guardado em float e da célula achada pelo piso (~1e-5 célula).
     */
    private const val EPS_R = 2e-4

    /**
     * Contagens (IN, MAG) dos pontos [from, to) de [xs]/[ys] na pose: inter shl 32 or ign. As mesmas contas de
     * [Tpl.at], sem desvio: para fx ≥ 0 o piso é a parte inteira de [Tpl.at]; fx < 0 dá piso ≤ -1 e, como além do
     * molde, cai na borda de [Tpl.code] (fora); NaN vira 0 nos dois.
     */
    private fun count(t: Tpl, xs: FloatArray, ys: FloatArray, from: Int, to: Int, cs: Float, sn: Float, ox: Float, oy: Float, lim: Float): Long {
        val code = t.code; val pw = t.nx + 2; val xm = t.nx + 1; val ym = t.ny + 1; val x0 = t.x0; val y0 = t.y0
        var inter = 0; var ign = 0
        // Atalho do piso pelo bit de sinal: com as contas finitas (sem NaN) e x0, y0 < 0 (fx nunca dá -0: s - x0 = s + |x0|
        // só zera em +0), fx < 0 ⟺ bit de sinal ligado, e para fx ≥ 0 a parte inteira é o piso
        val cap = 1e30f
        if (x0 < 0f && y0 < 0f && (lim + abs(ox) + abs(oy)) * (abs(cs) + abs(sn)) - x0 - y0 < cap) {
            for (i in from until to) {
                val dx = xs[i] - ox; val dy = ys[i] - oy
                val fx = (dx * cs + dy * sn - x0) * RES; val fy = (-dx * sn + dy * cs - y0) * RES
                val ix = kotlin.math.min(max((fx.toInt() + 1) and (java.lang.Float.floatToRawIntBits(fx) shr 31).inv(), 0), xm)
                val iy = kotlin.math.min(max((fy.toInt() + 1) and (java.lang.Float.floatToRawIntBits(fy) shr 31).inv(), 0), ym)
                val c = code[iy * pw + ix].toInt()
                inter += c and 1; ign += c shr 1
            }
            return (inter.toLong() shl 32) or ign.toLong()
        }
        for (i in from until to) {
            val dx = xs[i] - ox; val dy = ys[i] - oy
            val fx = (dx * cs + dy * sn - x0) * RES; val fy = (-dx * sn + dy * cs - y0) * RES
            val ix = kotlin.math.min(max(Math.floor(fx.toDouble()).toInt() + 1, 0), xm)
            val iy = kotlin.math.min(max(Math.floor(fy.toDouble()).toInt() + 1, 0), ym)
            val c = code[iy * pw + ix].toInt()
            inter += c and 1; ign += c shr 1
        }
        return (inter.toLong() shl 32) or ign.toLong()
    }

    /** Pontos conferidos entre dois testes do limite superior. */
    private const val CH = 16

    /**
     * Limite superior do IoU com [pos] de [n] pontos contados: no melhor caso todos os que faltam caem em [IN]
     * (melhor que [MAG], que só sai da união). true = o IoU final (em float) fica abaixo de [theta] com certeza
     * (folga de 1e-5 contra o arredondamento, que é ~1e-6).
     */
    private fun cannotReach(n: Int, pos: Int, inter: Int, ign: Int, k: Float, area: Float, theta: Float): Boolean {
        val thr = theta - 1e-5
        if (!(thr > 0.0)) return false
        val out = pos - inter - ign; val kd = k.toDouble()
        return (n - ign - out) * kd < thr * (area + out * kd)
    }

    /** Primeiro índice de [r] (crescente) com valor ≥ [v] (ou > [v] com [strict]). */
    private fun bound(r: FloatArray, v: Double, strict: Boolean): Int {
        var lo = 0; var hi = r.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (if (strict) r[mid] <= v else r[mid] < v) lo = mid + 1 else hi = mid }
        return lo
    }

    /**
     * IoU exato na pose (igual a [iou] com o passo de [s], que está em ordem de raio): ponto a menos de rIn do centro
     * do molde (com o deslocamento: |p| + |o|) tem a classe do centro, e além de rOut (|p| - |o|) é fora, em qualquer
     * giro; esses contam de uma vez e só o anel do meio passa pela conta ponto a ponto.
     */
    private fun iouRadial(t: Tpl, s: Sub, ang: Float, sc: Float, ox: Float, oy: Float): Float {
        val cs = cos(ang) / sc; val sn = sin(ang) / sc; val area = t.area * sc * sc
        val o = kotlin.math.sqrt(ox.toDouble() * ox + oy.toDouble() * oy)
        val a = bound(s.r, (t.rIn - EPS_R) * sc - o, false); val b = max(a, bound(s.r, (t.rOut + EPS_R) * sc + o, true))
        var inter = if (t.c0 == 1) a else 0; var ign = if (t.c0 == 2) a else 0
        if (b > a) { val c = count(t, s.x, s.y, a, b, cs, sn, ox, oy, s.lim); inter += (c ushr 32).toInt(); ign += (c and 0xFFFFFFFFL).toInt() }
        val ia = inter * s.k; val ma = (s.n - ign) * s.k
        return ia / (ma + area - ia)
    }

    /** [iou] com passo 1 (todos os pontos), pela conta sem desvio. */
    private fun iouAll(t: Tpl, p: Pts, ang: Float, sc: Float, ox: Float, oy: Float): Float {
        val cs = cos(ang) / sc; val sn = sin(ang) / sc
        val c = count(t, p.x, p.y, 0, p.n, cs, sn, ox, oy, p.lim)
        val k = p.area / p.n
        val ia = (c ushr 32).toInt() * k; val ma = (p.n - (c and 0xFFFFFFFFL).toInt()) * k
        return ia / (ma + t.area * sc * sc - ia)
    }

    /**
     * Busca grossa de um molde: giros de [step0] × escalas [scs], pontos [s] (ordem espalhada). Só interessam o máximo
     * (o 1º índice entre empates, como na ordenação estável) e, para os moldes refinados, o melhor que não é vizinho
     * dele; o resto da grade é cortado pelo limite superior assim que não pode mais chegar lá, e cada conta para onde
     * parou e pode continuar. Em cada escala, os pontos do disco de dentro do molde e os de além dele têm classe
     * conhecida em qualquer giro (centroide alinhado, sem deslocamento): só o anel do meio é contado ponto a ponto.
     */
    private class Grid(val t: Tpl, s: Sub, val nCoarse: Int, val scs: List<Float>, step0: Float) {
        val size = nCoarse * scs.size
        private val n = s.n; private val k = s.k; private val lim = s.lim
        private val ax = ArrayList<FloatArray>(); private val ay = ArrayList<FloatArray>()
        private val known = IntArray(scs.size); private val in0 = IntArray(scs.size); private val mag0 = IntArray(scs.size)
        private val cs = FloatArray(size); private val sn = FloatArray(size); private val area = FloatArray(size)
        private val pos = IntArray(size); private val inter = IntArray(size); private val ign = IntArray(size)
        private val fin = BooleanArray(size); val v = FloatArray(size); private val cutAt = FloatArray(size)
        private val order: IntArray
        init {
            for (si in scs.indices) {
                val sc = scs[si]; val rin = (t.rIn - EPS_R) * sc; val rout = (t.rOut + EPS_R) * sc
                var inner = 0; var outer = 0
                for (i in 0 until n) { val r = s.r[i]; if (r < rin) inner++ else if (r > rout) outer++ }
                val bx = FloatArray(n - inner - outer); val by = FloatArray(n - inner - outer); var m = 0
                for (i in 0 until n) { val r = s.r[i]; if (!(r < rin) && !(r > rout)) { bx[m] = s.x[i]; by[m] = s.y[i]; m++ } }
                ax += bx; ay += by
                known[si] = inner + outer; in0[si] = if (t.c0 == 1) inner else 0; mag0[si] = if (t.c0 == 2) inner else 0
            }
            for (e in 0 until size) {
                val ang = (e % nCoarse) * step0; val si = e / nCoarse; val sc = scs[si]
                cs[e] = cos(ang) / sc; sn[e] = sin(ang) / sc; area[e] = t.area * sc * sc; inter[e] = in0[si]; ign[e] = mag0[si]
            }
            // amostra (1/8 do anel, espalhada) de todas: ordem de avaliação, a melhor primeiro sobe o corte logo
            val est = FloatArray(size)
            for (e in 0 until size) {
                val si = e / nCoarse; val m = ax[si].size; val pre = kotlin.math.min(m, max(CH, m / 8))
                step(e, pre)
                val f = if (pre > 0) m.toFloat() / pre else 0f
                val ie = in0[si] + (inter[e] - in0[si]) * f; val ge = mag0[si] + (ign[e] - mag0[si]) * f
                est[e] = ie * k / ((n - ge) * k + area[e] - ie * k)
            }
            // decrescente pela estimativa (bits do float em ordem de inteiro; NaN por último), sem caixas
            val key = LongArray(size) { e -> val b = java.lang.Float.floatToIntBits(-est[e]); ((if (b < 0) b xor 0x7FFFFFFF else b).toLong() shl 32) or e.toLong() }
            key.sort()
            order = IntArray(size) { (key[it] and 0xFFFFFFFFL).toInt() }
        }

        private fun step(e: Int, to: Int) {
            val si = e / nCoarse
            val r = count(t, ax[si], ay[si], pos[e], to, cs[e], sn[e], 0f, 0f, lim)
            inter[e] += (r ushr 32).toInt(); ign[e] += (r and 0xFFFFFFFFL).toInt(); pos[e] = to
        }

        /** Conta [e] até o fim (true: [v] exato) ou até provar que fica abaixo de [theta] (false). */
        private fun run(e: Int, theta: Float): Boolean {
            if (fin[e]) return true
            val si = e / nCoarse; val m = ax[si].size
            while (pos[e] < m) {
                if (cannotReach(n, known[si] + pos[e], inter[e], ign[e], k, area[e], theta)) { cutAt[e] = theta; return false }
                step(e, kotlin.math.min(m, pos[e] + CH))
            }
            val ia = inter[e] * k; val ma = (n - ign[e]) * k
            v[e] = ia / (ma + area[e] - ia); fin[e] = true
            return true
        }

        /** Índice do máximo (o menor entre empates). */
        fun argmax(): Int {
            var best = -1; var bv = Float.NEGATIVE_INFINITY
            for (e in order) if (run(e, bv)) { val x = v[e]; if (best < 0 || x > bv || (x == bv && e < best)) { best = e; bv = x } }
            return best
        }

        /** Melhor índice que não é vizinho de [s1] (mesma escala, giro ao lado, com a volta), o menor entre empates. */
        fun second(s1: Int): Int? {
            fun nb(i: Int) = s1 / nCoarse == i / nCoarse && (abs(s1 - i) <= 1 || abs(s1 % nCoarse - i % nCoarse) >= nCoarse - 1)
            var best = -1; var bv = Float.NEGATIVE_INFINITY
            for (e in 0 until size) if (fin[e] && !nb(e)) { val x = v[e]; if (best < 0 || x > bv || (x == bv && e < best)) { best = e; bv = x } }
            for (e in order) {
                if (fin[e] || nb(e)) continue
                if (best >= 0 && cutAt[e] <= bv) continue // já se sabe que fica abaixo de cutAt ≤ bv
                if (run(e, bv)) { val x = v[e]; if (best < 0 || x > bv || (x == bv && e < best)) { best = e; bv = x } }
            }
            return if (best < 0) null else best
        }
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
     * a até 0,1 do melhor (~1500 pontos), e o IoU final com todos os pontos.
     *
     * Velocidade, com o resultado igual bit a bit ao da conta ponto a ponto ([iou]): a conta por ponto é sem desvio
     * ([count]); na busca grossa só o máximo de cada molde (e o 2º não vizinho dos refinados) precisa ser exato, e cada
     * giro para assim que o limite superior do IoU fica abaixo do melhor ([Grid]); pontos do disco de classe única em
     * volta do centroide do molde e os de além do molde contam de uma vez ([Grid], [iouRadial]).
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
        // conta não finita (deformação degenerada): o caminho direto, ponto a ponto (os atalhos supõem números finitos)
        if (!(p.lim.isFinite() && p.area.isFinite() && p.area > 0f)) return matchPlain(shapes, p, h, coarse, fine)
        val pc = Sub(p, coarse, spread = true); val pf = Sub(p, fine, spread = false)
        // 1) busca grossa em todos os moldes: giro de 5° × escala (com e sem os ímãs), máscara subamostrada
        class Cand(val s: String, val t: Tpl, val grid: Grid, val first: Int, val iou: Float)
        val cands = ArrayList<Cand>()
        val step0 = Math.toRadians(5.0).toFloat()
        for (s in shapes) for (t in templates[s] ?: continue) {
            // escala inicial pela área: a máscara pode ter os ímãs (fechamento cobriu) ou não (entalhe) — no E e no L
            // os ímãs são boa parte da peça, então as duas hipóteses entram na busca grossa
            val scs = listOf(sqrt(p.area / t.area), sqrt(p.area / (t.area + t.magArea))).let { if (it[0] / it[1] < 1.03f) it.take(1) else it }
            val nCoarse = max(1, Math.round(t.period / step0))
            val g = Grid(t, pc, nCoarse, scs, step0)
            val first = g.argmax()
            cands += Cand(s, t, g, first, g.v[first])
        }
        // 2) refino (giro, escala, deslocamento subpixel) só dos moldes que podem ganhar: até 0,1 do melhor da busca grossa
        val top = cands.maxOfOrNull { it.iou } ?: return emptyList()
        val refineSet = cands.sortedByDescending { it.iou }.filterIndexed { i, cd -> i < 2 || cd.iou >= top - .1f }.toSet()
        val out = ArrayList<Score>()
        for (cd in cands) {
            val t = cd.t; val g = cd.grid
            fun seed(i: Int) = (i % g.nCoarse) * step0 to g.scs[i / g.nCoarse]
            var best: Score? = null
            if (cd !in refineSet) { val (a0, s0) = seed(cd.first); best = Score(cd.s, cd.iou, a0, s0, t.mirror, holeIou(t, h, a0, s0, 0f, 0f)) }
            // os 2 melhores (giro, escala) distintos (não vizinhos) vão para o refino
            else for ((ang0, sc0) in listOfNotNull(cd.first, g.second(cd.first)).map(::seed)) {
                var ang = ang0; var sc = sc0; var ox = 0f; var oy = 0f
                var cur = iouRadial(t, pf, ang, sc, ox, oy)
                // subida de encosta: giro (2,5° → 0,3°), escala (±4% → ±0,5%), deslocamento (1 → 0,125 px)
                var stepA = Math.toRadians(2.5).toFloat(); var stepS = .04f; var stepT = 1f
                repeat(4) {
                    for (dir in floatArrayOf(-1f, 1f)) { val v = iouRadial(t, pf, ang + dir * stepA, sc, ox, oy); if (v > cur) { cur = v; ang += dir * stepA } }
                    for (dir in floatArrayOf(-1f, 1f)) { val s2 = sc * (1 + dir * stepS); val v = iouRadial(t, pf, ang, s2, ox, oy); if (v > cur) { cur = v; sc = s2 } }
                    for ((ddx, ddy) in listOf(-1f to 0f, 1f to 0f, 0f to -1f, 0f to 1f)) {
                        val v = iouRadial(t, pf, ang, sc, ox + ddx * stepT, oy + ddy * stepT); if (v > cur) { cur = v; ox += ddx * stepT; oy += ddy * stepT }
                    }
                    stepA /= 2; stepS /= 2; stepT /= 2
                }
                if (fine > 1) cur = iouAll(t, p, ang, sc, ox, oy)
                if (best == null || cur > best.iou) best = Score(cd.s, cur, ang, sc, t.mirror, holeIou(t, h, ang, sc, ox, oy))
            }
            best?.let { bs -> val prev = out.indexOfFirst { it.shape == cd.s }; if (prev < 0) out += bs else if (bs.iou > out[prev].iou) out[prev] = bs }
        }
        return out.sortedByDescending { it.iou }
    }

    /** [match] sem atalhos: todas as poses da busca grossa e do refino pela conta ponto a ponto de [iou]. */
    private fun matchPlain(shapes: Collection<String>, p: Pts, h: Pts?, coarse: Int, fine: Int): List<Score> {
        class Cand(val s: String, val t: Tpl, val seeds: List<Pair<Float, Float>>, val iou: Float)
        val cands = ArrayList<Cand>()
        for (s in shapes) for (t in templates[s] ?: continue) {
            val scs = listOf(sqrt(p.area / t.area), sqrt(p.area / (t.area + t.magArea))).let { if (it[0] / it[1] < 1.03f) it.take(1) else it }
            val step0 = Math.toRadians(5.0).toFloat()
            val nCoarse = max(1, Math.round(t.period / step0))
            val coarseIou = FloatArray(nCoarse * scs.size) { iou(t, p, (it % nCoarse) * step0, scs[it / nCoarse], 0f, 0f, coarse) }
            val order = coarseIou.indices.sortedByDescending { coarseIou[it] }
            val seeds = ArrayList<Int>()
            for (i in order) {
                if (seeds.none { it / nCoarse == i / nCoarse && (abs(it - i) <= 1 || abs(it % nCoarse - i % nCoarse) >= nCoarse - 1) }) seeds += i
                if (seeds.size == 2) break
            }
            cands += Cand(s, t, seeds.map { (it % nCoarse) * step0 to scs[it / nCoarse] }, coarseIou[order[0]])
        }
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
        // o mesmo que as componentes do complemento que não encostam na borda: o fundo ligado à borda sai numa
        // busca só (sem uma máscara por componente) e cada vão que sobra é medido
        val w = m.w; val h = m.h; val n = w * h; val bits = m.bits
        val cnt = m.count(); val out = Mask(w, h)
        if (n == 0) return out
        val seen = BooleanArray(n); val q = IntArray(n); var head = 0; var tail = 0
        fun push(i: Int) { if (!bits[i] && !seen[i]) { seen[i] = true; q[tail++] = i } }
        fun flood() { while (head < tail) { val i = q[head++]; val x = i % w; if (x > 0) push(i - 1); if (x < w - 1) push(i + 1); if (i >= w) push(i - w); if (i + w < n) push(i + w) } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        flood()
        for (s in 0 until n) {
            if (bits[s] || seen[s]) continue
            head = 0; tail = 0; push(s); flood()
            val k = tail; if (k >= max(4f, (cnt + k) * .008f)) for (j in 0 until k) out.bits[q[j]] = true
        }
        return out
    }
}
