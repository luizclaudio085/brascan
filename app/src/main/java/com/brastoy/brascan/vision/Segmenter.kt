package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Região colorida encontrada na imagem (coordenadas em pixels da imagem segmentada). */
class Blob(
    val id: Int,
    val count: Int,
    val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
    val color: Int,
    val hue: Float,
    val mask: Mask, // recorte do tamanho da caixa
    val fit: ShapeFit?,
    /** Partes de uma mesma região dividida por emenda (ímãs enfileirados): nunca são juntadas de novo. */
    val family: Int = -1,
) {
    val hueBin get() = Segmenter.hueBin(hue)
    val bw get() = maxX - minX + 1
    val bh get() = maxY - minY + 1
}

class Segmentation(val w: Int, val h: Int, val labels: IntArray, val blobs: List<Blob>,
                   /** Luminância e primeiro plano por pixel: o detector usa para tentar dividir uma região que não fecha com molde nenhum. */
                   val L: FloatArray? = null, val fg: BooleanArray? = null,
                   /** a*, b* (Lab) por pixel: o detector confere o matiz do painel translúcido que fica fora da região do aro. */
                   val A: FloatArray? = null, val B: FloatArray? = null,
                   /** Pixels de ímã (mancha escura pequena colada numa peça), fora das regiões; o detector de fotos junta à peça. */
                   val magnet: BooleanArray? = null,
                   /** Cor do fundo (L, a*, b*). */
                   val bgLab: FloatArray? = null,
                   /** Pixels de entrada (ARGB): o detector de fotos compara a cor de um furo com a da peça e mede o croma dos ímãs. */
                   val rgb: IntArray? = null)

/** Região crescida, antes de juntar pedaços: pixels de cor, a* e b* médios menos os do fundo, L* médio e caixa. */
internal class Reg(val cnt: Int, val da: Double, val db: Double, val l: Double, val x0: Int, val y0: Int, val x1: Int, val y1: Int)

/**
 * Segmentação em Kotlin puro (igual no celular e nos testes), feita para fotos reais:
 * 1. aprende a cor do fundo (mediana de a*, b* no espaço Lab — luz e sombra mudam L, quase não mudam a/b);
 * 2. primeiro plano = pixels cuja cor se afasta do fundo;
 * 3. cresce regiões pelo MATIZ (ângulo de a*, b*), não pela distância de cor: numa peça real o aro é saturado,
 *    o painel translúcido deixa o fundo aparecer (menos saturado) e os vincos em X são mais claros — tudo isso é
 *    a MESMA peça e tem o mesmo matiz; comparar a distância em a/b partia a peça em aro + painel + 4 triângulos
 *    (daí os falsos "triângulos" e tamanhos "médio" na cena realista);
 * 4. fechamento morfológico pequeno: ímãs escuros e reflexos dentro da peça não quebram a região.
 * Com peças SOLTAS (segment(loose = true): foto do inventário, contagem; o scanner 3D não usa):
 * 5. junta os pedaços da mesma peça (aro, painel, vincos) que o crescimento separou — mesmo matiz, croma e brilho
 *    parecidos, borda em comum — e só então aplica o tamanho mínimo (peça pequena era só pedaços abaixo dele);
 * 6. peça pequena (abaixo do mínimo antigo) entra se tiver a cor das peças grandes da foto;
 * 7. marca os ímãs (manchas escuras coladas numa peça) para o detector de fotos juntar à peça.
 *
 * Velocidade (a mesma saída, bit a bit, da conta direta; golden em DetectorGoldenTest): Lab por tabelas por canal e
 * raiz cúbica por polinômio (Math.cbrt só perto do meio entre dois floats; as 2^24 cores conferidas em
 * SegmenterLabTest), numa passada só com o primeiro plano; fundo pelas amostras e mediana por seleção pela raiz;
 * limiares de distância comparados ao quadrado (corte exato, achado uma vez por busca binária na própria sqrt);
 * fechamento morfológico em bits (64 pixels por Long); crescimento, junção e ímãs só pelos pixels marcados (bits /
 * lista), vizinhos sem alocar arrays e buffers do quadro reaproveitados.
 */
object Segmenter {
    const val BINS = 12
    @JvmField var debugSeams = false
    @JvmField var hueGrowing = false
    /** Divisão por emenda já na segmentação: desligada — na cena sintética partia peças inteiras; fica só a divisão fraca pedida pelo detector. */
    @JvmField var seamSplitting = false
    @JvmField var occlusionReclassify = true
    @JvmField var ringBackground = true
    private val HUE_18 = kotlin.math.cos(Math.toRadians(18.0)).toFloat()
    private val HUE_22 = kotlin.math.cos(Math.toRadians(22.0)).toFloat()
    /**
     * Junta regiões vizinhas que são pedaços da mesma peça (aro, painel, vincos): dividem ≥ [joinContact] da borda da
     * menor, matiz (visto do fundo) a ≤ [joinHueDeg], croma ≥ [joinMinChroma] do da outra e brilho a ≤ [joinMaxDL].
     */
    @JvmField var joinParts = true
    @JvmField var joinContact = .3f
    @JvmField var joinHueDeg = 14f
    @JvmField var joinMinChroma = .5
    @JvmField var joinMaxDL = 20.0
    /** Peça pequena (entre smallFrac e minFrac do quadro) só entra com croma ≥ [smallChroma] × mediana das grandes. */
    @JvmField var smallChroma = .65
    /** Croma de referência quando não há peça grande no quadro. */
    @JvmField var smallChromaAbs = 25.0
    /** Marca ímãs (Segmentation.magnet): mancha com L < [magnetDarkL] × L do fundo, quase sem cor, até [magnetMaxFrac] do quadro. */
    @JvmField var markMagnets = true
    @JvmField var magnetDarkL = .5f
    @JvmField var magnetMaxFrac = .0012f
    fun hueBin(h: Float) = ((h / 30f + .5f).toInt()) % BINS

