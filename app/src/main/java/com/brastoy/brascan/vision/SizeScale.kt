package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Escala da foto (lado do quadrado GRANDE, nas unidades das medidas) estimada EM CONJUNTO com os tamanhos que
 * existem: cada peça mede 1, 0,81 ou 0,5 vezes essa unidade (grande, médio, pequeno). Escolhe a unidade que melhor
 * explica todas as peças ao mesmo tempo (verossimilhança com ruído log-normal e uma fração de medidas ruins).
 *
 * Com 2+ tamanhos na foto a escala fica determinada: as razões entre grupos (0,81 = G/M, 0,62 = M/P, 0,5 = G/P) são
 * diferentes entre si. Com um tamanho só (ou razões que não decidem) a foto é AMBÍGUA: unidade = s, s/0,81 e s/0,5
 * explicam igualmente. Critério explícito: entre as hipóteses quase empatadas fica a de MENOR unidade, isto é, as
 * maiores peças da foto são as grandes (o mesmo que o detector sempre assumiu) — e a confiança sai baixa.
 * Com escala absoluta (altura da câmera conhecida), ela entra como prior forte e desfaz a ambiguidade.
 *
 * Ruído ASSIMÉTRICO: a segmentação perde pedaço da peça (aro claro, ímã, ponta de triângulo) muito mais do que
 * ganha, então medir menor que o tamanho é mais provável que medir maior (no banco sintético, com a escala certa:
 * 10% das peças medem ≥ 5% a menos, só 10% medem ≥ 3,6% a mais). Com 7% para baixo e 4,5% para cima o corte entre
 * grande e médio cai em 0,88 do grande — o mesmo corte empírico que o detector usava nas fotos reais.
 * Proporção de cada tamanho na foto estimada pela própria foto (EM com prior de Dirichlet): peça no limite entre
 * dois tamanhos vai para o tamanho que a foto mais tem.
 */
object SizeScale {
    private val SIZES = Catalog.SIZES
    private val LOGK = FloatArray(SIZES.size) { ln(Catalog.SCALE.getValue(SIZES[it])) }

    /** Ruído do lado medido (log) quando a peça mede MENOS que o tamanho dela / MAIS que o tamanho dela. */
    @JvmField var sigmaLow = .07f
    @JvmField var sigmaHigh = .045f
    /** Estimar a proporção de cada tamanho pela foto (false = proporções iguais). */
    @JvmField var classPrior = true
    /** Fração de medidas ruins (peça cortada na borda, forma errada, duas peças juntas). */
    private const val OUTLIER = .1f
    /** Hipóteses com verossimilhança até esta distância (nats) da melhor contam como empate. */
    private const val TIE_NATS = 2f
    /** Incerteza (log) da escala absoluta: altura da câmera e lado medido. */
    private const val ABS_SIGMA = .06f
    private const val STEP = .004f

    /**
     * [unit]: lado do quadrado grande (unidades das medidas). [conf]: probabilidade de a escala estar certa (0..1;
     * ~1/3 numa foto ambígua com um tamanho só). [ambiguous]: houve empate e valeu o critério "maiores = grandes".
     * [sizes]/[sizeConf]: tamanho de cada medida e probabilidade dele (já multiplicada por [conf]).
     * [mix]: proporção estimada de grande/médio/pequeno na foto.
     */
    class Fit(val unit: Float, val conf: Float, val ambiguous: Boolean, val sizes: List<String>, val sizeConf: FloatArray, val absolute: Boolean,
              val mix: FloatArray = FloatArray(LOGK.size) { 1f / LOGK.size }) {
        override fun toString() = "unidade=%.1f conf=%.2f%s%s".format(unit, conf, if (ambiguous) " ambígua" else "", if (absolute) " absoluta" else "")
    }

    /**
     * Tamanhos possíveis de cada medida pela forma ([Catalog.sizesOf]: o inventário do simulador, código B8): a ameia
     * (E) não existe no pequeno e o encaixe só no médio. Uma ameia que mede como pequena (dentes cobertos de ímãs) vai
     * para o tamanho possível mais perto e não puxa a escala da foto.
     */
    fun allowedFor(shapes: List<String>): Array<BooleanArray> = Array(shapes.size) { i -> val ok = Catalog.sizesOf(shapes[i]); BooleanArray(SIZES.size) { SIZES[it] in ok } }

