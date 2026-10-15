package com.brastoy.brascan.scan

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.ui.graphics.Color
import com.brastoy.brascan.three.Scene
import com.brastoy.brascan.three.V3
import com.brastoy.brascan.three.shadowOf
import com.brastoy.brascan.vision.Catalog
import com.brastoy.brascan.vision.Piece3D
import com.brastoy.brascan.vision.Segmenter
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

val SHAPES = Catalog.SHAPES
val SHAPE_LABEL = Catalog.LABEL
val SIZES = Catalog.SIZES

/** Unidades do modelo por metro: a peça média (7,5 cm) vira 0,8 unidade na tela. */
const val MODEL_SCALE = .8f / .075f
private fun edgeOf(size: String) = Catalog.EDGE_M.getValue(size) * MODEL_SCALE

enum class Orient(val label: String) { FRENTE("Em pé ↔"), LADO("Em pé ↕"), DEITADA("Deitada") }

/** Como a foto foi tirada: de frente (dá largura + altura) ou de cima (dá largura + profundidade). */
enum class Vista(val label: String) { FRENTE("Foto de frente"), CIMA("Foto de cima") }

/**
 * Peça. Da foto: caixa em 0..1 + giro no plano ([spin]). Do scanner AR: pose 3D completa ([pos], [pu], [pv]) em unidades do modelo.
 * [orient] escolhido pelo usuário sobrepõe a orientação detectada; [depth]/[lift] são ajustes em "lados de peça".
 */
data class Piece(
    val id: Int,
    val cx: Float = 0f, val cy: Float = 0f, val w: Float = 0f, val h: Float = 0f,
    val shape: String = "quadrado",
    val size: String = "medio",
    val color: Int,
    val orient: Orient? = null,
    val depth: Float = 0f,
    val lift: Float = 0f,
    val turn: Int = 0,
    val spin: Float = 0f,
    val pos: V3? = null, val pu: V3? = null, val pv: V3? = null,
)

data class Placed(val piece: Piece, val center: V3, val u: V3, val v: V3, val edge: Float) {
    val n get() = u.cross(v).norm()
    val orient: Orient get() = when { abs(n.y) > .7f -> Orient.DEITADA; abs(n.x) > abs(n.z) -> Orient.LADO; else -> Orient.FRENTE }
}

object Layout {
    private fun baseFor(o: Orient): Pair<V3, V3> = when (o) {
        Orient.FRENTE -> V3(1f, 0f, 0f) to V3(0f, 1f, 0f)
        Orient.LADO -> V3(0f, 0f, -1f) to V3(0f, 1f, 0f)
        Orient.DEITADA -> V3(1f, 0f, 0f) to V3(0f, 0f, 1f)
    }

    private fun rotate(u: V3, v: V3, a: Float) = (u * cos(a) + v * sin(a)) to (v * cos(a) - u * sin(a))

