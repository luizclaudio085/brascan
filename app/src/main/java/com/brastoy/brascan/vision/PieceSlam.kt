package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * SLAM sem ARCore: as próprias peças são os marcadores. Cada peça de contorno conhecido vista na imagem
 * dá a sua pose em relação à câmera (cantos → homografia → refino). Peças já mapeadas que reaparecem dão a
 * pose da câmera; peças novas entram no mapa. Revisitar peças antigas reassocia e corrige a deriva.
 *
 * Escala: com uma câmera só, "peça grande longe" e "peça média perto" geram a mesma imagem. Cada peça guarda
 * as duas hipóteses; a certa é a que fica parada quando a câmera muda de ângulo.
 *
 * Mundo: Y para cima (gravidade do celular, quando houver), metros, origem na primeira peça vista.
 */
class PieceSlam(private val k: Intrinsics, private val autoOptimize: Boolean = true) {
    /** Pose de uma detecção: candidatos peça→câmera (simetrias da peça dão vários com o mesmo erro). */
    /** [frontal]: 1 = vista de frente, 0 = de lado (peso do voto da forma). [outline]: 4 ou 3 cantos. */
    class Detection(val shape: String, val color: Int, val bin: Int, val candidates: List<Pose>, val rms: Double, val areaPx: Int,
                    val imgCorners: List<DoubleArray>, val frontal: Double, val outline: Int,
                    /** Poses para cada molde de contorno (quadrado/retângulo…): usado quando o mapa já sabe a forma. */
                    val byOutline: Map<String, List<Pose>> = emptyMap(),
                    /** Quadrado e retângulo explicam a imagem igual (vista de viés/longe): a forma desta vista vale pouco. */
                    val ambiguous: Boolean = false) {
        fun candidatesFor(shape: String): List<Pose> = byOutline[outlineOf(shape)]?.takeIf { it.isNotEmpty() } ?: candidates
    }

    /** [alts]: todas as poses possíveis desta observação (simetrias, espelho); a certa se repete entre vistas. */
    class Obs(val world: Pose, val camCenter: DoubleArray, val weight: Double, val alts: List<Pose> = listOf(world), val restored: Boolean = false, val frame: Int = -1,
              /** Molde de contorno com que a pose foi calculada (quadrado/retângulo/…): pose de outro molde não entra na média. */
              val model: String = "")

    inner class MapPiece(val id: Int, shape0: String, var color: Int, val bin: Int, val outline: Int) {
        val obs = ArrayList<Obs>()
        /** Votos de forma (peso = quão de frente a peça estava): a forma vista de lado engana. */
        val votes = HashMap<String, Double>().apply { put(shape0, 1e-3) }
        val shape get() = votes.maxBy { it.value }.key
        var pose: Pose = Pose.IDENTITY
        var size = "grande"
        /** Espalhamento (m) das posições observadas com a escala escolhida — medida de incerteza. */
        var spread = Double.NaN
        var lastSeen = 0
        /** Vistas e incerteza herdadas de um mapa salvo (sessão anterior). */
        var baseCount = 0; var baseSpread = Double.NaN
        /** Quadro-chave em que a peça apareceu pela primeira vez (rever peça antiga = fechar laço). */
        var firstKf = -1
        /** De quais lados a peça já foi vista: 12 setores de 30° em volta (bits) e maior elevação (graus). */
        var sectors = 0; var maxElev = -90.0
        /** Vistas distintas: só conta quando a câmera andou ≥ 2 cm desde a última vista contada (parado não acumula). */
        var views = 0; private var lastViewCam: DoubleArray? = null
        val count get() = baseCount + views

        fun add(o: Obs) {
            obs += o; noteView(o.camCenter)
            val lv = lastViewCam
            if (!o.restored && (!viewCounting || lv == null || dist(lv, o.camCenter) >= .01)) { views++; lastViewCam = o.camCenter.clone() }
        }

        fun noteView(cam: DoubleArray) {
            val d = DoubleArray(3) { cam[it] - pose.t[it] }; val l = sqrt(d.sumOf { it * it }); if (l < 1e-6) return
            val az = kotlin.math.atan2(d[2], d[0]); sectors = sectors or (1 shl ((((az + Math.PI) / (2 * Math.PI)) * 12).toInt().coerceIn(0, 11)))
            maxElev = max(maxElev, Math.toDegrees(kotlin.math.asin((d[1] / l).coerceIn(-1.0, 1.0))))
        }

        fun update() {
            // só observações feitas com o molde da forma atual (a pose de "quadrado" de um retângulo fica a outra distância)
            val wanted = outlineOf(shape)
            val recent = obs.takeLast(60)
            val same = if (modelFilter) recent.filter { it.model.isEmpty() || it.model == wanted } else recent
            val o = if (same.size >= 2) same else recent
            val sizes = if (o.size >= 3 && baselineDeg(o) > 8) Catalog.SIZES.filter { it != "pequeno" } else listOf(size)
            val res = sizes.map { s ->
                val sc = Catalog.SCALE.getValue(s).toDouble()
                val pts = o.map { ob -> DoubleArray(3) { j -> ob.camCenter[j] + (ob.world.t[j] - ob.camCenter[j]) * sc } }
                val w = o.sumOf { it.weight }
                val m = DoubleArray(3) { j -> o.indices.sumOf { pts[it][j] * o[it].weight } / w }
                Triple(s, o.indices.sumOf { i -> dist(pts[i], m) * o[i].weight } / w, m)
            }
            val cur = res.firstOrNull { it.first == size }; val best = res.minBy { it.second }
            val pick = if (cur != null && best.second > cur.second * .85) cur else best
            size = pick.first; spread = pick.second
            if (baseCount > 0 && !baseSpread.isNaN()) { val n = obs.count { !it.restored }; spread = (spread * n + baseSpread * baseCount) / (n + baseCount) }
            val bestP = pick.third
            // rotação: a orientação certa é a que se repete entre vistas — a espelhada (e, em quadrados, giros de 90° e cópias
            // fundidas noutra bacia) muda com o ângulo; a média de bacias diferentes dava normal torta (quadrado deitado a 68°)
            val rots: List<Pair<DoubleArray, Double>> = if (o.size >= 3) {
                val all = o.flatMap { ob -> ob.alts.map { it.r to ob } }
                val tol = Math.toRadians(12.0)
                val best = all.maxBy { (r, _) -> o.count { ob -> ob.alts.any { Rot.angle(it.r, r) < tol } } }.first
                o.mapNotNull { ob -> ob.alts.minByOrNull { Rot.angle(it.r, best) }?.takeIf { Rot.angle(it.r, best) < tol }?.let { it.r to ob.weight } }
            } else o.map { it.world.r to it.weight }
            val ref = Rot.toQuat(rots.last().first); val acc = DoubleArray(4)
            for ((r, w) in rots) { val q = Rot.toQuat(r); val sg = if (q.indices.sumOf { q[it] * ref[it] } < 0) -1.0 else 1.0; for (j in 0 until 4) acc[j] += q[j] * sg * w }
            pose = Pose(Rot.fromQuat(acc), bestP)
        }

        private fun baselineDeg(o: List<Obs>): Double {
            val dirs = o.map { ob -> val d = DoubleArray(3) { ob.world.t[it] - ob.camCenter[it] }; val l = sqrt(d.sumOf { it * it }); DoubleArray(3) { d[it] / l } }
            var mx = 0.0
            for (i in dirs.indices) for (j in i + 1 until dirs.size) mx = max(mx, acos(dirs[i].indices.sumOf { dirs[i][it] * dirs[j][it] }.coerceIn(-1.0, 1.0)))
            return Math.toDegrees(mx)
        }

        /** Saída com restrição geométrica: em pé/deitada quando a medida está perto disso (Snap.basis). */
        fun toPiece3D(): Piece3D {
            val (u, v, n) = Snap.basis(v3(pose.col(0)), v3(pose.col(1)))
            val share = (votes.maxOf { it.value } / votes.values.sum()).toFloat()
            return Piece3D(shape, size, color, V3(pose.t[0].toFloat(), pose.t[1].toFloat(), pose.t[2].toFloat()), u, v, n, 0f, 0f, count, spread, share)
        }
    }

