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
    private fun f(t: Float) = if (t > .008856f) cbrt(t) else 7.787f * t + 16f / 116f

    /** sRGB → CIE Lab (D65). */
    fun lab(c: Int, out: FloatArray) {
        val r = lin[c shr 16 and 255]; val g = lin[c shr 8 and 255]; val b = lin[c and 255]
        val x = f((.4124f * r + .3576f * g + .1805f * b) / .95047f)
        val y = f(.2126f * r + .7152f * g + .0722f * b)
        val z = f((.0193f * r + .1192f * g + .9505f * b) / 1.08883f)
        out[0] = 116f * y - 16f; out[1] = 500f * (x - y); out[2] = 200f * (y - z)
    }

    /**
     * [minFrac]: região com pelo menos essa fração do quadro (em pixels de cor) entra.
     * [loose] (peças SOLTAS: foto do inventário, contagem; o detector de fotos usa via PhotoPieces.detect): junta os
     * pedaços da mesma peça antes do tamanho mínimo, aceita peça pequena entre [smallFrac] e [minFrac] quando tem a cor
     * das grandes e marca os ímãs. Desligado (padrão: scanner 3D, peças encostadas), a segmentação é a de antes.
     */
    fun segment(px: IntArray, w: Int, h: Int, minFrac: Float = .002f, maxFrac: Float = .35f, maxBlobs: Int = 80, fgThreshold: Float = 13f,
                loose: Boolean = false, smallFrac: Float = minFrac * .4f): Segmentation {
        val n = w * h
        val L = FloatArray(n); val A = FloatArray(n); val B = FloatArray(n); val t = FloatArray(3)
        for (i in 0 until n) { lab(px[i], t); L[i] = t[0]; A[i] = t[1]; B[i] = t[2] }

        // fundo: mediana de a/b do ANEL externo do quadro (a construção fica no meio e pode encher o quadro);
        // se o anel for colorido (toalha, parede), usa a mediana do quadro inteiro
        val step = max(1, n / 20000)
        val ring = ArrayList<Int>(n / step / 2)
        val mx = w / 10; val my = h / 10
        for (i in 0 until n step step) { val x = i % w; val y = i / w; if (x < mx || x >= w - mx || y < my || y >= h - my) ring += i }
        var bgA: Float; var bgB: Float; val bgL: Float
        run {
            val ra = ring.map { A[it] }.sorted(); val rb = ring.map { B[it] }.sorted()
            bgA = ra[ra.size / 2]; bgB = rb[rb.size / 2]
            bgL = ring.map { L[it] }.sorted()[ring.size / 2]
            if (!ringBackground || sqrt(bgA * bgA + bgB * bgB) > 15f) {
                val sa = (0 until n step step).map { A[it] }.sorted(); val sb = (0 until n step step).map { B[it] }.sorted()
                bgA = sa[sa.size / 2]; bgB = sb[sb.size / 2]
            }
        }
        // matiz (direção de a*, b*) e croma (saturação) de cada pixel
        val C = FloatArray(n); val Hc = FloatArray(n); val Hs = FloatArray(n)
        for (i in 0 until n) { val c = sqrt(A[i] * A[i] + B[i] * B[i]); C[i] = c; if (c > 1e-3f) { Hc[i] = A[i] / c; Hs[i] = B[i] / c } }
        val fg = BooleanArray(n) { val da = A[it] - bgA; val db = B[it] - bgB; sqrt(da * da + db * db) > fgThreshold && L[it] > 12f }
        // ímãs: mancha bem mais escura que o fundo (a sombra no chão escurece bem menos) e quase sem cor, do tamanho de um
        // ímã, colada na peça. É peça, não cor da peça. Nos dentes do E os ímãs cobrem o dente inteiro: sem eles o dente some
        // e o E vira L/porta/retângulo. Mancha escura grande (objeto escuro, fundo) fica fora. Só marca: quem junta à peça
        // é o detector de fotos (PhotoPieces), com peças soltas; no scanner a emenda escura entre peças encostadas não é ímã.
        val magnet = BooleanArray(n)
        val marked = loose && markMagnets
        if (marked) {
            val dark = BooleanArray(n) { val da = A[it] - bgA; val db = B[it] - bgB; L[it] < magnetDarkL * bgL && sqrt(da * da + db * db) < 22f }
            val seen = BooleanArray(n); val qq = IntArray(n); val maxCells = max(8, (n * magnetMaxFrac).toInt())
            for (s0 in 0 until n) {
                if (!dark[s0] || seen[s0]) continue
                var head = 0; var tail = 0; qq[tail++] = s0; seen[s0] = true; var touches = false
                while (head < tail) {
                    val i = qq[head++]; val x = i % w
                    for (k in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, i - w, i + w)) {
                        if (k < 0 || k >= n) continue
                        if (dark[k]) { if (!seen[k]) { seen[k] = true; qq[tail++] = k } } else if (fg[k]) touches = true
                    }
                }
                if (touches && tail <= maxCells) for (j in 0 until tail) magnet[qq[j]] = true
            }
        }

        // fechamento (raio ~0,5% da largura): pontes sobre ímãs, reflexos e o sulco escuro entre aro e painel;
        // esses pixels "ponte" não entram na média de cor. A emenda entre duas peças encostadas também vira ponte —
        // é a divisão por emenda (seamSplit) que separa depois, olhando os pixels escuros que não são primeiro plano.
        val r = max(1, w / 200)
        val closed = Mask(w, h, fg.copyOf()).let { m -> var x = m; repeat(r) { x = x.dilate() }; repeat(r) { x = x.erode() }; x }
        val bridge = BooleanArray(n) { closed.bits[it] && !fg[it] }

        val label = IntArray(n) { -1 }; val raw = IntArray(n) { -1 }; val q = IntArray(n)
        val blobs = ArrayList<Blob>()
        val regs = ArrayList<Reg>()
        var region = 0
        for (s in 0 until n) {
            if (raw[s] >= 0 || !fg[s]) continue
            var head = 0; var tail = 0; q[tail++] = s; raw[s] = region
            // matiz médio da região = soma dos vetores (cos, sin) pesada pelo croma (média circular) + média de a/b/L
            var sumHc = (Hc[s] * C[s]).toDouble(); var sumHs = (Hs[s] * C[s]).toDouble()
            var sumL = L[s].toDouble(); var sumA = A[s].toDouble(); var sumB = B[s].toDouble(); var cnt = 1
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            while (head < tail) {
                val i = q[head++]; val x = i % w; val y = i / w
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                val ml = sqrt(sumHc * sumHc + sumHs * sumHs).coerceAtLeast(1e-9); val mcx = (sumHc / ml).toFloat(); val mcy = (sumHs / ml).toFloat()
                val mA = (sumA / cnt).toFloat(); val mB = (sumB / cnt).toFloat(); val mL = (sumL / cnt).toFloat()
                for (k in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, i - w, i + w)) {
                    if (k < 0 || k >= n || raw[k] >= 0) continue
                    if (fg[k]) {
                        // Entra se (a) tem o mesmo MATIZ da região (22°; 40° quando pouco saturado) e do vizinho (18°):
                        // aro, painel translúcido e vincos da mesma peça; OU (b) está perto da média de a/b/L da região
                        // e do vizinho (degradê suave, como nas fotos reais em que o painel puxa para a cor do fundo).
                        // A peça do lado, de outra cor, falha nas duas.
                        // Pelo matiz só de um pixel saturado da própria região para outro saturado vizinho (aro → painel):
                        // vindo por uma ponte (ímã/sulco escuro) a cor tem que bater de verdade, senão a região atravessa
                        // o ímã da peça do lado e entra no painel dela (laranja × rosa nas fotos reais).
                        val byHue = hueGrowing && fg[i] && C[i] > 18f && C[k] > 14f && Hc[k] * mcx + Hs[k] * mcy >= HUE_22 && !(C[k] > 18f && Hc[k] * Hc[i] + Hs[k] * Hs[i] < HUE_18)
                        val da = A[k] - mA; val db = B[k] - mB
                        val byMean = sqrt(da * da + db * db) <= 16f && abs(L[k] - mL) <= 38f &&
                            !(fg[i] && run { val ea = A[k] - A[i]; val eb = B[k] - B[i]; sqrt(ea * ea + eb * eb) > 7f })
                        if (!byHue && !byMean) continue
                        sumHc += Hc[k] * C[k]; sumHs += Hs[k] * C[k]; sumL += L[k]; sumA += A[k]; sumB += B[k]; cnt++
                    } else if (!bridge[k] || !fg[i] && !bridge[i]) continue
                    raw[k] = region; q[tail++] = k
                }
            }
            region++
            regs += Reg(cnt, sumA / cnt - bgA, sumB / cnt - bgB, sumL / cnt, x0, y0, x1, y1)
        }
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
        fun find(x: Int): Int { var a = x; while (root[a] != a) { root[a] = root[root[a]]; a = root[a] }; return a }
        if (loose && joinParts && nr > 1) {
            val per = IntArray(nr); val contact = HashMap<Long, Int>()
            for (i in 0 until n) {
                val a = raw[i]; if (a < 0) continue
                val x = i % w
                for (k in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, i - w, i + w)) {
                    val b = if (k < 0 || k >= n) -1 else raw[k]
                    if (b == a) continue
                    per[a]++
                    if (b > a) contact.merge(a.toLong() * nr + b, 1, Int::plus)
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
                val fa = find(a); val fb = find(b); if (fa == fb) continue
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
        // grupos (região sozinha ou pedaços juntados)
        val members = HashMap<Int, MutableList<Int>>()
        for (k in 0 until nr) members.getOrPut(find(k)) { ArrayList(1) } += k
        class Grp(val g: Int, val ms: List<Int>, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val color: Int, val chroma: Double)
        val groups = members.entries.sortedBy { it.key }.map { (g, ms) ->
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0; var colorCnt = 0; var sa = 0.0; var sb = 0.0
            for (k in ms) { val rg = regs[k]; x0 = min(x0, rg.x0); y0 = min(y0, rg.y0); x1 = max(x1, rg.x1); y1 = max(y1, rg.y1); colorCnt += rg.cnt; sa += rg.da * rg.cnt; sb += rg.db * rg.cnt }
            Grp(g, ms, x0, y0, x1, y1, colorCnt, kotlin.math.hypot(sa / max(1, colorCnt), sb / max(1, colorCnt)))
        }
        // Peça pequena (pequeno a 45 cm: 250-450 px de 480×360, abaixo do mínimo antigo de 0,2% = 345 px): entre
        // [smallFrac] e [minFrac] só entra se for tão colorida quanto as peças grandes da foto (croma médio ≥ [smallChroma]
        // × mediana das grandes). Plástico incolor (a ameia transparente da foto real), reflexo, sombra colorida e borda
        // pálida que não se juntou à peça ficam com pouca cor; peça pequena de verdade tem a cor das grandes.
        val bigChroma = groups.filter { it.color >= n * minFrac && it.color <= n * maxFrac }.map { it.chroma }.sorted()
        val refChroma = if (bigChroma.isEmpty()) smallChromaAbs else bigChroma[bigChroma.size / 2]
        for (gr in groups) {
            val x0 = gr.x0; val y0 = gr.y0; val x1 = gr.x1; val y1 = gr.y1; val g = gr.g
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            val small = loose && gr.color < n * minFrac
            if (gr.color > n * maxFrac || bw < 4 || bh < 4) continue
            if (!loose && gr.color < n * minFrac) continue
            if (small && (gr.color < n * smallFrac || gr.chroma < smallChroma * refChroma)) continue
            val mask = Mask(bw, bh)
            if (gr.ms.size == 1) { for (yy in y0..y1) for (xx in x0..x1) if (raw[yy * w + xx] == g) mask[xx - x0, yy - y0] = true }
            else for (yy in y0..y1) for (xx in x0..x1) { val l = raw[yy * w + xx]; if (l >= 0 && find(l) == g) mask[xx - x0, yy - y0] = true }
            // duas peças da mesma cor encostadas (ou uma atrás da outra) viram uma região só: a emenda entre elas
            // é uma fileira de ímãs escuros + degrau de brilho entre as faces. Divide por essa linha.
            val parts = if (seamSplitting) seamSplit(mask, x0, y0, w, L, fg, 0, weak = false) else listOf(mask)
            val family = if (parts.size > 1) g else -1
            for (part in parts) {
                val b = blobFromPart(part, x0, y0, w, px, fg, blobs.size, family, (n * (if (small) smallFrac else minFrac)).toInt()) ?: continue
                for (yy in 0 until part.h) for (xx in 0 until part.w) if (part[xx, yy]) label[(y0 + yy) * w + x0 + xx] = b.id
                blobs += b
            }
        }
        mergeOverlapping(blobs, label, w)
        // entalhe que é outra peça na frente não é forma: reclassifica sabendo onde estão as outras peças
        if (occlusionReclassify) for (i in blobs.indices) {
            val b = blobs[i]; val f = b.fit ?: continue
            if (f.gaps.isEmpty()) continue
            val other = Mask(b.bw, b.bh); var any = false
            for (yy in 0 until b.bh) for (xx in 0 until b.bw) { val l = label[(b.minY + yy) * w + b.minX + xx]; if (l >= 0 && l != b.id) { other[xx, yy] = true; any = true } }
            if (any) blobs[i] = Blob(b.id, b.count, b.minX, b.minY, b.maxX, b.maxY, b.color, b.hue, b.mask, ShapeClassifier.classify(b.mask, other), b.family)
        }
        val kept = blobs.sortedByDescending { it.count }.take(maxBlobs)
        if (kept.size < blobs.size) { val ok = kept.map { it.id }.toSet(); for (i in 0 until n) if (label[i] >= 0 && label[i] !in ok) label[i] = -1 }
        return Segmentation(w, h, label, kept, L, fg, A, B, magnet = if (marked) magnet else null, bgLab = if (loose) floatArrayOf(bgL, bgA, bgB) else null, rgb = px)
    }

    /** Blob a partir de uma parte ([part] no recorte que começa em [x0],[y0] da imagem de largura [w]). */
    private fun blobFromPart(part: Mask, x0: Int, y0: Int, w: Int, px: IntArray, fg: BooleanArray, id: Int, family: Int, minCount: Int): Blob? {
        var px0 = part.w; var py0 = part.h; var px1 = 0; var py1 = 0; var cnt = 0; var pr = 0L; var pg = 0L; var pb = 0L
        for (yy in 0 until part.h) for (xx in 0 until part.w) if (part[xx, yy]) {
            px0 = min(px0, xx); px1 = max(px1, xx); py0 = min(py0, yy); py1 = max(py1, yy)
            val i = (y0 + yy) * w + x0 + xx
            if (fg[i]) { pr += px[i] shr 16 and 255; pg += px[i] shr 8 and 255; pb += px[i] and 255; cnt++ }
        }
        if (cnt < minCount || cnt == 0 || px1 - px0 + 1 < 4 || py1 - py0 + 1 < 4) return null
        val pm = Mask(px1 - px0 + 1, py1 - py0 + 1)
        for (yy in py0..py1) for (xx in px0..px1) if (part[xx, yy]) pm[xx - px0, yy - py0] = true
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
                for (k in label.indices) if (label[k] == b.id) label[k] = a.id
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

    /** Preenche buracos pequenos (riscos, reflexos) sem fechar os vãos de verdade (janela, porta). */
    private fun fillSmallHoles(m: Mask, maxCells: Int) {
        val inv = Mask(m.w, m.h, BooleanArray(m.w * m.h) { !m.bits[it] })
        for (c in inv.components(minCells = 1)) {
            if (c.count() > maxCells) continue
            var border = false
            for (y in 0 until c.h) { if (c[0, y] || c[c.w - 1, y]) { border = true; break } }
            if (!border) for (x in 0 until c.w) if (c[x, 0] || c[x, c.h - 1]) { border = true; break }
            if (!border) for (i in c.bits.indices) if (c.bits[i]) m.bits[i] = true
        }
    }
}
