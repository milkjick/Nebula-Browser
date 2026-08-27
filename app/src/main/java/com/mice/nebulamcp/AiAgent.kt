package com.mice.nebulamcp

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Nebula AI Agent core. It deliberately uses only platform HTTP/JSON APIs so
 * the project does not acquire another Gradle dependency.
 */
class AiAgent(private val context: Context, private val settings: SettingsStore, private val tools: ToolRegistry, private val webAi: WebAiProvider? = null) {
    data class Action(val action: String, val selector: String = "", val text: String = "", val url: String = "", val direction: String = "down", val amount: Int = 700, val name: String = "", val arguments: JSONObject = JSONObject(), val confirmation: Boolean = false)
    data class Plan(val message: String, val actions: List<Action>)

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun buildContext(done: (String) -> Unit) {
        // IMPORTANT: browser_ai_context ultimately calls WebView.evaluateJavascript().
        // Never call it from the Android UI thread because ToolRegistry waits for the
        // UI callback. Doing so would deadlock the main Looper and make the Agent look
        // like it is not responding.
        executor.execute {
            try {
                val result = tools.call("browser_ai_context", JSONObject())
                val text = extractText(result)
                if (result.optBoolean("isError", false)) {
                    main.post { done(JSONObject().put("error", text).toString()) }
                } else {
                    main.post { done(text) }
                }
            } catch (e: Exception) {
                main.post { done("{\"error\":${JSONObject.quote(e.message ?: e.toString())}}") }
            }
        }
    }

    fun plan(userRequest: String, contextJson: String, done: (Result<Plan>) -> Unit) {
        executor.execute {
            try {
                if (settings.aiProviderMode == "web") {
                    val prompt = buildWebAgentPrompt(userRequest, contextJson)
                    webAi?.ask(prompt) { result ->
                        result.onSuccess { text -> parsePlanAndFinish(text, done) }
                            .onFailure { finish(done, Result.failure(it)) }
                    } ?: finish(done, Result.failure(IllegalStateException("Web AI Provider 未初始化")))
                    return@execute
                }
                val key = settings.aiApiKey.trim()
                if (key.isBlank()) throw IllegalStateException("当前为 API 模式，请在“AI 设置”中配置 API Key")
                val system = """

You are Nebula Browser AI Agent. Return JSON only, no markdown.
You can understand the supplied browser context and create a safe multi-step plan.
Allowed browser actions: navigate, new_tab, back, click, input, scroll, search.
Allowed MCP action: mcp_call.
The supplied BROWSER CONTEXT includes an "mcpTools" array (name + description) — only use tool names that appear there for mcp_call. It may also include a "memory" field with facts/notes you saved in earlier sessions; use agent_remember/agent_recall/agent_add_note (via mcp_call) to save anything worth remembering for next time (user preferences, task outcomes, ongoing progress).
Prefer semantic DOM targets over brittle CSS. For click/input use selector produced by browser_ai_context; do not invent selectors.
For risky actions (payments, destructive changes, account deletion, sending messages) create the plan but mark action confirmation=true. Never invent a selector; use an ai-* id from the context when possible.
JSON schema: {\"message\":string,\"actions\":[{\"action\":string,\"selector\":string,\"text\":string,\"url\":string,\"direction\":\"up|down\",\"amount\":number,\"name\":string,\"arguments\":object,\"confirmation\":boolean}]}
""".trimIndent()
                val user = "USER REQUEST:\n$userRequest\n\nBROWSER CONTEXT:\n$contextJson"
                val response = requestChat(key, settings.aiModel, system, user)
                parsePlanAndFinish(response.optString("content", response.toString()).trim(), done)
            } catch (e: Exception) { finish(done, Result.failure(e)) }
        }
    }

    private fun buildWebAgentPrompt(userRequest: String, contextJson: String): String = """
You are Nebula Browser AI Agent. Return JSON only, no markdown.
Create a safe multi-step browser/MCP plan from the supplied request and context.
Allowed actions: navigate, new_tab, back, click, input, scroll, search, mcp_call.
The supplied BROWSER CONTEXT includes an "mcpTools" array (name + description) — only use tool names that appear there for mcp_call, and a "memory" field with facts saved from earlier sessions (use agent_remember/agent_recall/agent_add_note via mcp_call to save anything worth remembering).
For click/input use the ai-* semantic target from browser_ai_context when available.
For risky actions create the plan but require user confirmation. Never invent a selector; use an ai-* id from the context when possible.
Schema: {"message":string,"actions":[{"action":string,"selector":string,"text":string,"url":string,"direction":"up|down","amount":number,"name":string,"arguments":object,"confirmation":boolean}]}
USER REQUEST:
$userRequest

BROWSER CONTEXT:
$contextJson
""".trimIndent()

