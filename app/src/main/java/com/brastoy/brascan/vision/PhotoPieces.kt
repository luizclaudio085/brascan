package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** Peça encontrada numa foto (pixels da imagem segmentada). */
/**
 * [spin]/[spinTop]: giro da peça no próprio plano para foto de frente / de cima (rad). [fit]: IoU do molde (0..1).
 * [side]: lado medido (no chão retificado quando houve correção de perspectiva). [sizeConf]: probabilidade de o
 * tamanho estar certo (escala da foto × folga da medida; baixa quando a foto só tem um tamanho e nada para comparar).
 */
data class PhotoPiece(val cx: Float, val cy: Float, val w: Float, val h: Float, val shape: String, val size: String, val color: Int, val spin: Float, val spinTop: Float, val side: Float, val fit: Float = 0f, val sizeConf: Float = 0f)

/**
 * Regiões → peças: cola os ímãs na peça e junta aro e painel do mesmo plástico (só com a segmentação de peças
 * soltas, [detect]), resolve o que está dentro de quê (encaixe dentro de moldura, vidros da janela, painel da própria
 * peça), a forma de cada peça pelo molde do catálogo ([ShapeMatch], sem a perspectiva quando o chão é conhecido ou
 * estimado) e o tamanho em relação ao quadrado grande da foto.
 *
 * Chão: gravidade do celular se vier em [GroundCam.down]; senão a inclinação estimada pelas próprias peças
 * ([GroundEstimate]: casco de cada peça contra a anisotropia do catálogo + os 3 tamanhos), com a forma da 1ª passada;
 * a forma é refeita sem a perspectiva e a mesma inclinação vale para o tamanho.
 *
 * Tamanho: (1) o contorno de cada peça vai para o chão retificado; (2) mede o lado pelo casco contra o molde do
 * catálogo ([measure]); (3) escolhe a escala da foto em conjunto com os tamanhos que existem ({1; 0,81; 0,5} × lado
 * do grande, [SizeScale]).
 *
 * Só para peças soltas (fotos, contagem do inventário); o scanner 3D usa as regiões do Segmenter direto.
 *
 * Velocidade: o trabalho de cada peça (máscara, molde, giro, contorno) e de cada hipótese de inclinação roda em
 * paralelo ([Par]) e volta na ordem de sempre; a saída é idêntica à sequencial (DetectorGoldenTest).
 */
object PhotoPieces {
    private val FRAMES = setOf("moldura", "porta", "janela")

    // ---- forma ----
    /** Forma pelo molde inteiro de cada peça do catálogo ([ShapeMatch]) em vez das regras do [ShapeClassifier]. */
    @JvmField var templateShapes = true
    /** Sem gravidade, refaz a forma sem a perspectiva estimada pela própria foto. */
    @JvmField var autoFloor = true
    /** Furo fechado a partir desta fração da silhueta: só as peças vazadas concorrem. */
    @JvmField var holeGate = .12f
    /** Peso do IoU dos furos e do alongamento do vão na escolha entre as vazadas. */
    @JvmField var holeWeight = .5f
    @JvmField var elongWeight = .5f
    /** Faixa comprida (≥ 3:1) que não fecha com molde nenhum = espinha do E. */
    @JvmField var stripToE = true
    /**
     * De onde vem a inclinação para refazer a forma: [TiltSource.GROUND] = [GroundEstimate] (o mesmo do tamanho, com
     * todas as formas e a escala); [TiltSource.ISO] = [FloorView.estimate] (só as peças de momento isótropo).
     */
    enum class TiltSource { GROUND, ISO }
    @JvmField var shapeTilt = TiltSource.GROUND
    /**
     * Depois de refazer a forma, estima de novo a inclinação (para o tamanho) com as formas novas. Desligado: no banco
     * a 2ª estimativa deu exatamente os mesmos números nas 60 cenas e custava ~20-50 ms por quadro.
     */
    @JvmField var refineTilt = false

    // ---- tamanho ----
    /**
     * Medida do lado: caixa mínima com o fator antigo dos triângulos (legado), só caixa, só área do casco, média
     * geométrica das duas, ou ADAPT (área quando a forma é confiável, caixa quando a forma está em dúvida).
     */
    enum class SideMode { OBB_TRI, OBB, AREA, MIX, ADAPT }
    @JvmField var sideMode = SideMode.ADAPT
    /** false = escala antiga (percentil 75 dos lados = grande). */
    @JvmField var jointScale = true
    /** Sem gravidade: estimar a inclinação do chão pelas peças (false = medir e classificar na imagem crua). */
    @JvmField var estimateTilt = true
    /**
     * Cada peça só recebe os tamanhos em que a forma dela existe no inventário do simulador (código B8,
     * [Catalog.sizesOf]): ameia (E) grande ou média, encaixe só médio.
     */
    @JvmField var inventorySizes = true
    /** Ameia (E) medida pelo comprimento da caixa (a espinha), não pela largura/área (dentes cobertos de ímãs). */
    @JvmField var eByLength = true

    // ---- painel fora da região, peça cortada pela borda, encaixe sem ímã ----
    /**
     * Vão cheio de primeiro plano da cor da peça é o painel que ficou fora da região, não furo (furo de verdade mostra o
     * chão); regiões da mesma cor encostadas são pedaços da mesma peça.
     */
    @JvmField var fillPanels = true
    /** "Dentro de outra" também exige ≥ 85% dos pixels dentro do casco da de fora (não só da caixa). */
    @JvmField var hullInside = true
    /** Região cortada pela borda da foto com o centro da peça fora do quadro não entra na contagem. */
    @JvmField var dropCutAtBorder = true
    /** Quadrado sem as ranhuras dos ímãs = encaixe (plástico, sem ímã). */
    @JvmField var magnetCheck = true
    /** Porta, moldura e janela têm contorno quadrado: com outro contorno o vão não é furo de moldura. */
    @JvmField var frameOutline = true

    /**
     * [pieces]: peças; [scale]: escala da foto (unidade = lado do grande nas unidades de [PhotoPiece.side], confiança,
     * ambígua ou não); [tiltDeg]: inclinação da câmera usada para retificar (0 = sem correção); [gravity]: veio da
     * gravidade (true) ou foi estimada pelas peças (false); [ground]: a retificação usada (null = nenhuma);
     * [estimate]: a última estimativa da inclinação pelas peças (custo com e sem inclinação), null com gravidade.
     */
    class Result(val pieces: List<PhotoPiece>, val scale: SizeScale.Fit, val tiltDeg: Float, val gravity: Boolean, val ground: GroundRect?,
                 val estimate: GroundEstimate.Result? = null)

    /**
     * [cam] (opcional): intrínsecos + gravidade (+ altura) quando o app tem (contagem ao vivo: [GroundCam.fromFrame] ou
     * [GroundCam.fromDeviceGravity]); com gravidade não há estimativa da inclinação. null = foto sem nada (estima).
     */
    fun from(seg: Segmentation, cam: GroundCam? = null): List<PhotoPiece> = analyze(seg, cam).pieces

    // sem trava: lido em paralelo por cada hipótese de inclinação (o valor é o mesmo, calculado uma vez por forma)
    private val catalogHullArea = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private fun hullArea(shape: String): Float =
        catalogHullArea[shape] ?: Geo2.area(Geo2.hull(Catalog.polygon(shape).map { P2(it.first, it.second) })).also { catalogHullArea[shape] = it }
    /** Comprimento (maior lado da caixa) do molde do E, em lados. */
    private val eLong: Float by lazy {
        val p = Catalog.polygon("E")
        maxOf(p.maxOf { it.first } - p.minOf { it.first }, p.maxOf { it.second } - p.minOf { it.second })
    }

