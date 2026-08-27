package com.mice.nebulamcp

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Local MCP gateway, mirrors the existing DeepSeek-gateway pattern:
 *   http://127.0.0.1:8788/mcp   (stable, always on when service is enabled)
 *   http://<lan-ip>:8788/mcp    (only served when "局域网访问" is on)
 *
 * Speaks a minimal JSON-RPC 2.0 subset over POST:
 *   initialize, tools/list, tools/call
 *
 * tools/list merges local tools (ToolRegistry) with any tools advertised by
 * configured remote MCP servers (RemoteMcpManager), namespaced as
 * "remote.<server>.<tool>". tools/call routes to whichever side owns the name.
 */
class McpServer(
    private val app: Context,
    port: Int,
    private val bindLan: Boolean,
    private val toolRegistry: ToolRegistry,
    private val remoteMcpManager: RemoteMcpManager,
    private val settings: SettingsStore
) : NanoHTTPD(if (bindLan) null else "127.0.0.1", port) {

    val callCount = AtomicLong(0)
    val startedAt = System.currentTimeMillis()

    override fun serve(session: IHTTPSession): Response {
        // CORS / preflight support so a page-injected script (fetch) can also reach this.
        if (session.method == Method.OPTIONS) {
            return corsify(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
        }

        if (session.uri != "/mcp") {
            if (session.uri == "/status" && session.method == Method.GET) {
                return corsify(jsonResponse(statusJson()))
            }
            return corsify(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found"))
        }

        if (!isAuthorized(session)) {
            return corsify(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "unauthorized"))
        }

        if (session.method != Method.POST) {
            return corsify(newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "use POST"))
        }

        callCount.incrementAndGet()

        return try {
            val body = readBody(session)
            val req = JSONObject(body)
            val id = req.opt("id")
            val method = req.optString("method").ifBlank { session.headers["mcp-method"] ?: "" }
            val params = req.optJSONObject("params") ?: JSONObject()

            if (method == "notifications/initialized") {
                return corsify(newFixedLengthResponse(Response.Status.OK, "text/plain", ""))
            }
            val result: JSONObject = when (method) {
                "initialize" -> initializeResult(params, session)
                "ping" -> JSONObject()
                "tools/list" -> toolsListResult()
                "tools/call" -> toolsCallResult(params)
                else -> return corsify(jsonResponse(rpcError(id, -32601, "method not found: $method")))
            }
            corsify(jsonResponse(rpcSuccess(id, result)))
        } catch (e: Exception) {
            corsify(jsonResponse(rpcError(null, -32603, e.message ?: "internal error")))
        }
    }

    /**
     * The injected page script (dspp_mainworld.js) talks to this server as a
     * same-device XHR and never sends a token — it can't, it's not a
     * "connect to my MCP server" client, it *is* this app. So loopback
     * callers (Host: 127.0.0.1 / localhost) are always trusted; the token
     * only gates access once LAN binding exposes the port to other devices.
     */
    private fun isAuthorized(session: IHTTPSession): Boolean {
        val host = session.headers["host"] ?: ""
        val isLoopback = host.startsWith("127.0.0.1") || host.startsWith("localhost")
        if (isLoopback) return true

        val header = session.headers["authorization"]
        val queryToken = session.parms["token"]
        val expected = settings.token
        val bearer = header?.removePrefix("Bearer ")?.trim()
        return bearer == expected || queryToken == expected
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    private fun initializeResult(params: JSONObject, session: IHTTPSession): JSONObject {
        val requested = params.optString("protocolVersion")
            .ifBlank { params.optJSONObject("_meta")?.optString("io.modelcontextprotocol/protocolVersion") }
        val version = when (requested) {
            "2026-07-28", "2025-11-25", "2025-06-18", "2024-11-05" -> requested
            else -> "2024-11-05"
        }
        return JSONObject()
            .put("protocolVersion", version)
            .put("serverInfo", JSONObject().put("name", "nebula-mcp").put("version", "0.2.0"))
            .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", true)))
            .put("instructions", "Nebula Browser MCP gateway. Local browser/files/network tools plus configured remote MCP tools and GitHub tools.")
    }

    private fun toolsListResult(): JSONObject {
        val local = toolRegistry.listToolDescriptors()
        val remote = remoteMcpManager.aggregateRemoteTools()
        val merged = JSONArray()
        for (i in 0 until local.length()) merged.put(local.get(i))
        for (i in 0 until remote.length()) merged.put(remote.get(i))
        return JSONObject().put("tools", merged)
    }

    private fun toolsCallResult(params: JSONObject): JSONObject {
        val name = params.getString("name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        return if (name.startsWith("remote.")) {
            remoteMcpManager.forwardCall(name, args)
        } else {
            toolRegistry.call(name, args)
        }
    }

    private fun rpcSuccess(id: Any?, result: JSONObject): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("result", result)

    private fun rpcError(id: Any?, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    private fun statusJson(): JSONObject = JSONObject()
        .put("uptime_ms", System.currentTimeMillis() - startedAt)
        .put("calls", callCount.get())
        .put("lan_access", bindLan)
        .put("local_tools", toolRegistry.listToolDescriptors().length())
        .put("remote_servers", remoteMcpManager.list().size)

    private fun jsonResponse(obj: JSONObject): Response {
        val response = newFixedLengthResponse(Response.Status.OK, "application/json", obj.toString())
        response.addHeader("MCP-Protocol-Version", "2026-07-28")
        return response
    }

    private fun corsify(resp: Response): Response {
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, MCP-Protocol-Version, Mcp-Method, Mcp-Name, Mcp-Session-Id")
        resp.addHeader("Access-Control-Expose-Headers", "MCP-Protocol-Version, Mcp-Session-Id")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        return resp
    }
}
