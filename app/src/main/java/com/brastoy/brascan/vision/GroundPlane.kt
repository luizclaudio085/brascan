package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Câmera da foto, para levar o contorno das peças deitadas ao plano do chão. Referencial de visão: x para a direita,
 * y para baixo, z para a frente (pixel = c + f · (x/z, y/z)). [down]: gravidade (para baixo) nesse referencial;
 * null = estimar pelas próprias peças. [heightM]: altura da câmera ao chão (m); > 0 dá escala absoluta.
 */
class GroundCam(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val down: FloatArray? = null, val heightM: Float = 0f) {
    companion object {
        /**
         * Sem intrínsecos (foto da galeria): câmera principal de celular, ~25 mm equivalentes → foco ≈ 0,78 do lado
         * maior da imagem, centro óptico no meio.
         */
        fun guess(w: Int, h: Int): GroundCam { val f = .78f * max(w, h); return GroundCam(f, f, w / 2f, h / 2f) }

        /**
         * De um quadro do SLAM/AR: intrínsecos na resolução do quadro e pose câmera→mundo (coluna-maior, câmera
         * olhando para -Z, Y do mundo para cima — o mesmo de [FrameSample]). A gravidade vem da pose.
         */
        fun fromFrame(f: FrameSample, heightM: Float = 0f): GroundCam {
            val m = f.camToWorld
            // mundo (0,-1,0) no referencial de visão: x = direita (col 0), y = -cima (col 1), z = frente (-col 2)
            return GroundCam(f.fx, f.fy, f.cx, f.cy, floatArrayOf(-m[1], m[5], m[9]), heightM)
        }

        /**
         * Do sensor de gravidade do Android (TYPE_GRAVITY/acelerômetro parado: aponta para CIMA, referencial do
         * aparelho x direita, y para o topo da tela, z saindo da tela), câmera traseira e imagem na mesma orientação
         * da tela em retrato (girar o vetor junto se a imagem vier deitada).
         */
        fun fromDeviceGravity(fx: Float, fy: Float, cx: Float, cy: Float, gx: Float, gy: Float, gz: Float, heightM: Float = 0f) =
            GroundCam(fx, fy, cx, cy, floatArrayOf(-gx, gy, gz), heightM)
    }
}

/**
 * Retificação do chão: câmera virtual no mesmo lugar olhando na direção da gravidade. Todo ponto do chão fica à
 * mesma distância dela, então a escala é a mesma na imagem inteira (some a perspectiva: peça longe deixa de parecer
 * menor; quadrado visto de viés volta a ser quadrado). Unidade: pixels da câmera real à distância da altura
 * (lado retificado = lado em m × fx / altura).
 */
class GroundRect(val cam: GroundCam, down: FloatArray) {
    private val gx: Float; private val gy: Float; private val gz: Float
    private val ax: Float; private val ay: Float; private val az: Float
    private val bx: Float; private val by: Float; private val bz: Float

    init {
        val l = sqrt(down[0] * down[0] + down[1] * down[1] + down[2] * down[2]).coerceAtLeast(1e-9f)
        gx = down[0] / l; gy = down[1] / l; gz = down[2] / l
        // e1 = x da câmera sem a componente da gravidade; e2 = g × e1
        var x = 1f - gx * gx; var y = -gx * gy; var z = -gx * gz
        val n = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-9f); x /= n; y /= n; z /= n
        ax = x; ay = y; az = z
        bx = gy * az - gz * ay; by = gz * ax - gx * az; bz = gx * ay - gy * ax
    }

    /** Inclinação da câmera: ângulo entre o eixo óptico e a vertical (graus). */
    val tiltDeg get() = Math.toDegrees(acos(gz.coerceIn(-1f, 1f).toDouble())).toFloat()

    /** Metros por unidade retificada (0 sem altura conhecida). */
    val metersPerUnit get() = if (cam.heightM > 0) cam.heightM / cam.fx else 0f

    /** Ponto da imagem (pixels contínuos) → chão retificado; null acima do horizonte. */
    fun map(x: Float, y: Float): P2? {
        val rx = (x - cam.cx) / cam.fx; val ry = (y - cam.cy) / cam.fy
        val q = gx * rx + gy * ry + gz
        if (q < .05f) return null
        return P2(cam.fx * (ax * rx + ay * ry + az) / q, cam.fx * (bx * rx + by * ry + bz) / q)
    }

    fun mapAll(pts: List<P2>): List<P2>? { val out = ArrayList<P2>(pts.size); for (p in pts) out += map(p.x, p.y) ?: return null; return out }

    companion object {
        /** Gravidade de uma câmera inclinada [tiltDeg] com o chão "descendo" na direção [azDeg] da imagem (0° = para baixo). */
        fun down(tiltDeg: Float, azDeg: Float): FloatArray {
            val t = Math.toRadians(tiltDeg.toDouble()); val a = Math.toRadians(azDeg.toDouble())
            return floatArrayOf((sin(t) * sin(a)).toFloat(), (sin(t) * cos(a)).toFloat(), cos(t).toFloat())
        }
    }
}

