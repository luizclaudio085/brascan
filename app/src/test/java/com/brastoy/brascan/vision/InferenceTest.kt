package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class InferenceTest {
    private val X = V3(1f, 0f, 0f); private val Y = V3(0f, 1f, 0f); private val Z = V3(0f, 0f, 1f)
    private var nextId = 1
    private val seen = Evidence(Evidence.Source.SCANNER, views = 8, spreadM = .003)
    /** Parede grande em pé; [alongX] = largura no eixo X (senão no eixo Z). */
    private fun wall(x: Float, z: Float, alongX: Boolean, y: Float = .5f) = Measure.P(nextId++, "quadrado", "grande", V3(x, y, z), if (alongX) X else Z, Y, 1f, seen)
    private fun near(a: V3, x: Float, y: Float, z: Float) = abs(a.x - x) < .1f && abs(a.y - y) < .1f && abs(a.z - z) < .1f

    @Test fun floatingWallGetsHiddenSupport() {
        val h = Inference.infer(listOf(wall(0f, 0f, true, y = 1.5f)))
        val sup = h.filter { it.group.startsWith("apoio") }
        assertEquals(1, sup.size)
        assertTrue("apoio fora do lugar: ${sup[0].pieces.map { it.center }}", near(sup[0].pieces[0].center, 0f, .5f, 0f))
        assertEquals(Evidence.Source.HIPOTESE, sup[0].pieces[0].evidence?.source)
    }

    @Test fun stackedWallsNeedNoSupport() {
        val h = Inference.infer(listOf(wall(0f, 0f, true), wall(0f, 0f, true, y = 1.5f)))
        assertTrue(h.none { it.group.startsWith("apoio") })
    }

    private fun house(withBack: Boolean) = buildList {
        add(wall(-.5f, 1f, true)); add(wall(.5f, 1f, true))           // frente
        add(wall(-1f, -.5f, false)); add(wall(-1f, .5f, false))       // esquerda
        add(wall(1f, -.5f, false)); add(wall(1f, .5f, false))         // direita
        if (withBack) { add(wall(-.5f, -1f, true)); add(wall(.5f, -1f, true)) }
    }

    @Test fun openSideOfAUShapedHouseIsAMissingWall() {
        val h = Inference.infer(house(withBack = false))
        val walls = h.filter { it.group.startsWith("parede") }
        assertEquals("esperava 1 hipótese de parede: ${h.map { it.title }}", 1, walls.size)
        val cs = walls[0].pieces.map { it.center }
        assertEquals(2, cs.size)
        assertTrue("parede fora do lado aberto: $cs", cs.all { abs(it.z + 1f) < .1f && abs(it.y - .5f) < .1f } && cs.any { abs(it.x + .5f) < .1f } && cs.any { abs(it.x - .5f) < .1f })
    }

    @Test fun closedHouseHasCompetingInteriorHypotheses() {
        val h = Inference.infer(house(withBack = true))
        assertTrue("casa fechada não deveria ter parede faltando", h.none { it.group.startsWith("parede") })
        val interior = h.filter { it.group.startsWith("interior") }.map { it.title }.toSet()
        assertEquals(setOf("Interior vazio", "Divisória no meio", "Piso por dentro"), interior)
        // as alternativas de um mesmo grupo somam peso ≈ 1 (competem entre si)
        assertEquals(1.0, h.filter { it.group.startsWith("interior") }.sumOf { it.weight }, .01)
    }

    @Test fun mirroredPieceFromSymmetry() {
        val ps = listOf(wall(-1f, 0f, false), wall(1f, 0f, false), wall(-2f, 0f, false))
        val sym = Inference.infer(ps).filter { it.group.startsWith("simetria") }
        assertTrue("sem hipótese de simetria", sym.isNotEmpty())
        assertTrue(sym.any { s -> s.pieces.any { near(it.center, 2f, .5f, 0f) } })
    }

    @Test fun hypothesesNeverCrossRealPieces() {
        val obs = house(withBack = false)
        val bodies = obs.map { Collision.Body(it.id, it.shape, it.center, it.u, it.v, it.edge) }
        for (h in Inference.infer(obs)) for (p in h.pieces) {
            val b = Collision.Body(-1, p.shape, p.center, p.u, p.v, p.edge)
            for (o in bodies) assertTrue("${h.title} atravessa peça real", Collision.worstOverlap(listOf(o, b)) <= Catalog.THICKNESS * 1.4f)
        }
    }
}
