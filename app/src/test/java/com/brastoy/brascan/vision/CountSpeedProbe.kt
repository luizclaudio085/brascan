package com.brastoy.brascan.vision

import org.junit.Test
import java.util.Random

/**
 * Sonda (não é regressão): tempo da contagem por quadro, separado em segmentação e classificação, numa cena de
 * peças soltas e num quadro de blocos de cor enormes (padrão da câmera do emulador, pior caso de área de blob).
 */
class CountSpeedProbe {
    private fun time(name: String, px: IntArray, w: Int, h: Int) {
        repeat(3) { PhotoPieces.from(Segmenter.segment(px, w, h, maxBlobs = 60)) } // aquece o JIT
        var seg = 0L; var cls = 0L; var n = 0
        repeat(5) {
            val t0 = System.nanoTime(); val s = Segmenter.segment(px, w, h, maxBlobs = 60)
            val t1 = System.nanoTime(); n = PhotoPieces.from(s).size
            val t2 = System.nanoTime(); seg += t1 - t0; cls += t2 - t1
        }
        println("$name ${w}x$h: segmentação %.0f ms, classificação %.0f ms, $n peças (caminho antigo)".format(seg / 5e6, cls / 5e6))
        repeat(2) { PhotoPieces.detect(px, w, h) }
        var tot = 0L; var m = 0
        repeat(5) { val t0 = System.nanoTime(); m = PhotoPieces.detect(px, w, h).size; tot += System.nanoTime() - t0 }
        println("$name ${w}x$h: detector de peças soltas %.0f ms, $m peças".format(tot / 5e6))
    }

    @Test fun probe() {
        val s = LooseBench.scene(1, 12, false)
        val f = RealisticScene.render(s.tiles, s.eye, s.target, Random(1), s.w, s.h, s.opts)
        time("cena solta", f.rgb, f.w, f.h)
        val w = 360; val h = 480; val rnd = Random(7)
        val px = IntArray(w * h) { 0xFFF8F0F8.toInt() }
        val cols = intArrayOf(0xFFDDFF66.toInt(), 0xFFA07468.toInt(), 0xFF44DD77.toInt(), 0xFF7A4FA0.toInt(), 0xFF101010.toInt(), 0xFFFFFFC0.toInt())
        for (b in 0 until 14) {
            val x0 = rnd.nextInt(w - 60); val y0 = rnd.nextInt(h - 60); val bw = 60 + rnd.nextInt(w - x0 - 59); val bh = 40 + rnd.nextInt(minOf(200, h - y0 - 39))
            val c = cols[b % cols.size]
            for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) px[y * w + x] = c
        }
        time("blocos grandes", px, w, h)
    }
}
