package com.brastoy.brascan.vision

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** Rasteriza as formas exatas do catálogo (as mesmas do simulador) em vários ângulos e confere a classificação. */
class ShapeClassifierTest {
    private fun inPoly(p: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var c = false; var j = p.size - 1
        for (i in p.indices) { val (xi, yi) = p[i]; val (xj, yj) = p[j]; if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c; j = i }
        return c
    }

    private fun raster(shape: String, rot: Double, side: Float = 70f): IntArray {
        val w = 240; val h = 240; val px = IntArray(w * h) { 0xFFC8BCA8.toInt() } // bege, como o carpete
        val poly = Catalog.polygon(shape); val holes = Catalog.holes(shape)
        val cy = (poly.minOf { it.second } + poly.maxOf { it.second }) / 2
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - 120f; val dy = 120f - y
            val a = ((dx * cos(-rot) - dy * sin(-rot)) / side).toFloat(); val b = ((dx * sin(-rot) + dy * cos(-rot)) / side).toFloat() + cy
            if (inPoly(poly, a, b) && holes.none { inPoly(it, a, b) }) px[y * w + x] = 0xFF4FA02A.toInt()
        }
        return px
    }

    @Test fun classifiesCatalogShapesAtSeveralAngles() {
        val wrong = ArrayList<String>()
        for (s in Catalog.SHAPES - "encaixe") for (deg in listOf(0.0, 17.0, 45.0, 90.0, 133.0, 200.0)) {
            val blobs = Segmenter.segment(raster(s, Math.toRadians(deg)), 240, 240).blobs
            val got = blobs.maxByOrNull { it.count }?.fit?.shape
            if (got != s) wrong += "$s@${deg.toInt()}° → $got"
        }
        assertEquals("erros: $wrong", 0, wrong.size)
    }

    /** Giro recuperado pelo encaixe do molde = giro aplicado (foto de frente, módulo a simetria da peça). */
    @Test fun recoversOrientation() {
        val period = mapOf("retangulo" to 180.0, "L" to 360.0, "retanguloTri" to 360.0, "isoceles" to 360.0, "porta" to 360.0, "E" to 360.0, "equilatero" to 120.0)
        val wrong = ArrayList<String>()
        for ((shape, per) in period) for (deg in listOf(0.0, 90.0, 180.0, 270.0, 30.0, 133.0)) {
            val seg = Segmenter.segment(raster(shape, Math.toRadians(deg)), 240, 240)
            val p = PhotoPieces.from(seg).maxBy { it.w * it.h }
            val got = Math.toDegrees(p.spin.toDouble())
            val diff = (((got - deg) % per + per) % per).let { if (it > per / 2) it - per else it }
            if (kotlin.math.abs(diff) > 8) wrong += "$shape@${deg.toInt()}° → ${got.toInt()}°"
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
