package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

/** Ranhuras dos ímãs: quadrado tem, encaixe não; nas fotos reais o croma acha a ranhura até no aro azul escuro. */
class MagnetsTest {
    private fun verdictOf(seg: Segmentation, b: Blob): Int {
        val q = Magnets.outline(b, 4)!!
        val unit = (0 until 4).map { (q[(it + 1) % 4] - q[it]).len() }.average().toFloat()
        return Magnets.verdict(Magnets.contrasts(Magnets.luminance(seg)!!, Magnets.chroma(seg)!!, q, unit))
    }

    @Test fun realSquaresHaveMagnets() {
        val (px, w, h) = RealPhotoProbe().load("tamanhos")
        val seg = Segmenter.segment(px, w, h)
        assertEquals(listOf(1, 1), seg.blobs.map { verdictOf(seg, it) })
    }

    /** Moldura azul escura (janelas.jpg): a ranhura é mais CLARA que o aro; pela luminância "não teria ímã". */
    @Test fun darkRealFrameHasMagnetsByChroma() {
        val (px, w, h) = RealPhotoProbe().load("janelas")
        val seg = Segmenter.segment(px, w, h)
        val blue = seg.blobs.first { it.hue in 200f..260f }
        assertEquals(1, verdictOf(seg, blue))
    }

    /** Encaixe (0,83 do grande, sem ímã) ao lado de um quadrado médio (0,81, com ímãs): só os ímãs separam os dois. */
    @Test fun insertVsMediumSquare() {
        val x = V3(1f, 0f, 0f); val z = V3(0f, 0f, -1f)
        val tiles = listOf(
            RealisticScene.Tile("encaixe", "encaixe", "medio", LooseBench.COLORS[1], V3(-.06f, .0025f, 0f), x, z),
            RealisticScene.Tile("quadrado", "quadrado", "medio", LooseBench.COLORS[3], V3(.06f, .0025f, .01f), x, z),
        )
        val f = RealisticScene.render(tiles, V3(.02f, .40f, .05f), V3(0f, 0f, 0f), Random(3), 480, 360, RealisticScene.Opts(noise = 3f, blur = 1))
        val got = PhotoPieces.from(Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60)).sortedBy { it.cx }.map { it.shape }
        assertEquals(listOf("encaixe", "quadrado"), got)
    }
}