    fun hsv(c: Int, out: FloatArray) {
        val r = (c shr 16 and 255) / 255f; val g = (c shr 8 and 255) / 255f; val b = (c and 255) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        var hh = when { d < 1e-6f -> 0f; mx == r -> 60f * (((g - b) / d) % 6f); mx == g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
        if (hh < 0) hh += 360f
        out[0] = hh; out[1] = if (mx < 1e-6f) 0f else d / mx; out[2] = mx
    }

    private val lin = FloatArray(256) { val c = it / 255f; if (c <= .04045f) c / 12.92f else ((c + .055f) / 1.055f).pow(2.4f) }

    /**
     * sRGB → CIE Lab (D65): x = f((0,4124·r + 0,3576·g + 0,1805·b) / 0,95047), y = f(0,2126·r + 0,7152·g + 0,0722·b),
     * z = f((0,0193·r + 0,1192·g + 0,9505·b) / 1,08883) com r, g, b lineares e f(t) = ∛t (t > 0,008856) ou
     * 7,787·t + 16/116; L* = 116·y − 16, a* = 500·(x − y), b* = 200·(y − z), tudo em float. Calculado por tabelas e
     * pela raiz cúbica rápida ([cbrtF]): os MESMOS floats da conta direta com kotlin.math.cbrt nas 2^24 cores
     * (SegmenterLabTest).
     */
    fun lab(c: Int, out: FloatArray) {
        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
        val x = fLab((XR[r] + XG[g] + XB[b]) / .95047f)
        val y = fLab(YR[r] + YG[g] + YB[b])
        val z = fLab((ZR[r] + ZG[g] + ZB[b]) / 1.08883f)
        out[0] = 116f * y - 16f; out[1] = 500f * (x - y); out[2] = 200f * (y - z)
    }

    // produto de cada canal linear pelo coeficiente da matriz: o mesmo float de 0,4124f * lin[r] etc., somado na mesma ordem
    private val XR = FloatArray(256) { .4124f * lin[it] }; private val XG = FloatArray(256) { .3576f * lin[it] }; private val XB = FloatArray(256) { .1805f * lin[it] }
    private val YR = FloatArray(256) { .2126f * lin[it] }; private val YG = FloatArray(256) { .7152f * lin[it] }; private val YB = FloatArray(256) { .0722f * lin[it] }
    private val ZR = FloatArray(256) { .0193f * lin[it] }; private val ZG = FloatArray(256) { .1192f * lin[it] }; private val ZB = FloatArray(256) { .9505f * lin[it] }
    /**
     * ∛ no meio de cada uma das 512 faixas c de m ∈ [1, 2) e os termos de Taylor: ∛(c + d) = a0 + d·(a1 + d·(a2 + d·a3)),
     * com |d| ≤ 1/1024 o erro relativo é < 2^-44,5 (próximo termo: 10/243 · (d/c)^4).
     */
    private val CB_POLY = DoubleArray(512 * 4).also { t ->
        for (k in 0 until 512) {
            val c = 1.0 + (k + .5) / 512; val a0 = Math.cbrt(c)
            t[4 * k] = a0; t[4 * k + 1] = a0 / (3 * c); t[4 * k + 2] = -a0 / (9 * c * c); t[4 * k + 3] = 5 * a0 / (81 * c * c * c)
        }
    }
    /** ∛(2^e) pelo expoente com viés do float (1..254). */
    private val CB_EXP = DoubleArray(256) { if (it in 1..254) Math.cbrt(Math.scalb(1.0, it - 127)) else 0.0 }
    private const val TWO_M23 = 1.0 / (1 shl 23)

    /**
     * ∛t, o MESMO float que kotlin.math.cbrt(t) (= Math.cbrt em double arredondado para float), sem divisão: polinômio
     * da faixa × ∛(2^e), erro relativo < 2^-44,4 (≤ ~370 ulps do double; Math.cbrt erra < 1 ulp). O float é decidido
     * pelos 29 bits de baixo do double: a ≥ 2^13 ulps do meio entre dois floats (bits = 2^28), o Math.cbrt arredonda
     * para o mesmo float; mais perto disso (1 em 2^15 chamadas), usa o próprio Math.cbrt.
     */
    private fun cbrtF(t: Float): Float {
        val bits = java.lang.Float.floatToRawIntBits(t)
        if (bits < 0x00800000 || bits >= 0x7F800000) return cbrt(t) // ≤ 0, subnormal, infinito, NaN
        val mant = bits and 0x7FFFFF
        val j = (mant ushr 14) shl 2
        val d = ((mant and 0x3FFF) - 0x2000) * TWO_M23 // m − c, exato
        val y = (CB_POLY[j] + d * (CB_POLY[j + 1] + d * (CB_POLY[j + 2] + d * CB_POLY[j + 3]))) * CB_EXP[bits ushr 23]
        val low = java.lang.Double.doubleToRawLongBits(y) and 0x1FFFFFFFL
        return if (abs(low - 0x10000000L) > 0x2000L) y.toFloat() else cbrt(t)
    }

    private fun fLab(t: Float) = if (t > .008856f) cbrtF(t) else 7.787f * t + 16f / 116f

    /** [lab] da cor [c] em L[i], A[i], B[i] (a mesma conta). */
    private fun labInto(c: Int, L: FloatArray, A: FloatArray, B: FloatArray, i: Int) {
        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
        val x = fLab((XR[r] + XG[g] + XB[b]) / .95047f)
        val y = fLab(YR[r] + YG[g] + YB[b])
        val z = fLab((ZR[r] + ZG[g] + ZB[b]) / 1.08883f)
        L[i] = 116f * y - 16f; A[i] = 500f * (x - y); B[i] = 200f * (y - z)
    }

    /** [lab] de px[0 until n] em L, A, B, pelo caminho da segmentação (o teste das 2^24 cores confere este também). */
    internal fun labPixels(px: IntArray, n: Int, L: FloatArray, A: FloatArray, B: FloatArray) { for (i in 0 until n) labInto(px[i], L, A, B, i) }

    /**
     * Maior float s ≥ 0 com [pred] verdadeiro, para [pred] que vale num começo de [0, ∞] e depois não (−1 se nem em 0;
     * +∞ se até no infinito). Com pred = "sqrt(s) ≤ t" vira um corte exato: sqrt(s) ≤ t ⇔ s ≤ corte (sqrt é monótona).
     */
    private inline fun lastWhere(pred: (Float) -> Boolean): Float {
        if (!pred(0f)) return -1f
        if (pred(Float.POSITIVE_INFINITY)) return Float.POSITIVE_INFINITY
        var lo = 0; var hi = 0x7F800000
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (pred(java.lang.Float.intBitsToFloat(mid))) lo = mid else hi = mid }
        return java.lang.Float.intBitsToFloat(lo)
    }

    /**
     * k-ésimo menor de v[0 until size] na ordem de Float.compareTo (= sorted()[k]), sem ordenar: seleção pela raiz em
     * 3 passadas de histograma (11 + 11 + 10 bits) de uma chave sem sinal com a mesma ordem.
     */
    internal fun select(v: FloatArray, size: Int, k: Int): Float {
        // chave: positivos (+0, +∞, NaN canônico) com o bit de cima ligado; negativos invertidos (−0 logo abaixo de +0)
        val a = IntArray(size) { val b = java.lang.Float.floatToIntBits(v[it]); if (b < 0) b.inv() else b or Int.MIN_VALUE }
        val hist = IntArray(2048)
        var prefix = 0; var prefixMask = 0; var rank = k
        for (pass in 0 until 3) {
            val shift = if (pass == 0) 21 else if (pass == 1) 10 else 0
            val m = if (pass == 2) 1023 else 2047
            java.util.Arrays.fill(hist, 0)
            for (j in 0 until size) { val u = a[j]; if ((u and prefixMask) == prefix) hist[(u ushr shift) and m]++ }
            var bin = 0
            while (rank >= hist[bin]) { rank -= hist[bin]; bin++ }
            prefix = prefix or (bin shl shift); prefixMask = prefixMask or (m shl shift)
        }
        return java.lang.Float.intBitsToFloat(if (prefix < 0) prefix and Int.MAX_VALUE else prefix.inv())
    }

