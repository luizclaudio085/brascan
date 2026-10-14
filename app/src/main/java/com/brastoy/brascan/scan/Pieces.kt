package com.brastoy.brascan.scan

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.ui.graphics.Color
import com.brastoy.brascan.three.Scene
import com.brastoy.brascan.three.V3
import com.brastoy.brascan.three.shadowOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

val SHAPES = listOf("quadrado", "retangulo", "equilatero", "retanguloTri", "janela", "L", "E")
val SHAPE_LABEL = mapOf("quadrado" to "Quadrado", "retangulo" to "Retângulo", "equilatero" to "Triângulo", "retanguloTri" to "Tri. retângulo", "janela" to "Janela", "L" to "L", "E" to "E")
val SIZES = listOf("pequeno", "medio", "grande")
private val EDGE = mapOf("pequeno" to .55f, "medio" to .8f, "grande" to 1.1f)

enum class Orient(val label: String) { FRENTE("Em pé ↔"), LADO("Em pé ↕"), DEITADA("Deitada") }

/** Como a foto foi tirada: de frente (dá largura + altura) ou de cima (dá largura + profundidade). */
enum class Vista(val label: String) { FRENTE("Foto de frente"), CIMA("Foto de cima") }

/** Peça marcada. Coordenadas da foto em 0..1; os deslocamentos são ajustes manuais em "tiles". */
data class Piece(
    val id: Int,
    val cx: Float, val cy: Float, val w: Float, val h: Float,
    val shape: String = "quadrado",
    val size: String = "medio",
    val color: Int,
    val orient: Orient? = null,
    val depth: Float = 0f,
    val lift: Float = 0f,
    val turn: Int = 0,
)

data class Placed(val piece: Piece, val center: V3, val orient: Orient, val edge: Float)

object Layout {
    /** Converte marcações da foto em posições no mundo 3D; escala pelo tamanho mediano das peças. */
    fun place(pieces: List<Piece>, view: Vista, imgAspect: Float): List<Placed> {
        if (pieces.isEmpty()) return emptyList()
        val unit = pieces.map { sqrt(it.w * it.h * imgAspect) }.sorted()[pieces.size / 2].coerceAtLeast(.02f)
        val k = EDGE.getValue("medio") / unit
        val cx = (pieces.minOf { it.cx - it.w / 2 } + pieces.maxOf { it.cx + it.w / 2 }) / 2
        val cy = (pieces.minOf { it.cy - it.h / 2 } + pieces.maxOf { it.cy + it.h / 2 }) / 2
        val floor = pieces.maxOf { it.cy + it.h / 2 }
        return pieces.map { p ->
            val edge = EDGE.getValue(p.size)
            val orient = p.orient ?: if (view == Vista.FRENTE) Orient.FRENTE else Orient.DEITADA
            val x = (p.cx - cx) * imgAspect * k
            val c = when (view) {
                Vista.FRENTE -> V3(x, (floor - p.cy) * k + p.lift * edge, p.depth * edge)
                Vista.CIMA -> V3(x, (if (orient == Orient.DEITADA) .03f else edge / 2) + p.lift * edge, (p.cy - cy) * k + p.depth * edge)
            }
            Placed(p, c, orient, edge)
        }
    }

    fun outline(shape: String, e: Float): List<Pair<Float, Float>> {
        val h = e / 2
        return when (shape) {
            "retangulo" -> listOf(-e to -h, e to -h, e to h, -e to h)
            "equilatero" -> { val t = e * .866f; listOf(-h to -t / 3, h to -t / 3, 0f to t * 2 / 3) }
            "retanguloTri" -> listOf(-h to -h, h to -h, -h to h)
            "L" -> listOf(-h to -h, h to -h, h to -h / 3, -h / 3 to -h / 3, -h / 3 to h, -h to h)
            "E" -> { val s = e / 5; listOf(-h to -h, h to -h, h to -h + s, -h + s to -h + s, -h + s to -s / 2, h * .6f to -s / 2, h * .6f to s / 2, -h + s to s / 2, -h + s to h - s, h to h - s, h to h, -h to h) }
            else -> listOf(-h to -h, h to -h, h to h, -h to h)
        }
    }

    fun draw(scene: Scene, placed: List<Placed>, selected: Int) {
        for (pl in placed) {
            val p = pl.piece
            val turn = p.turn * (Math.PI.toFloat() / 2)
            val (u, v) = when (pl.orient) {
                Orient.FRENTE -> V3(kotlin.math.cos(turn), 0f, kotlin.math.sin(turn)) to V3(0f, 1f, 0f)
                Orient.LADO -> V3(kotlin.math.sin(turn), 0f, kotlin.math.cos(turn)) to V3(0f, 1f, 0f)
                Orient.DEITADA -> V3(kotlin.math.cos(turn), 0f, kotlin.math.sin(turn)) to V3(-kotlin.math.sin(turn), 0f, kotlin.math.cos(turn))
            }
            val color = Color(p.color)
            val sel = p.id == selected
            val edgeColor = if (sel) Color.White else Color(lighten(p.color))
            val thick = .045f
            if (p.shape == "janela") {
                val e = pl.edge; val b = e * .16f; val h = e / 2
                listOf(
                    listOf(-h to -h, h to -h, h to -h + b, -h to -h + b), listOf(-h to h - b, h to h - b, h to h, -h to h),
                    listOf(-h to -h + b, -h + b to -h + b, -h + b to h - b, -h to h - b), listOf(h - b to -h + b, h to -h + b, h to h - b, h - b to h - b),
                ).forEach { scene.prism(it, pl.center, u, v, thick, color, p.id, 1f, edgeColor) }
                scene.shadowOf(outline("quadrado", e).map { (a, c) -> pl.center + u * a + v * c })
            } else {
                val o = outline(p.shape, pl.edge)
                scene.prism(o, pl.center, u, v, thick, color, p.id, .82f, edgeColor)
                scene.shadowOf(o.map { (a, c) -> pl.center + u * a + v * c })
            }
        }
    }

