package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Câmera da foto, para levar o contorno das peças deitadas ao plano do chão. Referencial de visão: x para a direita,
 * y para baixo, z para a frente (pixel = c + f · (x/z, y/z)). [down]: gravidade (para baixo) nesse referencial;
 * null = estimar pelas próprias peças. [heightM]: altura da câmera ao chão (m); > 0 dá escala absoluta.
 */
class GroundCam(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val down: FloatArray? = null, val heightM: Float = 0f) {
    companion object {
        /**
         * Sem intrínsecos (foto da galeria): câmera principal de celular, ~25 mm equivalentes → foco ≈ 0,78 do lado
         * maior da imagem, centro óptico no meio.
         */
        fun guess(w: Int, h: Int): GroundCam { val f = .78f * max(w, h); return GroundCam(f, f, w / 2f, h / 2f) }

        /**
         * De um quadro do SLAM/AR: intrínsecos na resolução do quadro e pose câmera→mundo (coluna-maior, câmera
         * olhando para -Z, Y do mundo para cima — o mesmo de [FrameSample]). A gravidade vem da pose.
         */
        fun fromFrame(f: FrameSample, heightM: Float = 0f): GroundCam {
            val m = f.camToWorld
            // mundo (0,-1,0) no referencial de visão: x = direita (col 0), y = -cima (col 1), z = frente (-col 2)
            return GroundCam(f.fx, f.fy, f.cx, f.cy, floatArrayOf(-m[1], m[5], m[9]), heightM)
        }

        /**
         * Do sensor de gravidade do Android (TYPE_GRAVITY/acelerômetro parado: aponta para CIMA, referencial do
         * aparelho x direita, y para o topo da tela, z saindo da tela), câmera traseira e imagem na mesma orientação
         * da tela em retrato (girar o vetor junto se a imagem vier deitada).
         */
        fun fromDeviceGravity(fx: Float, fy: Float, cx: Float, cy: Float, gx: Float, gy: Float, gz: Float, heightM: Float = 0f) =
            GroundCam(fx, fy, cx, cy, floatArrayOf(-gx, gy, gz), heightM)
    }
}

/**
 * Retificação do chão: câmera virtual no mesmo lugar olhando na direção da gravidade. Todo ponto do chão fica à
 * mesma distância dela, então a escala é a mesma na imagem inteira (some a perspectiva: peça longe deixa de parecer
 * menor; quadrado visto de viés volta a ser quadrado). Unidade: pixels da câmera real à distância da altura
 * (lado retificado = lado em m × fx / altura).
 */
class GroundRect(val cam: GroundCam, down: FloatArray) {
    private val gx: Float; private val gy: Float; private val gz: Float
    private val ax: Float; private val ay: Float; private val az: Float
    private val bx: Float; private val by: Float; private val bz: Float

