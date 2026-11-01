package com.brastoy.brascan.vision

import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Golden por componente da FORMA (mais estrito que o [DetectorGoldenTest], que só vê a peça final): em cada máscara
 * de peça das cenas do LooseBench (sementes 1..10, fácil e difícil) e das fotos reais, a lista INTEIRA de
 * [ShapeMatch.match] (todas as formas, IoU, giro, escala, espelho, IoU dos furos; com e sem a perspectiva da
 * gravidade, formas cheias e vazadas), [ShapeMatch.elongation]/[ShapeMatch.holeElongation]/[ShapeMatch.enclosedHoles],
 * [ShapeClassifier.classify], [TemplateFit.fit] (frente e cima) e [Magnets] (contorno e contrastes).
 * Gravado pelo código de antes das otimizações (app/src/test/resources/golden/forma.txt); arquivo ausente = gera.
 * Comparação como no [DetectorGoldenTest] (números com folga de 1e-3; as otimizações da forma dão o mesmo bit a bit).
 */
class ShapeGoldenTest {
    companion object {
        val FILE = File("src/test/resources/golden/forma.txt")
        private fun f(x: Float) = String.format(Locale.ROOT, "%.5f", x)

        private fun scores(tag: String, l: List<ShapeMatch.Score>) =
            "$tag " + l.joinToString(" ") { "${it.shape} ${f(it.iou)} ${f(it.angle)} ${f(it.scale)} ${it.mirror} ${f(it.holeIou)}" }

        fun lines(seg: Segmentation, cam: GroundCam): List<String> {
            val out = ArrayList<String>()
            val blobs = PhotoPieces.pieceBlobs(seg); val byId = blobs.associateBy { it.id }
            val view = cam.down?.let { FloorView.of(cam, it) }
            val lum = Magnets.luminance(seg); val chroma = Magnets.chroma(seg)
            for (b in blobs) {
                val m = PhotoPieces.pieceMask(b, seg, byId)
                out += "b ${b.id} ${m.w}x${m.h} ${m.count()} ${DetectorGoldenTest.maskHash(m)}"
                out += DetectorGoldenTest.blobLines(Blob(b.id, b.count, b.minX, b.minY, b.maxX, b.maxY, b.color, b.hue, m, ShapeClassifier.classify(m), b.family))
                out += scores("ml", ShapeMatch.match(m, ShapeMatch.LOOSE))
                out += scores("mf", ShapeMatch.match(m, setOf("moldura", "porta", "janela")))
                val warp = view?.shapeWarp(b.minX + b.bw / 2f, b.minY + b.bh / 2f)?.first
                if (warp != null) {
                    out += scores("wl", ShapeMatch.match(m, ShapeMatch.LOOSE, warp))
                    out += scores("wf", ShapeMatch.match(m, setOf("moldura", "porta", "janela"), warp))
                }
                val holes = ShapeMatch.enclosedHoles(m)
                out += "e ${f(ShapeMatch.elongation(m))} ${f(ShapeMatch.holeElongation(m))} ${f(ShapeMatch.elongation(m, warp))} ${f(ShapeMatch.holeElongation(m, warp))} ${holes.count()} ${DetectorGoldenTest.maskHash(holes)}"
                val fit = ShapeClassifier.classify(m)
                if (fit != null) for (s in listOf(fit.shape, "quadrado", "equilatero", "L", "porta")) {
                    val (a, i) = TemplateFit.fit(m, fit.obb, s, vUp = true, corners = fit.corners)
                    val (c, _) = TemplateFit.fit(m, fit.obb, s, vUp = false, corners = fit.corners)
                    out += "t $s ${f(a)} ${f(i)} ${f(c)}"
                }
                for (k in listOf(3, 4, 5)) {
                    val q = Magnets.outline(b, k)
                    out += "o $k " + (q?.joinToString(" ") { "${f(it.x)} ${f(it.y)}" } ?: "null") + (q?.let { " ${Magnets.squareish(it)}" } ?: "")
                    if (q != null && lum != null && chroma != null) {
                        val unit = q.indices.map { (q[(it + 1) % q.size] - q[it]).len() }.average().toFloat()
                        for (u in listOf(unit, 45f)) {
                            val c = Magnets.contrasts(lum, chroma, q, u)
                            out += "c ${f(u)} " + (c?.joinToString(" ") { f(it) } ?: "null") + " ${Magnets.verdict(c)}"
                        }
                    }
                }
            }
            return out
        }

        fun produce(): List<String> {
            val out = ArrayList<String>()
            for ((name, hard) in listOf("facil" to false, "dificil" to true)) for ((k, sf) in LooseBench.frames(1..10, 12, hard).withIndex()) {
                val (_, fr) = sf
                out += "## forma $name semente ${k + 1}"
                out += lines(Segmenter.segment(fr.rgb, fr.w, fr.h, maxBlobs = 60, loose = true), GroundCam.fromFrame(fr))
            }
            val probe = RealPhotoProbe()
            for (n in listOf("todas", "janelas", "tamanhos", "todas_640", "janelas_640", "tamanhos_640")) {
                val (px, w, h) = probe.load(n)
                out += "## forma foto $n"
                out += lines(Segmenter.segment(px, w, h, maxBlobs = 60, loose = true), GroundCam.guess(w, h))
            }
            return out
        }
    }

    @Test fun golden() {
        val got = produce()
        if (!FILE.exists()) {
            if (System.getenv("GOLDEN_RECORD") != "1") fail("golden ausente: ${FILE.path} (gerar no código original)")
            FILE.parentFile.mkdirs()
            FILE.writeText(got.joinToString("\n", postfix = "\n"))
            println("golden gerado: ${FILE.absolutePath} (${got.size} linhas)")
            return
        }
        val want = FILE.readLines().filter { it.isNotEmpty() }
        val errs = DetectorGoldenTest.diff(want, got)
        if (errs.isNotEmpty()) fail("saída da forma mudou (${errs.size} diferenças, primeiras):\n" + errs.joinToString("\n"))
        println("golden da forma: ${want.size} linhas iguais")
    }
}