    val map = ArrayList<MapPiece>()
    /** Câmera → mundo do último quadro rastreado. */
    var camera: Pose? = null; private set
    var lost = false; private set
    private var lostFrames = 0
    var frames = 0; private set
    var lastMatched = 0; private set
    /** Quadro-chave: pose da câmera, gravidade e cantos medidos de cada peça (para o ajuste global). */
    class Keyframe(var pose: Pose, val up: DoubleArray?, val obs: List<Pair<Int, PlanarObs>>)
    /** Cantos do molde (m, tamanho grande) ↔ pixels; [scale] = tamanho da peça / grande. */
    class PlanarObs(val model: List<DoubleArray>, val px: List<DoubleArray>, val sigma: Double)
    val keyframes = ArrayList<Keyframe>()
    /** Peça juntada em outra (duplicata): as observações antigas passam a valer para a que ficou. */
    private val alias = HashMap<Int, Int>()
    private fun resolve(id: Int): Int { var x = id; while (alias.containsKey(x)) x = alias.getValue(x); return x }
    val baReports = ArrayList<BundleAdjust.Report>()
    private var lastBaKf = 0

    /** Mapa veio de uma sessão salva e a câmera já se alinhou com ele. */
    var relocalized = false; private set
    val restoredMap get() = map.any { it.baseCount > 0 }
    private var nextId = 1

    /** Peças com contorno externo convexo conhecido servem de marcador (L e E ficam de fora por enquanto). */
    private val usable = setOf("quadrado", "retangulo", "equilatero", "retanguloTri", "isoceles", "moldura", "janela", "porta", "encaixe")

    private val QUADS = listOf("quadrado", "retangulo")
    private val TRIS = listOf("equilatero", "retanguloTri", "isoceles")

    fun detect(seg: Segmentation, px: IntArray? = null): List<Detection> {
        val out = ArrayList<Detection>()
        for (b in seg.blobs) {
            val d = detectBlob(b)
            if (d != null) { out += d; continue }
            // região grande que não fecha com molde nenhum: duas peças da mesma cor juntas? tenta dividir na
            // fileira escura (ímãs/emenda) e reconhecer as partes
            val fit = b.fit
            // só regiões que parecem duas peças (alongadas ou côncavas), sem furo de verdade; cada parte tem que ser grande
            val aspect = fit?.let { it.obb.w / max(it.obb.h, 1e-3f) }?.let { if (it < 1f) 1f / it else it } ?: 1f
            if (weakSplitting && px != null && fit != null && b.count >= 600 && fit.shape in usable && fit.gaps.none { it.enclosed } && (aspect >= 1.35f || fit.solidity < .85f))
                for (part in Segmenter.weakSplit(b, seg, px)) if (part.count >= b.count * .35f) detectBlob(part)?.let { out += it }
        }
        return out
    }

    private fun detectBlob(b: Blob): Detection? {
        run {
            val fit = b.fit ?: return null
            if (fit.shape !in usable || b.count < 120) return null
            val hull = Geo2.hull(b.mask.edgePoints())
            // contorno externo: 4 cantos (quadrado, retângulo, molduras, porta, janela) ou 3 (triângulos)
            val want = if (fit.shape in TRIS) 3 else 4
            var corners: List<P2>? = null
            for (f in doubleArrayOf(.035, .025, .05, .018, .07)) { val c = Geo2.simplifyClosed(hull, Geo2.perimeter(hull) * f.toFloat()); if (c.size == want) { corners = c; break } }
            val img = corners?.map { doubleArrayOf((it.x + b.minX).toDouble(), (it.y + b.minY).toDouble()) } ?: return null
            // cada molde de contorno possível: com a câmera calibrada, o molde errado deixa erro de reprojeção
            val outlines = if (want == 4) QUADS else TRIS
            val perModel = outlines.associateWith { solveAll(modelCorners(it), img) }
            val diag = sqrt((b.bw * b.bw + b.bh * b.bh).toDouble())
            val best = perModel.mapNotNull { (m, sols) -> sols.minByOrNull { it.rms }?.let { m to it } }.minByOrNull { it.second.rms } ?: return null
            var outlineShape = best.first; var ambiguous = false
            if (want == 4) {
                val other = perModel.filterKeys { it != best.first }.values.firstOrNull()?.minOfOrNull { it.rms } ?: Double.MAX_VALUE
                ambiguous = other - best.second.rms < .35
                // os dois moldes explicam igual (vista de longe/de viés): a proporção 2D só vale quando a peça está
                // de frente; de viés um quadrado parece retângulo, então fica quadrado e os votos entre vistas decidem
                if (other - best.second.rms < .35) {
                    val sq = perModel["quadrado"]?.minByOrNull { it.rms }
                    val frontalSq = sq?.let { r -> val ray = norm(r.pose.t); val nn = r.pose.col(2); abs(ray[0] * nn[0] + ray[1] * nn[1] + ray[2] * nn[2]) } ?: 0.0
                    // proporção 2D > 1,55 só vale como "retângulo" se a vista é razoavelmente de frente (> 50° de viés um
                    // quadrado também passa de 1,55); o retângulo de verdade visto muito de viés vota pouco (ambíguo)
                    outlineShape = if (fit.shape == "retangulo" && (!frontalRectRule || frontalSq > .65)) "retangulo" else "quadrado"
                }
            } else outlineShape = if (fit.shape in TRIS) fit.shape else "equilatero"
            val sols = perModel.getValue(outlineShape)
            val top = sols.minByOrNull { it.rms } ?: return null
            if (top.rms > max(2.5, diag * .06)) return null // região que junta duas peças não fecha com molde nenhum
            val cands = sols.filter { it.rms <= top.rms * 1.6 + .6 }.map { it.pose }
            // forma final: retifica a máscara para o plano da peça (tira a perspectiva) e classifica lá
            val shape = if (outlineShape == "quadrado") rectifiedShape(b, top.pose) ?: fit.shape.takeIf { it in SQUARE_FAMILY } ?: "quadrado" else outlineShape
            val ray = norm(top.pose.t); val nrm = top.pose.col(2)
            val frontal = abs(ray[0] * nrm[0] + ray[1] * nrm[1] + ray[2] * nrm[2])
            val byOutline = perModel.mapValues { (_, ss) -> val mn = ss.minOfOrNull { it.rms } ?: 0.0; ss.filter { it.rms <= mn * 1.6 + .6 }.map { it.pose } }
            return Detection(shape, b.color, b.hueBin, cands, top.rms, b.count, img, frontal, want, byOutline, ambiguous)
        }
    }