    /**
     * Lado (na unidade do casco) de uma peça de forma [shape] cujo casco convexo é [hull], e quanto a forma "fecha"
     * com o casco (0..1). [iou]: encaixe do molde na máscara (TemplateFit).
     *
     * Duas medidas contra o molde: ÁREA do casco (√(área / área do casco do molde)) e CAIXA mínima (largura e altura
     * contra as do molde). Com a forma certa a área é a melhor: ponta de triângulo que some na segmentação quase
     * não tem área, mas encurta a caixa. Com a forma ERRADA a área erra feio (triângulo lido como porta: −40%) e a
     * caixa quase não (as confusões comuns têm caixas parecidas). Forma confiável = molde encaixou (IoU alto) ou as
     * duas medidas concordam; senão vale a caixa.
     */
    fun measureWithTrust(hull: List<P2>, shape: String, iou: Float): Pair<Float, Float> {
        val out = FloatArray(2)
        measureInto(FloatArray(hull.size) { hull[it].x }, FloatArray(hull.size) { hull[it].y }, hull.size, shape, iou, out)
        return out[0] to out[1]
    }

    /**
     * [measureWithTrust] com o casco em arrays ([n] vértices): lado em out[0], confiança em out[1]. Caixa mínima e área
     * pelas mesmas contas de [Geo2.minAreaRect] e [Geo2.area] ([HullOps]); é o laço quente da estimativa da inclinação.
     */
    internal fun measureInto(xs: FloatArray, ys: FloatArray, n: Int, shape: String, iou: Float, out: FloatArray) {
        if (n < 3) { out[0] = 0f; out[1] = 0f; return }
        val box = FloatArray(2); HullOps.minRectWH(xs, ys, n, box)
        // ameia (E): os dentes ficam cobertos pelos ímãs e às vezes não entram na região (sobra só a espinha); a
        // largura e a área dependem disso, o comprimento (espinha = 1 lado do molde) não
        if (shape == "E" && eByLength && sideMode == SideMode.ADAPT) { out[0] = maxOf(box[0], box[1]) / eLong; out[1] = 0f; return }
        val tri = shape == "equilatero" || shape == "retanguloTri" || shape == "isoceles"
        val o = Catalog.sideFromBox(shape, box[0], box[1])
        val a = sqrt(HullOps.area(xs, ys, n) / hullArea(shape))
        if (o <= 0f || a <= 0f) { out[0] = max(o, a); out[1] = 0f; return }
        val d = ln(o / a) / .1f
        val trust = max(((iou - .45f) / .3f).coerceIn(0f, 1f), exp(-d * d))
        out[0] = when (sideMode) {
            // pontas de triângulo perdem proporcionalmente mais pixels (borda + ímãs); 1,1 calibrado nas fotos reais
            SideMode.OBB_TRI -> o * (if (tri) 1.1f else 1f)
            SideMode.OBB -> o
            SideMode.AREA -> a
            SideMode.MIX -> sqrt(o * a)
            SideMode.ADAPT -> exp(trust * ln(a) + (1 - trust) * ln(o))
        }
        out[1] = trust
    }

    fun measure(hull: List<P2>, shape: String, iou: Float = 1f): Float = measureWithTrust(hull, shape, iou).first

    // ---- peças pequenas / ímãs dos dentes (segmentação de peças soltas) ----
    /**
     * Junta à peça os ímãs (marcados pelo Segmenter) colados nela e em nenhuma outra; refaz a forma. Os dentes do E são
     * cobertos de ímãs: sem isso o E virava L/porta/retângulo. Ímã encostado em duas peças fica de fora.
     */
    @JvmField var attachMagnets = true

    private fun withMagnets(seg: Segmentation, magnet: BooleanArray): List<Blob> {
        val w = seg.w; val n = w * seg.h; val lab = seg.labels
        val byId = seg.blobs.associateBy { it.id }
        val add = HashMap<Int, MutableList<Int>>()
        val seen = BooleanArray(n); val q = IntArray(n)
        for (s0 in 0 until n) {
            if (!magnet[s0] || seen[s0] || lab[s0] >= 0) continue
            var head = 0; var tail = 0; q[tail++] = s0; seen[s0] = true; var owner = -1; var many = false
            while (head < tail) {
                val i = q[head++]; val x = i % w
                // vizinhos esquerda, direita, cima, baixo (sem alocar um array por pixel)
                for (d in 0 until 4) {
                    val k = when (d) { 0 -> if (x > 0) i - 1 else -1; 1 -> if (x < w - 1) i + 1 else -1; 2 -> i - w; else -> i + w }
                    if (k < 0 || k >= n) continue
                    val l = lab[k]
                    if (l >= 0) { if (owner < 0) owner = l else if (owner != l) many = true }
                    else if (magnet[k] && !seen[k]) { seen[k] = true; q[tail++] = k }
                }
            }
            if (owner >= 0 && !many && owner in byId) { val list = add.getOrPut(owner) { ArrayList() }; for (j in 0 until tail) list += q[j] }
        }
        if (add.isEmpty()) return seg.blobs
        // cada peça com ímãs: máscara nova e forma refeita, independente das outras (em paralelo, na ordem de sempre)
        val blobs = seg.blobs
        return Par.mapLargestFirst(blobs.size, { if (blobs[it].id in add) blobs[it].bw * blobs[it].bh else 0 }) { k ->
            val b = blobs[k]
            val extra = add[b.id]
            if (extra == null) b else {
                var x0 = b.minX; var y0 = b.minY; var x1 = b.maxX; var y1 = b.maxY
                for (i in extra) { val x = i % w; val y = i / w; x0 = minOf(x0, x); y0 = minOf(y0, y); x1 = maxOf(x1, x); y1 = maxOf(y1, y) }
                val m = Mask(x1 - x0 + 1, y1 - y0 + 1)
                for (yy in 0 until b.bh) for (xx in 0 until b.bw) if (b.mask[xx, yy]) m[b.minX - x0 + xx, b.minY - y0 + yy] = true
                for (i in extra) m[i % w - x0, i / w - y0] = true
                Blob(b.id, b.count, x0, y0, x1, y1, b.color, b.hue, m, ShapeClassifier.classify(m), b.family)
            }
        }
    }

    /** Junta região dentro da caixa de outra quando são o mesmo plástico (aro claro em volta do painel). */
    @JvmField var mergeNestedSame = true

