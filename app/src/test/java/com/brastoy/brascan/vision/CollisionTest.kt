package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollisionTest {
    private val X = V3(1f, 0f, 0f); private val Y = V3(0f, 1f, 0f); private val Z = V3(0f, 0f, 1f)
    private val flatT = Catalog.THICKNESS / 2
    private fun flat(id: Int, shape: String, x: Float, z: Float, fixed: Boolean = false) = Collision.Body(id, shape, V3(x, flatT, z), X, Z, 1f, fixed)

    @Test fun overlappingFlatTilesSlideApartInThePlane() {
        val a = flat(1, "quadrado", 0f, 0f); val b = flat(2, "quadrado", .6f, .1f)
        Collision.resolve(listOf(a, b))
        assertTrue("ainda se cruzam", Collision.worstOverlap(listOf(a, b)) < .005f)
        assertEquals("não deve empilhar", flatT, b.center.y, 1e-4f)
        assertTrue((b.center - a.center).x > .99f)
    }

    @Test fun wellObservedPieceMovesLess() {
        val seen = Collision.Body(1, "quadrado", V3(0f, flatT, 0f), X, Z, 1f, weight = 9f)
        val guess = Collision.Body(2, "quadrado", V3(.6f, flatT, 0f), X, Z, 1f, weight = 1f)
        Collision.resolve(listOf(seen, guess))
        assertTrue("peça confiável andou ${-seen.center.x}", -seen.center.x < .05f)
        assertTrue(guess.center.x - seen.center.x > .99f)
    }

    @Test fun touchingTilesDoNotMove() {
        val a = flat(1, "quadrado", 0f, 0f); val b = flat(2, "quadrado", 1f, 0f)
        assertEquals(0, Collision.resolve(listOf(a, b)))
    }

    @Test fun pieceInTheLNotchStaysPut() {
        // L ocupa o canto inferior esquerdo; o vão (x,y > -.15) recebe um quadrado de 0,6
        val l = Collision.Body(1, "L", V3(0f, .5f, 0f), X, Y, 1f)
        val sq = Collision.Body(2, "quadrado", V3(.2f, .7f, 0f), X, Y, .6f)
        assertEquals(0, Collision.resolve(listOf(l, sq), floor = false))
    }

    @Test fun insertInsideFrameIsAllowed() {
        val frame = Collision.Body(1, "moldura", V3(0f, .5f, 0f), X, Y, 1f)
        val ins = Collision.Body(2, "encaixe", V3(0f, .5f, 0f), X, Y, .81f)
        assertEquals(0, Collision.resolve(listOf(frame, ins)))
    }

    @Test fun wallThroughFloorTileIsPushedOut() {
        val floorTile = flat(1, "quadrado", 0f, 0f, fixed = true)
        val wall = Collision.Body(2, "quadrado", V3(0f, .45f, 0f), X, Y, 1f)
        Collision.resolve(listOf(floorTile, wall))
        assertTrue(Collision.worstOverlap(listOf(floorTile, wall)) < .005f)
    }

    @Test fun nothingBelowTheFloor() {
        val w = Collision.Body(1, "equilatero", V3(0f, 0f, 0f), X, Y, 1f)
        Collision.resolve(listOf(w))
        assertTrue(w.minY > -1e-4f)
    }

    @Test fun pileOfOverlappingPiecesEndsClean() {
        val r = java.util.Random(1)
        val bodies = (0 until 12).map { flat(it, Catalog.SHAPES[it % Catalog.SHAPES.size], r.nextFloat() * 2, r.nextFloat() * 2) }
        Collision.resolve(bodies, iters = 200)
        for (i in bodies.indices) for (j in i + 1 until bodies.size) { val w = Collision.worstOverlap(listOf(bodies[i], bodies[j])); if (w > .01f) println("par ${bodies[i].shape}@${bodies[i].center.x},${bodies[i].center.z} × ${bodies[j].shape}@${bodies[j].center.x},${bodies[j].center.z} = $w") }
        assertTrue("sobra ${Collision.worstOverlap(bodies)}", Collision.worstOverlap(bodies) < .01f)
    }
}