    private val SQUARE_FAMILY = setOf("quadrado", "moldura", "janela", "porta", "encaixe")

    private fun solveAll(model: List<DoubleArray>, img: List<DoubleArray>): List<PlanarPose.Result> {
        val sols = ArrayList<PlanarPose.Result>()
        for (dir in 0 until 2) for (sh in model.indices) {
            val ordered = List(img.size) { i -> val j = if (dir == 0) (sh + i) % img.size else (sh - i + img.size) % img.size; img[j] }
            PlanarPose.solve(model, ordered, k)?.let { r ->
                if (r.pose.t[2] > 0.03) sols += r
                // espelho só para triângulos: para 4 cantos a bacia refletida gerava fantasmas e estragava o ajuste global
                if (model.size == 3) PlanarPose.mirrored(r.pose, model, ordered, k)?.let { if (it.pose.t[2] > 0.03) sols += it }
            }
        }
        return sols
    }

    /** Amostra a máscara no plano da peça (vista de frente) e classifica: furos e proporções sem perspectiva. */
    private fun rectifiedShape(b: Blob, pose: Pose): String? {
        val e = Catalog.edgeM("grande").toDouble(); val n = 48; val half = .62 * e
        val m = Mask(n, n)
        for (y in 0 until n) for (x in 0 until n) {
            val a = -half + (x + .5) * 2 * half / n; val c = half - (y + .5) * 2 * half / n
            val q = PlanarPose.project(pose, doubleArrayOf(a, c), k)
            m[x, y] = b.mask[(q[0] - b.minX).toInt(), (q[1] - b.minY).toInt()]
        }
        val f = ShapeClassifier.classify(m) ?: return null
        return f.shape.takeIf { it in SQUARE_FAMILY }
    }

    fun modelCorners(shape: String, size: String = "grande"): List<DoubleArray> {
        val e = Catalog.edgeM(size)
        val hull = Geo2.hull(Catalog.polygon(shape).map { P2(it.first, it.second) })
        // a parte colorida termina antes da borda escura/ímãs
        val ins = Catalog.inset(hull.map { it.x to it.y }, Catalog.FRAME_M / e)
        return ins.map { (a, b) -> doubleArrayOf(a * e.toDouble(), b * e.toDouble()) }
    }

