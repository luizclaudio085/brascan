package com.brastoy.brascan.scan

import android.graphics.Bitmap
import com.brastoy.brascan.vision.GroundCam
import com.brastoy.brascan.vision.PhotoPieces
import com.brastoy.brascan.vision.Catalog
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Contagem de peças soltas para o inventário: o detector de peças soltas (PhotoPieces.detect: segmentação que junta
 * aro/painel/ímãs da mesma peça, forma pelo molde de cada peça do catálogo, escala conjunta {1; 0,81; 0,5} com
 * correção de perspectiva e teste de ímãs para o encaixe) aplicado a uma foto ou a um quadro da câmera, convertido
 * para os 30 pares tamanho/formato do inventário B8 do simulador.
 */
object Counting {
    /** Largura em que uma foto é analisada (mais pixels = peças pequenas mais nítidas, mas mais lento). */
    const val PHOTO_W = 640
    /** Largura do quadro analisado ao vivo. */
    const val LIVE_W = 480

    /**
     * Peça achada. Caixa NORMALIZADA (0..1 da largura e da altura da imagem), tipo do inventário, cor, encaixe no
     * molde (0..1; 1 = marcada à mão). [cut]: encosta na borda da imagem (pode ser só um pedaço de peça): fica fora
     * da contagem.
     */
    data class Found(val cx: Float, val cy: Float, val w: Float, val h: Float, val kind: Inventory.Kind, val color: Int, val fit: Float, val cut: Boolean = false,
                     /** Lado da peça medido pelo detector, em fração da largura da imagem (0 = desconhecido). */ val side: Float = 0f)

    /** Combinação que existe no inventário do simulador mais perto da detectada (ex.: "E pequeno" não existe → E médio). */
    fun kindOf(shape: String, size: String): Inventory.Kind {
        val exact = Inventory.KINDS.firstOrNull { it.type == shape && it.size == size }
        if (exact != null) return exact
        val order = when (size) { "pequeno" -> listOf("medio", "grande"); "grande" -> listOf("medio", "pequeno"); else -> listOf("grande", "pequeno") }
        return order.firstNotNullOfOrNull { s -> Inventory.KINDS.firstOrNull { it.type == shape && it.size == s } } ?: Inventory.KINDS.first()
    }

    /** [cam]: intrínsecos + gravidade do quadro, quando houver (sem isso a inclinação do chão é estimada pelas peças). */
    fun detect(px: IntArray, w: Int, h: Int, cam: GroundCam? = null): List<Found> =
        PhotoPieces.detect(px, w, h, maxBlobs = 60, cam = cam)
            .map { d ->
                val m = 2f // pixels
                val cut = d.cx - d.w / 2 < m || d.cy - d.h / 2 < m || d.cx + d.w / 2 > w - m || d.cy + d.h / 2 > h - m
                Found(d.cx / w, d.cy / h, d.w / w, d.h / h, kindOf(d.shape, d.size), d.color, d.fit, cut, d.side / w)
            }

    /** Foto inteira, reduzida para [maxW] de largura. */
    fun detect(bmp: Bitmap, maxW: Int = PHOTO_W): List<Found> {
        val w = minOf(maxW, bmp.width); val h = maxOf(1, (bmp.height.toLong() * w / bmp.width).toInt())
        val b = if (w == bmp.width) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        return detect(px, w, h)
    }

    fun tally(found: List<Found>): Map<String, Int> = found.filter { !it.cut }.groupingBy { it.kind.key }.eachCount()

    /** Gira os pixels do sensor para a imagem em pé (rotationDegrees do CameraX: 0, 90, 180, 270). */
    fun upright(px: IntArray, w: Int, h: Int, rot: Int): Triple<IntArray, Int, Int> = when (((rot % 360) + 360) % 360) {
        90 -> Triple(IntArray(w * h).also { o -> for (y in 0 until h) for (x in 0 until w) o[x * h + (h - 1 - y)] = px[y * w + x] }, h, w)
        180 -> Triple(IntArray(w * h).also { o -> for (i in 0 until w * h) o[w * h - 1 - i] = px[i] }, w, h)
        270 -> Triple(IntArray(w * h).also { o -> for (y in 0 until h) for (x in 0 until w) o[(w - 1 - x) * h + y] = px[y * w + x] }, h, w)
        else -> Triple(px, w, h)
    }

