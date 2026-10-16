package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Test
import java.io.File
import java.util.Random

/** Sonda: o que o segmentador vê num quadro da cena realista (blobs, forma, tamanho) + imagem dos rótulos. */
class RealisticSegProbe {
    @Test fun probe() {
        val tiles = RealisticScene.house(); val rnd = Random(1)
        val dir = File("build/realista").apply { mkdirs() }
        for ((name, eye, o) in listOf(
            Triple("seg-limpa", V3(.30f, .28f, .30f), RealisticScene.Opts(noise = 0f, carpet = false, shadow = false)),
            Triple("seg-celular", V3(.30f, .28f, .30f), RealisticScene.Opts(noise = 4f, blur = 1)),
        )) {
            val f = RealisticScene.render(tiles, eye, V3(0f, .05f, 0f), rnd, 480, 360, o)
            RealisticScene.savePpm(f, File(dir, "$name.ppm"))
            Segmenter.debugSeams = true
            val seg = Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f)
            Segmenter.debugSeams = false
            println("$name: ${seg.blobs.size} blobs")
            for (b in seg.blobs) println("  #${b.id} ${b.count}px caixa ${b.bw}x${b.bh} em (${b.minX},${b.minY}) cor #%06X forma=${b.fit?.shape} solidez=%.2f".format(b.color and 0xFFFFFF, b.fit?.solidity ?: 0f))
            // rótulos coloridos por blob
            val vis = IntArray(f.w * f.h) { i -> val l = seg.labels[i]; if (l < 0) (f.rgb[i] and 0xFFFFFF) / 3 or (0xFF shl 24) else seg.blobs.firstOrNull { it.id == l }?.color ?: 0xFFFFFFFF.toInt() }
            RealisticScene.savePpm(FrameSample(vis, f.w, f.h, f.fx, f.fy, f.cx, f.cy, f.camToWorld), File(dir, "$name-rotulos.ppm"))
            val dets = PieceSlam(Intrinsics(390.0, 390.0, 240.0, 180.0)).detect(seg, f.rgb)
            println("  detecções: " + dets.joinToString { "${it.shape}(rms %.1f, ${it.areaPx}px)".format(it.rms) })
        }
    }
}
