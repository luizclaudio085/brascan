package com.brastoy.brascan.vision

import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.hypot

/**
 * Sonda (não é regressão) da frente "ímãs": confusão só de FORMA (sem tamanho) e de onde vêm os falsos positivos
 * na contagem de peças soltas (LooseBench). Grava build/solto/ima-*.ppm (cruz verde = acerto, magenta = falso
 * positivo) e ima-*-rot.ppm (uma cor por região).
 *   ./gradlew :app:testDebugUnitTest --tests '*LooseMagnetProbe*'
 */
class LooseMagnetProbe {
    private val FRAMEISH = setOf("moldura", "janela", "porta", "encaixe", "quadrado")
    /** -Dima.verbose=1: imprime as regiões de cada erro de moldura/janela/porta/encaixe/quadrado. */
    private val verbose = System.getProperty("ima.verbose") != null

    @Test fun probe() {
        val dir = File("build/solto").apply { mkdirs() }
        for ((name, hard) in listOf("fácil" to false, "difícil" to true)) {
            val conf = HashMap<String, Int>(); val fps = ArrayList<String>()
            for (seed in 1..30) {
                val s = LooseBench.scene(seed.toLong(), 12, hard)
                val f = RealisticScene.render(s.tiles, s.eye, s.target, Random(seed.toLong()), s.w, s.h, s.opts)
                val seg = Segmenter.segment(f.rgb, f.w, f.h, maxBlobs = 60, loose = true)
                val dets = PhotoPieces.from(seg)
                val truth = s.tiles.mapNotNull { t ->
                    val c = RealisticScene.project(s.eye, s.target, t.c, s.w, s.h) ?: return@mapNotNull null
                    val c2 = RealisticScene.project(s.eye, s.target, t.c + t.u * t.e, s.w, s.h) ?: return@mapNotNull null
                    if (c[0] < 0 || c[1] < 0 || c[0] >= s.w || c[1] >= s.h) return@mapNotNull null
                    Triple(t, c, hypot(c2[0] - c[0], c2[1] - c[1]))
                }
                val used = HashSet<PhotoPiece>()
                for ((t, c, side) in truth.sortedBy { it.third }) {
                    val d = dets.filter { it !in used }.minByOrNull { hypot(it.cx - c[0], it.cy - c[1]) }?.takeIf { hypot(it.cx - c[0], it.cy - c[1]) < side * .5 }
                    if (d == null) { conf.merge("${t.shape} → -", 1, Int::plus); continue }
                    used += d
                    conf.merge("${t.shape} → ${d.shape}", 1, Int::plus)
                    if (verbose && d.shape != t.shape && (d.shape in FRAMEISH || t.shape in FRAMEISH)) {
                        println("s$seed ${t.name} → ${d.shape}/${d.size} lado=%.0f px".format(side))
                        for (b in seg.blobs) if (c[0] >= b.minX && c[0] <= b.maxX && c[1] >= b.minY && c[1] <= b.maxY) {
                            val hsv = FloatArray(3); Segmenter.hsv(b.color, hsv)
                            println("   blob #${b.id} n=${b.count} caixa ${b.bw}x${b.bh} hue=%.0f sat=%.2f val=%.2f fit=${b.fit?.shape} sol=%.2f furos=${b.fit?.gaps?.count { it.enclosed }} entalhes=${b.fit?.gaps?.count { !it.enclosed }}".format(hsv[0], hsv[1], hsv[2], b.fit?.solidity ?: 0f))
                        }
                    }
                }
                for (d in dets) if (d !in used) {
                    val near = truth.minByOrNull { hypot(d.cx - it.second[0], d.cy - it.second[1]) }
                    val dist = near?.let { hypot(d.cx - it.second[0], d.cy - it.second[1]) / it.third } ?: 99.0
                    fps += "s$seed ${d.shape}/${d.size} em (%.0f,%.0f) %.0fx%.0f lado=%.0f fit=%.2f; mais perto: ${near?.first?.name} a %.2f lados".format(d.cx, d.cy, d.w, d.h, d.side, d.fit, dist)
                }
                if (seed <= 4) {
                    val vis = f.rgb.copyOf()
                    for (d in dets) { val ok = d in used; val col = if (ok) 0xFF00FF00.toInt() else 0xFFFF00FF.toInt()
                        for (k in -3..3) { fun p(x: Int, y: Int) { if (x in 0 until f.w && y in 0 until f.h) vis[y * f.w + x] = col }; p(d.cx.toInt() + k, d.cy.toInt()); p(d.cx.toInt(), d.cy.toInt() + k) } }
                    RealisticScene.savePpm(FrameSample(vis, f.w, f.h, f.fx, f.fy, f.cx, f.cy, f.camToWorld), File(dir, "ima-$name-$seed.ppm"))
                    val pal = IntArray(256) { (0xFF shl 24) or (Random(it * 7919L + 3).nextInt() and 0xFFFFFF) }
                    val lab = IntArray(f.w * f.h) { i -> val l = seg.labels[i]; if (l < 0) ((f.rgb[i] and 0xFEFEFE) shr 1) or (0xFF shl 24) else pal[l and 255] }
                    RealisticScene.savePpm(FrameSample(lab, f.w, f.h, f.fx, f.fy, f.cx, f.cy, f.camToWorld), File(dir, "ima-$name-$seed-rot.ppm"))
                }
            }
            println("== $name: confusão de forma (verdade → detectado)")
            for (sh in Catalog.SHAPES) println("  %-12s ".format(sh) + conf.filterKeys { it.startsWith("$sh →") }.entries.sortedByDescending { it.value }.joinToString { "${it.key.substringAfter("→ ")}×${it.value}" })
            println("  falsos positivos (${fps.size}):"); fps.forEach { println("    $it") }
        }
    }
}
