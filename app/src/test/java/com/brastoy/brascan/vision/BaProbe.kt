package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/** Diagnóstico: o que cada ajuste global faz com as peças no cenário de gravidade ruidosa. */
class BaProbe {
    @Test fun probe() {
        val k = Intrinsics(390.0, 390.0, 240.0, 180.0)
        val slam = PieceSlam(k); val rnd = Random(4); val target = V3(-.02f, .04f, .04f)
        var nRep = 0
        for (i in 0 until 40) {
            val a = i * 1.15 * 2 * Math.PI / 40
            val eye = V3((cos(a) * .40).toFloat(), (.24 + .05 * sin(i * .7)).toFloat(), (sin(a) * .40).toFloat())
            val f = SyntheticScene.render(eye, target, rnd, 480, 360)
            val fw = (target - eye).norm(); val r = fw.cross(V3.UP).norm(); val up = r.cross(fw)
            val u = doubleArrayOf(V3.UP.dot(r).toDouble(), -V3.UP.dot(up).toDouble(), V3.UP.dot(fw).toDouble())
            val before = slam.map.associate { it.id to it.pose }
            slam.track(Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f), DoubleArray(3) { u[it] + rnd.nextGaussian() * Math.toRadians(2.0) })
            if (slam.baReports.size > nRep) {
                val rep = slam.baReports.last(); nRep = slam.baReports.size
                println("q$i ajuste: aceito=${rep.accepted} ${rep.note} reproj %.1f→%.1f px, kfs=${slam.keyframes.size}".format(rep.rmsBefore, rep.rmsAfter))
                for (mp in slam.map) before[mp.id]?.let { b -> val ang = Math.toDegrees(Rot.angle(b.r, mp.pose.r)); if (ang > 3) println("   ${mp.shape} #%06X girou %.1f° moveu %.1f cm".format(mp.color and 0xFFFFFF, ang, PieceSlam.dist(b.t, mp.pose.t) * 100)) }
            }
        }
    }
}