    /** Buffers do quadro que não saem do segment(): um só, reaproveitado (chamada concorrente usa buffers próprios). */
    private class Scratch {
        var raw = IntArray(0); var q = IntArray(0); var qx = IntArray(0); var darkIdx = IntArray(0)
        var bridge = BooleanArray(0); var dark = BooleanArray(0); var seen = BooleanArray(0)
        /** Máscaras em bits: [b0] = primeiro plano; [b1], [b2] = o fechamento. */
        var b0 = LongArray(0); var b1 = LongArray(0); var b2 = LongArray(0)
        fun ensure(n: Int, words: Int) {
            if (raw.size < n) { raw = IntArray(n); q = IntArray(n); qx = IntArray(n); darkIdx = IntArray(n); bridge = BooleanArray(n); dark = BooleanArray(n); seen = BooleanArray(n) }
            if (b0.size < words) { b0 = LongArray(words); b1 = LongArray(words); b2 = LongArray(words) }
        }
    }
    private const val SHARED_MAX_PX = 1_000_000L
    private val shared = Scratch()
    private val sharedBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * [minFrac]: região com pelo menos essa fração do quadro (em pixels de cor) entra.
     * [loose] (peças SOLTAS: foto do inventário, contagem; o detector de fotos usa via PhotoPieces.detect): junta os
     * pedaços da mesma peça antes do tamanho mínimo, aceita peça pequena entre [smallFrac] e [minFrac] quando tem a cor
     * das grandes e marca os ímãs. Desligado (padrão: scanner 3D, peças encostadas), a segmentação é a de antes.
     */
    fun segment(px: IntArray, w: Int, h: Int, minFrac: Float = .002f, maxFrac: Float = .35f, maxBlobs: Int = 80, fgThreshold: Float = 13f,
                loose: Boolean = false, smallFrac: Float = minFrac * .4f): Segmentation {
        // quadro grande (foto em resolução cheia) usa buffers descartáveis: o compartilhado fica preso enquanto o app vive,
        // então só serve quadros de até SHARED_MAX_PX (cobre a câmera e a foto de 640 px)
        val sc = if (w.toLong() * h <= SHARED_MAX_PX && sharedBusy.compareAndSet(false, true)) shared else Scratch()
        try {
            return segment(px, w, h, minFrac, maxFrac, maxBlobs, fgThreshold, loose, smallFrac, sc)
        } finally {
            if (sc === shared) sharedBusy.set(false)
        }
    }

    private fun segment(px: IntArray, w: Int, h: Int, minFrac: Float, maxFrac: Float, maxBlobs: Int, fgThreshold: Float,
                        loose: Boolean, smallFrac: Float, sc: Scratch): Segmentation {
        val n = w * h
        val words = (w + 63) ushr 6 // linha da máscara em bits
        sc.ensure(n, words * h)
        // fundo pelas amostras (Lab só delas); depois uma passada só: Lab, primeiro plano e candidatos a ímã de cada pixel
        val step = max(1, n / 20000)
        val samples = (n + step - 1) / step
        val sl = FloatArray(samples); val sa = FloatArray(samples); val sb = FloatArray(samples)
        val bg = background(px, w, h, step, sl, sa, sb)
        val bgL = bg[0]; val bgA = bg[1]; val bgB = bg[2]
        val L = FloatArray(n); val A = FloatArray(n); val B = FloatArray(n)
        // primeiro plano: pixels cuja cor (a*, b*) se afasta do fundo e não são pretos
        val fg = BooleanArray(n)
        val marked = loose && markMagnets
        // candidatos a ímã (L < magnetDarkL × L do fundo, sqrt(da² + db²) < 22 ⇔ da² + db² ≤ corte) na mesma passada
        val darkCount = labForeground(px, L, A, B, step, sl, sa, sb, bgA, bgB, lastWhere { !(sqrt(it) > fgThreshold) }, fg, sc.b0, w, h, words,
            if (marked) sc.dark else null, sc.darkIdx, magnetDarkL * bgL, lastWhere { sqrt(it) < 22f })
        // matiz (direção de a*, b*) e croma (saturação) de cada pixel: só o crescimento pelo matiz (hueGrowing) usa
        val C: FloatArray?; val Hc: FloatArray?; val Hs: FloatArray?
        if (hueGrowing) {
            C = FloatArray(n); Hc = FloatArray(n); Hs = FloatArray(n)
            for (i in 0 until n) { val c = sqrt(A[i] * A[i] + B[i] * B[i]); C[i] = c; if (c > 1e-3f) { Hc[i] = A[i] / c; Hs[i] = B[i] / c } }
        } else { C = null; Hc = null; Hs = null }
        // ímãs: mancha bem mais escura que o fundo (a sombra no chão escurece bem menos) e quase sem cor, do tamanho de um
        // ímã, colada na peça. É peça, não cor da peça. Nos dentes do E os ímãs cobrem o dente inteiro: sem eles o dente some
        // e o E vira L/porta/retângulo. Mancha escura grande (objeto escuro, fundo) fica fora. Só marca: quem junta à peça
        // é o detector de fotos (PhotoPieces), com peças soltas; no scanner a emenda escura entre peças encostadas não é ímã.
        val magnet = if (marked) BooleanArray(n).also { if (darkCount > 0) findMagnets(it, fg, darkCount, w, n, sc) } else null
        // fechamento (raio ~0,5% da largura): pontes sobre ímãs, reflexos e o sulco escuro entre aro e painel;
        // esses pixels "ponte" não entram na média de cor. A emenda entre duas peças encostadas também vira ponte —
        // é a divisão por emenda (seamSplit) que separa depois, olhando os pixels escuros que não são primeiro plano.
        val bridge = sc.bridge
        val closed = closing(bridge, max(1, w / 200), w, h, words, sc)
        val raw = sc.raw
        val regs = grow(L, A, B, fg, bridge, raw, sc.q, sc.qx, sc.b0, w, h, words, bgA, bgB, C, Hc, Hs)
        // Pedaços da mesma peça: o crescimento por cor separa aro (mais claro), painel (translúcido), vincos e a borda
        // borrada quando o degrau de cor entre vizinhos passa de 7 — numa peça pequena tudo isso tem 1-3 px e nenhum
        // pedaço sozinho chega ao tamanho mínimo (a peça sumia); num quadrado médio os vincos em X partiam o painel em 4
        // "triângulos". São do mesmo plástico: mesmo MATIZ visto a partir do fundo, croma e brilho parecidos, e se abraçam
        // (o aro contorna o painel, os vincos dividem o painel). Junta vizinhas assim que dividem ≥ 30% da borda da menor.
        // Duas peças encostadas de cores diferentes têm matiz diferente (≥ 25° entre as 7 cores); lado a lado só dividem
        // um lado (≤ 25% da borda). A borda pálida (mistura com o fundo, croma < metade) não entra: com ela as peças
        // grandes das fotos reais cresciam e a escala de tamanho (calibrada sem ela) mudava.
        val nr = regs.size
        val root = IntArray(nr) { it }
        if (loose && joinParts && nr > 1) joinPieces(raw, regs, root, sc.b0, closed, w, h, words)
        val label = IntArray(n); java.util.Arrays.fill(label, -1)
        val blobs = ArrayList<Blob>()
        groupBlobs(blobs, label, raw, regs, root, px, L, fg, w, h, minFrac, maxFrac, loose, smallFrac)
        val created = blobs.size
        mergeOverlapping(blobs, label, w)
        // entalhe que é outra peça na frente não é forma: reclassifica sabendo onde estão as outras peças
        if (occlusionReclassify) for (i in blobs.indices) {
            val b = blobs[i]; val f = b.fit ?: continue
            if (f.gaps.isEmpty()) continue
            val other = Mask(b.bw, b.bh); var outside = false
            for (yy in 0 until b.bh) for (xx in 0 until b.bw) { val l = label[(b.minY + yy) * w + b.minX + xx]; if (l >= 0 && l != b.id) { other[xx, yy] = true; if (!b.mask[xx, yy]) outside = true } }
            // a outra peça só muda a forma se cair num vão do casco (ShapeClassifier.gaps: fora da máscara); toda dentro
            // da máscara (furo pequeno fechado), a reclassificação daria o mesmo ShapeFit
            if (outside) blobs[i] = Blob(b.id, b.count, b.minX, b.minY, b.maxX, b.maxY, b.color, b.hue, b.mask, ShapeClassifier.classify(b.mask, other), b.family)
        }
        val kept = blobs.sortedByDescending { it.count }.take(maxBlobs)
        if (kept.size < blobs.size) {
            val ok = BooleanArray(created); for (b in kept) ok[b.id] = true
            for (i in 0 until n) { val l = label[i]; if (l >= 0 && !ok[l]) label[i] = -1 }
        }
        return Segmentation(w, h, label, kept, L, fg, A, B, magnet = magnet, bgLab = if (loose) floatArrayOf(bgL, bgA, bgB) else null, rgb = px)
    }

