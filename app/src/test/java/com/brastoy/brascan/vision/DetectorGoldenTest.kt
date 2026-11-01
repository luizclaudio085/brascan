package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Golden do detector: a saída INTEIRA do detector de peças soltas e do Segmenter do scanner em entradas fixas, gravada
 * pelo código de ANTES das otimizações de velocidade (ce4b61b) em app/src/test/resources/golden/detector.txt.
 * Otimização só vale se a saída continuar a mesma.
 *
 * Entradas:
 * - LooseBench, sementes 1..30, fácil e difícil (12 peças, 480×360): a segmentação de peças soltas
 *   (Segmenter.segment(loose = true): mapas L, a*, b*, primeiro plano, ímãs e rótulos por hash, cor do fundo, cada blob
 *   com a forma da regra inteira) e PhotoPieces.analyze dela (o que PhotoPieces.detect e LooseBench.detect devolvem)
 *   sem câmera (inclinação estimada pelas peças), com a câmera da cena (GroundCam.fromFrame: gravidade + intrínsecos,
 *   como a contagem ao vivo) e com gravidade + altura; por cena também a inclinação usada, a escala e a estimativa;
 * - fotos reais (todas, janelas, tamanhos e as versões _640, a resolução do app): segmentação solta + detector,
 *   segmentação padrão e o caminho antigo (PhotoPieces.from(Segmenter.segment(maxBlobs = 60)), o de scan/Pieces.kt);
 * - o que o scanner 3D vê: Segmenter.segment padrão (minFrac 0,0015) em 72 quadros da casinha da RealisticScene
 *   (3 condições × 24 posições, como a RealisticScanProbe) e PieceSlam.detect de cada quadro.
 *
 * Números (Float.toString, exatos) com folga de 1e-3 (relativa acima de 1); inteiros, nomes e hashes exatos. O teste
 * conta também as linhas que só bateram dentro da folga (o esperado das otimizações é zero: saída igual bit a bit).
 * Sem o arquivo, grava (rodar com o código ORIGINAL); no CI o arquivo tem que existir. A saída atual vai sempre para
 * build/golden/detector.txt.
 */
