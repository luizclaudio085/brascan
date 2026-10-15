package com.brastoy.brascan.vision

import com.brastoy.brascan.three.V3
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Peça reconstruída no espaço (metros, mundo do ARCore: Y para cima, alinhado com a gravidade). */
data class Piece3D(
    val shape: String,
    val size: String,
    val color: Int,
    val center: V3,
    /** Base ortonormal: u = largura, v = altura da peça, n = normal. */
    val u: V3, val v: V3, val n: V3,
    val w: Float, val h: Float,
    val support: Int,
)

object Pca {
    /** Autovalores/autovetores de uma matriz simétrica 3×3 (Jacobi). Retorna em ordem crescente. */
    fun eigen(m: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val a = Array(3) { m[it].clone() }
        val v = Array(3) { i -> DoubleArray(3) { j -> if (i == j) 1.0 else 0.0 } }
        repeat(50) {
            var p = 0; var q = 1
            if (abs(a[0][2]) > abs(a[p][q])) { p = 0; q = 2 }
            if (abs(a[1][2]) > abs(a[p][q])) { p = 1; q = 2 }
            if (abs(a[p][q]) < 1e-15) return@repeat
            val th = (a[q][q] - a[p][p]) / (2 * a[p][q])
            val t = (if (th >= 0) 1.0 else -1.0) / (abs(th) + sqrt(th * th + 1))
            val c = 1 / sqrt(t * t + 1); val s = t * c
            for (k in 0 until 3) { val akp = a[k][p]; val akq = a[k][q]; a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq }
            for (k in 0 until 3) { val apk = a[p][k]; val aqk = a[q][k]; a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk }
            for (k in 0 until 3) { val vkp = v[k][p]; val vkq = v[k][q]; v[k][p] = c * vkp - s * vkq; v[k][q] = s * vkp + c * vkq }
        }
        val order = (0 until 3).sortedBy { a[it][it] }
        return DoubleArray(3) { a[order[it]][order[it]] } to Array(3) { i -> DoubleArray(3) { k -> v[k][order[i]] } }
    }

    /** Plano por mínimos quadrados: centróide, normal e "planaridade" (λmin / λmed). */
    fun plane(pts: List<V3>): Triple<V3, V3, Float> {
        val c = V3(pts.sumOf { it.x.toDouble() }.toFloat() / pts.size, pts.sumOf { it.y.toDouble() }.toFloat() / pts.size, pts.sumOf { it.z.toDouble() }.toFloat() / pts.size)
        val m = Array(3) { DoubleArray(3) }
        for (p in pts) { val d = doubleArrayOf((p.x - c.x).toDouble(), (p.y - c.y).toDouble(), (p.z - c.z).toDouble()); for (i in 0 until 3) for (j in 0 until 3) m[i][j] += d[i] * d[j] }
        val (vals, vecs) = eigen(m)
        val n = V3(vecs[0][0].toFloat(), vecs[0][1].toFloat(), vecs[0][2].toFloat()).norm()
        return Triple(c, n, (vals[0] / max(vals[1], 1e-12)).toFloat())
    }
}

object Ransac {
    /** Extrai planos sucessivamente: cada um com ≥ [minInliers] pontos a até [tol] metros. */
    fun planes(points: List<V3>, tol: Float, minInliers: Int, rnd: java.util.Random = java.util.Random(7), iters: Int = 220): List<List<V3>> {
        var rest = points
        val out = ArrayList<List<V3>>()
        while (rest.size >= minInliers && out.size < 8) {
            var best: List<V3> = emptyList()
            repeat(iters) {
                val a = rest[rnd.nextInt(rest.size)]; val b = rest[rnd.nextInt(rest.size)]; val c = rest[rnd.nextInt(rest.size)]
                val n = (b - a).cross(c - a); if (n.len() < 1e-9f) return@repeat
                val nn = n.norm()
                val inl = rest.filter { abs((it - a).dot(nn)) < tol }
                if (inl.size > best.size) best = inl
            }
            if (best.size < minInliers) break
            // refina com PCA e recolhe inliers do plano refinado
            val (c, n, _) = Pca.plane(best)
            val refined = rest.filter { abs((it - c).dot(n)) < tol }
            out += refined
            val set = refined.toHashSet(); rest = rest.filter { it !in set }
        }
        return out
    }
}

