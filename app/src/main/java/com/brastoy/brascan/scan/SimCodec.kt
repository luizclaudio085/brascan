package com.brastoy.brascan.scan

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.brastoy.brascan.three.V3
import com.brastoy.brascan.vision.Segmenter
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Códigos do Brastoy Simulator (B8 por padrão; lê também B7…B1 e JSON) gerados e lidos pelo PRÓPRIO codec do
 * simulador (assets/brastoy-codec.js, extraído por tools/extrair_codec.py), rodando num WebView invisível.
 * Todo código gerado é decodificado de volta e conferido antes de ser entregue.
 */
object SimCodec {
    /** Paleta fixa dos códigos do simulador (a ordem é o índice gravado no código). */
    val PALETTE = listOf(0x78caff, 0xb19aff, 0xffba74, 0xff738a, 0xff98c4, 0x79ddb0, 0xffe18a)

    /** Cor mais próxima da paleta em Lab (as cores da foto não cabem no código). */
    fun nearestPalette(rgb: Int): Int {
        val a = FloatArray(3); val b = FloatArray(3)
        Segmenter.lab(rgb, a)
        return PALETTE.minBy { p -> Segmenter.lab(p, b); val dl = (a[0] - b[0]) * .5f; sqrt(dl * dl + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2])) }
    }

    sealed interface Result {
        data class Ok(val code: String, val pieces: Int, val maxPosError: Double = 0.0) : Result
        data class Failed(val message: String) : Result
    }

    /** O que um código trouxe: construção (peças no formato do simulador) e/ou inventário. */
    data class Decoded(val items: List<Item>, val inventory: Inventory?, val limited: Boolean, val sections: String)
    data class Item(val type: String, val size: String, val color: Int, val pos: V3, val rot: DoubleArray)

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

    /** Construção em B8 (com o inventário junto, se houver), conferida peça a peça depois de reimportada. */
    suspend fun encodeModel(ctx: Context, placed: List<Placed>, inventory: Inventory?): Result {
        val project = JSONObject().put("inventoryMode", if (inventory != null) "limited" else "unlimited").put("items", items(Layout.exportReady(placed)))
        inventory?.let { project.put("inventory", it.toSimJson()) }
        val js = """
            const p = $project;
            const code = BrastoyCodec.encodeB8(p, { pieces: true, inventory: ${inventory != null} });
            const back = await BrastoyCodec.decode(code);
            if (!back.items || back.items.length !== p.items.length) throw Error('reimportação com ' + (back.items ? back.items.length : 0) + ' de ' + p.items.length + ' peças');
            // o B8 reordena as peças: casa cada peça com uma igual do outro lado
            const pool = back.items.slice(); let err = 0;
            for (const x of p.items) {
              let best = -1, bd = 1e9;
              pool.forEach((y, i) => { if (y.type !== x.type || y.size !== x.size || y.color !== x.color) return;
                const d = Math.max(...x.pos.map((v, k) => Math.abs(v - y.pos[k]))); if (d < bd) { bd = d; best = i; } });
              if (best < 0 || bd > 1 / 128) throw Error('uma peça ' + x.type + ' mudou na reimportação');
              const y = pool.splice(best, 1)[0];
              if (Math.abs(Math.abs(x.rot.reduce((s, v, k) => s + v * y.rot[k], 0)) - 1) > 1e-3) throw Error('uma peça ' + x.type + ' mudou de orientação');
              err = Math.max(err, bd);
            }
            ${if (inventory != null) "if (!back.inventory) throw Error('o inventário não voltou');" else ""}
            return { code, pieces: back.items.length, err };
        """.trimIndent()
        return when (val r = run(ctx, js)) {
            is Raw.Ok -> { val o = JSONObject(r.json); Result.Ok(o.getString("code"), o.getInt("pieces"), o.getDouble("err")) }
            is Raw.Failed -> Result.Failed(r.message)
        }
    }

    /** Só o inventário em B8 (o simulador aplica as quantidades e o modo limitado). */
    suspend fun encodeInventory(ctx: Context, inventory: Inventory): Result {
        val js = """
            const p = { inventoryMode: 'limited', inventory: ${inventory.toSimJson()} };
            const code = BrastoyCodec.encodeB8(p, { pieces: false, inventory: true });
            const back = await BrastoyCodec.decode(code);
            for (const k of BrastoyCodec.kinds) if ((back.inventory[k.size] || {})[k.type] !== p.inventory[k.size][k.type]) throw Error('quantidade de ' + k.size + ' ' + k.type + ' mudou');
            return { code, pieces: 0, err: 0 };
        """.trimIndent()
        return when (val r = run(ctx, js)) {
            is Raw.Ok -> Result.Ok(JSONObject(r.json).getString("code"), inventory.total())
            is Raw.Failed -> Result.Failed(r.message)
        }
    }

    /** Lê qualquer código do simulador (B8…B1 ou JSON). */
    suspend fun decode(ctx: Context, text: String): Pair<Decoded?, String?> {
        val js = """
            const p = await BrastoyCodec.decode(${JSONObject.quote(text.trim())});
            const s = p.b8 ? Object.entries(p.b8).filter(([k, v]) => v).map(([k]) => k).join(',') : (p.items ? 'pieces' : '');
            return { items: p.items || [], inventory: p.inventory || null, limited: p.inventoryMode === 'limited', sections: s };
        """.trimIndent()
        return when (val r = run(ctx, js)) {
            is Raw.Failed -> null to r.message
            is Raw.Ok -> {
                val o = JSONObject(r.json)
                val items = parseItems(o.getJSONArray("items"))
                val inv = if (o.isNull("inventory")) null else Inventory.fromSimJson(o.getJSONObject("inventory"))
                Decoded(items, inv, o.getBoolean("limited"), o.getString("sections")) to null
            }
        }
    }

    /** items[{type,size,color,pos,rot}] do simulador → [Item]. */
    fun parseItems(arr: JSONArray): List<Item> = (0 until arr.length()).map { i ->
        val x = arr.getJSONObject(i); val p = x.getJSONArray("pos"); val q = x.optJSONArray("rot")
        val c = x.opt("color").let { c -> if (c is Number) c.toInt() else Integer.parseInt(c.toString().removePrefix("#"), 16) } or (0xFF shl 24)
        Item(x.getString("type"), x.getString("size"), c, V3(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat()),
            if (q == null) doubleArrayOf(0.0, 0.0, 0.0, 1.0) else DoubleArray(4) { q.getDouble(it) })
    }

    /**
     * Peças do app a partir dos items de um código: posição exata e base (u, v) = 1ª e 2ª colunas da matriz de
     * rotação do quatérnio (x, y, z, w), a mesma convenção de [Layout.quat] (ida e volta sem diferença).
     */
    fun pieces(items: List<Item>, firstId: Int, evidence: com.brastoy.brascan.vision.Evidence = com.brastoy.brascan.vision.Evidence(com.brastoy.brascan.vision.Evidence.Source.CODIGO)): List<Piece> = items.mapIndexed { i, it ->
        val n = sqrt(it.rot.sumOf { q -> q * q }).takeIf { l -> l > 1e-9 } ?: 1.0
        val (x, y, z, w) = it.rot.map { q -> (q / n).toFloat() }
        val u = V3(1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w))
        val v = V3(2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w))
        Piece(firstId + i, shape = it.type, size = it.size, color = it.color, pos = it.pos, pu = u, pv = v, evidence = evidence)
    }

    private sealed interface Raw { data class Ok(val json: String) : Raw; data class Failed(val message: String) : Raw }

    /** Roda [body] (corpo de função async que devolve um objeto) com o codec carregado; resultado em JSON. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun run(ctx: Context, body: String): Raw = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        main.post {
            val web = WebView(ctx.applicationContext)
            web.settings.javaScriptEnabled = true
            web.settings.allowFileAccess = false
            web.settings.blockNetworkLoads = true
            var finished = false
            fun finish(r: Raw) { if (finished) return; finished = true; main.post { web.destroy() }; if (cont.isActive) cont.resume(r) }
            web.webViewClient = object : android.webkit.WebViewClient() {
                // se o processo do WebView cair, o app continua: só esta operação falha
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean { finish(Raw.Failed("o codificador parou")); return true }
            }
            web.addJavascriptInterface(object {
                @JavascriptInterface fun ok(json: String) = finish(Raw.Ok(json))
                @JavascriptInterface fun fail(msg: String) = finish(Raw.Failed(msg))
            }, "BraScan")
            val codec = ctx.assets.open("brastoy-codec.js").bufferedReader().use { it.readText() }
            val script = "(async () => { try { const r = await (async () => { $body })(); BraScan.ok(JSON.stringify(r)); } catch (e) { BraScan.fail(String(e && e.message || e)); } })();"
            val html = "<!doctype html><meta charset=utf-8><script>$codec</script><script>$script</script>"
            web.loadDataWithBaseURL("https://brascan.local/", html, "text/html", "utf-8", null)
            main.postDelayed({ finish(Raw.Failed("o codificador não respondeu")) }, 20_000)
        }
    }
}
