package com.brastoy.brascan.vision

/**
 * Catálogo das peças Brastoy — mesmos nomes e polígonos do simulador MK (index.html: vertices/tileShape).
 * Unidade = lado do quadrado grande. Proporções conferidas nas fotos reais (out/2026):
 * médio/grande = 0,81 (quadrado laranja × rosa); retângulo = 1 × 0,5; isósceles com altura 1,85; encaixe ≈ 0,83.
 */
object Catalog {
    val SHAPES = listOf("quadrado", "retangulo", "equilatero", "retanguloTri", "isoceles", "moldura", "janela", "porta", "L", "E", "encaixe")
    val LABEL = mapOf(
        "quadrado" to "Quadrado", "retangulo" to "Retângulo", "equilatero" to "Triângulo", "retanguloTri" to "Tri. retângulo",
        "isoceles" to "Isósceles", "moldura" to "Moldura", "janela" to "Janela 4 vidros", "porta" to "Porta", "L" to "L",
        "E" to "Ameia (E)", "encaixe" to "Encaixe",
    )
    val SIZES = listOf("grande", "medio", "pequeno")

    /** Lado do quadrado grande (m). Padrão do simulador; sem régua nas fotos, confirmar se possível. */
    const val SIDE_M = .075f
    val SCALE = mapOf("grande" to 1f, "medio" to .81f, "pequeno" to .5f)
    const val THICKNESS = .065f // em lados
    /** Borda sem cor/escura em volta da parte colorida (m). */
    const val FRAME_M = .003f

    fun edgeM(size: String) = SIDE_M * SCALE.getValue(size)
    val EDGE_M get() = SIZES.associateWith { edgeM(it) }

    /** Tamanho mais próximo para um lado medido (m). */
    fun sizeFor(edgeM: Float): String = SIZES.minBy { kotlin.math.abs(edgeM(it) - edgeM) }

    /**
     * Tamanho pela razão em relação ao grande (foto sem escala absoluta). Cortes puxados para baixo
     * porque a segmentação só encolhe peças (borda e ímãs somem), nunca aumenta.
     */
    fun sizeForRatio(r: Float): String = when { r > .88f -> "grande"; r > .63f -> "medio"; else -> "pequeno" }

    /** Contorno em lados (origem = pivô do simulador). */
    fun polygon(shape: String): List<Pair<Float, Float>> = when (shape) {
        "equilatero" -> listOf(-.5f to -.289f, .5f to -.289f, 0f to .577f)
        "retanguloTri" -> listOf(-.5f to -.5f, .5f to -.5f, -.5f to .5f)
        "isoceles" -> listOf(-.5f to -.5f, .5f to -.5f, 0f to 1.35f)
        "retangulo" -> listOf(-.5f to -.25f, .5f to -.25f, .5f to .25f, -.5f to .25f)
        "L" -> listOf(-.5f to -.5f, .5f to -.5f, .5f to -.15f, -.15f to -.15f, -.15f to .5f, -.5f to .5f)
        "E" -> listOf(-.25f to -.5f, .25f to -.5f, .25f to -.4f, -.10f to -.4f, -.10f to -.2f, .25f to -.2f, .25f to -.1f, -.10f to -.1f,
            -.10f to .1f, .25f to .1f, .25f to .2f, -.10f to .2f, -.10f to .4f, .25f to .4f, .25f to .5f, -.25f to .5f)
        "encaixe" -> listOf(-.415f to -.415f, .415f to -.415f, .415f to .415f, -.415f to .415f)
        else -> listOf(-.5f to -.5f, .5f to -.5f, .5f to .5f, -.5f to .5f)
    }