    private fun parsePlanAndFinish(text: String, done: (Result<Plan>) -> Unit) {
        try {
            val cleaned = extractJsonObject(text)
            val obj = JSONObject(cleaned)
            val actions = mutableListOf<Action>()
            val arr = obj.optJSONArray("actions") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                actions += Action(
                    action = a.optString("action"), selector = a.optString("selector"), text = a.optString("text"),
                    url = a.optString("url"), direction = a.optString("direction", "down"), amount = a.optInt("amount", 700),
                    name = a.optString("name"), arguments = a.optJSONObject("arguments") ?: JSONObject(), confirmation = a.optBoolean("confirmation", false)
                )
            }
            finish(done, Result.success(Plan(obj.optString("message", "已生成执行计划"), actions)))
        } catch (e: Exception) { finish(done, Result.failure(IllegalStateException("AI 返回的 Agent JSON 无法解析: ${e.message}"))) }
    }

    fun execute(action: Action): JSONObject = when (action.action) {
        "navigate" -> tools.call("browser_navigate", JSONObject().put("url", action.url))
        "new_tab" -> tools.call("browser_new_tab", JSONObject().put("url", action.url))
        "back" -> tools.call("browser_back", JSONObject())
        "click" -> tools.call("browser_click", JSONObject().put("selector", action.selector))
        "input" -> tools.call("browser_type", JSONObject().put("selector", action.selector).put("text", action.text))
        "scroll" -> tools.call("browser_scroll", JSONObject().put("direction", action.direction).put("amount", action.amount))
        "search" -> tools.call("browser_ai_search", JSONObject().put("query", action.text))
        "mcp_call" -> tools.call("mcp_call", JSONObject().put("name", action.name).put("arguments", action.arguments))
        else -> JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "不支持的 Agent 动作: ${action.action}")))
    }

    /** Lightweight post-action verification used by the Agent Executor. */
    fun verify(action: Action): JSONObject {
        return when (action.action) {
            "click" -> tools.call("browser_eval_js", JSONObject().put("code", "JSON.stringify({url:location.href,active:(document.activeElement&&((document.activeElement.innerText||document.activeElement.value||document.activeElement.getAttribute('aria-label')||'').slice(0,120)))||''})"))
            "input" -> tools.call("browser_eval_js", JSONObject().put("code", "JSON.stringify({active:(document.activeElement&&((document.activeElement.value||document.activeElement.textContent||'').slice(0,${action.text.length.coerceAtMost(120)})))||''})"))
            "scroll" -> tools.call("browser_eval_js", JSONObject().put("code", "JSON.stringify({scrollY:window.scrollY,scrollHeight:document.documentElement.scrollHeight,viewport:innerHeight})"))
            "navigate", "new_tab", "back", "search" -> tools.call("browser_page_info", JSONObject())
            else -> JSONObject().put("ok", true).put("skipped", true)
        }
    }

    /** Extract the first balanced JSON object from model output. Web AI pages often
     * return a short explanation or markdown code fence even when asked for JSON only. */
    private fun extractJsonObject(text: String): String {
        val raw = text.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        if (raw.startsWith("{") && raw.endsWith("}")) return raw
        var start = -1
        var depth = 0
        var quoted = false
        var escaped = false
        for (i in raw.indices) {
            val c = raw[i]
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '\"') quoted = false
                continue
            }
            if (c == '\"') { quoted = true; continue }
            if (c == '{') {
                if (start < 0) start = i
                depth++
            } else if (c == '}' && start >= 0) {
                depth--
                if (depth == 0) return raw.substring(start, i + 1)
            }
        }
        throw IllegalStateException("AI 未返回有效 JSON：${raw.take(300)}")
    }

    fun extractText(result: JSONObject): String = try {
        result.optJSONArray("content")?.optJSONObject(0)?.optString("text") ?: result.toString()
    } catch (_: Exception) { result.toString() }

    private fun finish(cb: (Result<Plan>) -> Unit, r: Result<Plan>) = main.post { cb(r) }

    private fun requestChat(key: String, model: String, system: String, user: String): JSONObject {
        val base = settings.aiBaseUrl.trimEnd('/')
        val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.1)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .toString()
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("AI HTTP $code: $text")
        val root = JSONObject(text)
        return root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("AI 返回格式无效")
    }
}
