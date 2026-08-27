package com.mice.nebulamcp

import android.content.Context

/**
 * V16 lightweight repository layer.
 * Keeps browser state independent from Activity UI without adding a database dependency.
 */
class BrowserRepository(context: Context) {
    private val prefs = context.getSharedPreferences("nebula_browser_v16", Context.MODE_PRIVATE)

    var readerMode: Boolean
        get() = prefs.getBoolean("reader_mode", false)
        set(value) = prefs.edit().putBoolean("reader_mode", value).apply()

    var autoTranslate: Boolean
        get() = prefs.getBoolean("auto_translate", true)
        set(value) = prefs.edit().putBoolean("auto_translate", value).apply()

    var desktopMode: Boolean
        get() = prefs.getBoolean("desktop_mode", false)
        set(value) = prefs.edit().putBoolean("desktop_mode", value).apply()

    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    fun getString(key: String, fallback: String = ""): String = prefs.getString(key, fallback) ?: fallback
}
