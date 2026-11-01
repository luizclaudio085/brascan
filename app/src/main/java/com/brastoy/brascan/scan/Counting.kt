package com.brastoy.brascan.scan

import android.graphics.Bitmap
import com.brastoy.brascan.vision.GroundCam
import com.brastoy.brascan.vision.PhotoPieces

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
    data class Found(val cx: Float, val cy: Float, val w: Float, val h: Float, val kind: Inventory.Kind, val color: Int, val fit: Float, val cut: Boolean = false)

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
                Found(d.cx / w, d.cy / h, d.w / w, d.h / h, kindOf(d.shape, d.size), d.color, d.fit, cut)
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
     * reflexo, borrão) não muda o número na tela.
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
}
