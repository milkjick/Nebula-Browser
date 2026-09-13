package com.mice.nebulamcp

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Lightweight Tampermonkey/Via-style userscript store. */
class UserScriptStore(private val context: Context) {
    data class Script(
        val id: String,
        val name: String,
        val source: String,
        val enabled: Boolean = true,
        val matches: List<String> = listOf("*://*/*"),
        val excludes: List<String> = emptyList(),
        val runAt: String = "document-start"
    )

    private val dir = File(context.getExternalFilesDir(null), "scripts")

    init { dir.mkdirs() }

    @Synchronized fun list(): List<Script> = dir.listFiles { f -> f.name.endsWith(".user.js") }
        ?.mapNotNull { read(it) }
        ?.sortedBy { it.name.lowercase() } ?: emptyList()

    @Synchronized fun save(script: Script) {
        dir.mkdirs()
        val safe = script.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        File(dir, "$safe.user.js").writeText(script.source)
        File(dir, "$safe.meta").writeText(buildMeta(script))
    }

    @Synchronized fun delete(id: String) {
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        File(dir, "$safe.user.js").delete()
        File(dir, "$safe.meta").delete()
    }

    @Synchronized fun setEnabled(id: String, enabled: Boolean) {
        val s = list().find { it.id == id } ?: return
        save(s.copy(enabled = enabled))
    }

    /**
     * Removes scripts that are byte-for-byte duplicates of another script
     * (same source content, regardless of name/id) — e.g. the same URL or
     * file imported more than once before dedup-on-import existed. Keeps
     * the first occurrence in list() order (alphabetical by name), deletes
     * the rest. Returns how many were removed.
     */
    @Synchronized fun deduplicate(): Int {
        val seen = mutableSetOf<String>()
        var removed = 0
        list().forEach { script ->
            val hash = contentHash(script.source)
            if (!seen.add(hash)) {
                delete(script.id)
                removed++
            }
        }
        return removed
    }

    /** Returns the existing script with identical content, if any. */
    private fun findDuplicate(source: String): Script? {
        val hash = contentHash(source)
        return list().find { contentHash(it.source) == hash }
    }

