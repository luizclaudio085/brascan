package com.brastoy.brascan.vision

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Reproduz no PC um diagnóstico gravado no celular (botão ⏺ Gravar no scanner): roda o MESMO pipeline
 * (Segmenter + PieceSlam) nos quadros gravados e compara com o que o celular decidiu na hora.
 *
 *   python tools/diagnostico.py extrair <zip>            # quadros .jpg → .argb.gz (o classpath de teste não lê JPEG)
 *   ./gradlew :app:testDebugUnitTest --tests '*DiagReplayProbe*' -Ddiag=<pasta extraída>
 *   python tools/diagnostico.py desenhar <pasta>         # quadros anotados (celular × PC)
 *
 * Sem -Ddiag o teste é pulado (não é regressão: é ferramenta).
 */
class DiagReplayProbe {
    private fun load(f: File): Triple<IntArray, Int, Int> = DataInputStream(GZIPInputStream(f.inputStream().buffered())).use { d ->
        val w = d.readInt(); val h = d.readInt(); Triple(IntArray(w * h) { d.readInt() }, w, h)
    }

    @Test fun replay() {
        val path = System.getProperty("diag") ?: System.getenv("BRASCAN_DIAG")
        assumeTrue("passe -Ddiag=<pasta extraída do zip do celular>", path != null && File(path, "quadros.jsonl").exists())
        val dir = File(path!!)
        val meta = JSONObject(File(dir, "meta.json").readText())
        val k = Intrinsics(meta.getDouble("fx"), meta.getDouble("fy"), meta.getDouble("cx"), meta.getDouble("cy"))
        println("celular ${meta.optString("modelo")} android ${meta.optInt("android")} app ${meta.optString("versao")}: ${meta.getInt("w")}x${meta.getInt("h")} rot ${meta.optInt("rotationDegrees")} fx=%.1f".format(k.fx))
        val slam = PieceSlam(k)
        val lines = File(dir, "quadros.jsonl").readLines().filter { it.isNotBlank() }
        val replay = File(dir, "replay.jsonl").bufferedWriter()
        var agreeDets = 0; var totalDets = 0; var phoneDets = 0
        for (line in lines) {
            val o = JSONObject(line); val i = o.getInt("i")
            val f = File(dir, "q%05d.argb.gz".format(i)); if (!f.exists()) continue
            val (rgb, w, h) = load(f)
            val up = o.optJSONArray("up")?.let { a -> DoubleArray(3) { a.getDouble(it) } }
            val t0 = System.nanoTime()
            val seg = Segmenter.segment(rgb, w, h, minFrac = .0015f)
            val dets = slam.track(seg, up)
            val ms = (System.nanoTime() - t0) / 1e6
            val phone = o.getJSONArray("dets")
            totalDets += dets.size; phoneDets += phone.length(); if (dets.size == phone.length()) agreeDets++
            println("q$i: PC ${dets.size} det (${dets.joinToString { "${it.shape} rms=%.1f".format(it.rms) }}) · celular ${phone.length()} det · mapa PC ${slam.map.size} / celular ${o.getJSONArray("map").length()} · lost PC ${slam.lost} / cel ${o.optBoolean("lost")} · %.0f ms PC / ${o.optLong("ms")} ms celular".format(ms))
            replay.write(JSONObject().put("i", i).put("lost", slam.lost).put("map", slam.map.size)
                .put("dets", JSONArray(dets.map { d -> JSONObject().put("shape", d.shape).put("rms", d.rms).put("corners", JSONArray(d.imgCorners.map { JSONArray(it.toList()) })) })).toString())
            replay.newLine()
        }
        replay.close()
        println("quadros: ${lines.size} · detecções PC $totalDets / celular $phoneDets · quadros com a mesma contagem $agreeDets")
        println("mapa final no PC (${slam.map.size} peças, ${slam.pieces().size} confirmadas):")
        for (mp in slam.map) println("  #${mp.id} ${mp.shape} ${mp.size} n=${mp.count} spread=%.3f t=(%.3f, %.3f, %.3f) votos=${mp.votes}".format(mp.spread, mp.pose.t[0], mp.pose.t[1], mp.pose.t[2]))
        File(dir, "mapa.txt").takeIf { it.exists() }?.let { println("mapa final no celular:\n" + it.readText().take(2000)) }
    }
}