/**
 * Mapa de fusão em voxels: cada observação (ponto 3D vindo da profundidade ou da nuvem de pontos do SLAM,
 * com a cor da região da imagem onde caiu) vota no voxel. Depois agrupa por cor, separa planos e encaixa peças.
 */
class FusionMap(val cell: Float = .006f) {
    private class Vox(var sx: Float = 0f, var sy: Float = 0f, var sz: Float = 0f, var n: Int = 0, val bins: IntArray = IntArray(Segmenter.BINS), var r: Long = 0, var g: Long = 0, var b: Long = 0)
    private val map = HashMap<Long, Vox>()
    @Volatile var observations = 0; private set
    val voxels get() = map.size

    private fun key(x: Int, y: Int, z: Int) = ((x + 1_000_000).toLong() shl 42) or ((y + 1_000_000).toLong() shl 21) or (z + 1_000_000).toLong()
    private fun unkey(k: Long) = Triple(((k shr 42) and 0x1FFFFF).toInt() - 1_000_000, ((k shr 21) and 0x1FFFFF).toInt() - 1_000_000, (k and 0x1FFFFF).toInt() - 1_000_000)

    @Synchronized fun add(p: V3, hueBin: Int, color: Int) {
        val k = key(kotlin.math.floor(p.x / cell).toInt(), kotlin.math.floor(p.y / cell).toInt(), kotlin.math.floor(p.z / cell).toInt())
        val v = map.getOrPut(k) { Vox() }
        v.sx += p.x; v.sy += p.y; v.sz += p.z; v.n++; v.bins[hueBin]++
        v.r += color shr 16 and 255; v.g += color shr 8 and 255; v.b += color and 255
        observations++
    }

    @Synchronized fun clear() { map.clear(); observations = 0 }

    /** Pontos fundidos (para desenhar a nuvem ao vivo): posição média + cor. */
    @Synchronized fun cloud(minObs: Int = 2): List<Pair<V3, Int>> = map.values.filter { it.n >= minObs }.map {
        V3(it.sx / it.n, it.sy / it.n, it.sz / it.n) to ((0xFF shl 24) or ((it.r / it.n).toInt() shl 16) or ((it.g / it.n).toInt() shl 8) or (it.b / it.n).toInt())
    }

