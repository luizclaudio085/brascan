package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Intrínsecos da câmera (pixels da imagem analisada). Convenção OpenCV: x direita, y baixo, z para frente. */
data class Intrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double)

/** Pose rígida: p_destino = R · p_origem + t. R em ordem de linhas (row-major, 3×3). */
class Pose(val r: DoubleArray, val t: DoubleArray) {
    fun apply(p: DoubleArray) = doubleArrayOf(
        r[0] * p[0] + r[1] * p[1] + r[2] * p[2] + t[0],
        r[3] * p[0] + r[4] * p[1] + r[5] * p[2] + t[1],
        r[6] * p[0] + r[7] * p[1] + r[8] * p[2] + t[2],
    )
    fun rotate(p: DoubleArray) = doubleArrayOf(r[0] * p[0] + r[1] * p[1] + r[2] * p[2], r[3] * p[0] + r[4] * p[1] + r[5] * p[2], r[6] * p[0] + r[7] * p[1] + r[8] * p[2])
    fun inverse(): Pose {
        val rt = doubleArrayOf(r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8])
        val ti = Pose(rt, doubleArrayOf(0.0, 0.0, 0.0)).rotate(t)
        return Pose(rt, doubleArrayOf(-ti[0], -ti[1], -ti[2]))
    }
    /** this ∘ o  (primeiro o, depois this) */
    fun compose(o: Pose): Pose {
        val m = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) m[i * 3 + j] = r[i * 3] * o.r[j] + r[i * 3 + 1] * o.r[3 + j] + r[i * 3 + 2] * o.r[6 + j]
        return Pose(m, apply(o.t))
    }
    fun col(j: Int) = doubleArrayOf(r[j], r[3 + j], r[6 + j])
    fun quat(): DoubleArray = Rot.toQuat(r)

    companion object {
        val IDENTITY get() = Pose(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0))
    }
}

object Rot {
    /** Vetor de rotação (Rodrigues) → matriz. */
    fun exp(w: DoubleArray): DoubleArray {
        val th = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2])
        if (th < 1e-12) return doubleArrayOf(1.0, -w[2], w[1], w[2], 1.0, -w[0], -w[1], w[0], 1.0)
        val k = doubleArrayOf(w[0] / th, w[1] / th, w[2] / th); val c = cos(th); val s = sin(th); val v = 1 - c
        return doubleArrayOf(
            c + k[0] * k[0] * v, k[0] * k[1] * v - k[2] * s, k[0] * k[2] * v + k[1] * s,
            k[1] * k[0] * v + k[2] * s, c + k[1] * k[1] * v, k[1] * k[2] * v - k[0] * s,
            k[2] * k[0] * v - k[1] * s, k[2] * k[1] * v + k[0] * s, c + k[2] * k[2] * v,
        )
    }

    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val m = DoubleArray(9)
        for (i in 0 until 3) for (j in 0 until 3) m[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
        return m
    }

    fun toQuat(r: DoubleArray): DoubleArray { // (x, y, z, w)
        val tr = r[0] + r[4] + r[8]
        return when {
            tr > 0 -> { val s = sqrt(tr + 1) * 2; doubleArrayOf((r[7] - r[5]) / s, (r[2] - r[6]) / s, (r[3] - r[1]) / s, .25 * s) }
            r[0] > r[4] && r[0] > r[8] -> { val s = sqrt(1 + r[0] - r[4] - r[8]) * 2; doubleArrayOf(.25 * s, (r[1] + r[3]) / s, (r[2] + r[6]) / s, (r[7] - r[5]) / s) }
            r[4] > r[8] -> { val s = sqrt(1 + r[4] - r[0] - r[8]) * 2; doubleArrayOf((r[1] + r[3]) / s, .25 * s, (r[5] + r[7]) / s, (r[2] - r[6]) / s) }
            else -> { val s = sqrt(1 + r[8] - r[0] - r[4]) * 2; doubleArrayOf((r[2] + r[6]) / s, (r[5] + r[7]) / s, .25 * s, (r[3] - r[1]) / s) }
        }
    }

    fun fromQuat(q: DoubleArray): DoubleArray {
        val n = sqrt(q.sumOf { it * it }); val x = q[0] / n; val y = q[1] / n; val z = q[2] / n; val w = q[3] / n
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
    }

    /** Ângulo (rad) entre duas rotações. */
    fun angle(a: DoubleArray, b: DoubleArray): Double {
        val tr = a[0] * b[0] + a[3] * b[3] + a[6] * b[6] + a[1] * b[1] + a[4] * b[4] + a[7] * b[7] + a[2] * b[2] + a[5] * b[5] + a[8] * b[8]
        return kotlin.math.acos(((tr - 1) / 2).coerceIn(-1.0, 1.0))
    }

    /** Ortonormaliza (Gram-Schmidt nas colunas, mantendo r1 e r2 o mais fiéis possível). */
    fun orthonormal(c1: DoubleArray, c2: DoubleArray): DoubleArray {
        fun n(v: DoubleArray): DoubleArray { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l) }
        val a = n(c1); val b0 = n(c2)
        // bissetriz: distribui o erro entre as duas colunas
        val s = n(doubleArrayOf(a[0] + b0[0], a[1] + b0[1], a[2] + b0[2])); val d = n(doubleArrayOf(a[0] - b0[0], a[1] - b0[1], a[2] - b0[2]))
        val r1 = n(doubleArrayOf((s[0] + d[0]), (s[1] + d[1]), (s[2] + d[2]))); val r2 = n(doubleArrayOf((s[0] - d[0]), (s[1] - d[1]), (s[2] - d[2])))
        val r3 = doubleArrayOf(r1[1] * r2[2] - r1[2] * r2[1], r1[2] * r2[0] - r1[0] * r2[2], r1[0] * r2[1] - r1[1] * r2[0])
        return doubleArrayOf(r1[0], r2[0], r3[0], r1[1], r2[1], r3[1], r1[2], r2[2], r3[2])
    }
}

