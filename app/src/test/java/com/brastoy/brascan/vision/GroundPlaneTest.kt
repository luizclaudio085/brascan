package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Retificação do chão: com a câmera inclinada a peça longe parece menor e o quadrado vira trapézio; levando o
 * contorno ao chão (gravidade dada, ou inclinação estimada pelas peças) os lados voltam a ser comparáveis.
 */
class GroundPlaneTest {
    /** Quadrados grandes, médios e pequenos espalhados de perto a longe, câmera inclinada [tiltDeg]. */
    private fun scene(tiltDeg: Double): Pair<FrameSample, List<RealisticScene.Tile>> {
        val tiles = ArrayList<RealisticScene.Tile>()
        val sizes = listOf("grande", "medio", "pequeno", "grande", "medio", "pequeno", "grande", "medio")
        val pos = listOf(-.15f to -.10f, 0f to -.11f, .15f to -.10f, -.14f to .02f, .01f to .01f, .15f to .02f, -.08f to .12f, .09f to .12f)
        for ((i, s) in sizes.withIndex()) {
            val a = .3f * i
            val u = V3(cos(a), 0f, sin(a)); val v = V3(0f, 1f, 0f).cross(u)
            tiles += RealisticScene.Tile("$s $i", "quadrado", s, LooseBench.COLORS[i % LooseBench.COLORS.size], V3(pos[i].first, .0025f, pos[i].second), u, v)
        }
        val t = Math.toRadians(tiltDeg).toFloat(); val dist = .45f
        val eye = V3(0f, cos(t) * dist, sin(t) * dist)
        val f = RealisticScene.render(tiles, eye, V3(0f, 0f, 0f), Random(7), 480, 360, RealisticScene.Opts(noise = 1f, carpet = false, shadow = false))
        return f to tiles
    }

    @Test fun frameGravityConvention() {
        val (f, _) = scene(30.0)
        val r = GroundRect(GroundCam.fromFrame(f), GroundCam.fromFrame(f).down!!)
        assertEquals(30f, r.tiltDeg, .5f)
        // câmera olhando reto para baixo: gravidade = eixo óptico
        val top = GroundCam(400f, 400f, 240f, 180f, GroundRect.down(0f, 0f))
        assertEquals(0f, GroundRect(top, top.down!!).tiltDeg, 1e-3f)
    }

    @Test fun rectificationEqualizesSidesAndSizes() {
        val (f, tiles) = scene(35.0)
        val seg = Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60)
        val cam = GroundCam.fromFrame(f, heightM = .45f * cos(Math.toRadians(35.0)).toFloat())
        val rect = GroundRect(cam, cam.down!!)
        // lado retificado / lado verdadeiro é o mesmo para todas (unidade = px à distância da altura); na imagem não
        val eye = f.camToWorld.let { V3(it[12], it[13], it[14]) }
        fun truthAt(x: Float, y: Float) = tiles.minBy { val p = RealisticScene.project(eye, V3(0f, 0f, 0f), it.c, f.w, f.h)!!; (P2(p[0].toFloat(), p[1].toFloat()) - P2(x, y)).len() }
        // (medido como quadrado: aqui só interessa o tamanho, não a classificação da forma)
        val kept = PhotoPieces.analyze(seg, cam).pieces.map { p -> seg.blobs.first { it.minX + it.bw / 2f == p.cx && it.minY + it.bh / 2f == p.cy } }
        assertEquals(8, kept.size)
        val rat = ArrayList<Float>(); val img = ArrayList<Float>()
        for (b in kept) {
            val hull = Geo2.hull(b.mask.edgePoints().map { P2(it.x + b.minX, it.y + b.minY) })
            val k = Catalog.SCALE.getValue(truthAt(b.minX + b.bw / 2f, b.minY + b.bh / 2f).size)
            rat += PhotoPieces.measure(Geo2.hull(rect.mapAll(hull)!!), "quadrado") / k
            img += PhotoPieces.measure(hull, "quadrado") / k
        }
        assertTrue("retificado: $rat", rat.max() / rat.min() < 1.08f)
        assertTrue("imagem: $img", img.max() / img.min() > 1.15f)
        // com gravidade (e com altura) os tamanhos saem todos certos
        fun wrong(ps: List<PhotoPiece>) = ps.filter { it.size != truthAt(it.cx, it.cy).size }.map { "${truthAt(it.cx, it.cy).size}→${it.size}" }
        assertEquals(emptyList<String>(), wrong(PhotoPieces.from(seg, GroundCam.fromFrame(f))))
        assertEquals(emptyList<String>(), wrong(PhotoPieces.from(seg, cam)))
        // sem gravidade: inclinação estimada pelas próprias peças (foco do palpite ≠ verdadeiro)
        val est = PhotoPieces.analyze(seg)
        assertTrue("inclinação estimada ${est.tiltDeg}", abs(est.tiltDeg - 35f) < 6f)
        assertEquals(emptyList<String>(), wrong(est.pieces))
        assertEquals(8, est.pieces.size)
    }
}