    init {
        val l = sqrt(down[0] * down[0] + down[1] * down[1] + down[2] * down[2]).coerceAtLeast(1e-9f)
        gx = down[0] / l; gy = down[1] / l; gz = down[2] / l
        // e1 = x da câmera sem a componente da gravidade; e2 = g × e1
        var x = 1f - gx * gx; var y = -gx * gy; var z = -gx * gz
        val n = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-9f); x /= n; y /= n; z /= n
        ax = x; ay = y; az = z
        bx = gy * az - gz * ay; by = gz * ax - gx * az; bz = gx * ay - gy * ax
    }

    /** Inclinação da câmera: ângulo entre o eixo óptico e a vertical (graus). */
    val tiltDeg get() = Math.toDegrees(acos(gz.coerceIn(-1f, 1f).toDouble())).toFloat()

    /** Metros por unidade retificada (0 sem altura conhecida). */
    val metersPerUnit get() = if (cam.heightM > 0) cam.heightM / cam.fx else 0f

    /** Ponto da imagem (pixels contínuos) → chão retificado; null acima do horizonte. */
    fun map(x: Float, y: Float): P2? {
        val rx = (x - cam.cx) / cam.fx; val ry = (y - cam.cy) / cam.fy
        val q = gx * rx + gy * ry + gz
        if (q < .05f) return null
        return P2(cam.fx * (ax * rx + ay * ry + az) / q, cam.fx * (bx * rx + by * ry + bz) / q)
    }

    fun mapAll(pts: List<P2>): List<P2>? { val out = ArrayList<P2>(pts.size); for (p in pts) out += map(p.x, p.y) ?: return null; return out }

    /** [map] de [n] pontos sem alocar (mesma conta, mesmos floats); false se algum ficou acima do horizonte. */
    internal fun mapInto(xs: FloatArray, ys: FloatArray, n: Int, ox: FloatArray, oy: FloatArray): Boolean {
        val fx = cam.fx; val fy = cam.fy; val cx = cam.cx; val cy = cam.cy
        for (i in 0 until n) {
            val rx = (xs[i] - cx) / fx; val ry = (ys[i] - cy) / fy
            val q = gx * rx + gy * ry + gz
            if (q < .05f) return false
            ox[i] = fx * (ax * rx + ay * ry + az) / q; oy[i] = fx * (bx * rx + by * ry + bz) / q
        }
        return true
    }

    companion object {
        /** Gravidade de uma câmera inclinada [tiltDeg] com o chão "descendo" na direção [azDeg] da imagem (0° = para baixo). */
        fun down(tiltDeg: Float, azDeg: Float): FloatArray {
            val t = Math.toRadians(tiltDeg.toDouble()); val a = Math.toRadians(azDeg.toDouble())
            return floatArrayOf((sin(t) * sin(a)).toFloat(), (sin(t) * cos(a)).toFloat(), cos(t).toFloat())
        }
    }
}

/**
 * Inclinação do chão SEM gravidade, pelas próprias peças: no chão visto de cima o casco de um quadrado, triângulo
 * equilátero, moldura, janela ou porta é igual em toda direção (momentos de 2ª ordem iguais) e o de um retângulo
 * tem a proporção do catálogo; com a câmera inclinada eles achatam na direção da inclinação. Procura a gravidade
 * (inclinação, direção) que, retificando, devolve a cada peça a anisotropia do catálogo — e, de quebra, faz os lados
 * caírem nos 3 tamanhos (a perspectiva some: peça longe deixa de parecer menor). Prior fraco a favor de pouca
 * inclinação (foto de peças no chão é tirada de cima).
 */
object GroundEstimate {
    class Item(val hull: List<P2>, val shape: String, val weight: Float)
    class Result(val down: FloatArray, val tiltDeg: Float, val azDeg: Float, val cost: Float, val flatCost: Float)

    /** Ruído da anisotropia do casco medida (pixels da borda, borrão). */
    private const val SIGMA_AN = .05f
    /** Prior da inclinação (graus): custo (t / isto)². */
    private const val TILT_PRIOR = 30f
    private const val MAX_ITEMS = 30
    @JvmField var useScaleTerm = true
    /** Teto do custo de anisotropia por peça (σ², perda truncada em 3σ): forma errada ou duas peças juntas não puxam a inclinação. */
    @JvmField var anTrunc = 9f
    /**
     * Mínimo de peças para estimar. Com 2 a inclinação (2 incógnitas) e a escala se confundem: qualquer razão de
     * tamanhos entre as duas é "explicada" por uma inclinação que põe a menor mais longe (foto real "tamanhos" em
     * 640 px: 26° deixava o quadrado médio do tamanho do grande). Com 3+ as razões entre elas não fecham todas ao
     * mesmo tempo com uma inclinação errada.
     */
    @JvmField var minItems = 3
    /** Ganho mínimo de custo (plano − inclinado) para aceitar a inclinação; abaixo disso fica a foto crua (0 = sempre aceita). */
    @JvmField var minGain = 0f

    /**
     * [side]: lado medido de um casco já retificado da peça de índice k em [items] e a confiança na forma (0..1) —
     * o mesmo que o detector usa para o tamanho. [inventory]: no termo da escala, cada peça só pode ter os tamanhos
     * em que a forma dela existe ([Catalog.sizesOf], inventário do simulador). [side] é chamado de várias threads ao
     * mesmo tempo ([Par]): tem que ser uma função pura (como [PhotoPieces.measureWithTrust]).
     */
    fun estimate(cam: GroundCam, items: List<Item>, inventory: Boolean = false, side: (List<P2>, Int) -> Pair<Float, Float>): Result? =
        estimateArrays(cam, items, inventory) { xs, ys, n, k, out -> val (sd, t) = side(List(n) { P2(xs[it], ys[it]) }, k); out[0] = sd; out[1] = t }