    /**
     * Contagem estável ao vivo: mediana, por tipo, das contagens dos últimos [n] quadros. Um quadro ruim (mão na frente,
     * reflexo, borrão) não muda o número na tela. A câmera agora usa o [Tracker]; este fica como referência (o banco de
     * vídeo compara os dois).
     */
    class Stabilizer(private val n: Int = 7) {
        private val history = ArrayDeque<Map<String, Int>>()
        fun push(counts: Map<String, Int>): Map<String, Int> {
            history.addLast(counts); while (history.size > n) history.removeFirst()
            val keys = history.flatMap { it.keys }.toSet()
            return keys.associateWith { k -> val v = history.map { it[k] ?: 0 }.sorted(); v[v.size / 2] }.filterValues { it > 0 }
        }
        fun clear() = history.clear()
        val frames get() = history.size
    }

    /**
     * Contagem ao vivo por RASTREAMENTO: cada peça vira uma trilha que segue de quadro em quadro, em vez de cada quadro
     * ser contado do zero. As peças estão paradas no chão e a câmera mexe pouco entre quadros, então:
     * - o movimento da imagem é um só para todas as peças: é estimado pelos pares mais próximos (semelhança: translação,
     *   giro e escala, ou só translação com poucas peças) e aplicado às trilhas antes de associar;
     * - uma detecção é a mesma peça de uma trilha se o centro está perto (menos de meio lado), as caixas se sobrepõem, o
     *   tamanho não mudou muito (peça rígida, distância da câmera quase igual) e a cor (cromaticidade, que a exposição
     *   não muda) é parecida;
     * - só aparece na tela a trilha vista em pelo menos [minHits] dos últimos [window] quadros (ou em todos os quadros
     *   desde que surgiu, a partir de 2): um reflexo, sombra ou pedaço de borrão que surge em um quadro e some no outro
     *   não vira peça;
     * - uma peça confirmada que o detector perde por até [maxCoast] quadros (borrão) continua na tela, acompanhando o
     *   movimento da imagem;
     * - a peça é rígida e fica parada: a trilha que anda (ou, encostada na borda, cresce) sozinha depois de descontado o
     *   movimento da câmera é uma mão, não aparece, e a peça que ela cobre continua contada;
     * - a forma de cada peça é o voto dos quadros em que ela foi vista (peso maior quando o molde encaixa melhor), com
     *   folga para não trocar a cada quadro; o tamanho vem do lado medido (mediana dos quadros) comparado com a escala
     *   do chão, comum a todas as peças ([floorScale]), e, quando essa escala não fecha, do voto dos quadros;
     * - o total por tipo conta as trilhas na tela que não encostam na borda.
     * Os parâmetros além dos 3 primeiros existem para o banco de vídeo (LooseVideoProbe) comparar variantes.
     */
    class Tracker(private val window: Int = 5, private val minHits: Int = 3, private val maxCoast: Int = 2,
                  /** Tamanho pela escala comum a todas as peças ([floorScale]) em vez do voto do tamanho de cada quadro. */
                  private val sharedScale: Boolean = true,
                  /** A escala pode variar na imagem como a de um chão inclinado (câmera de lado). */
                  private val tilt: Boolean = true,
                  /** A escala só decide o tamanho da peça cujo lado cai a menos disto (log) de um tamanho do catálogo… */
                  private val trust: Float = .07f,
                  /** …e só se esta fração das peças cai assim (senão a escala do quadro não é confiável). */
                  private val trustShare: Float = .85f,
                  /** Peça vista em todos os quadros desde que apareceu confirma com 2 (0 = nunca, 1 = só no começo, 2 = sempre). */
                  private val fast: Int = 2,
                  /** O que se move sozinho na imagem (mão) não aparece, e a peça que ele cobre continua contada. */
                  private val still: Boolean = true,
                  /** Caixa que cresce/encolhe sozinha também é "movimento": 0 = nunca, 1 = só encostada na borda, 2 = sempre. */
                  private val growRule: Int = 1, private val maxGrow: Float = MAX_GROW,
                  /** O número de um tipo só muda quando o valor novo se repete por este número de quadros seguidos. */
                  private val hold: Int = 2,
                  /** Peça já vista em [steadyHits] quadros pode ficar até [steadyCoast] quadros sem ser vista (peça não some). */
                  private val steadyHits: Int = 4, private val steadyCoast: Int = 5) {
        private class Track(val id: Int, var x: Float, var y: Float, var w: Float, var h: Float, var color: Int, var fit: Float, var cut: Boolean) {
            var hist = 1 // bit 0 = visto no quadro atual, bit 1 = no anterior…
            var miss = 0
            var age = 1 // quadros desde que apareceu
            var hits = 1 // quadros em que foi vista
            /** Deslocamento próprio (pixels por quadro, média móvel) depois de descontado o movimento da câmera. */
            var move = 0f
            /** Mudança de área da caixa (|log|, por quadro, média móvel), também já descontado o zoom da câmera. */
            var grow = 0f
            /** Quadros seguidos coberta por algo que se move (não contam como perdida). */
            var hidden = 0
            var confirmed = false
            var chroma = chroma(color)
            val box get() = maxOf(w, h)
            /** Votos de forma e de tamanho (o tamanho do detector, usado quando não há escala comum). */
            val shapeVotes = HashMap<String, Float>(); val sizeVotes = HashMap<String, Float>()
            var shape: String? = null; var sizeVoted: String? = null
            /** Tamanho pela escala comum (null = sem escala). */
            var sizeScaled: String? = null
            /** Últimas medidas (forma, lado em pixels), atualizadas com a escala da imagem quando a câmera se aproxima. */
            val sides = ArrayDeque<Side>()
            val kind get() = kindOf(shape ?: "quadrado", sizeScaled ?: sizeVoted ?: "grande")

            fun vote(k: Inventory.Kind, fit: Float, sidePx: Float) {
                val wgt = .3f + fit.coerceIn(0f, 1f)
                shape = vote(shapeVotes, k.type, wgt, shape); sizeVoted = vote(sizeVotes, k.size, wgt, sizeVoted)
                if (sidePx > 0) { sides.addLast(Side(k.type, sidePx)); while (sides.size > 9) sides.removeFirst() }
            }
            private fun vote(m: HashMap<String, Float>, k: String, wgt: Float, cur: String?): String {
                for (e in m.entries) e.setValue(e.value * .9f)
                m.merge(k, wgt, Float::plus)
                val best = m.maxByOrNull { it.value }!!
                return if (cur != null && (m[cur] ?: 0f) >= best.value * .75f) cur else best.key
            }
            /** Lado da peça: mediana das medidas feitas com a forma votada (o lado depende do molde). */
            fun side(): Float {
                val l = sides.filter { it.shape == shape }.ifEmpty { sides.toList() }.map { it.px }.sorted()
                return if (l.isEmpty()) 0f else l[l.size / 2]
            }
        }
        private class Side(val shape: String, var px: Float)

        /** Detecção em pixels (eixos com a mesma escala, para distâncias e giros fazerem sentido). */
        private class D(val x: Float, val y: Float, val w: Float, val h: Float, val f: Found) {
            val size get() = maxOf(w, h)
            val chroma = chroma(f.color)
        }

        private val tracks = ArrayList<Track>()
        private var nextId = 0
        private var iw = 0; private var ih = 0
        /** Quadros recebidos desde o último [clear]. */
        var frames = 0; private set
        /** Contagem por tipo das peças rastreadas na tela (fora as cortadas pela borda). */
        var counts: Map<String, Int> = emptyMap(); private set
        /** Caixas na tela (normalizadas, como [Found]), com o tipo votado. */
        var shown: List<Found> = emptyList(); private set

        fun clear() { tracks.clear(); frames = 0; counts = emptyMap(); shown = emptyList(); held.clear(); pending.clear(); scale = null }

        /** Contagem exibida por tipo e o valor novo que está esperando se repetir (valor, quadros). */
        private val held = HashMap<String, Int>(); private val pending = HashMap<String, IntArray>()

        /** Uma peça que troca de tipo num quadro só (ou some e volta) não mexe no número: ele espera o novo valor se repetir. */
        private fun holdCounts(raw: Map<String, Int>): Map<String, Int> {
            val first = held.isEmpty()
            for (k in raw.keys + held.keys) {
                val v = raw[k] ?: 0
                if (v == (held[k] ?: 0)) { pending.remove(k); continue }
                val p = pending[k]
                if (p != null && p[0] == v) p[1]++ else pending[k] = intArrayOf(v, 1)
                if (first || pending.getValue(k)[1] >= hold) { if (v == 0) held.remove(k) else held[k] = v; pending.remove(k) }
            }
            return HashMap(held)
        }

        /** Um quadro analisado (imagem [w]×[h], detecções normalizadas) → caixas para mostrar. */
        fun push(found: List<Found>, w: Int, h: Int): List<Found> {
            if (w != iw || h != ih) { clear(); iw = w; ih = h } // girou o celular: outra imagem
            frames++
            val det = found.map { D(it.cx * w, it.cy * h, it.w * w, it.h * h, it) }
            for (t in tracks) t.age++
            follow(det)
            val taken = BooleanArray(det.size)
            val seen = HashSet<Track>()
            // 1º passe: critério estrito (posição, sobreposição, tamanho, cor); 2º passe: só posição e sobreposição, para
            // trilhas que sobraram (a mesma peça com a caixa mudada por borrão ou reflexo)
            for (strict in booleanArrayOf(true, false)) {
                val pairs = ArrayList<Triple<Track, Int, Float>>()
                for (t in tracks) {
                    if (t in seen) continue
                    for ((i, d) in det.withIndex()) if (!taken[i]) cost(t, d, strict)?.let { pairs += Triple(t, i, it) }
                }
                pairs.sortBy { it.third }
                for ((t, i, _) in pairs) {
                    if (t in seen || taken[i]) continue
                    seen += t; taken[i] = true; update(t, det[i])
                }
            }
            // coisas que se movem sozinhas (mão, sombra da mão) não são peça; a peça que some debaixo delas está só coberta
            val movers = if (still) seen.filter { moving(it) } else emptyList()
            for (t in tracks) if (t !in seen) {
                t.hist = t.hist shl 1
                val covered = t.confirmed && t.hidden < MAX_HIDDEN && movers.any { m -> overlap(t, m) > .3f }
                if (covered) t.hidden++ else t.miss++
                t.cut = t.x - t.w / 2 < CUT_PX || t.y - t.h / 2 < CUT_PX || t.x + t.w / 2 > w - CUT_PX || t.y + t.h / 2 > h - CUT_PX
            }
            // detecção nova vira trilha, a não ser que caia dentro de uma trilha já explicada neste quadro (pedaço da mesma peça)
            for ((i, d) in det.withIndex()) {
                if (taken[i]) continue
                // pedaço = mesma cor e quase toda a caixa dentro da caixa da trilha. Só o centro dentro não basta: a caixa de
                // peça girada (ou de L/porta) cobre área vazia, e uma peça vizinha menor encostada na diagonal sumiria
                if (seen.any { t -> d.w * d.h < t.w * t.h && hypot(t.chroma[0] - d.chroma[0], t.chroma[1] - d.chroma[1]) <= MAX_CHROMA && insideShare(d, t) > .7f }) continue
                tracks += Track(nextId++, d.x, d.y, d.w, d.h, d.f.color, d.f.fit, d.f.cut).also { if (!d.f.cut) it.vote(d.f.kind, d.f.fit, d.f.side * w) }
            }
            val mask = (1 shl window) - 1
            val quick = fast == 2 || (fast == 1 && frames <= window)
            for (t in tracks) {
                if (Integer.bitCount(t.hist and mask) >= minHits) t.confirmed = true
                if (quick && t.age in 2..window && Integer.bitCount(t.hist and ((1 shl t.age) - 1)) == t.age) t.confirmed = true
            }
            tracks.removeAll { t ->
                (if (t.confirmed) t.miss > coast(t) else t.miss > window - minHits) ||
                    t.x < -t.w / 2 || t.y < -t.h / 2 || t.x > w + t.w / 2 || t.y > h + t.h / 2
            }
            merge()
            val vis = tracks.filter { it.confirmed && it.miss <= coast(it) && !(still && moving(it)) }.sortedBy { it.id }
            if (sharedScale) rescale(vis)
            shown = vis.map { t -> Found(t.x / w, t.y / h, t.w / w, t.h / h, t.kind, t.color, t.fit, t.cut, t.side() / w) }
            counts = holdCounts(shown.filter { !it.cut }.groupingBy { it.kind.key }.eachCount())
            return shown
        }

        /** Escala do chão no quadro atual (lado do quadrado grande em pixels em cada ponto) ou null; ver [floorScale]. */
        var scale: FloorScale? = null; private set

        /** Tamanho de cada peça na tela = lado medido ÷ lado do grande ali, com folga para não trocar na fronteira. */
        private fun rescale(vis: List<Track>) {
            val pts = vis.filter { !it.cut && it.shape != "encaixe" && it.side() > 0f }.map { floatArrayOf(it.x, it.y, it.side()) }
            val sc = floorScale(pts, iw, ih, tilt, scale); scale = sc
            // distância (log do lado) de cada peça ao tamanho do catálogo mais perto, com esta escala
            fun off(t: Track): Float { val l = ln(t.side() / sc!!.at(t.x, t.y)); return LOG_RATIO.minOf { abs(l - it) } }
            val trusted = sc != null && pts.size >= 2 &&
                vis.count { !it.cut && it.shape != "encaixe" && it.side() > 0f && off(it) < trust } >= pts.size * trustShare
            for (t in vis) {
                if (!trusted || t.cut || t.side() <= 0f || off(t) >= trust) { t.sizeScaled = null; continue }
                val r = t.side() / sc!!.at(t.x, t.y)
                val cur = t.sizeScaled
                val keep = cur != null && (Catalog.sizeForRatio(r * 1.04f) == cur || Catalog.sizeForRatio(r / 1.04f) == cur)
                t.sizeScaled = if (keep) cur else Catalog.sizeForRatio(r)
            }
        }

        /** Custo de associar a trilha à detecção (menor = melhor) ou null se não pode ser a mesma peça. */
        private fun cost(t: Track, d: D, strict: Boolean): Float? {
            val ref = maxOf(t.box, d.size, 1f)
            val dn = hypot(t.x - d.x, t.y - d.y) / ref
            if (dn > .5f) return null
            val iou = iou(t.x, t.y, t.w, t.h, d.x, d.y, d.w, d.h)
            if (iou < .1f) return null
            val cd = hypot(t.chroma[0] - d.chroma[0], t.chroma[1] - d.chroma[1])
            if (strict) {
                if (!alike(t, d)) return null
            } else if (iou < .3f || cd > 2 * MAX_CHROMA) return null
            return dn + (1 - iou) * .5f + cd * 3f
        }

        /** Mesmo tamanho (peça rígida, câmera quase à mesma distância) e mesma cor (cromaticidade). */
        private fun alike(t: Track, d: D): Boolean {
            val ar = (t.w * t.h) / (d.w * d.h).coerceAtLeast(1f)
            return ar <= MAX_AREA_RATIO && ar >= 1 / MAX_AREA_RATIO && hypot(t.chroma[0] - d.chroma[0], t.chroma[1] - d.chroma[1]) <= MAX_CHROMA
        }

        /**
         * A peça de verdade fica parada no chão e é rígida: só a câmera a move e a aproxima (isso [follow] já descontou),
         * então a caixa não anda nem cresce sozinha de um quadro para o outro, como a de uma mão entrando na imagem.
         */
        private fun moving(t: Track) = t.move > maxOf(MOVE_MIN_PX, MOVE_FRAC * t.box) ||
            (growRule == 2 || (growRule == 1 && t.cut)) && t.grow > maxGrow

        /** Quadros seguidos que a trilha confirmada pode passar sem ser vista. */
        private fun coast(t: Track) = if (t.hits >= steadyHits) maxOf(maxCoast, steadyCoast) else maxCoast

        /** Fração da caixa de [t] coberta pela de [m]. */
        /** Fração da caixa da detecção [d] que cai dentro da caixa da trilha [t]. */
        private fun insideShare(d: D, t: Track): Float {
            val ix = minOf(d.x + d.w / 2, t.x + t.w / 2) - maxOf(d.x - d.w / 2, t.x - t.w / 2)
            val iy = minOf(d.y + d.h / 2, t.y + t.h / 2) - maxOf(d.y - d.h / 2, t.y - t.h / 2)
            return if (ix <= 0 || iy <= 0) 0f else ix * iy / (d.w * d.h).coerceAtLeast(1e-6f)
        }

        private fun overlap(t: Track, m: Track): Float {
            val ix = minOf(t.x + t.w / 2, m.x + m.w / 2) - maxOf(t.x - t.w / 2, m.x - m.w / 2)
            val iy = minOf(t.y + t.h / 2, m.y + m.h / 2) - maxOf(t.y - t.h / 2, m.y - m.h / 2)
            return if (ix <= 0 || iy <= 0) 0f else ix * iy / (t.w * t.h).coerceAtLeast(1f)
        }

        private fun update(t: Track, d: D) {
            t.move = t.move * .5f + hypot(d.x - t.x, d.y - t.y) * .5f
            t.grow = t.grow * .5f + abs(ln((d.w * d.h).coerceAtLeast(1f) / (t.w * t.h).coerceAtLeast(1f))) * .5f
            t.hidden = 0
            t.x += (d.x - t.x) * .75f; t.y += (d.y - t.y) * .75f
            t.w += (d.w - t.w) * .6f; t.h += (d.h - t.h) * .6f
            t.color = d.f.color; t.chroma = d.chroma; t.fit += (d.f.fit - t.fit) * .5f; t.cut = d.f.cut
            t.hist = (t.hist shl 1) or 1; t.miss = 0; t.hits++
            if (!d.f.cut) t.vote(d.f.kind, d.f.fit, d.f.side * iw) // peça cortada pela borda: forma e lado não valem
        }

        /**
         * Movimento da imagem desde o quadro anterior (as peças estão paradas: é o da câmera), estimado pelos pares
         * trilha ↔ detecção mais próximos um do outro, e aplicado às trilhas.
         */
        private fun follow(det: List<D>) {
            if (tracks.isEmpty() || det.isEmpty()) return
            val pairs = ArrayList<Pair<Track, D>>()
            for (t in tracks) {
                val d = det.minByOrNull { hypot(it.x - t.x, it.y - t.y) } ?: continue
                if (tracks.minByOrNull { hypot(it.x - d.x, it.y - d.y) } !== t) continue
                if (hypot(d.x - t.x, d.y - t.y) < maxOf(t.box, d.size) && alike(t, d) && !(still && moving(t))) pairs += t to d
            }
            if (pairs.isEmpty()) return
            val tx = median(pairs.map { it.second.x - it.first.x }); val ty = median(pairs.map { it.second.y - it.first.y })
            // pares que não seguem a translação da maioria (peça trocada, detecção ruim) ficam de fora da semelhança
            val good = pairs.filter { (t, d) -> hypot(d.x - t.x - tx, d.y - t.y - ty) < .3f * maxOf(t.box, d.size) }
            // p' = q̄ + [c −s; s c](p − p̄); sem giro/escala confiável, só a translação mediana
            var c = 1f; var s = 0f; var px = 0f; var py = 0f; var qx = tx; var qy = ty
            if (good.size >= 3) {
                val mpx = good.map { it.first.x }.average().toFloat(); val mpy = good.map { it.first.y }.average().toFloat()
                val mqx = good.map { it.second.x }.average().toFloat(); val mqy = good.map { it.second.y }.average().toFloat()
                var a = 0f; var b = 0f; var n2 = 0f
                for ((t, d) in good) {
                    val ux = t.x - mpx; val uy = t.y - mpy; val vx = d.x - mqx; val vy = d.y - mqy
                    a += ux * vx + uy * vy; b += ux * vy - uy * vx; n2 += ux * ux + uy * uy
                }
                if (n2 > 1f) {
                    val sc = a / n2; val ss = b / n2
                    if (hypot(sc, ss) in .85f..1.18f && abs(atan2(ss, sc)) < .2f) { c = sc; s = ss; px = mpx; py = mpy; qx = mqx; qy = mqy }
                }
            }
            val k = hypot(c, s)
            for (t in tracks) {
                val ux = t.x - px; val uy = t.y - py
                t.x = qx + c * ux - s * uy; t.y = qy + s * ux + c * uy
                t.w *= k; t.h *= k
                if (k != 1f) for (sd in t.sides) sd.px *= k
            }
        }

        /** Duas trilhas na mesma peça (uma nasceu de um pedaço): fica a mais antiga com os votos das duas. */
        private fun merge() {
            val gone = HashSet<Track>()
            for (i in tracks.indices) for (j in i + 1 until tracks.size) {
                val a = tracks[i]; val b = tracks[j]
                if (a in gone || b in gone) continue
                if (still && (moving(a) || moving(b))) continue // a mão passando por cima não vira a peça
                if (iou(a.x, a.y, a.w, a.h, b.x, b.y, b.w, b.h) < .5f) continue
                val (keep, drop) = if (!a.confirmed && b.confirmed) b to a else a to b
                drop.shapeVotes.forEach { (k, v) -> keep.shapeVotes.merge(k, v, Float::plus) }
                drop.sizeVotes.forEach { (k, v) -> keep.sizeVotes.merge(k, v, Float::plus) }
                keep.shapeVotes.maxByOrNull { it.value }?.let { if ((keep.shapeVotes[keep.shape] ?: 0f) < it.value * .75f) keep.shape = it.key }
                keep.sizeVotes.maxByOrNull { it.value }?.let { if ((keep.sizeVotes[keep.sizeVoted] ?: 0f) < it.value * .75f) keep.sizeVoted = it.key }
                drop.sides.forEach { keep.sides.addLast(it) }; while (keep.sides.size > 9) keep.sides.removeFirst()
                keep.hist = keep.hist or drop.hist; keep.miss = minOf(keep.miss, drop.miss); keep.age = maxOf(keep.age, drop.age); keep.hits += drop.hits
                gone += drop
            }
            tracks.removeAll(gone)
        }
    }

