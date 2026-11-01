package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Banco de testes da CONTAGEM de peças soltas (inventário): cenas sintéticas realistas (RealisticScene) com peças
 * deitadas espalhadas no chão, de todos os 30 pares tamanho/formato, em 7 cores, com fundo, luz, ruído, borrão e
 * inclinação da câmera variados. Cada cena tem gabarito; [run] devolve as métricas do detector de fotos
 * (Segmenter + PhotoPieces), o mesmo que o app usa na contagem.
 */
object LooseBench {
    /** Cores reais das peças (fotos de out/2026). */
    val COLORS = intArrayOf(0xFFE53950.toInt(), 0xFF2F8CFF.toInt(), 0xFF2EC27E.toInt(), 0xFFFFC531.toInt(), 0xFF9B5DE5.toInt(), 0xFFFF7A2F.toInt(), 0xFFFF77B8.toInt())

    /** Os 30 pares do inventário do simulador (mesma lista do app: scan/Inventory.KINDS). */
    val KINDS: List<Pair<String, String>> = listOf(
        "grande" to listOf("quadrado", "janela", "moldura", "retangulo", "equilatero", "retanguloTri", "isoceles", "E", "L", "porta"),
        "medio" to listOf("quadrado", "janela", "moldura", "retangulo", "equilatero", "retanguloTri", "isoceles", "L", "porta", "encaixe", "E"),
        "pequeno" to listOf("equilatero", "retanguloTri", "quadrado", "janela", "moldura", "retangulo", "isoceles", "L", "porta"),
    ).flatMap { (s, ts) -> ts.map { s to it } }

    class Scene(val tiles: List<RealisticScene.Tile>, val eye: V3, val target: V3, val opts: RealisticScene.Opts, val w: Int, val h: Int)

    /**
     * Uma cena: [n] peças deitadas sem encostar umas nas outras (o pedido ao usuário na contagem é espalhar).
     * [hard]: fundo de carpete, sombra, ruído e borrão mais fortes, câmera mais inclinada.
     */
    fun scene(seed: Long, n: Int, hard: Boolean, w: Int = 480, h: Int = 360, kinds: List<Pair<String, String>> = KINDS): Scene {
        val rnd = Random(seed)
        val tiles = ArrayList<RealisticScene.Tile>()
        val placed = ArrayList<Pair<V3, Float>>() // centro + raio
        var tries = 0
        while (tiles.size < n && tries++ < 2000) {
            val (size, type) = kinds[rnd.nextInt(kinds.size)]
            val e = Catalog.edgeM(size)
            val poly = Catalog.polygon(type)
            val r = poly.maxOf { hypot(it.first, it.second) } * e
            val c = V3(((rnd.nextDouble() - .5) * .42).toFloat(), .0025f, ((rnd.nextDouble() - .5) * .30).toFloat())
            if (placed.any { (p, pr) -> hypot(p.x - c.x, p.z - c.z) < pr + r + .012f }) continue
            val a = (rnd.nextDouble() * 2 * Math.PI).toFloat()
            // deitada: normal para cima; u no chão girado de a; v = n × u
            val u = V3(cos(a), 0f, sin(a)); val nUp = V3(0f, 1f, 0f); val v = nUp.cross(u)
            // metade das peças virada (normal para baixo): espelha o contorno, como na vida real
            val flip = rnd.nextBoolean()
            val vv = if (flip) v * -1f else v
            val color = COLORS[rnd.nextInt(COLORS.size)]
            tiles += RealisticScene.Tile("$size/$type", type, size, color, c, u, vv)
            placed += c to r
        }
        val tilt = Math.toRadians(if (hard) 10.0 + rnd.nextDouble() * 30 else 4.0 + rnd.nextDouble() * 12).toFloat()
        val az = (rnd.nextDouble() * 2 * Math.PI).toFloat()
        val dist = .42f + rnd.nextFloat() * .08f
        val target = V3(0f, 0f, 0f)
        val eye = V3(sin(tilt) * cos(az) * dist, cos(tilt) * dist, sin(tilt) * sin(az) * dist)
        val opts = if (hard) RealisticScene.Opts(exposure = .7f + rnd.nextFloat() * .5f, blur = rnd.nextInt(3), noise = 3f + rnd.nextFloat() * 4f, carpet = true, shadow = true)
                   else RealisticScene.Opts(exposure = .9f + rnd.nextFloat() * .2f, blur = rnd.nextInt(2), noise = 2f + rnd.nextFloat() * 2f, carpet = rnd.nextBoolean(), shadow = rnd.nextBoolean())
        return Scene(tiles, eye, target, opts, w, h)
    }

