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
    private class Tile(val name: String, val shape: String, val color: Int, val c: V3, val u: V3, val v: V3, val inside: (Float, Float) -> Boolean) {
        val n = u.cross(v).norm()
    }

    private val e = .075f; private val hh = e / 2
    private fun sq(a: Float, b: Float) = abs(a) < hh && abs(b) < hh
    private val X = V3(1f, 0f, 0f); private val Y = V3(0f, 1f, 0f); private val Z = V3(0f, 0f, 1f)
    private val t = e * .866f

    private val tiles = listOf(
        Tile("vermelho em pé", "quadrado", 0xFFE53950.toInt(), V3(0f, hh, 0f), X, Y, ::sq),
        Tile("azul em pé de lado", "quadrado", 0xFF2F8CFF.toInt(), V3(hh + .004f, hh, -hh - .004f), Z * -1f, Y, ::sq),
        Tile("amarelo deitado", "quadrado", 0xFFFFC531.toInt(), V3(.13f, .003f, .06f), X, Z * -1f, ::sq),
        Tile("triângulo verde em cima", "equilatero", 0xFF2EC27E.toInt(), V3(0f, e + .002f + t / 3, 0f), X, Y) { a, b -> b > -t / 3 && b < 2 * t / 3 && abs(a) < (2 * t / 3 - b) / 1.732f },
        Tile("retângulo roxo", "retangulo", 0xFF9B5DE5.toInt(), V3(-.17f, hh, .05f), X, Y) { a, b -> abs(a) < e && abs(b) < hh },
        Tile("janela laranja", "janela", 0xFFFF7A2F.toInt(), V3(-.07f, hh, .16f), Z, Y) { a, b -> sq(a, b) && !(abs(a) < hh * .62f && abs(b) < hh * .62f) },
    )

    private fun render(eye: V3, target: V3, rnd: Random): FrameSample {
        val w = 240; val h = 180; val fx = 195f; val fy = 195f; val cx = 120f; val cy = 90f
        val f = (target - eye).norm(); val r = f.cross(Y).norm(); val up = r.cross(f)
        val m = floatArrayOf(r.x, r.y, r.z, 0f, up.x, up.y, up.z, 0f, -f.x, -f.y, -f.z, 0f, eye.x, eye.y, eye.z, 1f)
        val rgb = IntArray(w * h); val depth = ShortArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dc = V3((x + .5f - cx) / fx, -(y + .5f - cy) / fy, -1f)
            val d = r * dc.x + up * dc.y + f * 1f // R * dc  (coluna Z = -f, dc.z = -1)
            var best = Float.MAX_VALUE; var col = 0xFFB4B0A8.toInt()
            if (d.y < 0) { val tt = -eye.y / d.y; if (tt > 0) { best = tt; col = if (((eye.x + d.x * tt) * 20).toInt() % 2 == 0) 0xFFB8B4AC.toInt() else 0xFFA9A59E.toInt() } }
            for (tl in tiles) {
                val den = d.dot(tl.n); if (abs(den) < 1e-6f) continue
                val tt = (tl.c - eye).dot(tl.n) / den
                if (tt <= 0 || tt >= best) continue
                val p = eye + d * tt - tl.c
                val a = p.dot(tl.u); val b = p.dot(tl.v)
                if (!tl.inside(a, b)) continue
                // borda escura de 3 mm como nas peças reais
                val edge = !tl.inside(a + .003f, b) || !tl.inside(a - .003f, b) || !tl.inside(a, b + .003f) || !tl.inside(a, b - .003f)
                best = tt; col = if (edge) 0xFF202020.toInt() else shade(tl.color, abs(den))
            }
            rgb[y * w + x] = col
            depth[y * w + x] = if (best == Float.MAX_VALUE) 0 else ((best * 1000) + rnd.nextGaussian() * 3).toInt().coerceIn(0, 32000).toShort()
        }
        return FrameSample(rgb, w, h, fx, fy, cx, cy, m, depth, w, h)
    }

    private fun shade(c: Int, k: Float): Int {
        val s = .7f + .3f * k
        return (0xFF shl 24) or (((c shr 16 and 255) * s).toInt() shl 16) or (((c shr 8 and 255) * s).toInt() shl 8) or ((c and 255) * s).toInt()
    }

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
            if (cand.size != "medio") misses += "${tl.name}: tamanho ${cand.size}"
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
