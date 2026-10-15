package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Peças não podem atravessar umas às outras. Cada peça vira um conjunto de prismas convexos
 * (partes do catálogo × espessura); pares que se cruzam são afastados pelo eixo de menor
 * penetração (teorema dos eixos separadores). Encostar (ímã com ímã) é permitido.
 */
object Collision {
    /** [weight]: confiança da posição (ex.: pontos observados). Na colisão, quem tem mais evidência se move menos. */
    /** [inflate]: folga extra (fração do lado) — na exportação garante distância maior que a quantização do formato. */
    class Body(val id: Int, val shape: String, var center: V3, val u: V3, val v: V3, val edge: Float, val fixed: Boolean = false, val weight: Float = 1f,
               val size: String = "grande", val inflate: Float = 0f) {
        val n = u.cross(v).norm()
        val t = Catalog.thickness(size, edge) + inflate * edge * 2
        private val g = edge * (1 + inflate)
        /** Vértices de cada parte convexa, relativos ao centro. */
        val parts: List<List<V3>> = Catalog.convexParts(shape).map { part -> part.flatMap { (a, b) -> listOf(u * (a * g) + v * (b * g) + n * (t / 2), u * (a * g) + v * (b * g) - n * (t / 2)) } }
        val partEdges: List<List<V3>> = Catalog.convexParts(shape).map { part -> part.indices.map { i -> val (a, b) = part[i]; val (c, d) = part[(i + 1) % part.size]; (u * (c - a) + v * (d - b)).norm() } }
        val radius = parts.flatten().maxOf { it.len() }
        val minY get() = parts.flatten().minOf { it.y } + center.y
    }

    /** Penetração entre duas partes convexas: vetor que afasta B de A (ou null se não se cruzam). */
    private fun sat(a: Body, pa: Int, b: Body, pb: Int, inPlaneOnly: Boolean): V3? {
        val va = a.parts[pa]; val vb = b.parts[pb]
        val axes = ArrayList<V3>()
        if (!inPlaneOnly) { axes += a.n; axes += b.n }
        val ea = a.partEdges[pa]; val eb = b.partEdges[pb]
        for (e in ea) axes += e.cross(a.n)
        for (e in eb) axes += e.cross(b.n)
        if (!inPlaneOnly) {
            for (x in ea + a.n) for (y in eb + b.n) { val c = x.cross(y); if (c.len() > 1e-3f) axes += c.norm() }
        }
        var best: V3? = null; var bestDepth = Float.MAX_VALUE
        val d = b.center - a.center
        for (ax0 in axes) {
            val l = ax0.len(); if (l < 1e-4f) continue
            val ax = ax0 * (1f / l)
            if (inPlaneOnly && abs(ax.dot(a.n)) > .3f) continue
            var a0 = Float.MAX_VALUE; var a1 = -Float.MAX_VALUE; var b0 = Float.MAX_VALUE; var b1 = -Float.MAX_VALUE
            for (p in va) { val k = p.dot(ax); a0 = min(a0, k); a1 = max(a1, k) }
            for (p in vb) { val k = p.dot(ax) + d.dot(ax); b0 = min(b0, k); b1 = max(b1, k) }
            val overlap = min(a1, b1) - max(a0, b0)
            if (overlap <= 0f) return null // eixo separador: não se tocam
            if (overlap < bestDepth) { bestDepth = overlap; best = if ((b0 + b1) / 2 >= (a0 + a1) / 2) ax * overlap else ax * -overlap }
        }
        return best
    }