class DetectorGoldenTest {
    companion object {
        val FILE = File("src/test/resources/golden/detector.txt")
        const val TOL = 1e-3

        private fun f(x: Float) = x.toString()
        private fun d(x: Double) = x.toString()
        private fun hex(c: Int) = String.format(Locale.ROOT, "%08X", c)

        private class Fnv {
            var h = -0x340d631b7bdddcdbL
            fun add(v: Int) { h = (h xor (v.toLong() and 0xFFFFFFFFL)) * 0x100000001b3L }
            fun hex(): String = java.lang.Long.toHexString(h)
        }
        fun hash(a: FloatArray?) = a?.let { Fnv().apply { add(it.size); for (v in it) add(java.lang.Float.floatToRawIntBits(v)) }.hex() } ?: "-"
        fun hash(a: BooleanArray?) = a?.let { Fnv().apply { add(it.size); for (v in it) add(if (v) 1 else 0) }.hex() } ?: "-"
        fun hash(a: IntArray?) = a?.let { Fnv().apply { add(it.size); for (v in it) add(v) }.hex() } ?: "-"
        /** Tamanho e pixels de uma máscara. */
        fun maskHash(m: Mask): String = Fnv().apply { add(m.w); add(m.h); for (i in m.bits.indices) if (m.bits[i]) add(i) }.hex()

        fun piece(p: PhotoPiece) =
            "P ${p.shape} ${p.size} ${hex(p.color)} ${f(p.cx)} ${f(p.cy)} ${f(p.w)} ${f(p.h)} ${f(p.spin)} ${f(p.spinTop)} ${f(p.side)} ${f(p.fit)} ${f(p.sizeConf)}"

        fun resultLines(r: PhotoPieces.Result): List<String> {
            val out = ArrayList<String>()
            val s = r.scale; val e = r.estimate
            out += "M ${f(r.tiltDeg)} ${r.gravity} ${r.ground?.let { f(it.tiltDeg) } ?: "-"} | ${f(s.unit)} ${f(s.conf)} ${s.ambiguous} ${s.absolute} " +
                "${s.sizes.joinToString(",").ifEmpty { "-" }} | ${s.sizeConf.joinToString(" ") { f(it) }} | ${s.mix.joinToString(" ") { f(it) }}"
            out += if (e == null) "E -" else "E ${f(e.tiltDeg)} ${f(e.azDeg)} ${f(e.cost)} ${f(e.flatCost)} ${e.down.joinToString(" ") { f(it) }}"
            out += "n ${r.pieces.size}"
            for (p in r.pieces) out += piece(p)
            return out
        }

        fun blobLines(b: Blob): List<String> {
            val out = ArrayList<String>()
            out += "B ${b.id} ${b.count} ${b.minX} ${b.minY} ${b.maxX} ${b.maxY} ${hex(b.color)} ${f(b.hue)} ${b.family} ${b.mask.w}x${b.mask.h} ${b.mask.count()} ${maskHash(b.mask)}"
            val fit = b.fit
            if (fit == null) out += "F -"
            else {
                out += "F ${fit.shape} ${f(fit.obb.c.x)} ${f(fit.obb.c.y)} ${f(fit.obb.angle)} ${f(fit.obb.w)} ${f(fit.obb.h)} ${fit.apexPositive} ${f(fit.solidity)} ${f(fit.rectangularity)} ${fit.corners.size} " +
                    fit.corners.joinToString(" ") { "${f(it.x)} ${f(it.y)}" }
                for (g in fit.gaps) out += "G ${f(g.cu)} ${f(g.cv)} ${f(g.su)} ${f(g.sv)} ${f(g.area)} ${g.enclosed}"
            }
            return out
        }

        fun segHead(s: Segmentation) =
            "S ${s.w}x${s.h} ${s.blobs.size} labels=${hash(s.labels)} L=${hash(s.L)} A=${hash(s.A)} B=${hash(s.B)} fg=${hash(s.fg)} magnet=${hash(s.magnet)} " +
                "bg=${s.bgLab?.joinToString(",") { f(it) } ?: "-"} rgb=${hash(s.rgb)}"

        fun segLines(s: Segmentation): List<String> = listOf(segHead(s)) + s.blobs.flatMap(::blobLines)

        private val K = Intrinsics(390.0, 390.0, 240.0, 180.0)

        fun slamLines(seg: Segmentation, rgb: IntArray): List<String> {
            val dets = PieceSlam(K).detect(seg, rgb)
            val out = ArrayList<String>(); out += "D ${dets.size}"
            for (dt in dets) {
                out += "d ${dt.shape} ${hex(dt.color)} ${dt.bin} ${d(dt.rms)} ${dt.areaPx} ${dt.outline} ${d(dt.frontal)} ${dt.ambiguous} ${dt.candidates.size} " +
                    dt.byOutline.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.size}" }.ifEmpty { "-" } + " | " +
                    dt.imgCorners.joinToString(" ") { "${d(it[0])} ${d(it[1])}" } + " | " +
                    (dt.candidates.firstOrNull()?.let { (it.r.toList() + it.t.toList()).joinToString(" ") { v -> d(v) } } ?: "-")
            }
            return out
        }

        /** Quadros do scanner 3D: casinha da RealisticScene, 3 condições × 24 posições numa volta (RealisticScanProbe). */
        fun scannerFrames(): List<Pair<String, FrameSample>> {
            val out = ArrayList<Pair<String, FrameSample>>()
            val tiles = RealisticScene.house(); val target = V3(0f, .05f, 0f); val frames = 24
            for ((name, o) in listOf(
                "limpa" to RealisticScene.Opts(noise = 0f, carpet = false, shadow = false),
                "celular" to RealisticScene.Opts(noise = 4f, blur = 2, carpet = true, shadow = true),
                "escura" to RealisticScene.Opts(exposure = .6f, noise = 6f, blur = 1),
            )) {
                val rnd = Random(11)
                for (i in 0 until frames) {
                    val a = i * 2 * Math.PI / frames
                    val eye = V3((cos(a) * .42f).toFloat(), .28f + (.03 * sin(i * .9)).toFloat(), (sin(a) * .42f).toFloat())
                    out += "$name q$i" to RealisticScene.render(tiles, eye, target, rnd, 480, 360, o)
                }
            }
            return out
        }

