package com.brastoy.brascan.vision

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Random

/** A conversão por arrays dá exatamente o mesmo que a antiga, que lia o ByteBuffer pixel a pixel com get(i). */
class YuvTest {
    /** Cópia literal da conversão antiga (ArScanView.yuvToArgb até 01/11/2026). */
    private fun old(yb: ByteBuffer, yRow: Int, yPix: Int, ub: ByteBuffer, uRow: Int, uPix: Int, vb: ByteBuffer, vRow: Int, vPix: Int, w: Int, h: Int, outW: Int): IntArray {
        val oh = h * outW / w
        val out = IntArray(outW * oh)
        val bx = (w / outW).coerceIn(1, 4); val by = (h / oh).coerceIn(1, 4)
        for (oy in 0 until oh) {
            val sy = oy * h / oh
            for (ox in 0 until outW) {
                val sx = ox * w / outW
                var ys = 0; var n = 0
                for (dy in 0 until by) { val row = (sy + dy).coerceAtMost(h - 1) * yRow; for (dx in 0 until bx) { ys += yb.get(row + (sx + dx).coerceAtMost(w - 1) * yPix).toInt() and 255; n++ } }
                val yy = ys.toFloat() / n
                var us = 0; var vs = 0; var nc = 0
                for (dy in 0 until by step 2) { val row = ((sy + dy) / 2).coerceAtMost(h / 2 - 1); for (dx in 0 until bx step 2) { val col = ((sx + dx) / 2).coerceAtMost(w / 2 - 1)
                    us += ub.get(row * uRow + col * uPix).toInt() and 255; vs += vb.get(row * vRow + col * vPix).toInt() and 255; nc++ } }
                val u = us.toFloat() / nc - 128f
                val v = vs.toFloat() / nc - 128f
                val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (yy - .344f * u - .714f * v).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
                out[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    @Test fun sameAsByteBufferVersion() {
        val rnd = Random(5)
        // (largura, altura, saída, padding de linha, croma intercalada (NV21/NV12) ou planar)
        for ((w, h, outW, pad, inter) in listOf(listOf(960, 720, 480, 0, 1), listOf(640, 480, 480, 64, 1), listOf(1280, 960, 480, 0, 0), listOf(640, 480, 240, 32, 0), listOf(480, 360, 480, 0, 1))) {
            val yRow = w + pad; val cw = w / 2; val ch = h / 2
            val uPix = if (inter == 1) 2 else 1; val uRow = cw * uPix + pad
            val y = ByteArray(yRow * h).also { rnd.nextBytes(it) }
            // intercalada: U e V no mesmo bloco, V começa 1 byte depois; limite do buffer termina antes do último byte
            val uv = ByteArray(uRow * ch).also { rnd.nextBytes(it) }
            val ub = if (inter == 1) ByteBuffer.wrap(uv, 0, uv.size - 1).slice() else ByteBuffer.wrap(ByteArray(uRow * ch).also { rnd.nextBytes(it) })
            val vb = if (inter == 1) ByteBuffer.wrap(uv, 1, uv.size - 1).slice() else ByteBuffer.wrap(ByteArray(uRow * ch).also { rnd.nextBytes(it) })
            val yb = ByteBuffer.wrap(y)
            // posição do buffer fora do 0 (o get(i) absoluto ignora a posição; a cópia também tem que ignorar)
            yb.position(7); ub.position(3); vb.position(5)
            val want = old(yb, yRow, 1, ub, uRow, uPix, vb, uRow, uPix, w, h, outW)
            val got = Yuv.toArgb(yb, yRow, 1, ub, uRow, uPix, vb, uRow, uPix, w, h, outW).first
            assertArrayEquals("w=$w h=$h out=$outW pad=$pad inter=$inter", want, got)
            assertArrayEquals("segunda chamada (arrays reaproveitados)", want, Yuv.toArgb(yb, yRow, 1, ub, uRow, uPix, vb, uRow, uPix, w, h, outW).first)
        }
    }
}
