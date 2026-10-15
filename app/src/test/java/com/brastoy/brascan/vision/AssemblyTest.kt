package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssemblyTest {
    private val X = V3(1f, 0f, 0f); private val Y = V3(0f, 1f, 0f); private val Z = V3(0f, 0f, 1f)
    private var id = 1
    private val colors = HashMap<Int, Int>()
    private fun piece(shape: String, c: V3, u: V3, v: V3, color: Int) = Measure.P(id++, shape, "grande", c, u, v, 1f, null).also { colors[it.id] = color }
    private val red = 0xFFE53950.toInt(); private val blue = 0xFF2F8CFF.toInt(); private val green = 0xFF2EC27E.toInt()

    /** Casinha: 4 paredes no chão, 2 paredes em cima, 2 triângulos de telhado e um piso. */
    private fun house(): List<Measure.P> {
        val t = .866f
        return listOf(
            piece("quadrado", V3(0f, .5f, .5f), X, Y, red), piece("quadrado", V3(0f, .5f, -.5f), X, Y, red),
            piece("quadrado", V3(-.5f, .5f, 0f), Z, Y, blue), piece("quadrado", V3(.5f, .5f, 0f), Z, Y, blue),
            piece("quadrado", V3(0f, 1.5f, .5f), X, Y, green), piece("quadrado", V3(0f, 1.5f, -.5f), X, Y, green),
            piece("equilatero", V3(0f, 2f + t / 3, .5f), X, Y, red), piece("equilatero", V3(0f, 2f + t / 3, -.5f), X, Y, red),
            piece("quadrado", V3(0f, .0325f, 0f), X, Z.let { V3(0f, 0f, -1f) }, blue),
        )
    }

    @Test fun nothingIsPlacedBeforeWhatHoldsIt() {
        val ps = house(); val plan = Assembly.plan(ps, colors)
        assertEquals(ps.size, plan.steps.size)
        val order = plan.steps.map { it.pieceId }
        val byId = ps.associateBy { it.id }
        fun idx(i: Int) = order.indexOf(i)
        // paredes de cima depois das de baixo; telhado depois das paredes de cima
        assertTrue(idx(5) > idx(1) && idx(6) > idx(2))
        assertTrue(idx(7) > idx(5) && idx(8) > idx(6))
        plan.steps.forEachIndexed { n, s -> println("${n + 1}. ${s.text} ${s.note}") }
        assertTrue(plan.steps.all { it.note.isEmpty() })
        // a partir do 2º passo, cada peça encosta em algo já montado (montagem contínua)
        assertTrue(plan.steps.drop(1).all { it.text.contains("encostado") || it.text.contains("em cima") })
        assertTrue(byId.getValue(order.first()).n.y.let { kotlin.math.abs(it) > .8f }) // começa pelo piso
        assertTrue(byId.getValue(order.last()).shape == "equilatero")
    }

    @Test fun billOfMaterialsCountsByKind() {
        val plan = Assembly.plan(house(), colors)
        val bom = plan.bom.associate { it.label to it.count }
        println(bom)
        assertEquals(2, bom["quadrado vermelho grande"])
        assertEquals(3, bom["quadrado ${Assembly.colorName(blue)} grande"])
        assertEquals(2, bom["triângulo vermelho grande"])
        assertEquals(9, plan.bom.sumOf { it.count })
    }

    @Test fun floatingPieceGoesLastWithWarning() {
        val ps = listOf(piece("quadrado", V3(0f, .5f, 0f), X, Y, red), piece("quadrado", V3(3f, 1.5f, 0f), X, Y, blue))
        val plan = Assembly.plan(ps, colors)
        assertEquals(ps[1].id, plan.steps.last().pieceId)
        assertTrue(plan.steps.last().note.contains("hipóteses"))
    }
}
