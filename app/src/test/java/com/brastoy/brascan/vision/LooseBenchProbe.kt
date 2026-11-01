package com.brastoy.brascan.vision

import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Sonda (não é regressão): números do detector de fotos na contagem de peças soltas.
 *   ./gradlew :app:testDebugUnitTest --tests '*LooseBenchProbe*'
 * Grava algumas cenas em build/solto (arquivos .ppm) para olhar.
 */
class LooseBenchProbe {
    @Test fun probe() {
        val dir = File("build/solto").apply { mkdirs() }
        for ((name, hard) in listOf("fácil" to false, "difícil" to true)) {
            val t0 = System.nanoTime()
            val st = LooseBench.run(1..30, 12, hard)
            println("$name: $st  (" + "%.0f".format((System.nanoTime() - t0) / 1e6 / 30) + " ms/cena)")
            println("  erros mais comuns: " + st.confusion.entries.sortedByDescending { it.value }.take(14).joinToString { "${it.key} ×${it.value}" })
        }
        for (seed in 1..3) {
            val s = LooseBench.scene(seed.toLong(), 12, seed == 3)
            val f = RealisticScene.render(s.tiles, s.eye, s.target, Random(seed.toLong()), s.w, s.h, s.opts)
            RealisticScene.savePpm(f, File(dir, "cena$seed.ppm"))
        }
    }
}
