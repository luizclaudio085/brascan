package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfidenceMeasureTest {
    private val scan = Evidence(Evidence.Source.SCANNER, views = 8, spreadM = .004, shapeShare = .9f)

    @Test fun confidenceLevels() {
        assertEquals(Level.CINZA, Confidence.assess(null, 0.0, .075).level)
        assertEquals(Level.AMARELO, Confidence.assess(Evidence(Evidence.Source.FOTO, fitIoU = .9f, view = "frente"), 0.0, .075).level)
        assertEquals(Level.VERDE, Confidence.assess(Evidence(Evidence.Source.FOTO, fitIoU = .9f, accepted = true), 0.0, .075).level)
        assertEquals(Level.VERDE, Confidence.assess(scan, 0.0, .075).level)
        assertEquals(Level.AMARELO, Confidence.assess(scan.copy(views = 3), 0.0, .075).level)
        assertEquals(Level.VERMELHO, Confidence.assess(scan.copy(spreadM = .03), 0.0, .075).level)
        val pushed = Confidence.assess(scan, .03, .075)
        assertEquals(Level.VERMELHO, pushed.level)
        assertTrue(pushed.reasons.any { it.contains("atravessava") })
        // confirmar não apaga conflito
        assertEquals(Level.VERMELHO, Confidence.assess(scan.copy(accepted = true), .03, .075).level)
    }

    private fun wall(id: Int, x: Float) = Measure.P(id, "quadrado", "grande", V3(x, .5f, 0f), V3(1f, 0f, 0f), V3(0f, 1f, 0f), 1f, scan)

    @Test fun overallDimensionsOfTwoLargeSquares() {
        val m = Measure.overall(listOf(wall(1, 0f), wall(2, 1f))).associateBy { it.label }
        assertEquals(15.0, m.getValue("Largura").value, .01)
        assertEquals(7.5, m.getValue("Altura").value, .01)
        assertEquals(Catalog.THICKNESS * 7.5, m.getValue("Profundidade").value, .01)
        assertEquals(Measure.Kind.OBSERVADA, m.getValue("Largura").kind)
        assertEquals(.8, m.getValue("Largura").plusMinus!!, 1e-6) // 2 × 0,4 cm
        assertEquals(0.0, m.getValue("Inclinação máx. das paredes").value, .01)
    }

    @Test fun distanceBetweenTouchingPieces() {
        val d = Measure.between(wall(1, 0f), wall(2, 1f)).associateBy { it.label }
        assertEquals(7.5, d.getValue("Entre centros").value, .01)
        assertEquals(0.0, d.getValue("Folga (cantos mais próximos)").value, .01)
    }

    @Test fun frontPhotoDepthIsEstimatedNotMeasured() {
        val photo = Evidence(Evidence.Source.FOTO, fitIoU = .9f, view = "frente")
        val ps = listOf(Measure.P(1, "quadrado", "grande", V3(0f, .5f, 0f), V3(1f, 0f, 0f), V3(0f, 1f, 0f), 1f, photo))
        val m = Measure.overall(ps).associateBy { it.label }
        assertEquals(Measure.Kind.DERIVADA, m.getValue("Altura").kind)
        assertEquals(Measure.Kind.ESTIMADA, m.getValue("Profundidade").kind)
        assertEquals(null, m.getValue("Profundidade").plusMinus)
    }
}
