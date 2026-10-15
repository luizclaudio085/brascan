package com.brastoy.brascan.ar

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.brastoy.brascan.vision.Intrinsics
import com.brastoy.brascan.vision.PieceSlam
import com.brastoy.brascan.vision.Pose
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Gravação de diagnóstico do scanner: cada quadro analisado (JPEG 480 px) + uma linha JSON com o que o
 * SLAM viu e decidiu (detecções, pose da câmera, gravidade, mapa). Vira um .zip em
 * Android/data/<app>/files/diagnostico, que o usuário manda por qualquer app. Serve para reproduzir no PC
 * exatamente o que aconteceu no celular, sem adivinhar.
 */
class Diagnostico(private val ctx: Context) {
    private val dir = File(ctx.getExternalFilesDir(null), "diagnostico/" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())).apply { mkdirs() }
    private val log = File(dir, "quadros.jsonl").bufferedWriter()
    var frames = 0; private set
    private var meta = false

    fun frame(rgb: IntArray, w: Int, h: Int, rot: Int, k: Intrinsics, up: DoubleArray?, s: PieceSlam, dets: List<PieceSlam.Detection>, ms: Long) {
        if (!meta) {
            File(dir, "meta.json").writeText(JSONObject().put("w", w).put("h", h).put("rotationDegrees", rot).put("fx", k.fx).put("fy", k.fy).put("cx", k.cx).put("cy", k.cy)
                .put("modelo", android.os.Build.MODEL).put("android", android.os.Build.VERSION.SDK_INT).put("versao", com.brastoy.brascan.BuildConfig.VERSION_NAME).toString(1))
            meta = true
        }
        val bmp = Bitmap.createBitmap(rgb, w, h, Bitmap.Config.ARGB_8888)
        FileOutputStream(File(dir, "q%05d.jpg".format(frames))).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        val o = JSONObject().put("i", frames).put("t", System.currentTimeMillis()).put("ms", ms).put("lost", s.lost).put("matched", s.lastMatched)
        up?.let { o.put("up", JSONArray(it.toList())) }
        s.camera?.let { o.put("cam", pose(it)) }
        o.put("dets", JSONArray(dets.map { d ->
            JSONObject().put("shape", d.shape).put("color", d.color).put("bin", d.bin).put("rms", d.rms).put("area", d.areaPx).put("frontal", d.frontal)
                .put("corners", JSONArray(d.imgCorners.map { JSONArray(it.toList()) })).put("pose", pose(d.candidates[0]))
        }))
        o.put("map", JSONArray(s.map.map { mp ->
            JSONObject().put("id", mp.id).put("shape", mp.shape).put("size", mp.size).put("color", mp.color).put("n", mp.count).put("spread", mp.spread).put("pose", pose(mp.pose))
                .put("votes", JSONObject(mp.votes.mapValues { it.value }))
        }))
        log.write(o.toString()); log.newLine()
        frames++
    }

    private fun pose(p: Pose) = JSONObject().put("r", JSONArray(p.r.toList())).put("t", JSONArray(p.t.toList()))

    /** Fecha, guarda o mapa final e empacota tudo num .zip; devolve o arquivo. */
    fun finish(mapText: String?): File {
        log.close()
        mapText?.let { File(dir, "mapa.txt").writeText(it) }
        val zip = File(dir.parentFile, dir.name + ".zip")
        ZipOutputStream(FileOutputStream(zip).buffered()).use { z ->
            for (f in dir.listFiles().orEmpty()) { z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        dir.deleteRecursively()
        return zip
    }

    companion object {
        fun share(ctx: Context, zip: File) {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", zip)
            val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "BraScan diagnóstico " + zip.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, "Enviar diagnóstico").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
