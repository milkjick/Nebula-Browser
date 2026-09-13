package com.mice.nebulamcp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small state holder for the on-device F12-style tools. */
class DevToolsSession {
    data class Entry(val time: Long = System.currentTimeMillis(), val type: String, val text: String)
    private val entries = mutableListOf<Entry>()

    @Synchronized fun add(type: String, text: String) {
        entries += Entry(type = type, text = text)
        if (entries.size > 1000) entries.removeAt(0)
    }

    @Synchronized fun clear() = entries.clear()

    @Synchronized fun text(): String = entries.joinToString("\n") {
        val t = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(it.time))
        "[$t] ${it.type.uppercase()}  ${it.text}"
    }
}
