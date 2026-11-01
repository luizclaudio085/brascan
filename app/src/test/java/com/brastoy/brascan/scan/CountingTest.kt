package com.brastoy.brascan.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CountingTest {
    @Test fun uprightMatchesScannerConvention() {
        // 3×2: valor = índice. Mesma regra do scanner: 90° → (h-1-y, x), 270° → (y, w-1-x)
        val w = 3; val h = 2; val px = IntArray(w * h) { it }
        for (rot in listOf(0, 90, 180, 270)) {
            val (o, ow, oh) = Counting.upright(px, w, h, rot)
            assertEquals(w * h, o.size)
            for (y in 0 until h) for (x in 0 until w) {
                val (nx, ny) = when (rot) { 90 -> (h - 1 - y) to x; 180 -> (w - 1 - x) to (h - 1 - y); 270 -> y to (w - 1 - x); else -> x to y }
                assertEquals("rot $rot ($x,$y)", px[y * w + x], o[ny * ow + nx])
            }
            if (rot % 180 == 90) { assertEquals(h, ow); assertEquals(w, oh) }
        }
        // ida e volta: 90 depois 270 devolve a imagem
        val (a, aw, ah) = Counting.upright(px, w, h, 90)
        val (b, _, _) = Counting.upright(a, aw, ah, 270)
        assertArrayEquals(px, b)
    }

    @Test fun stabilizerIgnoresOneBadFrame() {
        val s = Counting.Stabilizer(5)
        repeat(4) { s.push(mapOf("grande/quadrado" to 3, "medio/janela" to 1)) }
        val r = s.push(mapOf("grande/quadrado" to 9)) // mão na frente / reflexo num quadro
        assertEquals(3, r["grande/quadrado"]); assertEquals(1, r["medio/janela"])
    }

    // ---- rastreio ao vivo (imagem 480×360; caixas em pixels convertidas para normalizadas) ----
    private val W = 480; private val H = 360
    private val Q = Inventory.Kind("grande", "quadrado"); private val T = Inventory.Kind("grande", "equilatero")
    private val R = Inventory.Kind("medio", "retangulo"); private val P = Inventory.Kind("pequeno", "quadrado")
    private val RED = 0xFFE53950.toInt(); private val BLUE = 0xFF2F8CFF.toInt(); private val GREEN = 0xFF2EC27E.toInt(); private val YELLOW = 0xFFFFC531.toInt()
    private fun f(x: Float, y: Float, side: Float, k: Inventory.Kind, color: Int, fit: Float = .9f) =
        Counting.Found(x / W, y / H, side / W, side / H, k, color, fit, false, side / W)
    /** 4 peças paradas: dois tamanhos (grande 60 px, médio 49, pequeno 30) e cores diferentes. */
    private fun scene() = listOf(f(100f, 100f, 60f, Q, RED), f(300f, 90f, 60f, T, BLUE), f(200f, 250f, 49f, R, GREEN), f(380f, 260f, 30f, P, YELLOW))

    @Test fun trackerHidesOneFrameGhostAndKeepsMissedPiece() {
        val tr = Counting.Tracker()
        for (i in 0 until 8) {
            var dets = scene()
            if (i == 3) dets = dets + f(240f, 170f, 40f, Q, RED) // reflexo num quadro só
            if (i == 5) dets = dets.drop(1) // detector perde uma peça num quadro (borrão)
            val shown = tr.push(dets, W, H)
            assertTrue("quadro $i: fantasma na tela", shown.none { kotlin.math.abs(it.cx * W - 240f) < 20 && kotlin.math.abs(it.cy * H - 170f) < 20 })
            if (i == 0) assertTrue("1º quadro ainda não confirma", shown.isEmpty())
            else {
                assertEquals("quadro $i", 4, shown.size)
                assertEquals("quadro $i", mapOf(Q.key to 1, T.key to 1, R.key to 1, P.key to 1), tr.counts)
            }
        }
    }

    @Test fun trackerFollowsSlowCameraMotion() {
        // a câmera anda 3,5 px e gira 1,5° por quadro em volta do centro: as peças continuam as mesmas, sem duplicar
        val tr = Counting.Tracker()
        val base = scene()
        for (i in 0 until 12) {
            val a = Math.toRadians(-1.5 * i).toFloat(); val c = kotlin.math.cos(a); val s = kotlin.math.sin(a)
            val dets = base.map { b ->
                val x = b.cx * W - W / 2f; val y = b.cy * H - H / 2f
                val nx = W / 2f + c * x - s * y + 2.5f * i; val ny = H / 2f + s * x + c * y + 2.5f * i
                f(nx, ny, b.side * W, b.kind, b.color)
            }
            val shown = tr.push(dets, W, H)
            if (i >= 1) { assertEquals("quadro $i", 4, shown.size); assertEquals("quadro $i", 4, tr.counts.values.sum()) }
            for (b in shown) assertTrue("caixa acompanha a peça", dets.any { kotlin.math.hypot((it.cx - b.cx) * W, (it.cy - b.cy) * H) < 6f })
        }
    }

    @Test fun trackerVotesShapeAcrossFrames() {
        val tr = Counting.Tracker()
        val wrong = Inventory.Kind("grande", "moldura")
        for (i in 0 until 9) {
            val k = if (i % 3 == 2) wrong else Q // o molde erra 1 quadro em 3
            val dets = listOf(f(150f, 150f, 60f, k, RED, fit = if (k == Q) .85f else .6f), f(330f, 200f, 60f, T, BLUE))
            tr.push(dets, W, H)
            if (i >= 1) { assertEquals("quadro $i", 1, tr.counts[Q.key]); assertEquals("quadro $i", null, tr.counts[wrong.key]) }
        }
    }

    @Test fun trackerIgnoresMovingHandAndKeepsCoveredPiece() {
        val tr = Counting.Tracker()
        for (i in 0 until 10) {
            val pieces = scene()
            // mão entrando pela esquerda a 30 px por quadro, cobrindo a primeira peça (x = 100) nos quadros 4 a 6
            val hx = 20f + 30f * (i - 2)
            val covered = i in 4..6
            val dets = (if (covered) pieces.drop(1) else pieces) + (if (i >= 2) listOf(f(hx, 105f, 80f, Q, 0xFFE0AC90.toInt(), fit = .5f)) else emptyList())
            val shown = tr.push(dets, W, H)
            assertTrue("quadro $i: mão na tela", shown.none { it.w * W > 70f })
            if (i >= 1) assertEquals("quadro $i: peça coberta saiu da conta", 4, tr.counts.values.sum())
        }
    }

    @Test fun unitOfUsesCatalogRatios() {
        fun near(e: Float, a: Float?) = assertTrue("esperado ~$e, deu $a", a != null && kotlin.math.abs(a / e - 1) < .03f)
        near(100f, Counting.unitOf(listOf(100f, 81f, 50f, 100f)))
        near(100f, Counting.unitOf(listOf(81f, 82f, 50f, 50f, 81f))) // sem nenhum grande: as razões 0,81/0,5 dizem médio e pequeno
        near(60f, Counting.unitOf(listOf(60f, 61f, 59f))) // um tamanho só: ambíguo, vale "é grande"
        near(100f, Counting.unitOf(listOf(100f, 101f, 81f, 50f, 140f))) // um lado medido errado não arrasta a escala
        assertEquals(null, Counting.unitOf(listOf(70f)))
    }

    @Test fun floorScaleFindsTiltedFloor() {
        // chão inclinado: o grande tem 80 px no centro e 80·(1 + 0,5·Δy/480) noutros pontos (câmera ~30° de lado)
        val rnd = java.util.Random(3)
        val ratios = floatArrayOf(1f, .81f, .5f)
        val pts = (0 until 12).map {
            val x = 40f + rnd.nextFloat() * 400; val y = 30f + rnd.nextFloat() * 300
            floatArrayOf(x, y, 80f * (1 + .5f * (y - 180) / 480) * ratios[it % 3] * (1 + (rnd.nextFloat() - .5f) * .04f))
        }
        val sc = Counting.floorScale(pts, W, H, tilt = true)!!
        for (p in pts) { val u = 80f * (1 + .5f * (p[1] - 180) / 480); assertTrue("escala ${sc.at(p[0], p[1])} × $u", kotlin.math.abs(sc.at(p[0], p[1]) / u - 1) < .05f) }
        val flat = Counting.floorScale(pts.take(4), W, H, tilt = true)!! // poucas peças: escala única
        assertEquals(0f, flat.gx); assertEquals(0f, flat.gy)
    }

    @Test fun kindOfFallsBackToExistingSize() {
        assertEquals(Inventory.Kind("medio", "encaixe"), Counting.kindOf("encaixe", "grande"))
        assertEquals(Inventory.Kind("medio", "E"), Counting.kindOf("E", "pequeno"))
        assertEquals(Inventory.Kind("pequeno", "L"), Counting.kindOf("L", "pequeno"))
        assertEquals(30, Inventory.KINDS.size)
        assertEquals(30, Inventory.KINDS.map { it.key }.toSet().size)
    }
}