    /** Lado e confiança (out[0], out[1]) do casco retificado ([n] pontos em xs, ys) da peça de índice k. */
    fun interface ArraySide { fun side(xs: FloatArray, ys: FloatArray, n: Int, k: Int, out: FloatArray) }

    /**
     * [estimate] com os cascos em arrays (sem objetos por ponto em cada hipótese): as mesmas contas, na mesma ordem
     * ([GroundRect.mapInto], [PolyMoments.anisotropy], [HullOps.hull]). O custo de cada hipótese (inclinação, direção)
     * não depende das outras: a grade e cada refino são avaliados em paralelo ([Par]) e o melhor é escolhido depois na
     * ordem de sempre (mesmo empate, mesmo resultado que o laço sequencial).
     */
    internal fun estimateArrays(cam: GroundCam, items: List<Item>, inventory: Boolean, side: ArraySide): Result? {
        // as MAX_ITEMS maiores bastam (peça pequena tem anisotropia ruidosa) e seguram o custo com 60 peças
        val idx = items.indices.filter { items[it].weight > 0 && items[it].hull.size >= 3 }.sortedByDescending { Geo2.area(items[it].hull) }.take(MAX_ITEMS)
        val its = idx.map { items[it] }
        if (its.size < maxOf(2, minItems)) return null
        val an0 = FloatArray(its.size) { PolyMoments.catalogAnisotropy(its[it].shape) }
        val ws = FloatArray(its.size) { its[it].weight }
        val allowed = if (inventory) SizeScale.allowedFor(its.map { it.shape }) else null
        val hx = Array(its.size) { i -> FloatArray(its[i].hull.size) { its[i].hull[it].x } }
        val hy = Array(its.size) { i -> FloatArray(its[i].hull.size) { its[i].hull[it].y } }
        val maxN = its.maxOf { it.hull.size }
        val scaleTerm = useScaleTerm; val trunc = anTrunc
        /** Parte da anisotropia do custo (a mesma soma, na mesma ordem); MAX_VALUE se alguma peça fica acima do horizonte. */
        fun anisoPart(t: Float, a: Float): Float {
            val rect = GroundRect(cam, GroundRect.down(t, a))
            var c = 0f
            val mx = FloatArray(maxN); val my = FloatArray(maxN)
            for (i in its.indices) {
                val n = hx[i].size
                if (!rect.mapInto(hx[i], hy[i], n, mx, my)) return Float.MAX_VALUE // peça acima do horizonte: hipótese impossível
                val r = (PolyMoments.anisotropy(mx, my, n) - an0[i]) / SIGMA_AN
                c += ws[i] * minOf(r * r, trunc)
            }
            return c
        }
        /** Custo inteiro a partir da parte da anisotropia [c0] (a mesma conta do laço original: c − 2·escala + prior). */
        fun fullCost(t: Float, a: Float, c0: Float): Float {
            if (c0 == Float.MAX_VALUE) return Float.MAX_VALUE
            var c = c0
            if (scaleTerm) {
                val rect = GroundRect(cam, GroundRect.down(t, a))
                val mx = FloatArray(maxN); val my = FloatArray(maxN); val ox = FloatArray(maxN); val oy = FloatArray(maxN); val keys = LongArray(maxN)
                val sd = FloatArray(2)
                val sides = FloatArray(its.size); val sw = FloatArray(its.size)
                for (i in its.indices) {
                    val n = hx[i].size
                    rect.mapInto(hx[i], hy[i], n, mx, my)
                    val hn = HullOps.hull(mx, my, n, ox, oy, keys)
                    side.side(ox, oy, hn, idx[i], sd); sides[i] = sd[0]; sw[i] = ws[i] * (.3f + .7f * sd[1])
                }
                c -= 2 * SizeScale.bestLogLikelihood(sides, sw, allowed)
            }
            val tp = t / TILT_PRIOR
            return c + tp * tp
        }
        // Poda exata: o termo da escala nunca passa de llUp (cota de cada peça × peso máximo, com folga para o float),
        // então custo ≥ anisotropia − 2·llUp + prior (as mesmas contas em float, monótonas). Hipótese com essa cota
        // acima do melhor custo já calculado não pode ser a melhor nem empatar com ela: nem se calcula a escala dela.
        // As outras são calculadas inteiras, da menor cota para a maior (em paralelo, cada thread pega a próxima); no
        // fim a escolha percorre as calculadas na ordem original (mesmo empate: a 1ª) — o mesmo resultado do laço
        // completo, qualquer que seja a ordem em que as threads terminaram.
        val llUp = if (!scaleTerm) 0f else {
            var s = 0f; for (i in its.indices) s += ws[i] * maxOf(0f, SizeScale.llUpperBound(allowed?.get(i)))
            s * (1 + 1e-4f) + 1e-3f
        }
        /** Custos (NaN = podada: custo maior que o melhor) das hipóteses ([ts], [az]), com [best0] = melhor custo antes delas. */
        fun evaluate(ts: FloatArray, az: FloatArray, m: Int, best0: Float): FloatArray {
            val an = Par.mapFloat(m) { anisoPart(ts[it], az[it]) }
            val lb = FloatArray(m) { k -> if (an[k] == Float.MAX_VALUE) Float.MAX_VALUE else { val tp = ts[k] / TILT_PRIOR; an[k] - 2 * llUp + tp * tp } }
            val out = FloatArray(m) { if (an[it] == Float.MAX_VALUE) Float.MAX_VALUE else Float.NaN }
            val order = (0 until m).filter { an[it] != Float.MAX_VALUE }.sortedWith(compareBy<Int> { lb[it] }.thenBy { it })
            val best = java.util.concurrent.atomic.AtomicInteger(java.lang.Float.floatToIntBits(best0))
            Par.forEach(order.size) { p ->
                val k = order[p]
                // o melhor só desce: cota acima dele agora fica acima dele no fim
                if (lb[k] > java.lang.Float.intBitsToFloat(best.get())) return@forEach
                val c = fullCost(ts[k], az[k], an[k]); out[k] = c
                while (true) { val cur = best.get(); if (!(c < java.lang.Float.intBitsToFloat(cur)) || best.compareAndSet(cur, java.lang.Float.floatToIntBits(c))) break }
            }
            return out
        }
        // hipótese 0 = foto crua; grade grossa (o custo varia devagar com a direção) e dois refinos em volta do melhor
        val gt = FloatArray(11 * 12); val ga = FloatArray(gt.size)
        var g = 0
        for (t in 5..55 step 5) for (a in 0 until 360 step 30) { gt[g] = t.toFloat(); ga[g] = a.toFloat(); g++ }
        val flat = fullCost(0f, 0f, anisoPart(0f, 0f))
        val gc = evaluate(gt, ga, gt.size, flat)
        var bt = 0f; var ba = 0f; var bc = flat
        for (k in gt.indices) { val c = gc[k]; if (c < bc) { bc = c; bt = gt[k]; ba = ga[k] } } // NaN (podada) nunca é < bc
        val rt = FloatArray(24); val ra = FloatArray(24)
        for ((st, sa) in listOf(2f to 10f, 1f to 3f)) {
            if (bt <= 0) break
            val t0 = bt; val a0 = ba
            var m = 0
            for (dt in -2..2) for (da in -2..2) {
                val t = t0 + dt * st; if (t <= 0 || (dt == 0 && da == 0)) continue
                rt[m] = t; ra[m] = a0 + da * sa; m++
            }
            val rc = evaluate(rt, ra, m, bc)
            for (k in 0 until m) { val c = rc[k]; if (c < bc) { bc = c; bt = rt[k]; ba = ra[k] } }
        }
        if (bt > 0 && flat - bc < minGain) return Result(GroundRect.down(0f, 0f), 0f, 0f, flat, flat)
        return Result(GroundRect.down(bt, ba), bt, ((ba % 360) + 360) % 360, bc, flat)
    }
}

