package com.brastoy.brascan.ar

import com.brastoy.brascan.vision.Catalog
import com.brastoy.brascan.vision.Intrinsics
import com.brastoy.brascan.vision.Piece3D
import com.brastoy.brascan.vision.PieceSlam
import com.brastoy.brascan.vision.Pose
import com.brastoy.brascan.vision.Rot
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * O que vira holograma na tela da câmera. Uma detecção de UM quadro pode ser reflexo, mão, sombra, brilho ou
 * duas peças fundidas; peça de verdade é rígida e fica parada, então volta a aparecer no mesmo lugar quadro
 * após quadro. Holograma ao vivo (na pose da própria detecção: cola na peça de verdade, não depende do mapa)
 * só com evidência:
 *  - [Why.CONFIRMADA]: a detecção alimentou uma peça do mapa já vista de [PieceSlam.MIN_VIEWS] pontos de vista;
 *  - [Why.SEGUIDA]: caiu na mesma peça do mapa (mesmo lugar do MUNDO) em 2 quadros com no máximo
 *    [Policy.trackGap] quadros sem ela no meio;
 *  - [Why.REPETIDA]: mesma peça na IMAGEM (contorno, cor, lugar e tamanho) em 2 quadros, mesmo intervalo —
 *    vale também com o SLAM perdido;
 *  - [Why.FORTE] (desligado por padrão, [Policy.strong]): 4 cantos que só fecham com o molde do catálogo, grande,
 *    de frente e em pé/deitada pela gravidade. Triângulo nunca é "forte": 3 cantos sempre fecham com algum
 *    triângulo (P3P exato), o erro de reprojeção não prova nada.
 * Holograma que estava na tela e cuja detecção falhou neste quadro fica mais [Policy.hold] quadro(s) onde estava
 * (em relação à câmera), se a câmera quase não andou e nada apareceu na frente: o detector perde a peça num quadro
 * e acha no seguinte, e ela não pisca.
 * Peça do mapa NÃO detectada agora não é desenhada na pose do mapa por padrão ([Policy.mapRecent] < 0): medido na
 * cena realista (HologramProbe), metade ou mais delas cai fora da peça (cópia deixada pela deriva, peça escondida
 * atrás de outra, câmera do SLAM pulando entre quadros) — era a maior fonte de "peça que não existe".
 * Cada peça aparece uma vez só.
 */
object Holograms {
    /** Detecção do quadro reduzida ao que a escolha usa (pixels da imagem analisada). */
    class Det(
        val outline: Int, val color: Int, val bin: Int,
        /** Centro e raio aparente (px: do centro ao canto mais longe). */
        val cx: Double, val cy: Double, val r: Double,
        val areaPx: Int = 0, val rms: Double = 0.0, val frontal: Double = 1.0, val ambiguous: Boolean = false,
        /** Desvio (graus) da normal da peça até a vertical/horizontal mais próxima pela gravidade; NaN sem sensor. */
        val tiltDeg: Double = Double.NaN,
        /** Peça do mapa que esta detecção atualizou ou criou neste quadro (null: nenhuma). */
        val mapId: Int? = null,
        /** Vistas dessa peça do mapa (0 sem peça). */
        val mapCount: Int = 0,
    )

    /** Peça confirmada do mapa candidata a holograma na pose do mapa, já projetada na imagem atual. */
    class MapItem(val id: Int, val outline: Int, val color: Int, val bin: Int, val cx: Double, val cy: Double, val r: Double)

    /**
     * Peça vista na imagem em quadros recentes: onde, último quadro, em quantos quadros apareceu, último quadro em
     * que teve holograma e o holograma (coordenadas da câmera) para segurar na tela se a detecção falhar.
     */
    data class Track(val outline: Int, val color: Int, val bin: Int, val cx: Double, val cy: Double, val r: Double, val frame: Int, val hits: Int,
                     val shownAt: Int = -1000, val piece: Piece3D? = null)