    /**
     * Fundo (L, a*, b*): mediana de a/b do ANEL externo do quadro (a construção fica no meio e pode encher o quadro);
     * se o anel for colorido (toalha, parede), a/b vêm da mediana do quadro inteiro. Uma amostra a cada [step] pixels:
     * o Lab delas fica em [sl], [sa], [sb] (a passada principal reaproveita).
     */
    private fun background(px: IntArray, w: Int, h: Int, step: Int, sl: FloatArray, sa: FloatArray, sb: FloatArray): FloatArray {
        val n = w * h
        val mx = w / 10; val my = h / 10
        val samples = sl.size
        val ra = FloatArray(samples); val rb = FloatArray(samples); val rl = FloatArray(samples); var ring = 0
        var i = 0; var k = 0; var x = 0; var y = 0 // x = i % w, y = i / w
        while (i < n) {
            labInto(px[i], sl, sa, sb, k)
            if (x < mx || x >= w - mx || y < my || y >= h - my) { ra[ring] = sa[k]; rb[ring] = sb[k]; rl[ring] = sl[k]; ring++ }
            k++; i += step; x += step; while (x >= w) { x -= w; y++ }
        }
        var bgA = select(ra, ring, ring / 2); var bgB = select(rb, ring, ring / 2)
        val bgL = select(rl, ring, ring / 2)
        if (!ringBackground || sqrt(bgA * bgA + bgB * bgB) > 15f) { bgA = select(sa, k, k / 2); bgB = select(sb, k, k / 2) }
        return floatArrayOf(bgL, bgA, bgB)
    }

    /**
     * Lab de cada pixel em L, A, B (as amostras do fundo, a cada [step], já vêm em [sl], [sa], [sb]) e o primeiro plano:
     * sqrt(da² + db²) > limiar ⇔ da² + db² > [cut] (corte exato) e L* > 12, também empacotado em [bits] (linha y em
     * [words] Longs a partir de y·words) para o fechamento. Com [dark], marca os candidatos a ímã (L < [darkL],
     * da² + db² ≤ [darkCut]), põe os índices deles em [darkIdx] (em ordem) e devolve quantos.
     */
    private fun labForeground(px: IntArray, L: FloatArray, A: FloatArray, B: FloatArray, step: Int, sl: FloatArray, sa: FloatArray, sb: FloatArray,
                              bgA: Float, bgB: Float, cut: Float, fg: BooleanArray, bits: LongArray,
                              w: Int, h: Int, words: Int, dark: BooleanArray?, darkIdx: IntArray, darkL: Float, darkCut: Float): Int {
        var nDark = 0
        var next = 0; var k = 0 // próxima amostra do fundo (já tem o Lab)
        for (y in 0 until h) {
            val row = y * w; val wrow = y * words
            var word = 0L
            for (x in 0 until w) {
                val i = row + x
                if (i == next) { L[i] = sl[k]; A[i] = sa[k]; B[i] = sb[k]; k++; next += step } else labInto(px[i], L, A, B, i)
                val da = A[i] - bgA; val db = B[i] - bgB; val s = da * da + db * db; val l = L[i]
                val v = s > cut && l > 12f
                fg[i] = v
                if (v) word = word or (1L shl x)
                if ((x and 63) == 63) { bits[wrow + (x ushr 6)] = word; word = 0L }
                if (dark != null) { val d = l < darkL && s <= darkCut; dark[i] = d; if (d) darkIdx[nDark++] = i }
            }
            if ((w and 63) != 0) bits[wrow + words - 1] = word
        }
        return nDark
    }

    /**
     * Ímãs em [magnet]: mancha escura (sc.dark; os [nDark] pixels dela em sc.darkIdx, em ordem) de até magnetMaxFrac do
     * quadro colada no primeiro plano.
     */
    private fun findMagnets(magnet: BooleanArray, fg: BooleanArray, nDark: Int, w: Int, n: Int, sc: Scratch) {
        val dark = sc.dark; val seen = sc.seen; val q = sc.q; val qx = sc.qx; val idx = sc.darkIdx
        for (j in 0 until nDark) seen[idx[j]] = false // só pixel escuro é marcado
        val maxCells = max(8, (n * magnetMaxFrac).toInt())
        for (jj in 0 until nDark) {
            val s0 = idx[jj]
            if (seen[s0]) continue
            var head = 0; var tail = 0; q[tail] = s0; qx[tail] = s0 % w; tail++; seen[s0] = true; var touches = false
            while (head < tail) {
                val i = q[head]; val x = qx[head]; head++
                if (x > 0) { val k = i - 1; if (dark[k]) { if (!seen[k]) { seen[k] = true; q[tail] = k; qx[tail] = x - 1; tail++ } } else if (fg[k]) touches = true }
                if (x < w - 1) { val k = i + 1; if (dark[k]) { if (!seen[k]) { seen[k] = true; q[tail] = k; qx[tail] = x + 1; tail++ } } else if (fg[k]) touches = true }
                if (i >= w) { val k = i - w; if (dark[k]) { if (!seen[k]) { seen[k] = true; q[tail] = k; qx[tail] = x; tail++ } } else if (fg[k]) touches = true }
                if (i + w < n) { val k = i + w; if (dark[k]) { if (!seen[k]) { seen[k] = true; q[tail] = k; qx[tail] = x; tail++ } } else if (fg[k]) touches = true }
            }
            if (touches && tail <= maxCells) for (j in 0 until tail) magnet[q[j]] = true
        }
    }

    /**
     * Fechamento ([r]× Mask.dilate e depois [r]× Mask.erode, fora do quadro = vazio) do primeiro plano em bits (sc.b0,
     * que fica intacto); [bridge] = fechado e não primeiro plano. Devolve o fechado em bits (sc.b1 ou sc.b2).
     */
    private fun closing(bridge: BooleanArray, r: Int, w: Int, h: Int, words: Int, sc: Scratch): LongArray {
        val fgBits = sc.b0
        dilateBits(fgBits, sc.b1, w, h, words)
        var cur = sc.b1; var tmp = sc.b2
        repeat(r - 1) { dilateBits(cur, tmp, w, h, words); val s = cur; cur = tmp; tmp = s }
        repeat(r) { erodeBits(cur, tmp, h, words); val s = cur; cur = tmp; tmp = s }
        java.util.Arrays.fill(bridge, 0, w * h, false)
        for (y in 0 until h) {
            val row = y * w; val wrow = y * words
            for (j in 0 until words) {
                var v = cur[wrow + j] and fgBits[wrow + j].inv()
                while (v != 0L) { bridge[row + (j shl 6) + java.lang.Long.numberOfTrailingZeros(v)] = true; v = v and (v - 1) }
            }
        }
        return cur
    }

