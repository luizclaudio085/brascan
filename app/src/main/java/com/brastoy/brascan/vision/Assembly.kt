package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Manual de montagem da construção escaneada: lista de peças + passos na ordem em que dá para montar de
 * verdade (uma peça só entra quando o que a segura já está no lugar), cada passo com o texto de onde encaixar.
 * Unidades do modelo (1 = lado do quadrado grande), Y para cima, a vista "de frente" olha para -Z.
 */
object Assembly {
    data class Step(val pieceId: Int, val text: String, val note: String = "")
    data class Bom(val label: String, val count: Int)
    data class Plan(val steps: List<Step>, val bom: List<Bom>)

    private fun bottom(p: Measure.P) = p.vertices().minOf { it.y }
    private fun top(p: Measure.P) = p.vertices().maxOf { it.y }
    private fun footprintOverlap(a: Measure.P, b: Measure.P): Boolean {
        val va = a.vertices(); val vb = b.vertices()
        return min(va.maxOf { it.x }, vb.maxOf { it.x }) - max(va.minOf { it.x }, vb.minOf { it.x }) > -.02f &&
            min(va.maxOf { it.z }, vb.maxOf { it.z }) - max(va.minOf { it.z }, vb.minOf { it.z }) > -.02f
    }
    /** Encostadas (ímã com ímã): cantos a menos de ~8% do lado. */
    private fun touching(a: Measure.P, b: Measure.P): Boolean {
        val va = a.vertices(); val vb = b.vertices(); val lim = .08f * max(a.edge, b.edge)
        for (p in va) for (q in vb) if ((p - q).len() < lim) return true
        return false
    }

    fun colorName(argb: Int): String { val t = FloatArray(3); Segmenter.hsv(argb, t); return if (t[1] < .18f) (if (t[2] > .7f) "branco" else "cinza") else PieceSlam.colorName(Segmenter.hueBin(t[0])) }
    private fun sizeName(s: String) = when (s) { "grande" -> "grande"; "medio" -> "médio"; else -> "pequeno" }
    fun name(p: Measure.P, color: Int) = "${(Catalog.LABEL[p.shape] ?: p.shape).lowercase()} ${colorName(color)} ${sizeName(p.size)}"

    private fun posture(p: Measure.P): String = when {
        abs(p.n.y) > .8f -> "deitado no chão"
        abs(p.n.y) < .3f -> if (abs(p.n.z) >= abs(p.n.x)) "em pé, virado para a frente" else "em pé, virado para o lado"
        else -> "inclinado"
    }

    /** [colors]: cor de cada peça (id → ARGB). */
    fun plan(ps: List<Measure.P>, colors: Map<Int, Int>): Plan {
        val supporters = ps.associate { p ->
            p.id to if (bottom(p) < .1f) emptyList() else ps.filter { q -> q !== p && top(q) in (bottom(p) - .12f)..(bottom(p) + .05f) && footprintOverlap(p, q) }
        }
        val placed = LinkedHashSet<Int>(); val steps = ArrayList<Step>()
        var last: Measure.P? = null
        while (placed.size < ps.size) {
            val rest = ps.filter { it.id !in placed }
            // disponível: tudo que a segura já está montado (ou vai no chão)
            val ready = rest.filter { p -> supporters.getValue(p.id).let { s -> s.isEmpty() && bottom(p) < .1f || s.isNotEmpty() && s.all { it.id in placed } } }
            val pool = ready.ifEmpty { rest } // sobrou peça sem apoio visível: entra por último, com aviso
            val next = pool.minWith(compareBy<Measure.P>(
                { p -> if (placed.isEmpty() || ps.any { it.id in placed && touching(it, p) }) 0 else 1 }, // encosta no que já está montado
                { p -> (bottom(p) * 4).toInt() },                                                      // de baixo para cima
                { p -> if (placed.isEmpty()) top(p) else 0f },                                           // começa pelo piso deitado
                { p -> last?.let { (it.center - p.center).len() } ?: (p.center.x * .01f - p.center.z) }, // perto do passo anterior; começa pela frente
            ))
            val col = colors[next.id] ?: 0xFF888888.toInt()
            val ref = ps.filter { it.id in placed }.let { done ->
                supporters.getValue(next.id).firstOrNull { it.id in placed } ?: done.filter { touching(it, next) }.minByOrNull { (it.center - next.center).len() }
                    ?: done.minByOrNull { (it.center - next.center).len() }
            }
            val where = if (ref == null) "no chão para começar" else relation(next, ref, colors[ref.id] ?: 0, bottom(next) < .1f)
            val note = if (ready.isEmpty()) "Nada visível segura esta peça: veja as hipóteses de apoio no Raio-X" else ""
            steps += Step(next.id, "Coloque o ${name(next, col)}, ${posture(next)}, $where.", note)
            placed += next.id; last = next
        }
        val bom = ps.groupingBy { name(it, colors[it.id] ?: 0) }.eachCount().map { (k, v) -> Bom(k, v) }.sortedWith(compareByDescending<Bom> { it.count }.thenBy { it.label })
        return Plan(steps, bom)
    }

    private fun relation(p: Measure.P, ref: Measure.P, refColor: Int, onGround: Boolean): String {
        val d = p.center - ref.center; val e = max(p.edge, ref.edge)
        val rn = "do ${(Catalog.LABEL[ref.shape] ?: ref.shape).lowercase()} ${colorName(refColor)}"
        val glued = if (touching(p, ref)) "encostado " else "perto "
        return when {
            !onGround && d.y > .4f * e && abs(d.y) >= max(abs(d.x), abs(d.z)) * .8f -> "em cima $rn"
            abs(d.x) >= abs(d.z) -> glued + (if (d.x > 0) "à direita $rn" else "à esquerda $rn")
            else -> glued + (if (d.z > 0) "na frente $rn" else "atrás $rn")
        }
    }
}