    /** Log da densidade (sem constante) do resíduo [r] = ln(lado) − ln(unidade × razão). */
    private fun logDensity(r: Float): Float { val s = if (r < 0) sigmaLow else sigmaHigh; val z = r / s; return -.5f * z * z }

    /** Abaixo disto exp() em float dá exatamente 0 (exp(−110) ≈ 1,7e−48 < menor subnormal): a parcela nem é somada. */
    private const val EXP_ZERO = -110f
    private val UNIFORM = 1f / LOGK.size
    private val LOGK_MIN = LOGK.min()

    /**
     * Verossimilhança (log) de uma medida [l] = ln(lado) dada a unidade [lu] = ln(unidade) e a proporção [pi], só entre
     * os tamanhos [ok] (proporção renormalizada entre eles; null = os três).
     */
    private fun ll(l: Float, lu: Float, pi: FloatArray?, ok: BooleanArray? = null): Float {
        var p = 0f; var z = 0f
        for (k in LOGK.indices) {
            if (ok != null && !ok[k]) continue
            val w = pi?.get(k) ?: UNIFORM
            // p += w·exp(d): com exp(d) = 0 exato a soma não muda (p ≥ +0)
            z += w; val d = logDensity(l - lu - LOGK[k]); if (!(d < EXP_ZERO)) p += w * exp(d)
        }
        if (z <= 0f) return ln(OUTLIER / 1.5f)
        // densidade split-normal + piso de medida ruim, espalhada por ~1,5 em log
        return ln((1 - OUTLIER) * (p / z) / ((sigmaLow + sigmaHigh) / 2 * 2.5066f) + OUTLIER / 1.5f)
    }

    private fun total(logs: FloatArray, weights: FloatArray, idx: IntArray, lu: Float, absUnit: Float, pi: FloatArray? = null, allowed: Array<BooleanArray>? = null): Float {
        var s = 0f
        for (i in idx) s += weights[i] * ll(logs[i], lu, pi, allowed?.get(i))
        if (absUnit > 0) { val r = (lu - ln(absUnit)) / ABS_SIGMA; s -= .5f * r * r }
        return s
    }

    private fun range(logs: FloatArray, idx: IntArray, absUnit: Float): Pair<Float, Float> {
        var lo = logs[idx[0]]; var hi = logs[idx[0]]
        for (k in 1 until idx.size) { lo = kotlin.math.min(lo, logs[idx[k]]); hi = kotlin.math.max(hi, logs[idx[k]]) }
        hi -= LOGK_MIN
        if (absUnit > 0) { lo = minOf(lo, ln(absUnit) - .3f); hi = max(hi, ln(absUnit) + .3f) }
        return lo - .05f to hi + .05f
    }

    /**
     * Curva da verossimilhança em ln(unidade): (início, valores no passo [step]). [parallel]: cada ponto é independente
     * dos outros (mesmo valor em qualquer ordem), em paralelo ([Par]) — só na curva fina de [fit], uma por foto.
     */
    private fun curve(logs: FloatArray, weights: FloatArray, idx: IntArray, absUnit: Float, step: Float = STEP, allowed: Array<BooleanArray>? = null, parallel: Boolean = false): Pair<Float, FloatArray> {
        val (lo, hi) = range(logs, idx, absUnit)
        val m = ((hi - lo) / step).toInt() + 1
        if (parallel) return lo to Par.mapFloat(m) { total(logs, weights, idx, lo + it * step, absUnit, null, allowed) }
        return lo to FloatArray(m) { total(logs, weights, idx, lo + it * step, absUnit, null, allowed) }
    }

    /** Cotas de [llUpperBound] por (sigmaLow, sigmaHigh): uma por subconjunto de tamanhos (máscara de bits). */
    private val upperCache = java.util.concurrent.ConcurrentHashMap<Long, FloatArray>()