    /**
     * Processa um quadro. [upCam]: "para cima" no sistema da câmera (do sensor de gravidade), se houver.
     * Devolve as detecções usadas (para desenhar).
     */
    fun track(seg: Segmentation, upCam: DoubleArray? = null, px: IntArray? = null): List<Detection> {
        frames++
        val dets = detect(seg, px)
        if (dets.isEmpty()) { lost = camera != null; lastMatched = 0; return dets }
        val cam0 = camera
        if (map.isEmpty()) {
            // só semeia com peça nítida e razoavelmente de frente: uma peça cortada/de perfil no 1º quadro envenenava a sessão
            val good = if (seedGate) dets.filter { it.rms <= 3.0 && it.frontal >= .3 && it.areaPx >= 250 } else dets
            if (good.isEmpty()) return dets
            val c = Pose(alignUp(upCam), doubleArrayOf(0.0, 0.0, 0.0))
            camera = c; lost = false; lostFrames = 0
            val first = good.mapNotNull { d -> newPiece(d, c)?.let { mp -> mp to planarObs(d, d.candidates[0], outlineOf(d.shape)) } }
            maybeKeyframe(c, upCam, first)
            return dets
        }
        // mapa que não pegou (nada confirmado) e já está perdido há muito tempo: recomeça do zero
        if (lost) lostFrames++ else lostFrames = 0
        if (lostFrames > 30 && map.none { it.count >= MIN_VIEWS } && keyframes.size <= 3) { map.clear(); keyframes.clear(); camera = null; lost = false; lostFrames = 0; return track(seg, upCam) }
        // mapa salvo e câmera ainda desconhecida: relocalizar (só com consenso, nunca força alinhamento)
        val relocating = cam0 == null

        // Associação por consenso (também relocaliza e fecha o laço ao rever peças antigas):
        // cada par (peça vista, peça do mapa, simetria) propõe uma pose de câmera; vence a pose com
        // mais peças concordando. A gravidade medida descarta hipóteses inclinadas demais.
        data class Match(val det: Detection, val mp: MapPiece, val cand: Pose, val camEst: Pose)
        val hyps = ArrayList<Match>()
        val cosTilt = kotlin.math.cos(Math.toRadians(20.0))
        for (d in dets) for (mp in map) {
            if (mp.outline != d.outline || !sameHue(mp.color, mp.bin, d.color, d.bin)) continue
            val s = Catalog.SCALE.getValue(mp.size).toDouble()
            for (c in d.candidatesFor(mp.shape)) {
                val ce = mp.pose.compose(scaled(c, s).inverse())
                if (upCam != null && norm(ce.rotate(upCam))[1] < cosTilt) continue
                hyps += Match(d, mp, c, ce)
            }
        }
        fun agree(a: Pose, b: Pose) = dist(a.t, b.t) < .04 && Rot.angle(a.r, b.r) < Math.toRadians(10.0)
        var matches: List<Match> = emptyList(); var bestScore = Double.NEGATIVE_INFINITY
        for (h in hyps) {
            val sup = ArrayList<Match>(); val ud = HashSet<Detection>(); val um = HashSet<MapPiece>()
            for (g in hyps.filter { agree(it.camEst, h.camEst) }.sortedBy { dist(it.camEst.t, h.camEst.t) })
                if (g.det !in ud && g.mp !in um) { sup += g; ud += g.det; um += g.mp }
            val motion = if (cam0 == null) 0.0 else dist(h.camEst.t, cam0.t)
            val score = sup.size + (if (cam0 != null && motion < .15 && !lost) .5 else 0.0) - motion * .3 + sup.sumOf { it.mp.count.coerceAtMost(10) } * .01
            if (score > bestScore) { bestScore = score; matches = sup }
        }
        // uma peça só (sem confirmação) apenas se for continuação suave do quadro anterior
        val needed = if (relocating && map.size > 1) 2 else 1
        if (matches.size < needed || (matches.size == 1 && cam0 != null && (lost || dist(matches[0].camEst.t, cam0.t) > .12))) { lost = true; lastMatched = 0; return dets }
        if (relocating) relocalized = true
        val takenDet = matches.map { it.det }.toHashSet()

        // pose da câmera: média robusta das estimativas de cada peça (descarta as que discordam da mediana)
        val med = DoubleArray(3) { j -> matches.map { it.camEst.t[j] }.sorted()[matches.size / 2] }
        val inl = matches.filter { dist(it.camEst.t, med) < .05 }.ifEmpty { matches }
        val wts = inl.map { m -> m.mp.count.coerceAtMost(20) * sqrt(m.det.areaPx.toDouble()) / (1 + m.det.rms) }
        val tw = wts.sum()
        val t = DoubleArray(3) { j -> inl.indices.sumOf { inl[it].camEst.t[j] * wts[it] } / tw }
        val ref = Rot.toQuat(inl[0].camEst.r); val acc = DoubleArray(4)
        for ((i, m) in inl.withIndex()) { val q = Rot.toQuat(m.camEst.r); val sg = if (q.indices.sumOf { q[it] * ref[it] } < 0) -1.0 else 1.0; for (j in 0 until 4) acc[j] += q[j] * sg * wts[i] }
        var cam = Pose(Rot.fromQuat(acc), t)
        if (upCam != null) cam = gravityCorrect(cam, upCam)
        camera = cam; lost = false; lastMatched = inl.size

        val inlSet = inl.toSet()
        for (m in matches) if (m in inlSet) {
            val s = Catalog.SCALE.getValue(m.mp.size).toDouble()
            val cand = m.det.candidatesFor(m.mp.shape).minBy { c -> Rot.angle(cam.compose(scaled(c, s)).r, m.mp.pose.r) }
            m.mp.add(Obs(cam.compose(scaled(cand, s)), cam.t.clone(), weight(m.det), m.det.candidatesFor(m.mp.shape).map { cam.compose(scaled(it, s)) }, frame = frames, model = outlineOf(m.mp.shape))); m.mp.lastSeen = frames
            m.mp.votes.merge(m.det.shape, voteOf(m.det), Double::plus); m.mp.update()
        }
        maybeKeyframe(cam, upCam, inl.map { m ->
            val s = Catalog.SCALE.getValue(m.mp.size).toDouble()
            val cand = m.det.candidatesFor(m.mp.shape).minBy { c -> Rot.angle(cam.compose(scaled(c, s)).r, m.mp.pose.r) }
            m.mp to planarObs(m.det, cand, outlineOf(m.mp.shape))
        })
        // laço: reviu peça que apareceu muitos quadros-chave atrás → ajuste global
        val kfNow = keyframes.size - 1
        if (autoOptimize && inl.any { it.mp.firstKf in 0..(kfNow - 8) } && kfNow - lastBaKf >= 4) optimize()
        val born = ArrayList<Pair<MapPiece, PlanarObs>>()
        // as outras peças vistas: associadas ao mapa só pela posição (a orientação de triângulos é ambígua,
        // mas a posição não); acumulam observações e votam na orientação entre vistas
        for (d in dets) if (d !in takenDet) {
            val near = map.filter { it.outline == d.outline && sameHue(it.color, it.bin, d.color, d.bin) }.minByOrNull { mp ->
                val sc = Catalog.SCALE.getValue(mp.size).toDouble(); d.candidatesFor(mp.shape).minOf { c -> dist(cam.compose(scaled(c, sc)).t, mp.pose.t) }
            }
            val sc = near?.let { Catalog.SCALE.getValue(it.size).toDouble() } ?: 1.0
            val alts = near?.let { mp -> d.candidatesFor(mp.shape).map { cam.compose(scaled(it, sc)) } }
            if (near != null && alts!!.minOf { dist(it.t, near.pose.t) } < Catalog.edgeM("grande") * .6) {
                near.add(Obs(alts.minBy { Rot.angle(it.r, near.pose.r) }, cam.t.clone(), weight(d) * .5, alts, frame = frames, model = outlineOf(near.shape))); near.lastSeen = frames
                near.votes.merge(d.shape, voteOf(d), Double::plus); near.update()
            } else newPiece(d, cam)?.let { mp -> born += mp to planarObs(d, d.candidates[0], outlineOf(d.shape)) }
        }
        if (born.isNotEmpty()) { val kf = keyframes.lastOrNull(); maybeKeyframe(cam, upCam, born, force = kf == null || kf.pose !== cam) }
        mergeDuplicates()
        // detecção passageira (reflexo, peça cortada na borda): vista < 3 vezes e sumiu há 20 quadros → sai do mapa
        map.removeAll { it.count < MIN_VIEWS && it.baseCount == 0 && frames - it.lastSeen > 20 }
        return dets
    }