    /** Lado de cada tamanho ÷ lado do grande (catálogo: 1 : 0,81 : 0,5), em log. */
    private val LOG_RATIO = Catalog.SIZES.map { ln(Catalog.SCALE.getValue(it)) }.toFloatArray()
    /** Erro (log do lado) acima do qual uma peça não pesa mais na escolha da escala (molde errado, peça fundida). */
    private const val UNIT_CLIP = .12f * .12f
    /** Custo de dizer que uma peça não é grande: só desempata quando a cena inteira tem um tamanho (aí a escala é ambígua). */
    private const val UNIT_PRIOR = .004f

    /**
     * Lado do quadrado grande (pixels) que melhor explica os lados [sides] das peças de um quadro. Todas estão no chão, à
     * mesma distância da câmera, e cada uma é grande, média ou pequena (1 : 0,81 : 0,5 do catálogo): a escala certa é a
     * que põe cada lado perto de um desses três. Com dois tamanhos ou mais na cena as razões entre os lados resolvem a
     * escala sozinhas (1,23 grande/médio, 1,62 médio/pequeno, 2 grande/pequeno); com um tamanho só ela é ambígua e
     * vale "a maioria é grande", como no detector de fotos. null com menos de 2 peças.
     */
    fun unitOf(sides: List<Float>): Float? {
        val logs = sides.filter { it > 0f }.map { ln(it) }.toFloatArray()
        if (logs.size < 2) return null
        return exp(fitUnit(logs).second)
    }