    /**
     * Cresce as regiões ([raw]: região de cada pixel, −1 = nenhuma) a partir de cada pixel de primeiro plano ainda
     * livre, em ordem de varredura (os bits de [fgBits]), por busca em largura (vizinhos esquerda, direita, cima, baixo).
     */
    private fun grow(L: FloatArray, A: FloatArray, B: FloatArray, fg: BooleanArray, bridge: BooleanArray, raw: IntArray, q: IntArray, qx: IntArray, fgBits: LongArray,
                     w: Int, h: Int, words: Int, bgA: Float, bgB: Float, C: FloatArray?, Hc: FloatArray?, Hs: FloatArray?): ArrayList<Reg> {
        val n = w * h
        java.util.Arrays.fill(raw, 0, n, -1)
        val regs = ArrayList<Reg>()
        // limiares de cor ao quadrado (cortes exatos das comparações com sqrt)
        val cut16 = lastWhere { sqrt(it) <= 16f }
        val cut7 = lastWhere { !(sqrt(it) > 7f) }
        val hue = hueGrowing
        var region = 0
        for (sy in 0 until h) for (j in 0 until words) {
            var v = fgBits[sy * words + j]
            while (v != 0L) {
                val sx = (j shl 6) + java.lang.Long.numberOfTrailingZeros(v); v = v and (v - 1)
                val s = sy * w + sx
                if (raw[s] >= 0) continue
                var head = 0; var tail = 0; q[tail] = s; qx[tail] = sx; tail++; raw[s] = region
                // matiz médio da região = soma dos vetores (cos, sin) pesada pelo croma (média circular) + média de a/b/L
                var sumHc = 0.0; var sumHs = 0.0
                if (hue) { sumHc = (Hc!![s] * C!![s]).toDouble(); sumHs = (Hs!![s] * C[s]).toDouble() }
                var sumL = L[s].toDouble(); var sumA = A[s].toDouble(); var sumB = B[s].toDouble(); var cnt = 1
                var x0 = w; var x1 = 0; var i0 = n; var i1 = -1
                // médias da região: só mudam quando entra pixel de cor (cnt muda junto com as somas)
                var mA = 0f; var mB = 0f; var mL = 0f; var meanCnt = 0
                while (head < tail) {
                    val i = q[head]; val x = qx[head]; head++
                    if (x < x0) x0 = x; if (x > x1) x1 = x; if (i < i0) i0 = i; if (i > i1) i1 = i
                    var mcx = 0f; var mcy = 0f
                    if (hue) { val ml = sqrt(sumHc * sumHc + sumHs * sumHs).coerceAtLeast(1e-9); mcx = (sumHc / ml).toFloat(); mcy = (sumHs / ml).toFloat() }
                    if (cnt != meanCnt) { mA = (sumA / cnt).toFloat(); mB = (sumB / cnt).toFloat(); mL = (sumL / cnt).toFloat(); meanCnt = cnt }
                    val fi = fg[i]; val bi = bridge[i]; val ai = A[i]; val bbi = B[i]
                    for (d in 0 until 4) {
                        val k: Int; val xk: Int
                        if (d == 0) { if (x == 0) continue; k = i - 1; xk = x - 1 }
                        else if (d == 1) { if (x == w - 1) continue; k = i + 1; xk = x + 1 }
                        else if (d == 2) { k = i - w; if (k < 0) continue; xk = x }
                        else { k = i + w; if (k >= n) continue; xk = x }
                        if (raw[k] >= 0) continue
                        if (fg[k]) {
                            // Entra se (a) tem o mesmo MATIZ da região (22°; 40° quando pouco saturado) e do vizinho (18°):
                            // aro, painel translúcido e vincos da mesma peça; OU (b) está perto da média de a/b/L da região
                            // e do vizinho (degradê suave, como nas fotos reais em que o painel puxa para a cor do fundo).
                            // A peça do lado, de outra cor, falha nas duas.
                            // Pelo matiz só de um pixel saturado da própria região para outro saturado vizinho (aro → painel):
                            // vindo por uma ponte (ímã/sulco escuro) a cor tem que bater de verdade, senão a região atravessa
                            // o ímã da peça do lado e entra no painel dela (laranja × rosa nas fotos reais).
                            val byHue = hue && fi && C!![i] > 18f && C[k] > 14f && Hc!![k] * mcx + Hs!![k] * mcy >= HUE_22 && !(C[k] > 18f && Hc[k] * Hc[i] + Hs[k] * Hs[i] < HUE_18)
                            val ak = A[k]; val bk = B[k]
                            if (!byHue) {
                                val da = ak - mA; val db = bk - mB
                                if (da * da + db * db > cut16 || abs(L[k] - mL) > 38f) continue
                                if (fi) { val ea = ak - ai; val eb = bk - bbi; if (ea * ea + eb * eb > cut7) continue }
                            }
                            if (hue) { sumHc += Hc!![k] * C!![k]; sumHs += Hs!![k] * C[k] }
                            sumL += L[k]; sumA += ak; sumB += bk; cnt++
                        } else if (!bridge[k] || !fi && !bi) continue
                        raw[k] = region; q[tail] = k; qx[tail] = xk; tail++
                    }
                }
                region++
                regs += Reg(cnt, sumA / cnt - bgA, sumB / cnt - bgB, sumL / cnt, x0, i0 / w, x1, i1 / w)
            }
        }
        return regs
    }

    private fun find(root: IntArray, x: Int): Int { var a = x; while (root[a] != a) { root[a] = root[root[a]]; a = root[a] }; return a }

    /**
     * Junta (em [root]) as regiões vizinhas que são pedaços da mesma peça. Pixels de região estão no primeiro plano ou
     * no fechado ([fgBits] | [closed]): só esses são visitados, em ordem de varredura.
     */
    private fun joinPieces(raw: IntArray, regs: List<Reg>, root: IntArray, fgBits: LongArray, closed: LongArray, w: Int, h: Int, words: Int) {
        val nr = regs.size
        val per = IntArray(nr); val contact = HashMap<Long, Int>()
        // vizinhos na ordem esquerda, direita, cima, baixo (fora do quadro conta como borda); a ordem de inserção no
        // mapa decide o desempate dos pares com a mesma fração de contato
        for (y in 0 until h) for (j in 0 until words) {
            val row = y * w
            var v = fgBits[y * words + j] or closed[y * words + j]
            while (v != 0L) {
                val x = (j shl 6) + java.lang.Long.numberOfTrailingZeros(v); v = v and (v - 1)
                val i = row + x
                val a = raw[i]; if (a < 0) continue
                var b = if (x > 0) raw[i - 1] else -1
                if (b != a) { per[a]++; if (b > a) contact.merge(a.toLong() * nr + b, 1, Int::plus) }
                b = if (x < w - 1) raw[i + 1] else -1
                if (b != a) { per[a]++; if (b > a) contact.merge(a.toLong() * nr + b, 1, Int::plus) }
                b = if (y > 0) raw[i - w] else -1
                if (b != a) { per[a]++; if (b > a) contact.merge(a.toLong() * nr + b, 1, Int::plus) }
                b = if (y < h - 1) raw[i + w] else -1
                if (b != a) { per[a]++; if (b > a) contact.merge(a.toLong() * nr + b, 1, Int::plus) }
            }
        }
        val cosJ = kotlin.math.cos(Math.toRadians(joinHueDeg.toDouble()))
        // compara os GRUPOS (média pelos pixels de cor), não só o par: uma borda borrada com matiz intermediário
        // não pode encadear duas peças (vidro da janela → borda → moldura)
        val gn = DoubleArray(nr) { regs[it].cnt.toDouble() }; val ga = DoubleArray(nr) { regs[it].da * gn[it] }
        val gb = DoubleArray(nr) { regs[it].db * gn[it] }; val gl = DoubleArray(nr) { regs[it].l * gn[it] }
        val pairs = contact.entries.mapNotNull { (key, c) ->
            val a = (key / nr).toInt(); val b = (key % nr).toInt(); val frac = c.toFloat() / min(per[a], per[b])
            if (frac >= joinContact) Triple(a, b, frac) else null
        }.sortedByDescending { it.third }
        for ((a, b) in pairs) {
            val fa = find(root, a); val fb = find(root, b); if (fa == fb) continue
            val ax = ga[fa] / gn[fa]; val ay = gb[fa] / gn[fa]; val bx = ga[fb] / gn[fb]; val by = gb[fb] / gn[fb]
            val la = kotlin.math.hypot(ax, ay); val lb = kotlin.math.hypot(bx, by)
            if (la < 10.0 || lb < 10.0 || (ax * bx + ay * by) / (la * lb) < cosJ) continue // sem cor / outro matiz
            if (min(la, lb) < joinMinChroma * max(la, lb)) continue // borda pálida
            // aro, painel e vincos têm brilho parecido (≤ 10); peça clara encaixada numa escura não
            if (abs(gl[fa] / gn[fa] - gl[fb] / gn[fb]) > joinMaxDL) continue
            val keep = min(fa, fb); val gone = max(fa, fb)
            root[gone] = keep; gn[keep] += gn[gone]; ga[keep] += ga[gone]; gb[keep] += gb[gone]; gl[keep] += gl[gone]
        }
    }

