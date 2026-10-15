package com.brastoy.brascan.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Rasteriza formas conhecidas (em vários ângulos) e confere a classificação geométrica. */
class ShapeClassifierTest {
    private fun raster(rot: Double, inside: (Float, Float) -> Boolean): IntArray {
        val w = 200; val h = 160; val px = IntArray(w * h) { 0xFFC8C4BC.toInt() }
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - 100f; val dy = y - 80f
            val a = (dx * cos(-rot) - dy * sin(-rot)).toFloat(); val b = (dx * sin(-rot) + dy * cos(-rot)).toFloat()
            if (inside(a, b)) px[y * w + x] = 0xFFE53950.toInt()
        }
        return px
    }

    private fun shapeOf(px: IntArray) = Segmenter.segment(px, 200, 160).blobs.single().fit!!.shape

    private val s = 30f
    private val shapes: Map<String, (Float, Float) -> Boolean> = mapOf(
        "quadrado" to { a, b -> kotlin.math.abs(a) < s && kotlin.math.abs(b) < s },
        "retangulo" to { a, b -> kotlin.math.abs(a) < 2 * s && kotlin.math.abs(b) < s },
        "janela" to { a, b -> kotlin.math.abs(a) < s && kotlin.math.abs(b) < s && !(kotlin.math.abs(a) < s * .65f && kotlin.math.abs(b) < s * .65f) },
        "equilatero" to { a, b -> val t = s * 2 * .866f; b < t / 3 && b > -2 * t / 3 + 0 && kotlin.math.abs(a) < (b + 2 * t / 3) / 1.732f },
        "retanguloTri" to { a, b -> a > -s && b > -s && a + b < 0 },
        "L" to { a, b -> kotlin.math.abs(a) < s && kotlin.math.abs(b) < s && !(a > -s / 3 && b < s / 3) },
        "E" to { a, b -> kotlin.math.abs(a) < s && kotlin.math.abs(b) < s && !(a > -s * .6f && (b in -s * .6f..-s * .2f || b in s * .2f..s * .6f)) },
    )

    @Test fun classifiesAllShapesAtSeveralAngles() {
        val wrong = ArrayList<String>()
        for ((name, f) in shapes) for (deg in listOf(0.0, 17.0, 45.0, 90.0, 133.0)) {
            val got = shapeOf(raster(Math.toRadians(deg), f))
            if (got != name) wrong += "$name@$deg° → $got"
        }
        assertEquals("erros: $wrong", 0, wrong.size)
    }

    @Test fun darkFramesSplitTouchingTilesOfSameColor() {
        val w = 200; val h = 160; val px = IntArray(w * h) { 0xFFC8C4BC.toInt() }
        for (y in 40 until 100) for (x in 20 until 180) {
            val frame = (x - 20) % 60 < 4 || (x - 20) % 60 > 55 || y < 44 || y > 95
            px[y * w + x] = if (frame) 0xFF222222.toInt() else 0xFF2F8CFF.toInt()
        }
        assertEquals(3, Segmenter.segment(px, w, h).blobs.size)
    }
}
