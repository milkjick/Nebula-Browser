package com.mice.nebulamcp

import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

data class NetworkEntry(
    val id: Long,
    val url: String,
    val method: String = "GET",
    val mimeType: String? = null,
    val statusCode: Int = 0,
    val source: String = "webview",
    val timestamp: Long = System.currentTimeMillis()
)

class NetworkInspector(private val maxEntries: Int = 500) {
    private val seq = AtomicLong(0)
    private val entries = Collections.synchronizedList(mutableListOf<NetworkEntry>())

    fun record(url: String, method: String = "GET", statusCode: Int = 0, mimeType: String? = null) {
        if (url.isBlank()) return
        synchronized(entries) {
            entries.add(NetworkEntry(seq.incrementAndGet(), url, method, mimeType, statusCode))
            while (entries.size > maxEntries) entries.removeAt(0)
        }
    }

    fun snapshot(): List<NetworkEntry> = synchronized(entries) { entries.toList() }

    fun clear() = synchronized(entries) { entries.clear() }

    fun toHarJson(): String {
        val root = JSONObject()
        root.put("log", JSONObject()
            .put("version", "1.2")
            .put("creator", JSONObject().put("name", "Nebula Browser").put("version", "0.4.1"))
        )
        val list = org.json.JSONArray()
        snapshot().forEach {
            list.put(JSONObject()
                .put("startedDateTime", java.util.Date(it.timestamp).toString())
                .put("request", JSONObject().put("method", it.method).put("url", it.url).put("httpVersion", "HTTP/1.1"))
                .put("response", JSONObject().put("status", it.statusCode).put("content", JSONObject().put("mimeType", it.mimeType ?: "")))
            )
        }
        root.getJSONObject("log").put("entries", list)
        return root.toString(2)
    }
}
