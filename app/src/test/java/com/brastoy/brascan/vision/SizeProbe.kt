package com.brastoy.brascan.vision

import org.junit.Test

/**
 * Sonda do TAMANHO nas fotos reais (não é regressão): inclinação estimada, escala da foto e, por peça, lado medido
 * na imagem e no chão retificado, anisotropia do casco antes/depois e confiança do tamanho.
 *   ./gradlew :app:testDebugUnitTest --tests '*SizeProbe*'
 */
class SizeProbe {
    @Test fun realPhotos() {
        for (n in listOf("janelas", "tamanhos", "todas")) {
            val (px, w, h) = RealPhotoProbe().load(n)
            val seg = Segmenter.segment(px, w, h)
            val cam = GroundCam.guess(w, h)
            val items = seg.blobs.filter { it.fit != null }.map { b -> GroundEstimate.Item(Geo2.hull(b.mask.edgePoints().map { P2(it.x + b.minX, it.y + b.minY) }), b.fit!!.shape, 1f) }
            for (useScale in listOf(false, true)) {
                GroundEstimate.useScaleTerm = useScale
                val e = GroundEstimate.estimate(cam, items) { hh, k -> PhotoPieces.measureWithTrust(hh, items[k].shape, 1f) }
                println("$n: só anisotropia=${!useScale}: inclinação %.0f° direção %.0f° custo %.1f (plano %.1f)".format(e?.tiltDeg ?: 0f, e?.azDeg ?: 0f, e?.cost ?: 0f, e?.flatCost ?: 0f))
            }
            GroundEstimate.useScaleTerm = true
            val r = PhotoPieces.analyze(seg)
            println("== $n ${w}x$h: inclinação usada %.0f°, escala ${r.scale}".format(r.tiltDeg))
            val rect = r.ground
            for (p in r.pieces.sortedBy { it.cy * 10000 + it.cx }) {
                val b = seg.blobs.first { it.minX + it.bw / 2f == p.cx && it.minY + it.bh / 2f == p.cy }
                val hi = Geo2.hull(b.mask.edgePoints().map { P2(it.x + b.minX, it.y + b.minY) })
                val hr = rect?.mapAll(hi)?.let(Geo2::hull) ?: hi
                println("  %-12s %-7s conf=%.2f lado=%.1f (imagem %.1f) aniso %.3f→%.3f (molde %.3f) iou=%.2f".format(
                    p.shape, p.size, p.sizeConf, p.side, PhotoPieces.measure(hi, p.shape, p.fit), PolyMoments.anisotropy(hi), PolyMoments.anisotropy(hr),
                    PolyMoments.catalogAnisotropy(p.shape), p.fit))
            }
        }
    }
}
