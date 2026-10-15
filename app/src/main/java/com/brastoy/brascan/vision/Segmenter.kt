package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
 * Segmentação por cor em Kotlin puro (roda igual no celular e nos testes):
 * flood fill de pixels saturados com matiz/brilho parecidos; bordas escuras das peças separam vizinhas.
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

    fun segment(px: IntArray, w: Int, h: Int, minFrac: Float = .002f, maxFrac: Float = .35f, maxBlobs: Int = 80): Segmentation {
        val n = w * h
        val hue = FloatArray(n); val sat = FloatArray(n); val v = FloatArray(n); val t = FloatArray(3)
        for (i in 0 until n) { hsv(px[i], t); hue[i] = t[0]; sat[i] = t[1]; v[i] = t[2] }
        fun colored(i: Int) = sat[i] > .32f && v[i] > .22f
        fun hueDist(a: Float, b: Float) = min(abs(a - b), 360 - abs(a - b))
        val label = IntArray(n) { -1 }; val q = IntArray(n)
        val blobs = ArrayList<Blob>()
        val raw = IntArray(n) { -1 }
        var region = 0
        for (s in 0 until n) {
            if (raw[s] >= 0 || !colored(s)) continue
            var head = 0; var tail = 0; q[tail++] = s; raw[s] = region
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0; var r = 0L; var g = 0L; var b = 0L
            var hx = 0.0; var hy = 0.0
            while (head < tail) {
                val i = q[head++]; val x = i % w; val y = i / w
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                r += px[i] shr 16 and 255; g += px[i] shr 8 and 255; b += px[i] and 255
                val a = Math.toRadians(hue[i].toDouble()); hx += kotlin.math.cos(a); hy += kotlin.math.sin(a)
                for (k in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (y > 0) i - w else -1, if (y < h - 1) i + w else -1)) {
                    if (k < 0 || raw[k] >= 0 || !colored(k)) continue
                    if (hueDist(hue[k], hue[i]) > 16f || abs(v[k] - v[i]) > .25f) continue
                    raw[k] = region; q[tail++] = k
                }
            }
            val count = tail; val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (count >= n * minFrac && count <= n * maxFrac && bw >= 4 && bh >= 4) {
                val mask = Mask(bw, bh)
                for (k in 0 until tail) { val i = q[k]; mask[i % w - x0, i / w - y0] = true }
                val id = blobs.size
                for (k in 0 until tail) label[q[k]] = id
                var meanHue = Math.toDegrees(kotlin.math.atan2(hy, hx)).toFloat(); if (meanHue < 0) meanHue += 360f
                val color = (0xFF shl 24) or ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
                blobs += Blob(id, count, x0, y0, x1, y1, color, meanHue, mask, ShapeClassifier.classify(mask))
            }
            region++
        }
        val kept = blobs.sortedByDescending { it.count }.take(maxBlobs)
        if (kept.size < blobs.size) { val ok = kept.map { it.id }.toSet(); for (i in 0 until n) if (label[i] >= 0 && label[i] !in ok) label[i] = -1 }
        return Segmentation(w, h, label, kept)
    }
}
