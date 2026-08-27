package com.mice.nebulamcp

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Small ABP/uBO-compatible domain filter layer designed for a lightweight WebView browser. */
class AdBlockStore(private val context: Context) {
    data class Subscription(val id: String, val name: String, val url: String, val enabled: Boolean, val ruleCount: Int)

    private val prefs = context.getSharedPreferences("nebula_adblock", Context.MODE_PRIVATE)
    private val dir = File(context.getExternalFilesDir(null), "filters")
    private val customFile = File(dir, "custom.rules")
    @Volatile private var cachedHostRules: List<String> = emptyList()
    @Volatile private var cachedCosmeticRules: List<String> = emptyList()

    init { dir.mkdirs(); reloadCache() }

    @Synchronized fun addCustomRules(text: String) {
        dir.mkdirs()
        val existingLines = (if (customFile.exists()) customFile.readText() else "").lines()
        val newLines = text.lines()
        val merged = (existingLines + newLines).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        customFile.writeText(merged.joinToString("\n") + "\n")
        reloadCache()
    }

    /** Strips duplicate lines that accumulated in custom rules before dedup-on-append existed. */
    @Synchronized fun deduplicateCustomRules(): Int {
        val lines = customRules().lines().map { it.trim() }.filter { it.isNotEmpty() }
        val distinctLines = lines.distinct()
        customFile.writeText(distinctLines.joinToString("\n") + "\n")
        reloadCache()
        return lines.size - distinctLines.size
    }

    @Synchronized fun setCustomRules(text: String) { dir.mkdirs(); customFile.writeText(text); reloadCache() }
    fun customRules(): String = if (customFile.exists()) customFile.readText() else ""

    @Synchronized fun importSubscription(name: String, url: String, text: String): Subscription {
        val isRemote = url.startsWith("http")
        val existing = if (isRemote) subscriptions().find { it.url == url } else null
        val id = existing?.id ?: UUID.randomUUID().toString()
        File(dir, "$id.txt").writeText(text)
        val arr = subscriptions().toMutableList()
        val sub = Subscription(
            id,
            name.ifBlank { existing?.name ?: "Filter ${arr.size + 1}" },
            url,
            existing?.enabled ?: true,
            parseRules(text).size
        )
        val idx = arr.indexOfFirst { it.id == id }
        if (idx >= 0) arr[idx] = sub else arr.add(sub)
        persist(arr)
        reloadCache()
        return sub
    }

    /** Removes subscription rows pointing at the same remote URL, keeping the first. Returns how many were removed. */
    @Synchronized fun deduplicateSubscriptions(): Int {
        val subs = subscriptions()
        val seenUrls = mutableSetOf<String>()
        val keep = mutableListOf<Subscription>()
        var removed = 0
        subs.forEach { s ->
            val isRemote = s.url.startsWith("http")
            if (isRemote && !seenUrls.add(s.url)) {
                File(dir, "${s.id}.txt").delete()
                removed++
            } else {
                keep.add(s)
            }
        }
        persist(keep)
        reloadCache()
        return removed
    }

