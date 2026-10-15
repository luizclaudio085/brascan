package com.brastoy.brascan.three

import androidx.compose.ui.graphics.Color
import com.brastoy.brascan.vision.Catalog
import kotlin.math.max
import kotlin.math.min

/**
 * Geometria de uma peça imantada como a real (fotos de out/2026): aro de plástico em volta,
 * painel translúcido rebaixado no meio, ímãs escuros perto das bordas e nervuras em X.
 * Peças com furo ou côncavas (moldura, janela, porta, L, E) são feitas das partes retangulares do catálogo.
 */
object TileMesh {
    private fun mix(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

    fun build(s: Scene, shape: String, size: String, center: V3, u: V3, v: V3, edge: Float, color: Color, tag: Int, selected: Boolean, shadow: Boolean = true) {
        val n = u.cross(v).norm()
        val t = Catalog.thickness(size, edge)
        fun P(a: Float, b: Float, z: Float) = center + u * (a * edge) + v * (b * edge) + n * z
        val rimColor = mix(color, Color.White, .22f)
        val glow = if (selected) Color.White else mix(color, Color.White, .55f)

        if (Catalog.convex(shape)) {
            val outer = Catalog.polygon(shape)
            val rimW = if (shape == "encaixe") .05f else .075f
            val inner = Catalog.inset(outer, rimW)
            val k = outer.size
            // aro: anéis da frente e de trás + paredes externas e internas
            for (i in 0 until k) {
                val j = (i + 1) % k
                val (oa, ob) = outer[i]; val (oc, od) = outer[j]; val (ia, ib) = inner[i]; val (ic, id) = inner[j]
                for (z in floatArrayOf(t / 2, -t / 2)) s.faces += Face(listOf(P(oa, ob, z), P(oc, od, z), P(ic, id, z), P(ia, ib, z)), rimColor, .8f, tag, doubleSided = true)
                s.faces += Face(listOf(P(oa, ob, -t / 2), P(oc, od, -t / 2), P(oc, od, t / 2), P(oa, ob, t / 2)), rimColor, .9f, tag, doubleSided = true)
                s.faces += Face(listOf(P(ia, ib, t / 2), P(ic, id, t / 2), P(ic, id, -t / 2), P(ia, ib, -t / 2)), mix(color, Color.Black, .1f), .7f, tag, doubleSided = true)
            }
            // painel rebaixado (mais fino, mais transparente)
            for (z in floatArrayOf(t * .18f, -t * .18f)) s.faces += Face(inner.map { (a, b) -> P(a, b, z) }, color, .5f, tag, doubleSided = true)
            // nervuras em X do centro aos cantos internos
            val cx = inner.sumOf { it.first.toDouble() }.toFloat() / k; val cy = inner.sumOf { it.second.toDouble() }.toFloat() / k
            if (shape != "encaixe") for ((a, b) in inner) s.lines += Line3(P(a, b, t * .19f), P(cx, cy, t * .19f), glow.copy(alpha = .28f), 1.2f)
            for (i in 0 until k) { val (a, b) = outer[i]; val (c, d) = outer[(i + 1) % k]; for (z in floatArrayOf(t / 2, -t / 2)) s.lines += Line3(P(a, b, z), P(c, d, z), glow.copy(alpha = if (selected) 1f else .45f), if (selected) 3f else 1.4f) }
        } else {
            // partes retangulares maciças (o plástico das molduras é colorido e mais opaco)
            for (part in Catalog.convexParts(shape)) s.prism(part.map { (a, b) -> a * edge to b * edge }, center, u, v, t, mix(color, Color.White, .08f), tag, .82f, null)
            for (loop in listOf(Catalog.polygon(shape)) + Catalog.holes(shape)) for (i in loop.indices) {
                val (a, b) = loop[i]; val (c, d) = loop[(i + 1) % loop.size]
                for (z in floatArrayOf(t / 2, -t / 2)) s.lines += Line3(P(a, b, z), P(c, d, z), glow.copy(alpha = if (selected) 1f else .45f), if (selected) 3f else 1.4f)
            }
        }

        // ímãs (cinza escuro, aparecem através do plástico como nas fotos)
        val magnet = Color(0xFF3A4048)
        for (m in Catalog.magnets(shape, size)) {
            val c = P(m.x, m.y, 0f)
            val du = u * m.dx + v * m.dy; val dv = n.cross(du).norm()
            s.orientedBox(c, du.norm(), dv, n, .2f * edge, .04f * edge, t * .55f, magnet, tag)
        }
        if (shadow) s.shadowOf(Catalog.polygon(shape).map { (a, b) -> P(a, b, 0f) })
    }

    /** Raio de uma esfera que contém a peça (para enquadrar a câmera). */
    fun radius(shape: String, edge: Float): Float {
        val p = Catalog.polygon(shape)
        return p.maxOf { kotlin.math.hypot(it.first, it.second) } * edge
    }
}

fun Scene.orientedBox(c: V3, u: V3, v: V3, n: V3, su: Float, sv: Float, sn: Float, color: Color, tag: Int = -1) {
    fun p(a: Int, b: Int, z: Int) = c + u * (a * su / 2) + v * (b * sv / 2) + n * (z * sn / 2)
    listOf(
        listOf(p(-1, -1, 1), p(1, -1, 1), p(1, 1, 1), p(-1, 1, 1)), listOf(p(1, -1, -1), p(-1, -1, -1), p(-1, 1, -1), p(1, 1, -1)),
        listOf(p(-1, 1, 1), p(1, 1, 1), p(1, 1, -1), p(-1, 1, -1)), listOf(p(-1, -1, -1), p(1, -1, -1), p(1, -1, 1), p(-1, -1, 1)),
        listOf(p(1, -1, 1), p(1, -1, -1), p(1, 1, -1), p(1, 1, 1)), listOf(p(-1, -1, -1), p(-1, -1, 1), p(-1, 1, 1), p(-1, 1, -1)),
    ).forEach { faces += Face(it, color, 1f, tag, doubleSided = true) }
}

@Suppress("unused") private fun clamp(x: Float) = max(0f, min(1f, x))