    private fun lighten(c: Int): Int {
        val hsv = FloatArray(3); AColor.colorToHSV(c, hsv); hsv[1] *= .5f; hsv[2] = min(1f, hsv[2] * 1.4f + .2f); return AColor.HSVToColor(hsv)
    }

    fun json(placed: List<Placed>): String {
        val root = JSONObject().put("format", "Brastoy").put("version", 4).put("inventoryMode", "unlimited")
            .put("source", "BraScan: posições estimadas a partir de foto; confira no MK-7")
        val arr = JSONArray()
        for (pl in placed) {
            val q = when (pl.orient) {
                Orient.FRENTE -> listOf(0.0, 0.0, 0.0, 1.0)
                Orient.LADO -> listOf(0.0, .70710678, 0.0, .70710678)
                Orient.DEITADA -> listOf(.70710678, 0.0, 0.0, .70710678)
            }
            fun r(v: Float) = Math.round(v * 1000) / 1000.0
            arr.put(JSONObject().put("type", pl.piece.shape).put("size", pl.piece.size)
                .put("color", String.format(Locale.US, "#%06X", pl.piece.color and 0xffffff))
                .put("pos", JSONArray().put(r(pl.center.x)).put(r(pl.center.y)).put(r(pl.center.z)))
                .put("rot", JSONArray(q)))
        }
        return root.put("pieces", arr).toString(2)
    }
}

/**
 * Segmentação por cor: agrupa pixels vizinhos de matiz parecida em regiões (flood fill),
 * descarta fundo/ruído e classifica a forma pela proporção e pelo preenchimento da caixa.
 */
object Detector {
    fun detect(src: Bitmap, startId: Int): List<Piece> {
        val w = 200; val h = max(1, (src.height * w.toFloat() / src.width).toInt())
        val b = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val hue = FloatArray(w * h); val sat = FloatArray(w * h); val v = FloatArray(w * h)
        val hsv = FloatArray(3)
        for (i in px.indices) { AColor.colorToHSV(px[i], hsv); hue[i] = hsv[0]; sat[i] = hsv[1]; v[i] = hsv[2] }
        fun colored(i: Int) = sat[i] > .32f && v[i] > .22f
        fun hueDist(a: Float, b: Float) = min(abs(a - b), 360 - abs(a - b))
        val label = IntArray(w * h) { -1 }
        val queue = IntArray(w * h)
        val out = mutableListOf<Piece>()
        var id = startId
        val total = w * h
        for (start in 0 until total) {
            if (label[start] >= 0 || !colored(start)) continue
            var head = 0; var tail = 0; queue[tail++] = start; label[start] = start
            var minX = w; var minY = h; var maxX = 0; var maxY = 0; var r = 0L; var g = 0L; var bl = 0L
            while (head < tail) {
                val i = queue[head++]; val x = i % w; val y = i / w
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                r += AColor.red(px[i]); g += AColor.green(px[i]); bl += AColor.blue(px[i])
                for (n in intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, i - w, i + w)) {
                    if (n < 0 || n >= total || label[n] >= 0 || !colored(n)) continue
                    if (hueDist(hue[n], hue[i]) > 16f || abs(v[n] - v[i]) > .3f) continue
                    label[n] = start; queue[tail++] = n
                }
            }
            val count = tail; val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (count < total * .002f || count > total * .35f || bw < 4 || bh < 4) continue
            val fill = count.toFloat() / (bw * bh)
            val aspect = bw.toFloat() / bh
            val shape = when {
                fill < .38f && aspect in .7f..1.4f -> "janela"
                fill < .66f -> "equilatero"
                aspect > 1.6f || aspect < .62f -> "retangulo"
                else -> "quadrado"
            }
            val color = AColor.rgb((r / count).toInt(), (g / count).toInt(), (bl / count).toInt())
            out += Piece(id++, (minX + bw / 2f) / w, (minY + bh / 2f) / h, bw.toFloat() / w, bh.toFloat() / h, shape, color = color)
        }
        out.sortByDescending { it.w * it.h }
        val kept = out.take(60)
        // Ajusta tamanho relativo: muito menores/maiores que a mediana viram pequeno/grande.
        if (kept.isEmpty()) return kept
        val med = kept.map { sqrt(it.w * it.h) }.sorted()[kept.size / 2]
        return kept.map { p ->
            val s = sqrt(p.w * p.h) / med
            p.copy(size = when { s < .72f -> "pequeno"; s > 1.4f -> "grande"; else -> "medio" })
        }
    }
}
