package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Scanner sem ARCore: volta completa em volta da construção sintética só com imagens coloridas
 * (sem profundidade) + gravidade. O mapa sai num referencial próprio; alinhamos com a verdade
 * (rotação + translação, sem escala) e medimos o erro de cada peça.
 */
class PieceSlamTest {
    private val k = Intrinsics(390.0, 390.0, 240.0, 180.0)

    private fun upInCamera(eye: V3, target: V3): DoubleArray {
        val f = (target - eye).norm(); val r = f.cross(V3.UP).norm(); val up = r.cross(f)
        return doubleArrayOf(V3.UP.dot(r).toDouble(), -V3.UP.dot(up).toDouble(), V3.UP.dot(f).toDouble())
    }

    private fun scan(frames: Int, turns: Double, noiseDeg: Double = 0.0): PieceSlam {
        val slam = PieceSlam(k); val rnd = Random(4); val target = V3(-.02f, .04f, .04f)
        for (i in 0 until frames) {
            val a = i * turns * 2 * Math.PI / frames
            val eye = V3((cos(a) * .40).toFloat(), (.24 + .05 * sin(i * .7)).toFloat(), (sin(a) * .40).toFloat())
            val f = SyntheticScene.render(eye, target, rnd, 480, 360)
            val up = upInCamera(eye, target).let { u -> if (noiseDeg == 0.0) u else DoubleArray(3) { u[it] + rnd.nextGaussian() * Math.toRadians(noiseDeg) } }
            slam.track(Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f), up)
        }
        return slam
    }

    /** Alinhamento rígido (Kabsch, só rotação em torno de Y porque os dois têm Y = cima) + translação. */
    private var yaw = 0f
    private fun align(est: List<V3>, truth: List<V3>): (V3) -> V3 {
        val ce = est.fold(V3.ZERO) { a, b -> a + b } * (1f / est.size); val ct = truth.fold(V3.ZERO) { a, b -> a + b } * (1f / truth.size)
        var sxx = 0.0; var sxz = 0.0
        for (i in est.indices) { val a = est[i] - ce; val b = truth[i] - ct; sxx += a.x * b.x + a.z * b.z; sxz += a.x * b.z - a.z * b.x }
        val th = kotlin.math.atan2(sxz, sxx); val c = cos(th).toFloat(); val s = sin(th).toFloat(); yaw = th.toFloat()
        return { p -> val d = p - ce; V3(d.x * c - d.z * s, d.y, d.x * s + d.z * c) + ct }
    }

    private fun report(slam: PieceSlam): Pair<List<String>, String> {
        val found = slam.pieces()
        val out = StringBuilder("mapa (${slam.frames} quadros):\n")
        found.forEach { out.append("  ${it.shape} ${it.size} #%06X obs=${it.support} c=(%.3f,%.3f,%.3f) n=(%.2f,%.2f,%.2f)\n".format(it.color and 0xFFFFFF, it.center.x, it.center.y, it.center.z, it.n.x, it.n.y, it.n.z)) }
        val pairs = SyntheticScene.tiles.mapNotNull { t ->
            val bin = Segmenter.hueBin(FloatArray(3).also { Segmenter.hsv(t.color, it) }[0])
            found.filter { it.shape == t.shape && Segmenter.hueBin(FloatArray(3).also { a -> Segmenter.hsv(it.color, a) }[0]) == bin }.maxByOrNull { it.support }?.let { t to it }
        }
        val misses = ArrayList<String>()
        for (t in SyntheticScene.tiles) if (pairs.none { it.first === t }) misses += "${t.name}: não mapeada"
        if (pairs.size >= 3) {
            val f = align(pairs.map { it.second.center }, pairs.map { it.first.c })
            for ((t, p) in pairs) {
                val err = (f(p.center) - t.c).len()
                val cy = cos(yaw); val sy = sin(yaw); val n = V3(p.n.x * cy - p.n.z * sy, p.n.y, p.n.x * sy + p.n.z * cy)
                val ang = Math.toDegrees(acos(abs(n.dot(t.n)).toDouble().coerceAtMost(1.0)))
                out.append("  ${t.name}: erro %.1f cm, normal %.1f°, tamanho ${p.size}\n".format(err * 100, ang))
                // limites = nível medido em 15/10/2026 (regressão): triângulo tem só 3 cantos, por isso mais folga
                val maxAng = if (t.shape in setOf("equilatero", "retanguloTri", "isoceles")) 12.0 else 10.0
                if (err > .04f) misses += "${t.name}: posição errada por %.1f cm".format(err * 100)
                if (ang > maxAng) misses += "${t.name}: normal errada por %.0f°".format(ang)
                if (p.size != "grande") misses += "${t.name}: tamanho ${p.size}"
            }
        }
        return misses to out.toString()
    }

    @Test fun fullLoopWithoutDepthRecoversTheBuilding() {
        val slam = scan(40, 1.15)
        val (misses, out) = report(slam)
        println(out)
        assertTrue("$misses\n$out", misses.isEmpty())
    }

    @Test fun noisyGravityStillWorks() {
        val slam = scan(40, 1.15, noiseDeg = 2.0)
        val (misses, out) = report(slam)
        println(out)
        assertTrue("$misses\n$out", misses.isEmpty())
    }

    @Test fun planarPoseOfASingleSquareIsExact() {
        // projeta um quadrado conhecido e confere a pose recuperada
        val model = PieceSlam(k).modelCorners("quadrado")
        val truth = Pose(Rot.exp(doubleArrayOf(.4, -.3, .2)), doubleArrayOf(.03, -.02, .35))
        val img = model.map { val q = PlanarPose.project(truth, it, k); doubleArrayOf(q[0], q[1]) }
        val r = PlanarPose.solve(model, img, k)!!
        val dt = sqrt((0 until 3).sumOf { (r.pose.t[it] - truth.t[it]).let { d -> d * d } })
        assertTrue("translação errada por $dt", dt < 1e-6)
        assertTrue("rotação errada", Rot.angle(r.pose.r, truth.r) < 1e-5)
    }
}
