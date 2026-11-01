package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sonda (não é regressão): tempo por componente do detector de peças soltas e do Segmenter do scanner, com os laços
 * paralelos ligados (Par, padrão) e numa thread só (Par.enabled = false, o pior caso de um celular ocupado). Aquece o
 * JIT e dá a média e o mínimo de várias repetições nas mesmas entradas.
 *   ./gradlew :app:testDebugUnitTest --tests 'com.brastoy.brascan.vision.VelocidadeProbe' -Dreps=8
 * Cena solta 480×360 (LooseBench, 8 fáceis + 8 difíceis): segmentação de peças soltas, análise (PhotoPieces.analyze)
 * sem e com gravidade e o detect inteiro; fotos de 640 px (a resolução do app); Segmenter padrão do scanner 3D.
 */
class VelocidadeProbe {
    @Test fun probe() {
        val reps = (System.getProperty("reps") ?: "6").toInt()
        val bench = LooseBench.frames(1..8, 12, false) + LooseBench.frames(1..8, 12, true)
        val cams = bench.map { (_, f) -> GroundCam.fromFrame(f) }
        val photos = listOf("todas_640", "janelas_640", "tamanhos_640").map { RealPhotoProbe().load(it) }
        val rnd = Random(11); val tiles = RealisticScene.house(); val target = V3(0f, .05f, 0f)
        val scan = (0 until 12).map { i ->
            val a = i * 2 * Math.PI / 12
            val eye = V3((cos(a) * .42f).toFloat(), .28f + (.03 * sin(i * .9)).toFloat(), (sin(a) * .42f).toFloat())
            RealisticScene.render(tiles, eye, target, rnd, 480, 360, RealisticScene.Opts(noise = 4f, blur = 2, carpet = true, shadow = true))
        }
        val segs = bench.map { (_, f) -> Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = true) }
        val pSegs = photos.map { (px, w, h) -> Segmenter.segment(px, w, h, maxBlobs = 60, loose = true) }
        val names = listOf(
            "cena solta: segmentação loose", "cena solta: analyze sem gravidade", "cena solta: analyze com gravidade",
            "cena solta: detect sem gravidade", "cena solta: detect com gravidade",
            "foto 640: segmentação loose", "foto 640: analyze", "foto 640: detect", "scanner: Segmenter padrão",
        )
        fun once(): DoubleArray {
            fun t(n: Int, block: () -> Unit): Double { val t0 = System.nanoTime(); block(); return (System.nanoTime() - t0) / 1e6 / n }
            return doubleArrayOf(
                t(bench.size) { for ((_, f) in bench) Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = true) },
                t(bench.size) { for (s in segs) PhotoPieces.analyze(s) },
                t(bench.size) { for (k in segs.indices) PhotoPieces.analyze(segs[k], cams[k]) },
                t(bench.size) { for ((_, f) in bench) PhotoPieces.detect(f.rgb, f.w, f.h, maxBlobs = 60) },
                t(bench.size) { for ((k, sf) in bench.withIndex()) PhotoPieces.detect(sf.second.rgb, sf.second.w, sf.second.h, maxBlobs = 60, cam = cams[k]) },
                t(photos.size) { for ((px, w, h) in photos) Segmenter.segment(px, w, h, maxBlobs = 60, loose = true) },
                t(photos.size) { for (s in pSegs) PhotoPieces.analyze(s) },
                t(photos.size) { for ((px, w, h) in photos) PhotoPieces.detect(px, w, h, maxBlobs = 60) },
                t(scan.size) { for (f in scan) Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f) },
            )
        }
        val sb = StringBuilder("${bench.size} cenas 480x360, ${photos.size} fotos de 640 px, ${scan.size} quadros do scanner; $reps repetições (média / mínimo, ms por quadro)\n")
        for (par in listOf(true, false)) {
            Par.enabled = par
            repeat(3) { once() }
            val runs = (0 until reps).map { once() }
            sb.append(if (par) "[paralelo, ${Runtime.getRuntime().availableProcessors()} núcleos]\n" else "[1 thread]\n")
            for (i in names.indices) sb.append("  %-36s %7.1f / %7.1f\n".format(names[i], runs.map { it[i] }.average(), runs.minOf { it[i] }))
        }
        Par.enabled = true
        println(sb)
    }
}
