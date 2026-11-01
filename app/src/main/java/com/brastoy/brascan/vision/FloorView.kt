package com.brastoy.brascan.vision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Chão visto pela câmera: intrínsecos + direção "para cima" (contra a gravidade) no referencial da CÂMERA
 * (x para a direita, y para baixo, z para a frente, como os pixels). Dá a deformação local da imagem para o chão em
 * cada pixel (jacobiana 2×2): peça deitada vista de viés vira paralelogramo/triângulo achatado na imagem; com a
 * jacobiana ela volta à forma real antes de comparar com o molde. A altura da câmera não é conhecida (escala 1).
 */
class FloorView(val fx: Float, val fy: Float, val cx: Float, val cy: Float, up: FloatArray) {
    private val n: FloatArray
    private val e1: FloatArray
    private val e2: FloatArray

    init {
        val l = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]).coerceAtLeast(1e-6f)
        n = floatArrayOf(up[0] / l, up[1] / l, up[2] / l)
        // base do chão: e1 = x da câmera projetado no chão (ou z, se x for quase vertical); e2 = e1 × n
        val ref = if (abs(n[0]) < .9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 0f, 1f)
        val d = ref[0] * n[0] + ref[1] * n[1] + ref[2] * n[2]
        val a = floatArrayOf(ref[0] - d * n[0], ref[1] - d * n[1], ref[2] - d * n[2])
        val al = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        e1 = floatArrayOf(a[0] / al, a[1] / al, a[2] / al)
        e2 = floatArrayOf(e1[1] * n[2] - e1[2] * n[1], e1[2] * n[0] - e1[0] * n[2], e1[0] * n[1] - e1[1] * n[0])
    }

    /** Gravidade (para baixo) no referencial da câmera: a mesma convenção de [GroundCam.down]. */
    val down: FloatArray get() = floatArrayOf(-n[0], -n[1], -n[2])

    /** Ponto do chão (coordenadas da base e1, e2; câmera a altura 1) visto no pixel ([u], [v]); null acima do horizonte. */
    fun floor(u: Float, v: Float): FloatArray? {
        val qx = (u - cx) / fx; val qy = (v - cy) / fy
        val nq = n[0] * qx + n[1] * qy + n[2]
        if (nq > -1e-3f) return null // raio não desce até o chão
        val t = -1f / nq
        val x = t * qx; val y = t * qy; val z = t
        return floatArrayOf(e1[0] * x + e1[1] * y + e1[2] * z, e2[0] * x + e2[1] * y + e2[2] * z)
    }

    /** Jacobiana imagem → chão no pixel (linha a linha: da/du, da/dv, db/du, db/dv); null acima do horizonte. */
    fun jacobian(u: Float, v: Float): FloatArray? {
        val l = floor(u - 1, v) ?: return null; val r = floor(u + 1, v) ?: return null
        val t = floor(u, v - 1) ?: return null; val b = floor(u, v + 1) ?: return null
        return floatArrayOf((r[0] - l[0]) / 2, (b[0] - t[0]) / 2, (r[1] - l[1]) / 2, (b[1] - t[1]) / 2)
    }

    /** Jacobiana com determinante 1 (só a forma da deformação) e a escala local (lado do chão por pixel). */
    fun shapeWarp(u: Float, v: Float): Pair<FloatArray, Float>? {
        val j = jacobian(u, v) ?: return null
        val det = abs(j[0] * j[3] - j[1] * j[2]); if (det < 1e-12f) return null
        val s = sqrt(det)
        return floatArrayOf(j[0] / s, j[1] / s, j[2] / s, j[3] / s) to s
    }

    companion object {
        /** Formas com momento de 2ª ordem isótropo vistas de cima (polígono regular, molduras simétricas). */
        val ISOTROPIC = setOf("quadrado", "moldura", "janela", "equilatero", "encaixe")

        /** Anisotropia (0 = redonda) da covariância [c] = (cxx, cxy, cyy) depois da deformação [j] (2×2 linha a linha). */
        fun anisotropy(c: FloatArray, j: FloatArray): Float {
            // C' = J C Jᵀ
            val a = j[0]; val b = j[1]; val cc = j[2]; val d = j[3]
            val xx = a * a * c[0] + 2 * a * b * c[1] + b * b * c[2]
            val yy = cc * cc * c[0] + 2 * cc * d * c[1] + d * d * c[2]
            val xy = a * cc * c[0] + (a * d + b * cc) * c[1] + b * d * c[2]
            val tr = xx + yy; if (tr <= 0f) return 1f
            return sqrt((xx - yy) * (xx - yy) + 4 * xy * xy) / tr
        }

        /**
         * Estima o chão pela própria foto (sem gravidade): peça de momento isótropo ([ISOTROPIC]) tem a mesma largura
         * em toda direção vista de cima; na foto inclinada ela fica achatada na direção da inclinação, mais longe do
         * centro mais achatada. Procura a inclinação (0-60°) e a direção que deixam TODAS isótropas ao mesmo tempo
         * (média aparada da anisotropia), com a distância focal típica de celular ([focal] × largura).
         * [pieces]: (cx, cy, cxx, cxy, cyy) em pixels. Null se não há peças suficientes ou se a foto já é de cima.
         */
        fun estimate(w: Int, h: Int, pieces: List<FloatArray>, focal: Float = .8f): FloorView? =
            estimate(focal * w, focal * w, w / 2f, h / 2f, pieces)

        /** O mesmo com os intrínsecos de [cam] (palpite [GroundCam.guess] ou os do quadro). */
        fun estimate(cam: GroundCam, pieces: List<FloatArray>): FloorView? = estimate(cam.fx, cam.fy, cam.cx, cam.cy, pieces)

        /** Chão da câmera [cam] com a gravidade [down] (referencial da câmera, como em [GroundCam.down]). */
        fun of(cam: GroundCam, down: FloatArray) = FloorView(cam.fx, cam.fy, cam.cx, cam.cy, floatArrayOf(-down[0], -down[1], -down[2]))

        private fun estimate(fx: Float, fy: Float, cx: Float, cy: Float, pieces: List<FloatArray>): FloorView? {
            if (pieces.size < 2) return null
            fun view(tiltDeg: Float, azDeg: Float): FloorView {
                val t = Math.toRadians(tiltDeg.toDouble()); val a = Math.toRadians(azDeg.toDouble())
                return FloorView(fx, fy, cx, cy, floatArrayOf((sin(t) * cos(a)).toFloat(), (sin(t) * sin(a)).toFloat(), (-cos(t)).toFloat()))
            }
            fun cost(v: FloorView): Float {
                val an = pieces.map { p -> v.shapeWarp(p[0], p[1])?.first?.let { anisotropy(floatArrayOf(p[2], p[3], p[4]), it) } ?: 1f }.sorted()
                val k = maxOf(2, (an.size * 3 + 3) / 4) // média dos 75% menores: peça mal classificada não manda
                return an.take(k).average().toFloat()
            }
            val flat = cost(view(0f, 0f))
            var bt = 0f; var ba = 0f; var bc = flat
            for (t in 5..60 step 5) for (a in 0 until 360 step 15) { val c = cost(view(t.toFloat(), a.toFloat())); if (c < bc) { bc = c; bt = t.toFloat(); ba = a.toFloat() } }
            if (bt == 0f) return null
            // refino local
            var st = 2.5f; var sa = 7.5f
            repeat(4) {
                for ((dt, da) in listOf(st to 0f, -st to 0f, 0f to sa, 0f to -sa)) {
                    val t = (bt + dt).coerceIn(0f, 70f); val c = cost(view(t, ba + da)); if (c < bc) { bc = c; bt = t; ba += da }
                }
                st /= 2; sa /= 2
            }
            // só vale se deixou as peças claramente mais redondas que a foto crua
            return if (bc < flat * .8f && flat - bc > .02f) view(bt, ba) else null
        }
    }
}
