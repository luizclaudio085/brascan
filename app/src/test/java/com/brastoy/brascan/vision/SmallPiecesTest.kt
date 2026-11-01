package com.brastoy.brascan.vision

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.hypot

/**
 * Peças pequenas e finas na contagem de peças soltas (LooseBench): antes desta guarda ~18% das peças sumiam — quase todas
 * pequenas (retângulo, triângulos, L) e a ameia média —, porque a segmentação partia a peça em aro + painel + vincos e
 * nenhum pedaço chegava ao tamanho mínimo.
 */
class SmallPiecesTest {
    private val smallKinds = LooseBench.KINDS.filter { it.first == "pequeno" }

    @Test fun smallPiecesAreFound() {
        for (hard in listOf(false, true)) {
            val st = LooseBench.run(1..10, 12, hard)
            println("${if (hard) "difícil" else "fácil"} 1..10: $st")
            assertTrue("achadas ${st.pct(st.found)}%", st.pct(st.found) >= 96.0)
            assertTrue("falsos positivos ${st.falsePos}", st.falsePos <= 8)
            // a forma (sem o tamanho, que é outra frente) tem que melhorar junto: pedaço de peça não é peça
            assertTrue("forma ${st.pct(st.shapeOk)}%", st.pct(st.shapeOk) >= if (hard) 70.0 else 80.0)
        }
    }

    /** Só peças pequenas no quadro: sem peça grande para comparar a cor, vale o croma mínimo absoluto. */
    @Test fun onlySmallPiecesInTheFrame() {
        var truth = 0; var found = 0; var fp = 0
        for (seed in 1..8) {
            val s = LooseBench.scene(100L + seed, 10, seed % 2 == 0, kinds = smallKinds)
            val f = RealisticScene.render(s.tiles, s.eye, s.target, Random(100L + seed), s.w, s.h, s.opts)
            val st = LooseBench.score(s, LooseBench.detect(f.rgb, f.w, f.h))
            truth += st.truth; found += st.found; fp += st.falsePos
        }
        println("só pequenas: achadas $found/$truth, falsos positivos $fp")
        assertTrue("achadas $found/$truth", found >= truth * .93)
        assertTrue("falsos positivos $fp", fp <= 6)
    }

    /** O E pequeno/médio tem os dentes cobertos de ímãs: o detector de fotos cola os ímãs na peça. */
    @Test fun ameiaKeepsItsTeeth() {
        val kinds = listOf("medio" to "E", "grande" to "E", "grande" to "quadrado")
        var es = 0; var ok = 0
        for (seed in 1..8) {
            val s = LooseBench.scene(200L + seed, 6, false, kinds = kinds)
            val f = RealisticScene.render(s.tiles, s.eye, s.target, Random(200L + seed), s.w, s.h, s.opts)
            val dets = LooseBench.detect(f.rgb, f.w, f.h)
            for (t in s.tiles.filter { it.shape == "E" }) {
                val c = RealisticScene.project(s.eye, s.target, t.c, s.w, s.h) ?: continue
                if (c[0] < 0 || c[1] < 0 || c[0] >= s.w || c[1] >= s.h) continue
                es++
                val d = dets.minByOrNull { hypot(it.x - c[0], it.y - c[1]) } ?: continue
                if (d.shape == "E") ok++
            }
        }
        println("ameias com forma E: $ok/$es")
        assertTrue("E reconhecido $ok/$es", ok >= es * .5)
    }
}