    private fun newPiece(d: Detection, cam: Pose): MapPiece? {
        // peça nova nascendo em cima de uma já mapeada (ex.: escala errada): não duplica
        val w = cam.compose(d.candidates[0])
        if (map.any { it.outline == d.outline && sameHue(it.color, it.bin, d.color, d.bin) && dist(it.pose.t, w.t) < (if (bornRadiusOld) Catalog.edgeM("grande") * .6 else Catalog.edgeM(it.size) * .5) }) return null
        val mp = MapPiece(nextId++, d.shape, d.color, d.bin, d.outline)
        mp.firstKf = keyframes.size
        mp.votes.merge(d.shape, voteOf(d), Double::plus)
        mp.add(Obs(w, cam.t.clone(), weight(d), d.candidates.map { cam.compose(it) }, frame = frames, model = outlineOf(d.shape))); mp.lastSeen = frames; mp.update(); map += mp
        return mp
    }

    /** Cantos do molde ↔ cantos medidos, na ordem dada pela pose escolhida (simetria coerente com o mapa). */
    private fun planarObs(d: Detection, cand: Pose, outlineShape: String): PlanarObs {
        val model = modelCorners(outlineShape)
        val px = model.map { x -> val q = PlanarPose.project(cand, x, k); d.imgCorners.minBy { (it[0] - q[0]) * (it[0] - q[0]) + (it[1] - q[1]) * (it[1] - q[1]) } }
        return PlanarObs(model, px, 1.0 + d.rms)
    }

    /** Novo quadro-chave quando a câmera andou ≥ 5 cm ou girou ≥ 10° (no máximo 40: os do meio são rareados). */
    private fun maybeKeyframe(cam: Pose, up: DoubleArray?, obs: List<Pair<MapPiece, PlanarObs>>, force: Boolean = false) {
        val last = keyframes.lastOrNull()
        // peça que ainda não está em nenhum quadro-chave força um: senão ela ficaria fora do ajuste global
        val newcomer = force || obs.any { it.first.firstKf < 0 }
        if (!newcomer && last != null && dist(last.pose.t, cam.t) < .05 && Rot.angle(last.pose.r, cam.r) < Math.toRadians(10.0)) return
        keyframes += Keyframe(cam, up?.clone(), obs.map { (mp, o) -> mp.id to o })
        for ((mp, _) in obs) if (mp.firstKf < 0) mp.firstKf = keyframes.size - 1
        if (keyframes.size > 40) { keyframes.removeAt(keyframes.size / 2); lastBaKf = (lastBaKf - 1).coerceAtLeast(0) } // os mais antigos ancoram o laço; 40 mantém o ajuste rápido no celular
    }

    /**
     * Ajuste global: câmeras dos quadros-chave + peças. As peças passam a valer a pose otimizada
     * (as observações antigas viram um resumo com o mesmo peso) e a câmera atual acompanha a correção.
     */
    fun optimize(): BundleAdjust.Report? {
        val first = optimizeOnce() ?: return null
        if (!first.accepted) return first
        // depois do ajuste, cópias da mesma peça (criadas durante a deriva) ficam juntas: junta e ajusta de novo
        return if (mergeDuplicates(afterBa = true) > 0) optimizeOnce() ?: first else first
    }

    private fun optimizeOnce(): BundleAdjust.Report? {
        if (keyframes.size < 3) return null
        // triângulos ficam de fora: com 3 cantos a correspondência pode cair na pose espelhada
        val ids = keyframes.flatMap { kf -> kf.obs.map { resolve(it.first) } }.distinct()
            .filter { id -> map.any { it.id == id && it.outline == 4 } }
        val idx = ids.withIndex().associate { (i, id) -> id to i }
        val mps = ids.map { id -> map.first { it.id == id } }
        /** Observações para o ajuste a partir das poses ATUAIS (correspondência dos cantos e corte de associação refeitos). */
        fun buildObs(cs: List<Pose>, ps: List<Pose>) = keyframes.indices.flatMap { ki -> keyframes[ki].obs.mapNotNull { (id, o) ->
            val pi = idx[resolve(id)] ?: return@mapNotNull null
            if (o.model.size != 4) return@mapNotNull null
            val sc = Catalog.SCALE.getValue(mps[pi].size).toDouble()
            val toCam = cs[ki].inverse().compose(ps[pi])
            if (toCam.t[2] < .03) return@mapNotNull null // peça atrás da câmera: associação errada
            // peças simétricas: a ordem dos cantos segue a pose atual
            val model = o.model.map { doubleArrayOf(it[0] * sc, it[1] * sc) }
            val pred = model.map { PlanarPose.project(toCam, it, k) }
            val free = o.px.toMutableList(); var sum = 0.0
            val px = pred.map { q -> val best = free.minBy { (it[0] - q[0]) * (it[0] - q[0]) + (it[1] - q[1]) * (it[1] - q[1]) }; free.remove(best)
                sum += sqrt((best[0] - q[0]) * (best[0] - q[0]) + (best[1] - q[1]) * (best[1] - q[1])); best }
            if (sum / px.size > 25) return@mapNotNull null // longe demais (por enquanto): pode entrar na próxima rodada
            BundleAdjust.Obs(ki, pi, model, px, o.sigma)
        } }
        val cams = keyframes.map { it.pose }.toMutableList(); val pcs = mps.map { it.pose }.toMutableList()
        val lastOld = cams.last()
        // reassociação iterativa: cada rodada aproxima as poses e deixa entrar observações do laço que estavam longe
        var rep0: BundleAdjust.Report? = null; var used = 0; var firstRms = Double.NaN
        for (round in 0 until 3) {
            val obs = buildObs(cams, pcs)
            if (obs.size < 6) break
            if (rep0 != null && obs.size <= used) break // nada novo entrou
            used = obs.size
            val r = BundleAdjust.run(k, cams, keyframes.map { it.up }, pcs, obs)
            if (firstRms.isNaN()) firstRms = r.rmsBefore
            rep0 = BundleAdjust.Report(r.iterations, firstRms, r.rmsAfter, r.outliers)
        }
        if (rep0 == null || mps.isEmpty()) return null
        // aceita só se a reprojeção não piorou e nada saltou de forma impossível
        val jumpP = pcs.indices.maxOf { dist(pcs[it].t, mps[it].pose.t) }
        val jumpC = cams.indices.maxOf { dist(cams[it].t, keyframes[it].pose.t) }
        val turn = cams.indices.maxOf { Rot.angle(cams[it].r, keyframes[it].pose.r) }
        val ok = rep0.rmsAfter.isFinite() && rep0.rmsAfter <= rep0.rmsBefore * 1.05 + .1 && jumpP < .25 && jumpC < .25 && turn < Math.toRadians(30.0)
        if (!ok) {
            val rej = BundleAdjust.Report(rep0.iterations, rep0.rmsBefore, rep0.rmsAfter, rep0.outliers, accepted = false,
                note = "descartado: reprojeção %.1f→%.1f px, salto %.0f cm".format(rep0.rmsBefore, rep0.rmsAfter, maxOf(jumpP, jumpC) * 100))
            lastBaKf = keyframes.size - 1; baReports += rej
            return rej
        }
        val rep = rep0
        // peças fora do ajuste (triângulos): recebem a correção do último quadro-chave que as viu
        val inBa = mps.toSet()
        // cada observação recebe a correção do quadro-chave cuja câmera estava mais perto de onde ela foi feita
        val corrs = keyframes.indices.map { cams[it].compose(keyframes[it].pose.inverse()) }
        fun corrAt(c: DoubleArray) = corrs[keyframes.indices.minBy { dist(keyframes[it].pose.t, c) }]
        for (mp in map) if (mp !in inBa) {
            if (mp.obs.isEmpty()) continue
            val moved = mp.obs.map { o -> val corr = corrAt(o.camCenter); Obs(corr.compose(o.world), corr.apply(o.camCenter), o.weight, o.alts.map { corr.compose(it) }, o.restored) }
            mp.obs.clear(); mp.obs.addAll(moved); mp.update()
        }
        for (i in keyframes.indices) keyframes[i].pose = cams[i]
        for ((i, mp) in mps.withIndex()) {
            mp.baseCount += mp.obs.count { !it.restored }; mp.obs.clear()
            mp.pose = pcs[i]
            val nrm = mp.pose.col(2)
            mp.obs += Obs(mp.pose, DoubleArray(3) { mp.pose.t[it] + nrm[it] * .35 }, maxOf(1, mp.baseCount).toDouble(), listOf(mp.pose), restored = true)
        }
        // câmera atual: mesma correção do último quadro-chave
        camera?.let { c -> camera = cams.last().compose(lastOld.inverse()).compose(c) }
        lastBaKf = keyframes.size - 1; baReports += rep
        return rep
    }

