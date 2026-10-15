package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3

/**
 * Um quadro do SLAM já copiado da câmera: imagem pequena RGB, intrínsecos nessa resolução,
 * pose câmera→mundo (matriz 4×4 coluna-maior, câmera olhando para -Z, Y para cima),
 * profundidade opcional (mm, mesma área de visão) e nuvem de pontos opcional (x,y,z,confiança no mundo).
 */
class FrameSample(
    val rgb: IntArray, val w: Int, val h: Int,
    val fx: Float, val fy: Float, val cx: Float, val cy: Float,
    val camToWorld: FloatArray,
    val depth: ShortArray? = null, val dw: Int = 0, val dh: Int = 0,
    val cloud: FloatArray? = null,
)

object FrameProcessor {
    class Stats(val blobs: Int, val depthPoints: Int, val cloudPoints: Int)

    fun process(f: FrameSample, map: FusionMap, minDepthMm: Int = 80, maxDepthMm: Int = 2500): Pair<Segmentation, Stats> {
        val seg = Segmenter.segment(f.rgb, f.w, f.h, minFrac = .0015f)
        val m = f.camToWorld
        fun toWorld(x: Float, y: Float, z: Float) = V3(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14])
        var dPts = 0; var cPts = 0

        if (f.depth != null && f.dw > 0) {
            val sx = f.dw.toFloat() / f.w; val sy = f.dh.toFloat() / f.h
            fun depthAt(x: Int, y: Int) = f.depth[(y * sy).toInt().coerceIn(0, f.dh - 1) * f.dw + (x * sx).toInt().coerceIn(0, f.dw - 1)].toInt() and 0xFFFF
            for (b in seg.blobs) {
                val mk = b.mask
                val bin = b.hueBin
                for (yy in 0 until mk.h) for (xx in 0 until mk.w) {
                    if (!mk[xx, yy]) continue
                    val x = b.minX + xx; val y = b.minY + yy
                    val d = depthAt(x, y)
                    if (d < minDepthMm || d > maxDepthMm) continue
                    // Na borda a profundidade mistura peça e fundo: só aceita se bater com um vizinho de dentro.
                    if (!mk[xx - 1, yy] || !mk[xx + 1, yy] || !mk[xx, yy - 1] || !mk[xx, yy + 1]) {
                        val inner = listOf(xx - 1 to yy, xx + 1 to yy, xx to yy - 1, xx to yy + 1).filter { (a, c) -> mk[a, c] }
                        if (inner.none { (a, c) -> kotlin.math.abs(depthAt(b.minX + a, b.minY + c) - d) < 12 }) continue
                    }
                    val z = d / 1000f
                    map.add(toWorld((x + .5f - f.cx) / f.fx * z, -(y + .5f - f.cy) / f.fy * z, -z), bin, b.color); dPts++
                }
            }
        }

        f.cloud?.let { c ->
            // mundo → câmera: R^T (p - t)
            var i = 0
            while (i + 3 < c.size) {
                val conf = c[i + 3]
                if (conf >= .2f) {
                    val px = c[i] - m[12]; val py = c[i + 1] - m[13]; val pz = c[i + 2] - m[14]
                    val x = m[0] * px + m[1] * py + m[2] * pz; val y = m[4] * px + m[5] * py + m[6] * pz; val z = m[8] * px + m[9] * py + m[10] * pz
                    if (z < -.05f) {
                        val u = (f.fx * x / -z + f.cx).toInt(); val v = (f.cy - f.fy * y / -z).toInt()
                        if (u in 0 until f.w && v in 0 until f.h) {
                            val l = seg.labels[v * f.w + u]
                            if (l >= 0) { val b = seg.blobs.first { it.id == l }; map.add(V3(c[i], c[i + 1], c[i + 2]), b.hueBin, b.color); cPts++ }
                        }
                    }
                }
                i += 4
            }
        }
        return seg to Stats(seg.blobs.size, dPts, cPts)
    }
}