    /**
     * Menor deslocamento de B que separa TODAS as partes que se cruzam (uma direção só para a peça inteira —
     * empurrar parte a parte faz os pedaços de uma moldura se anularem).
     */
    private fun separation(a: Body, b: Body, coplanar: Boolean): V3? {
        val hits = ArrayList<Pair<Int, Int>>()
        for (pa in a.parts.indices) for (pb in b.parts.indices) if (sat(a, pa, b, pb, coplanar) != null) hits += pa to pb
        if (hits.isEmpty()) return null
        val axes = ArrayList<V3>()
        if (!coplanar) { axes += a.n; axes += b.n }
        for ((pa, pb) in hits) {
            for (e in a.partEdges[pa]) axes += e.cross(a.n).norm()
            for (e in b.partEdges[pb]) axes += e.cross(b.n).norm()
            if (!coplanar) for (x in a.partEdges[pa] + a.n) for (y in b.partEdges[pb] + b.n) { val c = x.cross(y); if (c.len() > 1e-3f) axes += c.norm() }
        }
        val d = b.center - a.center
        var best: V3? = null; var bestNeed = Float.MAX_VALUE
        for (ax in axes) for (sg in floatArrayOf(1f, -1f)) {
            val dir = ax * sg
            if (coplanar && abs(dir.dot(a.n)) > .3f) continue
            var need = 0f
            for ((pa, pb) in hits) {
                val aMax = a.parts[pa].maxOf { it.dot(dir) }; val bMin = b.parts[pb].minOf { it.dot(dir) } + d.dot(dir)
                need = max(need, aMax - bMin)
            }
            if (need < bestNeed) { bestNeed = need; best = dir * need }
        }
        return best
    }

    /** Encaixe dentro do vão de uma moldura/janela/porta: é o uso normal, não é colisão. */
    var allowInserts = true

    private fun allowed(a: Body, b: Body): Boolean {
        if (!allowInserts) return false
        val (ins, frame) = when { a.shape == "encaixe" -> a to b; b.shape == "encaixe" -> b to a; else -> return false }
        if (Catalog.holes(frame.shape).isEmpty() || abs(ins.n.dot(frame.n)) < .9f) return false
        val d = ins.center - frame.center
        return abs(d.dot(frame.n)) < frame.t && abs(d.dot(frame.u)) < .2f * frame.edge && abs(d.dot(frame.v)) < .2f * frame.edge
    }

    /**
     * Resolve sobreposições: até [iters] rodadas, empurrando metade para cada lado (ou tudo para a peça
     * não fixa). Peças coplanares (lado a lado na mesa) se afastam no plano em vez de empilhar.
     * Ninguém fica abaixo do chão (y = 0).
     */
    fun resolve(bodies: List<Body>, iters: Int = 40, tol: Float = .004f, floor: Boolean = true): Int {
        var moves = 0
        repeat(iters) {
            var any = false
            for (i in bodies.indices) for (j in i + 1 until bodies.size) {
                val a = bodies[i]; val b = bodies[j]
                if (a.fixed && b.fixed) continue
                if ((b.center - a.center).len() > a.radius + b.radius) continue
                if (allowed(a, b)) continue
                val coplanar = abs(a.n.dot(b.n)) > .95f && abs((b.center - a.center).dot(a.n)) < max(a.t, b.t)
                val push = separation(a, b, coplanar)
                val p = push ?: continue
                if (p.len() <= tol) continue
                val extra = p.norm() * (tol * .5f)
                val full = p + extra
                when {
                    a.fixed -> b.center = b.center + full
                    b.fixed -> a.center = a.center - full
                    else -> { val ka = b.weight / (a.weight + b.weight); a.center = a.center - full * ka; b.center = b.center + full * (1f - ka) }
                }
                any = true; moves++
            }
            if (floor) for (b in bodies) { val dy = -b.minY; if (dy > 1e-5f && !b.fixed) { b.center = b.center + V3(0f, dy, 0f); any = true } }
            if (!any) return moves
        }
        return moves
    }

    /** Maior penetração restante entre pares (para testes). */
    fun worstOverlap(bodies: List<Body>): Float {
        var w = 0f
        for (i in bodies.indices) for (j in i + 1 until bodies.size) {
            val a = bodies[i]; val b = bodies[j]
            if (allowed(a, b)) continue
            val coplanar = abs(a.n.dot(b.n)) > .95f && abs((b.center - a.center).dot(a.n)) < max(a.t, b.t)
            for (pa in a.parts.indices) for (pb in b.parts.indices) sat(a, pa, b, pb, coplanar)?.let { w = max(w, it.len()) }
        }
        return w
    }
}
