package com.brastoy.brascan.vision

import org.junit.Test

/**
 * Sonda do TAMANHO no banco de contagem (não é regressão): mesmas cenas do LooseBench, várias variantes do
 * detector de tamanho sobre a MESMA segmentação.
 *   ./gradlew :app:testDebugUnitTest --tests '*SizeBenchProbe*'
 * Só roda com o arquivo build/sonda-tamanho.txt (é lenta para a suíte): 1ª linha = variantes separadas por vírgula
 * ("todas" = todas); "confusao" numa linha imprime os erros de tamanho separando forma certa × forma errada.
 * As rodadas padrão / com gravidade / com gravidade e altura já saem na LooseBenchProbe.
 */
class SizeBenchProbe {
    /** [cam]: 0 nenhuma (foto), 1 gravidade + intrínsecos (câmera verdadeira), 2 também a altura. */
    private class V(val name: String, val mode: PhotoPieces.SideMode, val joint: Boolean, val tilt: Boolean, val cam: Int,
                    val sigma: Pair<Float, Float> = .07f to .045f, val prior: Boolean = true, val trunc: Float = 9f)

    @Test fun probe() {
        val variants = listOf(
            V("legado", PhotoPieces.SideMode.OBB_TRI, false, false, 0),
            V("conjunta (sem incl.)", PhotoPieces.SideMode.ADAPT, true, false, 0),
            V("incl. simétrico", PhotoPieces.SideMode.ADAPT, true, true, 0, .06f to .06f, false),
            V("incl. assimétrico", PhotoPieces.SideMode.ADAPT, true, true, 0, .07f to .045f, false),
            V("incl. simétr.+prop.", PhotoPieces.SideMode.ADAPT, true, true, 0, .06f to .06f, true),
            V("incl. (padrão)", PhotoPieces.SideMode.ADAPT, true, true, 0),
            V("incl. teto 4", PhotoPieces.SideMode.ADAPT, true, true, 0, trunc = 4f),
            V("incl. AREA", PhotoPieces.SideMode.AREA, true, true, 0),
            V("incl. MIX", PhotoPieces.SideMode.MIX, true, true, 0),
            V("gravidade", PhotoPieces.SideMode.ADAPT, true, false, 1),
            V("gravidade+altura", PhotoPieces.SideMode.ADAPT, true, false, 2),
        )
        val file = java.io.File("build/sonda-tamanho.txt")
        if (!file.exists()) { println("sonda desligada: crie app/build/sonda-tamanho.txt (1ª linha: todas)"); return }
        val cfg = file.readLines()
        val only = cfg.firstOrNull()?.takeIf { it.isNotBlank() && it != "todas" }?.split(",")?.map { it.trim() }?.toSet()
        val confusion = "confusao" in cfg
        // "sementes 31..90": outras cenas (conferir que não ficou ajustado às 30 da linha de base)
        val seeds = cfg.firstOrNull { it.startsWith("sementes") }?.substringAfter(" ")?.split("..")?.let { it[0].trim().toInt()..it[1].trim().toInt() } ?: 1..30
        val saved = Triple(PhotoPieces.sideMode, PhotoPieces.jointScale, PhotoPieces.estimateTilt)
        val savedS = Triple(SizeScale.sigmaLow, SizeScale.sigmaHigh, SizeScale.classPrior)
        try {
            for (hard in listOf(false, true)) {
                val frames = LooseBench.frames(seeds, 12, hard)
                val segs = frames.map { (_, f) -> Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60) }
                val tiltLog = StringBuilder()
                for (v in variants) {
                    if (only != null && v.name !in only) continue
                    PhotoPieces.sideMode = v.mode; PhotoPieces.jointScale = v.joint; PhotoPieces.estimateTilt = v.tilt
                    SizeScale.sigmaLow = v.sigma.first; SizeScale.sigmaHigh = v.sigma.second; SizeScale.classPrior = v.prior
                    GroundEstimate.anTrunc = v.trunc
                    var k = 0; var tiltErr = 0f; var tiltBad = 0
                    val t0 = System.nanoTime()
                    val st = LooseBench.evaluate(frames, { s, f ->
                        val seg = segs[k++]
                        val cam = when (v.cam) { 1 -> GroundCam.fromFrame(f); 2 -> GroundCam.fromFrame(f, heightM = s.eye.y); else -> null }
                        val r = PhotoPieces.analyze(seg, cam)
                        if (v.tilt) {
                            val truth = GroundRect(GroundCam.fromFrame(f), GroundCam.fromFrame(f).down!!).tiltDeg
                            if (v.name == "incl. (padrão)") tiltLog.append("%.0f→%.0f ".format(truth, r.tiltDeg))
                            val e = kotlin.math.abs(truth - r.tiltDeg); tiltErr += e; if (e > 8) tiltBad++
                        }
                        r.pieces.map { LooseBench.Det(it.cx, it.cy, it.shape, it.size) }
                    })
                    println("${if (hard) "difícil" else "fácil"} %-20s ".format(v.name) + st + "  (%.0f ms/cena)".format((System.nanoTime() - t0) / 1e6 / frames.size) +
                        if (v.tilt) "  inclinação: erro médio %.1f°, %d cenas > 8°".format(tiltErr / frames.size, tiltBad) else "")
                    if (confusion) {
                        // erros de TAMANHO (forma certa × forma errada)
                        var sBad = 0; var wOk = 0; var wBad = 0
                        val sz = HashMap<String, Int>()
                        for ((k2, n) in st.confusion) {
                            if (k2.endsWith("(não achada)")) continue
                            val (t, d) = k2.split(" → "); val (ts, tsh) = t.split("/"); val (ds, dsh) = d.split("/")
                            if (tsh == dsh) { if (ts != ds) { sBad += n; sz.merge("$ts→$ds ($tsh)", n, Int::plus) } } else { if (ts == ds) wOk += n else { wBad += n; sz.merge("$ts→$ds ($tsh→$dsh)", n, Int::plus) } }
                        }
                        println("    forma certa: tamanho certo ${st.kindOk}, errado $sBad | forma errada: tamanho certo $wOk, errado $wBad")
                        println("    " + sz.entries.sortedByDescending { it.value }.take(16).joinToString { "${it.key} ×${it.value}" })
                    }
                }
                if (tiltLog.isNotEmpty()) println("  inclinação verdadeira→estimada: $tiltLog")
            }
        } finally {
            PhotoPieces.sideMode = saved.first; PhotoPieces.jointScale = saved.second; PhotoPieces.estimateTilt = saved.third
            SizeScale.sigmaLow = savedS.first; SizeScale.sigmaHigh = savedS.second; SizeScale.classPrior = savedS.third
            GroundEstimate.anTrunc = 9f
        }
    }
}
