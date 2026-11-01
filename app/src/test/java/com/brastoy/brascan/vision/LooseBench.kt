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

    class Det(val x: Float, val y: Float, val shape: String, val size: String, val extra: Any? = null)

    class Stats {
        var truth = 0; var found = 0; var kindOk = 0; var shapeOk = 0; var sizeOk = 0; var falsePos = 0; var countErr = 0
        /** Tempo só do detector (sem renderizar a cena), somado; [scenes] cenas. */
        var detNs = 0L; var scenes = 0
        val detMs get() = if (scenes == 0) 0.0 else detNs / 1e6 / scenes
        val confusion = HashMap<String, Int>() // "verdade → detectado"
        /** Só a forma, entre as achadas: "verdade → detectada" (erros) e acertos por forma. */
        val shapeConf = HashMap<String, Int>(); val shapeHit = HashMap<String, Int>(); val shapeSeen = HashMap<String, Int>()
        /** Pares (verdade, detecção) das achadas, para sondas. */
        val pairs = ArrayList<Pair<RealisticScene.Tile, Det>>()
        /** Por tipo verdadeiro ("tamanho/forma"): [peças, achadas, tipo certo]. */
        val byKind = HashMap<String, IntArray>()
        fun kindLine(kinds: List<String>) = kinds.joinToString { k -> val a = byKind[k] ?: IntArray(3); "$k achadas ${a[1]}/${a[0]}, tipo certo ${a[2]}" }
        fun add(o: Stats) {
            detNs += o.detNs; scenes += o.scenes
            truth += o.truth; found += o.found; kindOk += o.kindOk; shapeOk += o.shapeOk; sizeOk += o.sizeOk; falsePos += o.falsePos; countErr += o.countErr
            o.confusion.forEach { (k, v) -> confusion.merge(k, v, Int::plus) }; o.shapeConf.forEach { (k, v) -> shapeConf.merge(k, v, Int::plus) }
            o.shapeHit.forEach { (k, v) -> shapeHit.merge(k, v, Int::plus) }; o.shapeSeen.forEach { (k, v) -> shapeSeen.merge(k, v, Int::plus) }; pairs += o.pairs
            o.byKind.forEach { (k, v) -> val a = byKind.getOrPut(k) { IntArray(3) }; for (i in 0..2) a[i] += v[i] }
        }
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
            val kc = st.byKind.getOrPut("${t.size}/${t.shape}") { IntArray(3) }; kc[0]++
            val d = dets.filter { it !in used }.minByOrNull { hypot(it.x - c[0], it.y - c[1]) }?.takeIf { hypot(it.x - c[0], it.y - c[1]) < side * .5 } ?: run {
                st.confusion.merge("${t.size}/${t.shape} → (não achada)", 1, Int::plus); null
            } ?: continue
            used += d; st.found++; st.pairs += t to d; kc[1]++
            val shapeOk = d.shape == t.shape; val sizeOk = d.size == t.size
            st.shapeSeen.merge(t.shape, 1, Int::plus); if (shapeOk) st.shapeHit.merge(t.shape, 1, Int::plus) else st.shapeConf.merge("${t.shape} → ${d.shape}", 1, Int::plus)
            if (shapeOk) st.shapeOk++; if (sizeOk) st.sizeOk++
            if (shapeOk && sizeOk) { st.kindOk++; kc[2]++ } else st.confusion.merge("${t.size}/${t.shape} → ${d.size}/${d.shape}", 1, Int::plus)
        }
        st.falsePos = dets.count { it !in used }
        val tc = HashMap<String, Int>(); val dc = HashMap<String, Int>()
        truthPx.forEach { tc.merge("${it.first.size}/${it.first.shape}", 1, Int::plus) }
        dets.forEach { dc.merge("${it.size}/${it.shape}", 1, Int::plus) }
        st.countErr = (tc.keys + dc.keys).sumOf { abs((tc[it] ?: 0) - (dc[it] ?: 0)) } / 2
        return st
    }

    /** Detector de fotos para peças soltas (o da contagem de inventário): PhotoPieces.detect. */
    fun detect(px: IntArray, w: Int, h: Int): List<Det> =
        PhotoPieces.detect(px, w, h, maxBlobs = 60).map { Det(it.cx, it.cy, it.shape, it.size) }

    /** O detector de antes (o que scan/Pieces.kt chama hoje: Segmenter.segment sem loose), para comparar. */
    fun detectOld(px: IntArray, w: Int, h: Int): List<Det> =
        PhotoPieces.from(Segmenter.segment(px, w, h, maxBlobs = 60)).map { Det(it.cx, it.cy, it.shape, it.size) }

    /**
     * [scale]: renderiza a MESMA cena com [scale]× a resolução (480×360 → 960×720 com 2), mesmo campo de visão; o borrão
     * (em px) cresce junto, como numa câmera de verdade. Mede o que ganha processando a contagem em resolução maior.
     */
    fun run(seeds: IntRange, n: Int, hard: Boolean, detector: (IntArray, Int, Int) -> List<Det> = ::detect, each: ((Scene, Stats) -> Unit)? = null,
            sceneDetector: ((Scene, IntArray, Int, Int) -> List<Det>)? = null, scale: Int = 1): Stats =
        evaluate(frames(seeds, n, hard, scale), { s, f -> sceneDetector?.invoke(s, f.rgb, f.w, f.h) ?: detector(f.rgb, f.w, f.h) }, each)

    /** Cenas já renderizadas (para rodar várias variantes do detector nas mesmas imagens); [scale] como em [run]. */
    fun frames(seeds: IntRange, n: Int, hard: Boolean, scale: Int = 1): List<Pair<Scene, FrameSample>> = seeds.map { seed ->
        val s0 = scene(seed.toLong(), n, hard)
        val s = if (scale == 1) s0 else s0.opts.let { o ->
            Scene(s0.tiles, s0.eye, s0.target, RealisticScene.Opts(o.exposure, o.blur * scale, o.noise, o.carpet, o.shadow, o.translucency), s0.w * scale, s0.h * scale)
        }
        s to RealisticScene.render(s.tiles, s.eye, s.target, Random(seed.toLong()), s.w, s.h, s.opts)
    }

    /**
     * Detector que também recebe a cena e o quadro (intrínsecos e pose da câmera verdadeira: rodada "com gravidade").
     * Mede o tempo só do detector ([Stats.detMs]).
     */
    fun evaluate(frames: List<Pair<Scene, FrameSample>>, detector: (Scene, FrameSample) -> List<Det>, each: ((Scene, Stats) -> Unit)? = null): Stats {
        val total = Stats()
        for ((s, f) in frames) {
            val t0 = System.nanoTime()
            val dets = detector(s, f)
            val dt = System.nanoTime() - t0
            val st = score(s, dets)
            st.detNs = dt; st.scenes = 1
            each?.invoke(s, st)
            total.add(st)
        }
        return total
    }

    /**
     * Câmera como o app veria com o acelerômetro: intrínsecos exatos do renderizador e a gravidade no referencial da
     * câmera (x direita, y para baixo, z para a frente). O mesmo que [GroundCam.fromFrame] do quadro renderizado.
     */
    fun camera(s: Scene): GroundCam {
        val fx = s.w * 195f / 240
        val Y = V3(0f, 1f, 0f)
        val f = (s.target - s.eye).norm(); val r = f.cross(Y).norm(); val up = r.cross(f)
        return GroundCam(fx, fx, s.w / 2f, s.h / 2f, floatArrayOf(-Y.dot(r), Y.dot(up), -Y.dot(f)))
    }

    /** Detector de fotos com gravidade + intrínsecos da cena (sem o quadro renderizado). */
    fun detectWithFloor(s: Scene, px: IntArray, w: Int, h: Int): List<Det> =
        PhotoPieces.detect(px, w, h, maxBlobs = 60, cam = camera(s)).map { Det(it.cx, it.cy, it.shape, it.size) }

    /**
     * Detector de fotos com a câmera verdadeira da cena: intrínsecos + gravidade (o celular tem acelerômetro) e,
     * com [height], também a altura da câmera ao chão (escala absoluta).
     */
    fun detectWithCamera(s: Scene, f: FrameSample, height: Boolean = false): List<Det> =
        PhotoPieces.detect(f.rgb, f.w, f.h, maxBlobs = 60, cam = GroundCam.fromFrame(f, heightM = if (height) s.eye.y else 0f))
            .map { Det(it.cx, it.cy, it.shape, it.size) }

    @Suppress("unused") private fun unused() = sqrt(0.0)
}
