package com.brastoy.brascan.vision

import com.brastoy.brascan.scan.Counting
import org.junit.Test

/**
 * Sonda (não é regressão): contagem AO VIVO num vídeo sintético de peças soltas (câmera na mão andando devagar), o
 * comportamento de 065cead (caixa para cada detecção, mediana por tipo de 7 quadros) contra o rastreador.
 *   ./gradlew :app:testDebugUnitTest --tests '*LooseVideoProbe*'
 * VIDEO_SEEDS=1-12 (padrão) escolhe as cenas; VIDEO_CURVES=1 imprime as curvas por quadro.
 */
class LooseVideoProbe {
    private fun seeds(def: String) = (System.getenv("VIDEO_SEEDS") ?: def).split("-").let { it[0].toInt()..it[1].toInt() }

    @Test fun probe() {
        val curves = System.getenv("VIDEO_CURVES") == "1"
        val sets = listOf(Triple("fácil", false, false), Triple("difícil", true, false), Triple("fácil com mão", false, true), Triple("fácil, câmera 3× mais rápida", false, false))
        for ((name, hard, hand) in sets) {
            val t0 = System.nanoTime()
            val fast = if (name.contains("3×")) 3f else 1f
            val clips = seeds("1-12").map { LooseVideoBench.clip(it.toLong(), 12, hard, hand = hand, fast = fast) }
            val det = clips.flatMap { c -> c.frames.map { it.detMs } }.average()
            println("== $name: ${clips.size} clipes × 12 quadros (detector %.0f ms/quadro, render+detecção %.0f s)".format(det, (System.nanoTime() - t0) / 1e9))
            println("  detecções cruas: " + LooseVideoBench.consistency(clips, examples = if (curves) 6 else 0))
            fun show(label: String, st: LooseVideoBench.Stats, extra: String = "") {
                println("  $label: $st$extra")
                println("     ${st.errors(6)}")
                if (curves) {
                    println("     erro por quadro: ${st.curve(st.errAt)}")
                    println("     fantasmas:       ${st.curve(st.ghostAt)}")
                    println("     sem caixa:       ${st.curve(st.missAt)}")
                }
            }
            show("atual (mediana 7)", LooseVideoBench.evaluate(clips) { LooseVideoBench.Current() })
            for ((label, mk) in listOf<Pair<String, () -> Counting.Tracker>>(
                "rastreio (padrão do app)" to { Counting.Tracker() },
                "rastreio, número sem espera" to { Counting.Tracker(hold = 1) },
                "rastreio, peça firme some como as outras (2 sem ver)" to { Counting.Tracker(steadyCoast = 2) },
                "rastreio, sem regra de peça parada" to { Counting.Tracker(still = false) },
                "rastreio, sem confirmação rápida (3 de 5 sempre)" to { Counting.Tracker(fast = 0) },
                "rastreio, escala única (sem inclinação do chão)" to { Counting.Tracker(tilt = false) },
                "rastreio, tamanho por voto dos quadros" to { Counting.Tracker(sharedScale = false) },
            )) {
                val made = ArrayList<LooseVideoBench.Tracked>()
                val st = LooseVideoBench.evaluate(clips) { LooseVideoBench.Tracked(mk()).also { made += it } }
                show(label, st, " · rastreio " + "%.3f ms/quadro".format(made.sumOf { it.nanos } / 1e6 / made.sumOf { it.calls }))
            }
        }
    }

    /** Tempo do rastreio no pior caso razoável: 30 peças (+ 5 detecções soltas por quadro), câmera andando. */
    @Test fun speedProbe() {
        val rnd = java.util.Random(5); val w = 480; val h = 360
        val kinds = com.brastoy.brascan.scan.Inventory.KINDS
        val base = (0 until 30).map { Triple(30f + rnd.nextFloat() * 420, 30f + rnd.nextFloat() * 300, kinds[rnd.nextInt(kinds.size)]) }
        val tr = Counting.Tracker()
        var ns = 0L; var n = 0
        for (i in 0 until 400) {
            val dx = (i % 40) * 1.5f
            val dets = base.map { (x, y, k) -> val s = 40f * com.brastoy.brascan.vision.Catalog.SCALE.getValue(k.size)
                Counting.Found((x + dx) / w, y / h, s / w, s / h, k, 0xFFE53950.toInt(), .8f, false, s / w) } +
                (0 until 5).map { Counting.Found(rnd.nextFloat(), rnd.nextFloat(), .05f, .07f, kinds[0], 0xFF2F8CFF.toInt(), .4f) }
            if (i % 40 == 0) tr.clear()
            val t0 = System.nanoTime(); tr.push(dets, w, h); if (i >= 40) { ns += System.nanoTime() - t0; n++ }
        }
        println("rastreio com 30 peças + 5 soltas: %.3f ms/quadro".format(ns / 1e6 / n))
    }

    /** Caixas-fantasma do rastreio nos clipes com mão: onde, de que tamanho, cortadas ou não. */
    @Test fun ghostProbe() {
        for (seed in seeds("1-12")) {
            val c = LooseVideoBench.clip(seed.toLong(), 12, false, hand = true)
            val m = LooseVideoBench.Tracked()
            val sb = StringBuilder()
            for ((i, f) in c.frames.withIndex()) {
                val sh = m.push(f.found, f.w, f.h); val s = LooseVideoBench.score(f, sh)
                val matched = s.match.values.toSet()
                for (b in sh.boxes) if (b !in matched) sb.append(" q$i(%.0f,%.0f %.0fx%.0f %s%s fit %.2f)".format(b.cx * f.w, b.cy * f.h, b.w * f.w, b.h * f.h, b.kind.key, if (b.cut) " cortada" else "", b.fit))
            }
            if (sb.isNotEmpty()) println("cena $seed:$sb")
        }
    }

    /** Escala do chão estimada × verdadeira (lado do grande em pixels onde está cada peça) em alguns clipes. */
    @Test fun scaleProbe() {
        for (hard in listOf(false, true)) for (seed in seeds("1-3")) {
            val c = LooseVideoBench.clip(seed.toLong(), 12, hard)
            val tr = Counting.Tracker()
            val sb = StringBuilder("${if (hard) "difícil" else "fácil"} $seed:")
            for ((i, f) in c.frames.withIndex()) {
                tr.push(f.found, f.w, f.h)
                val sc = tr.scale ?: continue
                // verdade: lado projetado ÷ razão do tamanho = lado do grande ali
                val r = f.truth.filter { it.whole }.map { t ->
                    val u = t.side / Catalog.SCALE.getValue(t.key.substringBefore('/'))
                    sc.at(t.cx.toFloat(), t.cy.toFloat()) / u
                }.sorted()
                if (i % 3 == 2 && r.isNotEmpty()) sb.append(" | q$i g=(%.2f,%.2f) est/real %.2f–%.2f".format(sc.gx, sc.gy, r.first(), r.last()))
            }
            println(sb)
        }
    }
}