        /** Saída inteira, em seções "## nome". */
        fun produce(): List<String> {
            val out = ArrayList<String>()
            // 1) banco de peças soltas
            for ((name, hard) in listOf("facil" to false, "dificil" to true)) {
                for ((k, sf) in LooseBench.frames(1..30, 12, hard).withIndex()) {
                    val (s, fr) = sf
                    val tag = "solta $name semente ${k + 1} ${s.tiles.size} pecas ${fr.w}x${fr.h}"
                    val seg = Segmenter.segment(fr.rgb, fr.w, fr.h, maxBlobs = 60, loose = true)
                    out += "## $tag segmentacao"; out += segLines(seg)
                    out += "## $tag sem-camera"; out += resultLines(PhotoPieces.analyze(seg, null))
                    out += "## $tag com-gravidade"; out += resultLines(PhotoPieces.analyze(seg, GroundCam.fromFrame(fr)))
                    out += "## $tag gravidade-e-altura"; out += resultLines(PhotoPieces.analyze(seg, GroundCam.fromFrame(fr, heightM = s.eye.y)))
                    // a análise não pode mexer na segmentação (as três usam a mesma)
                    out += "## $tag segmentacao-depois"; out += "H ${Fnv().apply { for (l in segLines(seg)) for (c in l) add(c.code) }.hex()}"
                }
            }
            // 2) fotos reais (400 px e 640 px, a resolução do app)
            val probe = RealPhotoProbe()
            for (n in listOf("todas", "janelas", "tamanhos", "todas_640", "janelas_640", "tamanhos_640")) {
                val (px, w, h) = probe.load(n)
                val loose = Segmenter.segment(px, w, h, maxBlobs = 60, loose = true)
                out += "## foto $n ${w}x$h segmentacao-solta"; out += segLines(loose)
                out += "## foto $n ${w}x$h solta"; out += resultLines(PhotoPieces.analyze(loose))
                out += "## foto $n ${w}x$h segmentacao-padrao"; out += segLines(Segmenter.segment(px, w, h))
                val old = Segmenter.segment(px, w, h, maxBlobs = 60)
                out += "## foto $n ${w}x$h segmentacao-antigo"; out += segLines(old)
                out += "## foto $n ${w}x$h antigo"; out += resultLines(PhotoPieces.analyze(old))
            }
            // 3) scanner 3D: segmentação padrão e as detecções do PieceSlam
            for ((name, fr) in scannerFrames()) {
                val seg = Segmenter.segment(fr.rgb, fr.w, fr.h, minFrac = .0015f)
                out += "## scanner $name"; out += segLines(seg); out += slamLines(seg, fr.rgb)
            }
            return out
        }

        private fun isNum(s: String) = s.toDoubleOrNull() != null && (s.contains('.') || s.contains('E') || s == "NaN" || s.endsWith("Infinity"))

        /** 0 = igual; 1 = igual dentro da folga [TOL] (números); 2 = diferente. */
        fun compare(a: String, b: String): Int {
            if (a == b) return 0
            val ta = a.split(' '); val tb = b.split(' ')
            if (ta.size != tb.size) return 2
            for (k in ta.indices) {
                if (ta[k] == tb[k]) continue
                // "chave=a,b,c": compara os valores
                val ka = ta[k].substringBefore('=', ""); val kb = tb[k].substringBefore('=', "")
                if (ka != kb) return 2
                val va = ta[k].substringAfter('=').split(','); val vb = tb[k].substringAfter('=').split(',')
                if (va.size != vb.size) return 2
                for (q in va.indices) {
                    if (va[q] == vb[q]) continue
                    if (!isNum(va[q]) || !isNum(vb[q])) return 2
                    val x = va[q].toDouble(); val y = vb[q].toDouble()
                    if (x.isNaN() || y.isNaN() || abs(x - y) > TOL * maxOf(1.0, abs(x))) return 2
                }
            }
            return 1
        }

        fun same(a: String, b: String) = compare(a, b) < 2

        class Report(val errs: List<String>, val lines: Int, val tolerated: Int, val different: Int)

