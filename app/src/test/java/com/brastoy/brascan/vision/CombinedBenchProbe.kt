package com.brastoy.brascan.vision

import org.junit.Test

/**
 * Sonda (não é regressão) da COMBINAÇÃO das frentes de detecção (tamanho, forma, peças pequenas, ímãs): variantes do
 * detector ligando e desligando cada parte sobre as MESMAS imagens do LooseBench.
 *   ./gradlew :app:testDebugUnitTest --tests '*CombinedBenchProbe*'
 * Só roda com o arquivo build/sonda-combinada.txt (é lenta para a suíte): 1ª linha = variantes separadas por vírgula
 * ("todas" = todas); uma linha "sementes 31..90" troca as cenas.
 */
class CombinedBenchProbe {
    /** [loose]: segmentação de peças soltas (Segmenter.segment(loose = true), a de PhotoPieces.detect). */
    private class V(val name: String, val loose: Boolean = true, val set: () -> Unit)

    private fun reset() {
        PhotoPieces.templateShapes = true; PhotoPieces.autoFloor = true; PhotoPieces.shapeTilt = PhotoPieces.TiltSource.GROUND
        PhotoPieces.refineTilt = false; PhotoPieces.estimateTilt = true; PhotoPieces.jointScale = true; PhotoPieces.sideMode = PhotoPieces.SideMode.ADAPT
        PhotoPieces.attachMagnets = true; PhotoPieces.mergeNestedSame = true
        PhotoPieces.fillPanels = true; PhotoPieces.dropCutAtBorder = true; PhotoPieces.magnetCheck = true; PhotoPieces.frameOutline = true
        PhotoPieces.hullInside = true; PhotoPieces.inventorySizes = true; PhotoPieces.eByLength = true
        GroundEstimate.minItems = 3; GroundEstimate.minGain = 0f
    }

    /** Tudo desligado: tem que dar exatamente a linha de base (commit 6a89c49). */
    private fun legacy() {
        PhotoPieces.templateShapes = false; PhotoPieces.estimateTilt = false; PhotoPieces.jointScale = false; PhotoPieces.sideMode = PhotoPieces.SideMode.OBB_TRI
        PhotoPieces.fillPanels = false; PhotoPieces.dropCutAtBorder = false; PhotoPieces.magnetCheck = false; PhotoPieces.frameOutline = false
        PhotoPieces.hullInside = false; PhotoPieces.inventorySizes = false; PhotoPieces.eByLength = false
    }

    @Test fun probe() {
        val variants = listOf(
            V("legado", loose = false) { legacy() },
            V("padrão") {},
            V("chão duas vezes") { PhotoPieces.refineTilt = true },
            V("forma por ISO") { PhotoPieces.shapeTilt = PhotoPieces.TiltSource.ISO; PhotoPieces.refineTilt = true },
            V("sem forma nova") { PhotoPieces.templateShapes = false },
            V("sem inclinação") { PhotoPieces.estimateTilt = false },
            V("sem inventário B8") { PhotoPieces.inventorySizes = false },
            V("E pela largura") { PhotoPieces.eByLength = false },
            V("incl. com 2 peças") { GroundEstimate.minItems = 2 },
            V("incl. ganho ≥ 4") { GroundEstimate.minGain = 4f },
            V("segmentação antiga", loose = false) {},
            V("sem colar ímãs") { PhotoPieces.attachMagnets = false },
            V("sem juntar aro") { PhotoPieces.mergeNestedSame = false },
            V("sem corte borda") { PhotoPieces.dropCutAtBorder = false },
            V("sem ímã encaixe") { PhotoPieces.magnetCheck = false },
            V("sem contorno") { PhotoPieces.frameOutline = false },
            V("antiga sem painel", loose = false) { PhotoPieces.fillPanels = false },
            V("antiga sem contorno", loose = false) { PhotoPieces.frameOutline = false },
        )
        val file = java.io.File("build/sonda-combinada.txt")
        if (!file.exists()) { println("sonda desligada: crie app/build/sonda-combinada.txt (1ª linha: todas)"); return }
        val cfg = file.readLines()
        val only = cfg.firstOrNull()?.takeIf { it.isNotBlank() && it != "todas" }?.split(",")?.map { it.trim() }?.toSet()
        val seeds = cfg.firstOrNull { it.startsWith("sementes") }?.substringAfter(" ")?.split("..")?.let { it[0].trim().toInt()..it[1].trim().toInt() } ?: 1..30
        try {
            for (hard in listOf(false, true)) {
                val frames = LooseBench.frames(seeds, 12, hard)
                for (v in variants) {
                    if (only != null && v.name !in only) continue
                    reset(); v.set()
                    // segmenta a cada cena (guardar as 60 segmentações estoura a memória do teste)
                    val st = LooseBench.evaluate(frames, { _, f ->
                        PhotoPieces.from(Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = v.loose)).map { LooseBench.Det(it.cx, it.cy, it.shape, it.size) }
                    })
                    println("${if (hard) "difícil" else "fácil"} %-16s ".format(v.name) + st + "  (detector %.0f ms/cena)".format(st.detMs))
                    println("    " + st.confusion.entries.sortedByDescending { it.value }.take(12).joinToString { "${it.key} ×${it.value}" })
                }
            }
            // fotos reais (as do RealPhotoTest e as reduzidas a 640 px como no app)
            val rp = RealPhotoProbe()
            for (v in variants) {
                if (only != null && v.name !in only) continue
                reset(); v.set()
                for (n in listOf("todas", "janelas", "tamanhos", "todas_640", "janelas_640", "tamanhos_640")) {
                    val (px, w, h) = rp.load(n)
                    val got = PhotoPieces.from(Segmenter.segment(px, w, h, maxBlobs = 60, loose = v.loose)).map { "${it.shape}/${it.size}" }.sorted()
                    println("foto %-16s %-12s ".format(v.name, n) + got)
                }
            }
        } finally { reset() }
    }
}
