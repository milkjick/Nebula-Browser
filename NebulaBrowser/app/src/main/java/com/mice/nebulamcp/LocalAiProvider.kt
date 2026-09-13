package com.mice.nebulamcp

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** OpenAI-compatible local model adapter. */
class LocalAiProvider(private val settings: SettingsStore) {
    fun ask(prompt: String): Result<String> = runCatching { chat(prompt) }

    fun askVision(prompt: String, bitmap: Bitmap): Result<String> = runCatching {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 72, out)
        val image = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", image)))
        val message = JSONObject().put("role", "user").put("content", content)
        request(JSONObject().put("model", settings.localAiModel).put("temperature", 0.2).put("messages", JSONArray().put(message)))
    }

    private fun chat(prompt: String): String {
        val message = JSONObject().put("role", "user").put("content", prompt)
        return request(JSONObject().put("model", settings.localAiModel).put("temperature", 0.2).put("messages", JSONArray().put(message)))
    }

    private fun request(body: JSONObject): String {
        val c = URL(settings.localAiBaseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15_000
        c.readTimeout = 120_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (settings.localAiApiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${settings.localAiApiKey}")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) error("Local AI HTTP $code: $text")
        return JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content") ?: text
    }
}
