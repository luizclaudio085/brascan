package com.brastoy.brascan.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Escala da foto estimada em conjunto com os 3 tamanhos (grande 1, médio 0,81, pequeno 0,5). */
class SizeScaleTest {
    private fun fit(vararg s: Float, abs: Float = 0f) = SizeScale.fit(s, FloatArray(s.size) { 1f }, abs)

    @Test fun threeSizesFixTheScale() {
        val u = 50f
        val r = floatArrayOf(1f, .81f, .5f, 1f, .81f, .5f, .81f, 1f)
        val noise = floatArrayOf(.02f, -.03f, .01f, -.02f, .015f, -.025f, 0f, .01f)
        val f = SizeScale.fit(FloatArray(r.size) { u * r[it] * (1 + noise[it]) }, FloatArray(r.size) { 1f })
        assertTrue("unidade ${f.unit}", abs(f.unit / u - 1) < .03f)
        assertEquals(listOf("grande", "medio", "pequeno", "grande", "medio", "pequeno", "medio", "grande"), f.sizes)
        assertFalse(f.ambiguous)
        assertTrue("confiança ${f.conf}", f.conf > .8f)
    }

    /** Duas turmas: a razão entre elas diz quais tamanhos são (0,81 = G+M, 0,62 = M+P, 0,5 = G+P). */
    @Test fun ratioBetweenGroupsDecides() {
        assertEquals(listOf("grande", "grande", "medio", "medio"), fit(50f, 50.5f, 40.5f, 40.2f).sizes)
        assertEquals(listOf("medio", "medio", "pequeno", "pequeno"), fit(40.5f, 41f, 25f, 25.3f).sizes)
        assertEquals(listOf("grande", "grande", "pequeno", "pequeno"), fit(50f, 49.4f, 25f, 25.2f).sizes)
    }

    /** Um tamanho só: ambígua, critério explícito "as maiores são as grandes", confiança baixa. */
    @Test fun singleSizeIsAmbiguous() {
        val f = fit(50f, 51f, 49.5f, 50.5f)
        assertTrue(f.ambiguous)
        assertEquals(List(4) { "grande" }, f.sizes)
        assertTrue("confiança ${f.conf}", f.conf < .5f)
    }

    /** Escala absoluta (altura da câmera) desfaz a ambiguidade. */
    @Test fun absoluteScaleResolvesAmbiguity() {
        val f = fit(50f, 51f, 49.5f, 50.5f, abs = 50f / .81f)
        assertEquals(List(4) { "medio" }, f.sizes)
        assertTrue(f.absolute)
        assertFalse(f.ambiguous)
    }

    /** Peça um pouco menor no meio de muitas grandes continua grande (a segmentação encolhe, não aumenta). */
    @Test fun slightlyShrunkPieceStaysLarge() {
        val f = fit(50f, 51f, 49f, 50.5f, 49.5f, 50f, 43.8f)
        assertEquals(List(7) { "grande" }, f.sizes)
    }

    /**
     * Inventário do simulador (código B8): a ameia (E) não existe no pequeno. Uma ameia que mede como pequena (dentes
     * cobertos pelos ímãs) vai para o médio, e as outras peças continuam com a escala delas.
     */
    @Test fun inventoryHasNoSmallE() {
        val s = floatArrayOf(50f, 50.5f, 40.5f, 49f, 27f)
        val shapes = listOf("quadrado", "quadrado", "quadrado", "porta", "E")
        assertEquals(listOf("grande", "grande", "medio", "grande", "pequeno"), SizeScale.fit(s, FloatArray(5) { 1f }).sizes)
        val f = SizeScale.fit(s, FloatArray(5) { 1f }, allowed = SizeScale.allowedFor(shapes))
        assertEquals(listOf("grande", "grande", "medio", "grande", "medio"), f.sizes)
        assertTrue("unidade ${f.unit}", abs(f.unit / 50f - 1) < .03f)
    }

    /** Medida com peso 0 (peça cortada pela borda) recebe tamanho mas não mexe na escala. */
    @Test fun zeroWeightDoesNotMoveScale() {
        val s = floatArrayOf(50f, 40.5f, 25f, 12f)
        val f = SizeScale.fit(s, floatArrayOf(1f, 1f, 1f, 0f))
        assertTrue("unidade ${f.unit}", abs(f.unit / 50f - 1) < .03f)
        assertEquals(listOf("grande", "medio", "pequeno", "pequeno"), f.sizes)
    }
}
