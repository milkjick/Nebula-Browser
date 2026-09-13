package com.mice.nebulamcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.sqrt

/** Small dependency-free vector memory index. Uses deterministic hashed bag-of-words embeddings. */
class VectorMemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nebula_vector_memory", Context.MODE_PRIVATE)
    private val dim = 128
    private val maxItems = 500

    data class Hit(val id: String, val text: String, val score: Double, val metadata: JSONObject)

    @Synchronized fun upsert(id: String, text: String, metadata: JSONObject = JSONObject()) {
        if (id.isBlank() || text.isBlank()) return
        val all = load().filterNot { it.optString("id") == id }.toMutableList()
        val o = JSONObject().put("id", id).put("text", text.take(6000)).put("metadata", metadata).put("embedding", vector(text))
        all.add(o)
        while (all.size > maxItems) all.removeAt(0)
        save(all)
    }

    fun search(query: String, limit: Int = 8): List<Hit> {
        if (query.isBlank()) return emptyList()
        val q = vector(query)
        return load().map { o ->
            Hit(o.optString("id"), o.optString("text"), cosine(q, readVector(o.optJSONArray("embedding"))), o.optJSONObject("metadata") ?: JSONObject())
        }.sortedByDescending { it.score }.take(limit.coerceIn(1, 30))
    }

    @Synchronized fun remove(id: String) { save(load().filterNot { it.optString("id") == id }) }
    @Synchronized fun clear() { prefs.edit().remove("items").apply() }
    fun size(): Int = load().size

    fun summary(query: String, limit: Int = 6): String = search(query, limit).joinToString("\n") { "[${String.format(Locale.US, "%.3f", it.score)}] ${it.text}" }

    private fun vector(text: String): JSONArray {
        val a = DoubleArray(dim)
        val tokens = Regex("[\\p{L}\\p{N}]{2,}").findAll(text.lowercase(Locale.getDefault())).map { it.value }.toList()
        for (token in tokens) {
            val h = token.hashCode()
            val i = (h and Int.MAX_VALUE) % dim
            a[i] += 1.0
            a[(i * 31 + 7) % dim] += if (h and 1 == 0) 0.5 else -0.5
        }
        var norm = sqrt(a.sumOf { it * it }); if (norm == 0.0) norm = 1.0
        return JSONArray().apply { a.forEach { put(it / norm) } }
    }

    private fun readVector(a: JSONArray?): DoubleArray = DoubleArray(dim) { i -> a?.optDouble(i, 0.0) ?: 0.0 }
    private fun cosine(a: JSONArray, b: DoubleArray): Double { var s=0.0; for(i in 0 until dim) s += a.optDouble(i,0.0)*b[i]; return s }
    private fun load(): MutableList<JSONObject> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try { val a=JSONArray(raw); MutableList(a.length()) { a.optJSONObject(it) ?: JSONObject() }.filter { it.has("id") }.toMutableList() } catch (_:Exception) { mutableListOf() }
    }
    private fun save(items: List<JSONObject>) { val a=JSONArray(); items.forEach { a.put(it) }; prefs.edit().putString("items", a.toString()).apply() }
}
