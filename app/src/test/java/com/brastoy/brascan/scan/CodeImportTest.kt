package com.brastoy.brascan.scan

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Construção vinda de um código do simulador (decodificada pelo codec real em tools/, gravada em
 * test/resources/codigos): entra no 3D exatamente como estava e sai de novo sem mexer nada.
 */
class CodeImportTest {
    private fun load(name: String): List<SimCodec.Item> {
        val text = File("src/test/resources/codigos/$name.json").readText()
        val re = Regex("""\{"type":"(\w+)","size":"(\w+)","color":"#([0-9a-fA-F]{6})","pos":\[([^\]]+)],"rot":\[([^\]]+)]}""")
        return re.findAll(text).map { m ->
            val p = m.groupValues[4].split(',').map { it.toFloat() }
            SimCodec.Item(m.groupValues[1], m.groupValues[2], Integer.parseInt(m.groupValues[3], 16) or (0xFF shl 24), V3(p[0], p[1], p[2]),
                m.groupValues[5].split(',').map { it.toDouble() }.toDoubleArray())
        }.toList()
    }

    private fun check(name: String, expected: Int) {
        val items = load(name)
        assertEquals(expected, items.size)
        val placed = Layout.place(SimCodec.pieces(items, 1), Vista.FRENTE, 1f)
        var maxPos = 0f; var maxRot = 0.0
        for ((it, pl) in items.zip(placed)) {
            maxPos = maxOf(maxPos, (pl.center - it.pos).len())
            val q = Layout.quat(pl.u, pl.v, pl.n)
            maxRot = maxOf(maxRot, 1 - abs(q.indices.sumOf { k -> q[k] * it.rot[k] }))
        }
        println("$name: ${items.size} peças, erro máx. posição $maxPos, rotação (1-|q·q'|) $maxRot")
        assertTrue("posição mudou ao importar: $maxPos", maxPos < 1e-4f)
        assertTrue("giro mudou ao importar: $maxRot", maxRot < 1e-5)
        // o simulador já garante que nada se atravessa: a colisão do app não deve empurrar nada
        val solved = Layout.solve(placed, -1)
        val pushed = solved.maxOf { it.pushed }
        val ready = Layout.exportReady(solved)
        val moved = ready.zip(placed).maxOf { (a, b) -> (a.center - b.center).len() }
        println("  empurrão na tela $pushed, deslocamento ao reexportar $moved (resolução do código ${1f / 256})")
        assertTrue("a colisão empurrou peças de um código válido: $pushed", pushed < 1f / 256)
        assertTrue("reexportar mexeu nas peças: $moved", moved < 1f / 256)
    }

    @Test fun castelo() = check("castelo", 1221)
    @Test fun exemplo301() = check("exemplo301", 301)
}
