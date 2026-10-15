package com.brastoy.brascan.vision

/**
 * Catálogo das peças Brastoy. Medidas em metros (lado da peça), ainda estimadas:
 * trocar aqui quando chegarem as fotos/medidas reais — scanner, 3D e JSON usam só esta tabela.
 */
object Catalog {
    val SHAPES = listOf("quadrado", "retangulo", "equilatero", "retanguloTri", "janela", "L", "E")
    val LABEL = mapOf("quadrado" to "Quadrado", "retangulo" to "Retângulo", "equilatero" to "Triângulo", "retanguloTri" to "Tri. retângulo", "janela" to "Janela", "L" to "L", "E" to "E")
    val SIZES = listOf("pequeno", "medio", "grande")

    /** Lado de referência de cada tamanho (m). Retângulo = 2 × lado por 1 × lado. */
    val EDGE_M = mapOf("pequeno" to .05f, "medio" to .075f, "grande" to .10f)
    const val THICKNESS_M = .006f
    /** Borda escura/transparente em volta da parte colorida (não aparece na segmentação). */
    const val FRAME_M = .003f

    /** Tamanho mais próximo para um lado medido (m). */
    fun sizeFor(edgeM: Float): String = EDGE_M.minBy { kotlin.math.abs(it.value - edgeM) }.key
}