    fun importSubscriptionFromUrl(name: String, url: String): Subscription {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "NebulaBrowser/1.0")
        conn.connect()
        if (conn.responseCode !in 200..299) error("订阅下载失败 HTTP ${conn.responseCode}")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        return importSubscription(name, url, text)
    }

    fun importSubscriptionFile(name: String, uri: Uri): Subscription {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("无法读取过滤器文件")
        return importSubscription(name, "file://local", text)
    }

    @Synchronized fun remove(id: String) {
        File(dir, "$id.txt").delete()
        persist(subscriptions().filterNot { it.id == id }); reloadCache()
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        persist(subscriptions().map { if (it.id == id) it.copy(enabled = enabled) else it }); reloadCache()
    }

    fun subscriptions(): List<Subscription> {
        val raw = prefs.getString("subs", "[]") ?: "[]"
        val result = mutableListOf<Subscription>()
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val file = File(dir, "$id.txt")
                result.add(Subscription(id, o.getString("name"), o.optString("url"), o.optBoolean("enabled", true), if (file.exists()) parseRules(file.readText()).size else 0))
            }
        } catch (_: Exception) {}
        return result
    }

    /**
     * Conservative request matcher.  The previous implementation treated almost any
     * dotted token as a domain rule, which made ordinary CDN/API URLs easy to kill.
     * We now require an explicit ABP domain rule (||example.com^), never block the
     * top-level site itself, and never block common media/static assets here.
     */
    fun isBlocked(url: String): Boolean = isBlocked(url, null)

    fun isBlocked(url: String, pageUrl: String?): Boolean {
        val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = parsed.host?.lowercase() ?: return false
        if (host.isBlank()) return false

        // Never use the network filter to break media/image/font/style delivery.
        if (isStaticAsset(url)) return false

        val pageHost = pageUrl?.let { runCatching { Uri.parse(it).host?.lowercase() }.getOrNull() }
        if (!pageHost.isNullOrBlank() && sameSite(host, pageHost)) return false

        val exceptions = cachedHostRules.filter { it.startsWith("@@||") }
        if (exceptions.any { matchesExplicitDomainRule(it.removePrefix("@@"), host) }) return false

        return cachedHostRules.any { rule ->
            !rule.startsWith("@@") && matchesExplicitDomainRule(rule, host)
        }
    }

    private fun matchesExplicitDomainRule(rule: String, host: String): Boolean {
        if (!rule.startsWith("||")) return false
        val domain = rule.removePrefix("||")
            .substringBeforeAny(charArrayOf('/', '^', '*', '$', '?'))
            .trim('.')
            .lowercase()
        if (domain.isBlank() || !domain.contains('.')) return false
        return host == domain || host.endsWith(".$domain")
    }

    private fun sameSite(a: String, b: String): Boolean =
        a == b || a.endsWith(".$b") || b.endsWith(".$a")

    private fun isStaticAsset(url: String): Boolean {
        val path = runCatching { Uri.parse(url).path.orEmpty().lowercase() }.getOrDefault("")
        return path.matches(Regex(".*\\.(?:png|jpe?g|gif|webp|avif|svg|ico|bmp|mp4|m4v|webm|mov|m3u8|ts|m4s|aac|mp3|wav|ogg|flac|woff2?|ttf|otf|css)(?:$|/).*"))
    }

    /** CSS selectors from common ## rules; deliberately ignores script/redirect rules. */
    fun cosmeticCss(url: String): String {
        val host = try { Uri.parse(url).host } catch (_: Exception) { null } ?: return ""
        val css = mutableListOf<String>()
        cachedCosmeticRules.forEach { line ->
            val idx = line.indexOf("##")
            if (idx == 0) {
                val selector = line.substring(2).trim()
                if (selector.isNotBlank()) css += "$selector{display:none !important;}"
            } else if (idx > 0 && !line.contains("#@#")) {
                val domains = line.substring(0, idx).split(',')
                if (domains.any { d -> d.trim().removePrefix("~.").let { host == it || host.endsWith(".$it") } }) {
                    val selector = line.substring(idx + 2).trim()
                    if (selector.isNotBlank() && selector.length < 1000) css += "$selector{display:none !important;}"
                }
            }
        }
        return css.joinToString("\n")
    }

    @Synchronized fun updateAll(): Int {
        var updated = 0
        subscriptions().filter { it.enabled && it.url.startsWith("http") }.forEach { sub ->
            try {
                val conn = (URL(sub.url).openConnection() as HttpURLConnection)
                conn.connectTimeout = 10000; conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "NebulaBrowser/1.0")
                conn.connect()
                if (conn.responseCode in 200..299) { File(dir, "${sub.id}.txt").writeText(conn.inputStream.bufferedReader().use { it.readText() }); updated++ }
            } catch (_: Exception) {}
        }
        reloadCache()
        return updated
    }

    private fun reloadCache() {
        val host = mutableListOf<String>()
        val cosmetic = mutableListOf<String>()
        host += parseRules(customRules())
        cosmetic += customRules().lineSequence().map { it.trim() }.filter { it.contains("##") && !it.startsWith("!") }.toList()
        subscriptions().filter { it.enabled }.forEach { sub ->
            val f = File(dir, "${sub.id}.txt")
            if (f.exists()) {
                val text = f.readText()
                host += parseRules(text)
                cosmetic += text.lineSequence().map { it.trim() }.filter { it.contains("##") && !it.startsWith("!") }.toList()
            }
        }
        cachedHostRules = host.distinct()
        cachedCosmeticRules = cosmetic.distinct()
    }

    private fun parseRules(text: String): List<String> = text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("!") && !it.startsWith("[") }
        .filter { !it.contains("##") && !it.contains("#@#") && !it.contains("#$#") }
        .filter { it.length < 1000 }
        .toList()

    private fun String.substringBeforeAny(chars: CharArray): String {
        val p = indexOfAny(chars)
        return if (p < 0) this else substring(0, p)
    }

    private fun persist(list: List<Subscription>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("enabled", it.enabled)) }
        prefs.edit().putString("subs", arr.toString()).apply()
    }
}
