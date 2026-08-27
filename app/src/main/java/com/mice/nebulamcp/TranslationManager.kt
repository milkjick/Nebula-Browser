package com.mice.nebulamcp

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Lightweight webpage translation helper.
 * Supports Google Translate's public web endpoint and Bing Translator's
 * unofficial web endpoint (no API key for either) — Bing is offered as an
 * alternative because Google Translate is frequently unreachable from
 * mainland China, which is exactly the situation this was added for.
 */
class TranslationManager {
    enum class Provider { GOOGLE, BING }

    data class Segment(val id: Int, val text: String)
    data class TranslationResult(val sourceLanguage: String?, val translations: List<String>)

    @Volatile private var cachedIG: String? = null
    @Volatile private var cachedIID: String? = null
    @Volatile private var bingTokenFetchedAt: Long = 0

    fun translateBatch(segments: List<Segment>, target: String = "zh-CN", provider: Provider = Provider.GOOGLE): TranslationResult? {
        if (segments.isEmpty()) return null

        // Translating each DOM segment independently is more reliable than concatenating
        // many nodes with separators: both providers may split/merge sentences arbitrarily.
        val executor = Executors.newFixedThreadPool(minOf(6, segments.size))
        return try {
            val futures = segments.map { segment ->
                executor.submit(Callable {
                    when (provider) {
                        Provider.GOOGLE -> translateOneGoogle(segment.text, target)
                        Provider.BING -> translateOneBing(segment.text, target)
                    }
                })
            }
            val out = ArrayList<String>()
            var detected: String? = null
            futures.forEach { future ->
                val result = future.get()
                out += result?.first.orEmpty()
                if (detected == null) detected = result?.second
            }
            if (out.none { it.isNotBlank() }) null
            else TranslationResult(detected, out)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun translateOneGoogle(text: String, target: String): Pair<String, String?>? {
        val q = text.trim()
        if (q.isBlank()) return null
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" +
            URLEncoder.encode(target, "UTF-8") + "&dt=t&dt=rm&q=" +
            URLEncoder.encode(q, "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 7000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONArray(body)
            val rows = root.optJSONArray(0) ?: return null
            val translated = buildString {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONArray(i) ?: continue
                    val part = row.optString(0)
                    if (part.isNotBlank()) append(part)
                }
            }.trim()
            translated.takeIf { it.isNotBlank() }?.let { it to root.optString(2).takeIf(String::isNotBlank) }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Fetches (and caches for 10 min) the IG/IID session tokens Bing's translator page embeds. */
    private fun ensureBingTokens(): Pair<String, String>? {
        val fresh = cachedIG != null && cachedIID != null && System.currentTimeMillis() - bingTokenFetchedAt < 10 * 60 * 1000
        if (fresh) return cachedIG!! to cachedIID!!
        return try {
            val conn = (URL("https://cn.bing.com/translator").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            }
            if (conn.responseCode !in 200..299) return null
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            val ig = Regex("IG:\"([0-9A-Za-z]+)\"").find(html)?.groupValues?.get(1)
            val iid = Regex("data-iid=\"([a-zA-Z0-9.]+)\"").find(html)?.groupValues?.get(1)
            if (ig != null && iid != null) {
                cachedIG = ig; cachedIID = iid; bingTokenFetchedAt = System.currentTimeMillis()
                ig to iid
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun bingLangCode(target: String): String = when {
        target.startsWith("zh-TW") || target.startsWith("zh-Hant") -> "zh-Hant"
        target.startsWith("zh") -> "zh-Hans"
        else -> target
    }

    private fun translateOneBing(text: String, target: String): Pair<String, String?>? {
        val q = text.trim()
        if (q.isBlank()) return null
        val (ig, iid) = ensureBingTokens() ?: return null
        return try {
            val url = "https://cn.bing.com/ttranslatev3?isVertical=1&IG=$ig&IID=$iid"
            val body = "fromLang=auto-detect&to=" + bingLangCode(target) + "&text=" + URLEncoder.encode(q, "UTF-8")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) {
                // Token likely expired — drop cache so the next call re-fetches.
                cachedIG = null; cachedIID = null
                return null
            }
            val respText = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(respText)
            val first = arr.optJSONObject(0) ?: return null
            val translations = first.optJSONArray("translations") ?: return null
            val t0 = translations.optJSONObject(0) ?: return null
            val translated = t0.optString("text")
            val detected = first.optJSONObject("detectedLanguage")?.optString("language")
            translated.takeIf { it.isNotBlank() }?.let { it to detected }
        } catch (_: Exception) {
            null
        }
    }
}