    /** Memória entre quadros (imutável: [choose] devolve a nova). */
    class Memory(
        val frame: Int = 0,
        /** Peça do mapa → último quadro em que recebeu detecção, e há quantos quadros seguidos. */
        val lastAssoc: Map<Int, Int> = emptyMap(),
        val streak: Map<Int, Int> = emptyMap(),
        val tracks: List<Track> = emptyList(),
        /** Último quadro em que a câmera do SLAM encaixou no mapa, e a pose dela nesse quadro. */
        val lastFresh: Int = -1000,
        val lastCam: Pose? = null,
    )

    enum class Why { CONFIRMADA, SEGUIDA, REPETIDA, FORTE }

    class Policy(
        /** Quadros sem a peça que a trilha (na imagem e no mapa) tolera: o detector perde peça de verdade em ~40% dos quadros. */
        val trackGap: Int = 2,
        /** Quadros que um holograma fica onde estava quando a detecção falha (a ~15 quadros/s, 1 = 70 ms). */
        val hold: Int = 1,
        /** Só segura se a câmera andou menos que isto desde o quadro anterior (metade do lado da peça; 10°): senão ele descola da peça. */
        val holdMaxMoveM: Double = Catalog.SIDE_M / 2.0, val holdMaxTurnDeg: Double = 10.0,
        /** Peça confirmada não detectada agora: desenhada na pose do mapa até este nº de quadros depois da última detecção (< 0: nunca). */
        val mapRecent: Int = -1,
        /** Câmera do SLAM que não encaixa há mais que isto (quadros) não desenha peça do mapa: o celular já andou. */
        val maxStale: Int = 1,
        val strong: Boolean = false,
        // "forte" (imagem analisada de 480 px): lado ≥ ~40 px (ímãs conferidos a partir de 30 px), molde do catálogo
        // encaixado a ≤ 1,2 px sem empate quadrado/retângulo, ≤ 60° de viés, apoiada pela gravidade a ≤ 12°
        val strongArea: Int = 1600, val strongRms: Double = 1.2, val strongFrontal: Double = .5, val strongTilt: Double = 12.0,
    )

    /**
     * [live]: índice da detecção → motivo; [held]: hologramas do quadro anterior segurados na tela;
     * [mapIds]: peças do mapa desenhadas na pose do mapa; [trackOf]: detecção → índice da sua trilha em [Memory.tracks].
     */
    class Choice(val live: List<Pair<Int, Why>>, val held: List<Track>, val mapIds: List<Int>, val memory: Memory, val trackOf: IntArray)

    fun strong(d: Det, p: Policy) = d.outline == 4 && !d.ambiguous && d.areaPx >= p.strongArea && d.rms <= p.strongRms &&
        d.frontal >= p.strongFrontal && (d.tiltDeg.isNaN() || d.tiltDeg <= p.strongTilt)

    /** Mesma peça na imagem: mesmo contorno e cor, centro perto e tamanho parecido (peça rígida, celular andando devagar). */
    fun sameSpot(outline: Int, color: Int, bin: Int, cx: Double, cy: Double, r: Double, o: Int, oc: Int, ob: Int, ox: Double, oy: Double, or: Double): Boolean {
        if (outline != o || !PieceSlam.sameHue(color, bin, oc, ob)) return false
        val ratio = r / max(or, 1e-6)
        return ratio in .6..1.67 && hypot(cx - ox, cy - oy) < max(r, or) * .6
    }

    /** Dois hologramas no mesmo lugar da tela (qualquer cor): um cobre o outro. */
    private fun overlaps(ax: Double, ay: Double, ar: Double, bx: Double, by: Double, br: Double) = hypot(ax - bx, ay - by) < max(ar, br) * .8