    /** Converte peças (da foto ou do AR) em posições e bases no mundo 3D do modelo. */
    fun place(pieces: List<Piece>, view: Vista, imgAspect: Float): List<Placed> {
        if (pieces.isEmpty()) return emptyList()
        val photo = pieces.filter { it.pos == null }
        val unit = photo.map { sqrt(it.w * it.h * imgAspect) }.sorted().getOrNull(photo.size / 2)?.coerceAtLeast(.02f) ?: 1f
        val k = edgeOf("medio") / unit
        val cx = photo.minOfOrNull { it.cx - it.w / 2 }?.let { (it + photo.maxOf { p -> p.cx + p.w / 2 }) / 2 } ?: 0f
        val cy = photo.minOfOrNull { it.cy - it.h / 2 }?.let { (it + photo.maxOf { p -> p.cy + p.h / 2 }) / 2 } ?: 0f
        val floor = photo.maxOfOrNull { it.cy + it.h / 2 } ?: 0f
        return pieces.map { p ->
            val edge = edgeOf(p.size)
            val turn = p.turn * (Math.PI / 2).toFloat()
            if (p.pos != null && p.pu != null && p.pv != null && p.orient == null) {
                val (u, v) = rotate(p.pu, p.pv, turn)
                val n = u.cross(v).norm()
                Placed(p, p.pos + n * (p.depth * edge) + V3.UP * (p.lift * edge), u, v, edge)
            } else {
                val orient = p.orient ?: if (view == Vista.FRENTE) Orient.FRENTE else Orient.DEITADA
                val (b0, b1) = baseFor(orient)
                val (u, v) = rotate(b0, b1, p.spin + turn)
                val c = if (p.pos != null) p.pos + V3.UP * (p.lift * edge) else {
                    val x = (p.cx - cx) * imgAspect * k
                    when (view) {
                        Vista.FRENTE -> V3(x, (floor - p.cy) * k + p.lift * edge, p.depth * edge)
                        Vista.CIMA -> V3(x, (if (orient == Orient.DEITADA) .03f else edge / 2) + p.lift * edge, (p.cy - cy) * k + p.depth * edge)
                    }
                }
                Placed(p, c, u, v, edge)
            }
        }
    }

    /** Peça com orientação fixa (modelo de exemplo). */
    fun fixed(p: Piece, c: V3, o: Orient): Placed { val (u, v) = rotate(baseFor(o).first, baseFor(o).second, p.turn * (Math.PI / 2).toFloat()); return Placed(p, c, u, v, edgeOf(p.size)) }

    /** Peças do scanner AR (metros, mundo do ARCore) → peças do modelo, centradas e apoiadas no chão. */
    fun fromScan(found: List<Piece3D>, firstId: Int): List<Piece> {
        if (found.isEmpty()) return emptyList()
        val cx = found.sumOf { it.center.x.toDouble() }.toFloat() / found.size
        val cz = found.sumOf { it.center.z.toDouble() }.toFloat() / found.size
        val floor = found.minOf { p -> outline(p.shape, Catalog.EDGE_M.getValue(p.size)).minOf { (a, b) -> (p.center + p.u * a + p.v * b).y } }
        return found.mapIndexed { i, p ->
            Piece(firstId + i, shape = p.shape, size = p.size, color = p.color,
                pos = V3((p.center.x - cx) * MODEL_SCALE, (p.center.y - floor) * MODEL_SCALE, (p.center.z - cz) * MODEL_SCALE), pu = p.u, pv = p.v)
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
            val p = pl.piece; val u = pl.u; val v = pl.v
            val color = Color(p.color)
            val edgeColor = if (p.id == selected) Color.White else Color(lighten(p.color))
            val thick = Catalog.THICKNESS_M * MODEL_SCALE
            if (p.shape == "janela") {
                val e = pl.edge; val b = e * .16f; val h = e / 2
                listOf(
                    listOf(-h to -h, h to -h, h to -h + b, -h to -h + b), listOf(-h to h - b, h to h - b, h to h, -h to h),
                    listOf(-h to -h + b, -h + b to -h + b, -h + b to h - b, -h to h - b), listOf(h - b to -h + b, h to -h + b, h to h - b, h - b to h - b),
                ).forEach { scene.prism(it, pl.center, u, v, thick, color, p.id, 1f, edgeColor) }
                scene.shadowOf(outline("quadrado", e).map { (a, c) -> pl.center + u * a + v * c })
            } else {
                val o = outline(p.shape, pl.edge)
                scene.prism(o, pl.center, u, v, thick, color, p.id, .85f, edgeColor)
                scene.shadowOf(o.map { (a, c) -> pl.center + u * a + v * c })
            }
        }
    }

    private fun lighten(c: Int): Int {
        val hsv = FloatArray(3); AColor.colorToHSV(c, hsv); hsv[1] *= .5f; hsv[2] = min(1f, hsv[2] * 1.4f + .2f); return AColor.HSVToColor(hsv)
    }