    /** Furos (em lados). Janela: 4 vidros dentro do vão da moldura. */
    fun holes(shape: String): List<List<Pair<Float, Float>>> {
        fun rect(x0: Float, y0: Float, x1: Float, y1: Float) = listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y1)
        return when (shape) {
            "moldura" -> listOf(rect(-.30f, -.30f, .30f, .30f))
            "janela" -> listOf(rect(-.30f, -.30f, -.03f, -.03f), rect(.03f, -.30f, .30f, -.03f), rect(-.30f, .03f, -.03f, .30f), rect(.03f, .03f, .30f, .30f))
            "porta" -> listOf(rect(-.20f, -.48f, .20f, .30f))
            else -> emptyList()
        }
    }

    /** Onde fica o pivô em relação ao centro da caixa do polígono, ao longo da altura (fração da altura). */
    fun pivotOffset(shape: String): Float {
        val p = polygon(shape); val y0 = p.minOf { it.second }; val y1 = p.maxOf { it.second }
        return (0f - (y0 + y1) / 2) / (y1 - y0)
    }

    /** Lado de referência medido a partir da caixa mínima (largura, altura) da forma, em unidades quaisquer. */
    fun sideFromBox(shape: String, w: Float, h: Float): Float {
        val p = polygon(shape)
        val pw = p.maxOf { it.first } - p.minOf { it.first }; val ph = p.maxOf { it.second } - p.minOf { it.second }
        val long = maxOf(pw, ph); val short = minOf(pw, ph)
        return (maxOf(w, h) / long + minOf(w, h) / short) / 2
    }

    private fun inPoly(p: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var c = false; var j = p.size - 1
        for (i in p.indices) { val (xi, yi) = p[i]; val (xj, yj) = p[j]; if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c; j = i }
        return c
    }

    /** Peça sem furo e convexa? (triângulos, quadrado, retângulo, encaixe) */
    fun convex(shape: String) = shape !in setOf("L", "E", "moldura", "janela", "porta")

    /**
     * Peça dividida em partes convexas (em lados): convexas ficam inteiras; L, E e as com furo viram
     * retângulos de uma grade nas linhas do contorno e dos furos (todas são retilíneas).
     */
    fun convexParts(shape: String): List<List<Pair<Float, Float>>> {
        val poly = polygon(shape)
        if (convex(shape)) return listOf(poly)
        val hs = holes(shape)
        val xs = (poly.map { it.first } + hs.flatMap { h -> h.map { it.first } }).distinct().sorted()
        val ys = (poly.map { it.second } + hs.flatMap { h -> h.map { it.second } }).distinct().sorted()
        fun solid(x: Float, y: Float) = inPoly(poly, x, y) && hs.none { inPoly(it, x, y) }
        val out = ArrayList<List<Pair<Float, Float>>>()
        for (j in 0 until ys.size - 1) {
            var start = -1
            for (i in 0..xs.size - 1) {
                val ok = i < xs.size - 1 && solid((xs[i] + xs[i + 1]) / 2, (ys[j] + ys[j + 1]) / 2)
                if (ok && start < 0) start = i
                if (!ok && start >= 0) { out += listOf(xs[start] to ys[j], xs[i] to ys[j], xs[i] to ys[j + 1], xs[start] to ys[j + 1]); start = -1 }
            }
        }
        return out
    }

    /** Ímã: centro (lados), direção da aresta e normal para dentro. Mesma regra do simulador (magnetSites). */
    data class Magnet(val x: Float, val y: Float, val dx: Float, val dy: Float)

    fun magnets(shape: String, size: String): List<Magnet> {
        if (shape == "encaixe") return emptyList()
        val poly = polygon(shape); val sc = SCALE.getValue(size); val out = ArrayList<Magnet>()
        for (i in poly.indices) {
            val (px, py) = poly[i]; val (qx, qy) = poly[(i + 1) % poly.size]
            val dx = qx - px; val dy = qy - py; val len = kotlin.math.hypot(dx, dy)
            if (len * sc < .24f) continue
            val ux = dx / len; val uy = dy / len
            for (t in floatArrayOf(.275f, .725f)) out += Magnet(px + dx * t - uy * .035f, py + dy * t + ux * .035f, ux, uy)
        }
        return out
    }

    /** Polígono convexo/simples (anti-horário) deslocado para dentro em [d] (cantos em esquadria). */
    fun inset(poly: List<Pair<Float, Float>>, d: Float): List<Pair<Float, Float>> = poly.indices.map { i ->
        val (ax, ay) = poly[(i - 1 + poly.size) % poly.size]; val (bx, by) = poly[i]; val (cx, cy) = poly[(i + 1) % poly.size]
        fun nrm(x: Float, y: Float): Pair<Float, Float> { val l = kotlin.math.hypot(x, y); return -y / l to x / l } // esquerda = dentro (CCW)
        val (n1x, n1y) = nrm(bx - ax, by - ay); val (n2x, n2y) = nrm(cx - bx, cy - by)
        val mx = n1x + n2x; val my = n1y + n2y; val ml = kotlin.math.hypot(mx, my).coerceAtLeast(1e-4f)
        val cos = (mx / ml) * n1x + (my / ml) * n1y
        val k = d / cos.coerceAtLeast(.2f)
        bx + mx / ml * k to by + my / ml * k
    }
}