    /**
     * Grupos (região sozinha ou pedaços juntados) → blobs em [blobs] e rótulos em [label], na ordem da raiz do grupo
     * (= menor região dele); caixa, pixels de cor e croma somados na ordem das regiões.
     */
    private fun groupBlobs(blobs: ArrayList<Blob>, label: IntArray, raw: IntArray, regs: List<Reg>, root: IntArray, px: IntArray, L: FloatArray, fg: BooleanArray,
                           w: Int, h: Int, minFrac: Float, maxFrac: Float, loose: Boolean, smallFrac: Float) {
        val n = w * h; val nr = regs.size
        val gSize = IntArray(nr); val gx0 = IntArray(nr); val gy0 = IntArray(nr); val gx1 = IntArray(nr); val gy1 = IntArray(nr)
        val gColor = IntArray(nr); val gsa = DoubleArray(nr); val gsb = DoubleArray(nr)
        for (k in 0 until nr) {
            val g = find(root, k); val rg = regs[k]
            if (gSize[g] == 0) { gx0[g] = w; gy0[g] = h; gx1[g] = 0; gy1[g] = 0 }
            gSize[g]++
            gx0[g] = min(gx0[g], rg.x0); gy0[g] = min(gy0[g], rg.y0); gx1[g] = max(gx1[g], rg.x1); gy1[g] = max(gy1[g], rg.y1)
            gColor[g] += rg.cnt; gsa[g] += rg.da * rg.cnt; gsb[g] += rg.db * rg.cnt
        }
        val gChroma = DoubleArray(nr)
        for (g in 0 until nr) if (gSize[g] > 0) gChroma[g] = kotlin.math.hypot(gsa[g] / max(1, gColor[g]), gsb[g] / max(1, gColor[g]))
        // Peça pequena (pequeno a 45 cm: 250-450 px de 480×360, abaixo do mínimo antigo de 0,2% = 345 px): entre
        // [smallFrac] e [minFrac] só entra se for tão colorida quanto as peças grandes da foto (croma médio ≥ [smallChroma]
        // × mediana das grandes). Plástico incolor (a ameia transparente da foto real), reflexo, sombra colorida e borda
        // pálida que não se juntou à peça ficam com pouca cor; peça pequena de verdade tem a cor das grandes.
        var nBig = 0
        for (g in 0 until nr) if (gSize[g] > 0 && gColor[g] >= n * minFrac && gColor[g] <= n * maxFrac) nBig++
        val bigChroma = DoubleArray(nBig)
        run { var k = 0; for (g in 0 until nr) if (gSize[g] > 0 && gColor[g] >= n * minFrac && gColor[g] <= n * maxFrac) bigChroma[k++] = gChroma[g] }
        bigChroma.sort()
        val refChroma = if (nBig == 0) smallChromaAbs else bigChroma[nBig / 2]
        for (g in 0 until nr) {
            if (gSize[g] == 0) continue
            val x0 = gx0[g]; val y0 = gy0[g]; val x1 = gx1[g]; val y1 = gy1[g]; val color = gColor[g]
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            val small = loose && color < n * minFrac
            if (color > n * maxFrac || bw < 4 || bh < 4) continue
            if (!loose && color < n * minFrac) continue
            if (small && (color < n * smallFrac || gChroma[g] < smallChroma * refChroma)) continue
            val mask = Mask(bw, bh); val mb = mask.bits
            if (gSize[g] == 1) { for (yy in y0..y1) { val row = yy * w; val o = (yy - y0) * bw - x0; for (xx in x0..x1) if (raw[row + xx] == g) mb[o + xx] = true } }
            else for (yy in y0..y1) { val row = yy * w; val o = (yy - y0) * bw - x0; for (xx in x0..x1) { val l = raw[row + xx]; if (l >= 0 && find(root, l) == g) mb[o + xx] = true } }
            // duas peças da mesma cor encostadas (ou uma atrás da outra) viram uma região só: a emenda entre elas
            // é uma fileira de ímãs escuros + degrau de brilho entre as faces. Divide por essa linha.
            val parts = if (seamSplitting) seamSplit(mask, x0, y0, w, L, fg, 0, weak = false) else listOf(mask)
            val family = if (parts.size > 1) g else -1
            for (part in parts) {
                val b = blobFromPart(part, x0, y0, w, px, fg, blobs.size, family, (n * (if (small) smallFrac else minFrac)).toInt()) ?: continue
                val pb = part.bits; val pw = part.w
                for (yy in 0 until part.h) { val o = yy * pw; val row = (y0 + yy) * w + x0; for (xx in 0 until pw) if (pb[o + xx]) label[row + xx] = b.id }
                blobs += b
            }
        }
    }

    /** Mask.dilate() em bits: cada linha em [words] Longs (bit x da linha = pixel x); bits além de [w] ficam 0. */
    private fun dilateBits(src: LongArray, dst: LongArray, w: Int, h: Int, words: Int) {
        val tailMask = if ((w and 63) == 0) -1L else (1L shl (w and 63)) - 1
        for (y in 0 until h) {
            val o = y * words
            for (j in 0 until words) {
                val c = src[o + j]
                var v = c or (c shl 1) or (c ushr 1)
                if (j > 0) v = v or (src[o + j - 1] ushr 63)
                if (j < words - 1) v = v or (src[o + j + 1] shl 63)
                if (y > 0) v = v or src[o - words + j]
                if (y < h - 1) v = v or src[o + words + j]
                dst[o + j] = v
            }
            dst[o + words - 1] = dst[o + words - 1] and tailMask
        }
    }

    /** Mask.erode() em bits (fora do quadro = vazio: a borda sempre sai vazia). */
    private fun erodeBits(src: LongArray, dst: LongArray, h: Int, words: Int) {
        for (y in 0 until h) {
            val o = y * words
            for (j in 0 until words) {
                val c = src[o + j]
                val left = (c shl 1) or (if (j > 0) src[o + j - 1] ushr 63 else 0L)
                val right = (c ushr 1) or (if (j < words - 1) src[o + j + 1] shl 63 else 0L)
                var v = c and left and right
                v = if (y > 0) v and src[o - words + j] else 0L
                v = if (y < h - 1) v and src[o + words + j] else 0L
                dst[o + j] = v
            }
        }
    }

