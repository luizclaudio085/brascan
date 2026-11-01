package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Test
import java.util.Random

/**
 * Sonda (não é regressão): tempo por componente do detector de peças soltas e do Segmenter do scanner, com
 * aquecimento e média de várias repetições; e um amostrador de pilha (perfil pobre, sem flags na JVM) que diz onde
 * vai o tempo do PhotoPieces.analyze.
 *   ./gradlew :app:testDebugUnitTest --tests 'com.brastoy.brascan.vision.FormaSpeedProbe'
 */
class FormaSpeedProbe {
    private fun ms(reps: Int, block: () -> Unit): Double {
        repeat(3) { block() }
        val t0 = System.nanoTime(); repeat(reps) { block() }
        return (System.nanoTime() - t0) / 1e6 / reps
    }

    /** Amostrador: a cada ~0,5 ms pega a pilha da thread [t]; conta o método de cima do app (próprio) e todos os da pilha (inclusivo). */
    private class Sampler(val t: Thread) : Thread() {
        val self = HashMap<String, Int>(); val incl = HashMap<String, Int>(); var n = 0
        @Volatile var on = true
        override fun run() {
            while (on) {
                val st = t.stackTrace
                val app = st.filter { it.className.startsWith("com.brastoy.brascan.vision") && !it.className.contains("Probe") }
                if (app.isNotEmpty()) {
                    n++
                    val top = st.first().let { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                    self.merge("${app.first().className.substringAfterLast('.')}.${app.first().methodName}:${app.first().lineNumber} [$top]", 1, Int::plus)
                    for (k in app.map { "${it.className.substringAfterLast('.')}.${it.methodName}" }.toSet()) incl.merge(k, 1, Int::plus)
                }
                sleep(0, 500_000)
            }
        }
        fun report(title: String) {
            println("== perfil $title ($n amostras)")
            println("  inclusivo: " + incl.entries.sortedByDescending { it.value }.take(30).joinToString { "${it.key} ${"%.0f".format(100.0 * it.value / n)}%" })
            println("  próprio:")
            for ((k, v) in self.entries.sortedByDescending { it.value }.take(30)) println("    %5.1f%% %s".format(100.0 * v / n, k))
        }
    }

    private fun profile(title: String, block: () -> Unit) {
        val s = Sampler(Thread.currentThread()); s.isDaemon = true; s.start()
        block()
        s.on = false; s.join()
        s.report(title)
    }

    @Test fun probe() {
        val reps = (System.getProperty("reps") ?: "10").toInt()
        // cena solta 480×360 (a do CountSpeedProbe: semente 1, fácil) e média de 10 cenas (5 fáceis + 5 difíceis)
        val one = LooseBench.frames(1..1, 12, false)
        val many = LooseBench.frames(1..5, 12, false) + LooseBench.frames(1..5, 12, true)
        fun report(name: String, frames: List<Pair<LooseBench.Scene, FrameSample>>) {
            val segs = frames.map { (_, f) -> Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = true) }
            val tSeg = ms(reps) { for ((_, f) in frames) Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = true) } / frames.size
            val tAn = ms(reps) { for (s in segs) PhotoPieces.analyze(s) } / frames.size
            val tAnG = ms(reps) { for ((k, s) in segs.withIndex()) PhotoPieces.analyze(s, GroundCam.fromFrame(frames[k].second)) } / frames.size
            println("$name: segmentação (loose) %.1f ms, analyze sem gravidade %.1f ms, com gravidade %.1f ms → detect total %.1f / %.1f ms"
                .format(tSeg, tAn, tAnG, tSeg + tAn, tSeg + tAnG))
        }
        report("cena solta 480x360 semente 1", one)
        report("cena solta 480x360 média de 10", many)

        val (px, w, h) = RealPhotoProbe().load("todas_640")
        val seg = Segmenter.segment(px, w, h, maxBlobs = 60, loose = true)
        val tSeg = ms(reps) { Segmenter.segment(px, w, h, maxBlobs = 60, loose = true) }
        val tAn = ms(reps) { PhotoPieces.analyze(seg) }
        println("foto todas_640 ${w}x$h: segmentação (loose) %.1f ms, analyze %.1f ms → detect total %.1f ms".format(tSeg, tAn, tSeg + tAn))

        val house = RealisticScene.house()
        val fr = RealisticScene.render(house, V3(.30f, .28f, .30f), V3(0f, .05f, 0f), Random(1), 480, 360, RealisticScene.Opts(noise = 4f, blur = 1))
        val tScan = ms(reps) { Segmenter.segment(fr.rgb, fr.w, fr.h, minFrac = .0015f) }
        println("scanner 480x360 (Segmenter padrão, minFrac 0,0015): %.1f ms".format(tScan))

        if (System.getProperty("profile") != "false") {
            val segsMany = many.map { (_, f) -> Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = true) }
            profile("analyze sem gravidade (10 cenas × 5)") { repeat(5) { for (s in segsMany) PhotoPieces.analyze(s) } }
            profile("analyze com gravidade (10 cenas × 5)") { repeat(5) { for ((k, s) in segsMany.withIndex()) PhotoPieces.analyze(s, GroundCam.fromFrame(many[k].second)) } }
            profile("analyze foto 640 × 10") { repeat(10) { PhotoPieces.analyze(seg) } }
            profile("scanner × 20") { repeat(20) { Segmenter.segment(fr.rgb, fr.w, fr.h, minFrac = .0015f) } }
            profile("segment loose 10 cenas × 3") { repeat(3) { for ((_, f) in many) Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = true) } }
        }
    }
}
