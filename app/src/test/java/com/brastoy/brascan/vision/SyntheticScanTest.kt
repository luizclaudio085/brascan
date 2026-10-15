package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Scanner virtual": renderiza uma construção conhecida de vários ângulos (cor + profundidade com ruído,
 * como o ARCore entrega), passa pelo pipeline inteiro e confere o que foi reconstruído.
 */
class SyntheticScanTest {
    private val tiles get() = SyntheticScene.tiles
    private val e = SyntheticScene.e
    private fun render(eye: V3, target: V3, rnd: Random) = SyntheticScene.render(eye, target, rnd)

    @Test fun reconstructsShapesPosesAndColors() {
        val map = FusionMap()
        val rnd = Random(3)
        val target = V3(-.02f, .04f, .04f)
        for (i in 0 until 12) {
            val a = i / 12.0 * 2 * Math.PI
            val eye = V3((cos(a) * .42).toFloat(), .22f + (i % 3) * .06f, (sin(a) * .42).toFloat())
            FrameProcessor.process(render(eye, target, rnd), map)
        }
        val found = map.extract()
        val report = StringBuilder("reconstruído:\n")
        found.forEach { report.append("  ${it.shape} ${it.size} #%06X c=%s n=%s w=%.3f h=%.3f\n".format(it.color and 0xFFFFFF, fmt(it.center), fmt(it.n), it.w, it.h)) }
        val misses = ArrayList<String>()
        for (tl in tiles) {
            val bin = Segmenter.hueBin(FloatArray(3).also { Segmenter.hsv(tl.color, it) }[0])
            val cand = found.filter { Segmenter.hueBin(FloatArray(3).also { a -> Segmenter.hsv(it.color, a) }[0]) == bin }.minByOrNull { (it.center - tl.c).len() }
            if (cand == null) { misses += "${tl.name}: não achou"; continue }
            val dist = (cand.center - tl.c).len()
            val ang = Math.toDegrees(acos(abs(cand.n.dot(tl.n)).toDouble().coerceAtMost(1.0)))
            if (dist > .015f) misses += "${tl.name}: centro errado por %.1f cm".format(dist * 100)
            if (ang > 8) misses += "${tl.name}: normal errada por %.0f°".format(ang)
            if (cand.shape != tl.shape) misses += "${tl.name}: forma ${cand.shape} (esperado ${tl.shape})"
            if (cand.size != "grande") misses += "${tl.name}: tamanho ${cand.size}"
        }
        println(report)
        assertTrue("$misses\n$report", misses.isEmpty())
    }

    /** Celular sem profundidade: só a nuvem esparsa de pontos do SLAM (pontos nas bordas/texturas das peças). */
    @Test fun pointCloudOnlyStillFindsPieces() {
        val map = FusionMap()
        val rnd = Random(5)
        val target = V3(-.02f, .04f, .04f)
        for (i in 0 until 30) {
            val a = i / 30.0 * 2 * Math.PI
            val eye = V3((cos(a) * .42).toFloat(), .25f, (sin(a) * .42).toFloat())
            val full = render(eye, target, rnd)
            val cloud = ArrayList<Float>()
            for (tl in tiles) repeat(60) {
                val p = V3((rnd.nextFloat() - .5f) * e * 2, (rnd.nextFloat() - .5f) * e * 2, 0f)
                if (tl.inside(p.x, p.y)) { val w = tl.c + tl.u * p.x + tl.v * p.y + tl.n * (rnd.nextGaussian() * .001).toFloat(); cloud += w.x; cloud += w.y; cloud += w.z; cloud += 1f }
            }
            FrameProcessor.process(FrameSample(full.rgb, full.w, full.h, full.fx, full.fy, full.cx, full.cy, full.camToWorld, cloud = cloud.toFloatArray()), map)
        }
        val found = map.extract(minObs = 1)
        val colors = found.map { Segmenter.hueBin(FloatArray(3).also { a -> Segmenter.hsv(it.color, a) }[0]) }.toSet()
        println("só nuvem: " + found.joinToString { "${it.shape}/${it.size}" })
        assertTrue("achou ${colors.size} cores: $found", colors.size >= 5)
    }

    private fun fmt(v: V3) = "(%.3f, %.3f, %.3f)".format(v.x, v.y, v.z)
}