    /** Blob a partir de uma parte ([part] no recorte que começa em [x0],[y0] da imagem de largura [w]). */
    private fun blobFromPart(part: Mask, x0: Int, y0: Int, w: Int, px: IntArray, fg: BooleanArray, id: Int, family: Int, minCount: Int): Blob? {
        var px0 = part.w; var py0 = part.h; var px1 = 0; var py1 = 0; var cnt = 0; var pr = 0L; var pg = 0L; var pb = 0L
        val bits = part.bits; val pw = part.w
        for (yy in 0 until part.h) {
            val o = yy * pw; val row = (y0 + yy) * w + x0
            for (xx in 0 until pw) if (bits[o + xx]) {
                if (xx < px0) px0 = xx; if (xx > px1) px1 = xx; if (yy < py0) py0 = yy; if (yy > py1) py1 = yy
                val i = row + xx
                if (fg[i]) { val c = px[i]; pr += c shr 16 and 255; pg += c shr 8 and 255; pb += c and 255; cnt++ }
            }
        }
        if (cnt < minCount || cnt == 0 || px1 - px0 + 1 < 4 || py1 - py0 + 1 < 4) return null
        val pm = Mask(px1 - px0 + 1, py1 - py0 + 1); val mb = pm.bits; val mw = pm.w
        for (yy in py0..py1) { val o = yy * pw; val oo = (yy - py0) * mw - px0; for (xx in px0..px1) if (bits[o + xx]) mb[oo + xx] = true }
        fillSmallHoles(pm, max(4, cnt / 60))
        val color = (0xFF shl 24) or ((pr / cnt).toInt() shl 16) or ((pg / cnt).toInt() shl 8) or (pb / cnt).toInt()
        val t = FloatArray(3); hsv(color, t)
        return Blob(id, cnt, x0 + px0, y0 + py0, x0 + px1, y0 + py1, color, t[0], pm, ShapeClassifier.classify(pm), family)
    }

    /**
     * Divisão FRACA, pedida pelo detector quando a região não fecha com molde nenhum (erro de reprojeção alto):
     * aceita a melhor linha de pixels escuros mesmo com um ímã só e sem contraste. Devolve as partes como
     * blobs (ids negativos: não existem no mapa de rótulos) ou lista vazia se não há linha boa.
     */
    fun weakSplit(b: Blob, seg: Segmentation, px: IntArray): List<Blob> {
        val L = seg.L ?: return emptyList(); val fg = seg.fg ?: return emptyList()
        // peça com furo de verdade (moldura, janela, porta) nunca é dividida: o furo não é emenda
        val inv = Mask(b.mask.w, b.mask.h, BooleanArray(b.mask.w * b.mask.h) { !b.mask.bits[it] })
        for (c in inv.components(minCells = max(4, b.count / 40))) {
            var border = false
            for (y in 0 until c.h) if (c[0, y] || c[c.w - 1, y]) { border = true; break }
            if (!border) for (x in 0 until c.w) if (c[x, 0] || c[x, c.h - 1]) { border = true; break }
            if (!border) return emptyList()
        }
        val parts = seamSplit(b.mask, b.minX, b.minY, seg.w, L, fg, 1, weak = true)
        if (parts.size < 2) return emptyList()
        return parts.mapIndexedNotNull { i, part -> blobFromPart(part, b.minX, b.minY, seg.w, px, fg, -1 - i, b.id, 150) }
    }

