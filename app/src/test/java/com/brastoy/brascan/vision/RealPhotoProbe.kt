package com.brastoy.brascan.vision

import org.junit.Test
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/** Diagnóstico nas fotos reais das peças (imprime o que o segmentador vê). */
class RealPhotoProbe {
    /** Fotos reais já reduzidas para 400 px (ARGB cru em gzip: o classpath de teste do Android não tem javax.imageio). */
    fun load(name: String): Triple<IntArray, Int, Int> = DataInputStream(GZIPInputStream(javaClass.getResourceAsStream("/fotos/$name.argb.gz")!!)).use { d ->
        val w = d.readInt(); val h = d.readInt(); Triple(IntArray(w * h) { d.readInt() }, w, h)
    }

    @Test fun probe() {
        for (n in listOf("janelas", "tamanhos", "todas")) {
            val (px, w, h) = load(n)
            val seg = Segmenter.segment(px, w, h)
            println("== $n ${w}x$h: ${seg.blobs.size} regiões")
            for (p in PhotoPieces.from(seg).sortedBy { it.cy * 10000 + it.cx }) println("  -> %-12s %-7s #%06X em (%.0f,%.0f) lado=%.0f".format(p.shape, p.size, p.color and 0xFFFFFF, p.cx, p.cy, p.side))
            for (b in seg.blobs.sortedBy { it.minY * 10000 + it.minX }) {
                val f = b.fit
                println("  #%06X hue=%3.0f n=%5d box=(%d,%d %dx%d) %s obb=%.0fx%.0f sol=%.2f rect=%.2f cantos=%d".format(
                    b.color and 0xFFFFFF, b.hue, b.count, b.minX, b.minY, b.bw, b.bh, f?.shape, f?.obb?.w ?: 0f, f?.obb?.h ?: 0f, f?.solidity ?: 0f, f?.rectangularity ?: 0f, f?.corners?.size ?: 0))
                f?.gaps?.forEach { g -> println("      vão %s c=(%.2f,%.2f) ext=%.2fx%.2f área=%.2f".format(if (g.enclosed) "FURO" else "entalhe", g.cu, g.cv, g.su, g.sv, g.area)) }
            }
        }
    }
}