    /**
     * Escolha pura: detecções do quadro (com a peça do mapa que cada uma alimentou), peças confirmadas do mapa
     * projetadas na imagem, se a câmera do SLAM encaixou neste quadro ([fresh]) e se está perdida, e quanto a câmera
     * andou desde o quadro anterior ([moved] = metros, graus; null = não se sabe). [cam]: pose da câmera deste quadro
     * (se encaixou), guardada para medir o movimento no próximo.
     */
    fun choose(dets: List<Det>, map: List<MapItem>, fresh: Boolean, lost: Boolean, mem: Memory, p: Policy = Policy(), moved: DoubleArray? = null, cam: Pose? = null): Choice {
        val f = mem.frame + 1
        // quadros seguidos em que cada peça do mapa recebeu detecção (mesmo lugar do mundo)
        val lastAssoc = HashMap<Int, Int>(); val streak = HashMap<Int, Int>()
        for ((id, fr) in mem.lastAssoc) if (f - fr <= 60) { lastAssoc[id] = fr; mem.streak[id]?.let { streak[id] = it } }
        for (id in dets.mapNotNull { it.mapId }.distinct()) {
            val last = mem.lastAssoc[id]
            streak[id] = if (last != null && f - last <= p.trackGap + 1) (mem.streak[id] ?: 1) + 1 else 1
            lastAssoc[id] = f
        }
        // trilhas na imagem: cada detecção (maiores primeiro) continua a trilha recente mais perto que bate com ela
        val old = mem.tracks.filter { f - it.frame <= p.trackGap + 1 }.toMutableList()
        val prev = arrayOfNulls<Track>(dets.size)
        for (i in dets.indices.sortedByDescending { dets[it].areaPx }) {
            val d = dets[i]
            prev[i] = old.filter { sameSpot(d.outline, d.color, d.bin, d.cx, d.cy, d.r, it.outline, it.color, it.bin, it.cx, it.cy, it.r) }
                .minByOrNull { hypot(d.cx - it.cx, d.cy - it.cy) }?.also { old.remove(it) }
        }
        val hits = IntArray(dets.size) { (prev[it]?.hits ?: 0) + 1 }
        val picked = ArrayList<Pair<Int, Why>>()
        for ((i, d) in dets.withIndex()) {
            val why = when {
                d.mapId != null && d.mapCount >= PieceSlam.MIN_VIEWS -> Why.CONFIRMADA
                d.mapId != null && (streak[d.mapId] ?: 0) >= 2 -> Why.SEGUIDA
                hits[i] >= 2 -> Why.REPETIDA
                p.strong && strong(d, p) -> Why.FORTE
                else -> null
            }
            if (why != null) picked += i to why
        }
        // uma peça, um holograma: a mesma peça do mapa ou o mesmo lugar da imagem duas vezes fica com a mais forte
        val live = ArrayList<Pair<Int, Why>>()
        for (q in picked.sortedWith(compareBy<Pair<Int, Why>> { it.second.ordinal }.thenByDescending { dets[it.first].areaPx })) {
            val d = dets[q.first]
            val dup = live.any { (j, _) -> val o = dets[j]
                (d.mapId != null && d.mapId == o.mapId) || (d.outline == o.outline && hypot(d.cx - o.cx, d.cy - o.cy) < min(d.r, o.r) * .7) }
            if (!dup) live += q
        }
        live.sortBy { it.first }
        val liveSet = live.map { it.first }.toSet()
        fun coveredByLive(x: Double, y: Double, r: Double) = live.any { (j, _) -> val d = dets[j]; overlaps(d.cx, d.cy, d.r, x, y, r) }
        // detecção falhou neste quadro: o holograma do quadro anterior fica onde estava, se a câmera quase não andou e
        // nada novo apareceu ali (outra coisa na frente = a peça está escondida)
        val still = moved == null || (moved[0] < p.holdMaxMoveM && moved[1] < p.holdMaxTurnDeg)
        val held = if (!still) emptyList() else old.filter { t -> t.piece != null && f - t.shownAt <= p.hold &&
            dets.none { d -> overlaps(d.cx, d.cy, d.r, t.cx, t.cy, t.r) } }
        val tracks = ArrayList<Track>()
        val trackOf = IntArray(dets.size)
        for ((i, d) in dets.withIndex()) {
            trackOf[i] = tracks.size
            tracks += Track(d.outline, d.color, d.bin, d.cx, d.cy, d.r, f, hits[i], if (i in liveSet) f else prev[i]?.shownAt ?: -1000, null)
        }
        tracks += old // não vistas agora, ainda dentro do intervalo tolerado (as seguradas mantêm o quadro em que apareceram)
        val lastFresh = if (fresh) f else mem.lastFresh
        val liveIds = live.mapNotNull { dets[it.first].mapId }.toSet()
        val mapIds = if (p.mapRecent < 0 || lost || f - lastFresh > p.maxStale) emptyList() else map.filter { m ->
            val seen = lastAssoc[m.id]
            // em cima de uma peça vista agora: é ela mesma (cópia) ou está escondida atrás dela — não desenha por cima
            seen != null && f - seen <= p.mapRecent && m.id !in liveIds && !coveredByLive(m.cx, m.cy, m.r) &&
                held.none { overlaps(it.cx, it.cy, it.r, m.cx, m.cy, m.r) }
        }.map { it.id }
        return Choice(live, held, mapIds, Memory(f, lastAssoc, streak, tracks, lastFresh, if (fresh) cam else mem.lastCam), trackOf)
    }

