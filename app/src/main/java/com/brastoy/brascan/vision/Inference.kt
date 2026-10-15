package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Inferência de partes não observadas (fase 9). Tudo aqui é HIPÓTESE: nunca vira peça sem o usuário aceitar,
 * nunca entra em medição como observado. Cada hipótese diz por que existe e tem um peso relativo (não é
 * probabilidade medida). Hipóteses do mesmo [group] são alternativas que competem entre si.
 *
 * Unidades do modelo: 1 = lado do quadrado grande; Y para cima; chão em y = 0.
 */
object Inference {
    data class Hypothesis(val key: String, val group: String, val title: String, val reason: String, val weight: Double, val pieces: List<Measure.P>)

    private val UP = V3.UP
    private const val TOL = .06f

    private fun hypo(shape: String, size: String, c: V3, u: V3, v: V3, edge: Float) =
        Measure.P(-1, shape, size, c, u.norm(), v.norm(), edge, Evidence(Evidence.Source.HIPOTESE))

    private fun bottom(p: Measure.P) = p.vertices().minOf { it.y }
    private fun top(p: Measure.P) = p.vertices().maxOf { it.y }
    private fun standing(p: Measure.P) = abs(p.n.y) < .3f
    private fun sizeForEdge(e: Float) = Catalog.SIZES.minBy { abs(Catalog.SCALE.getValue(it) - e) }

    fun infer(observed: List<Measure.P>): List<Hypothesis> {
        if (observed.isEmpty()) return emptyList()
        val out = ArrayList<Hypothesis>()
        out += support(observed)
        val enc = enclosure(observed)
        out += enc
        out += symmetry(observed)
        // filtros comuns: nada atravessa peça real; lugar já ocupado por peça observada = hipótese resolvida
        val bodies = observed.map { Collision.Body(it.id, it.shape, it.center, it.u, it.v, it.edge, size = it.size) }
        return out.mapNotNull { h ->
            val keep = h.pieces.filter { hp ->
                val b = Collision.Body(-1, hp.shape, hp.center, hp.u, hp.v, hp.edge, size = hp.size)
                observed.none { (it.center - hp.center).len() < .3f * hp.edge } &&
                    bodies.none { o -> (o.center - b.center).len() < o.radius + b.radius && Collision.worstOverlap(listOf(o, b)) > Catalog.THICKNESS * 1.4f * hp.edge } // encostar na quina (espessura) é normal
            }
            when {
                h.pieces.isEmpty() -> h // alternativa "vazio": não tem peça
                keep.isEmpty() -> null
                else -> h.copy(pieces = keep)
            }
        }.distinctBy { it.key }
    }

    /** Gravidade: peça acima do chão sem nada embaixo segurando → provavelmente há apoio escondido. */
    private fun support(ps: List<Measure.P>): List<Hypothesis> = ps.mapNotNull { p ->
        val b = bottom(p); if (b < .3f) return@mapNotNull null
        val vs = p.vertices(); val x0 = vs.minOf { it.x }; val x1 = vs.maxOf { it.x }; val z0 = vs.minOf { it.z }; val z1 = vs.maxOf { it.z }
        val held = ps.any { q ->
            q !== p && top(q) in (b - .12f)..(b + .03f) && q.vertices().let { w ->
                min(x1, w.maxOf { it.x }) - max(x0, w.minOf { it.x }) > -.02f && min(z1, w.maxOf { it.z }) - max(z0, w.minOf { it.z }) > -.02f
            }
        }
        if (held) return@mapNotNull null
        val e = Catalog.SCALE.getValue(sizeForEdge(b)); if (e > b + .15f) return@mapNotNull null
        val c = V3(p.center.x, b - e / 2, p.center.z)
        val u = if (standing(p)) V3(p.u.x, 0f, p.u.z) else V3(p.u.x, 0f, p.u.z).let { if (it.len() < .1f) V3(1f, 0f, 0f) else it }
        val pc = hypo("quadrado", sizeForEdge(e), c, u, UP, e)
        Hypothesis("apoio:${key(c)}", "apoio:${p.id}", "Apoio escondido", "A peça está a %.0f cm do chão e nada visível a segura".format(b * Catalog.SIDE_M * 100), .7, listOf(pc))
    }