    /** Mesma peça mapeada duas vezes (associação perdida num quadro): junta as observações. */
    private fun mergeDuplicates(afterBa: Boolean = false): Int {
        val before = map.size
        var i = 0
        while (i < map.size) {
            val a = map[i]; var j = i + 1
            while (j < map.size) {
                val b = map[j]
                val na = a.pose.col(2); val nb = b.pose.col(2)
                val sameNormal = abs(na[0] * nb[0] + na[1] * nb[1] + na[2] * nb[2]) > .82
                // raio em função do maior tamanho dos dois: com um grande vale 6,4 cm (como sempre); dois médios 5,2 cm,
                // porque dois médios ENCOSTADOS distam 6,1 cm e eram fundidos numa peça só.
                // ("vistas juntas no mesmo quadro" NÃO serve para distinguir: a mesma peça rende dois blobs com frequência)
                // Raio pelo maior tamanho dos dois (dois médios encostados distam 6,1 cm): 0,7 lado quadro a quadro — cópia
                // por deriva fica separada até o ajuste global (laço) juntá-la, e depois dele a fusão é larga (0,85 lado).
                // O que sobra a mais de 0,7 lado com poucas vistas fica fora da saída (pieces()).
                val e = maxOf(Catalog.edgeM(a.size), Catalog.edgeM(b.size))
                val radius = when (mergeMode) {
                    0 -> Catalog.edgeM("grande") * .85
                    else -> if (afterBa) e * .85 else e * .7
                }
                val seenTogether = false
                if (a.outline == b.outline && sameHue(a.color, a.bin, b.color, b.bin) && sameNormal && !seenTogether && dist(a.pose.t, b.pose.t) < radius) {
                    a.obs += b.obs; a.views += b.views; a.baseCount += b.baseCount; a.sectors = a.sectors or b.sectors; a.maxElev = max(a.maxElev, b.maxElev)
                    alias[b.id] = a.id; if (b.firstKf in 0 until a.firstKf || a.firstKf < 0) a.firstKf = b.firstKf
                    b.votes.forEach { (sh, v) -> a.votes.merge(sh, v, Double::plus) }; a.update(); map.removeAt(j)
                } else j++
            }
            i++
        }
        return before - map.size
    }

    /** Peso do voto de forma: quanto mais de frente, mais vale; vista em que quadrado e retângulo empatam quase não conta. */
    private fun voteOf(d: Detection) = if (ambiguousVote && d.ambiguous && d.frontal < .8) .02 else .05 + d.frontal * d.frontal // de viés e empatado: quase não conta; de frente a proporção 2D vale

    private fun weight(d: Detection) = if (triWeight) sqrt(d.areaPx.toDouble()) / (1 + max(d.rms, 1.0)) * (if (d.outline == 3) .5 else 1.0) else sqrt(d.areaPx.toDouble()) / (1 + d.rms)

    /** Peça → câmera com a peça [s] vezes o tamanho grande: mesma imagem, distância ×s. */
    private fun scaled(p: Pose, s: Double) = if (s == 1.0) p else Pose(p.r, doubleArrayOf(p.t[0] * s, p.t[1] * s, p.t[2] * s))

