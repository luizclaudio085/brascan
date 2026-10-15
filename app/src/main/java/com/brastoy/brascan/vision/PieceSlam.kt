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
class PieceSlam(private val k: Intrinsics) {
    /** Pose de uma detecção: candidatos peça→câmera (simetrias da peça dão vários com o mesmo erro). */
    /** [frontal]: 1 = vista de frente, 0 = de lado (peso do voto da forma). [outline]: 4 ou 3 cantos. */
    class Detection(val shape: String, val color: Int, val bin: Int, val candidates: List<Pose>, val rms: Double, val areaPx: Int,
                    val imgCorners: List<DoubleArray>, val frontal: Double, val outline: Int,
                    /** Poses para cada molde de contorno (quadrado/retângulo…): usado quando o mapa já sabe a forma. */
                    val byOutline: Map<String, List<Pose>> = emptyMap()) {
        fun candidatesFor(shape: String): List<Pose> = byOutline[outlineOf(shape)]?.takeIf { it.isNotEmpty() } ?: candidates
    }

    /** [alts]: todas as poses possíveis desta observação (simetrias, espelho); a certa se repete entre vistas. */
    class Obs(val world: Pose, val camCenter: DoubleArray, val weight: Double, val alts: List<Pose> = listOf(world))

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

        fun update() {
            val o = obs.takeLast(60)
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
            val bestP = pick.third
            // rotação: em triângulos, a orientação certa é a que se repete entre vistas (a espelhada muda com o ângulo)
            val rots: List<Pair<DoubleArray, Double>> = if (outline == 3 && o.size >= 3) {
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
            return Piece3D(shape, size, color, V3(pose.t[0].toFloat(), pose.t[1].toFloat(), pose.t[2].toFloat()), u, v, n, 0f, 0f, obs.size, spread, share)
        }
    }

    val map = ArrayList<MapPiece>()
    /** Câmera → mundo do último quadro rastreado. */
    var camera: Pose? = null; private set
    var lost = false; private set
    var frames = 0; private set
    var lastMatched = 0; private set
    private var nextId = 1

    /** Peças com contorno externo convexo conhecido servem de marcador (L e E ficam de fora por enquanto). */
    private val usable = setOf("quadrado", "retangulo", "equilatero", "retanguloTri", "isoceles", "moldura", "janela", "porta", "encaixe")

    private val QUADS = listOf("quadrado", "retangulo")
    private val TRIS = listOf("equilatero", "retanguloTri", "isoceles")

    fun detect(seg: Segmentation): List<Detection> {
        val out = ArrayList<Detection>()
        for (b in seg.blobs) {
            val fit = b.fit ?: continue
            if (fit.shape !in usable || b.count < 120) continue
            val hull = Geo2.hull(b.mask.edgePoints())
            // contorno externo: 4 cantos (quadrado, retângulo, molduras, porta, janela) ou 3 (triângulos)
            val want = if (fit.shape in TRIS) 3 else 4
            var corners: List<P2>? = null
            for (f in doubleArrayOf(.035, .025, .05, .018, .07)) { val c = Geo2.simplifyClosed(hull, Geo2.perimeter(hull) * f.toFloat()); if (c.size == want) { corners = c; break } }
            val img = corners?.map { doubleArrayOf((it.x + b.minX).toDouble(), (it.y + b.minY).toDouble()) } ?: continue
            // cada molde de contorno possível: com a câmera calibrada, o molde errado deixa erro de reprojeção
            val outlines = if (want == 4) QUADS else TRIS
            val perModel = outlines.associateWith { solveAll(modelCorners(it), img) }
            val diag = sqrt((b.bw * b.bw + b.bh * b.bh).toDouble())
            val best = perModel.mapNotNull { (m, sols) -> sols.minByOrNull { it.rms }?.let { m to it } }.minByOrNull { it.second.rms } ?: continue
            var outlineShape = best.first
            if (want == 4) {
                val other = perModel.filterKeys { it != best.first }.values.firstOrNull()?.minOfOrNull { it.rms } ?: Double.MAX_VALUE
                // diferença pequena = vista quase de frente/longe: aí a proporção na imagem é confiável
                if (other - best.second.rms < .35) outlineShape = if (fit.shape == "retangulo") "retangulo" else "quadrado"
            } else outlineShape = if (fit.shape in TRIS) fit.shape else "equilatero"
            val sols = perModel.getValue(outlineShape)
            val top = sols.minByOrNull { it.rms } ?: continue
            if (top.rms > max(2.5, diag * .06)) continue
            val cands = sols.filter { it.rms <= top.rms * 1.6 + .6 }.map { it.pose }
            // forma final: retifica a máscara para o plano da peça (tira a perspectiva) e classifica lá
            val shape = if (outlineShape == "quadrado") rectifiedShape(b, top.pose) ?: fit.shape.takeIf { it in SQUARE_FAMILY } ?: "quadrado" else outlineShape
            val ray = norm(top.pose.t); val nrm = top.pose.col(2)
            val frontal = abs(ray[0] * nrm[0] + ray[1] * nrm[1] + ray[2] * nrm[2])
            val byOutline = perModel.mapValues { (_, ss) -> val mn = ss.minOfOrNull { it.rms } ?: 0.0; ss.filter { it.rms <= mn * 1.6 + .6 }.map { it.pose } }
            out += Detection(shape, b.color, b.hueBin, cands, top.rms, b.count, img, frontal, want, byOutline)
        }
        return out
    }