/** Momentos de 2ª ordem de polígono (fórmulas fechadas por arestas). */
object PolyMoments {
    /** Área, centroide e momentos centrais (μxx, μyy, μxy) por unidade de área. */
    fun of(p: List<P2>): FloatArray = of(FloatArray(p.size) { p[it].x }, FloatArray(p.size) { p[it].y }, p.size)

    /** [of] com os [n] vértices em arrays. */
    fun of(xs: FloatArray, ys: FloatArray, n: Int): FloatArray { val out = FloatArray(6); ofInto(xs, ys, n, out); return out }

    private fun ofInto(xs: FloatArray, ys: FloatArray, n: Int, out: FloatArray) {
        var a = 0.0; var cx = 0.0; var cy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val x0 = xs[i].toDouble(); val y0 = ys[i].toDouble(); val x1 = xs[j].toDouble(); val y1 = ys[j].toDouble()
            val c = x0 * y1 - x1 * y0
            a += c; cx += (x0 + x1) * c; cy += (y0 + y1) * c
            sxx += (x0 * x0 + x0 * x1 + x1 * x1) * c; syy += (y0 * y0 + y0 * y1 + y1 * y1) * c
            sxy += (x0 * y1 + 2 * x0 * y0 + 2 * x1 * y1 + x1 * y0) * c
        }
        a /= 2
        if (abs(a) < 1e-9) { out.fill(0f); return }
        cx /= 6 * a; cy /= 6 * a
        val mxx = sxx / 12 / a - cx * cx; val myy = syy / 12 / a - cy * cy; val mxy = sxy / 24 / a - cx * cy
        out[0] = abs(a).toFloat(); out[1] = cx.toFloat(); out[2] = cy.toFloat(); out[3] = mxx.toFloat(); out[4] = myy.toFloat(); out[5] = mxy.toFloat()
    }

    /** 0 = igual em toda direção (quadrado, triângulo equilátero, octógono); retângulo 2:1 = 0,6. */
    fun anisotropy(p: List<P2>): Float = anisotropy(FloatArray(p.size) { p[it].x }, FloatArray(p.size) { p[it].y }, p.size)

    /** [anisotropy] com os [n] vértices em arrays. */
    fun anisotropy(xs: FloatArray, ys: FloatArray, n: Int): Float {
        val m = FloatArray(6); ofInto(xs, ys, n, m)
        val tr = m[3] + m[4]; if (tr <= 0) return 0f
        return sqrt((m[3] - m[4]) * (m[3] - m[4]) + 4 * m[5] * m[5]) / tr
    }

    private val catalogCache = HashMap<String, Float>()

    /** Anisotropia do casco convexo da forma do catálogo (o que o casco da máscara deve ter visto de cima). */
    fun catalogAnisotropy(shape: String): Float = synchronized(catalogCache) {
        catalogCache.getOrPut(shape) { anisotropy(Geo2.hull(Catalog.polygon(shape).map { P2(it.first, it.second) })) }
    }
}