    /** Paredes em pé formando um contorno: lado aberto → parede faltando; contorno fechado → interior desconhecido. */
    private fun enclosure(ps: List<Measure.P>): List<Hypothesis> {
        val walls = ps.filter { standing(it) && it.shape !in setOf("equilatero", "retanguloTri", "isoceles") }
        if (walls.size < 2) return emptyList()
        val out = ArrayList<Hypothesis>()
        // agrupa por nível (altura da base)
        for ((_, level) in walls.groupBy { (bottom(it) / .5f).roundToInt() }) {
            if (level.size < 2) continue
            val L = level.minOf { bottom(it) }
            // referencial alinhado à primeira parede
            val th = atan2(level[0].u.z, level[0].u.x)
            fun toLocal(p: V3) = (cos(-th) * p.x - sin(-th) * p.z) to (sin(-th) * p.x + cos(-th) * p.z)
            fun toWorld(a: Float, b: Float, y: Float) = V3(cos(th) * a - sin(th) * b, y, sin(th) * a + cos(th) * b)
            val segs = level.map { w -> val half = w.u * (w.edge * (Catalog.polygon(w.shape).let { pg -> pg.maxOf { it.first } - pg.minOf { it.first } }) / 2); toLocal(w.center - half) to toLocal(w.center + half) }
            val ax = segs.flatMap { listOf(it.first.first, it.second.first) }; val az = segs.flatMap { listOf(it.first.second, it.second.second) }
            val x0 = ax.min(); val x1 = ax.max(); val z0 = az.min(); val z1 = az.max()
            if (x1 - x0 < .8f || z1 - z0 < .8f) continue
            val e = level.groupingBy { it.size }.eachCount().maxBy { it.value }.key.let { Catalog.SCALE.getValue(it) }
            val size = sizeForEdge(e)
            data class Side(val name: String, val fixedX: Boolean, val at: Float, val from: Float, val to: Float)
            val sides = listOf(Side("esquerdo", true, x0, z0, z1), Side("direito", true, x1, z0, z1), Side("da frente", false, z1, x0, x1), Side("de trás", false, z0, x0, x1))
            fun covered(s: Side): List<Pair<Float, Float>> = segs.mapNotNull { (a, b) ->
                if (s.fixedX && abs(a.first - s.at) < .15f && abs(b.first - s.at) < .15f) min(a.second, b.second) to max(a.second, b.second)
                else if (!s.fixedX && abs(a.second - s.at) < .15f && abs(b.second - s.at) < .15f) min(a.first, b.first) to max(a.first, b.first) else null
            }
            fun coverage(s: Side): Float {
                val iv = covered(s).sortedBy { it.first }; var tot = 0f; var end = s.from
                for ((a, b) in iv) { val aa = max(a, end); if (b > aa) { tot += b - aa; end = b } }
                return tot / (s.to - s.from)
            }
            val cov = sides.associateWith { coverage(it) }
            val closed = cov.count { it.value >= .6f }
            for (s in sides) {
                if (cov.getValue(s) >= .5f || closed < 2) continue
                // só se os dois lados vizinhos tiverem parede (contorno em U, não paredes soltas)
                val neighbors = sides.filter { it.fixedX != s.fixedX }
                if (neighbors.count { cov.getValue(it) >= .5f } < 2) continue
                val n = ((s.to - s.from) / e).roundToInt().coerceAtLeast(1)
                val pieces = (0 until n).map { i ->
                    val t = s.from + (i + .5f) * (s.to - s.from) / n
                    val c = if (s.fixedX) toWorld(s.at, t, L + e / 2) else toWorld(t, s.at, L + e / 2)
                    val u = if (s.fixedX) toWorld(0f, 1f, 0f) else toWorld(1f, 0f, 0f)
                    hypo("quadrado", size, c, u, UP, e)
                }
                out += Hypothesis("parede:${s.name}:${key(pieces[0].center)}", "parede:${s.name}", "Parede ${s.name} faltando",
                    "Os lados vizinhos têm parede e este está aberto (pode não ter sido escaneado)", .55, pieces)
            }
            // interior de contorno (quase) fechado: de fora a câmera não vê; alternativas
            if (closed >= 3 && min(x1 - x0, z1 - z0) >= 1.6f * e) {
                val g = "interior:${key(toWorld((x0 + x1) / 2, (z0 + z1) / 2, L))}"
                out += Hypothesis("$g:vazio", g, "Interior vazio", "Nada visto por dentro; é o mais comum em casas de peças", .45, emptyList())
                val longX = x1 - x0 >= z1 - z0
                val m = ((if (longX) z1 - z0 else x1 - x0) / e).roundToInt().coerceAtLeast(1)
                val div = (0 until m).map { i ->
                    val t = (if (longX) z0 else x0) + (i + .5f) * (if (longX) z1 - z0 else x1 - x0) / m
                    val c = if (longX) toWorld((x0 + x1) / 2, t, L + e / 2) else toWorld(t, (z0 + z1) / 2, L + e / 2)
                    hypo("quadrado", size, c, if (longX) toWorld(0f, 1f, 0f) else toWorld(1f, 0f, 0f), UP, e)
                }
                out += Hypothesis("$g:divisoria", g, "Divisória no meio", "Construção grande o bastante para dois cômodos; não dá para ver por fora", .25, div)
                val nx = ((x1 - x0) / e).roundToInt().coerceIn(1, 4); val nz = ((z1 - z0) / e).roundToInt().coerceIn(1, 4)
                val t = Catalog.thickness(size, e)
                val floor = (0 until nx).flatMap { i -> (0 until nz).map { j ->
                    hypo("quadrado", size, toWorld(x0 + (i + .5f) * (x1 - x0) / nx, z0 + (j + .5f) * (z1 - z0) / nz, L + t / 2), toWorld(1f, 0f, 0f), toWorld(0f, 1f, 0f), e)
                } }
                out += Hypothesis("$g:piso", g, "Piso por dentro", "Casas de peças costumam ter piso; escondido pelas paredes", .3, floor)
            }
        }
        return out
    }

