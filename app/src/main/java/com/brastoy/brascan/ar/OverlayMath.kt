package com.brastoy.brascan.ar

import com.brastoy.brascan.vision.Intrinsics
import com.brastoy.brascan.vision.Piece3D
import com.brastoy.brascan.vision.Pose
import kotlin.math.max

/**
 * O que o scanner sabe neste quadro, para desenhar as peças 3D por cima da imagem da câmera:
 * pose câmera→mundo do SLAM (eixos da câmera: x direita, y baixo, z para frente), intrínsecos na
 * imagem analisada (w×h, orientação do sensor), rotação para a tela em pé e as peças do mapa (em metros)
 * com quantas vezes cada uma foi vista.
 */
class OverlayFrame(
    val cam: Pose?, val lost: Boolean, val k: Intrinsics, val w: Int, val h: Int, val rot: Int,
    val pieces: List<Pair<Piece3D, Int>>,
)

/** Matrizes da realidade aumentada (sem Android, para dar para testar no PC). */
object OverlayMath {
    /** Mundo→câmera (coluna-maior) a partir da pose câmera→mundo: Pc = Rᵀ (Pw − t). */
    fun viewMatrix(cam: Pose, out: FloatArray) {
        val r = cam.r; val t = cam.t
        for (i in 0 until 3) for (j in 0 until 3) out[j * 4 + i] = r[j * 3 + i].toFloat() // Rᵀ: linha i, coluna j = R[j][i]
        for (i in 0 until 3) out[12 + i] = -(r[0 * 3 + i] * t[0] + r[1 * 3 + i] * t[1] + r[2 * 3 + i] * t[2]).toFloat()
        out[3] = 0f; out[7] = 0f; out[11] = 0f; out[15] = 1f
    }

    /** Pixel da imagem analisada (orientação do sensor) → pixel em pé, como a tela desenha os contornos. */
    fun upright(x: Double, y: Double, w: Int, h: Int, rot: Int): DoubleArray = when (rot) {
        90 -> doubleArrayOf(h - y, x); 180 -> doubleArrayOf(w - x, h - y); 270 -> doubleArrayOf(y, w - x); else -> doubleArrayOf(x, y)
    }

    /**
     * Projeção que leva coordenadas da câmera (x direita, y baixo, z frente) ao recorte da tela:
     * pixel = intrínsecos; gira para a tela em pé (rotationDegrees); FILL_CENTER como a pré-visualização.
     * clip = (nx·z, ny·z, A·z + B, z): tudo linear em (x, y, z), por isso cabe numa matriz 4×4 (coluna-maior).
     */
    fun projection(k: Intrinsics, w: Int, h: Int, rot: Int, screenW: Int, screenH: Int, out: FloatArray) {
        // u·z e v·z como combinações de (x, y, z)
        val uz = doubleArrayOf(k.fx, 0.0, k.cx); val vz = doubleArrayOf(0.0, k.fy, k.cy)
        val (upW, upH) = if (rot == 90 || rot == 270) h to w else w to h
        // em pé: u'·z e v'·z (coeficientes de x, y, z; a constante multiplica z)
        val uPz: DoubleArray; val vPz: DoubleArray
        when (rot) {
            90 -> { uPz = doubleArrayOf(-vz[0], -vz[1], h - vz[2]); vPz = uz }
            180 -> { uPz = doubleArrayOf(-uz[0], -uz[1], w - uz[2]); vPz = doubleArrayOf(-vz[0], -vz[1], h - vz[2]) }
            270 -> { uPz = vz; vPz = doubleArrayOf(-uz[0], -uz[1], w - uz[2]) }
            else -> { uPz = uz; vPz = vz }
        }
        val sc = max(screenW.toDouble() / upW, screenH.toDouble() / upH)
        val ox = (screenW - upW * sc) / 2; val oy = (screenH - upH * sc) / 2
        // nx·z = (2 sc / W) u'z + (2 ox / W − 1) z ; ny·z = −(2 sc / H) v'z + (1 − 2 oy / H) z
        val ax = 2 * sc / screenW; val bx = 2 * ox / screenW - 1
        val ay = -2 * sc / screenH; val by = 1 - 2 * oy / screenH
        val near = .02; val far = 30.0
        val a = (far + near) / (far - near); val b = -2 * far * near / (far - near)
        val m = DoubleArray(16)
        m[0] = ax * uPz[0]; m[4] = ax * uPz[1]; m[8] = ax * uPz[2] + bx; m[12] = 0.0
        m[1] = ay * vPz[0]; m[5] = ay * vPz[1]; m[9] = ay * vPz[2] + by; m[13] = 0.0
        m[2] = 0.0; m[6] = 0.0; m[10] = a; m[14] = b
        m[3] = 0.0; m[7] = 0.0; m[11] = 1.0; m[15] = 0.0
        for (i in 0 until 16) out[i] = m[i].toFloat()
    }

    /** Onde um ponto do mundo cai na tela (pixels) por estas matrizes — para testes e depuração. */
    fun toScreen(view: FloatArray, proj: FloatArray, p: DoubleArray, screenW: Int, screenH: Int): DoubleArray? {
        val c = DoubleArray(4) { i -> view[i] * p[0] + view[4 + i] * p[1] + view[8 + i] * p[2] + view[12 + i] }
        val q = DoubleArray(4) { i -> proj[i] * c[0] + proj[4 + i] * c[1] + proj[8 + i] * c[2] + proj[12 + i] * c[3] }
        if (q[3] <= 1e-9) return null
        val nx = q[0] / q[3]; val ny = q[1] / q[3]
        return doubleArrayOf((nx + 1) / 2 * screenW, (1 - ny) / 2 * screenH, q[2] / q[3])
    }

    /** O mesmo ponto pelo caminho dos contornos (intrínsecos → em pé → FILL_CENTER), para conferir a matriz. */
    fun toScreenReference(cam: Pose, k: Intrinsics, w: Int, h: Int, rot: Int, p: DoubleArray, screenW: Int, screenH: Int): DoubleArray {
        val c = cam.inverse().apply(p)
        val u = k.fx * c[0] / c[2] + k.cx; val v = k.fy * c[1] / c[2] + k.cy
        val (ux, uy) = upright(u, v, w, h, rot).let { it[0] to it[1] }
        val (upW, upH) = if (rot == 90 || rot == 270) h to w else w to h
        val sc = max(screenW.toDouble() / upW, screenH.toDouble() / upH)
        val ox = (screenW - upW * sc) / 2; val oy = (screenH - upH * sc) / 2
        return doubleArrayOf(ox + ux * sc, oy + uy * sc)
    }
}
