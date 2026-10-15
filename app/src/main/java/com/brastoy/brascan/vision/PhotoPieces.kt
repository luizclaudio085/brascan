package com.brastoy.brascan.vision

import kotlin.math.abs

/** Peça encontrada numa foto (pixels da imagem segmentada). */
/** [spin]/[spinTop]: giro da peça no próprio plano para foto de frente / de cima (rad). [fit]: IoU do molde (0..1). */
data class PhotoPiece(val cx: Float, val cy: Float, val w: Float, val h: Float, val shape: String, val size: String, val color: Int, val spin: Float, val spinTop: Float, val side: Float, val fit: Float = 0f)

/**
 * Regiões → peças: resolve o que está dentro de quê (encaixe dentro de moldura, vidros da janela)
 * e o tamanho de cada peça em relação ao quadrado grande da foto.
 */
object PhotoPieces {
    private val FRAMES = setOf("moldura", "porta", "janela")

    fun from(seg: Segmentation): List<PhotoPiece> {
        val blobs = seg.blobs
        fun inside(a: Blob, b: Blob): Boolean { // a dentro da caixa de b (com folga)
            val ix = minOf(a.maxX, b.maxX) - maxOf(a.minX, b.minX) + 1; val iy = minOf(a.maxY, b.maxY) - maxOf(a.minY, b.minY) + 1
            return ix > 0 && iy > 0 && ix * iy >= a.bw * a.bh * .85f && a.bw * a.bh < b.bw * b.bh
        }
        val shape = HashMap<Int, String>(); val drop = HashSet<Int>(); val parent = HashMap<Int, Int>()
        for (b in blobs) shape[b.id] = b.fit?.shape ?: "quadrado"
        for (a in blobs) {
            val outer = blobs.filter { it !== a && inside(a, it) }.minByOrNull { it.bw * it.bh } ?: continue
            val big = a.bw * a.bh >= outer.bw * outer.bh * .25f
            when {
                big -> { shape[a.id] = "encaixe"; parent[a.id] = outer.id; if (shape[outer.id] !in FRAMES) shape[outer.id] = "moldura" }
                shape[outer.id] in FRAMES || (outer.fit?.gaps?.count { it.enclosed } ?: 0) > 0 -> { drop += a.id; if ((outer.fit?.gaps?.count { it.enclosed } ?: 0) >= 3) shape[outer.id] = "janela" }
                else -> drop += a.id // resto da mesma peça (reflexo, ímã)
            }
        }
        // vidros da janela de 4 vidros: vários pedacinhos dentro da mesma moldura
        blobs.filter { it.id in drop }.groupBy { a -> blobs.filter { it !== a && inside(a, it) }.minByOrNull { it.bw * it.bh }?.id }
            .forEach { (outer, inner) -> if (outer != null && inner.size >= 3) shape[outer] = "janela" }

        val kept = blobs.filter { it.id !in drop }
        fun side(b: Blob): Float {
            val f = b.fit ?: return maxOf(b.bw, b.bh).toFloat()
            val s = shape.getValue(b.id)
            // pontas de triângulo perdem proporcionalmente mais pixels (borda + ímãs); 1,1 calibrado nas fotos reais
            val tri = s == "equilatero" || s == "retanguloTri" || s == "isoceles"
            return if (s == "encaixe") maxOf(f.obb.w, f.obb.h) / .83f else Catalog.sideFromBox(s, f.obb.w, f.obb.h) * (if (tri) 1.1f else 1f)
        }
        // o lado do quadrado grande = percentil 75 dos lados (a maioria das peças é grande)
        val sides = kept.filter { shape[it.id] != "encaixe" }.map(::side).sorted()
        val unit = sides.getOrNull((sides.size * 3) / 4 - if (sides.size % 4 == 0 && sides.isNotEmpty()) 1 else 0)?.takeIf { it > 0 } ?: 1f

        val spins = HashMap<Int, Pair<Float, Float>>(); val fits = HashMap<Int, Float>()
        fun snap(a: Float): Float { val q = (Math.PI / 2).toFloat(); val r = Math.round(a / q) * q; return if (abs(a - r) < .14f) r else a }
        for (b in kept) {
            val f = b.fit ?: continue
            // molde exato do catálogo: frente (v para cima → giro = -θ) e cima (v para baixo → giro = θ)
            val (thUp, iou) = TemplateFit.fit(b.mask, f.obb, shape.getValue(b.id), vUp = true, corners = f.corners)
            val (thDown, _) = TemplateFit.fit(b.mask, f.obb, shape.getValue(b.id), vUp = false, corners = f.corners)
            spins[b.id] = snap(-thUp) to snap(thDown); fits[b.id] = iou
        }
        // encaixe segue o giro da moldura onde está
        for ((ins, frame) in parent) spins[frame]?.let { spins[ins] = it }

        return kept.map { b ->
            val s = shape.getValue(b.id)
            val (spin, spinTop) = spins[b.id] ?: (0f to 0f)
            val size = if (s == "encaixe") "medio" else Catalog.sizeForRatio(side(b) / unit)
            PhotoPiece(b.minX + b.bw / 2f, b.minY + b.bh / 2f, b.bw.toFloat(), b.bh.toFloat(), s, size, b.color, spin, spinTop, side(b), fits[b.id] ?: 0f)
        }
    }
}
