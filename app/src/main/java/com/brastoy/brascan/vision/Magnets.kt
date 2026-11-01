package com.brastoy.brascan.vision

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Ímãs de uma peça Brastoy numa foto, a partir do contorno externo (cantos do casco): toda peça convexa tem 2 ímãs
 * por lado, a 0,275 e 0,725 do comprimento e 0,035 lado para dentro (Catalog.magnets, igual ao simulador); na foto são
 * ranhuras cinza/escuras no aro colorido. O encaixe (plástico, sem ímã) não tem; caixa, mão, sombra, pedaço de painel
 * também não. Mesma ideia de PieceSlam.magnetsVisible, mas sem pose 3D: só os cantos do contorno na imagem.
 */
object Magnets {
    /**
     * Lado (px) abaixo do qual não se afirma nada sobre os ímãs: a ranhura tem 0,044 do lado de largura, e abaixo
     * de ~1,75 px (lado < 40 px) o borrão de 1-2 px da câmera pode apagá-la (nas cenas, peças pequenas de 27-29 px
     * já pareciam "sem ímã"). Ausência de ímã é afirmação negativa: só vale com folga de resolução.
     */
    @JvmField var minUnitPx = 40f

    /** Cantos do contorno externo da região, em coordenadas da imagem (casco simplificado até sobrar [want] cantos). */
    fun outline(b: Blob, want: Int): List<P2>? {
        val hull = Geo2.hull(b.mask.edgePoints()); if (hull.size < want) return null
        for (f in floatArrayOf(.035f, .025f, .05f, .018f, .07f, .1f)) {
            val c = Geo2.simplifyClosed(hull, Geo2.perimeter(hull) * f)
            if (c.size == want) return c.map { P2(it.x + b.minX, it.y + b.minY) }
        }
        return null
    }

    /** Canal por pixel (luminância ou croma) da imagem segmentada; null fora da imagem. */
    fun interface Channel { fun at(x: Int, y: Int): Float? }

    /** Luminância L* da segmentação. */
    fun luminance(seg: Segmentation): Channel? {
        val L = seg.L ?: return null; val w = seg.w; val h = seg.h
        return Channel { x, y -> if (x < 0 || y < 0 || x >= w || y >= h) null else L[y * w + x] }
    }

    /**
     * Croma (saturação em Lab) da foto: a ranhura do ímã é cinza (sem pigmento) e o aro é colorido. Nas peças reais
     * escuras (azul, roxo) a ranhura é MAIS CLARA que o aro, então a luminância sozinha não serve; o croma cai nas duas.
     */
    fun chroma(seg: Segmentation): Channel? {
        val px = seg.rgb ?: return null; val w = seg.w; val h = seg.h
        val t = FloatArray(3); val cache = HashMap<Int, Float>()
        return Channel { x, y ->
            if (x < 0 || y < 0 || x >= w || y >= h) null else cache.getOrPut(y * w + x) { Segmenter.lab(px[y * w + x], t); hypot(t[1], t[2]) }
        }
    }

    /** Valor bilinear num ponto (coordenadas de borda de pixel: o centro do pixel x está em x + 0,5). */
    private fun sample(ch: Channel, x: Float, y: Float): Float? {
        val fx = x - .5f; val fy = y - .5f
        val x0 = kotlin.math.floor(fx).toInt(); val y0 = kotlin.math.floor(fy).toInt()
        val ax = fx - x0; val ay = fy - y0
        val a = ch.at(x0, y0) ?: return null; val b = ch.at(x0 + 1, y0) ?: return null
        val c = ch.at(x0, y0 + 1) ?: return null; val d = ch.at(x0 + 1, y0 + 1) ?: return null
        return (a * (1 - ax) + b * ax) * (1 - ay) + (c * (1 - ax) + d * ax) * ay
    }

