package com.brastoy.brascan.vision

/**
 * YUV_420_888 → ARGB reduzido para [outW] de largura, sobre os planos já copiados para arrays. Quando a redução é
 * ≥ 2×, faz MÉDIA do bloco de luminância (e da croma correspondente) em vez de pegar um pixel só: menos ruído e
 * serrilhado. É a mesma conta que era feita direto no ByteBuffer (pixel a pixel com get(i), lento no ART); o
 * resultado é idêntico (YuvTest).
 */
object Yuv {
    fun toArgb(yb: ByteArray, yRow: Int, yPix: Int, ub: ByteArray, uRow: Int, uPix: Int, vb: ByteArray, vRow: Int, vPix: Int,
               w: Int, h: Int, outW: Int): Triple<IntArray, Int, Int> {
        val oh = h * outW / w
        val out = IntArray(outW * oh)
        val bx = (w / outW).coerceIn(1, 4); val by = (h / oh).coerceIn(1, 4) // tamanho do bloco de média
        for (oy in 0 until oh) {
            val sy = oy * h / oh
            for (ox in 0 until outW) {
                val sx = ox * w / outW
                var ys = 0; var n = 0
                for (dy in 0 until by) { val row = (sy + dy).coerceAtMost(h - 1) * yRow; for (dx in 0 until bx) { ys += yb[row + (sx + dx).coerceAtMost(w - 1) * yPix].toInt() and 255; n++ } }
                val yy = ys.toFloat() / n
                var us = 0; var vs = 0; var nc = 0
                for (dy in 0 until by step 2) {
                    val row = ((sy + dy) / 2).coerceAtMost(h / 2 - 1)
                    for (dx in 0 until bx step 2) {
                        val col = ((sx + dx) / 2).coerceAtMost(w / 2 - 1)
                        us += ub[row * uRow + col * uPix].toInt() and 255; vs += vb[row * vRow + col * vPix].toInt() and 255; nc++
                    }
                }
                val u = us.toFloat() / nc - 128f
                val v = vs.toFloat() / nc - 128f
                val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (yy - .344f * u - .714f * v).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
                out[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Triple(out, outW, oh)
    }

    /** Arrays reaproveitados por thread (cada câmera analisa numa thread própria): sem ~1 MB de lixo por quadro. */
    private val cache = ThreadLocal<Array<ByteArray>>()

    /** Copia os 3 planos (do índice 0 ao limite, como o get(i) absoluto lia) e converte. */
    fun toArgb(y: java.nio.ByteBuffer, yRow: Int, yPix: Int, u: java.nio.ByteBuffer, uRow: Int, uPix: Int, v: java.nio.ByteBuffer, vRow: Int, vPix: Int,
               w: Int, h: Int, outW: Int): Triple<IntArray, Int, Int> {
        val bufs = cache.get() ?: arrayOf(ByteArray(0), ByteArray(0), ByteArray(0)).also { cache.set(it) }
        fun copy(i: Int, b: java.nio.ByteBuffer): ByteArray {
            val n = b.limit()
            if (bufs[i].size < n) bufs[i] = ByteArray(n)
            val d = b.duplicate(); d.position(0); d.get(bufs[i], 0, n)
            return bufs[i]
        }
        return toArgb(copy(0, y), yRow, yPix, copy(1, u), uRow, uPix, copy(2, v), vRow, vPix, w, h, outW)
    }
}
