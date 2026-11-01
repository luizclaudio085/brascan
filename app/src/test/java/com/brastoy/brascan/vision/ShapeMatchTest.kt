package com.brastoy.brascan.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Classificação pelo molde ([ShapeMatch] via PhotoPieces.templateShape): formas exatas do catálogo, giradas, pequenas e de viés. */
class ShapeMatchTest {
    private fun inPoly(p: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var c = false; var j = p.size - 1
        for (i in p.indices) { val (xi, yi) = p[i]; val (xj, yj) = p[j]; if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c; j = i }
        return c
    }

    /**
     * Máscara da forma com lado [side] px, girada [rot] e depois deformada por [a] (2×2, chão → imagem; achatamento
     * da perspectiva). Pixel cheio se o centro cai na peça.
     */
    private fun mask(shape: String, rot: Double, side: Float, a: FloatArray = floatArrayOf(1f, 0f, 0f, 1f)): Mask {
        val n = (side * 2.2f).toInt(); val m = Mask(n, n)
        val det = a[0] * a[3] - a[1] * a[2]
        val inv = floatArrayOf(a[3] / det, -a[1] / det, -a[2] / det, a[0] / det)
        val poly = Catalog.polygon(shape); val holes = Catalog.holes(shape)
        val cy = (poly.minOf { it.second } + poly.maxOf { it.second }) / 2
        for (y in 0 until n) for (x in 0 until n) {
            val ix = x + .5f - n / 2f; val iy = y + .5f - n / 2f
            val fx = inv[0] * ix + inv[1] * iy; val fy = inv[2] * ix + inv[3] * iy
            val u = ((fx * cos(-rot) - fy * sin(-rot)) / side).toFloat(); val v = ((fx * sin(-rot) + fy * cos(-rot)) / side).toFloat() + cy
            if (inPoly(poly, u, v) && holes.none { inPoly(it, u, v) }) m[x, y] = true
        }
        return m
    }

    @Test fun catalogShapesAtSeveralSizesAndAngles() {
        val wrong = ArrayList<String>()
        for (s in ShapeMatch.LOOSE) for (side in listOf(34f, 50f, 72f)) for (deg in listOf(0.0, 17.0, 45.0, 90.0, 133.0, 200.0)) {
            val got = PhotoPieces.templateShape(mask(s, Math.toRadians(deg), side))
            if (got != s) wrong += "$s/${side.toInt()}px@${deg.toInt()}° → $got"
        }
        assertEquals("erros: $wrong", 0, wrong.size)
    }

    /** Peça de viés (achatada 0,72 numa direção qualquer): com a deformação conhecida volta à forma certa. */
    @Test fun perspectiveIsUndoneByWarp() {
        val wrong = ArrayList<String>()
        for (s in ShapeMatch.LOOSE) for ((deg, dirDeg) in listOf(20.0 to 0.0, 70.0 to 50.0, 155.0 to 120.0)) {
            val d = Math.toRadians(dirDeg); val k = .72f
            val c = cos(d).toFloat(); val sn = sin(d).toFloat()
            // achata k na direção d: A = R diag(1, k) Rᵀ
            val a = floatArrayOf(c * c + k * sn * sn, (1 - k) * c * sn, (1 - k) * c * sn, sn * sn + k * c * c)
            val det = a[0] * a[3] - a[1] * a[2]; val r = sqrt(det)
            val warp = floatArrayOf(a[3] / det * r, -a[1] / det * r, -a[2] / det * r, a[0] / det * r) // A⁻¹ com det 1
            val got = PhotoPieces.templateShape(mask(s, Math.toRadians(deg), 56f, a), warp)
            if (got != s) wrong += "$s@${deg.toInt()}° viés ${dirDeg.toInt()}° → $got"
        }
        assertEquals("erros: $wrong", 0, wrong.size)
    }

    /** Inclinação do chão estimada pelas peças "redondas": deixa todas isótropas de novo. */
    @Test fun floorTiltFromIsotropicPieces() {
        val w = 480; val h = 360
        for ((tilt, az) in listOf(35.0 to 270.0, 25.0 to 200.0, 50.0 to 300.0)) {
            val t = Math.toRadians(tilt); val a = Math.toRadians(az)
            val truth = FloorView(.8f * w, .8f * w, w / 2f, h / 2f, floatArrayOf((sin(t) * cos(a)).toFloat(), (sin(t) * sin(a)).toFloat(), (-cos(t)).toFloat()))
            // peça redonda (covariância isótropa no chão) vista em vários pontos da imagem: C_img = J⁻¹ C J⁻ᵀ
            val pieces = listOf(120f to 100f, 300f to 90f, 240f to 200f, 90f to 280f, 380f to 270f).mapNotNull { (u, v) ->
                val j = truth.jacobian(u, v) ?: return@mapNotNull null
                val det = j[0] * j[3] - j[1] * j[2]; val i = floatArrayOf(j[3] / det, -j[1] / det, -j[2] / det, j[0] / det)
                val s2 = 1e-4f
                floatArrayOf(u, v, s2 * (i[0] * i[0] + i[1] * i[1]), s2 * (i[0] * i[2] + i[1] * i[3]), s2 * (i[2] * i[2] + i[3] * i[3]))
            }
            val est = FloorView.estimate(w, h, pieces)
            assertTrue("inclinação $tilt° não achada", est != null)
            for (p in pieces) {
                val an = FloorView.anisotropy(floatArrayOf(p[2], p[3], p[4]), est!!.shapeWarp(p[0], p[1])!!.first)
                assertTrue("inclinação $tilt°/$az°: anisotropia %.3f".format(an), an < .04f)
            }
        }
        // foto de cima: nada a corrigir
        val flat = listOf(120f to 100f, 300f to 90f, 240f to 200f).map { (u, v) -> floatArrayOf(u, v, 1f, 0f, 1f) }
        assertEquals(null, FloorView.estimate(w, h, flat))
    }
}