        /** Compara duas saídas seção a seção (cada "## ..." tem que bater; dentro dela, linha a linha). */
        fun report(want: List<String>, got: List<String>, max: Int = 40): Report {
            val errs = ArrayList<String>(); var tol = 0; var nErr = 0
            fun sections(l: List<String>): List<Pair<String, List<String>>> {
                val r = ArrayList<Pair<String, MutableList<String>>>()
                for (s in l) if (s.startsWith("## ")) r += s to ArrayList() else r.lastOrNull()?.second?.add(s)
                return r
            }
            val a = sections(want); val b = sections(got)
            if (a.size != b.size) { errs += "número de seções: ${a.size} × ${b.size}"; nErr++ }
            for (k in 0 until minOf(a.size, b.size)) {
                val (ha, la) = a[k]; val (hb, lb) = b[k]
                if (ha != hb) { nErr++; if (errs.size < max) errs += "seção $k: '$ha' × '$hb'"; continue }
                if (la.size != lb.size) { nErr++; if (errs.size < max) errs += "$ha: ${la.size} linhas × ${lb.size}" }
                for (q in 0 until minOf(la.size, lb.size)) when (compare(la[q], lb[q])) {
                    1 -> tol++
                    2 -> { nErr++; if (errs.size < max) errs += "$ha\n   era: ${la[q]}\n  veio: ${lb[q]}" }
                }
            }
            if (nErr > errs.size) errs += "… ($nErr diferenças no total)"
            return Report(errs, want.size, tol, nErr)
        }

        fun diff(want: List<String>, got: List<String>, max: Int = 40): List<String> = report(want, got, max).errs

        /** Roda e compara com o arquivo (ou grava, se faltar). */
        fun check() {
            val t0 = System.nanoTime()
            val got = produce()
            File("build/golden").mkdirs(); File("build/golden/detector.txt").writeText(got.joinToString("\n", postfix = "\n"))
            if (!FILE.exists()) {
                // gravar só quando pedido (GOLDEN_RECORD=1), e no código ORIGINAL: senão um teste rodado de outra pasta
                // gravaria um golden novo com o código atual e passaria sem comparar nada
                if (System.getenv("GOLDEN_RECORD") != "1") fail("golden ausente: ${FILE.absolutePath} (gerar no código original com GOLDEN_RECORD=1)")
                FILE.parentFile.mkdirs(); FILE.writeText(got.joinToString("\n", postfix = "\n"))
                println("golden gerado: ${FILE.absolutePath} (${got.size} linhas)")
                return
            }
            val want = FILE.readLines().filter { it.isNotEmpty() }
            val r = report(want, got)
            println("golden: ${r.lines} linhas, ${r.different} diferentes, ${r.tolerated} só dentro da folga; %.1f s".format((System.nanoTime() - t0) / 1e9))
            if (r.errs.isNotEmpty()) fail("saída do detector mudou:\n" + r.errs.joinToString("\n"))
            // as otimizações dão saída igual bit a bit; diferença pequena (dentro da folga) também é mudança
            if (r.tolerated > 0) fail("saída do detector mudou em ${r.tolerated} linhas (dentro da folga de 1e-3, mas não igual)")
        }
    }

    @Test fun golden() = check()

    /** O golden grava PhotoPieces.analyze(segmentação solta); conferir que é o mesmo que detect e LooseBench.detect devolvem. */
    @Test fun detectIsAnalyzeOfLooseSegmentation() {
        for (hard in listOf(false, true)) for ((_, fr) in LooseBench.frames(1..3, 12, hard)) {
            val seg = Segmenter.segment(fr.rgb, fr.w, fr.h, maxBlobs = 60, loose = true)
            val a = PhotoPieces.analyze(seg).pieces
            assertEquals(a, PhotoPieces.detect(fr.rgb, fr.w, fr.h, maxBlobs = 60))
            assertEquals(a.map { listOf(it.cx, it.cy, it.shape, it.size) }, LooseBench.detect(fr.rgb, fr.w, fr.h).map { listOf(it.x, it.y, it.shape, it.size) })
            val cam = GroundCam.fromFrame(fr)
            assertEquals(PhotoPieces.analyze(seg, cam).pieces, PhotoPieces.detect(fr.rgb, fr.w, fr.h, maxBlobs = 60, cam = cam))
        }
    }
}