    /** (custo, log do lado do grande) para lados em log [logs]; ver [unitOf]. */
    private fun fitUnit(logs: FloatArray): Pair<Float, Float> {
        fun cost(u: Float): Float {
            var c = 0f
            for (p in logs) {
                var e = Float.MAX_VALUE; var k = 0
                for (i in LOG_RATIO.indices) { val d = abs(p - u - LOG_RATIO[i]); if (d < e) { e = d; k = i } }
                c += minOf(e * e, UNIT_CLIP) + if (k == 0) 0f else UNIT_PRIOR
            }
            return c
        }
        var best = Float.MAX_VALUE; var bu = 0f
        for (p in logs) for (q in LOG_RATIO) {
            val u = p - q; val c = cost(u)
            if (c < best - 1e-6f || (c <= best + 1e-6f && u < bu)) { best = c; bu = u }
        }
        // refina com as peças que encaixam bem: média de log(lado) − log(razão do tamanho atribuído)
        var sum = 0f; var n = 0
        for (p in logs) {
            var e = Float.MAX_VALUE
            for (q in LOG_RATIO) { val d = p - bu - q; if (abs(d) < abs(e)) e = d }
            if (abs(e) < .08f) { sum += e; n++ }
        }
        if (n > 0) bu += sum / n
        return cost(bu) to bu
    }

