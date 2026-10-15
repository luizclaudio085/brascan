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

    /** [ghost] < 1 deixa a peça transparente (Raio-X: paredes fantasma / isolar uma peça). */
    fun build(s: Scene, shape: String, size: String, center: V3, u: V3, v: V3, edge: Float, color: Color, tag: Int, selected: Boolean, shadow: Boolean = true, ghost: Float = 1f) {
        val first = s.faces.size; val firstLine = s.lines.size
        buildInner(s, shape, size, center, u, v, edge, color, tag, selected, shadow)
        if (ghost < 1f) {
            for (i in first until s.faces.size) { val f = s.faces[i]; s.faces[i] = Face(f.pts, f.color, (f.alpha * ghost).coerceAtMost(.98f), f.tag, f.doubleSided, f.outline, f.unlit) }
            for (i in firstLine until s.lines.size) { val l = s.lines[i]; s.lines[i] = Line3(l.a, l.b, l.color.copy(alpha = l.color.alpha * (.4f + .6f * ghost)), l.width) }
        }
    }

    private fun buildInner(s: Scene, shape: String, size: String, center: V3, u: V3, v: V3, edge: Float, color: Color, tag: Int, selected: Boolean, shadow: Boolean) {
        val n = u.cross(v).norm()
        val t = Catalog.thickness(size, edge)
        fun P(a: Float, b: Float, z: Float) = center + u * (a * edge) + v * (b * edge) + n * z
        val rimColor = mix(color, Color.White, .22f)
        val glow = if (selected) Color.White else mix(color, Color.White, .55f)

        val magnet = Color(0xFF2E333B)
        // bolinha (pino) em relevo no aro: disco claro com borda escura
        fun pin(a: Float, b: Float, r: Float, z: Float) {
            val ring = List(8) { i -> val ang = i * Math.PI.toFloat() / 4; P(a + r * kotlin.math.cos(ang), b + r * kotlin.math.sin(ang), z) }
            s.faces += Face(ring, mix(rimColor, Color.White, .35f), 1f, tag, doubleSided = true)
            for (i in ring.indices) s.lines += Line3(ring[i], ring[(i + 1) % ring.size], mix(color, Color.Black, .45f).copy(alpha = .8f), 1f)
        }
        // janela do ímã no aro: retângulo escuro visto através do plástico, nas duas faces
        fun pockets() {
            val loops = listOf(Catalog.polygon(shape)) + Catalog.holes(shape)
            for (m in Catalog.magnets(shape, size)) {
                val l = kotlin.math.hypot(m.dx, m.dy).coerceAtLeast(1e-6f); val ax = m.dx / l; val ay = m.dy / l
                val hl = .1f; val hw = .022f
                // centro do bolso: no meio do aro, ao lado da borda mais próxima (lado de dentro da peça)
                var best = Float.MAX_VALUE; var mx = m.x; var my = m.y
                for (loop in loops) for (i in loop.indices) {
                    val (x0, y0) = loop[i]; val (x1, y1) = loop[(i + 1) % loop.size]
                    val ex = x1 - x0; val ey = y1 - y0; val el = ex * ex + ey * ey
                    val tt = if (el > 0f) (((m.x - x0) * ex + (m.y - y0) * ey) / el).coerceIn(0f, 1f) else 0f
                    val px = x0 + ex * tt; val py = y0 + ey * tt; val d = kotlin.math.hypot(m.x - px, m.y - py)
                    if (d < best) {
                        best = d; val nl = kotlin.math.sqrt(el).coerceAtLeast(1e-6f)
                        val nx = -ey / nl; val ny = ex / nl; val off = .042f
                        val inside = insidePiece(px + nx * off, py + ny * off, loops)
                        mx = px + (if (inside) nx else -nx) * off; my = py + (if (inside) ny else -ny) * off
                    }
                }
                val q = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f).map { (i, j) -> (mx + ax * hl * i - ay * hw * j) to (my + ay * hl * i + ax * hw * j) }
                for (z in floatArrayOf(t / 2 + t * .02f, -t / 2 - t * .02f)) s.faces += Face(q.map { (a, b) -> P(a, b, z) }, magnet, .92f, tag, doubleSided = true)
            }
        }

        if (Catalog.convex(shape)) {
            val outer = Catalog.polygon(shape)
            val rimW = if (shape == "encaixe") .05f else .085f
            val inner = Catalog.inset(outer, rimW)
            val k = outer.size
            val rimSide = mix(color, Color.Black, .12f)
            // painel rebaixado: borda alta, centro afundado (pirâmide rasa)
            val zEdge = t * .30f; val zMid = t * .06f
            // aro: anéis da frente e de trás (plástico grosso, quase opaco) + paredes externas e o degrau para o painel
            for (i in 0 until k) {
                val j = (i + 1) % k
                val (oa, ob) = outer[i]; val (oc, od) = outer[j]; val (ia, ib) = inner[i]; val (ic, id) = inner[j]
                for (z in floatArrayOf(t / 2, -t / 2)) s.faces += Face(listOf(P(oa, ob, z), P(oc, od, z), P(ic, id, z), P(ia, ib, z)), rimColor, .96f, tag, doubleSided = true)
                s.faces += Face(listOf(P(oa, ob, -t / 2), P(oc, od, -t / 2), P(oc, od, t / 2), P(oa, ob, t / 2)), rimSide, .97f, tag, doubleSided = true)
                for (sg in floatArrayOf(1f, -1f)) s.faces += Face(listOf(P(ia, ib, sg * t / 2), P(ic, id, sg * t / 2), P(ic, id, sg * zEdge), P(ia, ib, sg * zEdge)), mix(color, Color.Black, .25f), .9f, tag, doubleSided = true)
            }
            // painel: uma faceta triangular por lado, do canto interno até o centro afundado (dá o X do quadrado)
            val cx = inner.sumOf { it.first.toDouble() }.toFloat() / k; val cy = inner.sumOf { it.second.toDouble() }.toFloat() / k
            val crease = mix(color, Color.White, .6f)
            if (shape == "encaixe") for (z in floatArrayOf(t * .18f, -t * .18f)) s.faces += Face(inner.map { (a, b) -> P(a, b, z) }, color, .7f, tag, doubleSided = true)
            else for (sg in floatArrayOf(1f, -1f)) {
                for (i in 0 until k) {
                    val (ia, ib) = inner[i]; val (ic, id) = inner[(i + 1) % k]
                    s.faces += Face(listOf(P(ia, ib, sg * zEdge), P(ic, id, sg * zEdge), P(cx, cy, sg * zMid)), color, .66f, tag, doubleSided = true)
                    s.lines += Line3(P(ia, ib, sg * (zEdge + .001f)), P(cx, cy, sg * (zMid + .001f)), crease.copy(alpha = .75f), 1.6f)
                }
                // bolinha afundada no centro
                val r = .03f
                val dot = List(8) { i -> val ang = i * Math.PI.toFloat() / 4; P(cx + r * kotlin.math.cos(ang), cy + r * kotlin.math.sin(ang), sg * (zMid - t * .03f)) }
                s.faces += Face(dot, mix(color, Color.Black, .35f), .95f, tag, doubleSided = true)
                for (i in dot.indices) s.lines += Line3(dot[i], dot[(i + 1) % dot.size], crease.copy(alpha = .9f), 1.2f)
                // contorno interno do aro (onde o painel desce)
                for (i in 0 until k) { val (ia, ib) = inner[i]; val (ic, id) = inner[(i + 1) % k]; s.lines += Line3(P(ia, ib, sg * t / 2), P(ic, id, sg * t / 2), mix(color, Color.Black, .4f).copy(alpha = .7f), 1.2f) }
            }
            // pinos: nos cantos e no meio de cada lado do aro
            if (shape != "encaixe") for (i in 0 until k) {
                val (oa, ob) = outer[i]; val (ia, ib) = inner[i]; val (oc, od) = outer[(i + 1) % k]; val (ic, id) = inner[(i + 1) % k]
                for (sg in floatArrayOf(1f, -1f)) {
                    pin((oa + ia) / 2, (ob + ib) / 2, .018f, sg * (t / 2 + t * .03f))
                    pin((oa + ia + oc + ic) / 4, (ob + ib + od + id) / 4, .016f, sg * (t / 2 + t * .03f))
                }
            }
            for (i in 0 until k) { val (a, b) = outer[i]; val (c, d) = outer[(i + 1) % k]; for (z in floatArrayOf(t / 2, -t / 2)) s.lines += Line3(P(a, b, z), P(c, d, z), glow.copy(alpha = if (selected) 1f else .55f), if (selected) 3f else 1.4f) }
            pockets()
        } else {
            // partes retangulares maciças (o plástico das molduras é colorido e mais opaco)
            for (part in Catalog.convexParts(shape)) s.prism(part.map { (a, b) -> a * edge to b * edge }, center, u, v, t, mix(color, Color.White, .12f), tag, .95f, null)
            for (loop in listOf(Catalog.polygon(shape)) + Catalog.holes(shape)) for (i in loop.indices) {
                val (a, b) = loop[i]; val (c, d) = loop[(i + 1) % loop.size]
                for (z in floatArrayOf(t / 2, -t / 2)) s.lines += Line3(P(a, b, z), P(c, d, z), glow.copy(alpha = if (selected) 1f else .55f), if (selected) 3f else 1.4f)
            }
            // cantos do contorno: pinos como nas peças reais
            for ((a, b) in Catalog.inset(Catalog.polygon(shape), .045f)) for (sg in floatArrayOf(1f, -1f)) pin(a, b, .017f, sg * (t / 2 + t * .03f))
            pockets()
        }

        if (shadow) s.shadowOf(Catalog.polygon(shape).map { (a, b) -> P(a, b, 0f) })
    }

    /** Ponto dentro do contorno externo e fora dos furos (par-ímpar sobre todos os laços). */
    private fun insidePiece(x: Float, y: Float, loops: List<List<Pair<Float, Float>>>): Boolean {
        var c = false
        for (loop in loops) for (i in loop.indices) {
            val (xi, yi) = loop[i]; val (xj, yj) = loop[(i + 1) % loop.size]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
        }
        return c
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
