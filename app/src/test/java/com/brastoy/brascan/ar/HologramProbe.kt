package com.brastoy.brascan.ar

import com.brastoy.brascan.three.V3
import com.brastoy.brascan.vision.Catalog
import com.brastoy.brascan.vision.Intrinsics
import com.brastoy.brascan.vision.Piece3D
import com.brastoy.brascan.vision.PieceSlam
import com.brastoy.brascan.vision.Pose
import com.brastoy.brascan.vision.RealisticScene
import com.brastoy.brascan.vision.Segmenter
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Sonda: o que o usuário VÊ na câmera (hologramas) na cena realista (casinha de 9 peças encostadas). Compara,
 * no MESMO rastreio, o jeito antigo (toda detecção do quadro vira holograma + todas as peças confirmadas do mapa
 * na câmera do SLAM, mesmo perdido) com [Holograms]; "todas-det" (toda detecção, sem mapa) é a referência do
 * que o detector cobre. Por quadro, olhando com a câmera verdadeira (raio do olho até o centro do holograma):
 *  - fantasma: holograma na tela que não corresponde a peça verdadeira (cai no fundo, num "intruso" ou numa
 *    peça de outra cor);
 *  - duplicado: segundo holograma em cima da mesma peça verdadeira;
 *  - falta: peça verdadeira visível (critério da RealisticScanProbe) sem holograma dela em cima;
 *  - forma errada: holograma em cima de peça de contorno diferente;
 *  - apagou: peça continua visível, tinha holograma no quadro anterior e agora não (pisca);
 *  - atraso: quadros entre a 1ª vez que a peça fica visível e o 1º holograma nela (e quanto disso é da escolha,
 *    contando da 1ª detecção dela).
 * Caminhadas: a padrão (24 quadros, 15° entre quadros, como RealisticScanProbe) e uma densa (96 quadros, 3,75°,
 * mais perto do celular de verdade andando devagar). Com intrusos: em ~35% dos quadros aparece, por 1-2 quadros,
 * algo com cara de peça que não existe (reflexo, brilho, mão passando: mesma aparência de uma peça, ímãs inclusive,
 * pior caso para quem só olha um quadro), entre a câmera e a casinha.
 */
class HologramProbe {
    private val k = Intrinsics(390.0, 390.0, 240.0, 180.0)
    private val W = 480; private val H = 360

    private fun upInCamera(eye: V3, target: V3): DoubleArray {
        val f = (target - eye).norm(); val r = f.cross(V3.UP).norm(); val up = r.cross(f)
        return doubleArrayOf(V3.UP.dot(r).toDouble(), -V3.UP.dot(up).toDouble(), V3.UP.dot(f).toDouble())
    }

    class Holo(val cx: Double, val cy: Double, val shape: String, val tag: String, val color: Int)
    class Score {
        var frames = 0; var shown = 0; var ghosts = 0; var dups = 0; var visible = 0; var missing = 0; var wrongShape = 0
        var delaySum = 0; var delayN = 0; var never = 0; var blinks = 0; var extraSum = 0; var extraN = 0
        val ghostBy = HashMap<String, Int>(); val dupBy = HashMap<String, Int>()
        fun add(o: Score) {
            frames += o.frames; shown += o.shown; ghosts += o.ghosts; dups += o.dups; visible += o.visible; missing += o.missing; wrongShape += o.wrongShape
            delaySum += o.delaySum; delayN += o.delayN; never += o.never; blinks += o.blinks; extraSum += o.extraSum; extraN += o.extraN
            o.ghostBy.forEach { (a, b) -> ghostBy.merge(a, b, Int::plus) }; o.dupBy.forEach { (a, b) -> dupBy.merge(a, b, Int::plus) }
        }
        override fun toString() = ("na tela $shown, fantasmas $ghosts ${ghostBy.toSortedMap()}, duplicados $dups ${dupBy.toSortedMap()}, falta $missing/$visible (${100 * missing / visible.coerceAtLeast(1)}%%), " +
            "forma errada $wrongShape, apagou $blinks, atraso médio %.2f quadros (+%.2f sobre a 1ª detecção), nunca $never").format(delaySum.toDouble() / delayN.coerceAtLeast(1), extraSum.toDouble() / extraN.coerceAtLeast(1))
    }

