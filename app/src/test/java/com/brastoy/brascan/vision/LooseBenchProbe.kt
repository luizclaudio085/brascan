package com.brastoy.brascan.vision

import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Sonda (não é regressão): números do detector de fotos na contagem de peças soltas.
 *   ./gradlew :app:testDebugUnitTest --tests '*LooseBenchProbe*'
 * Rodadas: foto sem nada (inclinação do chão estimada pelas peças), "com gravidade" (câmera verdadeira da cena:
 * intrínsecos + gravidade, como na contagem ao vivo no celular), "com gravidade e altura" (escala absoluta) e a chamada
 * que o app faz hoje (PhotoPieces.from(Segmenter.segment(...)) sem a segmentação de peças soltas).
 * Grava algumas cenas em build/solto (arquivos .ppm) para olhar.
 */
class LooseBenchProbe {
    @Test fun probe() {
        val dir = File("build/solto").apply { mkdirs() }
        for ((name, hard) in listOf("fácil" to false, "difícil" to true)) {
            val frames = LooseBench.frames(1..30, 12, hard)
            for ((round, det) in listOf<Pair<String, (LooseBench.Scene, FrameSample) -> List<LooseBench.Det>>>(
                "" to { _, f -> LooseBench.detect(f.rgb, f.w, f.h) },
                " com gravidade" to { s, f -> LooseBench.detectWithCamera(s, f) },
                " com gravidade e altura" to { s, f -> LooseBench.detectWithCamera(s, f, height = true) },
                " com a chamada atual do app (segmentação sem loose)" to { _, f -> LooseBench.detectOld(f.rgb, f.w, f.h) },
            )) {
                val t0 = System.nanoTime()
                val st = LooseBench.evaluate(frames, det)
                println("$name$round: $st  (" + "%.0f".format((System.nanoTime() - t0) / 1e6 / frames.size) + " ms/cena, detector " + "%.0f".format(st.detMs) + " ms)")
                if (round.isEmpty()) {
                    println("  erros mais comuns: " + st.confusion.entries.sortedByDescending { it.value }.take(14).joinToString { "${it.key} ×${it.value}" })
                    println("  erros de forma: " + st.shapeConf.entries.sortedByDescending { it.value }.take(10).joinToString { "${it.key} ×${it.value}" })
                }
            }
        }
        for (seed in 1..3) {
            val s = LooseBench.scene(seed.toLong(), 12, seed == 3)
            val f = RealisticScene.render(s.tiles, s.eye, s.target, Random(seed.toLong()), s.w, s.h, s.opts)
            RealisticScene.savePpm(f, File(dir, "cena$seed.ppm"))
        }
    }
}
