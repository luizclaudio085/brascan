package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Medições do modelo. Cada medida diz de onde vem:
 * OBSERVADA (scanner, várias vistas), DERIVADA (das medidas conhecidas das peças do catálogo),
 * ESTIMADA (depende de suposição — ex.: profundidade numa foto de frente).
 */
object Measure {
    enum class Kind(val label: String) { OBSERVADA("observada"), DERIVADA("derivada das peças"), ESTIMADA("estimada") }

    data class Item(val label: String, val value: Double, val unit: String, val plusMinus: Double?, val kind: Kind, val note: String = "")

    /** Peça para medir: pose em unidades do modelo (1 = lado do quadrado grande). */
    class P(val id: Int, val shape: String, val size: String, val center: V3, val u: V3, val v: V3, val edge: Float, val evidence: Evidence?) {
        val n get() = u.cross(v).norm()
        fun vertices(): List<V3> {
            val t = Catalog.thickness(size, edge); val nn = n
            return Catalog.polygon(shape).flatMap { (a, b) -> val p = center + u * (a * edge) + v * (b * edge); listOf(p + nn * (t / 2), p - nn * (t / 2)) }
        }
        /** Desvio (m) da posição desta peça, quando medido. */
        val sigmaM: Double get() = evidence?.spreadM?.takeIf { !it.isNaN() } ?: Double.NaN
    }

    private const val CM = Catalog.SIDE_M * 100.0 // 1 unidade do modelo em cm

    private fun kindFor(ps: List<P>, axisObservedInPhoto: Boolean): Kind {
        val src = ps.mapNotNull { it.evidence?.source }.toSet()
        return when {
            src.isNotEmpty() && src.all { it == Evidence.Source.SCANNER } && ps.all { (it.evidence?.views ?: 0) >= 3 } -> Kind.OBSERVADA
            src.all { it == Evidence.Source.FOTO || it == Evidence.Source.MANUAL } && axisObservedInPhoto -> Kind.DERIVADA
            else -> Kind.ESTIMADA
        }
    }

    private fun plusMinus(ps: List<P>, kind: Kind, value: Double): Double? = when (kind) {
        Kind.OBSERVADA -> ps.mapNotNull { p -> p.sigmaM.takeIf { !it.isNaN() } }.maxOrNull()?.let { 2 * it * 100 } // 2 desvios nas pontas
        Kind.DERIVADA -> max(.3, value * .04) // arredondamento de tamanho/posição pela foto
        Kind.ESTIMADA -> null
    }

    /** Dimensões gerais: altura, largura, profundidade, caixa envolvente e verticalidade. */
    fun overall(ps: List<P>): List<Item> {
        if (ps.isEmpty()) return emptyList()
        val vs = ps.flatMap { it.vertices() }
        val w = (vs.maxOf { it.x } - vs.minOf { it.x }) * CM
        val h = (vs.maxOf { it.y } - max(0f, vs.minOf { it.y })) * CM
        val d = (vs.maxOf { it.z } - vs.minOf { it.z }) * CM
        val view = ps.firstNotNullOfOrNull { it.evidence?.view } ?: ""
        val kw = kindFor(ps, true)
        val kh = kindFor(ps, view != "cima")
        val kd = kindFor(ps, view == "cima")
        val out = arrayListOf(
            Item("Altura", h, "cm", plusMinus(ps, kh, h), kh),
            Item("Largura", w, "cm", plusMinus(ps, kw, w), kw),
            Item("Profundidade", d, "cm", plusMinus(ps, kd, d), kd, if (kd == Kind.ESTIMADA && view == "frente") "foto de frente não mostra a profundidade" else ""),
        )
        val vol = w * h.coerceAtLeast(Catalog.THICKNESS * CM) * d.coerceAtLeast(Catalog.THICKNESS * CM)
        out += Item("Volume da caixa envolvente", vol, "cm³", null, listOf(kw, kh, kd).maxBy { it.ordinal }, "volume da caixa que contém tudo, não do plástico")
        if (ps.any { it.evidence?.source == Evidence.Source.FOTO || it.evidence?.source == Evidence.Source.MANUAL })
            out += Item("Escala usada", Catalog.SIDE_M * 100.0, "cm", null, Kind.ESTIMADA, "foto sem régua: medidas supõem quadrado grande = %.1f cm".format(Catalog.SIDE_M * 100))
        val standing = ps.filter { abs(it.n.y) < .5f }
        if (standing.isNotEmpty()) {
            val tilt = standing.maxOf { Math.toDegrees(acos(sqrt(1.0 - it.n.y * it.n.y).coerceIn(-1.0, 1.0))) }
            out += Item("Inclinação máx. das paredes", tilt, "°", null, kindFor(standing, true), "0° = perfeitamente em pé")
        }
        return out
    }

    /** Distância entre duas peças: entre centros e a folga (menor distância entre vértices). */
    fun between(a: P, b: P): List<Item> {
        val dc = (b.center - a.center).len() * CM
        val va = a.vertices(); val vb = b.vertices()
        var gap = Double.MAX_VALUE
        for (p in va) for (q in vb) gap = min(gap, (p - q).len().toDouble())
        val k = kindFor(listOf(a, b), true)
        val pm = if (k == Kind.OBSERVADA) sqrt((a.sigmaM.takeIf { !it.isNaN() } ?: 0.0).let { it * it } + (b.sigmaM.takeIf { !it.isNaN() } ?: 0.0).let { it * it }) * 100 else plusMinus(listOf(a, b), k, dc)
        return listOf(
            Item("Entre centros", dc, "cm", pm, k),
            Item("Folga (cantos mais próximos)", gap * CM, "cm", pm, k, "aproximada pelos cantos"),
            Item("Ângulo entre as peças", Math.toDegrees(acos(abs(a.n.dot(b.n)).toDouble().coerceAtMost(1.0))), "°", null, k),
        )
    }
}