    /**
     * Aro e painel da mesma peça às vezes ficam em regiões separadas grandes (aro borrado com o fundo, resolução alta):
     * o painel fica "dentro" do aro e viraria encaixe + moldura. Mesmo plástico = mesmo matiz visto do fundo (≤ 14°) e
     * brilho parecido (≤ 20); encaixe de verdade numa moldura é outra peça, de outra cor ou bem mais clara/escura.
     */
    private fun mergeNested(blobs: List<Blob>, bg: FloatArray): List<Blob> {
        val out = blobs.toMutableList(); val t = FloatArray(3); val u = FloatArray(3)
        val cosJ = kotlin.math.cos(Math.toRadians(Segmenter.joinHueDeg.toDouble()))
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in out.indices) for (j in out.indices) {
                val a = out[i]; val b = out[j]
                if (i == j || !inside(a, b)) continue
                Segmenter.lab(a.color, t); Segmenter.lab(b.color, u)
                val ax = (t[1] - bg[1]).toDouble(); val ay = (t[2] - bg[2]).toDouble(); val bx = (u[1] - bg[1]).toDouble(); val by = (u[2] - bg[2]).toDouble()
                val la = kotlin.math.hypot(ax, ay); val lb = kotlin.math.hypot(bx, by)
                if (la < 10 || lb < 10 || (ax * bx + ay * by) / (la * lb) < cosJ || abs(t[0] - u[0]) > Segmenter.joinMaxDL) continue
                val x0 = minOf(a.minX, b.minX); val y0 = minOf(a.minY, b.minY); val x1 = maxOf(a.maxX, b.maxX); val y1 = maxOf(a.maxY, b.maxY)
                val m = Mask(x1 - x0 + 1, y1 - y0 + 1)
                for (src in listOf(a, b)) for (yy in 0 until src.bh) for (xx in 0 until src.bw) if (src.mask[xx, yy]) m[src.minX - x0 + xx, src.minY - y0 + yy] = true
                val cnt = a.count + b.count
                fun ch(c: Int, sh: Int) = (c shr sh and 255)
                val color = (0xFF shl 24) or (((ch(a.color, 16) * a.count + ch(b.color, 16) * b.count) / cnt) shl 16) or
                    (((ch(a.color, 8) * a.count + ch(b.color, 8) * b.count) / cnt) shl 8) or ((ch(a.color, 0) * a.count + ch(b.color, 0) * b.count) / cnt)
                Segmenter.hsv(color, t)
                out[j] = Blob(b.id, cnt, x0, y0, x1, y1, color, t[0], m, ShapeClassifier.classify(m), b.family)
                out.removeAt(i); changed = true; break@loop
            }
        }
        return out
    }

    /** Regiões da segmentação já com os ímãs colados e aro/painel do mesmo plástico juntos. */
    internal fun pieceBlobs(seg: Segmentation): List<Blob> {
        var blobs = seg.magnet?.takeIf { attachMagnets }?.let { withMagnets(seg, it) } ?: seg.blobs
        if (mergeNestedSame) seg.bgLab?.let { blobs = mergeNested(blobs, it) }
        return blobs
    }

    /**
     * Foto (ou quadro da contagem ao vivo) de peças SOLTAS → peças. É o detector de fotos com tudo ligado
     * (Segmenter.segment(loose = true)): peças pequenas e finas, ímãs dos dentes do E, aro e painel juntos.
     * Qualquer resolução (os limiares são frações do quadro); 960×720 dá mais forma certa que 480×360 por ~4× o tempo.
     */
    fun detect(px: IntArray, w: Int, h: Int, maxBlobs: Int = 60, cam: GroundCam? = null): List<PhotoPiece> =
        from(Segmenter.segment(px, w, h, maxBlobs = maxBlobs, loose = true), cam)

    fun analyze(seg: Segmentation, cam: GroundCam? = null): Result {
        var blobs = pieceBlobs(seg)
        // painel fora da região / pedaços encostados: só na segmentação comum. A de peças soltas (seg.bgLab) já junta
        // os pedaços da mesma peça pela borda em comum e pela cor do grupo; ali, com os ímãs já colados, "a até 2 px"
        // deixa de querer dizer "mesma peça" e juntava peças vizinhas da mesma cor (foto real "todas": equilátero +
        // porta verdes viravam um retângulo)
        if (fillPanels && seg.bgLab == null) blobs = joinFragments(seg, fillPanels(seg, blobs))
        if (dropCutAtBorder) blobs = blobs.filterNot { cutByBorder(it, seg.w, seg.h) }
        val byId = blobs.associateBy { it.id }
        // no molde, "dentro" também exige o centro dentro do casco da de fora: caixa de peça girada (isósceles) pega a vizinha
        val localHulls = HashMap<Int, List<P2>>()
        fun inHullOf(a: Blob, o: Blob): Boolean {
            val h = localHulls.getOrPut(o.id) { maskHull(o.mask, 0, 0) }; if (h.size < 3) return true
            val p = P2(a.minX + a.bw / 2f - o.minX, a.minY + a.bh / 2f - o.minY)
            for (i in h.indices) if ((h[(i + 1) % h.size] - h[i]).cross(p - h[i]) < 0) return false
            return true
        }
        // e ≥ 85% dos pixels dentro do casco da de fora: num triângulo ou L de viés a caixa tem muito espaço vazio onde
        // cabe pedaço de outra coisa
        val imgHulls = HashMap<Int, List<P2>>()
        fun imgHull(b: Blob) = imgHulls.getOrPut(b.id) { if (b.fit == null) emptyList() else maskHull(b.mask, b.minX, b.minY) }
        val outerOf = blobs.associate { a ->
            a.id to blobs.filter { it !== a && inside(a, it) && (!templateShapes || inHullOf(a, it)) && (!hullInside || fractionInHull(a, imgHull(it)) >= .85f) }.minByOrNull { it.bw * it.bh }
        }
        val shape = HashMap<Int, String>(); val drop = HashSet<Int>(); val parent = HashMap<Int, Int>()
        val pmask = HashMap<Int, Mask>(); val shapeIou = HashMap<Int, Float>()

        // câmera: a do app (intrínsecos + gravidade) ou o palpite de celular; chão dado, estimado ou nenhum
        val c0 = cam ?: GroundCam.guess(seg.w, seg.h)
        val gravity = c0.down != null
        var down: FloatArray? = c0.down
        // peça encostada na borda da foto não serve para medir
        fun isCut(b: Blob) = b.minX <= 0 || b.minY <= 0 || b.maxX >= seg.w - 1 || b.maxY >= seg.h - 1
        var lastEstimate: GroundEstimate.Result? = null
        /** Inclinação pelas peças [cands] com as formas atuais ([GroundEstimate]); encaixe e peça cortada ficam de fora. */
        fun estimateDown(cands: List<Blob>, iouOf: (Blob) -> Float): FloatArray? {
            // encaixe fica dentro da moldura: é a mesma região, contaria duas vezes (e duas peças juntas lidas como
            // moldura+encaixe puxavam a inclinação para 56° numa cena de 7°)
            val use = cands.filter { !isCut(it) && imgHull(it).size >= 3 && shape[it.id] != "encaixe" }
            val useShape = Array(use.size) { shape.getValue(use[it].id) }; val useIou = FloatArray(use.size) { iouOf(use[it]) }
            val est = GroundEstimate.estimateArrays(c0, use.mapIndexed { k, b -> GroundEstimate.Item(imgHull(b), useShape[k], 1f) }, inventorySizes) { xs, ys, n, k, out ->
                measureInto(xs, ys, n, useShape[k], useIou[k], out)
            }
            lastEstimate = est
            return est?.down?.takeIf { est.tiltDeg > 0f }
        }
        var estimated = false
        /** Chão usado para tirar a perspectiva da forma (null = imagem crua). */
        var view: FloorView? = null

        if (templateShapes) {
            // painel/pedaço da PRÓPRIA peça dentro da caixa (mesma cor): entra na máscara dela, não é peça
            for (a in blobs) { val o = outerOf[a.id] ?: continue; if (samePiece(a, o)) drop += a.id }
            // Quem fica dentro de outra peça vira encaixe (grande) ou sai (vidro/reflexo, pequeno); a de fora de um
            // encaixe vira moldura e a de vários vidros, janela. Isso só depende das caixas e das cores, não do molde:
            // decide aqui (as atribuições, na ordem de sempre) e aplica depois da forma. O molde dessas peças não é
            // usado — só o das de fora entra na estimativa da inclinação (1ª passada).
            val forced = LinkedHashMap<Int, String>(); val smallInner = LinkedHashSet<Int>(); val inner = HashSet<Int>()
            for (a in blobs) {
                if (a.id in drop) continue
                val outer = outerOf[a.id] ?: continue
                inner += a.id
                // peça de outra cor dentro de outra: grande = encaixe (só entra no vão octogonal da moldura); pequena =
                // vidro/reflexo (o molde da de fora já viu os furos)
                if (a.bw * a.bh >= outer.bw * outer.bh * .25f) { forced[a.id] = "encaixe"; parent[a.id] = outer.id; forced[outer.id] = "moldura" }
                else smallInner += a.id
            }
            // vidros coloridos da janela de 4 vidros: vários pedacinhos de outra cor dentro da mesma peça
            val dropAfter = drop + smallInner
            blobs.filter { it.id in dropAfter }.groupBy { outerOf[it.id]?.id }
                .forEach { (outer, inn) -> if (outer != null && outer !in dropAfter && inn.count { !samePiece(it, byId.getValue(outer)) } >= 3) forced[outer] = "janela" }
            // máscara da peça inteira (aro + painel): de todas que ficam (o encaixe também, para o giro)
            // e se tem furo fechado ≥ holeGate (só as vazadas concorrem no molde): não depende da perspectiva, vale
            // para as duas passadas
            val withMask = blobs.filter { it.id !in drop && it.id !in smallInner }
            val framesOf = HashMap<Int, Boolean>()
            val masks = Par.mapLargestFirst(withMask.size, { withMask[it].bw * withMask[it].bh }) {
                val m = pieceMask(withMask[it], seg, byId); m to (withMask[it].id !in inner && holeFraction(m) >= holeGate)
            }
            for (i in withMask.indices) { pmask[withMask[i].id] = masks[i].first; framesOf[withMask[i].id] = masks[i].second }
            // forma pelo molde, cada peça independente das outras (em paralelo, maiores primeiro; resultado na peça certa)
            fun classify(view: FloorView?, todo: List<Blob>) {
                val res = Par.mapLargestFirst(todo.size, { todo[it].bw * todo[it].bh }) { i ->
                    val b = todo[i]
                    val warp = view?.shapeWarp(b.minX + b.bw / 2f, b.minY + b.bh / 2f)?.first
                    templateScore(pmask.getValue(b.id), warp, framesOf.getValue(b.id))
                }
                for (i in todo.indices) { val b = todo[i]; val sc = res[i]; shape[b.id] = sc?.first ?: b.fit?.shape ?: "quadrado"; shapeIou[b.id] = sc?.second ?: 0f }
            }
            val unused = inner + forced.keys
            // as que precisam do molde no fim
            val need = blobs.filter { it.id !in drop && it.id !in unused }
            view = down?.let { FloorView.of(c0, it) }
            if (!(down == null && estimateTilt && autoFloor)) classify(view, need)
            else {
                // sem gravidade: estima a inclinação do chão pelas peças e classifica de novo sem a perspectiva.
                // 1ª passada (imagem crua) só com as que a estimativa usa — as de fora (mesmo as que viram
                // moldura/janela); pela inclinação do chão, só as inteiras na foto e com forma
                val free = blobs.filter { it.id !in drop && outerOf[it.id] == null }
                val first = if (shapeTilt == TiltSource.GROUND) free.filter { !isCut(it) && it.fit != null } else free
                classify(null, first)
                down = when (shapeTilt) {
                    TiltSource.GROUND -> estimateDown(free) { shapeIou[it.id] ?: 0f }
                    TiltSource.ISO -> FloorView.estimate(c0, free.filter { shape[it.id] in FloorView.ISOTROPIC }.map { b -> moments(pmask.getValue(b.id), b.minX, b.minY) })?.down
                }
                estimated = true
                val firstIds = first.mapTo(HashSet()) { it.id }
                // com inclinação: todas de novo sem a perspectiva; sem: as que faltaram, na imagem crua (como na 1ª passada)
                val d = down
                if (d != null) { view = FloorView.of(c0, d); classify(view, need) } else classify(null, need.filter { it.id !in firstIds })
            }
            for ((id, s) in forced) shape[id] = s
            drop += smallInner
        } else {
            for (b in blobs) shape[b.id] = b.fit?.shape ?: "quadrado"
            for (a in blobs) {
                val outer = outerOf[a.id] ?: continue
                val big = a.bw * a.bh >= outer.bw * outer.bh * .25f
                when {
                    big -> { shape[a.id] = "encaixe"; parent[a.id] = outer.id; if (shape[outer.id] !in FRAMES) shape[outer.id] = "moldura" }
                    shape[outer.id] in FRAMES || (outer.fit?.gaps?.count { it.enclosed } ?: 0) > 0 -> { drop += a.id; if ((outer.fit?.gaps?.count { it.enclosed } ?: 0) >= 3) shape[outer.id] = "janela" }
                    else -> drop += a.id // resto da mesma peça (reflexo, ímã)
                }
            }
            // vidros da janela de 4 vidros: vários pedacinhos dentro da mesma moldura
            blobs.filter { it.id in drop }.groupBy { outerOf[it.id]?.id }.forEach { (outer, inner) -> if (outer != null && inner.size >= 3) shape[outer] = "janela" }
        }

        val kept = blobs.filter { it.id !in drop }
        val hasInsert = parent.values.toSet()
        val lum = Magnets.luminance(seg)
        // contorno e ímãs de cada peça: independentes das outras (em paralelo; cada uma com o seu canal de croma, que
        // guarda os valores já calculados e não pode ser dividido entre threads — os valores são os mesmos)
        val outlined = kept.filter { it.id !in hasInsert && it.id !in parent }
        val view0 = view
        val finalShape = Par.mapLargestFirst(outlined.size, { outlined[it].bw * outlined[it].bh }) { k ->
            val b = outlined[k]
            val s = shape.getValue(b.id); var cur = s
            // porta/moldura/janela têm contorno QUADRADO (4 cantos, o vão fica dentro). Um entalhe só num contorno de
            // 5 cantos (canto cortado) é o L, não "porta sem soleira"; furo num contorno que não é quadrado é painel
            // que a segmentação perdeu: a forma é a do contorno cheio. Peça cortada pela borda da foto não tem o
            // contorno inteiro: fica como está.
            if (frameOutline && s in FRAMES && !touchesBorder(b, seg.w, seg.h) && !squareOutline(b)) {
                if (templateShapes) {
                    // pelo molde, com o vão fechado (sem furo só concorrem as formas cheias)
                    val warp = view0?.shapeWarp(b.minX + b.bw / 2f, b.minY + b.bh / 2f)?.first
                    templateScore(filledHoles(pmask[b.id] ?: b.mask), warp)?.first?.takeIf { it != "quadrado" }?.let { cur = it }
                } else {
                    val holes = b.fit?.gaps?.count { it.enclosed } ?: 0
                    if (holes == 0) { if (s == "porta") cur = "L" }
                    else ShapeClassifier.classify(filledHoles(b.mask))?.shape?.takeIf { it != "quadrado" }?.let { cur = it }
                }
            }
            // quadrado sem nenhuma ranhura de ímã nos lados: é o encaixe (o único quadrado sem ímã)
            if (magnetCheck && lum != null && cur == "quadrado") {
                val chroma = Magnets.chroma(seg)
                if (chroma != null) {
                    val q = Magnets.outline(b, 4)
                    if (q != null && Magnets.squareish(q)) {
                        val unit = (0 until 4).map { (q[(it + 1) % 4] - q[it]).len() }.average().toFloat()
                        if (Magnets.verdict(Magnets.contrasts(lum, chroma, q, unit)) < 0) cur = "encaixe"
                    }
                }
            }
            cur
        }
        for (k in outlined.indices) shape[outlined[k].id] = finalShape[k]

        val spins = HashMap<Int, Pair<Float, Float>>(); val fits = HashMap<Int, Float>()
        fun snap(a: Float): Float { val q = (Math.PI / 2).toFloat(); val r = Math.round(a / q) * q; return if (abs(a - r) < .14f) r else a }
        // molde exato do catálogo: frente (v para cima → giro = -θ) e cima (v para baixo → giro = θ); cada peça e cada
        // lado independentes (em paralelo). O encaixe segue o giro da moldura onde está: o giro "de cima" dele só seria
        // trocado pelo da moldura (fica de fora quando ninguém o lê); o IoU dele (de frente) vale para a medida
        val withFit = kept.filter { it.fit != null }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        fun followsFrame(b: Blob): Boolean {
            val fr = parent[b.id] ?: return false
            return b.id !in hasInsert && fr in keptIds && byId[fr]?.fit != null
        }
        val tasks = ArrayList<Pair<Blob, Boolean>>()
        for (b in withFit) { tasks += b to true; if (!followsFrame(b)) tasks += b to false }
        val fitRes = Par.mapLargestFirst(tasks.size, { val b = tasks[it].first; (b.bw + 8) * (b.bh + 8) * (if (shape.getValue(b.id) in SQUARE_FIT) 4 else 8 + 4 * b.fit!!.corners.size) }) { i ->
            val (b, up) = tasks[i]; val f = b.fit!!
            TemplateFit.fit(pmask[b.id] ?: b.mask, f.obb, shape.getValue(b.id), vUp = up, corners = f.corners)
        }
        val upOf = HashMap<Int, Pair<Float, Float>>(); val downOf = HashMap<Int, Float>()
        for (i in tasks.indices) { val (b, up) = tasks[i]; if (up) upOf[b.id] = fitRes[i] else downOf[b.id] = fitRes[i].first }
        for (b in withFit) {
            val (thUp, iou) = upOf.getValue(b.id)
            spins[b.id] = snap(-thUp) to (downOf[b.id]?.let { snap(it) } ?: 0f); fits[b.id] = iou
        }
        // encaixe segue o giro da moldura onde está
        for ((ins, frame) in parent) spins[frame]?.let { spins[ins] = it }

        val hulls = kept.map(::imgHull)
        val cut = kept.map(::isCut)
        val shapes = kept.map { shape.getValue(it.id) }
        val ious = kept.map { fits[it.id] ?: 0f }

        // chão para o tamanho: gravidade dada, ou estimada pelas peças (de novo, com as formas finais), ou nenhuma correção
        if (!gravity && estimateTilt && (!estimated || refineTilt)) down = estimateDown(kept) { fits[it.id] ?: 0f }
        val ground = down?.let { GroundRect(c0, it) }
        val sides = FloatArray(kept.size); val weights = FloatArray(kept.size)
        for (i in kept.indices) {
            val b = kept[i]
            if (hulls[i].size < 3) { sides[i] = maxOf(b.bw, b.bh).toFloat(); continue }
            // acima do horizonte do chão (parede, móvel): não é peça deitada; mede na imagem e não entra na escala
            val onFloor = ground?.mapAll(hulls[i])?.let(Geo2::hull)
            val (s, trust) = measureWithTrust(onFloor ?: hulls[i], shapes[i], ious[i])
            sides[i] = s
            // escala da foto: encaixe só existe no médio e não entra; peça cortada pela borda também não; forma em dúvida pesa menos
            weights[i] = if (cut[i] || shapes[i] == "encaixe" || (ground != null && onFloor == null)) 0f else .3f + .7f * trust
        }
        val absUnit = ground?.metersPerUnit?.takeIf { it > 0 }?.let { Catalog.SIDE_M / it } ?: 0f
        val scale = if (jointScale) SizeScale.fit(sides, weights, absUnit, if (inventorySizes) SizeScale.allowedFor(shapes) else null) else legacyScale(sides, FloatArray(kept.size) { if (shapes[it] == "encaixe") 0f else 1f })

        val pieces = kept.mapIndexed { i, b ->
            val s = shapes[i]
            val (spin, spinTop) = spins[b.id] ?: (0f to 0f)
            val size = if (s == "encaixe") "medio" else scale.sizes[i]
            PhotoPiece(b.minX + b.bw / 2f, b.minY + b.bh / 2f, b.bw.toFloat(), b.bh.toFloat(), s, size, b.color, spin, spinTop, sides[i], ious[i],
                if (s == "encaixe") 1f else scale.sizeConf[i])
        }
        return Result(pieces, scale, ground?.tiltDeg ?: 0f, gravity, ground, lastEstimate)
    }

    /** Escala antiga: o lado do quadrado grande = percentil 75 dos lados (a maioria das peças é grande). */
    private fun legacyScale(sides: FloatArray, weights: FloatArray): SizeScale.Fit {
        val s = sides.indices.filter { weights[it] > 0 }.map { sides[it] }.sorted()
        val unit = s.getOrNull((s.size * 3) / 4 - if (s.size % 4 == 0 && s.isNotEmpty()) 1 else 0)?.takeIf { it > 0 } ?: 1f
        return SizeScale.Fit(unit, 0f, true, sides.map { Catalog.sizeForRatio(it / unit) }, FloatArray(sides.size), false)
    }

    /** [a] dentro da caixa de [b] (com folga). */
    internal fun inside(a: Blob, b: Blob): Boolean {
        val ix = minOf(a.maxX, b.maxX) - maxOf(a.minX, b.minX) + 1; val iy = minOf(a.maxY, b.maxY) - maxOf(a.minY, b.minY) + 1
        return ix > 0 && iy > 0 && ix * iy >= a.bw * a.bh * .85f && a.bw * a.bh < b.bw * b.bh
    }

    /**
     * Mesma peça (aro × painel × vincos): mesmo pigmento → matiz a ±12° (o chão visto pelo painel translúcido puxa só
     * uns 2-5°) e brilho parecido (±20 em L). Encaixe de outra cor dentro da moldura falha (foto real "todas": rosa na
     * moldura roxa, 16° e +35 em L).
     */
    private fun samePiece(a: Blob, b: Blob): Boolean {
        val d = abs(a.hue - b.hue) % 360f; if (minOf(d, 360f - d) > 12f) return false
        val t = FloatArray(3); val u = FloatArray(3); Segmenter.lab(a.color, t); Segmenter.lab(b.color, u)
        return abs(t[0] - u[0]) <= 20f
    }

    /**
     * Máscara da peça INTEIRA para o molde: a região + o painel translúcido dela. Aro e painel distam ~20 em a/b (fotos
     * reais) e viram regiões separadas (o painel ainda partido pelos vincos em X): o aro sozinho é um anel e o molde via
     * moldura/porta num quadrado ou triângulo. Entra o que, dentro do casco da região, é primeiro plano com o mesmo matiz
     * (pixel a pixel: sem rótulo, ou de região contida nela); furo de verdade mostra o chão (não é primeiro plano) e
     * continua furo, e encaixe/vidro/peça vizinha de outra cor também.
     */
    internal fun pieceMask(b: Blob, seg: Segmentation, byId: Map<Int, Blob>): Mask {
        val fg = seg.fg ?: return b.mask; val A = seg.A ?: return b.mask; val B = seg.B ?: return b.mask
        val hull = maskHull(b.mask, 0, 0); if (hull.size < 3) return b.mask
        fun inHull(x: Float, y: Float): Boolean {
            for (i in hull.indices) if ((hull[(i + 1) % hull.size] - hull[i]).cross(P2(x, y) - hull[i]) < 0) return false
            return true
        }
        // matiz de referência: média de a/b da própria região
        var ra = 0.0; var rb = 0.0
        for (y in 0 until b.bh) for (x in 0 until b.bw) if (b.mask[x, y]) { val i = (b.minY + y) * seg.w + b.minX + x; if (fg[i]) { ra += A[i]; rb += B[i] } }
        val rl = sqrt(ra * ra + rb * rb); if (rl < 1e-6) return b.mask
        val ux = (ra / rl).toFloat(); val uy = (rb / rl).toFloat()
        val m = Mask(b.bw, b.bh, b.mask.bits.copyOf()); var added = 0
        val ok = HashMap<Int, Boolean>()
        for (y in 0 until b.bh) for (x in 0 until b.bw) {
            if (m[x, y]) continue
            val i = (b.minY + y) * seg.w + b.minX + x
            if (!fg[i]) continue
            val c = sqrt(A[i] * A[i] + B[i] * B[i]); if (c < 8f || (A[i] * ux + B[i] * uy) / c < HUE_COS) continue
            val l = seg.labels[i]
            if (l >= 0 && l != b.id && !ok.getOrPut(l) { byId[l]?.let { o -> inside(o, b) && samePiece(o, b) } ?: false }) continue
            if (!inHull(x + .5f, y + .5f)) continue
            m[x, y] = true; added++
        }
        if (added == 0) return b.mask
        // só o que ficou ligado à região (o resto é outra coisa que caiu no casco)
        val keep = Mask(b.bw, b.bh)
        for (c in m.components(minCells = 1)) {
            var touches = false
            for (k in c.bits.indices) if (c.bits[k] && b.mask.bits[k]) { touches = true; break }
            if (touches) for (k in c.bits.indices) if (c.bits[k]) keep.bits[k] = true
        }
        return keep
    }

    private val HUE_COS = kotlin.math.cos(Math.toRadians(28.0)).toFloat()
    /** Formas que o TemplateFit testa só em 4 giros (estimativa do trabalho de cada giro, para a ordem das tarefas). */
    private val SQUARE_FIT = setOf("quadrado", "moldura", "janela", "encaixe")

    /**
     * O mesmo que Geo2.hull(m.edgePoints() deslocados de ([ox], [oy])), sem a lista de pontos de borda: o casco da
     * união dos pixels é o dos 4 cantos dos pixels das pontas de cada linha (os outros pontos de borda ficam dentro), e
     * com coordenadas inteiras pequenas as contas em float são exatas — mesmo casco, mesmos vértices na mesma ordem
     * (a cadeia monótona começa no menor (x, y), que está entre esses cantos).
     */
    internal fun maskHull(m: Mask, ox: Int, oy: Int): List<P2> {
        val xs = FloatArray(4 * m.h); val ys = FloatArray(4 * m.h); var n = 0
        for (y in 0 until m.h) {
            val row = y * m.w; var x0 = -1; var x1 = -1
            for (x in 0 until m.w) if (m.bits[row + x]) { if (x0 < 0) x0 = x; x1 = x }
            if (x0 < 0) continue
            val l = (x0 + ox).toFloat(); val r = (x1 + 1 + ox).toFloat(); val t = (y + oy).toFloat(); val b = (y + 1 + oy).toFloat()
            xs[n] = l; ys[n++] = t; xs[n] = l; ys[n++] = b; xs[n] = r; ys[n++] = t; xs[n] = r; ys[n++] = b
        }
        if (n == 0) return emptyList()
        val hx = FloatArray(n); val hy = FloatArray(n)
        val k = HullOps.hull(xs, ys, n, hx, hy, LongArray(n))
        return List(k) { P2(hx[it], hy[it]) }
    }

    /** Centro e covariância (cx, cy, cxx, cxy, cyy, em pixels da imagem) da silhueta cheia (peça + furos fechados). */
    internal fun moments(m: Mask, x0: Int, y0: Int): FloatArray {
        val holes = ShapeMatch.enclosedHoles(m)
        var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (y in 0 until m.h) for (x in 0 until m.w) if (m[x, y] || holes[x, y]) {
            val u = x + .5; val v = y + .5; n++; sx += u; sy += v; sxx += u * u; syy += v * v; sxy += u * v
        }
        if (n == 0) return floatArrayOf(x0.toFloat(), y0.toFloat(), 1f, 0f, 1f)
        val mx = sx / n; val my = sy / n
        return floatArrayOf((x0 + mx).toFloat(), (y0 + my).toFloat(), (sxx / n - mx * mx).toFloat(), (sxy / n - mx * my).toFloat(), (syy / n - my * my).toFloat())
    }

    /** Fração da silhueta cheia que é furo fechado (o chão aparecendo por dentro: moldura, janela, porta). */
    internal fun holeFraction(m: Mask): Float {
        val h = ShapeMatch.enclosedHoles(m).count()
        return h / maxOf(1f, (m.count() + h).toFloat())
    }

    /**
     * Forma pelo molde. Furo de verdade (≥ [holeGate] da silhueta) só existe nas peças vazadas, então só elas concorrem.
     * Entre as vazadas a forma do FURO decide (octógono × arco da porta × 4 vidros): o IoU dos furos entra com peso
     * [holeWeight] — no IoU da peça inteira a borda do furo pesa pouco perto do contorno externo (borrão, ímãs).
     */
    internal fun templateShape(m: Mask, warp: FloatArray? = null): String? = templateScore(m, warp)?.first

    /** [templateShape] e o IoU do molde escolhido (0 quando a forma veio da regra da faixa comprida). */
    internal fun templateScore(m: Mask, warp: FloatArray? = null): Pair<String, Float>? = templateScore(m, warp, holeFraction(m) >= holeGate)

    /** [templateScore] com [frames] (furo ≥ [holeGate]: só as vazadas concorrem) já calculado para [m]. */
    internal fun templateScore(m: Mask, warp: FloatArray?, frames: Boolean): Pair<String, Float>? {
        val scores = ShapeMatch.match(m, if (frames) FRAMES else ShapeMatch.LOOSE, warp)
        // faixa comprida (≥ 3:1) que não fecha com molde nenhum só pode ser o E: nenhuma outra peça passa de 2:1, e no
        // E os dentes finos ficam quase todos cobertos pelos ímãs (escuros, fora da região) e sobra a espinha. (Retângulo
        // muito de viés também fica comprido, mas ainda fecha com o molde dele.)
        if (!frames && stripToE && (scores.firstOrNull()?.iou ?: 0f) < .7f && ShapeMatch.elongation(m, warp) >= 3f) return "E" to 0f
        // porta × moldura em peça pequena (vão de ~15 px): o IoU quase empata; o alongamento do vão decide melhor
        // (porta ~1,4 × octógono 1,0; corte em 1,2 como nas regras antigas)
        val e = if (frames) ShapeMatch.holeElongation(m, warp) else 0f
        fun prior(s: String) = when (s) { "porta" -> elongWeight * (e - 1.2f).coerceIn(-.2f, .2f); "moldura" -> elongWeight * (1.2f - e).coerceIn(-.2f, .2f); else -> 0f }
        return scores.maxByOrNull { it.iou + holeWeight * it.holeIou + (if (frames) prior(it.shape) else 0f) }?.let { it.shape to it.iou }
    }

    private fun fractionInHull(a: Blob, hull: List<P2>): Float {
        if (hull.size < 3) return 0f
        var n = 0; var inn = 0
        for (y in 0 until a.bh) for (x in 0 until a.bw) if (a.mask[x, y]) {
            n++
            val p = P2(a.minX + x + .5f, a.minY + y + .5f)
            if (hull.indices.all { i -> (hull[(i + 1) % hull.size] - hull[i]).cross(p - hull[i]) >= 0 }) inn++
        }
        return if (n == 0) 0f else inn.toFloat() / n
    }

    // ---------------- painel fora da região ----------------

    private fun lab(c: Int) = FloatArray(3).also { Segmenter.lab(c, it) }

    /**
     * Na peça real (e na cena realista) o aro é mais claro e o painel translúcido mais saturado; quando a segmentação
     * não atravessa a passagem aro → painel (ou os vincos claros partem o painel em 4 triângulos), sobra só o aro: um
     * anel com "furo" que vira moldura/porta/janela, e os pedaços do painel viram encaixe/vidros/falsos positivos.
     * Numa porta/moldura o anel interno (painel + aro do vão) também fica de fora e o vão parece bem maior.
     * Furo de verdade mostra o CHÃO; o falso está cheio de primeiro plano da cor da peça. Dentro de cada vão do casco
     * (furo fechado ou entalhe: com o aro partido nos ímãs o painel perdido abre para fora),
     * o que é primeiro plano com a cor da peça e tem espessura (sobrevive a uma erosão: a franja borrada de 1 px do
     * vão de verdade não sobrevive) volta para a peça; sobra pequena também; regiões que estavam ali são absorvidas.
     * Peça de outra cor dentro do furo (encaixe na moldura, vidros) fica de fora.
     */
    internal fun fillPanels(seg: Segmentation, blobs: List<Blob>): List<Blob> {
        val rgb = seg.rgb ?: return blobs; val fg = seg.fg ?: return blobs
        val w = seg.w
        val absorbed = HashSet<Int>(); val replaced = HashMap<Int, Blob>()
        val byId = blobs.associateBy { it.id }
        val t = FloatArray(3)
        for (b in blobs) {
            if (b.count < 60) continue
            // vãos dentro do casco convexo: furos fechados e entalhes (o aro partido no ímã abre o painel para fora)
            val hull = maskHull(b.mask, 0, 0)
            val inHull = Mask(b.mask.w, b.mask.h)
            for (y in 0 until b.mask.h) for (x in 0 until b.mask.w) {
                val p = P2(x + .5f, y + .5f)
                inHull[x, y] = hull.size >= 3 && hull.indices.all { k -> (hull[(k + 1) % hull.size] - hull[k]).cross(p - hull[k]) >= 0 }
            }
            val gaps = Mask(b.mask.w, b.mask.h, BooleanArray(b.mask.w * b.mask.h) { inHull.bits[it] && !b.mask.bits[it] })
            var filled: Mask? = null; var add = 0
            val inHole = HashMap<Int, Int>() // outra região → pixels dela que voltaram para a peça
            val blobLab = lab(b.color)
            for (c in gaps.components(minCells = 4)) {
                var enclosed = true
                for (y in 0 until c.h) for (x in 0 until c.w) if (c[x, y] && (!inHull[x - 1, y] || !inHull[x + 1, y] || !inHull[x, y - 1] || !inHull[x, y + 1])) { enclosed = false; break }
                // pixels do furo com a cor da peça
                val same = Mask(c.w, c.h); var nSame = 0; var n = 0
                for (y in 0 until c.h) for (x in 0 until c.w) if (c[x, y]) {
                    n++
                    val i = (b.minY + y) * w + b.minX + x
                    if (!fg[i]) continue
                    Segmenter.lab(rgb[i], t)
                    if (samePiece(t, blobLab)) { same[x, y] = true; nSame++ }
                }
                if (nSame < 4) continue
                // só o que tem espessura (painel), não a franja borrada da beira de um vão de verdade
                val thick = if (enclosed && nSame >= n * .85f) same else same.erode().dilate().let { o -> for (k in o.bits.indices) o.bits[k] = o.bits[k] && same.bits[k]; o }
                if (thick.count() < (if (enclosed) 4 else maxOf(8, b.count / 25))) continue
                val m = filled ?: Mask(b.mask.w, b.mask.h, b.mask.bits.copyOf()).also { filled = it }
                for (y in 0 until c.h) for (x in 0 until c.w) if (thick[x, y]) {
                    m[x, y] = true; add++
                    val l = seg.labels[(b.minY + y) * w + b.minX + x]
                    if (l >= 0 && l != b.id) inHole.merge(l, 1, Int::plus)
                }
                // sobra pequena do furo (vincos, ruído): fecha também
                if (!enclosed) continue
                val rest = Mask(c.w, c.h); for (k in c.bits.indices) rest.bits[k] = c.bits[k] && !m.bits[k]
                for (r in rest.components(minCells = 1)) if (r.count() <= maxOf(4, n / 12)) for (k in r.bits.indices) if (r.bits[k]) { m.bits[k] = true; add++ }
            }
            val m = filled ?: continue
            for ((id, k) in inHole) { val o = byId[id] ?: continue; if (k >= o.count * .8f) absorbed += id }
            replaced[b.id] = Blob(b.id, b.count + add, b.minX, b.minY, b.maxX, b.maxY, b.color, b.hue, m, ShapeClassifier.classify(m), b.family)
        }
        if (replaced.isEmpty()) return blobs
        return blobs.filter { it.id !in absorbed }.map { replaced[it.id] ?: it }
    }

    /** Mesma peça pela cor média: aro e painel distam ~10-15 em a/b e ~10 em L*; encaixe rosa × moldura roxa (L* 35) e vidros × janela (a/b 30) não. */
    private fun samePiece(a: FloatArray, b: FloatArray) = hypot(a[1] - b[1], a[2] - b[2]) <= 22f && abs(a[0] - b[0]) <= 25f

    /**
     * Regiões de cor parecida encostadas (até 2 px: vinco claro, sulco entre aro e painel) são a mesma peça. A
     * segmentação já junta peças da mesma cor que se tocam, então duas regiões assim só ficam separadas quando um salto
     * de cor DENTRO de uma peça parou o crescimento (aro → painel; vinco partindo o painel). Junta as máscaras (fechando
     * a fresta de 1-2 px entre elas) e reclassifica a forma.
     */
    internal fun joinFragments(seg: Segmentation, blobs: List<Blob>): List<Blob> {
        if (blobs.size < 2) return blobs
        val w = seg.w; val h = seg.h
        val owner = IntArray(w * h) { -1 }
        blobs.forEachIndexed { k, b -> for (y in 0 until b.bh) for (x in 0 until b.bw) if (b.mask[x, y]) owner[(b.minY + y) * w + b.minX + x] = k }
        val contact = HashMap<Long, Int>() // par de regiões → pixels encostados (até 2 px)
        for (y in 0 until h) for (x in 0 until w) {
            val a = owner[y * w + x]; if (a < 0) continue
            for (d in 1..2) {
                if (x + d < w) { val b = owner[y * w + x + d]; if (b >= 0 && b != a) contact.merge(minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong(), 1, Int::plus) }
                if (y + d < h) { val b = owner[(y + d) * w + x]; if (b >= 0 && b != a) contact.merge(minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong(), 1, Int::plus) }
            }
        }
        val colors = blobs.map { lab(it.color) }
        val root = IntArray(blobs.size) { it }
        fun find(i: Int): Int { var r = i; while (root[r] != r) r = root[r]; return r }
        for ((key, n) in contact) {
            val a = (key shr 32).toInt(); val b = (key and 0xFFFFFFFFL).toInt()
            if (n < 8 || !samePiece(colors[a], colors[b])) continue
            val ra = find(a); val rb = find(b); if (ra != rb) root[maxOf(ra, rb)] = minOf(ra, rb)
        }
        val groups = blobs.indices.groupBy { find(it) }
        if (groups.size == blobs.size) return blobs
        return groups.values.map { g ->
            if (g.size == 1) return@map blobs[g[0]]
            val parts = g.map { blobs[it] }
            val x0 = parts.minOf { it.minX }; val y0 = parts.minOf { it.minY }; val x1 = parts.maxOf { it.maxX }; val y1 = parts.maxOf { it.maxY }
            val u = Mask(x1 - x0 + 1, y1 - y0 + 1)
            for (p in parts) for (y in 0 until p.bh) for (x in 0 until p.bw) if (p.mask[x, y]) u[p.minX - x0 + x, p.minY - y0 + y] = true
            val closed = u.dilate().dilate().erode().erode()
            for (k in u.bits.indices) if (closed.bits[k]) u.bits[k] = true
            val cnt = parts.sumOf { it.count }
            fun ch(sh: Int) = parts.sumOf { (it.color shr sh and 255) * it.count } / cnt
            val color = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            val hsv = FloatArray(3); Segmenter.hsv(color, hsv)
            val main = parts.maxBy { it.count }
            Blob(main.id, cnt, x0, y0, x1, y1, color, hsv[0], u, ShapeClassifier.classify(u), main.family)
        }
    }

    // ---------------- peça cortada pela borda da foto ----------------

    /**
     * Região que encosta na borda da foto: a peça continua fora do quadro. Numa figura com centro de simetria cortada
     * por uma reta, o centro fica fora quando a corda do corte passa de 2× a profundidade da parte visível; a
     * segmentação ainda come o aro e os ímãs do lado oposto ao corte (a profundidade medida encolhe) e triângulo não
     * tem centro de simetria, então o corte é 1,5×. Pedaço assim não é contado: forma e tamanho de um pedaço são
     * chute, e a peça aparece inteira na foto do lado.
     */
    fun cutByBorder(b: Blob, w: Int, h: Int): Boolean {
        val m = b.mask
        fun check(chord: Int, depth: Int) = chord >= 4 && chord > 1.5f * depth
        if (b.minY == 0) { var c = 0; for (x in 0 until m.w) if (m[x, 0]) c++; if (check(c, b.bh)) return true }
        if (b.maxY == h - 1) { var c = 0; for (x in 0 until m.w) if (m[x, m.h - 1]) c++; if (check(c, b.bh)) return true }
        if (b.minX == 0) { var c = 0; for (y in 0 until m.h) if (m[0, y]) c++; if (check(c, b.bw)) return true }
        if (b.maxX == w - 1) { var c = 0; for (y in 0 until m.h) if (m[m.w - 1, y]) c++; if (check(c, b.bw)) return true }
        return false
    }

    // ---------------- contorno ----------------

    /**
     * O contorno externo é um quadrilátero (quadrado visto de viés): 4 cantos com lados opostos parecidos que explicam
     * o casco (o borrão arredonda os cantos e o quadrilátero dos cantos do casco fica com 84-98% da área; o L, com o
     * canto cortado, não fecha lados opostos parecidos).
     */
    private fun squareOutline(b: Blob): Boolean {
        val q = Magnets.outline(b, 4) ?: return false
        if (!Magnets.squareish(q)) return false
        val hull = maskHull(b.mask, 0, 0)
        return Geo2.area(q) >= Geo2.area(hull) * .75f
    }

    private fun touchesBorder(b: Blob, w: Int, h: Int) = b.minX == 0 || b.minY == 0 || b.maxX == w - 1 || b.maxY == h - 1

    /** Máscara com todos os furos fechados (o que não encosta na borda do recorte). */
    private fun filledHoles(m: Mask): Mask {
        val out = Mask(m.w, m.h, m.bits.copyOf())
        val inv = Mask(m.w, m.h, BooleanArray(m.w * m.h) { !m.bits[it] })
        for (c in inv.components(minCells = 1)) {
            var border = false
            for (y in 0 until c.h) for (x in 0 until c.w) if (c[x, y] && (x == 0 || y == 0 || x == c.w - 1 || y == c.h - 1)) { border = true; break }
            if (!border) for (k in c.bits.indices) if (c.bits[k]) out.bits[k] = true
        }
        return out
    }
}

