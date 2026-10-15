package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

class BundleAdjustTest {
    private val k = Intrinsics(390.0, 390.0, 240.0, 180.0)

    private fun lookAt(eye: DoubleArray, target: DoubleArray): Pose {
        // câmera→mundo, convenção OpenCV (x direita, y baixo, z frente)
        val f = PieceSlam.norm(DoubleArray(3) { target[it] - eye[it] })
        val r = PieceSlam.norm(doubleArrayOf(-f[2], 0.0, f[0])) // frente × cima(0,1,0) → direita
        val d = doubleArrayOf(f[1] * r[2] - f[2] * r[1], f[2] * r[0] - f[0] * r[2], f[0] * r[1] - f[1] * r[0]) // baixo = frente × direita
        return Pose(doubleArrayOf(r[0], d[0], f[0], r[1], d[1], f[1], r[2], d[2], f[2]), eye)
    }

    /** Cena exata: 6 peças, 12 câmeras em volta; poses iniciais estragadas; o ajuste tem que voltar perto da verdade. */
    @Test fun recoversPosesFromPerturbedStart() {
        val rnd = Random(2)
        val model = PieceSlam(k).modelCorners("quadrado")
        val truthP = List(6) { i -> Pose(Rot.exp(doubleArrayOf(0.0, i * .9, 0.0)), doubleArrayOf((i % 3 - 1) * .09, .04, (i / 3 - .5) * .09)) }
        val truthC = List(12) { i -> val a = i * 2 * Math.PI / 12; lookAt(doubleArrayOf(cos(a) * .4, .25, sin(a) * .4), doubleArrayOf(0.0, .03, 0.0)) }
        val ups = truthC.map { c -> c.inverse().rotate(doubleArrayOf(0.0, 1.0, 0.0)) }
        val obs = ArrayList<BundleAdjust.Obs>()
        for ((ci, c) in truthC.withIndex()) for ((pi, p) in truthP.withIndex()) {
            val piece2cam = c.inverse().compose(p)
            if (piece2cam.col(2).let { n -> val ray = PieceSlam.norm(piece2cam.t); kotlin.math.abs(n[0] * ray[0] + n[1] * ray[1] + n[2] * ray[2]) } < .25) continue // de lado demais
            val px = model.map { x -> val q = PlanarPose.project(piece2cam, x, k); doubleArrayOf(q[0] + rnd.nextGaussian() * .5, q[1] + rnd.nextGaussian() * .5) }
            obs += BundleAdjust.Obs(ci, pi, model, px, 1.0)
        }
        fun jitter(p: Pose, t: Double, a: Double) = Pose(Rot.mul(Rot.exp(DoubleArray(3) { rnd.nextGaussian() * a }), p.r), DoubleArray(3) { p.t[it] + rnd.nextGaussian() * t })
        val cams = truthC.mapIndexed { i, c -> if (i == 0) c else jitter(c, .02, Math.toRadians(3.0)) }.toMutableList()
        val pcs = truthP.map { jitter(it, .02, Math.toRadians(3.0)) }.toMutableList()
        fun err() = pcs.indices.maxOf { PieceSlam.dist(pcs[it].t, truthP[it].t) }
        val before = err()
        val rep = BundleAdjust.run(k, cams, ups, pcs, obs)
        val after = err()
        println("BA puro: erro máx. peça %.2f cm → %.2f cm; reprojeção %.1f px → %.2f px em ${rep.iterations} iterações".format(before * 100, after * 100, rep.rmsBefore, rep.rmsAfter))
        assertTrue("erro depois %.2f cm".format(after * 100), after < .004)
        assertTrue(rep.rmsAfter < 1.0)
    }

    private fun scan(slam: PieceSlam, frames: Int, turns: Double, res: Int, noiseDeg: Double) {
        val rnd = Random(21); val target = V3(-.02f, .04f, .04f)
        for (i in 0 until frames) {
            val a = i * turns * 2 * Math.PI / frames
            val eye = V3((cos(a) * .40).toFloat(), (.24 + .05 * sin(i * .7)).toFloat(), (sin(a) * .40).toFloat())
            val f = SyntheticScene.render(eye, target, rnd, res, res * 3 / 4)
            val fw = (target - eye).norm(); val r = fw.cross(V3.UP).norm(); val up = r.cross(fw)
            val u = doubleArrayOf(V3.UP.dot(r).toDouble(), -V3.UP.dot(up).toDouble(), V3.UP.dot(fw).toDouble())
            slam.track(Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f), DoubleArray(3) { u[it] + rnd.nextGaussian() * Math.toRadians(noiseDeg) })
        }
    }

    private fun worstPairError(found: List<Piece3D>): Double {
        val pairs = SyntheticScene.tiles.mapNotNull { t ->
            val bin = Segmenter.hueBin(FloatArray(3).also { Segmenter.hsv(t.color, it) }[0])
            found.filter { it.shape == t.shape && Segmenter.hueBin(FloatArray(3).also { a -> Segmenter.hsv(it.color, a) }[0]) == bin }.maxByOrNull { it.support }?.let { t to it }
        }
        var w = 0.0; var which = ""
        for (i in pairs.indices) for (j in i + 1 until pairs.size) {
            val e = kotlin.math.abs(((pairs[i].first.c - pairs[j].first.c).len() - (pairs[i].second.center - pairs[j].second.center).len()).toDouble())
            if (e > w) { w = e; which = "${pairs[i].first.name} × ${pairs[j].first.name}" }
        }
        println("  pior par: $which (%.1f cm)".format(w * 100))
        return w
    }

    /** Duas voltas com câmera de menor resolução e gravidade ruidosa: erro do mapa sem e com o ajuste global. */
    @Test fun loopClosureReducesDrift() {
        val k2 = Intrinsics(292.5, 292.5, 180.0, 135.0)
        val plain = PieceSlam(k2, autoOptimize = false); scan(plain, 70, 2.0, 360, 3.0)
        val before = worstPairError(plain.pieces())
        val ba = PieceSlam(k2, autoOptimize = true)
        val t0 = System.nanoTime(); scan(ba, 70, 2.0, 360, 3.0); val tScan = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime(); ba.optimize(); val tBa = (System.nanoTime() - t1) / 1e6
        println("tempo: 70 quadros com ajustes automáticos %.0f ms (%.0f ms/quadro); ajuste final %.0f ms com ${ba.keyframes.size} quadros-chave".format(tScan, tScan / 70, tBa))
        val after = worstPairError(ba.pieces())
        ba.pieces().forEach { println("  ${it.shape} ${it.size} #%06X vistas=${it.support} c=(%.3f,%.3f,%.3f)".format(it.color and 0xFFFFFF, it.center.x, it.center.y, it.center.z)) }
        plain.pieces().forEach { println("  sem: ${it.shape} ${it.size} #%06X vistas=${it.support}".format(it.color and 0xFFFFFF)) }
        println("deriva (pior erro entre pares): sem ajuste %.1f cm → com ajuste %.1f cm; ajustes automáticos: ${ba.baReports.size - 1}; reprojeção última: %.1f → %.2f px".format(
            before * 100, after * 100, ba.baReports.last().rmsBefore, ba.baReports.last().rmsAfter))
        assertTrue("ajuste piorou: %.1f → %.1f cm".format(before * 100, after * 100), after <= before + .003)
        // nível medido em 15/10/2026: 8,5 → 3,4 cm (deriva forte, 360 px, gravidade com 3° de ruído)
        assertTrue("erro depois %.1f cm".format(after * 100), after < .04 && after < before * .5)
        assertTrue("laço não disparou ajuste automático", ba.baReports.size >= 2)
    }
}
