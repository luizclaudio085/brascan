package com.brastoy.brascan.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test fun kindOfFallsBackToExistingSize() {
        assertEquals(Inventory.Kind("medio", "encaixe"), Counting.kindOf("encaixe", "grande"))
        assertEquals(Inventory.Kind("medio", "E"), Counting.kindOf("E", "pequeno"))
        assertEquals(Inventory.Kind("pequeno", "L"), Counting.kindOf("L", "pequeno"))
        assertEquals(30, Inventory.KINDS.size)
        assertEquals(30, Inventory.KINDS.map { it.key }.toSet().size)
    }
}
