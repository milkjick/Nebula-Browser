package com.mice.nebulamcp

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/**
 * Remote MCP gateway with protocol compatibility and lightweight local discovery.
 * It supports current Streamable HTTP headers plus older JSON-RPC MCP servers.
 */
class RemoteMcpManager(private val settings: SettingsStore) {
    data class RemoteServer(val name: String, val url: String, val token: String, val discovered: Boolean = false)
    data class ProbeResult(val server: RemoteServer, val ok: Boolean, val toolCount: Int, val error: String = "")

    @Volatile private var discoveredCache: List<RemoteServer> = emptyList()
    @Volatile private var lastDiscoveryAt = 0L

    fun list(includeDiscovered: Boolean = true): List<RemoteServer> {
        val configured = configuredList()
        if (!includeDiscovered) return configured
        val names = configured.map { it.url }.toHashSet()
        return configured + discoveredCache.filter { it.url !in names }
    }

    fun configuredList(): List<RemoteServer> {
        val arr = JSONArray(settings.remoteServersJson)
        val out = mutableListOf<RemoteServer>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(RemoteServer(o.getString("name"), normalizeUrl(o.getString("url")), o.optString("token", ""), false))
        }
        return out
    }

    fun add(name: String, url: String, token: String) {
        val servers = configuredList().filterNot { it.name == name || it.url == normalizeUrl(url) }.toMutableList()
        servers.add(RemoteServer(name.trim(), normalizeUrl(url), token.trim(), false))
        persist(servers)
    }

    fun remove(name: String) = persist(configuredList().filterNot { it.name == name })

    private fun persist(servers: List<RemoteServer>) {
        val arr = JSONArray()
        servers.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url).put("token", it.token)) }
        settings.remoteServersJson = arr.toString()
    }

    /** Aggregate configured + auto-discovered remote tools. Errors are retained for diagnostics. */
    fun aggregateRemoteTools(): JSONArray {
        if (settings.mcpAutoDiscoveryEnabled) discoverLocalMcp(false)
        val out = JSONArray()
        for (server in list()) {
            try {
                val resp = rpcCall(server, "tools/list", JSONObject())
                val tools = resp.optJSONObject("result")?.optJSONArray("tools") ?: continue
                for (i in 0 until tools.length()) {
                    val t = tools.getJSONObject(i)
                    val rawName = t.optString("name")
                    if (rawName.isBlank()) continue
                    val renamed = JSONObject(t.toString())
                    renamed.put("name", "remote.${safeName(server.name)}.$rawName")
                    renamed.put("description", "[${server.name}] ${t.optString("description", "")}".trim())
                    out.put(renamed)
                }
            } catch (_: Exception) { }
        }
        return out
    }

    fun forwardCall(fullName: String, args: JSONObject): JSONObject {
        val rest = fullName.removePrefix("remote.")
        val dot = rest.indexOf('.')
        if (dot <= 0) return errorResult("malformed remote tool name: $fullName")
        val serverName = rest.substring(0, dot)
        val toolName = rest.substring(dot + 1)
        val server = list().find { safeName(it.name) == serverName }
            ?: return errorResult("unknown remote server: $serverName")
        return try {
            val params = JSONObject().put("name", toolName).put("arguments", args)
            val resp = rpcCall(server, "tools/call", params)
            resp.optJSONObject("result") ?: errorResult("remote server returned no result")
        } catch (e: Exception) {
            errorResult("remote call failed: ${e.message}")
        }
    }

    /** Test all configured/discovered servers and return tool counts + actionable errors. */
    fun diagnostics(): JSONArray {
        if (settings.mcpAutoDiscoveryEnabled) discoverLocalMcp(true)
        val out = JSONArray()
        for (server in list()) {
            try {
                val resp = rpcCall(server, "tools/list", JSONObject())
                val tools = resp.optJSONObject("result")?.optJSONArray("tools")
                out.put(JSONObject().put("name", server.name).put("url", server.url).put("ok", tools != null).put("toolCount", tools?.length() ?: 0).put("discovered", server.discovered))
            } catch (e: Exception) {
                out.put(JSONObject().put("name", server.name).put("url", server.url).put("ok", false).put("toolCount", 0).put("discovered", server.discovered).put("error", e.message ?: e.toString()))
            }
        }
        return out
    }

    /**
     * Discovers common MCP endpoints on the same Android device. It deliberately
     * avoids the app's own 8788 endpoint and does not scan the LAN automatically.
     */
    fun discoverLocalMcp(force: Boolean = false): List<RemoteServer> {
        val now = System.currentTimeMillis()
        if (!force && now - lastDiscoveryAt < 30_000L) return discoveredCache
        lastDiscoveryAt = now
        val ports = listOf(3000, 3001, 5000, 8000, 8080, 8787, 9000, 9001, 9090, 11434)
        val found = mutableListOf<RemoteServer>()
        for (port in ports) {
            if (port == NebulaApp.MCP_PORT) continue
            // Fast TCP gate prevents a full HTTP timeout on every closed port.
            try {
                Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 150) }
            } catch (_: Exception) { continue }
            val url = "http://127.0.0.1:$port/mcp"
            try {
                val server = RemoteServer("local-$port", url, "", true)
                val resp = rpcCall(server, "tools/list", JSONObject())
                val tools = resp.optJSONObject("result")?.optJSONArray("tools")
                if (tools != null) found.add(server)
            } catch (_: Exception) { }
        }
        discoveredCache = found
        return found
    }

    private fun rpcCall(server: RemoteServer, method: String, params: JSONObject): JSONObject {
        var last: Exception? = null
        // Current Streamable HTTP first, then older revisions for compatibility.
        for (version in listOf("2026-07-28", "2025-11-25", "2025-06-18", "2024-11-05")) {
            try {
                return request(server, method, params, version, null).json
            } catch (e: Exception) {
                last = e
            }
        }
        // Some 2025 servers require an initialize handshake and session id.
        try {
            val init = initialize(server, "2025-11-25")
            val session = init.sessionId
            notifyInitialized(server, "2025-11-25", session)
            return request(server, method, params, "2025-11-25", session).json
        } catch (e: Exception) {
            last = e
        }
        throw last ?: IllegalStateException("MCP request failed")
    }

    private data class HttpResult(val json: JSONObject, val sessionId: String?)

    private fun initialize(server: RemoteServer, version: String): HttpResult {
        val meta = JSONObject()
            .put("io.modelcontextprotocol/protocolVersion", version)
            .put("io.modelcontextprotocol/clientInfo", JSONObject().put("name", "NebulaBrowser").put("version", "0.2.0"))
            .put("io.modelcontextprotocol/clientCapabilities", JSONObject())
        val params = JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "NebulaBrowser").put("version", "0.2.0"))
            .put("_meta", meta)
        return request(server, "initialize", params, version, null)
    }

    private fun request(server: RemoteServer, method: String, rawParams: JSONObject, version: String, sessionId: String?): HttpResult {
        val meta = JSONObject()
            .put("io.modelcontextprotocol/protocolVersion", version)
            .put("io.modelcontextprotocol/clientInfo", JSONObject().put("name", "NebulaBrowser").put("version", "0.2.0"))
            .put("io.modelcontextprotocol/clientCapabilities", JSONObject())
        val params = JSONObject(rawParams.toString()).put("_meta", meta)
        val body = JSONObject().put("jsonrpc", "2.0").put("id", idGen.incrementAndGet()).put("method", method).put("params", params)
        val conn = (URL(server.url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 2500
        conn.readTimeout = 10000
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("MCP-Protocol-Version", version)
        conn.setRequestProperty("Mcp-Method", method)
        if (method == "tools/call") conn.setRequestProperty("Mcp-Name", rawParams.optString("name"))
        if (!sessionId.isNullOrBlank()) conn.setRequestProperty("Mcp-Session-Id", sessionId)
        if (server.token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${server.token}")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val input = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = input?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(300)}")
        val parsed = parseJsonOrSse(text)
        val err = parsed.optJSONObject("error")
        if (err != null) throw IllegalStateException("MCP ${err.optInt("code")}: ${err.optString("message")}")
        return HttpResult(parsed, conn.getHeaderField("Mcp-Session-Id"))
    }

    private fun notifyInitialized(server: RemoteServer, version: String, sessionId: String?) {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/initialized")
            .put("params", JSONObject())
        val conn = (URL(server.url).openConnection() as HttpURLConnection)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 2500
        conn.readTimeout = 5000
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("MCP-Protocol-Version", version)
        conn.setRequestProperty("Mcp-Method", "notifications/initialized")
        if (!sessionId.isNullOrBlank()) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId)
        }
        if (server.token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${server.token}")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode !in 200..299 && conn.responseCode != 202) {
            throw IllegalStateException("initialize notification failed: HTTP ${conn.responseCode}")
        }
        conn.disconnect()
    }

    private fun parseJsonOrSse(text: String): JSONObject {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) return JSONObject(trimmed)
        val dataLines = trimmed.lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.startsWith("{") }
            .toList()
        if (dataLines.isNotEmpty()) return JSONObject(dataLines.last())
        throw IllegalStateException("MCP returned non-JSON/non-SSE response")
    }

    private fun normalizeUrl(url: String): String = url.trim().removeSuffix("/")
    private fun safeName(name: String): String = name.trim().replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "server" }

    private fun errorResult(msg: String): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", msg)))
        .put("isError", true)

    companion object { private val idGen = AtomicLong(1) }
}