    private fun contentHash(source: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(source.trim().toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** @return the script and whether it was newly imported (false = an identical script already existed). */
    fun importText(source: String, fallbackName: String = "Imported Script"): Pair<Script, Boolean> {
        val prepared = inlineRequires(source)
        findDuplicate(prepared)?.let { return it to false }
        val meta = parse(prepared)
        val id = UUID.randomUUID().toString()
        val script = Script(
            id = id,
            name = meta.name.ifBlank { fallbackName },
            source = prepared,
            enabled = true,
            matches = if (meta.matches.isEmpty()) listOf("*://*/*") else meta.matches,
            excludes = meta.excludes,
            runAt = meta.runAt
        )
        save(script)
        cacheResources(prepared, id)
        return script to true
    }

    fun importFile(uri: Uri): Pair<Script, Boolean> {
        val source = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("无法读取脚本文件")
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Imported Script"
        return importText(source, name)
    }

    /**
     * Import a userscript directly from an install link. The optional referer/cookies
     * are important for script hosts that require the browser session used to view
     * the install page.
     */
    fun importUrl(url: String, referer: String? = null): Pair<Script, Boolean> {
        require(url.startsWith("http://") || url.startsWith("https://")) { "无效的脚本 URL" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/plain, application/javascript, text/javascript, application/x-javascript, */*")
            if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
            if (!referer.isNullOrBlank()) {
                runCatching {
                    CookieManager.getInstance().getCookie(referer)
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            }
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) error("下载脚本失败 HTTP ${conn.responseCode}")
            val source = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (!looksLikeUserscript(source)) {
                error("目标内容不是有效的 Userscript，可能是网页、验证码或下载页")
            }
            val finalUrl = conn.url?.toString().orEmpty().ifBlank { url }
            val name = URL(finalUrl).path.substringAfterLast('/').ifBlank { "Remote Script" }
                .substringBeforeLast('.')
                .ifBlank { "Remote Script" }
            return importText(source, name)
        } finally {
            conn.disconnect()
        }
    }

    private fun looksLikeUserscript(source: String): Boolean {
        if (source.length < 20) return false
        val header = source.take(20000)
        val hasHeader = header.contains("==UserScript==", ignoreCase = false) &&
            header.contains("==/UserScript==", ignoreCase = false)
        if (hasHeader) return true
        // Some compatible scripts use only the metadata keys without the exact
        // delimiters. Still require @match/@include so normal HTML is rejected.
        val hasMeta = Regex("(?m)^\\s*//\\s*@(?:match|include)\\s+").containsMatchIn(header)
        val looksHtml = Regex("(?is)<(?:html|head|body|!doctype)\\b").containsMatchIn(header)
        return hasMeta && !looksHtml
    }

    fun matchingScripts(url: String): List<Script> = list().filter {
        it.enabled && matchesAny(it.matches, url) && it.excludes.none { pattern -> patternMatches(pattern, url) }
    }

    /** Return a previously downloaded @resource without doing network I/O on the WebView thread. */
    fun readCachedResource(scriptId: String, resourceName: String): String? {
        val safeId = scriptId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val safeName = resourceName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val file = File(File(dir, "resources/$safeId"), safeName + ".txt")
        return runCatching { if (file.exists()) file.readText() else null }.getOrNull()
    }

    private fun cacheResources(source: String, scriptId: String) {
        val resources = Regex("@resource\\s+([^\\s]+)\\s+(https?://\\S+)").findAll(source)
            .map { it.groupValues[1] to it.groupValues[2] }
            .distinctBy { it.first }
            .toList()
        if (resources.isEmpty()) return
        val outDir = File(dir, "resources/${scriptId.replace(Regex("[^A-Za-z0-9_-]"), "_")}").apply { mkdirs() }
        resources.forEach { (name, url) ->
            val target = File(outDir, name.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".txt")
            if (target.exists() && target.length() > 0) return@forEach
            runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 15000
                    setRequestProperty("User-Agent", "NebulaBrowser/20.3.4")
                    instanceFollowRedirects = true
                }
                if (c.responseCode in 200..299) {
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    if (text.isNotBlank()) target.writeText(text)
                }
                c.disconnect()
            }
        }
    }

    private fun read(file: File): Script? = try {
        val source = file.readText()
        val meta = parse(source)
        val id = file.name.removeSuffix(".user.js")
        val enabled = !File(dir, "$id.meta").readTextOrNull().orEmpty().contains("enabled=false")
        Script(
            id = id,
            name = meta.name.ifBlank { file.nameWithoutExtension },
            source = source,
            enabled = enabled,
            matches = if (meta.matches.isEmpty()) listOf("*://*/*") else meta.matches,
            excludes = meta.excludes,
            runAt = meta.runAt
        )
    } catch (_: Exception) { null }

    private fun buildMeta(s: Script) = "enabled=${s.enabled}\nname=${s.name}\nmatches=${s.matches.joinToString("\u001f")}\nrunAt=${s.runAt}\n"

    private fun File.readTextOrNull(): String? = try { readText() } catch (_: Exception) { null }

    /**
     * Userscripts commonly depend on @require libraries. WebView cannot execute
     * Tampermonkey's @require directive by itself, so resolve the dependency at
     * import time and prepend it in declaration order. Failed optional requires
     * are left out rather than making the whole script unusable.
     */
    private fun inlineRequires(source: String): String {
        val urls = Regex("@require\\s+(.+)").findAll(source)
            .map { it.groupValues[1].trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
        if (urls.isEmpty()) return source
        val requires = buildString {
            urls.forEach { u ->
                try {
                    val c = (URL(u).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 15000
                        setRequestProperty("User-Agent", "NebulaBrowser/1.0")
                    }
                    if (c.responseCode in 200..299) {
                        val dep = c.inputStream.bufferedReader().use { it.readText() }
                        if (dep.isNotBlank()) {
                            append("\n/* @require ").append(u).append(" */\n")
                            append(dep).append("\n")
                        }
                    }
                    c.disconnect()
                } catch (_: Exception) {
                    // Keep importing the main userscript.
                }
            }
        }
        return if (requires.isBlank()) source else requires + "\n" + source
    }

    private data class Meta(
        val name: String = "",
        val matches: List<String> = emptyList(),
        val excludes: List<String> = emptyList(),
        val runAt: String = "document-start"
    )

    private fun parse(source: String): Meta {
        val start = source.indexOf("==UserScript==")
        val end = source.indexOf("==/UserScript==")
        if (start < 0 || end <= start) return Meta()
        val block = source.substring(start, end)
        val name = Regex("@name\\s+(.+)").find(block)?.groupValues?.get(1)?.trim().orEmpty()
        val matches = Regex("@match\\s+(.+)").findAll(block).map { it.groupValues[1].trim() }.toList()
        val include = Regex("@include\\s+(.+)").findAll(block).map { it.groupValues[1].trim() }.toList()
        val exclude = Regex("@exclude\\s+(.+)").findAll(block).map { it.groupValues[1].trim() }.toList()
        val runAt = Regex("@run-at\\s+(.+)").find(block)?.groupValues?.get(1)?.trim() ?: "document-start"
        return Meta(name, (matches + include).distinct(), exclude.distinct(), runAt)
    }

    companion object {
        fun matchesAny(patterns: List<String>, url: String): Boolean {
            if (patterns.isEmpty()) return true
            return patterns.any { patternMatches(it, url) }
        }

        private fun patternMatches(pattern: String, url: String): Boolean {
            if (pattern == "<all_urls>" || pattern == "*" || pattern == "*://*/*") return true
            return try {
                val regex = globToRegex(pattern)
                Regex(regex).matches(url)
            } catch (_: Exception) {
                false
            }
        }

        /** 将简单的 @match 通配符规则转换为正则表达式 */
        private fun globToRegex(pattern: String): String {
            val p = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
            return "^$p$"
        }
    }
}