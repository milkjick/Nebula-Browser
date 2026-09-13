package com.mice.nebulamcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Agent Memory" — a small persistent key/value + freeform-note store the AI
 * Agent can read and write across sessions via MCP tools (agent_remember /
 * agent_recall / agent_forget / agent_list_memory). Deliberately simple: no
 * embeddings, no relevance ranking — just facts the agent chose to write
 * down, surfaced back to it as plain text in future browser_ai_context calls
 * so it has continuity across tasks without needing the user to repeat
 * themselves every time.
 *
 * Capped so it can't grow unbounded and silently bloat every future prompt.
 */
class AgentMemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nebula_agent_memory", Context.MODE_PRIVATE)

    companion object {
        private const val MAX_FACTS = 100
        private const val MAX_NOTES = 50
        private const val MAX_VALUE_LEN = 2000
    }

    @Synchronized
    fun remember(key: String, value: String) {
        if (key.isBlank()) return
        val facts = factsMap()
        facts[key.trim()] = value.take(MAX_VALUE_LEN)
        val trimmed = if (facts.size > MAX_FACTS) {
            LinkedHashMap(facts.entries.drop(facts.size - MAX_FACTS).associate { it.key to it.value })
        } else facts
        persistFacts(trimmed)
    }

    fun recall(key: String): String? = factsMap()[key.trim()]

    @Synchronized
    fun forget(key: String) {
        val facts = factsMap()
        facts.remove(key.trim())
        persistFacts(facts)
    }

    fun allFacts(): Map<String, String> = factsMap()

    @Synchronized
    fun addNote(text: String) {
        if (text.isBlank()) return
        val notes = notesList().toMutableList()
        notes.add(text.take(MAX_VALUE_LEN))
        val trimmed = if (notes.size > MAX_NOTES) notes.takeLast(MAX_NOTES) else notes
        persistNotes(trimmed)
    }

    fun notes(): List<String> = notesList()

    @Synchronized
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /** Compact text summary suitable for dropping straight into an AI prompt/context. */
    fun summaryText(): String {
        val facts = factsMap()
        val notesText = notesList()
        if (facts.isEmpty() && notesText.isEmpty()) return ""
        return buildString {
            if (facts.isNotEmpty()) {
                appendLine("已记住的事实：")
                facts.forEach { (k, v) -> appendLine("- $k: $v") }
            }
            if (notesText.isNotEmpty()) {
                if (facts.isNotEmpty()) appendLine()
                appendLine("历史笔记：")
                notesText.takeLast(15).forEach { appendLine("- $it") }
            }
        }.trim()
    }

    private fun factsMap(): LinkedHashMap<String, String> {
        val raw = prefs.getString("facts", "{}") ?: "{}"
        val map = LinkedHashMap<String, String>()
        try {
            val obj = JSONObject(raw)
            obj.keys().forEach { k -> map[k] = obj.optString(k) }
        } catch (_: Exception) {}
        return map
    }

    private fun persistFacts(map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("facts", obj.toString()).apply()
    }

    private fun notesList(): List<String> {
        val raw = prefs.getString("notes", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistNotes(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("notes", arr.toString()).apply()
    }
}
