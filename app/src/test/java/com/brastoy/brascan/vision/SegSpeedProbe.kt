package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sonda (não é regressão): tempo por componente, com aquecimento do JIT e média de várias repetições.
 *   ./gradlew :app:testDebugUnitTest --tests 'com.brastoy.brascan.vision.SegSpeedProbe'
 * Cena solta 480×360 (LooseBench fácil e difícil): segmentação de peças soltas, análise (PhotoPieces.analyze) sem e
 * com gravidade, total; foto 640 px (a resolução do app); segmentação padrão do scanner 3D na cena realista.
 */
class SegSpeedProbe {
    private inline fun ms(reps: Int, block: () -> Unit): Double { val t0 = System.nanoTime(); repeat(reps) { block() }; return (System.nanoTime() - t0) / 1e6 / reps }

    @Test fun probe() {
        val reps = (System.getProperty("reps") ?: "5").toInt()
        val bench = LooseBench.frames(1..8, 12, false) + LooseBench.frames(1..8, 12, true)
        val photos = listOf("todas_640", "janelas_640", "tamanhos_640").map { RealPhotoProbe().load(it) }
        val rnd = Random(11); val tiles = RealisticScene.house(); val target = V3(0f, .05f, 0f)
        val scan = (0 until 12).map { i ->
            val a = i * 2 * Math.PI / 12
            val eye = V3((cos(a) * .42f).toFloat(), .28f + (.03 * sin(i * .9)).toFloat(), (sin(a) * .42f).toFloat())
            RealisticScene.render(tiles, eye, target, rnd, 480, 360, RealisticScene.Opts(noise = 4f, blur = 2, carpet = true, shadow = true))
        }
        fun round(print: Boolean) {
            val r = if (print) reps else 1
            val segs = bench.map { (_, f) -> Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = true) }
            val segLoose = ms(r) { for ((_, f) in bench) Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = true) } / bench.size
            val anaFree = ms(r) { for (s in segs) PhotoPieces.analyze(s) } / bench.size
            val anaGrav = ms(r) { bench.forEachIndexed { k, (_, f) -> PhotoPieces.analyze(segs[k], GroundCam.fromFrame(f)) } } / bench.size
            val totFree = ms(r) { for ((_, f) in bench) PhotoPieces.detect(f.rgb, f.w, f.h, maxBlobs = 60) } / bench.size
            val totGrav = ms(r) { for ((_, f) in bench) PhotoPieces.detect(f.rgb, f.w, f.h, maxBlobs = 60, cam = GroundCam.fromFrame(f)) } / bench.size
            val pSegs = photos.map { (px, w, h) -> Segmenter.segment(px, w, h, maxBlobs = 60, loose = true) }
            val pSeg = ms(r) { for ((px, w, h) in photos) Segmenter.segment(px, w, h, maxBlobs = 60, loose = true) } / photos.size
            val pAna = ms(r) { for (s in pSegs) PhotoPieces.analyze(s) } / photos.size
            val pTot = ms(r) { for ((px, w, h) in photos) PhotoPieces.detect(px, w, h, maxBlobs = 60) } / photos.size
            val sDef = ms(r) { for (f in scan) Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f) } / scan.size
            if (print) {
                println("cena solta 480x360 (${bench.size} quadros, $reps rep.): segmentação loose %.1f ms | análise sem gravidade %.1f ms, com gravidade %.1f ms | total sem gravidade %.1f ms, com gravidade %.1f ms"
                    .format(segLoose, anaFree, anaGrav, totFree, totGrav))
                println("foto 640 px (${photos.size} fotos): segmentação loose %.1f ms | análise %.1f ms | total %.1f ms".format(pSeg, pAna, pTot))
                println("scanner 480x360 (${scan.size} quadros): Segmenter.segment padrão %.1f ms".format(sDef))
            }
        }
        round(false); round(false); round(false)
        round(true)
    }
}
