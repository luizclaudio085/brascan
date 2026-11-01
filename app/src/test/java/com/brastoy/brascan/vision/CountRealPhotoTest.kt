package com.brastoy.brascan.vision

import com.brastoy.brascan.scan.Counting
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Contagem de inventário nas fotos reais (out/2026), com o gabarito conferido olhando a foto. A ameia incolor de
 * "todas" quase não aparece sobre o lençol claro: fica no gabarito (é uma peça de verdade), então o erro mínimo
 * esperado nessa foto é 1 enquanto ela não for vista.
 */
class CountRealPhotoTest {
    private val truth = mapOf(
        "todas" to mapOf(
            "grande/isoceles" to 1, "grande/quadrado" to 1, "grande/retangulo" to 1, "grande/porta" to 2, "grande/moldura" to 2,
            "grande/equilatero" to 1, "grande/janela" to 1, "grande/E" to 1, "grande/retanguloTri" to 1, "grande/L" to 1,
            "medio/encaixe" to 2,
        ),
        "janelas" to mapOf("grande/janela" to 1, "grande/moldura" to 1, "medio/encaixe" to 1),
        "tamanhos" to mapOf("grande/quadrado" to 1, "medio/quadrado" to 1),
    )

    /** Peças a mais ou a menos somando todos os tipos, dividido por 2 (uma troca de tipo conta 1). */
    private fun error(name: String): Int {
        val (px, w, h) = RealPhotoProbe().load(name)
        val got = Counting.tally(Counting.detect(px, w, h))
        val want = truth.getValue(name.removeSuffix("_640"))
        val err = (got.keys + want.keys).sumOf { abs((got[it] ?: 0) - (want[it] ?: 0)) }
        val diff = (got.keys + want.keys).filter { (got[it] ?: 0) != (want[it] ?: 0) }.sorted().joinToString { "$it: ${got[it] ?: 0} (certo ${want[it] ?: 0})" }
        println("$name ${w}x$h: ${got.values.sum()} contadas, ${want.values.sum()} de verdade, erro ${(err + 1) / 2}${if (diff.isEmpty()) "" else " — $diff"}")
        return (err + 1) / 2
    }

    @Test fun realPhotos() {
        val e = listOf("todas", "janelas", "tamanhos").associateWith(::error)
        // 400 px: o encaixe solto mede ~37 px, abaixo do mínimo do teste de ímãs (40 px), e sai quadrado médio; em 640 px
        // (a resolução do app, ver appResolution) ele é reconhecido e só a ameia incolor escapa
        assertTrue("todas: erro ${e["todas"]}", e.getValue("todas") <= 2)
        assertTrue("janelas: erro ${e["janelas"]}", e.getValue("janelas") == 0)
        assertTrue("tamanhos: erro ${e["tamanhos"]}", e.getValue("tamanhos") == 0)
    }

    /** Mesmo caminho do app: JPEG original reduzido para Counting.PHOTO_W (640 px, bilinear; arquivos *_640). */
    @Test fun appResolution() {
        val e = listOf("todas", "janelas", "tamanhos").associateWith { error(it + "_640") }
        println("na resolução do app: $e")
        assertTrue("todas: erro ${e["todas"]}", e.getValue("todas") <= 1)
        assertTrue("janelas: erro ${e["janelas"]}", e.getValue("janelas") == 0)
        assertTrue("tamanhos: erro ${e["tamanhos"]}", e.getValue("tamanhos") == 0)
    }
}
