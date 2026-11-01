package com.brastoy.brascan.vision

import com.brastoy.brascan.scan.Counting
import com.brastoy.brascan.three.V3
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Banco de VÍDEO da contagem ao vivo: a mesma cena de peças soltas do [LooseBench] filmada por uma câmera na mão
 * que se move devagar (deriva de alguns mm por quadro, tremor, giro em torno da vertical, um tranco às vezes),
 * com ruído, borrão e exposição sorteados em cada quadro. Os quadros são detectados uma vez (o detector é o
 * mesmo do app: [Counting.detect]) e cada método de exibição ([Method]) recebe a mesma sequência de detecções.
 *
 * Por quadro mede: caixas mostradas que não são peça (fantasmas), peças (centro na imagem) sem caixa, erro de
 * contagem por tipo do total exibido (Σ|exibido − verdade| sobre os 30 tipos; verdade = peças inteiras na
 * imagem), tipo errado nas caixas certas; por clipe: quadro em que o número exibido para de mudar e quantas
 * vezes ele mudou sem que a verdade mudasse (pisca).
 */
object LooseVideoBench {
    class Frame(val eye: V3, val target: V3, val w: Int, val h: Int, val found: List<Counting.Found>, val truth: List<Truth>, val detMs: Double)
    class Clip(val seed: Long, val hard: Boolean, val frames: List<Frame>)

    /** Peça da cena vista num quadro: centro e lado em pixels, cantos projetados, inteira na imagem? */
    class Truth(val idx: Int, val key: String,val cx: Double, val cy: Double, val side: Double, val corners: List<DoubleArray>, val centerIn: Boolean, val whole: Boolean, val any: Boolean)

    /** O que a tela mostra num quadro. */
    class Shown(val boxes: List<Counting.Found>, val counts: Map<String, Int>)

    interface Method { fun push(found: List<Counting.Found>, w: Int, h: Int): Shown }

    /** Comportamento de 065cead: todas as detecções do quadro viram caixa; total = mediana por tipo de 7 quadros. */
    class Current : Method {
        private val stab = Counting.Stabilizer(7)
        override fun push(found: List<Counting.Found>, w: Int, h: Int) = Shown(found, stab.push(Counting.tally(found)))
    }

    /** Rastreador de peças entre quadros ([Counting.Tracker]). */
    class Tracked(private val t: Counting.Tracker = Counting.Tracker()) : Method {
        var nanos = 0L; var calls = 0
        override fun push(found: List<Counting.Found>, w: Int, h: Int): Shown {
            val t0 = System.nanoTime(); val boxes = t.push(found, w, h); nanos += System.nanoTime() - t0; calls++
            return Shown(boxes, t.counts)
        }
    }

    private fun g(rnd: Random) = rnd.nextGaussian().toFloat()

    /**
     * [hand]: uma mão entra por uma borda no meio do clipe, avança ~25 px por quadro até perto do centro e volta (a
     * pessoa ajeitando uma peça): objeto que não é peça, cor de pele, que se move e cobre peças por alguns quadros.
     */
    fun clip(seed: Long, n: Int, hard: Boolean, frames: Int = 12, hand: Boolean = false, fast: Float = 1f): Clip {
        val s = LooseBench.scene(seed, n, hard)
        val rnd = Random(seed * 7919 + 13)
        val hr = Random(seed * 31 + 7)
        val handStart = 3 + hr.nextInt(3); val handSide = hr.nextInt(4); val handAt = .25f + .5f * hr.nextFloat()
        val skin = intArrayOf(0xFFE0AC90.toInt(), 0xFFC68A6A.toInt(), 0xFF9C6A50.toInt())[hr.nextInt(3)]
        // mão: deriva quase constante (3–6 mm/quadro a ~45 cm do chão ≈ 3–5 px), tremor de ~1 mm, giro lento
        // ([fast] multiplica a deriva e o giro: celular mais lento, menos quadros por segundo)
        val speed = (if (hard) .005f else .0035f) * (.7f + .6f * rnd.nextFloat()) * fast
        val dir = rnd.nextDouble() * 2 * Math.PI
        var vx = (cos(dir) * speed).toFloat(); var vz = (sin(dir) * speed).toFloat()
        val yawRate = Math.toRadians(g(rnd) * (if (hard) 1.2 else .6) * fast).toFloat()
        val jerk = if (hard) 3 + rnd.nextInt(frames - 4) else -1
        var px = 0f; var pz = 0f; var py = 0f; var yaw = 0f
        val out = ArrayList<Frame>()
        for (i in 0 until frames) {
            if (i > 0) {
                vx += g(rnd) * .001f; vz += g(rnd) * .001f
                px += vx + g(rnd) * .001f; pz += vz + g(rnd) * .001f; py += g(rnd) * .0015f
                yaw += yawRate + Math.toRadians(g(rnd) * .3).toFloat()
                if (i == jerk) { val a = rnd.nextDouble() * 2 * Math.PI; px += (cos(a) * .012).toFloat(); pz += (sin(a) * .012).toFloat() }
            }
            val rel = s.eye - s.target; val c = cos(yaw); val sn = sin(yaw)
            val target = s.target + V3(px, 0f, pz)
            val eye = target + V3(rel.x * c - rel.z * sn, rel.y + py, rel.x * sn + rel.z * c)
            val o = RealisticScene.Opts(exposure = s.opts.exposure * (1f + g(rnd) * .04f), blur = rnd.nextInt(if (hard) 3 else 2),
                noise = s.opts.noise, carpet = s.opts.carpet, shadow = s.opts.shadow)
            val f = RealisticScene.render(s.tiles, eye, target, Random(seed * 1000 + i), s.w, s.h, o)
            val k = i - handStart
            if (hand && k in 0..5) drawHand(f.rgb, f.w, f.h, handSide, handAt, reach = (if (k <= 3) k + 1 else 6 - k) * 25f, skin, hr)
            val t0 = System.nanoTime(); val found = Counting.detect(f.rgb, f.w, f.h); val ms = (System.nanoTime() - t0) / 1e6
            out += Frame(eye, target, f.w, f.h, found, truth(s, eye, target), ms)
        }
        return Clip(seed, hard, out)
    }

