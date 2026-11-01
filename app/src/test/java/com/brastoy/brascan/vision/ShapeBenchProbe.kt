package com.brastoy.brascan.vision

import org.junit.Test

/**
 * Sonda (não é regressão) da FORMA na contagem de peças soltas: regras antigas × molde ([ShapeMatch]), acerto por
 * forma, confusões e tempo do detector por quadro. Modos extras: "molde-sem-chao" (sem estimar a inclinação do chão)
 * e "gravidade" (FloorView exato da cena).
 *   ./gradlew :app:testDebugUnitTest --tests '*ShapeBenchProbe*'
 */
class ShapeBenchProbe {
    @Test fun probe() {
        val old = PhotoPieces.templateShapes
        try {
            val modes = (System.getProperty("modes") ?: "regras,molde").split(',')
            for (mode in modes) {
                PhotoPieces.templateShapes = mode != "regras"
                PhotoPieces.autoFloor = mode != "molde-sem-chao"
                println("=== ${mode.uppercase()}")
                for ((name, hard) in listOf("fácil" to false, "difícil" to true)) {
                    val t0 = System.nanoTime()
                    val st = LooseBench.run(1..30, 12, hard, sceneDetector = if (mode == "gravidade") LooseBench::detectWithFloor else null)
                    println("$name: $st  (" + "%.0f".format((System.nanoTime() - t0) / 1e6 / 30) + " ms/cena)")
                    println("  forma entre as achadas: %.1f%%".format(100.0 * st.shapeOk / st.found.coerceAtLeast(1)))
                    println("  por forma: " + st.shapeSeen.keys.sorted().joinToString { "$it ${st.shapeHit[it] ?: 0}/${st.shapeSeen[it]}" })
                    println("  erros de forma: " + st.shapeConf.entries.sortedByDescending { it.value }.take(20).joinToString { "${it.key} ×${it.value}" })
                }
                val rp = RealPhotoProbe()
                if (mode != "gravidade") for (n in listOf("janelas", "tamanhos", "todas")) {
                    val (px, w, h) = rp.load(n)
                    println("  foto $n: " + PhotoPieces.from(Segmenter.segment(px, w, h)).map { "${it.shape}/${it.size}" }.sorted())
                }
            }
            // tempo só do detector (sem renderizar): segmentação × peças
            for (tm in listOf(false, true)) {
                PhotoPieces.templateShapes = tm; PhotoPieces.autoFloor = true
                var tSeg = 0L; var tPieces = 0L; var k = 0
                for (rep in 0 until 2) for (hard in listOf(false, true)) for (seed in 1..15) {
                    val s = LooseBench.scene(seed.toLong(), 12, hard)
                    val f = RealisticScene.render(s.tiles, s.eye, s.target, java.util.Random(seed.toLong()), s.w, s.h, s.opts)
                    val t0 = System.nanoTime(); val seg = Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60)
                    val t1 = System.nanoTime(); PhotoPieces.from(seg); val t2 = System.nanoTime()
                    if (rep == 1) { tSeg += t1 - t0; tPieces += t2 - t1; k++ }
                }
                println("tempo por quadro 480x360 (${if (tm) "molde" else "regras"}): segmentação %.0f ms, peças %.0f ms".format(tSeg / 1e6 / k, tPieces / 1e6 / k))
            }
        } finally { PhotoPieces.templateShapes = old; PhotoPieces.autoFloor = true }
    }
}