    /**
     * Uma peça com luz desigual às vezes vira duas regiões sobrepostas da mesma cor: junta se as caixas
     * se sobrepõem bastante e o tom é parecido.
     */
    private fun mergeOverlapping(blobs: ArrayList<Blob>, label: IntArray, w: Int) {
        val t = FloatArray(3); val u = FloatArray(3)
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in blobs.indices) for (j in i + 1 until blobs.size) {
                val a = blobs[i]; val b = blobs[j]
                if (a.family >= 0 && a.family == b.family) continue // separadas de propósito pela emenda
                val ix = min(a.maxX, b.maxX) - max(a.minX, b.minX) + 1; val iy = min(a.maxY, b.maxY) - max(a.minY, b.minY) + 1
                if (ix <= 0 || iy <= 0) continue
                val small = min(a.bw * a.bh, b.bw * b.bh)
                if (ix * iy < small * .6f) continue
                lab(a.color, t); lab(b.color, u)
                val inner = if (a.bw * a.bh < b.bw * b.bh) a else b; val outer = if (inner === a) b else a
                val dab = sqrt((t[1] - u[1]) * (t[1] - u[1]) + (t[2] - u[2]) * (t[2] - u[2]))
                val ha = FloatArray(3).also { hsv(a.color, it) }[0]; val hb = FloatArray(3).also { hsv(b.color, it) }[0]
                val dh = min(abs(ha - hb), 360 - abs(ha - hb))
                // pedaço pequeno (tira de ímã, borda escura) da mesma peça: mesmo tom, bem menor → junta
                val fragment = inner.count < outer.count * .2f && dh < 20f
                // encaixe/vidro dentro de uma moldura: fica separado (vira peça "encaixe" ou vidro da janela)
                if (!fragment && (dab > 22f || inner.count > outer.count * .2f && (dab > 10f || abs(t[0] - u[0]) > 18f))) continue
                val x0 = min(a.minX, b.minX); val y0 = min(a.minY, b.minY); val x1 = max(a.maxX, b.maxX); val y1 = max(a.maxY, b.maxY)
                val m = Mask(x1 - x0 + 1, y1 - y0 + 1)
                for (src in listOf(a, b)) for (yy in 0 until src.bh) for (xx in 0 until src.bw) if (src.mask[xx, yy]) m[src.minX - x0 + xx, src.minY - y0 + yy] = true
                fillSmallHoles(m, max(4, (a.count + b.count) / 60))
                val cnt = a.count + b.count
                fun ch(c: Int, sh: Int) = (c shr sh and 255)
                val color = (0xFF shl 24) or (((ch(a.color, 16) * a.count + ch(b.color, 16) * b.count) / cnt) shl 16) or (((ch(a.color, 8) * a.count + ch(b.color, 8) * b.count) / cnt) shl 8) or ((ch(a.color, 0) * a.count + ch(b.color, 0) * b.count) / cnt)
                hsv(color, t)
                val merged = Blob(a.id, cnt, x0, y0, x1, y1, color, t[0], m, ShapeClassifier.classify(m))
                // os rótulos de b só existem dentro da caixa dele
                for (yy in b.minY..b.maxY) { val row = yy * w; for (xx in b.minX..b.maxX) if (label[row + xx] == b.id) label[row + xx] = a.id }
                blobs[i] = merged; blobs.removeAt(j)
                changed = true; break@loop
            }
        }
    }

    /**
     * Divide uma região por EMENDAS: linha reta onde se enfileiram pixels escuros dentro do casco (ímãs das duas
     * peças, borda) e/ou o brilho muda de um lado para o outro (faces com orientação diferente). Só divide se os
     * dois lados forem grandes (≥ 20%): a fileira de ímãs da própria peça fica na borda e não corta nada.
     * Transformada de Hough em 36 direções; recursivo até 2 níveis (três peças em fila).
     */
    private fun seamSplit(mask: Mask, x0: Int, y0: Int, w: Int, L: FloatArray, fg: BooleanArray, depth: Int, weak: Boolean): List<Mask> {
        val total = mask.count()
        if (depth > 1 || total < 400) return listOf(mask)
        val hull = Geo2.hull(mask.edgePoints()); if (hull.size < 3) return listOf(mask)
        val mw = mask.w; val mh = mask.h
        // casco preenchido
        val inHull = Mask(mw, mh)
        for (y in 0 until mh) for (x in 0 until mw) {
            val cx = x + .5f; val cy = y + .5f; var c = false
            for (i in hull.indices) { val a = hull[i]; val b = hull[(i + 1) % hull.size]
                if ((a.y > cy) != (b.y > cy) && cx < (b.x - a.x) * (cy - a.y) / (b.y - a.y) + a.x) c = !c }
            inHull[x, y] = c
        }
        // brilho médio da região (só pixels de primeiro plano)
        var sumL = 0.0; var nL = 0
        for (y in 0 until mh) for (x in 0 until mw) if (mask[x, y]) { val i = (y0 + y) * w + x0 + x; if (fg[i]) { sumL += L[i]; nL++ } }
        if (nL == 0) return listOf(mask)
        val meanL = (sumL / nL).toFloat()
        // pixels escuros dentro do casco que não são primeiro plano (ímãs, borda, sombra da emenda) — mesmo os que o
        // fechamento colou na região
        val dark = ArrayList<Int>()
        for (y in 0 until mh) for (x in 0 until mw) if (inHull[x, y]) { val i = (y0 + y) * w + x0 + x; if (!fg[i] && L[i] < meanL - 15f) dark += y * mw + x }
        if (debugSeams && total > 1500) runCatching { // imagem de depuração: máscara cinza, escuros vermelhos, casco azul
            val f = java.io.File("build/realista/seam-${mw}x$mh.ppm"); f.parentFile.mkdirs()
            f.outputStream().buffered().use { o -> o.write(("P6 " + mw + " " + mh + " 255 ").toByteArray())
                for (y in 0 until mh) for (x in 0 until mw) { val d = (y * mw + x) in dark.toSet(); val c = when { d -> intArrayOf(255, 40, 40); mask[x, y] -> intArrayOf(170, 170, 170); inHull[x, y] -> intArrayOf(60, 60, 220); else -> intArrayOf(0, 0, 0) }; o.write(c[0]); o.write(c[1]); o.write(c[2]) } }
        }
        if (dark.size < 12) return listOf(mask)
        val diag = sqrt((mw * mw + mh * mh).toDouble()).toFloat()
        var best: FloatArray? = null; var bestScore = 0f
        for (deg in 0 until 180 step 5) {
            val th = Math.toRadians(deg.toDouble()); val nx = kotlin.math.cos(th).toFloat(); val ny = kotlin.math.sin(th).toFloat()
            val bins = IntArray((diag + 3).toInt() + 1)
            for (d in dark) { val r = (d % mw + .5f) * nx + (d / mw + .5f) * ny + diag / 2; val b = r.toInt(); if (b in bins.indices) bins[b]++ }
            for (b in 1 until bins.size - 1) {
                val support = bins[b - 1] + bins[b] + bins[b + 1]; if (support < 10) continue
                val rho = b + .5f - diag / 2
                // comprimento da corda (pixels do casco a ≤ 0,5 px da linha), lados e contraste de brilho
                var chord = 0; var sideA = 0; var sideB = 0; var lA = 0.0; var nA = 0; var lB = 0.0; var nB = 0
                for (y in 0 until mh) for (x in 0 until mw) {
                    if (!inHull[x, y]) continue
                    val sd = (x + .5f) * nx + (y + .5f) * ny - rho
                    if (abs(sd) <= .5f) chord++
                    if (!mask[x, y]) continue
                    if (sd > 1.5f) sideA++ else if (sd < -1.5f) sideB++
                    val i = (y0 + y) * w + x0 + x
                    if (fg[i] && abs(sd) in 2.5f..7f) { if (sd > 0) { lA += L[i]; nA++ } else { lB += L[i]; nB++ } }
                }
                if (chord < 8 || min(sideA, sideB) < total * .2f) continue
                // os escuros na linha têm que formar a fileira de ímãs de uma emenda: ≥ 2 trechos com vão pequeno
                // entre eles (os 2 ímãs de uma borda), ocupando ≥ 35% da corda. Uma diagonal que pega os ímãs de
                // duas bordas de uma peça só dá 2 trechos nas pontas, com um vão enorme no meio → não divide.
                val ts = ArrayList<Float>()
                for (d in dark) { val xx = d % mw + .5f; val yy = d / mw + .5f; if (abs(xx * nx + yy * ny - rho) <= 1.5f) ts += -xx * ny + yy * nx }
                ts.sort()
                var runs = 1; var maxGap = 0f
                for (q in 1 until ts.size) { val g = ts[q] - ts[q - 1]; if (g > 4f) { runs++; maxGap = max(maxGap, g) } }
                val span = if (ts.isEmpty()) 0f else ts.last() - ts.first()
                val darkFrac = support.toFloat() / chord
                val contrast = if (nA > 10 && nB > 10) abs(lA / nA - lB / nB).toFloat() else 0f
                if (debugSeams && darkFrac > .12f) println("  emenda? ${mw}x$mh deg=$deg rho=%.0f chord=$chord sup=$support frac=%.2f lados=$sideA/$sideB runs=$runs gap=%.0f span=%.0f contraste=%.0f".format(rho, darkFrac, maxGap, span, contrast))
                val seamLike = runs >= 2 && maxGap <= span * .45f && span >= chord * .35f
                // (uma fileira escura contínua NÃO vale: é a borda interna de uma moldura/janela/porta)
                // um ímã só visível (o outro escondido) + faces com brilho diferente dos dois lados
                val oneBarStep = runs == 1 && span >= chord * .15f && contrast > 10f
                // fraca (molde não fechou): basta a fileira escura ter tamanho de ímã (≥ 12% da corda)
                val weakOk = weak && span >= chord * .12f && darkFrac >= .2f
                if (!seamLike && !oneBarStep && !weakOk) continue
                val score = darkFrac + (if (contrast > 12f) .25f else 0f)
                if (weakOk || darkFrac >= .3f || darkFrac >= .15f && contrast > 10f) if (score > bestScore) { bestScore = score; best = floatArrayOf(nx, ny, rho) }
            }
        }
        val line = best ?: return listOf(mask)
        if (debugSeams) println("  DIVIDIU ${mw}x$mh em (%.2f, %.2f, %.1f) score=%.2f".format(line[0], line[1], line[2], bestScore))
        val a = Mask(mw, mh); val b = Mask(mw, mh)
        for (y in 0 until mh) for (x in 0 until mw) if (mask[x, y]) { val sd = (x + .5f) * line[0] + (y + .5f) * line[1] - line[2]; if (sd > 0) a[x, y] = true else b[x, y] = true }
        // cada lado pode ainda ser duas peças (fila de três)
        return seamSplit(a, x0, y0, w, L, fg, depth + 1, weak) + seamSplit(b, x0, y0, w, L, fg, depth + 1, weak)
    }

    /**
     * Preenche buracos pequenos (riscos, reflexos) sem fechar os vãos de verdade (janela, porta): componente (4-vizinhos)
     * do vazio com até [maxCells] células que não encosta na borda do recorte. Uma busca por componente, sem a máscara
     * inteira por componente de Mask.components.
     */
    private fun fillSmallHoles(m: Mask, maxCells: Int) {
        val w = m.w; val h = m.h; val bits = m.bits; val n = w * h
        val seen = BooleanArray(n); val q = IntArray(n)
        for (s in 0 until n) {
            if (bits[s] || seen[s]) continue
            var head = 0; var tail = 0; q[tail++] = s; seen[s] = true; var border = false
            while (head < tail) {
                val i = q[head++]; val y = i / w; val x = i - y * w
                if (x == 0 || x == w - 1 || y == 0 || y == h - 1) border = true
                if (x > 0) { val k = i - 1; if (!bits[k] && !seen[k]) { seen[k] = true; q[tail++] = k } }
                if (x < w - 1) { val k = i + 1; if (!bits[k] && !seen[k]) { seen[k] = true; q[tail++] = k } }
                if (y > 0) { val k = i - w; if (!bits[k] && !seen[k]) { seen[k] = true; q[tail++] = k } }
                if (y < h - 1) { val k = i + w; if (!bits[k] && !seen[k]) { seen[k] = true; q[tail++] = k } }
            }
            if (!border && tail <= maxCells) for (j in 0 until tail) bits[q[j]] = true
        }
    }
}
