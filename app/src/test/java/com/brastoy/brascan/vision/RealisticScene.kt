package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Cena sintética REALISTA (fotos de out/2026): peças de plástico translúcido com aro colorido, ímãs escuros
 * perto das bordas, vincos em X, bolinha no centro; carpete com textura; sombra no chão; exposição, borrão
 * de movimento e ruído de sensor opcionais. Construção fácil = casinha com peças ENCOSTADAS, várias da
 * mesma cor lado a lado (o que a cena simples SyntheticScene não tem).
 */
object RealisticScene {
    class Tile(val name: String, val shape: String, val size: String, val color: Int, val c: V3, val u: V3, val v: V3) {
        val n = u.cross(v).norm()
        val e = Catalog.edgeM(size)
        val poly = Catalog.polygon(shape)
        val holes = Catalog.holes(shape)
        val magnets = Catalog.magnets(shape, size)
        /** Cantos no mundo (metros). */
        fun corners(): List<V3> = poly.map { (a, b) -> c + u * (a * e) + v * (b * e) }
    }

    class Opts(
        val exposure: Float = 1f, val blur: Int = 0, val noise: Float = 4f,
        val carpet: Boolean = true, val shadow: Boolean = true,
        /** Opacidade do painel. Medido nas fotos reais (tamanhos.jpg): o painel é MAIS saturado que o aro (C 72 × 50),
         * mesma luminância; aro e painel distam ~20 em a/b. */
        val translucency: Float = .8f,
    )

    private val X = V3(1f, 0f, 0f); private val Y = V3(0f, 1f, 0f); private val Z = V3(0f, 0f, 1f)
    private const val E = Catalog.SIDE_M
    private val RED = 0xFFE53950.toInt(); private val BLUE = 0xFF2F8CFF.toInt(); private val GREEN = 0xFF2EC27E.toInt()
    private val YELLOW = 0xFFFFC531.toInt(); private val PURPLE = 0xFF9B5DE5.toInt(); private val ORANGE = 0xFFFF7A2F.toInt()

    /** Casinha: piso deitado, 4 paredes em pé encostadas (duas azuis iguais lado a lado), telhado de 2 triângulos. */
    fun house(): List<Tile> {
        val h = E / 2; val t = E * .866f
        return listOf(
            Tile("piso amarelo", "quadrado", "grande", YELLOW, V3(0f, .002f, 0f), X, Z * -1f),
            Tile("parede frente vermelha", "quadrado", "grande", RED, V3(0f, h + .004f, h + .002f), X, Y),
            Tile("parede trás vermelha", "quadrado", "grande", RED, V3(0f, h + .004f, -h - .002f), X * -1f, Y),
            Tile("parede esquerda azul", "quadrado", "grande", BLUE, V3(-h - .002f, h + .004f, 0f), Z * -1f, Y),
            Tile("parede direita azul", "quadrado", "grande", BLUE, V3(h + .002f, h + .004f, 0f), Z, Y),
            Tile("telhado frente verde", "equilatero", "grande", GREEN, V3(0f, E + .004f + t / 3, h + .002f), X, Y),
            Tile("telhado trás verde", "equilatero", "grande", GREEN, V3(0f, E + .004f + t / 3, -h - .002f), X * -1f, Y),
            Tile("retângulo roxo ao lado", "retangulo", "grande", PURPLE, V3(.16f, .002f, .03f), X, Z * -1f),
            Tile("moldura laranja de lado", "moldura", "grande", ORANGE, V3(-.15f, h + .004f, .1f), Z, Y),
        )
    }

    /** A peça é a primeira coisa que o raio do olho até o centro dela atinge (não está escondida atrás de outra). */
    fun visible(tiles: List<Tile>, eye: V3, t: Tile): Boolean {
        val d = (t.c - eye).norm(); val dist = (t.c - eye).len()
        for (o in tiles) {
            if (o === t) continue
            val den = d.dot(o.n); if (abs(den) < 1e-6f) continue
            val tt = (o.c - eye).dot(o.n) / den; if (tt <= 0 || tt >= dist - .002f) continue
            val p = eye + d * tt - o.c
            if (insideTile(o, p.dot(o.u) / o.e, p.dot(o.v) / o.e)) return false
        }
        return true
    }

