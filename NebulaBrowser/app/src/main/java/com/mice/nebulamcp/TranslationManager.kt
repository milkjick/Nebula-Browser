package com.mice.nebulamcp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Robust multi-engine page translation backend.
 *
 * Design goals:
 *  - never let one failed public endpoint abort the whole page
 *  - translate independent paragraphs concurrently
 *  - cache successful translations in-memory
 *  - keep code/pre/svg/navigation controls out of the page collector (handled by JS)
 *  - use Tencent Transmart as an additional engine, following the same multi-engine
 *    strategy used by current userscript translators.
 */
class TranslationManager {
    enum class Provider { AUTO, GOOGLE, BING, TENCENT }

    data class Segment(val id: Int, val text: String)
    data class TranslationResult(val sourceLanguage: String?, val translations: List<String>)

    private val cache = ConcurrentHashMap<String, String>()
    @Volatile private var cachedIG: String? = null
    @Volatile private var cachedIID: String? = null
    @Volatile private var bingTokenFetchedAt: Long = 0L

    fun translateBatch(
        segments: List<Segment>,
        target: String = "zh-CN",
        provider: Provider = Provider.AUTO
    ): TranslationResult? {
        if (segments.isEmpty()) return null

        val result = arrayOfNulls<String>(segments.size)
        val pending = ArrayList<Pair<Int, Segment>>()
        segments.forEachIndexed { index, segment ->
            val key = cacheKey(segment.text, target)
            val cached = cache[key]
            if (!cached.isNullOrBlank()) result[index] = cached
            else pending += index to segment
        }

        if (pending.isNotEmpty()) {
            val executor = Executors.newFixedThreadPool(minOf(8, pending.size))
            try {
                val futures = pending.map { (index, segment) ->
                    index to executor.submit(Callable { translateOneWithFallback(segment.text, target, provider) })
                }
                futures.forEach { (index, future) ->
                    val translated = runCatching { future.get() }.getOrNull()?.first
                    if (!translated.isNullOrBlank()) {
                        result[index] = translated
                        cache[cacheKey(segments[index].text, target)] = translated
                    }
                }
            } finally {
                executor.shutdownNow()
            }
        }

        val out = result.map { it.orEmpty() }
        if (out.none { it.isNotBlank() }) return null
        return TranslationResult(null, out)
    }

    private fun cacheKey(text: String, target: String): String =
        target + "\u0000" + text.trim()

    private fun translateOneWithFallback(
        text: String,
        target: String,
        preferred: Provider
    ): Pair<String, String?>? {
        val providers = when (preferred) {
            Provider.GOOGLE -> listOf(Provider.GOOGLE, Provider.TENCENT, Provider.BING)
            Provider.BING -> listOf(Provider.BING, Provider.TENCENT, Provider.GOOGLE)
            Provider.TENCENT -> listOf(Provider.TENCENT, Provider.BING, Provider.GOOGLE)
            Provider.AUTO -> listOf(Provider.TENCENT, Provider.BING, Provider.GOOGLE)
        }
        for (p in providers) {
            val result = runCatching {
                when (p) {
                    Provider.GOOGLE -> translateOneGoogle(text, target)
                    Provider.BING -> translateOneBing(text, target)
                    Provider.TENCENT -> translateOneTencent(text, target)
                    Provider.AUTO -> null
                }
            }.getOrNull()
            if (!result?.first.isNullOrBlank() && !result!!.first.equals(text.trim(), true)) return result
        }
        return translateOneMyMemory(text, target)
    }