    /** Centro do contorno do holograma na imagem ([toCam]: mundo → câmera; null = já em coordenadas da câmera). */
    private fun holo(p: Piece3D, toCam: Pose?, tag: String): Holo? {
        val e = Catalog.edgeM(p.size)
        val px = Catalog.polygon(p.shape).map { (a, b) ->
            val q = p.center + p.u * (a * e) + p.v * (b * e)
            val v = doubleArrayOf(q.x.toDouble(), q.y.toDouble(), q.z.toDouble())
            val c = toCam?.apply(v) ?: v
            if (c[2] < .02) return null
            doubleArrayOf(k.fx * c[0] / c[2] + k.cx, k.fy * c[1] / c[2] + k.cy)
        }
        return Holo(px.sumOf { it[0] } / px.size, px.sumOf { it[1] } / px.size, p.shape, tag, p.color)
    }

    private fun inside(c: List<Pair<Float, Float>>, x: Float, y: Float): Boolean {
        var ins = false
        for (i in c.indices) { val (ax, ay) = c[i]; val (bx, by) = c[(i + 1) % c.size]
            if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) ins = !ins }
        return ins
    }

    private fun sameColor(a: Int, b: Int): Boolean {
        val x = FloatArray(3).also { Segmenter.hsv(a, it) }; val y = FloatArray(3).also { Segmenter.hsv(b, it) }
        val d = abs(x[0] - y[0]); return minOf(d, 360f - d) <= 35f
    }

    class Walk(val name: String, val o: RealisticScene.Opts, val frames: Int, val intruders: Boolean)

    private val PALETTE = listOf(0xFFE53950.toInt(), 0xFF2F8CFF.toInt(), 0xFF2EC27E.toInt(), 0xFFFFC531.toInt(), 0xFF9B5DE5.toInt(), 0xFFFF7A2F.toInt())

    /** Intruso: peça de mentira entre a câmera e a casinha, virada mais ou menos para a câmera. */
    private fun intruder(rnd: Random, eye: V3, target: V3, id: Int): Pair<RealisticScene.Tile, V3> {
        val fwd = (target - eye).norm(); val side = fwd.cross(V3.UP).norm(); val up = side.cross(fwd)
        val c = eye + (target - eye) * (.35f + .3f * rnd.nextFloat()) + side * ((rnd.nextFloat() - .5f) * .12f) + up * ((rnd.nextFloat() - .5f) * .08f)
        val tilt = Math.toRadians(35.0 * rnd.nextDouble()).toFloat(); val az = (rnd.nextDouble() * 2 * Math.PI).toFloat()
        val n = (fwd * -1f + (side * cos(az) + up * sin(az)) * kotlin.math.tan(tilt)).norm()
        val ref = if (abs(n.dot(V3.UP)) < .9f) V3.UP else side
        val u0 = ref.cross(n).norm(); val v0 = n.cross(u0)
        val spin = (rnd.nextDouble() * 2 * Math.PI).toFloat()
        val u = u0 * cos(spin) + v0 * sin(spin); val v = n.cross(u)
        val shape = listOf("quadrado", "retangulo", "equilatero")[rnd.nextInt(3)]
        val vel = (side * (rnd.nextFloat() - .5f) + up * (rnd.nextFloat() - .5f)).norm() * .05f // mão passando: 5 cm por quadro
        return RealisticScene.Tile("intruso $id", shape, "grande", PALETTE[rnd.nextInt(PALETTE.size)], c, u, v) to vel
    }

    /** Roda a caminhada e pontua cada política (e o jeito antigo) no MESMO rastreio. */
    fun run(w: Walk, policies: List<Pair<String, Holograms.Policy>>, verbose: Boolean = false): Map<String, Score> {
        val house = RealisticScene.house()
        val slam = PieceSlam(k); val rnd = Random(11); val rndI = Random(5); val target = V3(0f, .05f, 0f)
        val names = listOf("antes", "todas-det") + policies.map { it.first }
        val scores = names.associateWith { Score() }
        val mems = policies.map { Holograms.Memory() }.toMutableList()
        val firstVis = IntArray(house.size) { -1 }
        val first = names.associateWith { IntArray(house.size) { -1 } }
        val covered = names.associateWith { BooleanArray(house.size) }; var visPrev = BooleanArray(house.size)
        var intr: Triple<RealisticScene.Tile, V3, Int>? = null // intruso, velocidade, quadros que ainda vive
        var nIntr = 0
        for (i in 0 until w.frames) {
            val a = i * 2 * Math.PI / w.frames
            // mesma volta da RealisticScanProbe (raio 42 cm, 28 cm de altura, balanço vertical); a densa balança igual por ângulo
            val eye = V3((cos(a) * .42f).toFloat(), .28f + (.03 * sin(i * .9 * 24 / w.frames)).toFloat(), (sin(a) * .42f).toFloat())
            if (w.intruders) {
                intr = intr?.let { (t, vel, left) -> if (left <= 1) null else Triple(RealisticScene.Tile(t.name, t.shape, t.size, t.color, t.c + vel, t.u, t.v), vel, left - 1) }
                if (intr == null && rndI.nextFloat() < .35f) { val (t, vel) = intruder(rndI, eye, target, nIntr++); intr = Triple(t, vel, if (rndI.nextFloat() < .7f) 1 else 2) }
            }
            val tiles = house + listOfNotNull(intr?.first)
            val f = RealisticScene.render(tiles, eye, target, rnd, W, H, w.o)
            val up = upInCamera(eye, target)
            val dets = slam.track(Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f), up, f.rgb)
            // verdade: peças da casinha visíveis (critério da RealisticScanProbe)
            val vis = house.indices.filter { ti -> val t = house[ti]; val p = RealisticScene.project(eye, target, t.c, W, H) ?: return@filter false
                p[0] > 10 && p[0] < 470 && p[1] > 10 && p[1] < 350 && abs((t.c - eye).norm().dot(t.n)) > .25f && RealisticScene.visible(tiles, eye, t) }
            for (ti in vis) if (firstVis[ti] < 0) firstVis[ti] = i
            // o que o olho vê naquele pixel (contorno externo: o furo da moldura conta como peça), com folga de 6 px
            val fw = (target - eye).norm(); val rr = fw.cross(V3.UP).norm(); val uu = rr.cross(fw); val fx = W * 195f / 240
            fun hitAt(x: Double, y: Double): Int? {
                val d = (rr * ((x.toFloat() - W / 2f) / fx) + uu * (-(y.toFloat() - H / 2f) / fx) + fw).norm()
                var best = Float.MAX_VALUE; var hit: Int? = null
                for ((ti, t) in tiles.withIndex()) {
                    val den = d.dot(t.n); if (abs(den) < 1e-6f) continue
                    val tt = (t.c - eye).dot(t.n) / den; if (tt <= 0 || tt >= best) continue
                    val q = eye + d * tt - t.c
                    if (!inside(t.poly, q.dot(t.u) / t.e, q.dot(t.v) / t.e)) continue
                    best = tt; hit = ti
                }
                return hit
            }
            fun onTile(x: Double, y: Double): Int? {
                hitAt(x, y)?.let { return it }
                val near = listOf(-6.0 to 0.0, 6.0 to 0.0, 0.0 to -6.0, 0.0 to 6.0, -4.0 to -4.0, 4.0 to -4.0, -4.0 to 4.0, 4.0 to 4.0).mapNotNull { (dx, dy) -> hitAt(x + dx, y + dy) }
                return near.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            }

            fun score(holos: List<Holo>, name: String) {
                val s = scores.getValue(name); val fst = first.getValue(name)
                s.frames++
                val count = IntArray(house.size); val line = StringBuilder()
                for (h in holos) {
                    if (h.cx < 0 || h.cy < 0 || h.cx >= W || h.cy >= H) continue // fora da tela
                    s.shown++
                    val ti = onTile(h.cx, h.cy)
                    // em cima de nada, de um intruso, ou de uma peça de outra cor: não corresponde a peça verdadeira
                    val why = when { ti == null -> "/fundo"; ti >= house.size -> "/intruso"; !sameColor(h.color, house[ti].color) -> "/outra-cor"; else -> null }
                    if (ti == null || why != null) {
                        s.ghosts++; s.ghostBy.merge(h.tag.substringBefore('#') + why, 1, Int::plus)
                        line.append(" FANTASMA ${h.tag}:${h.shape}@(%.0f,%.0f)$why".format(h.cx, h.cy)); continue
                    }
                    if (count[ti]++ > 0) { s.dups++; s.dupBy.merge(h.tag.substringBefore('#'), 1, Int::plus); line.append(" DUPLICADO ${h.tag}:${house[ti].name}") }
                    if (PieceSlam.outlineOf(h.shape) != PieceSlam.outlineOf(house[ti].shape) && h.shape != house[ti].shape) s.wrongShape++
                }
                for (ti in vis) { s.visible++; if (count[ti] == 0) { s.missing++; line.append(" [falta ${house[ti].name}]") } }
                // piscou: a peça continua visível, tinha holograma no quadro anterior e agora não tem
                val cov = covered.getValue(name)
                for (ti in vis) if (visPrev[ti] && cov[ti] && count[ti] == 0) s.blinks++
                for (ti in house.indices) cov[ti] = count[ti] > 0
                for (ti in house.indices) if (firstVis[ti] >= 0 && fst[ti] < 0 && count[ti] > 0) { fst[ti] = i; s.delaySum += i - firstVis[ti]; s.delayN++ }
                if (verbose) println("  ${w.name} q$i $name: ${holos.size} hologramas$line")
            }

            // ANTES: cada detecção do quadro + todas as confirmadas do mapa na câmera do SLAM (mesmo perdido)
            val toCam = slam.camera?.inverse()
            val allDets = dets.mapNotNull { d -> val p = d.candidates[0]
                holo(Piece3D(d.shape, "grande", d.color, PieceSlam.v3(p.t), PieceSlam.v3(p.col(0)), PieceSlam.v3(p.col(1)), PieceSlam.v3(p.col(2)), 0f, 0f, 1), null, "det") }
            score(allDets + (if (toCam == null) emptyList() else slam.map.filter { it.count >= PieceSlam.MIN_VIEWS }.mapNotNull { holo(it.toPiece3D(), toCam, "mapa") }), "antes")
            // referência: só as detecções do quadro, sem filtro nenhum (teto do que a detecção cobre)
            score(allDets, "todas-det")
            for ((j, pol) in policies.withIndex()) {
                val fr = Holograms.frame(slam, dets, up, k, mems[j], pol.second); mems[j] = fr.memory
                score(fr.live.mapIndexedNotNull { n, (_, p) -> holo(p, null, fr.why[n].name.lowercase()) } + fr.held.mapNotNull { holo(it, null, "segurada") } +
                    (if (toCam == null) emptyList() else fr.map.mapNotNull { (p, _) -> holo(p, toCam, "mapa") }), pol.first)
            }
            visPrev = BooleanArray(house.size) { it in vis }
        }
        for (n in names) scores.getValue(n).never = house.indices.count { firstVis[it] >= 0 && first.getValue(n)[it] < 0 }
        val fDet = first.getValue("todas-det")
        for (n in names) { val fs = first.getValue(n); val s = scores.getValue(n)
            for (ti in house.indices) if (fDet[ti] >= 0 && fs[ti] >= 0) { s.extraSum += fs[ti] - fDet[ti]; s.extraN++ } }
        for (n in names) println("${w.name} ${n.padEnd(12)} ${scores.getValue(n)}")
        return scores
    }

    private fun walks(frames: Int, intruders: Boolean) = listOf(
        Walk("limpa/$frames${if (intruders) "+intrusos" else ""}", RealisticScene.Opts(noise = 0f, carpet = false, shadow = false), frames, intruders),
        Walk("celular/$frames${if (intruders) "+intrusos" else ""}", RealisticScene.Opts(noise = 4f, blur = 2, carpet = true, shadow = true), frames, intruders),
        Walk("escura/$frames${if (intruders) "+intrusos" else ""}", RealisticScene.Opts(exposure = .6f, noise = 6f, blur = 1), frames, intruders),
    )

    private val policies = listOf(
        "depois" to Holograms.Policy(),
        "sem-segurar" to Holograms.Policy(hold = 0),
        "trilha/1" to Holograms.Policy(trackGap = 1),
        "+forte" to Holograms.Policy(strong = true),
        "+mapa/1" to Holograms.Policy(mapRecent = 1),
        "+mapa/4" to Holograms.Policy(mapRecent = 4),
    )

    @Test fun probe() {
        val verbose = System.getenv("HOLO_VERBOSE") == "1"
        for (intruders in listOf(false, true)) for (frames in listOf(24, 96)) {
            val tot = LinkedHashMap<String, Score>()
            for (w in walks(frames, intruders)) for ((n, s) in run(w, policies, verbose)) tot.getOrPut(n) { Score() }.add(s)
            for ((n, s) in tot) println("TOTAL $frames quadros${if (intruders) " +intrusos" else ""} ${n.padEnd(12)} $s")
        }
    }
}
