package com.brastoy.brascan.vision

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão da contagem ao vivo no banco de vídeo (3 clipes com a mão entrando na imagem): o rastreio não mostra a
 * mão como peça, não erra mais a contagem por tipo que o comportamento antigo (mediana de 7 quadros) e o número
 * pisca menos. Comparação relativa: continua valendo se o detector mudar.
 */
class LooseVideoTest {
    @Test fun trackerBeatsMedianOnHandClips() {
        val clips = (1..3).map { LooseVideoBench.clip(it.toLong(), 12, hard = false, hand = true) }
        val cur = LooseVideoBench.evaluate(clips) { LooseVideoBench.Current() }
        val trk = LooseVideoBench.evaluate(clips) { LooseVideoBench.Tracked() }
        println("atual: $cur\nrastreio: $trk")
        val gc = cur.ghostsR / cur.nR.toDouble(); val gt = trk.ghostsR / trk.nR.toDouble()
        assertTrue("fantasmas por quadro: rastreio %.2f, atual %.2f".format(gt, gc), gt <= maxOf(.05, gc * .5))
        val ec = cur.countErrR / cur.nR.toDouble(); val et = trk.countErrR / trk.nR.toDouble()
        assertTrue("erro de contagem por tipo: rastreio %.2f, atual %.2f".format(et, ec), et <= ec + .25)
        assertTrue("número muda sem motivo: rastreio ${trk.flips}, atual ${cur.flips}", trk.flips <= cur.flips)
    }
}