    private val SQUARE_FAMILY = setOf("quadrado", "moldura", "janela", "porta", "encaixe")

    private fun solveAll(model: List<DoubleArray>, img: List<DoubleArray>): List<PlanarPose.Result> {
        val sols = ArrayList<PlanarPose.Result>()
        for (dir in 0 until 2) for (sh in model.indices) {
            val ordered = List(img.size) { i -> val j = if (dir == 0) (sh + i) % img.size else (sh - i + img.size) % img.size; img[j] }
            PlanarPose.solve(model, ordered, k)?.let { r ->
                if (r.pose.t[2] > 0.03) sols += r
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
    fun track(seg: Segmentation, upCam: DoubleArray? = null): List<Detection> {
        frames++
        val dets = detect(seg)
        if (dets.isEmpty()) { lost = camera != null; lastMatched = 0; return dets }
        val cam0 = camera
        if (cam0 == null || map.isEmpty()) {
            val c = Pose(alignUp(upCam), doubleArrayOf(0.0, 0.0, 0.0))
            camera = c; lost = false
            for (d in dets) newPiece(d, c)
            return dets
        }

        // Associação por consenso (também relocaliza e fecha o laço ao rever peças antigas):
        // cada par (peça vista, peça do mapa, simetria) propõe uma pose de câmera; vence a pose com
        // mais peças concordando. A gravidade medida descarta hipóteses inclinadas demais.
        data class Match(val det: Detection, val mp: MapPiece, val cand: Pose, val camEst: Pose)
        val hyps = ArrayList<Match>()
        val cosTilt = kotlin.math.cos(Math.toRadians(20.0))
        for (d in dets) for (mp in map) {
            if (mp.outline != d.outline || mp.bin != d.bin) continue
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
            val motion = dist(h.camEst.t, cam0.t)
            val score = sup.size + (if (motion < .15 && !lost) .5 else 0.0) - motion * .3 + sup.sumOf { it.mp.obs.size.coerceAtMost(10) } * .01
            if (score > bestScore) { bestScore = score; matches = sup }
        }
        // uma peça só (sem confirmação) apenas se for continuação suave do quadro anterior
        if (matches.isEmpty() || (matches.size == 1 && (lost || dist(matches[0].camEst.t, cam0.t) > .12))) { lost = true; lastMatched = 0; return dets }
        val takenDet = matches.map { it.det }.toHashSet()

        // pose da câmera: média robusta das estimativas de cada peça (descarta as que discordam da mediana)
        val med = DoubleArray(3) { j -> matches.map { it.camEst.t[j] }.sorted()[matches.size / 2] }
        val inl = matches.filter { dist(it.camEst.t, med) < .05 }.ifEmpty { matches }
        val wts = inl.map { m -> m.mp.obs.size.coerceAtMost(20) * sqrt(m.det.areaPx.toDouble()) / (1 + m.det.rms) }
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
            m.mp.obs += Obs(cam.compose(scaled(cand, s)), cam.t.clone(), weight(m.det), m.det.candidatesFor(m.mp.shape).map { cam.compose(scaled(it, s)) }); m.mp.lastSeen = frames
            m.mp.votes.merge(m.det.shape, .05 + m.det.frontal * m.det.frontal, Double::plus); m.mp.update()
        }
        // as outras peças vistas: associadas ao mapa só pela posição (a orientação de triângulos é ambígua,
        // mas a posição não); acumulam observações e votam na orientação entre vistas
        for (d in dets) if (d !in takenDet) {
            val near = map.filter { it.outline == d.outline && it.bin == d.bin }.minByOrNull { mp ->
                val sc = Catalog.SCALE.getValue(mp.size).toDouble(); d.candidatesFor(mp.shape).minOf { c -> dist(cam.compose(scaled(c, sc)).t, mp.pose.t) }
            }
            val sc = near?.let { Catalog.SCALE.getValue(it.size).toDouble() } ?: 1.0
            val alts = near?.let { mp -> d.candidatesFor(mp.shape).map { cam.compose(scaled(it, sc)) } }
            if (near != null && alts!!.minOf { dist(it.t, near.pose.t) } < Catalog.edgeM("grande") * .6) {
                near.obs += Obs(alts.minBy { Rot.angle(it.r, near.pose.r) }, cam.t.clone(), weight(d) * .5, alts); near.lastSeen = frames
                near.votes.merge(d.shape, .05 + d.frontal * d.frontal, Double::plus); near.update()
            } else newPiece(d, cam)
        }
        mergeDuplicates()
        return dets
    }

    private fun newPiece(d: Detection, cam: Pose) {
        // peça nova nascendo em cima de uma já mapeada (ex.: escala errada): não duplica
        val w = cam.compose(d.candidates[0])
        if (map.any { it.outline == d.outline && it.bin == d.bin && dist(it.pose.t, w.t) < Catalog.edgeM("grande") * .6 }) return
        val mp = MapPiece(nextId++, d.shape, d.color, d.bin, d.outline)
        mp.votes.merge(d.shape, .05 + d.frontal * d.frontal, Double::plus)
        mp.obs += Obs(w, cam.t.clone(), weight(d), d.candidates.map { cam.compose(it) }); mp.lastSeen = frames; mp.update(); map += mp
    }

    /** Mesma peça mapeada duas vezes (associação perdida num quadro): junta as observações. */
    private fun mergeDuplicates() {
        var i = 0
        while (i < map.size) {
            val a = map[i]; var j = i + 1
            while (j < map.size) {
                val b = map[j]
                val na = a.pose.col(2); val nb = b.pose.col(2)
                val sameNormal = abs(na[0] * nb[0] + na[1] * nb[1] + na[2] * nb[2]) > .82
                if (a.outline == b.outline && a.bin == b.bin && sameNormal && dist(a.pose.t, b.pose.t) < Catalog.edgeM("grande") * .85) {
                    a.obs += b.obs; b.votes.forEach { (sh, v) -> a.votes.merge(sh, v, Double::plus) }; a.update(); map.removeAt(j)
                } else j++
            }
            i++
        }
    }

    private fun weight(d: Detection) = sqrt(d.areaPx.toDouble()) / (1 + d.rms)

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
        val ang = acos(u[1].coerceIn(-1.0, 1.0))
        val corr = Rot.exp(DoubleArray(3) { ax[it] / s * ang })
        return Pose(Rot.mul(corr, cam.r), cam.t)
    }

    /** Só entra no modelo o que foi visto em pelo menos 3 quadros (uma vista só pode ser reflexo/ruído). */
    fun pieces(minObs: Int = 3): List<Piece3D> = map.filter { it.obs.size >= minObs }.map { it.toPiece3D() }

    companion object {
        /** Molde de contorno usado no PnP para cada forma. */
        fun outlineOf(shape: String) = when (shape) { "retangulo" -> "retangulo"; "equilatero", "retanguloTri", "isoceles" -> shape; else -> "quadrado" }
        fun dist(a: DoubleArray, b: DoubleArray) = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
        fun norm(v: DoubleArray): DoubleArray { val l = sqrt(v.sumOf { it * it }); return DoubleArray(3) { v[it] / l } }
        fun v3(d: DoubleArray) = V3(d[0].toFloat(), d[1].toFloat(), d[2].toFloat())
    }
}
