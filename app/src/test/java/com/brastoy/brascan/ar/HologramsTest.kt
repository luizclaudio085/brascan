package com.brastoy.brascan.ar

import com.brastoy.brascan.ar.Holograms.Det
import com.brastoy.brascan.ar.Holograms.MapItem
import com.brastoy.brascan.ar.Holograms.Memory
import com.brastoy.brascan.ar.Holograms.Policy
import com.brastoy.brascan.ar.Holograms.Why
import com.brastoy.brascan.three.V3
import com.brastoy.brascan.vision.Piece3D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escolha dos hologramas (o que o usuário vê na câmera): só o que tem evidência, sem piscar, uma vez por peça. */
class HologramsTest {
    private val RED = 0xFFE53950.toInt(); private val BLUE = 0xFF2F8CFF.toInt()

    private fun det(cx: Double, cy: Double, color: Int = RED, bin: Int = 0, outline: Int = 4, mapId: Int? = null, mapCount: Int = 0, area: Int = 900,
                    rms: Double = .5, frontal: Double = .8, tilt: Double = 3.0) =
        Det(outline, color, bin, cx, cy, 30.0, area, rms, frontal, false, tilt, mapId, mapCount)

    /** Um quadro como o app faz: escolhe e guarda o desenho de cada holograma ao vivo na memória. */
    private class Run(val p: Policy = Policy()) {
        var mem = Memory()
        fun step(dets: List<Det>, map: List<MapItem> = emptyList(), fresh: Boolean = true, lost: Boolean = false, moved: DoubleArray? = null): Holograms.Choice {
            val ch = Holograms.choose(dets, map, fresh, lost, mem, p, moved)
            mem = Holograms.remember(ch, ch.live.map { Piece3D("quadrado", "grande", dets[it.first].color, V3(0f, 0f, .4f), V3(1f, 0f, 0f), V3(0f, -1f, 0f), V3(0f, 0f, -1f), 0f, 0f, 1) })
            return ch
        }
    }

    @Test fun oneFrameDetectionIsNotAHologram() {
        val r = Run()
        assertTrue("1º quadro sem evidência não aparece", r.step(listOf(det(100.0, 100.0))).live.isEmpty())
        val ch = r.step(listOf(det(104.0, 98.0)))
        assertEquals("mesma peça na imagem no quadro seguinte aparece", listOf(0 to Why.REPETIDA), ch.live)
    }

    @Test fun somethingThatMovesAcrossTheImageIsNeverShown() {
        // mão/reflexo andando 40 px por quadro (mais que 0,6 × raio): nunca é a mesma peça parada
        val r = Run()
        for (i in 0 until 6) assertTrue("quadro $i", r.step(listOf(det(100.0 + 40 * i, 100.0))).live.isEmpty())
    }

    @Test fun differentColorOrOutlineAtTheSameSpotIsNotTheSamePiece() {
        val r = Run()
        r.step(listOf(det(100.0, 100.0, color = RED, bin = 0)))
        assertTrue(r.step(listOf(det(100.0, 100.0, color = BLUE, bin = 8))).live.isEmpty())
        assertTrue(r.step(listOf(det(100.0, 100.0, color = 0xFF2EC27E.toInt(), bin = 4, outline = 3))).live.isEmpty())
    }

    @Test fun trackSurvivesTwoMissedFrames() {
        val r = Run()
        r.step(listOf(det(100.0, 100.0)))
        r.step(emptyList()); r.step(emptyList())
        assertEquals(listOf(0 to Why.REPETIDA), r.step(listOf(det(102.0, 101.0))).live)
        // três quadros sem a peça: a trilha acabou, volta a precisar de 2 vistas
        val q = Run(); q.step(listOf(det(100.0, 100.0))); repeat(3) { q.step(emptyList()) }
        assertTrue(q.step(listOf(det(102.0, 101.0))).live.isEmpty())
    }

    @Test fun confirmedMapPieceShowsAtOnce() {
        val ch = Run().step(listOf(det(200.0, 150.0, mapId = 7, mapCount = 3)))
        assertEquals(listOf(0 to Why.CONFIRMADA), ch.live)
    }

    @Test fun sameMapPieceInTwoFramesShowsEvenIfItMovedInTheImage() {
        // o celular andou muito: na imagem a peça mudou de lugar, mas caiu na mesma peça do mapa (mesmo lugar do mundo)
        val r = Run()
        assertTrue(r.step(listOf(det(100.0, 100.0, mapId = 5, mapCount = 1))).live.isEmpty())
        assertEquals(listOf(0 to Why.SEGUIDA), r.step(listOf(det(260.0, 180.0, mapId = 5, mapCount = 2))).live)
        // outra peça nova do mapa no mesmo quadro não herda nada
        assertTrue(Run().step(listOf(det(260.0, 180.0, mapId = 6, mapCount = 1))).live.isEmpty())
    }

