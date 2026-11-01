package com.brastoy.brascan.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

/**
 * O Lab rápido do Segmenter (tabelas por canal + raiz cúbica por polinômio, Segmenter.lab e o caminho da segmentação)
 * dá o MESMO float que a conta direta de antes (cópia abaixo, com kotlin.math.cbrt) em todas as 2^24 cores; e a
 * mediana do fundo por seleção é a mesma de sorted()[k].
 */
class SegmenterLabTest {
    // ---- conta de antes (Segmenter.lab até ce4b61b), copiada sem mudança ----
    private val lin = FloatArray(256) { val c = it / 255f; if (c <= .04045f) c / 12.92f else ((c + .055f) / 1.055f).pow(2.4f) }
    private fun f(t: Float) = if (t > .008856f) kotlin.math.cbrt(t) else 7.787f * t + 16f / 116f
    private fun refLab(c: Int, out: FloatArray) {
        val r = lin[c shr 16 and 255]; val g = lin[c shr 8 and 255]; val b = lin[c and 255]
        val x = f((.4124f * r + .3576f * g + .1805f * b) / .95047f)
        val y = f(.2126f * r + .7152f * g + .0722f * b)
        val z = f((.0193f * r + .1192f * g + .9505f * b) / 1.08883f)
        out[0] = 116f * y - 16f; out[1] = 500f * (x - y); out[2] = 200f * (y - z)
    }

    @Test fun allColorsBitIdentical() {
        val chunk = 1 shl 20
        val px = IntArray(chunk); val L = FloatArray(chunk); val A = FloatArray(chunk); val B = FloatArray(chunk)
        val t = FloatArray(3); val u = FloatArray(3)
        var bad = 0; var first = ""
        fun same(a: Float, b: Float) = a.toRawBits() == b.toRawBits()
        for (base in 0 until (1 shl 24) step chunk) {
            for (i in 0 until chunk) px[i] = (0xFF shl 24) or (base + i)
            Segmenter.labPixels(px, chunk, L, A, B)
            for (i in 0 until chunk) {
                refLab(px[i], t); Segmenter.lab(px[i], u)
                if (!same(t[0], L[i]) || !same(t[1], A[i]) || !same(t[2], B[i]) || !same(t[0], u[0]) || !same(t[1], u[1]) || !same(t[2], u[2])) {
                    if (bad++ == 0) first = "#%06X: antes (%s, %s, %s) × segmentação (%s, %s, %s) × lab (%s, %s, %s)".format(base + i, t[0], t[1], t[2], L[i], A[i], B[i], u[0], u[1], u[2])
                }
            }
        }
        assertEquals("cores com Lab diferente (primeira: $first)", 0, bad)
    }

    /** A mediana do fundo por seleção (Segmenter.select) é o mesmo float de sorted()[k], com negativos, −0, +0 e repetidos. */
    @Test fun selectMatchesSorted() {
        val rnd = java.util.Random(5)
        for (trial in 0 until 400) {
            val n = 1 + rnd.nextInt(if (trial < 200) 30 else 5000)
            val pool = floatArrayOf(0f, -0f, 1f, -1f, 3.5f, -120.25f, Float.MIN_VALUE, -Float.MIN_VALUE, 1e-30f, 7f)
            val v = FloatArray(n) { if (rnd.nextInt(3) == 0) pool[rnd.nextInt(pool.size)] else (rnd.nextGaussian() * 40).toFloat() }
            val sorted = v.toList().sorted()
            for (k in listOf(0, n / 2, n - 1, rnd.nextInt(n))) {
                val got = Segmenter.select(v, n, k)
                assertEquals("n=$n k=$k", sorted[k].toRawBits(), got.toRawBits())
            }
        }
    }
}
