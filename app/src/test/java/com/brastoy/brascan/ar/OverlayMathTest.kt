package com.brastoy.brascan.ar

import com.brastoy.brascan.three.V3
import com.brastoy.brascan.vision.Intrinsics
import com.brastoy.brascan.vision.PieceSlam
import com.brastoy.brascan.vision.Pose
import com.brastoy.brascan.vision.Rot
import com.brastoy.brascan.vision.Segmenter
import com.brastoy.brascan.vision.SyntheticScene
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A sobreposição 3D (RA) tem que cair exatamente em cima da peça que a câmera vê. */
class OverlayMathTest {
    private val k = Intrinsics(412.0, 409.0, 243.5, 178.0)

    /** Matriz × ponto = mesmo pixel que o caminho dos contornos, nas 4 rotações da tela e com recorte FILL_CENTER. */
    @Test fun matrixMatchesTheOutlinePath() {
        val rnd = Random(7)
        for (rot in listOf(0, 90, 180, 270)) for (trial in 0 until 40) {
            val cam = Pose(Rot.exp(DoubleArray(3) { rnd.nextGaussian() * .6 }), DoubleArray(3) { rnd.nextGaussian() * .3 })
            // ponto 20–80 cm à frente da câmera, dentro do campo de visão
            val pc = doubleArrayOf(rnd.nextGaussian() * .15, rnd.nextGaussian() * .1, .2 + rnd.nextDouble() * .6)
            val pw = cam.apply(pc)
            val (sw, sh) = if (rot == 90 || rot == 270) 1080 to 2340 else 2340 to 1080
            val view = FloatArray(16); val proj = FloatArray(16)
            OverlayMath.viewMatrix(cam, view); OverlayMath.projection(k, 480, 360, rot, sw, sh, proj)
            val a = OverlayMath.toScreen(view, proj, pw, sw, sh)!!
            val b = OverlayMath.toScreenReference(cam, k, 480, 360, rot, pw, sw, sh)
            assertEquals("rot $rot x", b[0], a[0], .05); assertEquals("rot $rot y", b[1], a[1], .05)
            assertTrue("profundidade em [-1,1]", a[2] > -1 && a[2] < 1)
        }
    }

    /** Depois de rastrear a cena sintética, os cantos 3D das peças do mapa projetam em cima dos cantos detectados. */
    @Test fun mapPiecesProjectOntoTheDetections() {
        val kk = Intrinsics(390.0, 390.0, 240.0, 180.0)
        val slam = PieceSlam(kk); val rnd = Random(4); val target = V3(-.02f, .04f, .04f)
        var checked = 0; var worstAll = 0.0
        for (i in 0 until 24) {
            val a = i * .5 * 2 * Math.PI / 24
            val eye = V3((cos(a) * .40).toFloat(), .26f, (sin(a) * .40).toFloat())
            val f = SyntheticScene.render(eye, target, rnd, 480, 360)
            val fwd = (target - eye).norm(); val r = fwd.cross(V3.UP).norm(); val up = r.cross(fwd)
            val dets = slam.track(Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f), doubleArrayOf(V3.UP.dot(r).toDouble(), -V3.UP.dot(up).toDouble(), V3.UP.dot(fwd).toDouble()))
            if (i < 10) continue // mapa ainda nascendo
            val cam = slam.camera ?: continue
            val view = FloatArray(16); val proj = FloatArray(16)
            OverlayMath.viewMatrix(cam, view); OverlayMath.projection(kk, 480, 360, 0, 480, 360, proj)
            // só peças confirmadas e vistas neste quadro (as outras não têm detecção para comparar)
            for (mp in slam.map.filter { it.count >= 3 && it.lastSeen == slam.frames }) {
                val p = mp.toPiece3D()
                // o detector mede os cantos da parte colorida (sem a borda escura): mesmo molde do PnP
                val corners = slam.modelCorners(PieceSlam.outlineOf(p.shape), p.size).map { c -> p.center + p.u * c[0].toFloat() + p.v * c[1].toFloat() }
                val px = corners.mapNotNull { OverlayMath.toScreen(view, proj, doubleArrayOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()), 480, 360) }
                val det = dets.filter { it.bin == mp.bin && it.outline == mp.outline }.minByOrNull { d -> d.imgCorners.sumOf { c -> px.minOf { q -> hypot(q[0] - c[0], q[1] - c[1]) } } } ?: continue
                val worst = px.maxOf { q -> det.imgCorners.minOf { c -> hypot(q[0] - c[0], q[1] - c[1]) } }
                println("quadro $i ${p.shape} #%06X: pior canto %.1f px".format(p.color and 0xFFFFFF, worst))
                // triângulos: 3 cantos e espelho ambíguo → o mapa é menos preciso (≈2 cm); quadrados/retângulos ≈1 cm
                val limit = if (det.outline == 3) 25.0 else 14.0
                assertTrue("quadro $i ${p.shape}: sobreposição fora por %.1f px".format(worst), worst < limit)
                worstAll = kotlin.math.max(worstAll, worst); checked++
            }
        }
        println("$checked conferências, pior %.1f px".format(worstAll))
        assertTrue("conferiu só $checked", checked >= 8)
    }

    @Test fun uprightMatchesTheScreenCanvas() {
        // mesma conta que MarkerScanScreen usa para desenhar os contornos
        assertTrue(OverlayMath.upright(10.0, 20.0, 480, 360, 90).let { abs(it[0] - 340) < 1e-9 && abs(it[1] - 10) < 1e-9 })
        assertTrue(OverlayMath.upright(10.0, 20.0, 480, 360, 270).let { abs(it[0] - 20) < 1e-9 && abs(it[1] - 470) < 1e-9 })
    }
}