    /**
     * Hologramas prontos para desenhar: [live] em coordenadas da câmera (com a detecção de origem, para o contorno),
     * [held] em coordenadas da câmera (segurados de um quadro anterior), [map] no mundo.
     */
    class Frame(val live: List<Pair<PieceSlam.Detection, Piece3D>>, val held: List<Piece3D>, val map: List<Pair<Piece3D, Int>>,
                val memory: Memory, val why: List<Why>, val mapIds: List<Int>)

    /** Monta a entrada da escolha a partir do SLAM logo depois de [PieceSlam.track] e devolve o que desenhar. */
    fun frame(slam: PieceSlam, dets: List<PieceSlam.Detection>, upCam: DoubleArray?, k: Intrinsics, mem: Memory, p: Policy = Policy()): Frame {
        val assoc = slam.lastAssoc
        val byId = slam.map.associateBy { it.id }
        val fresh = assoc.isNotEmpty() && slam.camera != null
        val ds = dets.map { d -> val id = assoc[d]?.takeIf { it in byId }; det(d, upCam, id, id?.let { byId.getValue(it).count } ?: 0) }
        val cam = slam.camera
        val items = ArrayList<MapItem>()
        if (cam != null && p.mapRecent >= 0) {
            // peças confirmadas, sem as cópias que o mapa ainda não juntou (como PieceSlam.pieces: peças rígidas não
            // se sobrepõem, encostadas distam ≥ 1 lado)
            val inv = cam.inverse(); val kept = ArrayList<PieceSlam.MapPiece>()
            for (mp in slam.map.filter { it.count >= PieceSlam.MIN_VIEWS }.sortedByDescending { it.count }) {
                val n = mp.pose.col(2)
                if (kept.any { o -> val m = o.pose.col(2)
                        o.outline == mp.outline && PieceSlam.sameHue(o.color, o.bin, mp.color, mp.bin) && abs(n[0] * m[0] + n[1] * m[1] + n[2] * m[2]) > .82 &&
                        PieceSlam.dist(o.pose.t, mp.pose.t) < max(Catalog.edgeM(o.size), Catalog.edgeM(mp.size)) * .8 }) continue
                kept += mp
                val c = inv.apply(mp.pose.t); if (c[2] < .03) continue
                val e = Catalog.edgeM(mp.size) * .71 // centro → canto de um quadrado
                items += MapItem(mp.id, mp.outline, mp.color, mp.bin, k.fx * c[0] / c[2] + k.cx, k.fy * c[1] / c[2] + k.cy, k.fx * e / c[2])
            }
        }
        // quanto a câmera andou desde o quadro anterior (só se o SLAM encaixou nos dois)
        val moved = mem.lastCam?.takeIf { fresh && cam != null && mem.lastFresh == mem.frame }?.let { c0 ->
            doubleArrayOf(PieceSlam.dist(c0.t, cam!!.t), Math.toDegrees(Rot.angle(c0.r, cam.r))) }
        val ch = choose(ds, items, fresh, slam.lost, mem, p, moved, if (fresh) cam else null)
        val live = ch.live.map { (i, _) -> val d = dets[i]; d to livePiece(d, ds[i].mapId?.let { byId[it] }, if (fresh) cam else null) }
        return Frame(live, ch.held.mapNotNull { it.piece }, ch.mapIds.mapNotNull { id -> byId[id] }.map { it.toPiece3D() to it.count },
            remember(ch, live.map { it.second }), ch.live.map { it.second }, ch.mapIds.filter { it in byId })
    }

