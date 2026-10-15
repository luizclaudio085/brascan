package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Diagnóstico: trajetória estimada × verdadeira (distância e altura relativas ao 1º quadro). */
class SlamTrajectoryProbe {
    @Test fun probe() {
        val k = Intrinsics(390.0, 390.0, 240.0, 180.0)
        val slam = PieceSlam(k); val rnd = Random(4); val target = V3(-.02f, .04f, .04f)
        var e0: V3? = null
        for (i in 0 until 40) {
            val a = i * 1.15 * 2 * Math.PI / 40
            val eye = V3((cos(a) * .40).toFloat(), (.24 + .05 * sin(i * .7)).toFloat(), (sin(a) * .40).toFloat())
            val f = SyntheticScene.render(eye, target, rnd, 480, 360)
            val fw = (target - eye).norm(); val r = fw.cross(V3.UP).norm(); val up = r.cross(fw)
            val upCam = doubleArrayOf(V3.UP.dot(r).toDouble(), -V3.UP.dot(up).toDouble(), V3.UP.dot(fw).toDouble())
            val seg = Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f)
            val dets = slam.track(seg, upCam)
            if (e0 == null) e0 = eye
            val c = slam.camera
            val trueD = (eye - e0).len(); val trueH = eye.y - e0.y
            val estD = c?.let { sqrt(it.t.sumOf { v -> v * v }) } ?: -1.0; val estH = c?.t?.get(1) ?: -1.0
            println("q%02d dets=%d %s match=%d mapa=%d  dist real %.3f est %.3f | altura real %+.3f est %+.3f  formas=%s".format(i, dets.size,
                if (slam.lost) "PERDIDO" else "ok     ", slam.lastMatched, slam.map.size, trueD, estD, trueH, estH, dets.joinToString(",") { it.shape.take(4) + "/" + "%.1f".format(it.rms) }))
        }
    }
}
