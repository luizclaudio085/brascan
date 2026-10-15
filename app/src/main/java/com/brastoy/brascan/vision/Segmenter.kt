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
) {
    val hueBin get() = Segmenter.hueBin(hue)
    val bw get() = maxX - minX + 1
    val bh get() = maxY - minY + 1
}

class Segmentation(val w: Int, val h: Int, val labels: IntArray, val blobs: List<Blob>)

/**
 * Segmentação em Kotlin puro (igual no celular e nos testes), feita para fotos reais:
 * 1. aprende a cor do fundo (mediana de a*, b* no espaço Lab — luz e sombra mudam L, quase não mudam a/b);
 * 2. primeiro plano = pixels cuja cor se afasta do fundo;
 * 3. cresce regiões comparando com a MÉDIA da região (não com o vizinho, que deixava a cor "vazar" em degradês);
 * 4. fechamento morfológico: ímãs escuros e reflexos dentro da peça não quebram a região.
 */
object Segmenter {
    const val BINS = 12
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

    fun segment(px: IntArray, w: Int, h: Int, minFrac: Float = .002f, maxFrac: Float = .35f, maxBlobs: Int = 80, fgThreshold: Float = 13f): Segmentation {
        val n = w * h
        val L = FloatArray(n); val A = FloatArray(n); val B = FloatArray(n); val t = FloatArray(3)
        for (i in 0 until n) { lab(px[i], t); L[i] = t[0]; A[i] = t[1]; B[i] = t[2] }

        // fundo: mediana de a/b (o fundo ocupa a maior parte da foto)
        val step = max(1, n / 20000)
        val sa = (0 until n step step).map { A[it] }.sorted(); val sb = (0 until n step step).map { B[it] }.sorted()
        val bgA = sa[sa.size / 2]; val bgB = sb[sb.size / 2]
        val fg = BooleanArray(n) { val da = A[it] - bgA; val db = B[it] - bgB; sqrt(da * da + db * db) > fgThreshold && L[it] > 12f }

        // fechamento (raio ~0,5% da largura): pontes sobre ímãs/reflexos; esses pixels "ponte" não entram na média de cor
        val r = max(1, w / 200)
        val closed = Mask(w, h, fg.copyOf()).let { m -> var x = m; repeat(r) { x = x.dilate() }; repeat(r) { x = x.erode() }; x }
        val bridge = BooleanArray(n) { closed.bits[it] && !fg[it] }

        val label = IntArray(n) { -1 }; val raw = IntArray(n) { -1 }; val q = IntArray(n)
        val blobs = ArrayList<Blob>()
        var region = 0
        for (s in 0 until n) {
            if (raw[s] >= 0 || !fg[s]) continue
            var head = 0; var tail = 0; q[tail++] = s; raw[s] = region
            var sumL = L[s].toDouble(); var sumA = A[s].toDouble(); var sumB = B[s].toDouble(); var cnt = 1
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0; var rr = 0L; var gg = 0L; var bb = 0L; var colorCnt = 0
            while (head < tail) {
                val i = q[head++]; val x = i % w; val y = i / w
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (fg[i]) { rr += px[i] shr 16 and 255; gg += px[i] shr 8 and 255; bb += px[i] and 255; colorCnt++ }
                val mA = (sumA / cnt).toFloat(); val mB = (sumB / cnt).toFloat(); val mL = (sumL / cnt).toFloat()
                for (k in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, i - w, i + w)) {
                    if (k < 0 || k >= n || raw[k] >= 0) continue
                    if (fg[k]) {
                        // perto da média da região E do vizinho: degradês suaves passam, troca de peça não
                        val da = A[k] - mA; val db = B[k] - mB
                        if (sqrt(da * da + db * db) > 16f || abs(L[k] - mL) > 38f) continue
                        if (fg[i]) { val ea = A[k] - A[i]; val eb = B[k] - B[i]; if (sqrt(ea * ea + eb * eb) > 7f) continue }
                        sumL += L[k]; sumA += A[k]; sumB += B[k]; cnt++
                    } else if (!bridge[k] || !fg[i] && !bridge[i]) continue
                    raw[k] = region; q[tail++] = k
                }
            }
            region++
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (colorCnt < n * minFrac || colorCnt > n * maxFrac || bw < 4 || bh < 4) continue
            val mask = Mask(bw, bh)
            for (k in 0 until tail) { val i = q[k]; mask[i % w - x0, i / w - y0] = true }
            fillSmallHoles(mask, max(4, colorCnt / 60))
            val id = blobs.size
            for (k in 0 until tail) label[q[k]] = id
            val color = (0xFF shl 24) or ((rr / colorCnt).toInt() shl 16) or ((gg / colorCnt).toInt() shl 8) or (bb / colorCnt).toInt()
            hsv(color, t)
            blobs += Blob(id, colorCnt, x0, y0, x1, y1, color, t[0], mask, ShapeClassifier.classify(mask))
        }
        mergeOverlapping(blobs, label, w)
        val kept = blobs.sortedByDescending { it.count }.take(maxBlobs)
        if (kept.size < blobs.size) { val ok = kept.map { it.id }.toSet(); for (i in 0 until n) if (label[i] >= 0 && label[i] !in ok) label[i] = -1 }
        return Segmentation(w, h, label, kept)
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