/**
 * Inclinação do chão SEM gravidade, pelas próprias peças: no chão visto de cima o casco de um quadrado, triângulo
 * equilátero, moldura, janela ou porta é igual em toda direção (momentos de 2ª ordem iguais) e o de um retângulo
 * tem a proporção do catálogo; com a câmera inclinada eles achatam na direção da inclinação. Procura a gravidade
 * (inclinação, direção) que, retificando, devolve a cada peça a anisotropia do catálogo — e, de quebra, faz os lados
 * caírem nos 3 tamanhos (a perspectiva some: peça longe deixa de parecer menor). Prior fraco a favor de pouca
 * inclinação (foto de peças no chão é tirada de cima).
 */
object GroundEstimate {
    class Item(val hull: List<P2>, val shape: String, val weight: Float)
    class Result(val down: FloatArray, val tiltDeg: Float, val azDeg: Float, val cost: Float, val flatCost: Float)

    /** Ruído da anisotropia do casco medida (pixels da borda, borrão). */
    private const val SIGMA_AN = .05f
    /** Prior da inclinação (graus): custo (t / isto)². */
    private const val TILT_PRIOR = 30f
    private const val MAX_ITEMS = 30
    @JvmField var useScaleTerm = true
    /** Teto do custo de anisotropia por peça (σ², perda truncada em 3σ): forma errada ou duas peças juntas não puxam a inclinação. */
    @JvmField var anTrunc = 9f
    /**
     * Mínimo de peças para estimar. Com 2 a inclinação (2 incógnitas) e a escala se confundem: qualquer razão de
     * tamanhos entre as duas é "explicada" por uma inclinação que põe a menor mais longe (foto real "tamanhos" em
     * 640 px: 26° deixava o quadrado médio do tamanho do grande). Com 3+ as razões entre elas não fecham todas ao
     * mesmo tempo com uma inclinação errada.
     */
    @JvmField var minItems = 3
    /** Ganho mínimo de custo (plano − inclinado) para aceitar a inclinação; abaixo disso fica a foto crua (0 = sempre aceita). */
    @JvmField var minGain = 0f