    @Test fun onePiecePerHologram() {
        // duas detecções na mesma peça do mapa (peça partida em duas regiões): uma só, a maior
        val ch = Run().step(listOf(det(100.0, 100.0, mapId = 3, mapCount = 4, area = 500), det(150.0, 100.0, mapId = 3, mapCount = 4, area = 1200)))
        assertEquals(listOf(1 to Why.CONFIRMADA), ch.live)
        // duas detecções no mesmo lugar da imagem (peças do mapa diferentes, cópia): uma só
        val ch2 = Run().step(listOf(det(100.0, 100.0, mapId = 3, mapCount = 4), det(105.0, 102.0, mapId = 9, mapCount = 3, area = 600)))
        assertEquals(1, ch2.live.size)
    }

    @Test fun hologramIsHeldOneFrameWhenTheDetectorMisses() {
        val r = Run()
        r.step(listOf(det(100.0, 100.0))); assertEquals(1, r.step(listOf(det(101.0, 100.0))).live.size)
        val miss = r.step(emptyList(), moved = doubleArrayOf(.005, 1.0))
        assertEquals("segura o do quadro anterior", 1, miss.held.size)
        assertTrue("só um quadro", r.step(emptyList(), moved = doubleArrayOf(.005, 1.0)).held.isEmpty())
    }

    @Test fun noHoldWhenTheCameraMovedOrSomethingIsInFront() {
        fun shown(): Run { val r = Run(); r.step(listOf(det(100.0, 100.0))); r.step(listOf(det(101.0, 100.0))); return r }
        // a câmera andou 6 cm: o holograma segurado descolaria da peça
        assertTrue(shown().step(emptyList(), moved = doubleArrayOf(.06, 3.0)).held.isEmpty())
        assertTrue(shown().step(emptyList(), moved = doubleArrayOf(.01, 15.0)).held.isEmpty())
        // outra coisa (azul) apareceu em cima: a peça está escondida, não desenha por cima
        assertTrue(shown().step(listOf(det(102.0, 101.0, color = BLUE, bin = 8))).held.isEmpty())
        // detectada de novo: é holograma ao vivo, não segurado
        val again = shown().step(listOf(det(102.0, 100.0)))
        assertTrue(again.held.isEmpty()); assertEquals(1, again.live.size)
    }

    @Test fun undetectedMapPiecesAreNotDrawnByDefault() {
        val item = listOf(MapItem(4, 4, RED, 0, 300.0, 200.0, 30.0))
        val r = Run(); r.step(listOf(det(300.0, 200.0, mapId = 4, mapCount = 5)), item)
        val off = r.step(emptyList(), item)
        assertTrue(off.mapIds.isEmpty()); assertEquals("no lugar dela fica o holograma ao vivo segurado", 1, off.held.size)
        // ligado (mapa bom): só a que foi detectada há pouco, com a câmera em dia e sem estar perdido (sem segurar o
        // holograma ao vivo, que senão ocupa o lugar dela: uma peça, um holograma)
        val on = Run(Policy(mapRecent = 1, hold = 0)); on.step(listOf(det(300.0, 200.0, mapId = 4, mapCount = 5)), item)
        assertEquals(listOf(4), on.step(emptyList(), item).mapIds)
        assertTrue("2 quadros depois já não", on.step(emptyList(), item).mapIds.isEmpty())
        val lost = Run(Policy(mapRecent = 1, hold = 0)); lost.step(listOf(det(300.0, 200.0, mapId = 4, mapCount = 5)), item)
        assertTrue(lost.step(emptyList(), item, lost = true).mapIds.isEmpty())
        val stale = Run(Policy(mapRecent = 4, hold = 0)); stale.step(listOf(det(300.0, 200.0, mapId = 4, mapCount = 5)), item)
        stale.step(emptyList(), item, fresh = false)
        assertTrue("câmera sem encaixar há 2 quadros", stale.step(emptyList(), item, fresh = false).mapIds.isEmpty())
        // outra peça vista agora em cima dela (escondida atrás): não desenha
        val hid = Run(Policy(mapRecent = 1, hold = 0)); hid.step(listOf(det(300.0, 200.0, mapId = 4, mapCount = 5)), item)
        assertTrue(hid.step(listOf(det(305.0, 200.0, color = BLUE, bin = 8, mapId = 8, mapCount = 3)), item).mapIds.isEmpty())
    }

    @Test fun strongIsOnlyForQuadsResting() {
        val p = Policy(strong = true)
        val quad = det(100.0, 100.0, area = 2500, rms = .4, frontal = .9, tilt = 2.0)
        assertEquals(listOf(0 to Why.FORTE), Run(p).step(listOf(quad)).live)
        // triângulo: 3 cantos sempre fecham com algum triângulo
        assertTrue(Run(p).step(listOf(det(100.0, 100.0, outline = 3, area = 2500, rms = .0, frontal = .9, tilt = 2.0))).live.isEmpty())
        // inclinada 30°: nem em pé nem deitada pela gravidade
        assertTrue(Run(p).step(listOf(det(100.0, 100.0, area = 2500, rms = .4, frontal = .9, tilt = 30.0))).live.isEmpty())
        // desligado por padrão
        assertTrue(Run().step(listOf(quad)).live.isEmpty())
    }
}
