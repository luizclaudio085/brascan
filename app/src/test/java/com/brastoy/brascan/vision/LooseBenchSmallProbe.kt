package com.brastoy.brascan.vision

import org.junit.Test

/**
 * Sonda (não é regressão) da frente "peças pequenas e não achadas": o mesmo banco do LooseBenchProbe, com o tempo só do
 * detector, achadas por tipo pequeno, o detector antigo (sem peças soltas) para comparar e a mesma cena em resolução 2×
 * (o que a contagem ganharia processando 960×720).
 *   ./gradlew :app:testDebugUnitTest --tests '*LooseBenchSmallProbe*'
 */
class LooseBenchSmallProbe {
    private val small = listOf("pequeno/retangulo", "pequeno/retanguloTri", "pequeno/equilatero", "pequeno/L", "medio/E", "grande/E")

    @Test fun probe() {
        val runs = listOf(
            Triple("antigo 480x360", 1, LooseBench::detectOld),
            Triple("480x360", 1, LooseBench::detect),
            Triple("960x720", 2, LooseBench::detect),
        )
        for ((label, scale, detector) in runs) for ((name, hard) in listOf("fácil" to false, "difícil" to true)) {
            val st = LooseBench.run(1..30, 12, hard, detector, scale = scale)
            println("$label $name: $st (detector " + "%.0f".format(st.detMs) + " ms/cena)")
            println("  pequenas: " + st.kindLine(small))
            println("  erros mais comuns: " + st.confusion.entries.sortedByDescending { it.value }.take(10).joinToString { "${it.key} ×${it.value}" })
        }
    }
}
