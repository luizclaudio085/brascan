package com.brastoy.brascan.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fotos reais das peças (out/2026, carpete bege). A ameia transparente não aparece em nenhuma — plástico
 * incolor sobre fundo claro não tem cor para segmentar.
 */
class RealPhotoTest {
    private fun pieces(name: String) = PhotoPieces.from(RealPhotoProbe().load(name).let { (px, w, h) -> Segmenter.segment(px, w, h) })
    private fun summary(name: String) = pieces(name).map { "${it.shape}/${it.size}" }.sorted()

    @Test fun windowsAndInsert() = assertEquals(listOf("encaixe/medio", "janela/grande", "moldura/grande"), summary("janelas"))

    @Test fun largeAndMediumSquare() = assertEquals(listOf("quadrado/grande", "quadrado/medio"), summary("tamanhos"))

    @Test fun allLargePieces() = assertEquals(
        listOf(
            "L/grande", "encaixe/medio", "equilatero/grande", "isoceles/grande", "janela/grande", "moldura/grande", "moldura/grande",
            "porta/grande", "porta/grande", "quadrado/grande", "quadrado/grande", "retanguloTri/grande", "retangulo/grande",
        ).sorted(),
        summary("todas"),
    )
}