    class Det(val x: Float, val y: Float, val shape: String, val size: String)

    class Stats {
        var truth = 0; var found = 0; var kindOk = 0; var shapeOk = 0; var sizeOk = 0; var falsePos = 0; var countErr = 0
        val confusion = HashMap<String, Int>() // "verdade → detectado"
        fun add(o: Stats) { truth += o.truth; found += o.found; kindOk += o.kindOk; shapeOk += o.shapeOk; sizeOk += o.sizeOk; falsePos += o.falsePos; countErr += o.countErr; o.confusion.forEach { (k, v) -> confusion.merge(k, v, Int::plus) } }
        fun pct(a: Int) = if (truth == 0) 0.0 else 100.0 * a / truth
        override fun toString() = "peças ${truth}: achadas %.1f%%, tipo certo (forma+tamanho) %.1f%%, forma %.1f%%, tamanho %.1f%%, falsos positivos $falsePos, erro de contagem por tipo %.1f%%"
            .format(pct(found), pct(kindOk), pct(shapeOk), pct(sizeOk), pct(countErr))
    }

    /** Compara [dets] com o gabarito da cena. */
    fun score(s: Scene, dets: List<Det>): Stats {
        val st = Stats()
        val truthPx = s.tiles.mapNotNull { t ->
            val c = RealisticScene.project(s.eye, s.target, t.c, s.w, s.h) ?: return@mapNotNull null
            val c2 = RealisticScene.project(s.eye, s.target, t.c + t.u * t.e, s.w, s.h) ?: return@mapNotNull null
            if (c[0] < 0 || c[1] < 0 || c[0] >= s.w || c[1] >= s.h) return@mapNotNull null
            Triple(t, c, hypot(c2[0] - c[0], c2[1] - c[1]))
        }
        st.truth = truthPx.size
        val used = HashSet<Det>()
        for ((t, c, side) in truthPx.sortedBy { it.third }) {
            val d = dets.filter { it !in used }.minByOrNull { hypot(it.x - c[0], it.y - c[1]) }?.takeIf { hypot(it.x - c[0], it.y - c[1]) < side * .5 } ?: run {
                st.confusion.merge("${t.size}/${t.shape} → (não achada)", 1, Int::plus); null
            } ?: continue
            used += d; st.found++
            val shapeOk = d.shape == t.shape; val sizeOk = d.size == t.size
            if (shapeOk) st.shapeOk++; if (sizeOk) st.sizeOk++
            if (shapeOk && sizeOk) st.kindOk++ else st.confusion.merge("${t.size}/${t.shape} → ${d.size}/${d.shape}", 1, Int::plus)
        }
        st.falsePos = dets.count { it !in used }
        val tc = HashMap<String, Int>(); val dc = HashMap<String, Int>()
        truthPx.forEach { tc.merge("${it.first.size}/${it.first.shape}", 1, Int::plus) }
        dets.forEach { dc.merge("${it.size}/${it.shape}", 1, Int::plus) }
        st.countErr = (tc.keys + dc.keys).sumOf { abs((tc[it] ?: 0) - (dc[it] ?: 0)) } / 2
        return st
    }

    /** Detector de fotos do app (o mesmo da contagem de inventário). */
    fun detect(px: IntArray, w: Int, h: Int): List<Det> =
        PhotoPieces.from(Segmenter.segment(px, w, h, maxBlobs = 60)).map { Det(it.cx, it.cy, it.shape, it.size) }

    fun run(seeds: IntRange, n: Int, hard: Boolean, detector: (IntArray, Int, Int) -> List<Det> = ::detect, each: ((Scene, Stats) -> Unit)? = null): Stats {
        val total = Stats()
        for (seed in seeds) {
            val s = scene(seed.toLong(), n, hard)
            val f = RealisticScene.render(s.tiles, s.eye, s.target, Random(seed.toLong()), s.w, s.h, s.opts)
            val st = score(s, detector(f.rgb, f.w, f.h))
            each?.invoke(s, st)
            total.add(st)
        }
        return total
    }

    @Suppress("unused") private fun unused() = sqrt(0.0)
}
