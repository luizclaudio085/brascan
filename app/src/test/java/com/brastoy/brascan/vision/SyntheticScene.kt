package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import java.util.Random
import kotlin.math.abs

/** Construção conhecida + "câmera virtual" (cor e profundidade com ruído), usada pelos testes de reconstrução. */
object SyntheticScene {
    class Tile(val name: String, val shape: String, val color: Int, val c: V3, val u: V3, val v: V3, val inside: (Float, Float) -> Boolean) {
        val n = u.cross(v).norm()
    }

    val e = .075f; private val hh = e / 2
    private fun sq(a: Float, b: Float) = abs(a) < hh && abs(b) < hh
    private val X = V3(1f, 0f, 0f); private val Y = V3(0f, 1f, 0f); private val Z = V3(0f, 0f, 1f)
    private val t = e * .866f

    val tiles = listOf(
        Tile("vermelho em pé", "quadrado", 0xFFE53950.toInt(), V3(0f, hh, 0f), X, Y, ::sq),
        Tile("azul em pé de lado", "quadrado", 0xFF2F8CFF.toInt(), V3(hh + .004f, hh, -hh - .004f), Z * -1f, Y, ::sq),
        Tile("amarelo deitado", "quadrado", 0xFFFFC531.toInt(), V3(.13f, .003f, .06f), X, Z * -1f, ::sq),
        Tile("triângulo verde em cima", "equilatero", 0xFF2EC27E.toInt(), V3(0f, e + .002f + t / 3, 0f), X, Y) { a, b -> b > -t / 3 && b < 2 * t / 3 && abs(a) < (2 * t / 3 - b) / 1.732f },
        Tile("retângulo roxo", "retangulo", 0xFF9B5DE5.toInt(), V3(-.17f, e / 4, .05f), X, Y) { a, b -> abs(a) < hh && abs(b) < e / 4 },
        Tile("moldura laranja", "moldura", 0xFFFF7A2F.toInt(), V3(-.07f, hh, .16f), Z, Y) { a, b -> sq(a, b) && !(abs(a) < e * .3f && abs(b) < e * .3f) },
    )

    fun render(eye: V3, target: V3, rnd: Random, w: Int = 240, h: Int = 180): FrameSample {
        val fx = w * 195f / 240; val fy = fx; val cx = w / 2f; val cy = h / 2f
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

}