    /**
     * Cota superior de [ll] sem proporção (pi = null, como em [bestLogLikelihood]) para uma medida qualquer, em
     * qualquer unidade, só entre os tamanhos [ok]: com folga para os arredondamentos em float (a conta de verdade
     * nunca passa dela). Serve para descartar hipóteses de inclinação sem calcular a escala ([GroundEstimate]).
     * Máximo de Σₖ exp(logDensity(x − ln kₖ)) por uma grade fina em x mais a constante de Lipschitz × meio passo.
     */
    fun llUpperBound(ok: BooleanArray?): Float {
        var mask = 0; for (k in LOGK.indices) if (ok == null || ok[k]) mask = mask or (1 shl k)
        val sl = sigmaLow; val sh = sigmaHigh
        val key = (java.lang.Float.floatToIntBits(sl).toLong() shl 32) or (java.lang.Float.floatToIntBits(sh).toLong() and 0xFFFFFFFFL)
        val all = upperCache[key] ?: FloatArray(1 shl LOGK.size) { computeUpper(it, sl, sh) }.also { upperCache[key] = it }
        return all[mask]
    }

    private fun computeUpper(mask: Int, sigmaLow: Float, sigmaHigh: Float): Float {
        val ks = LOGK.indices.filter { mask and (1 shl it) != 0 }
        if (ks.isEmpty()) return ln(OUTLIER / 1.5f) + 1e-3f
        val sl = sigmaLow.toDouble(); val sh = sigmaHigh.toDouble()
        fun g(r: Double): Double { val s = if (r < 0) sl else sh; val z = r / s; return kotlin.math.exp(-.5 * z * z) }
        // fora de [min ln k, max ln k] todas as parcelas só caem: o máximo está dentro
        val lo = ks.minOf { LOGK[it].toDouble() }; val hi = ks.maxOf { LOGK[it].toDouble() }
        val step = 2.5e-4; var best = 0.0; var x = lo
        while (x <= hi + step) { var s = 0.0; for (k in ks) s += g(x - LOGK[k]); if (s > best) best = s; x += step }
        val lip = ks.size / (minOf(sl, sh) * kotlin.math.sqrt(kotlin.math.E))
        val sum = best + lip * step / 2
        // p/z = (Σ peso·g)/(Σ peso) com pesos iguais
        val pz = sum / ks.size * (1 + 1e-4)
        val d = (sigmaLow + sigmaHigh) / 2.0 * 2.5066
        return (kotlin.math.ln((1 - OUTLIER.toDouble()) * pz / d * (1 + 1e-4) + OUTLIER / 1.5) + 1e-3).toFloat()
    }

    /** Índices das medidas que entram na escala (lado e peso > 0), em ordem. */
    private fun usable(sides: FloatArray, weights: FloatArray): IntArray {
        var n = 0; for (i in sides.indices) if (sides[i] > 0 && weights[i] > 0) n++
        val out = IntArray(n); var k = 0; for (i in sides.indices) if (sides[i] > 0 && weights[i] > 0) out[k++] = i
        return out
    }

    /**
     * Melhor verossimilhança (log) das medidas com alguma unidade: quanto as medidas "cabem" nos 3 tamanhos.
     * Rápida (grade grossa + refino): é chamada para cada hipótese de inclinação do chão. [allowed]: como em [fit].
     */
    fun bestLogLikelihood(sides: FloatArray, weights: FloatArray, allowed: Array<BooleanArray>? = null): Float {
        val idx = usable(sides, weights); if (idx.isEmpty()) return 0f
        val logs = FloatArray(sides.size) { if (sides[it] > 0) ln(sides[it]) else 0f }
        val coarse = sigmaHigh / 2
        val (lo, c) = curve(logs, weights, idx, 0f, coarse, allowed)
        val j = c.indices.maxBy { c[it] }
        var best = c[j]
        for (q in -4..4) best = max(best, total(logs, weights, idx, lo + j * coarse + q * coarse / 5, 0f, null, allowed))
        return best
    }

