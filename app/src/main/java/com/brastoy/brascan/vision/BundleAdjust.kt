package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ajuste de feixes (bundle adjustment) para o SLAM por peças: otimiza juntas as poses das câmeras
 * (quadros-chave) e das peças minimizando o erro de reprojeção dos cantos, com a gravidade medida
 * como restrição extra. Levenberg-Marquardt + Huber (observação absurda — ex.: laço falso — pesa pouco).
 * Escala absoluta vem das medidas reais das peças (cantos em metros).
 */
object BundleAdjust {
    /** Observação: cantos do molde (m, plano da peça) ↔ pixels medidos, num quadro-chave. */
    class Obs(val kf: Int, val piece: Int, val model: List<DoubleArray>, val px: List<DoubleArray>, val sigma: Double)

    /** [accepted] = false: resultado descartado (divergiu, piorou ou saltou demais) e o mapa anterior foi mantido. */
    class Report(val iterations: Int, val rmsBefore: Double, val rmsAfter: Double, val outliers: Int, val accepted: Boolean = true, val note: String = "")

    /**
     * [cams]: câmera→mundo por quadro-chave (a 0 fica fixa: define o referencial). [pieces]: peça→mundo.
     * [ups]: "para cima" medido em cada quadro-chave (coordenadas da câmera), ou null.
     * Altera [cams] e [pieces] no lugar.
     */
    fun run(k: Intrinsics, cams: MutableList<Pose>, ups: List<DoubleArray?>, pieces: MutableList<Pose>, obs: List<Obs>, maxIter: Int = 15): Report {
        val nc = cams.size; val np = pieces.size
        val n = 6 * ((nc - 1) + np) // câmera 0 fixa
        fun ci(c: Int) = if (c == 0) -1 else 6 * (c - 1)
        fun pi(p: Int) = 6 * (nc - 1) + 6 * p
        val huber = 2.0; val sigmaG = .07 // ~4°: a gravidade só segura a inclinação; quem manda são os cantos

        fun obsRes(o: Obs, cam: Pose, piece: Pose): DoubleArray {
            val inv = cam.inverse(); val r = DoubleArray(o.model.size * 2)
            for (i in o.model.indices) {
                val w = piece.apply(doubleArrayOf(o.model[i][0], o.model[i][1], 0.0)); val c = inv.apply(w)
                val z = if (c[2] < 1e-4) 1e-4 else c[2]
                r[2 * i] = (k.fx * c[0] / z + k.cx - o.px[i][0]) / o.sigma; r[2 * i + 1] = (k.fy * c[1] / z + k.cy - o.px[i][1]) / o.sigma
            }
            return r
        }
        fun gravRes(cam: Pose, up: DoubleArray): DoubleArray { val g = cam.rotate(up); val l = sqrt(g.sumOf { it * it }); return doubleArrayOf(g[0] / l / sigmaG, g[2] / l / sigmaG) }
        fun huberW(r: DoubleArray): Double { val e = sqrt(r.sumOf { it * it } / (r.size / 2)); return if (e <= huber) 1.0 else huber / e }
        fun cost(cs: List<Pose>, ps: List<Pose>): Double {
            var s = 0.0
            for (o in obs) { val r = obsRes(o, cs[o.kf], ps[o.piece]); val w = huberW(r); s += w * r.sumOf { it * it } }
            for (c in ups.indices) ups[c]?.let { s += gravRes(cs[c], it).sumOf { v -> v * v } }
            return s
        }
        fun rmsPx(cs: List<Pose>, ps: List<Pose>): Double {
            var s = 0.0; var m = 0
            for (o in obs) { val r = obsRes(o, cs[o.kf], ps[o.piece]); for (v in r) { s += (v * o.sigma) * (v * o.sigma) }; m += r.size / 2 }
            return if (m == 0) 0.0 else sqrt(s / m)
        }
        fun perturb(p: Pose, d: DoubleArray, off: Int) = Pose(Rot.mul(Rot.exp(doubleArrayOf(d[off], d[off + 1], d[off + 2])), p.r), doubleArrayOf(p.t[0] + d[off + 3], p.t[1] + d[off + 4], p.t[2] + d[off + 5]))

        val before = rmsPx(cams, pieces)
        if (n == 0 || obs.isEmpty()) return Report(0, before, before, 0)
        var lambda = 1e-3; var cur = cost(cams, pieces); var it = 0
        while (it < maxIter) {
            it++
            val h = Array(n) { DoubleArray(n) }; val g = DoubleArray(n)
            // bloco de cada observação: 6 parâmetros da câmera + 6 da peça (diferenças finitas)
            for (o in obs) {
                val cam = cams[o.kf]; val pc = pieces[o.piece]
                val r0 = obsRes(o, cam, pc); val w = huberW(r0)
                val idx = IntArray(12) { -1 }; val jac = Array(12) { DoubleArray(r0.size) }
                for (q in 0 until 12) {
                    val isCam = q < 6; val base = if (isCam) ci(o.kf) else pi(o.piece); if (base < 0) continue
                    val eps = if (q % 6 < 3) 1e-6 else 1e-6
                    val d = DoubleArray(6); d[q % 6] = eps
                    val r1 = if (isCam) obsRes(o, perturb(cam, d, 0), pc) else obsRes(o, cam, perturb(pc, d, 0))
                    for (i in r0.indices) jac[q][i] = (r1[i] - r0[i]) / eps
                    idx[q] = base + q % 6
                }
                for (a in 0 until 12) { val ia = idx[a]; if (ia < 0) continue
                    var ga = 0.0; for (i in r0.indices) ga += jac[a][i] * r0[i]; g[ia] += w * ga
                    for (b in 0 until 12) { val ib = idx[b]; if (ib < 0) continue
                        var s = 0.0; for (i in r0.indices) s += jac[a][i] * jac[b][i]; h[ia][ib] += w * s } }
            }
            for (c in 1 until nc) ups[c]?.let { up ->
                val r0 = gravRes(cams[c], up); val base = ci(c)
                val jac = Array(3) { q -> val d = DoubleArray(6); d[q] = 1e-6; val r1 = gravRes(perturb(cams[c], d, 0), up); DoubleArray(2) { (r1[it] - r0[it]) / 1e-6 } }
                for (a in 0 until 3) { g[base + a] += jac[a][0] * r0[0] + jac[a][1] * r0[1]; for (b in 0 until 3) h[base + a][base + b] += jac[a][0] * jac[b][0] + jac[a][1] * jac[b][1] }
            }
            var improved = false
            for (tries in 0 until 6) {
                val hh = Array(n) { i -> h[i].clone().also { it[i] += lambda * (h[i][i] + 1e-9) } }
                val d = Linear.solve(hh, DoubleArray(n) { -g[it] }) ?: break
                val nc2 = cams.mapIndexed { c, p -> if (c == 0) p else perturb(p, d, ci(c)) }
                val np2 = pieces.mapIndexed { p, pp -> perturb(pp, d, pi(p)) }
                val nCost = cost(nc2, np2)
                if (nCost < cur) {
                    for (c in cams.indices) cams[c] = nc2[c]; for (p in pieces.indices) pieces[p] = np2[p]
                    val rel = (cur - nCost) / cur; cur = nCost; lambda = maxOf(lambda / 3, 1e-7); improved = true
                    if (rel < 1e-6) it = maxIter
                    break
                } else lambda *= 4
            }
            if (!improved) break
        }
        val outl = obs.count { o -> val r = obsRes(o, cams[o.kf], pieces[o.piece]); sqrt(r.sumOf { it * it } / (r.size / 2)) > huber * 2 }
        return Report(it, before, rmsPx(cams, pieces), outl)
    }

    @Suppress("unused") private fun absMax(v: DoubleArray) = v.maxOf { abs(it) }
}