    /**
     * [side]: lado medido de um casco já retificado da peça de índice k em [items] e a confiança na forma (0..1) —
     * o mesmo que o detector usa para o tamanho. [inventory]: no termo da escala, cada peça só pode ter os tamanhos
     * em que a forma dela existe ([Catalog.sizesOf], inventário do simulador).
     */
    fun estimate(cam: GroundCam, items: List<Item>, inventory: Boolean = false, side: (List<P2>, Int) -> Pair<Float, Float>): Result? {
        // as MAX_ITEMS maiores bastam (peça pequena tem anisotropia ruidosa) e seguram o custo com 60 peças
        val idx = items.indices.filter { items[it].weight > 0 && items[it].hull.size >= 3 }.sortedByDescending { Geo2.area(items[it].hull) }.take(MAX_ITEMS)
        val its = idx.map { items[it] }
        if (its.size < maxOf(2, minItems)) return null
        val an0 = FloatArray(its.size) { PolyMoments.catalogAnisotropy(its[it].shape) }
        val ws = FloatArray(its.size) { its[it].weight }
        val allowed = if (inventory) SizeScale.allowedFor(its.map { it.shape }) else null
        fun cost(t: Float, a: Float): Float {
            val rect = GroundRect(cam, GroundRect.down(t, a))
            var c = 0f
            val sides = FloatArray(its.size); val sw = FloatArray(its.size)
            for (i in its.indices) {
                val h = rect.mapAll(its[i].hull) ?: return Float.MAX_VALUE // peça acima do horizonte: hipótese impossível
                val r = (PolyMoments.anisotropy(h) - an0[i]) / SIGMA_AN
                c += ws[i] * minOf(r * r, anTrunc)
                if (useScaleTerm) { val (sd, trust) = side(Geo2.hull(h), idx[i]); sides[i] = sd; sw[i] = ws[i] * (.3f + .7f * trust) }
            }
            if (useScaleTerm) c -= 2 * SizeScale.bestLogLikelihood(sides, sw, allowed)
            val tp = t / TILT_PRIOR
            return c + tp * tp
        }
        val flat = cost(0f, 0f)
        var bt = 0f; var ba = 0f; var bc = flat
        // grade grossa (o custo varia devagar com a direção) e dois refinos em volta do melhor
        for (t in 5..55 step 5) for (a in 0 until 360 step 30) { val c = cost(t.toFloat(), a.toFloat()); if (c < bc) { bc = c; bt = t.toFloat(); ba = a.toFloat() } }
        for ((st, sa) in listOf(2f to 10f, 1f to 3f)) {
            if (bt <= 0) break
            val t0 = bt; val a0 = ba
            for (dt in -2..2) for (da in -2..2) {
                val t = t0 + dt * st; if (t <= 0 || (dt == 0 && da == 0)) continue
                val c = cost(t, a0 + da * sa); if (c < bc) { bc = c; bt = t; ba = a0 + da * sa }
            }
        }
        if (bt > 0 && flat - bc < minGain) return Result(GroundRect.down(0f, 0f), 0f, 0f, flat, flat)
        return Result(GroundRect.down(bt, ba), bt, ((ba % 360) + 360) % 360, bc, flat)
    }
}

/** Momentos de 2ª ordem de polígono (fórmulas fechadas por arestas). */
object PolyMoments {
    /** Área, centroide e momentos centrais (μxx, μyy, μxy) por unidade de área. */
    fun of(p: List<P2>): FloatArray {
        var a = 0.0; var cx = 0.0; var cy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in p.indices) {
            val x0 = p[i].x.toDouble(); val y0 = p[i].y.toDouble(); val x1 = p[(i + 1) % p.size].x.toDouble(); val y1 = p[(i + 1) % p.size].y.toDouble()
            val c = x0 * y1 - x1 * y0
            a += c; cx += (x0 + x1) * c; cy += (y0 + y1) * c
            sxx += (x0 * x0 + x0 * x1 + x1 * x1) * c; syy += (y0 * y0 + y0 * y1 + y1 * y1) * c
            sxy += (x0 * y1 + 2 * x0 * y0 + 2 * x1 * y1 + x1 * y0) * c
        }
        a /= 2
        if (abs(a) < 1e-9) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        cx /= 6 * a; cy /= 6 * a
        val mxx = sxx / 12 / a - cx * cx; val myy = syy / 12 / a - cy * cy; val mxy = sxy / 24 / a - cx * cy
        return floatArrayOf(abs(a).toFloat(), cx.toFloat(), cy.toFloat(), mxx.toFloat(), myy.toFloat(), mxy.toFloat())
    }

    /** 0 = igual em toda direção (quadrado, triângulo equilátero, octógono); retângulo 2:1 = 0,6. */
    fun anisotropy(p: List<P2>): Float {
        val m = of(p); val tr = m[3] + m[4]; if (tr <= 0) return 0f
        return sqrt((m[3] - m[4]) * (m[3] - m[4]) + 4 * m[5] * m[5]) / tr
    }

    private val catalogCache = HashMap<String, Float>()

    /** Anisotropia do casco convexo da forma do catálogo (o que o casco da máscara deve ter visto de cima). */
    fun catalogAnisotropy(shape: String): Float = synchronized(catalogCache) {
        catalogCache.getOrPut(shape) { anisotropy(Geo2.hull(Catalog.polygon(shape).map { P2(it.first, it.second) })) }
    }
}
