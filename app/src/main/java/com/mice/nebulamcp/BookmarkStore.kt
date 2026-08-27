package com.mice.nebulamcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class BookmarkStore(context: Context) {
    private val prefs = context.getSharedPreferences("nebula_bookmarks", Context.MODE_PRIVATE)

    data class Bookmark(val title: String, val url: String, val time: Long)

    fun list(): List<Bookmark> {
        val arr = JSONArray(prefs.getString("bookmarks", "[]"))
        val out = mutableListOf<Bookmark>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Bookmark(o.getString("title"), o.getString("url"), o.optLong("time", 0)))
        }
        return out.sortedByDescending { it.time }
    }

    fun add(title: String, url: String) {
        val list = list().filterNot { it.url == url }.toMutableList()
        list.add(Bookmark(title, url, System.currentTimeMillis()))
        persist(list)
    }

    fun remove(url: String) {
        persist(list().filterNot { it.url == url })
    }

    fun contains(url: String): Boolean = list().any { it.url == url }

    private fun persist(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().put("title", b.title).put("url", b.url).put("time", b.time))
        }
        prefs.edit().putString("bookmarks", arr.toString()).apply()
    }
}