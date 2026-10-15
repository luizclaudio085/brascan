package com.brastoy.brascan.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.brastoy.brascan.three.V3
import com.brastoy.brascan.vision.Evidence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Um projeto salvo: peças (com evidências), como a foto foi tirada, a foto e o mapa do scanner 3D. */
class Project(val name: String, val savedAt: Long, val vista: Vista, val pieces: List<Piece>, val slamMap: String?, val photo: Bitmap?, val pid: String = "")

/**
 * Projetos ficam em arquivos do próprio app (não precisa de permissão nem de internet).
 * "atual" é salvo sozinho a cada mudança: se o app fechar no meio, nada se perde.
 */
class ProjectStore(ctx: Context) {
    private val dir = File(ctx.filesDir, "projetos").apply { mkdirs() }
    data class Entry(val id: String, val name: String, val savedAt: Long, val pieces: Int)

    fun list(): List<Entry> = dir.listFiles { f -> f.name.endsWith(".json") && f.name != "atual.json" }.orEmpty().mapNotNull { f ->
        runCatching { val j = JSONObject(f.readText()); Entry(f.name.removeSuffix(".json"), j.getString("name"), j.getLong("savedAt"), j.getJSONArray("pieces").length()) }.getOrNull()
    }.sortedByDescending { it.savedAt }

    fun save(id: String, p: Project) {
        val j = JSONObject().put("format", "BraScan-projeto").put("version", 1).put("name", p.name).put("savedAt", p.savedAt)
            .put("pid", p.pid).put("vista", p.vista.name).put("pieces", JSONArray(p.pieces.map(::pieceJson)))
        p.slamMap?.let { j.put("slamMap", it) }
        val photoFile = File(dir, "$id.jpg")
        if (p.photo != null) { photoFile.outputStream().use { p.photo.compress(Bitmap.CompressFormat.JPEG, 88, it) }; j.put("photo", photoFile.name) } else photoFile.delete()
        // grava num temporário e troca: um desligamento no meio não corrompe o projeto
        val tmp = File(dir, "$id.json.tmp"); tmp.writeText(j.toString()); tmp.renameTo(File(dir, "$id.json").also { it.delete() })
    }

    fun load(id: String): Project? = runCatching {
        val j = JSONObject(File(dir, "$id.json").readText())
        val photo = j.optString("photo").takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(File(dir, it).path) }
        val arr = j.getJSONArray("pieces")
        Project(j.getString("name"), j.getLong("savedAt"), Vista.valueOf(j.optString("vista", "FRENTE")), List(arr.length()) { pieceFrom(arr.getJSONObject(it)) }, j.optString("slamMap").takeIf { it.isNotBlank() }, photo, j.optString("pid"))
    }.getOrNull()

    fun delete(id: String) { File(dir, "$id.json").delete(); File(dir, "$id.jpg").delete() }

    companion object {
        fun newId() = "p" + System.currentTimeMillis()

        private fun v3(v: V3?) = v?.let { JSONArray(listOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble())) }
        private fun v3(a: JSONArray?) = a?.let { V3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
        private fun num(d: Double) = if (d.isNaN()) JSONObject.NULL else d

        fun pieceJson(p: Piece): JSONObject = JSONObject().put("id", p.id).put("cx", p.cx.toDouble()).put("cy", p.cy.toDouble()).put("w", p.w.toDouble()).put("h", p.h.toDouble())
            .put("shape", p.shape).put("size", p.size).put("color", p.color).put("orient", p.orient?.name ?: JSONObject.NULL)
            .put("depth", p.depth.toDouble()).put("lift", p.lift.toDouble()).put("turn", p.turn).put("spin", p.spin.toDouble()).put("spinTop", p.spinTop.toDouble())
            .put("pos", v3(p.pos) ?: JSONObject.NULL).put("pu", v3(p.pu) ?: JSONObject.NULL).put("pv", v3(p.pv) ?: JSONObject.NULL)
            .put("evidence", p.evidence?.let { e ->
                JSONObject().put("source", e.source.name).put("views", e.views).put("spreadM", num(e.spreadM)).put("fitIoU", num(e.fitIoU.toDouble()))
                    .put("shapeShare", num(e.shapeShare.toDouble())).put("accepted", e.accepted).put("view", e.view)
            } ?: JSONObject.NULL)

        fun pieceFrom(j: JSONObject): Piece {
            fun f(k: String) = j.optDouble(k, 0.0).toFloat()
            val e = j.optJSONObject("evidence")?.let { ej ->
                Evidence(Evidence.Source.valueOf(ej.getString("source")), ej.optInt("views", 1), ej.optDouble("spreadM", Double.NaN),
                    ej.optDouble("fitIoU", Double.NaN).toFloat(), ej.optDouble("shapeShare", Double.NaN).toFloat(), ej.optBoolean("accepted"), ej.optString("view"))
            }
            return Piece(j.getInt("id"), f("cx"), f("cy"), f("w"), f("h"), j.getString("shape"), j.getString("size"), j.getInt("color"),
                j.optString("orient").takeIf { it.isNotBlank() && it != "null" }?.let { Orient.valueOf(it) }, f("depth"), f("lift"), j.optInt("turn"), f("spin"), f("spinTop"),
                v3(j.optJSONArray("pos")), v3(j.optJSONArray("pu")), v3(j.optJSONArray("pv")), e)
        }
    }
}