    /**
     * Contraste dos ímãs por lado de um contorno convexo ([poly]: cantos na imagem, em ordem): valor do canal no aro
     * no meio do lado (sem ímã) menos o dos dois sítios de ímã (o maior dos dois: os DOIS têm que estar abaixo).
     * Em cada ponto usa o mínimo numa janela em profundidade (a ranhura tem 2-3 px e o contorno erra 1 px) e o mesmo
     * operador no aro de referência, para o ruído não pesar de um lado só. [unitPx]: lado da peça em px (o
     * afastamento dos ímãs é 0,035 lado). Lado que sai da imagem fica de fora. null = peça pequena demais.
     */
    fun edgeContrasts(ch: Channel, poly: List<P2>, unitPx: Float): FloatArray? {
        if (unitPx < minUnitPx || poly.size < 3) return null
        val gx = poly.sumOf { it.x.toDouble() }.toFloat() / poly.size; val gy = poly.sumOf { it.y.toDouble() }.toFloat() / poly.size
        val d0 = max(.8f, unitPx * .008f); val d1 = unitPx * .075f + 1f
        val out = ArrayList<Float>()
        for (i in poly.indices) {
            val p = poly[i]; val q = poly[(i + 1) % poly.size]
            val ex = q.x - p.x; val ey = q.y - p.y; val len = hypot(ex, ey)
            if (len < unitPx * .35f) continue
            val ux = ex / len; val uy = ey / len
            var nx = -uy; var ny = ux
            if ((gx - p.x) * nx + (gy - p.y) * ny < 0) { nx = -nx; ny = -ny }
            fun profile(t: Float, half: Float): Float? {
                var acc = 0f
                for (s in -2..2) {
                    val a = t * len + s * half / 2
                    var mn = Float.MAX_VALUE; var d = d0
                    while (d <= d1) { val l = sample(ch, p.x + ux * a + nx * d, p.y + uy * a + ny * d) ?: return null; mn = min(mn, l); d += .5f }
                    acc += mn
                }
                return acc / 5
            }
            val ref = profile(.5f, unitPx * .05f) ?: continue
            val m1 = profile(.275f, unitPx * .07f) ?: continue
            val m2 = profile(.725f, unitPx * .07f) ?: continue
            out += ref - max(m1, m2)
        }
        return out.toFloatArray()
    }

    /**
     * Contraste por lado nos dois canais (o maior dos dois): luminância (ranhura escura nas peças claras e nas cenas)
     * e croma (ranhura cinza no aro colorido; nas peças escuras reais a ranhura é mais clara que o aro).
     */
    fun contrasts(lum: Channel, chroma: Channel, poly: List<P2>, unitPx: Float): FloatArray? {
        val a = edgeContrasts(lum, poly, unitPx) ?: return null; val b = edgeContrasts(chroma, poly, unitPx) ?: return null
        if (a.size != b.size) return null
        return FloatArray(a.size) { max(a[it], b[it]) }
    }

    /** Contraste mínimo de uma ranhura de ímã (unidades do canal: L* ou croma). */
    @JvmField var darkMin = 5f

    /** Veredito: 1 = tem ímãs, -1 = não tem (vários lados medidos, nenhum com ranhura), 0 = não dá para dizer. */
    fun verdict(contrasts: FloatArray?, thr: Float = darkMin): Int {
        if (contrasts == null || contrasts.size < 2) return 0
        val dark = contrasts.count { it > thr }
        return when {
            dark * 2 >= contrasts.size && dark >= 2 -> 1
            dark == 0 && contrasts.size >= 3 && contrasts.max() < thr * .6f -> -1
            else -> 0
        }
    }

    /** Lados opostos do quadrilátero parecidos (≤ 1,7×) e cantos sem ângulo absurdo: contorno que pode ser um quadrado visto de viés. */
    fun squareish(q: List<P2>): Boolean {
        if (q.size != 4) return false
        val l = (0 until 4).map { (q[(it + 1) % 4] - q[it]).len() }
        if (l.min() < 1e-3f) return false
        val r1 = l[0] / l[2]; val r2 = l[1] / l[3]
        if (r1 !in .6f..1.67f || r2 !in .6f..1.67f) return false
        val ang = Geo2.angles(q)
        return ang.all { it in 50f..130f }
    }
}