    @Synchronized fun extract(minObs: Int = 2, up: V3 = V3.UP): List<Piece3D> {
        // 1. voxels confiáveis com a cor dominante
        data class Cell(val x: Int, val y: Int, val z: Int, val p: V3, val bin: Int, val color: Int)
        val cells = HashMap<Long, Cell>()
        for ((k, v) in map) {
            if (v.n < minObs) continue
            val bin = v.bins.indices.maxBy { v.bins[it] }
            if (v.bins[bin] < v.n * .6f) continue
            val (x, y, z) = unkey(k)
            cells[k] = Cell(x, y, z, V3(v.sx / v.n, v.sy / v.n, v.sz / v.n), bin, (0xFF shl 24) or ((v.r / v.n).toInt() shl 16) or ((v.g / v.n).toInt() shl 8) or (v.b / v.n).toInt())
        }
        // 2. componentes conexas (26-vizinhança) da mesma cor
        val seen = HashSet<Long>(); val out = ArrayList<Piece3D>()
        for ((k0, c0) in cells) {
            if (k0 in seen) continue
            val comp = ArrayList<Cell>(); val stack = ArrayDeque<Long>(); stack += k0; seen += k0
            while (stack.isNotEmpty()) {
                val c = cells.getValue(stack.removeLast()); comp += c
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    val nk = key(c.x + dx, c.y + dy, c.z + dz)
                    val nc = cells[nk] ?: continue
                    if (nk !in seen && nc.bin == c0.bin) { seen += nk; stack += nk }
                }
            }
            if (comp.size < 12) continue
            // 3. planos dentro do grupo (duas peças da mesma cor em quina viram dois planos)
            val pts = comp.map { it.p }
            val color = averageColor(comp.map { it.color })
            for (plane in Ransac.planes(pts, tol = cell * 1.2f, minInliers = 12)) out += fitPieces(plane, color, up)
        }
        return out
    }

    private fun averageColor(cs: List<Int>): Int {
        var r = 0L; var g = 0L; var b = 0L
        for (c in cs) { r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255 }
        val n = max(1, cs.size)
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** Projeta o plano numa grade 2D, separa ilhas, classifica a forma e devolve a pose 3D de cada peça. */
    private fun fitPieces(pts: List<V3>, color: Int, up: V3): List<Piece3D> {
        val (c, n0, _) = Pca.plane(pts)
        val n = Snap.normal(n0, up)
        // base do plano: em paredes, v = cima; no chão, u qualquer (ajustado pelo OBB depois)
        val v0 = if (abs(n.dot(up)) > .9f) V3(1f, 0f, 0f).let { (it - n * it.dot(n)).norm() } else (up - n * up.dot(n)).norm()
        val u0 = v0.cross(n).norm()
        val res = cell * .5f
        val loc = pts.map { P2((it - c).dot(u0), (it - c).dot(v0)) }
        val minX = loc.minOf { it.x }; val minY = loc.minOf { it.y }
        val gw = ((loc.maxOf { it.x } - minX) / res).toInt() + 3; val gh = ((loc.maxOf { it.y } - minY) / res).toInt() + 3
        if (gw * gh > 400_000) return emptyList()
        val m = Mask(gw, gh)
        for (p in loc) { val x = ((p.x - minX) / res).toInt() + 1; val y = ((p.y - minY) / res).toInt() + 1; for (dx in -1..1) for (dy in -1..1) m[x + dx, y + dy] = true }
        val out = ArrayList<Piece3D>()
        for (island in m.components(minCells = 20)) {
            val fit = ShapeClassifier.classify(island.erode().takeIf { it.count() >= 10 } ?: island) ?: continue
            var ang = fit.obb.angle
            if (abs(n.dot(up)) < .9f) ang = Snap.angle(ang) // paredes: bordas alinhadas à vertical
            val ux = P2(kotlin.math.cos(ang), kotlin.math.sin(ang)); val vy = P2(-ux.y, ux.x)
            val u = (u0 * ux.x + v0 * ux.y).norm(); var v = (u0 * vy.x + v0 * vy.y).norm()
            var uu = u
            if (fit.shape == "equilatero" || fit.shape == "retanguloTri") {
                // Vira a peça se o vértice aponta para -v (mantendo a base destra).
                val apex = fit.obb.vy * (if (fit.apexPositive) 1f else -1f)
                if (apex.dot(vy) < 0) { v = v * -1f; uu = uu * -1f }
            }
            // Medida pela ilha sem erosão. A grade engorda a peça (vizinhança de 1 célula + quantização do voxel);
            // a borda escura (sem cor) a emagrece. o = deslocamento líquido de cada lado.
            val full = Geo2.minAreaRect(Geo2.hull(island.edgePoints()))
            val o = Catalog.FRAME_M - res - cell * .25f
            val tri = fit.shape == "equilatero" || fit.shape == "retanguloTri"
            val wM = full.w * res + o * (if (tri) 3.46f else 2f); val hM = full.h * res + o * (if (tri) 3f else 2f)
            var oc = fit.obb.c
            // Triângulos: o "centro" da peça é o baricentro (1/3 da altura a partir da base), não o centro da caixa.
            if (tri) oc = oc - fit.obb.vy * ((if (fit.apexPositive) 1f else -1f) * fit.obb.h / 6f)
            val cc = c + u0 * (oc.x * res + minX - res) + v0 * (oc.y * res + minY - res)
            val edge = when (fit.shape) { "retangulo" -> hM; "equilatero" -> max(wM, hM / .866f); else -> max(wM, hM) }
            out += Piece3D(fit.shape, Catalog.sizeFor(edge), color, cc, uu, v, uu.cross(v).norm(), wM, hM, island.count())
        }
        return out
    }
}

/** Encaixes: peças reais ficam em pé ou deitadas; pequenos desvios são ruído do sensor. */
object Snap {
    private val tol = Math.toRadians(14.0).toFloat()

    fun normal(n: V3, up: V3): V3 {
        val d = n.dot(up)
        return when {
            abs(d) > kotlin.math.cos(tol) -> if (d > 0) up else up * -1f
            abs(d) < kotlin.math.sin(tol) -> (n - up * d).norm()
            else -> n
        }
    }

    fun angle(a: Float): Float {
        val q = (Math.PI / 2).toFloat()
        val r = Math.round(a / q) * q
        return if (abs(a - r) < tol) r else a
    }
}
