package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sonda (não é regressão): tempo por componente do detector de peças soltas, com foco em escala e inclinação
 * (GroundEstimate, SizeScale, FloorView e o fluxo de PhotoPieces.analyze), sem e com gravidade.
 *   ./gradlew :app:testDebugUnitTest --tests 'com.brastoy.brascan.vision.EscalaSpeedProbe'
 * Aquece o JIT e tira a média de várias repetições nas mesmas entradas, em paralelo (padrão) e numa thread só
 * (Par.enabled = false). Também amostra a pilha da thread que chama durante analyze (perfil grosseiro por função).
 */
class EscalaSpeedProbe {
    private inline fun avgMs(reps: Int, block: () -> Unit): Double {
        val t0 = System.nanoTime(); repeat(reps) { block() }; return (System.nanoTime() - t0) / 1e6 / reps
    }

    @Test fun probe() {
        val frames = LooseBench.frames(1..8, 12, false) + LooseBench.frames(1..8, 12, true)
        val segs = frames.map { (_, f) -> Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = true) }
        val cams = frames.map { (_, f) -> GroundCam.fromFrame(f) }
        val probe = RealPhotoProbe()
        val photos = listOf("todas_640", "janelas_640", "tamanhos_640").map { n -> n to probe.load(n) }
        val photoSegs = photos.map { (_, p) -> Segmenter.segment(p.first, p.second, p.third, maxBlobs = 60, loose = true) }
        val tiles = RealisticScene.house(); val rnd = Random(11)
        val sc = (0 until 8).map { i ->
            val a = i * 2 * Math.PI / 8
            RealisticScene.render(tiles, V3((cos(a) * .42).toFloat(), .28f, (sin(a) * .42).toFloat()), V3(0f, .05f, 0f), rnd, 480, 360, RealisticScene.Opts(noise = 4f, blur = 2))
        }
        val reps = 5
        for (par in listOf(true, false)) {
            Par.enabled = par
            // aquecimento
            repeat(3) { for (i in frames.indices) { PhotoPieces.analyze(segs[i]); PhotoPieces.analyze(segs[i], cams[i]) }; for (s in photoSegs) PhotoPieces.analyze(s) }
            val seg = avgMs(reps) { for ((_, f) in frames) Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = true) } / frames.size
            val sem = avgMs(reps) { for (s in segs) PhotoPieces.analyze(s) } / frames.size
            val com = avgMs(reps) { for (i in segs.indices) PhotoPieces.analyze(segs[i], cams[i]) } / frames.size
            // partes (pelas chaves): sem estimar a inclinação; estimando só para o tamanho (sem refazer a forma)
            PhotoPieces.estimateTilt = false
            val noTilt = avgMs(reps) { for (s in segs) PhotoPieces.analyze(s) } / frames.size
            PhotoPieces.estimateTilt = true; PhotoPieces.autoFloor = false
            val sizeOnly = avgMs(reps) { for (s in segs) PhotoPieces.analyze(s) } / frames.size
            PhotoPieces.autoFloor = true
            val mode = if (par) "PARALELO" else "1 THREAD"
            println("[$mode] cena solta 480x360 (16 cenas): segmentação loose %.1f ms".format(seg))
            println("[$mode]   analyze sem gravidade %.1f ms | com gravidade %.1f ms".format(sem, com))
            println("[$mode]   sem gravidade: sem estimar %.1f ms, estimativa (GroundEstimate) ≈ %.1f ms, refazer a forma ≈ %.1f ms".format(noTilt, sizeOnly - noTilt, sem - sizeOnly))
            println("[$mode]   total detect sem gravidade %.1f ms | com gravidade %.1f ms".format(seg + sem, seg + com))
            for ((k, np) in photos.withIndex()) {
                val (n, p) = np
                val ts = avgMs(reps) { Segmenter.segment(p.first, p.second, p.third, maxBlobs = 60, loose = true) }
                val ta = avgMs(reps) { PhotoPieces.analyze(photoSegs[k]) }
                println("[$mode] foto $n ${p.second}x${p.third}: segmentação %.1f ms, analyze %.1f ms, total %.1f ms".format(ts, ta, ts + ta))
            }
        }
        Par.enabled = true
        repeat(3) { for (f in sc) Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f) }
        val tsc = avgMs(reps) { for (f in sc) Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f) } / sc.size
        println("scanner 480x360: Segmenter.segment padrão %.1f ms".format(tsc))

        // estimativa da inclinação sozinha: as mesmas peças que o analyze usa (formas do analyze, IoU 1)
        val estItems = segs.indices.map { i ->
            val r = PhotoPieces.analyze(segs[i])
            r.pieces.filter { it.shape != "encaixe" }.map { p ->
                val b = PhotoPieces.pieceBlobs(segs[i]).minBy { kotlin.math.abs(it.minX + it.bw / 2f - p.cx) + kotlin.math.abs(it.minY + it.bh / 2f - p.cy) }
                GroundEstimate.Item(Geo2.hull(b.mask.edgePoints().map { P2(it.x + b.minX, it.y + b.minY) }), p.shape, 1f)
            }
        }
        for (par in listOf(true, false)) {
            Par.enabled = par
            repeat(3) { for (i in segs.indices) GroundEstimate.estimate(GroundCam.guess(480, 360), estItems[i], true) { h, k -> PhotoPieces.measureWithTrust(h, estItems[i][k].shape, 1f) } }
            val te = avgMs(reps) { for (i in segs.indices) GroundEstimate.estimate(GroundCam.guess(480, 360), estItems[i], true) { h, k -> PhotoPieces.measureWithTrust(h, estItems[i][k].shape, 1f) } } / segs.size
            println("[${if (par) "PARALELO" else "1 THREAD"}] GroundEstimate.estimate (lista) %.2f ms por cena".format(te))
        }
        Par.enabled = true

        // perfil grosseiro de analyze (sem gravidade e com), só a thread que chama
        for ((label, withCam) in listOf("sem gravidade" to false, "com gravidade" to true)) for (par in listOf(true, false)) {
            Par.enabled = par
            val counts = HashMap<String, Int>(); val self = HashMap<String, Int>()
            val main = Thread.currentThread(); var running = true; var samples = 0
            val sampler = Thread {
                while (running) {
                    val st = main.stackTrace
                    val vis = st.filter { it.className.startsWith("com.brastoy.brascan.vision") && !it.className.contains("Probe") }
                    if (vis.isNotEmpty()) {
                        samples++
                        self.merge(vis.first().let { "${it.className.substringAfterLast('.')}.${it.methodName}" }, 1, Int::plus)
                        for (k in vis.map { "${it.className.substringAfterLast('.').substringBefore('$')}.${it.methodName}" }.toSet()) counts.merge(k, 1, Int::plus)
                    }
                    Thread.sleep(0, 200_000)
                }
            }
            sampler.isDaemon = true; sampler.start()
            repeat(10) { for (i in segs.indices) PhotoPieces.analyze(segs[i], if (withCam) cams[i] else null) }
            running = false; sampler.join()
            val mode = if (par) "PARALELO" else "1 THREAD"
            println("perfil analyze $label [$mode] ($samples amostras): inclusivo " + counts.entries.sortedByDescending { it.value }.take(24).joinToString { "${it.key} ${100 * it.value / samples.coerceAtLeast(1)}%" })
            println("  próprio: " + self.entries.sortedByDescending { it.value }.take(16).joinToString { "${it.key} ${100 * it.value / samples.coerceAtLeast(1)}%" })
        }
        Par.enabled = true
    }
}