/**
 * Pose de um polígono plano de medidas conhecidas a partir dos cantos na imagem:
 * homografia (DLT) → decomposição com K → refinamento Gauss-Newton do erro de reprojeção.
 */
object PlanarPose {
    class Result(val pose: Pose, val rms: Double)

    /** [model]: cantos no plano da peça (metros, z=0). [img]: mesmos cantos em pixels, mesma ordem. */
    fun solve(model: List<DoubleArray>, img: List<DoubleArray>, k: Intrinsics): Result? {
        if (model.size < 3 || model.size != img.size) return null
        val norm = img.map { doubleArrayOf((it[0] - k.cx) / k.fx, (it[1] - k.cy) / k.fy) }
        var m = model; var nm = norm
        if (model.size == 3) { // triângulo: centroide como 4º ponto só para a estimativa inicial; o refino usa os 3 cantos
            m = model + doubleArrayOf(model.sumOf { it[0] } / 3, model.sumOf { it[1] } / 3)
            nm = norm + doubleArrayOf(norm.sumOf { it[0] } / 3, norm.sumOf { it[1] } / 3)
        }
        val h = homography(m, nm) ?: return null
        val h1 = doubleArrayOf(h[0], h[3], h[6]); val h2 = doubleArrayOf(h[1], h[4], h[7]); val h3 = doubleArrayOf(h[2], h[5], h[8])
        fun len(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        var lam = 2 / (len(h1) + len(h2))
        if (h3[2] * lam < 0) lam = -lam // a peça tem que estar na frente da câmera
        val r = Rot.orthonormal(doubleArrayOf(h1[0] * lam, h1[1] * lam, h1[2] * lam), doubleArrayOf(h2[0] * lam, h2[1] * lam, h2[2] * lam))
        var pose = Pose(r, doubleArrayOf(h3[0] * lam, h3[1] * lam, h3[2] * lam))
        pose = refine(pose, model, img, k)
        return Result(pose, rms(pose, model, img, k))
    }

    /**
     * Ambiguidade clássica de alvos planos pequenos (forte em triângulos): a pose "espelhada" — normal refletida
     * em torno da linha de visão — projeta quase igual. Devolve essa alternativa já refinada.
     */
    fun mirrored(p: Pose, model: List<DoubleArray>, img: List<DoubleArray>, k: Intrinsics): Result? {
        val l = sqrt(p.t.sumOf { it * it }); val v = DoubleArray(3) { p.t[it] / l }
        val n = p.col(2)
        val ax = doubleArrayOf(n[1] * v[2] - n[2] * v[1], n[2] * v[0] - n[0] * v[2], n[0] * v[1] - n[1] * v[0])
        val s = sqrt(ax.sumOf { it * it }); if (s < 1e-6) return null
        val dot = n.indices.sumOf { n[it] * v[it] }
        // ângulo entre a normal e a linha de visão (do lado mais próximo); gira o dobro para o outro lado
        val phi = kotlin.math.acos(abs(dot).coerceAtMost(1.0)) * (if (dot < 0) -1 else 1)
        val r = Rot.mul(Rot.exp(DoubleArray(3) { ax[it] / s * 2 * phi }), p.r)
        val q = refine(Pose(r, p.t.clone()), model, img, k)
        return Result(q, rms(q, model, img, k))
    }

    fun project(p: Pose, x: DoubleArray, k: Intrinsics): DoubleArray {
        val c = p.apply(doubleArrayOf(x[0], x[1], 0.0))
        return doubleArrayOf(k.fx * c[0] / c[2] + k.cx, k.fy * c[1] / c[2] + k.cy, c[2])
    }

    fun rms(p: Pose, model: List<DoubleArray>, img: List<DoubleArray>, k: Intrinsics): Double {
        var s = 0.0
        for (i in model.indices) { val q = project(p, model[i], k); if (q[2] <= 0) return Double.MAX_VALUE; s += (q[0] - img[i][0]).let { it * it } + (q[1] - img[i][1]).let { it * it } }
        return sqrt(s / model.size)
    }

    /** Gauss-Newton em 6 parâmetros (rotação incremental + translação), Jacobiano numérico. */
    fun refine(p0: Pose, model: List<DoubleArray>, img: List<DoubleArray>, k: Intrinsics, iters: Int = 12): Pose {
        var p = p0
        fun residual(pp: Pose): DoubleArray { val r = DoubleArray(model.size * 2); for (i in model.indices) { val q = project(pp, model[i], k); r[2 * i] = q[0] - img[i][0]; r[2 * i + 1] = q[1] - img[i][1] }; return r }
        fun perturb(pp: Pose, d: DoubleArray) = Pose(Rot.mul(Rot.exp(doubleArrayOf(d[0], d[1], d[2])), pp.r), doubleArrayOf(pp.t[0] + d[3], pp.t[1] + d[4], pp.t[2] + d[5]))
        repeat(iters) {
            val r0 = residual(p); val n = r0.size
            val j = Array(n) { DoubleArray(6) }
            for (c in 0 until 6) {
                val eps = if (c < 3) 1e-6 else 1e-6 * maxOf(1e-3, abs(p.t[2]))
                val d = DoubleArray(6); d[c] = eps
                val r1 = residual(perturb(p, d))
                for (i in 0 until n) j[i][c] = (r1[i] - r0[i]) / eps
            }
            // (JᵀJ + μI) δ = -Jᵀr
            val a = Array(6) { DoubleArray(6) }; val b = DoubleArray(6)
            for (i in 0 until n) for (x in 0 until 6) { b[x] -= j[i][x] * r0[i]; for (y in 0 until 6) a[x][y] += j[i][x] * j[i][y] }
            for (x in 0 until 6) a[x][x] *= 1.0001
            val d = Linear.solve(a, b) ?: return p
            val np = perturb(p, d)
            if (rms(np, model, img, k) <= rms(p, model, img, k)) p = np else return p
            if (d.sumOf { it * it } < 1e-18) return p
        }
        return p
    }

    /** Homografia (h33 = 1) por DLT com ≥ 4 pontos (mínimos quadrados). */
    fun homography(src: List<DoubleArray>, dst: List<DoubleArray>): DoubleArray? {
        val a = Array(8) { DoubleArray(8) }; val b = DoubleArray(8)
        for (i in src.indices) {
            val (x, y) = src[i][0] to src[i][1]; val (u, v) = dst[i][0] to dst[i][1]
            val r1 = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y); val r2 = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y)
            for (p in 0 until 8) { b[p] += r1[p] * u + r2[p] * v; for (q in 0 until 8) a[p][q] += r1[p] * r1[q] + r2[p] * r2[q] }
        }
        val h = Linear.solve(a, b) ?: return null
        return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0)
    }
}

object Linear {
    /** Eliminação de Gauss com pivotamento parcial. */
    fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val n = b0.size; val a = Array(n) { a0[it].clone() }; val b = b0.clone()
        for (c in 0 until n) {
            var p = c; for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (abs(a[p][c]) < 1e-14) return null
            val t = a[c]; a[c] = a[p]; a[p] = t; val tb = b[c]; b[c] = b[p]; b[p] = tb
            for (r in c + 1 until n) { val f = a[r][c] / a[c][c]; for (k in c until n) a[r][k] -= f * a[c][k]; b[r] -= f * b[c] }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) { var s = b[r]; for (k in r + 1 until n) s -= a[r][k] * x[k]; x[r] = s / a[r][r] }
        return x
    }
}
