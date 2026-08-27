package com.mice.nebulamcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Per-origin web permission decisions used by the V16 privacy center. */
class SitePermissionStore(context: Context) {
    private val prefs = context.getSharedPreferences("nebula_site_permissions", Context.MODE_PRIVATE)

    data class Permission(val origin: String, val camera: Boolean, val microphone: Boolean, val location: Boolean)

    fun get(origin: String): Permission {
        val o = JSONObject(prefs.getString(origin, "{}") ?: "{}")
        return Permission(origin, o.optBoolean("camera"), o.optBoolean("microphone"), o.optBoolean("location"))
    }

    fun set(origin: String, camera: Boolean? = null, microphone: Boolean? = null, location: Boolean? = null) {
        val old = get(origin)
        prefs.edit().putString(origin, JSONObject()
            .put("camera", camera ?: old.camera)
            .put("microphone", microphone ?: old.microphone)
            .put("location", location ?: old.location).toString()).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    fun list(): List<Permission> = prefs.all.keys.map { get(it) }.sortedBy { it.origin }
}