    /** Antebraço + palma (elipse) entrando [reach] pixels pela borda [side] (0 esq., 1 dir., 2 cima, 3 baixo), sombreado. */
    private fun drawHand(rgb: IntArray, w: Int, h: Int, side: Int, at: Float, reach: Float, skin: Int, rnd: Random) {
        val horiz = side < 2
        val across = (if (horiz) h else w) * at
        val palmR = 34f; val palmW = 26f; val armW = 22f
        val tip = reach + palmR // ponta dos dedos, medida a partir da borda
        for (y in 0 until h) for (x in 0 until w) {
            val a = when (side) { 0 -> x.toFloat(); 1 -> (w - 1 - x).toFloat(); 2 -> y.toFloat(); else -> (h - 1 - y).toFloat() } // ao longo do braço
            val b = (if (horiz) y else x) - across // de lado
            val pa = (a - (tip - palmR)) / palmR; val pb = b / palmW
            val inPalm = pa * pa + pb * pb < 1f
            val inArm = a < tip - palmR && abs(b) < armW
            if (!inPalm && !inArm) continue
            val shade = .75f + .25f * (1f - minOf(1f, abs(b) / palmW)) + rnd.nextGaussian().toFloat() * .03f
            val r = ((skin shr 16 and 255) * shade).toInt().coerceIn(0, 255); val g = ((skin shr 8 and 255) * shade).toInt().coerceIn(0, 255); val bl = ((skin and 255) * shade).toInt().coerceIn(0, 255)
            rgb[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }

    private fun truth(s: LooseBench.Scene, eye: V3, target: V3): List<Truth> = s.tiles.withIndex().mapNotNull { (idx, t) ->
        val c = RealisticScene.project(eye, target, t.c, s.w, s.h) ?: return@mapNotNull null
        val c2 = RealisticScene.project(eye, target, t.c + t.u * t.e, s.w, s.h) ?: return@mapNotNull null
        val cs = t.corners().map { RealisticScene.project(eye, target, it, s.w, s.h) ?: return@mapNotNull null }
        fun inImg(p: DoubleArray, m: Double) = p[0] >= m && p[1] >= m && p[0] <= s.w - m && p[1] <= s.h - m
        Truth(idx, "${t.size}/${t.shape}",c[0], c[1], hypot(c2[0] - c[0], c2[1] - c[1]), cs, inImg(c, 0.0), cs.all { inImg(it, 3.0) }, cs.any { inImg(it, 0.0) })
    }

    private fun insidePoly(c: List<DoubleArray>, x: Double, y: Double): Boolean {
        var inside = false
        for (i in c.indices) { val a = c[i]; val b = c[(i + 1) % c.size]
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) inside = !inside }
        return inside
    }

    class FrameScore(val ghosts: Int, val missing: Int, val countErr: Int, val totalErr: Int, val wrongKind: Int, val boxes: Int, val truthCount: Map<String, Int>,
                     /** verdade → mostrado, nas caixas certas de peças inteiras */ val pairs: List<Pair<String, String>>,
                     /** índice da peça na cena → caixa mostrada */ val match: Map<Int, Counting.Found>)

    fun score(f: Frame, sh: Shown): FrameScore {
        // pares caixa ↔ peça: centro da caixa a menos de meio lado do centro da peça, ou (peça cortada pela borda)
        // dentro do contorno projetado; um para um, do par mais perto para o mais longe
        val cand = ArrayList<Triple<Int, Int, Double>>()
        for ((bi, b) in sh.boxes.withIndex()) {
            val bx = b.cx * f.w.toDouble(); val by = b.cy * f.h.toDouble()
            for ((ti, t) in f.truth.withIndex()) {
                if (!t.any) continue
                val d = hypot(bx - t.cx, by - t.cy)
                if (d < t.side * .5 || (!t.whole && insidePoly(t.corners, bx, by))) cand += Triple(bi, ti, d)
            }
        }
        val bUsed = HashSet<Int>(); val tUsed = HashMap<Int, Int>()
        for ((bi, ti, _) in cand.sortedBy { it.third }) { if (bi in bUsed || ti in tUsed) continue; bUsed += bi; tUsed[ti] = bi }
        val ghosts = sh.boxes.size - bUsed.size
        val missing = f.truth.indices.count { f.truth[it].centerIn && it !in tUsed }
        val wrongKind = tUsed.count { (ti, bi) -> f.truth[ti].whole && !sh.boxes[bi].cut && sh.boxes[bi].kind.key != f.truth[ti].key }
        val tc = f.truth.filter { it.whole }.groupingBy { it.key }.eachCount()
        val err = (tc.keys + sh.counts.keys).sumOf { abs((tc[it] ?: 0) - (sh.counts[it] ?: 0)) }
        val pairs = tUsed.filter { (ti, bi) -> f.truth[ti].whole && !sh.boxes[bi].cut }.map { (ti, bi) -> f.truth[ti].key to sh.boxes[bi].kind.key }
        return FrameScore(ghosts, missing, err, abs(tc.values.sum() - sh.counts.values.sum()), wrongKind, sh.boxes.size, tc, pairs,
            tUsed.entries.associate { (ti, bi) -> f.truth[ti].idx to sh.boxes[bi] })
    }

    class Stats(val frames: Int) {
        var clips = 0; var n = 0; var ghosts = 0; var missing = 0; var countErr = 0; var totalErr = 0; var wrongKind = 0; var boxes = 0; var truth = 0
        var exact = 0 // quadros com a contagem por tipo exata
        var flips = 0 // o número exibido mudou sem a verdade mudar
        var kindFlips = 0 // … e o total ficou igual (peça trocou de tipo)
        var settle = 0 // soma do quadro (1-based) a partir do qual o número exibido não muda mais
        var lastErr = 0 // erro por tipo no último quadro, somado nos clipes
        val errAt = IntArray(frames); val ghostAt = IntArray(frames); val missAt = IntArray(frames)
        /** "verdade → mostrado" nas caixas certas com tipo errado; quantas erram só o tamanho, só a forma, os dois. */
        val confusion = HashMap<String, Int>(); var sizeOnly = 0; var shapeOnly = 0; var both = 0
        fun errors(top: Int = 10) = "tipo errado: só tamanho $sizeOnly, só forma $shapeOnly, os dois $both · " +
            confusion.entries.sortedByDescending { it.value }.take(top).joinToString { "${it.key} ×${it.value}" }
        /** Do 4º quadro em diante (depois de o rastreio ter tido tempo de confirmar). */
        var nR = 0; var ghostsR = 0; var missingR = 0; var countErrR = 0; var totalErrR = 0
        var firstShown = 0 // soma do quadro (1-based) em que aparece o primeiro número
        override fun toString() = ("caixas/quadro %.1f (peças %.1f): fantasmas %.2f, peças sem caixa %.2f, tipo errado %.2f · erro de contagem por tipo %.2f " +
            "(total %.2f), quadros exatos %.0f%%, último quadro %.2f · número muda sem motivo %.1f×/clipe (%.1f só troca de tipo), estável a partir do quadro %.1f, 1º número no quadro %.1f" +
            " · do 4º quadro em diante: fantasmas %.2f, sem caixa %.2f, erro por tipo %.2f (total %.2f)")
            .format(boxes / n.toDouble(), truth / n.toDouble(), ghosts / n.toDouble(), missing / n.toDouble(), wrongKind / n.toDouble(), countErr / n.toDouble(),
                totalErr / n.toDouble(), 100.0 * exact / n, lastErr / clips.toDouble(), flips / clips.toDouble(), kindFlips / clips.toDouble(), settle / clips.toDouble(), firstShown / clips.toDouble(),
                ghostsR / nR.toDouble(), missingR / nR.toDouble(), countErrR / nR.toDouble(), totalErrR / nR.toDouble())
        fun curve(a: IntArray) = a.joinToString(" ") { "%.2f".format(it / clips.toDouble()) }
    }

    /**
     * Diagnóstico das detecções cruas: para cada peça inteira, em quantos quadros o tipo saiu certo e se o voto da
     * maioria dos quadros acertaria (teto do que o voto entre quadros consegue sem mudar o detector).
     */
    private val sideErr = HashMap<String, MutableList<Float>>()

    fun consistency(clips: List<Clip>, examples: Int = 0): String {
        var pieces = 0; var seenFrames = 0; var okFrames = 0; var majorityOk = 0; var neverOk = 0; var sizeMaj = 0; var shapeMaj = 0
        val ex = StringBuilder()
        for (c in clips) {
            val per = HashMap<Int, MutableList<Counting.Found>>(); val key = HashMap<Int, String>()
            for (f in c.frames) {
                val s = score(f, Shown(f.found, emptyMap()))
                for ((idx, b) in s.match) {
                    val t = f.truth.first { it.idx == idx }; if (!t.whole || b.cut) continue
                    per.getOrPut(idx) { ArrayList() } += b; key[idx] = t.key
                    sideErr.getOrPut(t.key.substringAfter('/')) { ArrayList() } += (b.side * f.w / t.side).toFloat()
                }
            }
            for ((idx, l) in per) {
                if (l.size < 3) continue
                pieces++; seenFrames += l.size; val k = key.getValue(idx)
                val ok = l.count { it.kind.key == k }; okFrames += ok; if (ok == 0) neverOk++
                val maj = l.groupingBy { it.kind.key }.eachCount().maxByOrNull { it.value }!!.key
                if (maj == k) majorityOk++
                if (maj.substringBefore('/') == k.substringBefore('/')) sizeMaj++
                if (maj.substringAfter('/') == k.substringAfter('/')) shapeMaj++
                if (ex.length < examples * 120 && maj != k) ex.append("\n     $k: ").append(l.joinToString(" ") { it.kind.key + "(%.2f)".format(it.side / maxOf(.001f, l.map { b -> b.side }.sorted()[l.size / 2])) })
            }
        }
        fun q(l: List<Float>, p: Double) = l.sorted()[((l.size - 1) * p).toInt()]
        val sides = sideErr.entries.sortedBy { it.key }.joinToString { (k, l) -> "$k %.2f [%.2f–%.2f]".format(q(l, .5), q(l, .1), q(l, .9)) }
        sideErr.clear()
        return ("peças inteiras vistas em ≥3 quadros: $pieces, tipo certo em %.0f%% dos quadros, voto da maioria certo em %.0f%% (tamanho %.0f%%, forma %.0f%%), " +
            "nunca certo $neverOk").format(100.0 * okFrames / seenFrames, 100.0 * majorityOk / pieces, 100.0 * sizeMaj / pieces, 100.0 * shapeMaj / pieces) +
            "\n     lado medido / lado real (mediana [p10–p90]): $sides" + ex
    }

    fun evaluate(clips: List<Clip>, make: () -> Method): Stats {
        val st = Stats(clips.first().frames.size)
        for (c in clips) {
            val m = make(); st.clips++
            var prev: Map<String, Int>? = null; var prevTruth: Map<String, Int>? = null; var lastChange = 0; var first = c.frames.size + 1
            for ((i, f) in c.frames.withIndex()) {
                val sh = m.push(f.found, f.w, f.h); val s = score(f, sh)
                st.n++; st.ghosts += s.ghosts; st.missing += s.missing; st.countErr += s.countErr; st.totalErr += s.totalErr; st.wrongKind += s.wrongKind
                if (i >= 3) { st.nR++; st.ghostsR += s.ghosts; st.missingR += s.missing; st.countErrR += s.countErr; st.totalErrR += s.totalErr }
                if (sh.counts.isNotEmpty() && first > i + 1) first = i + 1
                for ((t, k) in s.pairs) if (t != k) {
                    st.confusion.merge("$t → $k", 1, Int::plus)
                    val (ts, tt) = t.split("/"); val (ks, kt) = k.split("/")
                    if (ts != ks && tt != kt) st.both++ else if (ts != ks) st.sizeOnly++ else st.shapeOnly++
                }
                st.boxes += s.boxes; st.truth += f.truth.count { it.centerIn }
                if (s.countErr == 0) st.exact++
                st.errAt[i] += s.countErr; st.ghostAt[i] += s.ghosts; st.missAt[i] += s.missing
                if (prev != null && sh.counts != prev) {
                    lastChange = i
                    if (s.truthCount == prevTruth) { st.flips++; if (sh.counts.values.sum() == prev.values.sum()) st.kindFlips++ }
                }
                if (i == c.frames.lastIndex) st.lastErr += s.countErr
                prev = sh.counts; prevTruth = s.truthCount
            }
            st.settle += lastChange + 1; st.firstShown += first
        }
        return st
    }
}