    /**
     * Escala do chão num quadro: lado do quadrado grande, em pixels, em cada ponto da imagem. Para um chão plano a
     * inversa da profundidade é afim na imagem, e o tamanho aparente de uma peça deitada acompanha: com a câmera
     * inclinada, a peça perto da borda de baixo parece maior que a igual perto da borda de cima.
     */
    class FloorScale(val u0: Float, val gx: Float, val gy: Float, private val cx: Float, private val cy: Float, private val w: Float) {
        fun at(x: Float, y: Float) = u0 * (1 + gx * (x - cx) / w + gy * (y - cy) / w)
    }

    /** Peças (com lado medido) a partir das quais a inclinação do chão é estimada; com menos, a escala é uma só. */
    private const val TILT_MIN = 6
    /** Desempate a favor de pouca inclinação (por peça, por unidade de gradiente²). */
    private const val TILT_PRIOR = .002f
    /** A inclinação só é aceita se explicar os lados melhor que a escala única por esta margem (por peça). */
    private const val TILT_GAIN = .0015f

    /**
     * Escala do chão ([FloorScale]) que melhor explica as peças [pts] (x, y, lado em pixels) de uma imagem [w]×[h]:
     * com [tilt], procura também um gradiente (até ±1,2 por largura de imagem, câmera até ~45° de lado) numa grade e
     * depois em volta do melhor, e só fica com ele se ganhar da escala única. [prev]: a do quadro anterior, que fica
     * se continuar quase tão boa (o número não troca por causa de um empate).
     */
    fun floorScale(pts: List<FloatArray>, w: Int, h: Int, tilt: Boolean, prev: FloorScale? = null): FloorScale? {
        val n = pts.size
        if (n < 2) return null
        val cx = w / 2f; val cy = h / 2f; val ww = w.toFloat()
        val ls = FloatArray(n) { ln(pts[it][2]) }
        val buf = FloatArray(n)
        fun eval(gx: Float, gy: Float): Pair<Float, Float>? {
            for (i in 0 until n) {
                val f = 1 + gx * (pts[i][0] - cx) / ww + gy * (pts[i][1] - cy) / ww
                if (f < .35f) return null
                buf[i] = ls[i] - ln(f)
            }
            val (c, u) = fitUnit(buf)
            return c + TILT_PRIOR * n * (gx * gx + gy * gy) to u
        }
        val flat = eval(0f, 0f)!!
        if (!tilt || n < TILT_MIN) return FloorScale(exp(flat.second), 0f, 0f, cx, cy, ww)
        var bc = Float.MAX_VALUE; var bu = 0f; var bx = 0f; var by = 0f
        fun tryAt(gx: Float, gy: Float) { val r = eval(gx, gy) ?: return; if (r.first < bc) { bc = r.first; bu = r.second; bx = gx; by = gy } }
        for (i in -4..4) for (j in -4..4) tryAt(i * .3f, j * .3f)
        val x0 = bx; val y0 = by
        for (i in -3..3) for (j in -3..3) tryAt(x0 + i * .1f, y0 + j * .1f)
        if (bc > flat.first - TILT_GAIN * n) { bx = 0f; by = 0f; bc = flat.first; bu = flat.second }
        if (prev != null) {
            val p = eval(prev.gx, prev.gy)
            if (p != null && p.first <= bc + .001f * n) { bx = prev.gx; by = prev.gy; bu = p.second }
        }
        return FloorScale(exp(bu), bx, by, cx, cy, ww)
    }