    /** Simetria: se quase tudo tem par espelhado, a peça sem par sugere a do outro lado. */
    private fun symmetry(ps: List<Measure.P>): List<Hypothesis> {
        if (ps.size < 3) return emptyList()
        val c = ps.fold(V3.ZERO) { a, p -> a + p.center } * (1f / ps.size)
        val th = atan2(ps[0].u.z, ps[0].u.x)
        val axes = listOf(V3(cos(th), 0f, sin(th)), V3(-sin(th), 0f, cos(th)))
        val out = ArrayList<Hypothesis>()
        for ((ai, ax) in axes.withIndex()) {
            // plano de simetria: entre os candidatos (meio de cada par de peças iguais), o que mais casa pares
            fun unmatchedFor(off: Float): List<Measure.P> {
                fun m(p: V3) = p - ax * (2 * (p.dot(ax) - off))
                return ps.filter { p -> val mm = m(p.center); ps.none { q -> q.shape == p.shape && q.size == p.size && (q.center - mm).len() < .25f * p.edge } }
            }
            val cands = ps.flatMap { a -> ps.filter { it !== a && it.shape == a.shape }.map { b -> (a.center.dot(ax) + b.center.dot(ax)) / 2 } } + c.dot(ax)
            val off = cands.minBy { unmatchedFor(it).size }
            fun mirror(p: V3) = p - ax * (2 * (p.dot(ax) - off))
            fun mirrorDir(d: V3) = d - ax * (2 * d.dot(ax))
            val unmatched = unmatchedFor(off)
            val matched = ps.size - unmatched.size
            if (matched < ps.size * .6f || unmatched.isEmpty()) continue
            // peça exatamente no plano de simetria é par de si mesma
            val todo = unmatched.filter { abs(it.center.dot(ax) - off) > .2f * it.edge }
            if (todo.isEmpty()) continue
            val pieces = todo.map { p -> hypo(p.shape, p.size, mirror(p.center), mirrorDir(p.u), mirrorDir(p.v), p.edge) }
            out += Hypothesis("simetria:$ai:${key(pieces[0].center)}", "simetria:$ai", "Lado espelhado",
                "${matched} de ${ps.size} peças têm par simétrico; ${todo.size} do outro lado podem estar escondidas", .45 * matched / ps.size, pieces)
        }
        return out
    }

    private fun key(v: V3) = "${(v.x * 4).roundToInt()},${(v.y * 4).roundToInt()},${(v.z * 4).roundToInt()}"
}