/**
 * Laço paralelo para trabalho independente por índice (forma de cada peça pelo molde, giro de cada peça, custo de
 * cada hipótese de inclinação): cada f(i) só lê dados compartilhados e devolve o seu resultado, que vai para a posição
 * i. A saída não depende da ordem nem de quantas threads rodaram: é a mesma do laço sequencial.
 * A thread que chama também trabalha e só espera os índices que outras threads já pegaram (sem deadlock em chamada
 * aninhada nem com o pool ocupado).
 */
internal object Par {
    /** false = tudo sequencial na thread que chama. */
    @JvmField @Volatile var enabled = true
    /**
     * Threads ajudantes (além da que chama). No celular: núcleos − 2, até 3 — a câmera, o preview e a tela também
     * precisam de núcleo, e em big.LITTLE os núcleos lentos alongam o caminho crítico. No PC (testes): núcleos − 1, até 7.
     * O resultado não depende do número de threads (golden em paralelo e sequencial).
     */
    private val helpers = Runtime.getRuntime().availableProcessors().let { n ->
        if (System.getProperty("java.vendor").orEmpty().contains("Android", ignoreCase = true)) (n - 2).coerceIn(0, 3) else (n - 1).coerceIn(0, 7)
    }
    private val pool: java.util.concurrent.ExecutorService by lazy {
        // prioridade fixa: sem isso as ajudantes herdam a da primeira thread que chamou (às vezes de fundo, baixa)
        java.util.concurrent.Executors.newFixedThreadPool(maxOf(1, helpers)) { r -> Thread(r, "brascan-par").apply { isDaemon = true; priority = Thread.NORM_PRIORITY } }
    }