/**
 * Casco convexo, área e caixa mínima com os pontos em arrays: as MESMAS contas de [Geo2.hull], [Geo2.area] e
 * [Geo2.minAreaRect] (mesma ordem das operações em float, mesmos empates), sem objetos por ponto. Usadas no laço
 * quente da estimativa da inclinação (um casco por peça por hipótese) e na medida do lado.
 */
internal object HullOps {
    /** Chave de ordenação de um float na ordem de Float.compare (−0 antes de +0), e a volta. */
    private fun key(f: Float): Int { val b = java.lang.Float.floatToIntBits(f); return if (b < 0) b xor 0x7fffffff else b }
    private fun unkey(k: Int): Float = java.lang.Float.intBitsToFloat(if (k < 0) k xor 0x7fffffff else k)

    /**
     * [Geo2.hull] dos [n] pontos ([xs], [ys]) em ([ox], [oy]); devolve quantos vértices. [keys]: rascunho (≥ n).
     * Igual ao original: menos de 3 pontos voltam como estão; repetidos saem (distinct); ordem por (x, y) com
     * Float.compare; cadeia monótona com o mesmo produto vetorial (≤ 0 descarta); inferior + superior sem o último.
     */
    fun hull(xs: FloatArray, ys: FloatArray, n: Int, ox: FloatArray, oy: FloatArray, keys: LongArray): Int {
        if (n < 3) { for (i in 0 until n) { ox[i] = xs[i]; oy[i] = ys[i] }; return n }
        for (i in 0 until n) keys[i] = (key(xs[i]).toLong() shl 32) or ((key(ys[i]) xor Int.MIN_VALUE).toLong() and 0xFFFFFFFFL)
        java.util.Arrays.sort(keys, 0, n)
        var m = 0
        for (i in 0 until n) if (m == 0 || keys[i] != keys[m - 1]) keys[m++] = keys[i]
        val sx = FloatArray(m); val sy = FloatArray(m)
        for (i in 0 until m) { sx[i] = unkey((keys[i] shr 32).toInt()); sy[i] = unkey(keys[i].toInt() xor Int.MIN_VALUE) }
        if (m < 3) { for (i in 0 until m) { ox[i] = sx[i]; oy[i] = sy[i] }; return m }
        val st = IntArray(m)
        var l = 0
        for (p in 0 until m) {
            while (l >= 2 && turn(sx, sy, st[l - 2], st[l - 1], p) <= 0) l--
            st[l++] = p
        }
        var k = 0
        for (i in 0 until l - 1) { ox[k] = sx[st[i]]; oy[k] = sy[st[i]]; k++ }
        l = 0
        for (p in m - 1 downTo 0) {
            while (l >= 2 && turn(sx, sy, st[l - 2], st[l - 1], p) <= 0) l--
            st[l++] = p
        }
        for (i in 0 until l - 1) { ox[k] = sx[st[i]]; oy[k] = sy[st[i]]; k++ }
        return k
    }

