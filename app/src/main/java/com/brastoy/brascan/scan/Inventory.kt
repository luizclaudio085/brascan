package com.brastoy.brascan.scan

import android.content.Context
import com.brastoy.brascan.vision.Catalog
import org.json.JSONObject

/**
 * Inventário de peças: quantas de cada um dos 30 pares tamanho/formato do simulador (mesma ordem de `kinds` do
 * codec: os 22 do B2 e depois os 8 novos). É o que vai na seção "inventário" do código B8.
 */
class Inventory(counts: Map<String, Int> = emptyMap()) {
    data class Kind(val size: String, val type: String) { val key get() = "$size/$type" }

    private val counts = HashMap<String, Int>().apply { for (k in KINDS) put(k.key, (counts[k.key] ?: 0).coerceIn(0, MAX)) }

    operator fun get(k: Kind) = counts[k.key] ?: 0
    fun get(size: String, type: String) = counts["$size/$type"] ?: 0
    fun total() = counts.values.sum()
    fun copy(change: (HashMap<String, Int>) -> Unit = {}) = Inventory(HashMap(counts).also(change))
    fun with(k: Kind, n: Int) = copy { it[k.key] = n.coerceIn(0, MAX) }
    /** Soma uma contagem (um lote fotografado) ao inventário. */
    fun plus(add: Map<String, Int>) = copy { m -> for ((k, n) in add) if (m.containsKey(k)) m[k] = ((m[k] ?: 0) + n).coerceIn(0, MAX) }

    /** {grande:{quadrado:n,…}, medio:{…}, pequeno:{…}} como o simulador grava. */
    fun toSimJson(): JSONObject {
        val o = JSONObject()
        for (k in KINDS) { if (!o.has(k.size)) o.put(k.size, JSONObject()); o.getJSONObject(k.size).put(k.type, this[k]) }
        return o
    }

    fun toJson() = JSONObject(counts as Map<*, *>).toString()

    companion object {
        /** O código B8 guarda até 255 de cada. */
        const val MAX = 255

        val KINDS: List<Kind> = run {
            val legacy = listOf("quadrado", "janela", "moldura", "retangulo", "equilatero", "retanguloTri", "isoceles", "E", "L", "porta").map { Kind("grande", it) } +
                listOf("quadrado", "janela", "moldura", "retangulo", "equilatero", "retanguloTri", "isoceles", "L", "porta", "encaixe").map { Kind("medio", it) } +
                listOf("equilatero", "retanguloTri").map { Kind("pequeno", it) }
            val all = mapOf(
                "grande" to listOf("quadrado", "janela", "moldura", "retangulo", "equilatero", "retanguloTri", "isoceles", "E", "L", "porta"),
                "medio" to listOf("quadrado", "janela", "moldura", "retangulo", "equilatero", "retanguloTri", "isoceles", "L", "porta", "encaixe", "E"),
                "pequeno" to listOf("equilatero", "retanguloTri", "quadrado", "janela", "moldura", "retangulo", "isoceles", "L", "porta"),
            ).flatMap { (s, ts) -> ts.map { Kind(s, it) } }
            legacy + all.filter { it !in legacy }
        }

        fun fromSimJson(o: JSONObject) = Inventory(KINDS.associate { k -> k.key to (o.optJSONObject(k.size)?.optInt(k.type, 0) ?: 0) })
        fun fromJson(s: String) = runCatching { val o = JSONObject(s); Inventory(KINDS.associate { it.key to o.optInt(it.key, 0) }) }.getOrNull()

        fun label(k: Kind) = (Catalog.LABEL[k.type] ?: k.type)
        fun sizeLabel(size: String) = when (size) { "grande" -> "Grande"; "medio" -> "Médio"; else -> "Pequeno" }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("inventario", Context.MODE_PRIVATE)
        fun load(ctx: Context) = prefs(ctx).getString("atual", null)?.let(::fromJson) ?: Inventory()
        fun save(ctx: Context, inv: Inventory) = prefs(ctx).edit().putString("atual", inv.toJson()).apply()
    }
}