    /**
     * f(i) para cada i, cada um pego por uma thread na ordem 0, 1, 2… (sequencial: nessa ordem). Para quem precisa
     * de algo além de "resultado na posição i" (poda com o melhor já achado): o que f faz com os dados compartilhados
     * tem que ser seguro entre threads e não pode mudar o resultado final conforme a ordem.
     */
    fun forEach(n: Int, f: (Int) -> Unit) = run(n, f)

    private fun run(n: Int, f: (Int) -> Unit) {
        if (n <= 0) return
        if (n == 1 || !enabled || helpers == 0) { for (i in 0 until n) f(i); return }
        val next = java.util.concurrent.atomic.AtomicInteger()
        val left = java.util.concurrent.CountDownLatch(n)
        val err = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val work = Runnable {
            while (true) {
                val i = next.getAndIncrement(); if (i >= n) break
                try { f(i) } catch (t: Throwable) { err.compareAndSet(null, t) } finally { left.countDown() }
            }
        }
        repeat(minOf(helpers, n - 1)) { pool.execute(work) }
        work.run()
        // espera sem largar no meio (os ajudantes ainda escrevem nos resultados); a interrupção fica marcada
        var interrupted = false
        while (true) { try { left.await(); break } catch (e: InterruptedException) { interrupted = true } }
        if (interrupted) Thread.currentThread().interrupt()
        err.get()?.let { throw it }
    }

    fun <T> map(n: Int, f: (Int) -> T): List<T> {
        val out = arrayOfNulls<Any?>(n)
        run(n) { out[it] = f(it) }
        @Suppress("UNCHECKED_CAST") return out.asList() as List<T>
    }

    fun mapFloat(n: Int, f: (Int) -> Float): FloatArray { val out = FloatArray(n); run(n) { out[it] = f(it) }; return out }

    /**
     * [map] com as tarefas começando pelas de maior [cost] (estimativa do trabalho): a mais pesada não fica para o
     * fim com as outras threads paradas. O resultado continua na posição i.
     */
    fun <T> mapLargestFirst(n: Int, cost: (Int) -> Int, f: (Int) -> T): List<T> {
        if (n <= 1 || !enabled || helpers == 0) return map(n, f)
        val c = IntArray(n) { cost(it) }
        val ord = (0 until n).sortedByDescending { c[it] }
        val out = arrayOfNulls<Any?>(n)
        run(n) { val i = ord[it]; out[i] = f(i) }
        @Suppress("UNCHECKED_CAST") return out.asList() as List<T>
    }
}