    /**
     * [sides]: lados medidos (mesma unidade, já sem perspectiva); [weights]: confiança de cada medida (0 = não entra
     * na estimativa da escala, mas recebe tamanho). [absUnit] > 0: unidade esperada pela escala absoluta.
     * [allowed] (opcional, [allowedFor]): tamanhos possíveis de cada medida pela forma (inventário do simulador).
     */
    fun fit(sides: FloatArray, weights: FloatArray, absUnit: Float = 0f, allowed: Array<BooleanArray>? = null): Fit {
        val idx = usable(sides, weights)
        if (idx.isEmpty()) {
            val u = if (absUnit > 0) absUnit else sides.maxOrNull()?.takeIf { it > 0 } ?: 1f
            return assign(sides, u, if (absUnit > 0) .5f else 0f, ambiguous = absUnit <= 0, absolute = absUnit > 0, pi = null, allowed = allowed)
        }
        val logs = FloatArray(sides.size) { if (sides[it] > 0) ln(sides[it]) else 0f }
        val (lo, score) = curve(logs, weights, idx, absUnit, allowed = allowed, parallel = true)
        val m = score.size
        val best = score.max()
        // picos (máximos locais) quase empatados com o melhor, distintos se separados por mais de 0,1 em log
        // (metade da menor diferença entre tamanhos)
        val peaks = (0 until m).filter { j -> score[j] >= best - TIE_NATS && (j == 0 || score[j] >= score[j - 1]) && (j == m - 1 || score[j] >= score[j + 1]) }
        val distinct = ArrayList<Int>()
        for (j in peaks.sortedByDescending { score[it] }) if (distinct.none { abs(it - j) * STEP < .1f }) distinct += j
        val ambiguous = distinct.size > 1
        // critério do empate: menor unidade (as maiores peças são as grandes)
        val pick = if (ambiguous) distinct.min() else distinct.first()
        // confiança = massa da posterior (prior plano em log u) na bacia escolhida (±0,08 em log)
        var tot = 0.0; var mass = 0.0
        for (j in 0 until m) { val p = exp((score[j] - best).toDouble()); tot += p; if (abs(j - pick) * STEP <= .08f) mass += p }
        val conf = (mass / tot).toFloat()
        // proporções da foto (EM) e refino da unidade dentro da bacia escolhida
        var lu = lo + pick * STEP
        var pi: FloatArray? = null
        repeat(2) {
            if (classPrior) pi = mixture(logs, weights, idx, lu, allowed)
            var bl = lu; var bs = total(logs, weights, idx, lu, absUnit, pi, allowed)
            for (q in -12..12) { val c = lu + q * STEP / 2; val s = total(logs, weights, idx, c, absUnit, pi, allowed); if (s > bs) { bs = s; bl = c } }
            lu = bl
        }
        return assign(sides, exp(lu), conf, ambiguous, absUnit > 0, pi, allowed)
    }

    /** Proporção de cada tamanho dada a unidade (EM; Dirichlet(1) para nenhum tamanho sumir por uma peça só). */
    private fun mixture(logs: FloatArray, weights: FloatArray, idx: IntArray, lu: Float, allowed: Array<BooleanArray>? = null): FloatArray {
        var pi = FloatArray(LOGK.size) { 1f / LOGK.size }
        repeat(10) {
            val acc = FloatArray(LOGK.size) { 1f }; var tw = LOGK.size.toFloat()
            for (i in idx) {
                val ok = allowed?.get(i)
                val p = FloatArray(LOGK.size) { if (ok != null && !ok[it]) 0f else pi[it] * exp(logDensity(logs[i] - lu - LOGK[it])) }
                val s = p.sum(); if (s <= 1e-20f) continue
                for (k in p.indices) acc[k] += weights[i] * p[k] / s
                tw += weights[i]
            }
            pi = FloatArray(LOGK.size) { acc[it] / tw }
        }
        return pi
    }

    private fun assign(sides: FloatArray, unit: Float, conf: Float, ambiguous: Boolean, absolute: Boolean, pi: FloatArray?, allowed: Array<BooleanArray>? = null): Fit {
        val lu = ln(unit)
        val sizes = ArrayList<String>(sides.size); val sc = FloatArray(sides.size)
        for (i in sides.indices) {
            val ok = allowed?.get(i)?.takeIf { a -> a.any { it } }
            if (sides[i] <= 0) { sizes += SIZES[ok?.indexOfFirst { it }?.coerceAtLeast(0) ?: 0]; sc[i] = 0f; continue }
            val l = ln(sides[i])
            // posterior de cada tamanho possível (log, sem underflow para peças muito fora)
            val lp = FloatArray(LOGK.size) { if (ok != null && !ok[it]) Float.NEGATIVE_INFINITY else ln(pi?.get(it) ?: (1f / LOGK.size)) + logDensity(l - lu - LOGK[it]) }
            val k = lp.indices.maxBy { lp[it] }
            var s = 0f; for (v in lp) if (v > Float.NEGATIVE_INFINITY) s += exp(v - lp[k])
            sizes += SIZES[k]; sc[i] = conf / s
        }
        return Fit(unit, conf, ambiguous, sizes, sc, absolute, pi ?: FloatArray(LOGK.size) { 1f / LOGK.size })
    }
}
