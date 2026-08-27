package com.mice.nebulamcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("nebula_history", Context.MODE_PRIVATE)

    data class HistoryItem(val title: String, val url: String, val time: Long)

    fun list(): List<HistoryItem> {
        val arr = JSONArray(prefs.getString("history", "[]"))
        val out = mutableListOf<HistoryItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(HistoryItem(o.getString("title"), o.getString("url"), o.optLong("time", 0)))
        }
        return out.sortedByDescending { it.time }
    }

    fun add(title: String, url: String) {
        val list = list().filterNot { it.url == url }.toMutableList()
        list.add(HistoryItem(title, url, System.currentTimeMillis()))
        // 最多保留500条
        persist(list.take(500))
    }

    fun clear() {
        prefs.edit().putString("history", "[]").apply()
    }

    private fun persist(list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(JSONObject().put("title", h.title).put("url", h.url).put("time", h.time))
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }
}