    private fun open(url: String, method: String = "GET", contentType: String? = null): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 9000
            instanceFollowRedirects = true
            useCaches = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/128 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
        }
    }

    private fun translateOneGoogle(text: String, target: String): Pair<String, String?>? {
        val q = text.trim()
        if (q.isBlank()) return null
        val encodedTarget = URLEncoder.encode(target, "UTF-8")
        val encodedText = URLEncoder.encode(q, "UTF-8")
        val endpoints = listOf(
            "https://translate.googleapis.com/translate_a/single",
            "https://translate.google.com/translate_a/single"
        )
        for (endpoint in endpoints) {
            val conn = runCatching {
                open(endpoint + "?client=gtx&sl=auto&tl=" + encodedTarget + "&dt=t&dt=rm&q=" + encodedText)
            }.getOrNull() ?: continue
            try {
                if (conn.responseCode !in 200..299) continue
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val root = JSONArray(body)
                val rows = root.optJSONArray(0) ?: continue
                val translated = buildString {
                    for (i in 0 until rows.length()) {
                        val row = rows.optJSONArray(i) ?: continue
                        val raw = row.opt(0)
                        if (raw == null || raw === JSONObject.NULL) continue
                        val part = raw.toString()
                        if (part.isNotBlank() && !part.equals("null", true)) append(part)
                    }
                }.trim()
                if (translated.isNotBlank()) return translated to root.optString(2).takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                // try next endpoint/provider
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /** Tencent Transmart public web endpoint. It supports a batch text_list. */
    private fun translateOneTencent(text: String, target: String): Pair<String, String?>? {
        val q = text.trim()
        if (q.isBlank()) return null
        val clientKey = "browser-chrome-128-Android-${System.currentTimeMillis()}-${kotlin.random.Random.nextLong()}"
        val body = JSONObject().apply {
            put("header", JSONObject().apply {
                put("fn", "auto_translation")
                put("session", "")
                put("client_key", clientKey)
                put("user", "")
            })
            put("type", "plain")
            put("model_category", "normal")
            put("text_domain", "general")
            put("source", JSONObject().apply {
                put("lang", "auto")
                put("text_list", JSONArray().put(q))
            })
            put("target", JSONObject().put("lang", if (target.startsWith("zh-TW")) "zh-TW" else "zh"))
        }.toString()
        val conn = runCatching { open("https://transmart.qq.com/api/imt", "POST", "application/json") }.getOrNull() ?: return null
        return try {
            conn.setRequestProperty("Origin", "https://transmart.qq.com")
            conn.setRequestProperty("Referer", "https://transmart.qq.com/")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            val json = JSONObject(conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
            val arr = json.optJSONArray("auto_translation") ?: return null
            val translated = arr.optString(0).trim()
            translated.takeIf { it.isNotBlank() }?.let { it to null }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    @Synchronized
    private fun ensureBingTokens(): Pair<String, String>? {
        val now = System.currentTimeMillis()
        if (cachedIG != null && cachedIID != null && now - bingTokenFetchedAt < 8 * 60 * 1000L) {
            return cachedIG!! to cachedIID!!
        }
        return try {
            val conn = open("https://cn.bing.com/translator")
            if (conn.responseCode !in 200..299) return null
            val html = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val ig = listOf(
                Regex("\\\"IG\\\"\\s*:\\s*\\\"([0-9A-Za-z]+)\\\""),
                Regex("IG:\\s*\\\"([0-9A-Za-z]+)\\\"")
            ).firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
            val iid = listOf(
                Regex("data-iid=[\\\"']([^\\\"']+)[\\\"']"),
                Regex("IID\\s*[:=]\\s*[\\\"']([^\\\"']+)[\\\"']")
            ).firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
            if (ig != null && iid != null) {
                cachedIG = ig
                cachedIID = iid
                bingTokenFetchedAt = now
                ig to iid
            } else null
        } catch (_: Exception) { null }
    }

    private fun bingLangCode(target: String): String = when {
        target.startsWith("zh-TW") || target.startsWith("zh-Hant") -> "zh-Hant"
        target.startsWith("zh") -> "zh-Hans"
        else -> target
    }

    private fun translateOneBing(text: String, target: String): Pair<String, String?>? {
        val q = text.trim()
        if (q.isBlank()) return null
        val tokens = ensureBingTokens() ?: return null
        return try {
            val (ig, iid) = tokens
            val url = "https://cn.bing.com/ttranslatev3?isVertical=1&IG=" +
                URLEncoder.encode(ig, "UTF-8") + "&IID=" + URLEncoder.encode(iid, "UTF-8")
            val body = "fromLang=auto-detect&to=" + bingLangCode(target) + "&text=" + URLEncoder.encode(q, "UTF-8")
            val conn = open(url, "POST", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                cachedIG = null; cachedIID = null; conn.disconnect(); return null
            }
            val response = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            conn.disconnect()
            val arr = JSONArray(response)
            val first = arr.optJSONObject(0) ?: return null
            val translations = first.optJSONArray("translations") ?: return null
            val firstTranslation = translations.optJSONObject(0)
            val rawTranslated = firstTranslation?.opt("text")
            val translated = if (rawTranslated == null || rawTranslated === JSONObject.NULL) "" else rawTranslated.toString().trim()
            val rawDetected = first.optJSONObject("detectedLanguage")?.opt("language")
            val detected = if (rawDetected == null || rawDetected === JSONObject.NULL) null else rawDetected.toString().trim()
            translated.takeIf { it.isNotBlank() }?.let { it to detected }
        } catch (_: Exception) { null }
    }

    private fun translateOneMyMemory(text: String, target: String): Pair<String, String?>? {
        val q = text.trim()
        if (q.isBlank() || q.length > 500) return null
        val lang = if (target.startsWith("zh-TW") || target.startsWith("zh-Hant")) "zh-TW" else "zh-CN"
        val url = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(q, "UTF-8") +
            "&langpair=autodetect%7C" + URLEncoder.encode(lang, "UTF-8")
        val conn = runCatching { open(url) }.getOrNull() ?: return null
        return try {
            if (conn.responseCode !in 200..299) return null
            val obj = JSONObject(conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
            val translated = obj.optJSONObject("responseData")?.optString("translatedText").orEmpty().trim()
            if (obj.optInt("responseStatus", 200) !in 200..299 || translated.isBlank() || translated.equals(q, true)) null
            else translated to null
        } catch (_: Exception) { null }
        finally { conn.disconnect() }
    }
}
