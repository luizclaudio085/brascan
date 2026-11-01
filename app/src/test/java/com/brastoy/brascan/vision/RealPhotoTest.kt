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
    /** O detector de peças soltas (PhotoPieces.detect: segmentação de peças soltas, o da contagem). */
    private fun loose(name: String) = RealPhotoProbe().load(name).let { (px, w, h) -> PhotoPieces.detect(px, w, h) }.map { "${it.shape}/${it.size}" }.sorted()

    @Test fun windowsAndInsert() = assertEquals(listOf("encaixe/medio", "janela/grande", "moldura/grande"), summary("janelas"))

    @Test fun largeAndMediumSquare() = assertEquals(listOf("quadrado/grande", "quadrado/medio"), summary("tamanhos"))

    @Test fun allLargePieces() = assertEquals(
        listOf(
            "L/grande", "encaixe/medio", "equilatero/grande", "isoceles/grande", "janela/grande", "moldura/grande", "moldura/grande",
            "porta/grande", "porta/grande", "quadrado/grande", "quadrado/grande", "retanguloTri/grande", "retangulo/grande",
        ).sorted(),
        summary("todas"),
    )

    /**
     * PhotoPieces.detect na resolução em que o app analisa a foto do inventário (Counting.PHOTO_W = 640 px; arquivos
     * *_640), contra o gabarito conferido olhando a foto (o mesmo do CountRealPhotoTest, sem a ameia incolor): em
     * "todas" a peça rosa avulsa é um ENCAIXE (0,83 do grande, sem ímãs), e não quadrado/grande como na lista antiga
     * de 400 px. Em 640 px os lados passam de 40 px e as ranhuras dos ímãs aparecem (Magnets.minUnitPx); com os ímãs
     * colados nas outras peças ela mede 0,8 da unidade, o tamanho do médio.
     */
    @Test fun looseDetectorAtAppResolution() {
        assertEquals(listOf("encaixe/medio", "janela/grande", "moldura/grande"), loose("janelas_640"))
        assertEquals(listOf("quadrado/grande", "quadrado/medio"), loose("tamanhos_640"))
        assertEquals(
            listOf(
                "L/grande", "encaixe/medio", "encaixe/medio", "equilatero/grande", "isoceles/grande", "janela/grande", "moldura/grande", "moldura/grande",
                "porta/grande", "porta/grande", "quadrado/grande", "retanguloTri/grande", "retangulo/grande",
            ).sorted(),
            loose("todas_640"),
        )
        // em 400 px "janelas" e "tamanhos" dão a mesma lista do caminho antigo
        assertEquals(listOf("encaixe/medio", "janela/grande", "moldura/grande"), loose("janelas"))
        assertEquals(listOf("quadrado/grande", "quadrado/medio"), loose("tamanhos"))
    }
}