    /** Quatérnio (x, y, z, w) da base (u, v, n) — mesma convenção do MK-7: identidade = peça em pé de frente. */
    fun quat(u: V3, v: V3, n: V3): List<Double> {
        val m00 = u.x; val m10 = u.y; val m20 = u.z; val m01 = v.x; val m11 = v.y; val m21 = v.z; val m02 = n.x; val m12 = n.y; val m22 = n.z
        val tr = m00 + m11 + m22
        val q = when {
            tr > 0 -> { val s = sqrt(tr + 1f) * 2; floatArrayOf((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, .25f * s) }
            m00 > m11 && m00 > m22 -> { val s = sqrt(1f + m00 - m11 - m22) * 2; floatArrayOf(.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s) }
            m11 > m22 -> { val s = sqrt(1f + m11 - m00 - m22) * 2; floatArrayOf((m01 + m10) / s, .25f * s, (m12 + m21) / s, (m02 - m20) / s) }
            else -> { val s = sqrt(1f + m22 - m00 - m11) * 2; floatArrayOf((m02 + m20) / s, (m12 + m21) / s, .25f * s, (m10 - m01) / s) }
        }
        return q.map { Math.round(it * 1e6) / 1e6 }
    }

    fun json(placed: List<Placed>): String {
        val root = JSONObject().put("format", "Brastoy").put("version", 4).put("inventoryMode", "unlimited")
            .put("unitMeters", Math.round(1e5 / MODEL_SCALE) / 1e5)
            .put("source", "BraScan: posições estimadas (foto ou scanner 3D); confira no MK-7")
        val arr = JSONArray()
        for (pl in placed) {
            fun r(v: Float) = Math.round(v * 1000) / 1000.0
            arr.put(JSONObject().put("type", pl.piece.shape).put("size", pl.piece.size)
                .put("color", String.format(Locale.US, "#%06X", pl.piece.color and 0xffffff))
                .put("pos", JSONArray().put(r(pl.center.x)).put(r(pl.center.y)).put(r(pl.center.z)))
                .put("rot", JSONArray(quat(pl.u, pl.v, pl.n))))
        }
        return root.put("pieces", arr).toString(2)
    }
}

/** Foto → peças: segmentação + classificação geométrica (vision/), tamanho relativo à mediana. */
object Detector {
    fun detect(src: Bitmap, startId: Int): List<Piece> {
        val w = 320; val h = max(1, (src.height * w.toFloat() / src.width).toInt())
        val b = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val seg = Segmenter.segment(px, w, h, maxBlobs = 60)
        if (seg.blobs.isEmpty()) return emptyList()
        val med = seg.blobs.map { sqrt(it.count.toFloat()) }.sorted()[seg.blobs.size / 2]
        var id = startId
        return seg.blobs.map { bl ->
            val fit = bl.fit
            val shape = fit?.shape ?: "quadrado"
            // giro no plano: triângulos pelo vértice; o resto pelo retângulo mínimo (encaixado em 0°/90° se perto)
            val spin = when {
                fit == null -> 0f
                shape == "equilatero" || shape == "retanguloTri" -> { val a = fit.obb.vy * (if (fit.apexPositive) 1f else -1f); atan2(-a.x, -a.y) }
                else -> { val q = (Math.PI / 2).toFloat(); val a = -fit.obb.angle; val r = Math.round(a / q) * q; if (abs(a - r) < .26f) 0f else a - r }
            }
            val rel = sqrt(bl.count.toFloat()) / med
            Piece(id++, (bl.minX + bl.bw / 2f) / w, (bl.minY + bl.bh / 2f) / h, bl.bw.toFloat() / w, bl.bh.toFloat() / h, shape,
                size = when { rel < .75f -> "pequeno"; rel > 1.3f -> "grande"; else -> "medio" }, color = bl.color, spin = spin)
        }
    }
}
