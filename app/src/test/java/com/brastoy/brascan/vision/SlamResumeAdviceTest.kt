package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/** Fase 8 (salvar/retomar o escaneamento) e fase 11 (dicas de onde escanear). */
class SlamResumeAdviceTest {
    private val k = Intrinsics(390.0, 390.0, 240.0, 180.0)
    private val target = V3(-.02f, .04f, .04f)

    private fun feed(slam: PieceSlam, from: Int, to: Int, total: Int = 40, turns: Double = 1.15) {
        val rnd = Random(10L + from)
        for (i in from until to) {
            val a = i * turns * 2 * Math.PI / total
            val eye = V3((cos(a) * .40).toFloat(), (.24 + .05 * sin(i * .7)).toFloat(), (sin(a) * .40).toFloat())
            val f = SyntheticScene.render(eye, target, rnd, 480, 360)
            val fw = (target - eye).norm(); val r = fw.cross(V3.UP).norm(); val up = r.cross(fw)
            slam.track(Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f), doubleArrayOf(V3.UP.dot(r).toDouble(), -V3.UP.dot(up).toDouble(), V3.UP.dot(fw).toDouble()))
        }
    }

    /** Distâncias entre centros das peças (independe do referencial) comparadas com a verdade. */
    private fun worstPairError(found: List<Piece3D>): Double {
        val truth = SyntheticScene.tiles
        val pairs = truth.mapNotNull { t ->
            val bin = Segmenter.hueBin(FloatArray(3).also { Segmenter.hsv(t.color, it) }[0])
            found.filter { it.shape == t.shape && Segmenter.hueBin(FloatArray(3).also { a -> Segmenter.hsv(it.color, a) }[0]) == bin }.maxByOrNull { it.support }?.let { t to it }
        }
        var worst = 0.0
        for (i in pairs.indices) for (j in i + 1 until pairs.size) {
            val dt = (pairs[i].first.c - pairs[j].first.c).len(); val de = (pairs[i].second.center - pairs[j].second.center).len()
            worst = maxOf(worst, kotlin.math.abs((dt - de).toDouble()))
        }
        return worst
    }

    @Test fun saveHalfwayAndResumeTheOtherHalf() {
        val first = PieceSlam(k); feed(first, 0, 20)
        val saved = first.export()
        val second = PieceSlam(k)
        val n = second.restore(saved)
        assertEquals(first.map.size, n)
        assertFalse(second.relocalized)
        feed(second, 20, 40)
        assertTrue("não se realinhou com o mapa salvo", second.relocalized)
        val found = second.pieces()
        val err = worstPairError(found)
        println("retomado: ${found.size} peças, pior erro entre pares %.1f cm".format(err * 100))
        assertTrue("pior erro entre pares %.1f cm".format(err * 100), err < .04)
        assertTrue("duplicou peças: ${found.size}", found.size <= SyntheticScene.tiles.size + 2)
    }

    @Test fun unrelatedSavedMapIsNeverForced() {
        val first = PieceSlam(k); feed(first, 0, 20)
        // mapa "de outra construção": mesmas posições, cores diferentes
        val other = first.export().lines().joinToString("\n") { ln ->
            val f = ln.split("|"); if (f.size < 11) ln else f.toMutableList().also { it[3] = ((it[3].toInt() + 6) % 12).toString() }.joinToString("|")
        }
        val s = PieceSlam(k); s.restore(other); val before = s.map.size
        feed(s, 20, 30)
        assertFalse("alinhou com mapa errado", s.relocalized)
        assertEquals("adicionou peças sem estar alinhado", before, s.map.size)
        assertTrue(s.advice().first().text.startsWith("Procurando"))
    }

    @Test fun adviceFollowsCoverage() {
        val s = PieceSlam(k); feed(s, 0, 8)
        val early = s.advice().map { it.text }
        println("dicas no começo: $early")
        assertTrue("devia pedir para ver outro lado: $early", early.any { it.startsWith("Falta ver") })
        feed(s, 8, 40)
        val late = s.advice(limit = 20).map { it.text }
        println("dicas no fim: $late")
        assertFalse("ainda pede outro lado depois da volta completa: $late", late.any { it.startsWith("Falta ver") })
        assertTrue("devia sugerir vista de cima (câmera sempre baixa): $late", late.any { it.contains("de cima") })
    }
}