    /** Pixel de um ponto do mundo com a câmera deste renderizador (x direita, y cima, z para trás). */
    fun project(eye: V3, target: V3, p: V3, w: Int, h: Int): DoubleArray? {
        val fx = w * 195f / 240; val cx = w / 2f; val cy = h / 2f
        val f = (target - eye).norm(); val r = f.cross(Y).norm(); val up = r.cross(f)
        val d = p - eye; val z = d.dot(f); if (z < .02f) return null
        return doubleArrayOf((cx + fx * d.dot(r) / z).toDouble(), (cy - fx * d.dot(up) / z).toDouble())
    }

    private fun inside(poly: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var c = false
        for (i in poly.indices) {
            val (xi, yi) = poly[i]; val (xj, yj) = poly[(i + 1) % poly.size]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
        }
        return c
    }
    private fun segDist(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 > 0) ((x - ax) * dx + (y - ay) * dy) / l2 else 0f
        val tt = t.coerceIn(0f, 1f); return hypot(x - (ax + dx * tt), y - (ay + dy * tt))
    }
    private fun borderDist(t: Tile, x: Float, y: Float): Float {
        var d = Float.MAX_VALUE
        for (loop in listOf(t.poly) + t.holes) for (i in loop.indices) { val (ax, ay) = loop[i]; val (bx, by) = loop[(i + 1) % loop.size]; d = min(d, segDist(x, y, ax, ay, bx, by)) }
        return d
    }
    private fun insideTile(t: Tile, x: Float, y: Float) = inside(t.poly, x, y) && t.holes.none { inside(it, x, y) }

    private fun mix(a: Int, b: Int, k: Float): Int {
        fun ch(s: Int) = ((a shr s and 255) * (1 - k) + (b shr s and 255) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private fun scale(c: Int, k: Float): Int {
        fun ch(s: Int) = ((c shr s and 255) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    /** Carpete bege com textura fina (hash determinístico por célula de ~3 mm). */
    private fun carpet(x: Float, z: Float, textured: Boolean): Int {
        if (!textured) return 0xFFB4B0A8.toInt()
        val i = (x * 330).toInt(); val j = (z * 330).toInt()
        var hsh = i * 73856093 xor j * 19349663; hsh = hsh xor (hsh ushr 13); hsh *= 0x5bd1e995.toInt(); hsh = hsh xor (hsh ushr 15)
        val k = .88f + (hsh and 255) / 255f * .24f
        return scale(0xFFB8B0A4.toInt(), k)
    }

    fun render(tiles: List<Tile>, eye: V3, target: V3, rnd: Random, w: Int = 480, h: Int = 360, o: Opts = Opts()): FrameSample {
        val fx = w * 195f / 240; val fy = fx; val cx = w / 2f; val cy = h / 2f
        val f = (target - eye).norm(); val r = f.cross(Y).norm(); val up = r.cross(f)
        val m = floatArrayOf(r.x, r.y, r.z, 0f, up.x, up.y, up.z, 0f, -f.x, -f.y, -f.z, 0f, eye.x, eye.y, eye.z, 1f)
        val rgb = IntArray(w * h)
        fun floorColor(p: V3): Int {
            var c = carpet(p.x, p.z, o.carpet)
            if (o.shadow) { // sombra macia: peça logo acima do ponto (luz vem de cima, um pouco inclinada)
                var sh = 0f
                for (t in tiles) {
                    val lx = p.x - .03f; val lz = p.z + .02f // deslocamento da luz
                    val d = V3(.08f, 1f, -.05f).norm(); val den = d.dot(t.n); if (abs(den) < 1e-6f) continue
                    val tt = (t.c - V3(lx, p.y, lz)).dot(t.n) / den; if (tt <= 0 || tt > .25f) continue
                    val q = V3(lx, p.y, lz) + d * tt - t.c; val a = q.dot(t.u) / t.e; val b = q.dot(t.v) / t.e
                    if (insideTile(t, a, b)) sh = max(sh, if (borderDist(t, a, b) < .085f) .45f else .45f * (1 - o.translucency * .6f))
                }
                c = scale(c, 1 - sh)
            }
            return c
        }
        for (y in 0 until h) for (x in 0 until w) {
            val dc = V3((x + .5f - cx) / fx, -(y + .5f - cy) / fy, -1f)
            val d = (r * dc.x + up * dc.y + f * 1f).norm()
            // chão
            var best = Float.MAX_VALUE; var col = 0xFF9A948C.toInt()
            if (d.y < 0) { val tt = -eye.y / d.y; if (tt > 0) { best = tt; col = floorColor(eye + d * tt) } }
            val bgCol = col; val bgT = best
            var hit: Tile? = null; var ha = 0f; var hb = 0f; var hden = 0f
            for (t in tiles) {
                val den = d.dot(t.n); if (abs(den) < 1e-6f) continue
                val tt = (t.c - eye).dot(t.n) / den
                if (tt <= 0 || tt >= best) continue
                val p = eye + d * tt - t.c
                val a = p.dot(t.u) / t.e; val b = p.dot(t.v) / t.e
                if (!insideTile(t, a, b)) continue
                best = tt; hit = t; ha = a; hb = b; hden = abs(den)
            }
            hit?.let { t ->
                val shade = .72f + .28f * hden
                val bd = borderDist(t, ha, hb)
                val onMagnet = t.magnets.any { mg -> val lx = (ha - mg.x) * mg.dx + (hb - mg.y) * mg.dy; val ly = -(ha - mg.x) * mg.dy + (hb - mg.y) * mg.dx; abs(lx) < .11f && abs(ly) < .022f }
                col = when {
                    onMagnet -> mix(0xFF2E3238.toInt(), t.color, .12f)
                    bd < .085f -> scale(mix(t.color, 0xFFFFFFFF.toInt(), .3f), shade) // aro: plástico colorido mais claro e menos saturado que o painel
                    else -> {
                        // painel translúcido: o que está atrás aparece através
                        val behind = if (bgT < Float.MAX_VALUE) bgCol else 0xFF9A948C.toInt()
                        var c = scale(mix(behind, t.color, o.translucency), shade)
                        // vincos claros dos cantos internos ao centro e bolinha no meio
                        val inner = Catalog.inset(t.poly, .085f)
                        val crease = inner.any { (ix, iy) -> segDist(ha, hb, ix, iy, 0f, 0f) < .012f }
                        if (crease) c = mix(c, 0xFFFFFFFF.toInt(), .22f)
                        if (hypot(ha, hb) < .03f) c = scale(c, .8f)
                        c
                    }
                }
            }
            rgb[y * w + x] = col
        }
        // pós: exposição, borrão (caixa) e ruído do sensor
        if (o.exposure != 1f) for (i in rgb.indices) rgb[i] = scale(rgb[i], o.exposure)
        if (o.blur > 0) {
            val src = rgb.clone(); val rad = o.blur
            for (y in 0 until h) for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (dy in -rad..rad) for (dx in -rad..rad) { val xx = x + dx; val yy = y + dy; if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue; val c = src[yy * w + xx]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255; n++ }
                rgb[y * w + x] = (0xFF shl 24) or (sr / n shl 16) or (sg / n shl 8) or (sb / n)
            }
        }
        if (o.noise > 0) for (i in rgb.indices) {
            val c = rgb[i]; val nz = (rnd.nextGaussian() * o.noise).toInt()
            rgb[i] = (0xFF shl 24) or ((c shr 16 and 255) + nz).coerceIn(0, 255).shl(16) or ((c shr 8 and 255) + nz).coerceIn(0, 255).shl(8) or ((c and 255) + nz).coerceIn(0, 255)
        }
        return FrameSample(rgb, w, h, fx, fy, cx, cy, m)
    }

    /** Guarda um quadro como PPM (para olhar no PC: qualquer visualizador abre). */
    fun savePpm(f: FrameSample, path: java.io.File) {
        path.outputStream().buffered().use { o ->
            o.write("P6\n${f.w} ${f.h}\n255\n".toByteArray())
            for (c in f.rgb) { o.write(c shr 16 and 255); o.write(c shr 8 and 255); o.write(c and 255) }
        }
    }
}
