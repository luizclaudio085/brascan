package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sonda: quanto o detector e o SLAM acertam na cena realista (casinha de peças encostadas) em condições de
 * celular — e onde erram. Imprime por quadro: peças visíveis, acertos, falsos positivos, forma errada.
 * Grava alguns quadros em build/realista (arquivos .ppm) para olhar.
 */
class RealisticScanProbe {
    private val k = Intrinsics(390.0, 390.0, 240.0, 180.0)

    private fun upInCamera(eye: V3, target: V3): DoubleArray {
        val f = (target - eye).norm(); val r = f.cross(V3.UP).norm(); val up = r.cross(f)
        return doubleArrayOf(V3.UP.dot(r).toDouble(), -V3.UP.dot(up).toDouble(), V3.UP.dot(f).toDouble())
    }

    private fun insideQuad(c: List<DoubleArray>, x: Double, y: Double): Boolean {
        var inside = false
        for (i in c.indices) { val a = c[i]; val b = c[(i + 1) % c.size]
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside }
        return inside
    }

    class Tally { var visible = 0; var hit = 0; var fp = 0; var wrongShape = 0; var wrongSize = 0 }

    fun run(name: String, tiles: List<RealisticScene.Tile>, o: RealisticScene.Opts, frames: Int = 24, radius: Float = .42f, height: Float = .28f, save: Boolean = false): Pair<Tally, PieceSlam> {
        val slam = PieceSlam(k); val rnd = Random(11); val target = V3(0f, .05f, 0f); val tally = Tally()
        val dir = File("build/realista").apply { mkdirs() }
        for (i in 0 until frames) {
            val a = i * 2 * Math.PI / frames
            val eye = V3((cos(a) * radius).toFloat(), height + (.03 * sin(i * .9)).toFloat(), (sin(a) * radius).toFloat())
            val f = RealisticScene.render(tiles, eye, target, rnd, 480, 360, o)
            if (save && i % 6 == 0) RealisticScene.savePpm(f, File(dir, "$name-q$i.ppm"))
            val seg = Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f)
            val dets = slam.track(seg, upInCamera(eye, target), f.rgb)
            // verdade: peças de frente para a câmera (|cos| > .25) com o centro dentro da imagem
            val vis = tiles.filter { t -> val p = RealisticScene.project(eye, target, t.c, 480, 360) ?: return@filter false
                p[0] > 10 && p[0] < 470 && p[1] > 10 && p[1] < 350 && abs((t.c - eye).norm().dot(t.n)) > .25f && RealisticScene.visible(tiles, eye, t) }
            val used = HashSet<PieceSlam.Detection>()
            val line = StringBuilder("q$i: ")
            for (t in vis) {
                val p = RealisticScene.project(eye, target, t.c, 480, 360)!!
                val d = dets.firstOrNull { it !in used && insideQuad(it.imgCorners, p[0], p[1]) }
                tally.visible++
                if (d == null) { line.append("[${t.name}: NÃO VISTA] "); continue }
                used += d; tally.hit++
                if (PieceSlam.outlineOf(d.shape) != PieceSlam.outlineOf(t.shape) && d.shape != t.shape) { tally.wrongShape++; line.append("[${t.name}: forma ${d.shape}] ") }
            }
            val fps = dets.filter { it !in used }
            tally.fp += fps.size
            if (fps.isNotEmpty()) line.append("FP: ${fps.joinToString { "${it.shape}/${it.areaPx}px" }}")
            println(line.toString().trimEnd() + "  · mapa ${slam.map.size}, lost=${slam.lost}")
        }
        val found = slam.pieces()
        println("$name: visíveis ${tally.visible}, vistas ${tally.hit} (${100 * tally.hit / tally.visible.coerceAtLeast(1)}%), falsos positivos ${tally.fp}, forma errada ${tally.wrongShape}")
        println("  mapa final: ${slam.map.size} no mapa, ${found.size} confirmadas (verdade: ${tiles.size})")
        for (p in found) println("    ${p.shape} ${p.size} #%06X obs=${p.support} c=(%.3f, %.3f, %.3f) n=(%.2f, %.2f, %.2f)".format(p.color and 0xFFFFFF, p.center.x, p.center.y, p.center.z, p.n.x, p.n.y, p.n.z))
        return tally to slam
    }

    @Test fun probe() {
        run("limpa", RealisticScene.house(), RealisticScene.Opts(noise = 0f, carpet = false, shadow = false), save = true)
        run("celular", RealisticScene.house(), RealisticScene.Opts(noise = 4f, blur = 2, carpet = true, shadow = true), save = true) // 480 px vindos de YUV 4:2:0 com média de bloco: suave
        run("escura", RealisticScene.house(), RealisticScene.Opts(exposure = .6f, noise = 6f, blur = 1))
    }
}
