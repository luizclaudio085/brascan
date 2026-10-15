package com.brastoy.brascan.scan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.brastoy.brascan.vision.Segmenter
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Código de construção do Brastoy Simulator gerado pelo PRÓPRIO codec do simulador
 * (assets/b5codec.js, extraído por tools/extrair_codec_b5.py), rodando num WebView invisível.
 * Depois de codificar, decodifica de volta e compara peça a peça antes de entregar o código.
 */
object B5Export {
    /** Paleta fixa dos códigos B2/B4/B5 do simulador (a ordem é o índice gravado no código). */
    val PALETTE = listOf(0x78caff, 0xb19aff, 0xffba74, 0xff738a, 0xff98c4, 0x79ddb0, 0xffe18a)

    /** Cor mais próxima da paleta em Lab (as cores da foto não cabem no código compacto). */
    fun nearestPalette(rgb: Int): Int {
        val a = FloatArray(3); val b = FloatArray(3)
        Segmenter.lab(rgb, a)
        return PALETTE.minBy { p -> Segmenter.lab(p, b); val dl = (a[0] - b[0]) * .5f; sqrt(dl * dl + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])) }
    }

    sealed interface Result {
        data class Ok(val code: String, val pieces: Int, val maxPosError: Double) : Result
        data class Failed(val message: String) : Result
    }

    /** items no formato do simulador, com cores já na paleta. */
    fun items(placed: List<Placed>): JSONArray {
        val arr = JSONArray()
        for (pl in placed) {
            fun r(v: Float) = Math.round(v * 1e5) / 1e5
            arr.put(JSONObject().put("type", pl.piece.shape).put("size", pl.piece.size)
                .put("color", String.format("#%06x", nearestPalette(pl.piece.color)))
                .put("pos", JSONArray().put(r(pl.center.x)).put(r(pl.center.y)).put(r(pl.center.z)))
                .put("rot", JSONArray(Layout.quat(pl.u, pl.v, pl.n))))
        }
        return arr
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun encode(ctx: Context, placed: List<Placed>): Result = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        main.post {
            val web = WebView(ctx.applicationContext)
            web.settings.javaScriptEnabled = true
            web.settings.allowFileAccess = false
            web.settings.blockNetworkLoads = true
            var finished = false
            fun finish(r: Result) { if (finished) return; finished = true; main.post { web.destroy() }; if (cont.isActive) cont.resume(r) }
            web.webViewClient = object : android.webkit.WebViewClient() {
                // se o processo do WebView cair, o app continua: só a exportação falha
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean { finish(Result.Failed("o codificador parou")); return true }
            }
            web.addJavascriptInterface(object {
                @JavascriptInterface fun ok(code: String, pieces: Int, err: Double) = finish(Result.Ok(code, pieces, err))
                @JavascriptInterface fun fail(msg: String) = finish(Result.Failed(msg))
            }, "BraScan")
            val codec = ctx.assets.open("b5codec.js").bufferedReader().use { it.readText() }
            val project = JSONObject().put("inventoryMode", "unlimited").put("items", items(Layout.exportReady(placed))).toString()
            // exportar → reimportar → comparar → validar
            val check = """
                (async () => {
                  try {
                    const p = $project;
                    const code = await BrastoyCodec.encode(p);
                    const back = await BrastoyCodec.decode(code);
                    if (back.items.length !== p.items.length) throw Error('reimportação com ' + back.items.length + ' de ' + p.items.length + ' peças');
                    let err = 0;
                    p.items.forEach((x, i) => { const y = back.items[i];
                      if (x.type !== y.type || x.size !== y.size || x.color !== y.color) throw Error('peça ' + (i + 1) + ' mudou de tipo/cor');
                      x.pos.forEach((v, k) => err = Math.max(err, Math.abs(v - y.pos[k])));
                      if (Math.abs(Math.abs(x.rot.reduce((s, v, k) => s + v * y.rot[k], 0)) - 1) > 1e-3) throw Error('peça ' + (i + 1) + ' mudou de orientação'); });
                    BraScan.ok(code, back.items.length, err);
                  } catch (e) { BraScan.fail(String(e && e.message || e)); }
                })();
            """.trimIndent()
            val html = "<!doctype html><meta charset=utf-8><script>$codec</script><script>$check</script>"
            web.loadDataWithBaseURL("https://brascan.local/", html, "text/html", "utf-8", null)
            main.postDelayed({ finish(Result.Failed("o codificador não respondeu")) }, 20_000)
        }
    }
}