    /** (b − a) × (p − a): o (lo[l−1] − lo[l−2]).cross(p − lo[l−2]) de [Geo2.hull]. */
    private fun turn(x: FloatArray, y: FloatArray, a: Int, b: Int, p: Int): Float {
        val d1x = x[b] - x[a]; val d1y = y[b] - y[a]; val d2x = x[p] - x[a]; val d2y = y[p] - y[a]
        return d1x * d2y - d1y * d2x
    }

    /** [Geo2.area]. */
    fun area(xs: FloatArray, ys: FloatArray, n: Int): Float {
        var a = 0f
        for (i in 0 until n) { val j = if (i + 1 == n) 0 else i + 1; a += xs[i] * ys[j] - ys[i] * xs[j] }
        return abs(a) / 2
    }

    /** Largura e altura (w ≥ h) do [Geo2.minAreaRect] em out[0], out[1] (o mesmo retângulo, o mesmo empate). */
    fun minRectWH(xs: FloatArray, ys: FloatArray, n: Int, out: FloatArray) {
        if (n < 3) { out[0] = 0f; out[1] = 0f; return }
        var found = false; var bw = 0f; var bh = 0f; var barea = 0f
        for (i in 0 until n) {
            val j = if (i + 1 == n) 0 else i + 1
            val ex = xs[j] - xs[i]; val ey = ys[j] - ys[i]; val l = kotlin.math.hypot(ex, ey); if (l < 1e-9f) continue
            val inv = 1f / l; val ux = ex * inv; val uy = ey * inv; val vx = -uy; val vy = ux
            var a0 = Float.MAX_VALUE; var a1 = -Float.MAX_VALUE; var b0 = Float.MAX_VALUE; var b1 = -Float.MAX_VALUE
            for (q in 0 until n) {
                val px = xs[q]; val py = ys[q]
                val a = px * ux + py * uy; val b = px * vx + py * vy
                a0 = kotlin.math.min(a0, a); a1 = kotlin.math.max(a1, a); b0 = kotlin.math.min(b0, b); b1 = kotlin.math.max(b1, b)
            }
            val w = a1 - a0; val h = b1 - b0; val area = w * h
            if (!found || area < barea) { found = true; bw = w; bh = h; barea = area }
        }
        if (bw < bh) { out[0] = bh; out[1] = bw } else { out[0] = bw; out[1] = bh }
    }
}