    /** Memória do próximo quadro: a trilha de cada holograma ao vivo guarda o desenho ([pieces], na ordem de [Choice.live]) para segurá-lo se a detecção falhar. */
    fun remember(ch: Choice, pieces: List<Piece3D>): Memory {
        val tracks = ch.memory.tracks.toMutableList()
        for ((n, q) in ch.live.withIndex()) { val ti = ch.trackOf[q.first]; tracks[ti] = tracks[ti].copy(piece = pieces[n]) }
        val m = ch.memory
        return Memory(m.frame, m.lastAssoc, m.streak, tracks, m.lastFresh, m.lastCam)
    }

    fun det(d: PieceSlam.Detection, upCam: DoubleArray?, mapId: Int?, mapCount: Int): Det {
        val cx = d.imgCorners.sumOf { it[0] } / d.imgCorners.size; val cy = d.imgCorners.sumOf { it[1] } / d.imgCorners.size
        val r = d.imgCorners.maxOf { hypot(it[0] - cx, it[1] - cy) }
        var tilt = Double.NaN
        if (upCam != null) {
            val n = d.candidates[0].col(2); val l = hypot(hypot(upCam[0], upCam[1]), upCam[2])
            if (l > 1e-9) {
                val a = Math.toDegrees(acos((abs(n[0] * upCam[0] + n[1] * upCam[1] + n[2] * upCam[2]) / l).coerceIn(0.0, 1.0)))
                tilt = min(a, 90 - a) // deitada (normal na vertical) ou em pé (normal na horizontal)
            }
        }
        return Det(d.outline, d.color, d.bin, cx, cy, r, d.areaPx, d.rms, d.frontal, d.ambiguous, tilt, mapId, mapCount)
    }

    /**
     * Holograma ao vivo (coordenadas da câmera), na pose da própria detecção. Com peça do mapa: forma e tamanho
     * votados no mapa (de viés um quadrado parece retângulo) e, com a câmera em dia, a simetria que concorda com
     * a orientação do mapa. [Piece3D.support] = vistas da peça do mapa (1 sem peça).
     */
    fun livePiece(d: PieceSlam.Detection, mp: PieceSlam.MapPiece?, cam: Pose?): Piece3D {
        if (mp == null || mp.outline != d.outline) {
            val q = d.candidates[0]
            return Piece3D(d.shape, "grande", d.color, PieceSlam.v3(q.t), PieceSlam.v3(q.col(0)), PieceSlam.v3(q.col(1)), PieceSlam.v3(q.col(2)), 0f, 0f, 1)
        }
        val s = Catalog.SCALE.getValue(mp.size).toDouble()
        val cands = d.candidatesFor(mp.shape)
        val q = if (cam != null) cands.minBy { c -> Rot.angle(Rot.mul(cam.r, c.r), mp.pose.r) } else cands[0]
        val t = doubleArrayOf(q.t[0] * s, q.t[1] * s, q.t[2] * s) // mesma imagem: peça s vezes menor, s vezes mais perto
        return Piece3D(mp.shape, mp.size, d.color, PieceSlam.v3(t), PieceSlam.v3(q.col(0)), PieceSlam.v3(q.col(1)), PieceSlam.v3(q.col(2)), 0f, 0f, mp.count)
    }
}