    /** Rotação câmera→mundo com o "para cima" medido virando +Y do mundo. */
    private fun alignUp(upCam: DoubleArray?): DoubleArray {
        val y = norm(upCam ?: doubleArrayOf(0.0, -1.0, 0.0))
        val ref = if (abs(y[0]) < .9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 0.0, 1.0)
        val x = norm(DoubleArray(3) { ref[it] - y[it] * (ref[0] * y[0] + ref[1] * y[1] + ref[2] * y[2]) })
        val z = doubleArrayOf(x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0])
        return doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2])
    }

    /** A gravidade é medida direto: corrige inclinação/rolagem acumuladas sem mexer no rumo. */
    private fun gravityCorrect(cam: Pose, upCam: DoubleArray): Pose {
        val u = norm(cam.rotate(upCam)) // para cima medido, em coordenadas do mundo
        val target = doubleArrayOf(0.0, 1.0, 0.0)
        val ax = doubleArrayOf(u[1] * target[2] - u[2] * target[1], u[2] * target[0] - u[0] * target[2], u[0] * target[1] - u[1] * target[0])
        val s = sqrt(ax.sumOf { it * it }); if (s < 1e-9) return cam
        // limite por quadro: uma amostra ruim de gravidade não vira um giro brusco, mas o erro acumulado ainda é corrigido rápido
        val ang = acos(u[1].coerceIn(-1.0, 1.0)).let { if (softGravity) it.coerceAtMost(Math.toRadians(15.0)) else it }
        val corr = Rot.exp(DoubleArray(3) { ax[it] / s * ang })
        return Pose(Rot.mul(corr, cam.r), cam.t)
    }

    /**
     * Só entra no modelo o que foi visto em pelo menos 3 pontos de vista (uma vista só pode ser reflexo/ruído).
     * Duplicata que o mapa ainda não fundiu (mesma cor, mesmo contorno, normal parecida, a menos de 0,8 lado da
     * mais vista) fica de fora da saída — o mapa em si não muda.
     */
    fun pieces(minObs: Int = MIN_VIEWS): List<Piece3D> {
        val kept = ArrayList<MapPiece>()
        for (mp in map.filter { it.count >= minObs }.sortedByDescending { it.count }) {
            val n = mp.pose.col(2)
            val dup = kept.any { k -> k.outline == mp.outline && sameHue(k.color, k.bin, mp.color, mp.bin) &&
                abs(n[0] * k.pose.col(2)[0] + n[1] * k.pose.col(2)[1] + n[2] * k.pose.col(2)[2]) > .82 && run {
                    val d = dist(k.pose.t, mp.pose.t); val e = maxOf(Catalog.edgeM(k.size), Catalog.edgeM(mp.size))
                    // peças encostadas distam ≥ 1 lado; a menos de 0,8 lado é duplicata; até 1 lado só se foi vista 4× menos
                    d < e * .8 || d < e * 1.0 && mp.count * 4 < k.count
                } }
            if (!dup) kept += mp
        }
        return kept.map { it.toPiece3D() }
    }

    /** Mapa em texto (para salvar o projeto e retomar o escaneamento depois). Metros, mundo com Y para cima. */
    fun export(): String {
        val sb = StringBuilder("BRASCAN-MAPA 1\n")
        for (mp in map) {
            val votes = mp.votes.entries.joinToString(",") { "${it.key}:${"%.4f".format(java.util.Locale.US, it.value)}" }
            val nums = (mp.pose.r.toList() + mp.pose.t.toList()).joinToString(" ") { "%.6f".format(java.util.Locale.US, it) }
            sb.append("${mp.id}|$votes|${mp.color}|${mp.bin}|${mp.outline}|${mp.size}|${if (mp.spread.isNaN()) "nan" else "%.5f".format(java.util.Locale.US, mp.spread)}|${mp.count}|${mp.sectors}|${"%.1f".format(java.util.Locale.US, mp.maxElev)}|$nums\n")
        }
        return sb.toString()
    }

    /**
     * Retoma um mapa salvo. A câmera começa desconhecida: o próximo quadro com ≥ 2 peças conhecidas
     * concordando alinha a sessão nova com a antiga (sem consenso, nada é adicionado).
     */
    fun restore(text: String): Int {
        val lines = text.lines(); if (lines.firstOrNull()?.startsWith("BRASCAN-MAPA") != true) return 0
        map.clear(); camera = null; relocalized = false
        for (ln in lines.drop(1)) {
            val f = ln.split("|"); if (f.size < 11) continue
            val votes = f[1].split(",").filter { ":" in it }.associate { it.substringBefore(":") to it.substringAfter(":").toDouble() }
            val mp = MapPiece(f[0].toInt(), votes.maxByOrNull { it.value }?.key ?: "quadrado", f[2].toInt(), f[3].toInt(), f[4].toInt())
            mp.votes.clear(); mp.votes.putAll(votes)
            mp.size = f[5]; val spr = f[6].toDoubleOrNull() ?: Double.NaN
            val n = f[10].trim().split(" ").map { it.toDouble() }
            mp.pose = Pose(DoubleArray(9) { n[it] }, doubleArrayOf(n[9], n[10], n[11]))
            mp.baseCount = f[7].toInt(); mp.baseSpread = spr; mp.sectors = f[8].toInt(); mp.maxElev = f[9].toDouble()
            // uma observação "resumo" com o peso das vistas antigas (câmera fictícia na frente da peça)
            val nrm = mp.pose.col(2)
            mp.obs += Obs(mp.pose, DoubleArray(3) { mp.pose.t[it] + nrm[it] * .35 }, mp.baseCount.toDouble(), listOf(mp.pose), restored = true)
            mp.spread = spr
            map += mp; nextId = max(nextId, mp.id + 1)
        }
        lost = true
        return map.size
    }

    data class Advice(val text: String, val targetId: Int?, val priority: Double)
    private val adviceSeen = HashMap<String, Pair<Int, Int>>() // dica → (quadro em que começou, contagem naquele momento)

    /**
     * Escaneamento ativo: o que observar agora para ganhar mais informação. Prioridade: rastreio >
     * peça ainda fora do modelo > forma/tamanho/posição em dúvida > lado nunca visto > vista de cima.
     * Uma dica que não melhorou em ~40 quadros sai da frente por um tempo (não pede o impossível).
     */
    fun advice(limit: Int = 3): List<Advice> {
        val out = ArrayList<Advice>()
        if (map.isEmpty()) return listOf(Advice("Aponte para as peças coloridas", null, 100.0))
        if (camera == null) return listOf(Advice("Procurando a construção salva: mostre 2 peças que já foram escaneadas", null, 100.0))
        if (lost) out += Advice("Perdi a posição: volte a mostrar peças já vistas", null, 90.0)
        val solid = map.filter { it.count >= MIN_VIEWS }
        val confirming = ArrayList<MapPiece>()
        for (mp in map) {
            if (mp.count < MIN_VIEWS && frames - mp.lastSeen > 2) continue // só pede para segurar no que está na tela agora
            val name = "${(Catalog.LABEL[mp.shape] ?: mp.shape).lowercase()} ${colorName(mp.bin)}"
            val share = mp.votes.maxOf { it.value } / mp.votes.values.sum()
            when {
                mp.count < MIN_VIEWS -> confirming += mp
                share < .7 -> out += Advice("Chegue mais perto do $name e olhe de frente: forma em dúvida", mp.id, 40.0)
                mp.count >= MIN_VIEWS && mp.obs.count { !it.restored } >= 3 && viewSpreadDeg(mp) < 8 -> out += Advice("Mova o celular de lado mostrando o $name: preciso de outro ângulo para medir o tamanho", mp.id, 35.0)
                !mp.spread.isNaN() && mp.spread > .015 -> out += Advice("Mostre o $name de outro ângulo: posição incerta (±${"%.1f".format(mp.spread * 100)} cm)", mp.id, 30.0 + mp.spread * 100)
            }
        }
        if (confirming.size == 1) { val mp = confirming[0]; out += Advice("Mova o celular devagar em volta do ${(Catalog.LABEL[mp.shape] ?: mp.shape).lowercase()} ${colorName(mp.bin)}, sem tirar da tela", mp.id, 50.0) }
        else if (confirming.size > 1) out += Advice("Mova o celular devagar, sem tirar da tela: ${confirming.size} peças sendo confirmadas", confirming[0].id, 50.0)
        // cobertura em volta da construção
        val seen = solid.fold(0) { a, m -> a or m.sectors }; val nSeen = Integer.bitCount(seen)
        if (solid.isNotEmpty() && nSeen < 9) {
            val c = DoubleArray(3) { j -> solid.sumOf { it.pose.t[j] } / solid.size }
            // maior sequência de setores nunca vistos → direção do centro dela
            var bestStart = 0; var bestLen = 0
            for (st in 0 until 12) { var l = 0; while (l < 12 && (seen shr ((st + l) % 12)) and 1 == 0) l++; if (l > bestLen) { bestLen = l; bestStart = st } }
            if (bestLen > 0) {
                val az = (bestStart + bestLen / 2.0 + .5) / 12 * 2 * Math.PI - Math.PI
                val cam = camera!!; val fwd = norm(doubleArrayOf(c[0] - cam.t[0], 0.0, c[2] - cam.t[2]))
                val right = doubleArrayOf(-fwd[2], 0.0, fwd[0]) // frente × cima
                val r = max(.25, dist(c, cam.t))
                val target = doubleArrayOf(c[0] + kotlin.math.cos(az) * r, cam.t[1], c[2] + kotlin.math.sin(az) * r)
                val side = (target[0] - cam.t[0]) * right[0] + (target[2] - cam.t[2]) * right[2]
                val behind = (target[0] - c[0]) * fwd[0] + (target[2] - c[2]) * fwd[2] > r * .5
                out += Advice(if (behind) "Falta ver o lado de trás: dê a volta na construção" else "Falta ver um lado: ande para a ${if (side > 0) "direita" else "esquerda"} em volta da construção", null, 20.0 + bestLen)
            }
        }
        if (solid.isNotEmpty() && solid.maxOf { it.maxElev } < 55) out += Advice("Escaneie também de cima, apontando para baixo", null, 15.0) // 55°: celular bem acima, olhando para baixo

        // não insistir no que não está melhorando
        val result = out.filter { a ->
            val metric = a.targetId?.let { id -> map.firstOrNull { it.id == id }?.count } ?: nSeen
            val st = adviceSeen.getOrPut(a.text) { frames to metric }
            when {
                metric != st.second -> { adviceSeen[a.text] = frames to metric; true }
                frames - st.first > 140 -> { adviceSeen[a.text] = frames to metric; true }
                else -> frames - st.first <= 40
            }
        }
        return result.sortedByDescending { it.priority }.distinctBy { it.text }.take(limit)
    }

    private fun viewSpreadDeg(mp: MapPiece): Double {
        val o = mp.obs.filter { !it.restored }
        val dirs = o.map { ob -> norm(DoubleArray(3) { ob.world.t[it] - ob.camCenter[it] }) }
        var mx = 0.0
        for (i in dirs.indices) for (j in i + 1 until dirs.size) mx = max(mx, acos(dirs[i].indices.sumOf { dirs[i][it] * dirs[j][it] }.coerceIn(-1.0, 1.0)))
        return Math.toDegrees(mx)
    }

    /** Centro da peça do mapa projetado na imagem atual (pixels do sensor), ou null se atrás/fora. */
    fun projectToImage(id: Int): DoubleArray? {
        val cam = camera ?: return null; val mp = map.firstOrNull { it.id == id } ?: return null
        val c = cam.inverse().apply(mp.pose.t); if (c[2] < .02) return null
        return doubleArrayOf(k.fx * c[0] / c[2] + k.cx, k.fy * c[1] / c[2] + k.cy)
    }

    companion object {
        /** Pontos de vista distintos (≥ 1 cm entre si) para uma peça entrar no modelo: 3 deixava passar cópias por deriva. */
        const val MIN_VIEWS = 3
        @JvmField var weakSplitting = true
        @JvmField var ambiguousVote = true
        /** Empate quadrado/retângulo decidido pela proporção 2D (como antes); o voto baixo das vistas de viés e o filtro por molde no update() já tratam a parede vista de lado. */
        @JvmField var frontalRectRule = false
        @JvmField var viewCounting = true
        @JvmField var seedGate = true
        @JvmField var modelFilter = true
        /** Raio de fusão no mapa = 0,85 × maior lado dos dois (dois médios encostados distam 6,1 cm e não se fundem). */
        @JvmField var mergeMode = 1
        @JvmField var hueTol = 22f
        @JvmField var bornRadiusOld = false
        @JvmField var binExact = false
        @JvmField var softGravity = true // correção de inclinação limitada a 15° por quadro: uma amostra ruim não vira giro brusco
        @JvmField var triWeight = true
        private val COLOR_NAMES = listOf("vermelho", "laranja", "amarelo", "amarelo", "verde", "verde", "verde", "azul-claro", "azul", "azul", "roxo", "rosa")
        fun colorName(bin: Int) = COLOR_NAMES[bin.coerceIn(0, 11)]
        /**
         * Mesma cor: matiz a ≤ 22° (circular) E caixa de matiz igual ou vizinha. Só a caixa fixa de 30° trocava a
         * identidade da peça na borda da caixa; só o matiz ignoraria a caixa gravada num mapa salvo.
         */
        fun sameHue(colorA: Int, binA: Int, colorB: Int, binB: Int): Boolean {
            val db = abs(binA - binB); if (minOf(db, 12 - db) > (if (binExact) 0 else 1)) return false
            val a = FloatArray(3).also { Segmenter.hsv(colorA, it) }; val b = FloatArray(3).also { Segmenter.hsv(colorB, it) }
            val d = abs(a[0] - b[0]); return minOf(d, 360f - d) <= hueTol
        }
        /** Molde de contorno usado no PnP para cada forma. */
        fun outlineOf(shape: String) = when (shape) { "retangulo" -> "retangulo"; "equilatero", "retanguloTri", "isoceles" -> shape; else -> "quadrado" }
        fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
        fun norm(v: DoubleArray): DoubleArray { val l = sqrt(v.sumOf { it * it }); return DoubleArray(3) { v[it] / l } }
        fun v3(d: DoubleArray) = V3(d[0].toFloat(), d[1].toFloat(), d[2].toFloat())
    }
}