    /** Trilha que anda sozinha mais que isto por quadro (pixels, ou fração do tamanho da caixa) não é peça parada. */
    private const val MOVE_MIN_PX = 5f
    private const val MOVE_FRAC = .15f
    /** Caixa que muda de área mais que isto por quadro (|log|, média móvel) não é de peça rígida parada. */
    private const val MAX_GROW = .15f
    /** Quadros que uma peça confirmada pode ficar coberta (mão passando) sem sair da conta. */
    private const val MAX_HIDDEN = 6

    /** Folga (pixels) para considerar que a caixa encosta na borda da imagem (a mesma de [detect]). */
    private const val CUT_PX = 2f
    /** Área da caixa de uma mesma peça entre dois quadros seguidos (peça rígida, câmera quase à mesma distância). */
    private const val MAX_AREA_RATIO = 2f
    /** Distância máxima de cromaticidade (r, g normalizados) entre duas vistas da mesma peça. */
    private const val MAX_CHROMA = .08f

    private fun chroma(c: Int): FloatArray {
        val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255; val s = (r + g + b).coerceAtLeast(1).toFloat()
        return floatArrayOf(r / s, g / s)
    }

    private fun median(v: List<Float>): Float { val s = v.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

    private fun iou(ax: Float, ay: Float, aw: Float, ah: Float, bx: Float, by: Float, bw: Float, bh: Float): Float {
        val ix = minOf(ax + aw / 2, bx + bw / 2) - maxOf(ax - aw / 2, bx - bw / 2)
        val iy = minOf(ay + ah / 2, by + bh / 2) - maxOf(ay - ah / 2, by - bh / 2)
        if (ix <= 0 || iy <= 0) return 0f
        val i = ix * iy; return i / (aw * ah + bw * bh - i)
    }
}
