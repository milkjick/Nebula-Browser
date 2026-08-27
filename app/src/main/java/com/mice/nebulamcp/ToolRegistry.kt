package com.mice.nebulamcp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

/**
 * Bridge the MCP server uses to reach the live WebView on the UI thread.
 * MainActivity implements this and registers itself in onCreate/onDestroy.
 */
interface WebViewBridge {
    fun navigate(url: String)
    fun currentUrl(): String
    fun evalJs(code: String, onResult: (String) -> Unit)
    fun getHtml(onResult: (String) -> Unit)
    fun aiNewTab(url: String) = navigate(url)
    fun aiBack() { evalJs("history.back();") {} }
    fun aiListTabs(): String = "[]"
    fun aiAllTabsContext(onResult: (String) -> Unit) { onResult(aiListTabs()) }
    fun aiDownload(url: String): String = "unsupported"
}

/**
 * Implements the fixed local tool set advertised over tools/list:
 *   browser_navigate, browser_current_url, browser_eval_js, browser_get_html,
 *   fs_read, fs_write, fs_list,
 *   net_rule_add, net_rule_list, net_rule_remove
 *
 * Network rules are stored here and consulted by MainActivity's
 * WebViewClient#shouldInterceptRequest via NetRuleStore.
 */
class ToolRegistry(private val context: Context) {

    private var bridgeRef: WeakReference<WebViewBridge>? = null
    val netRules = NetRuleStore(context)
    val agentMemory = AgentMemoryStore(context)
    private val githubTools = GithubTools(NebulaApp.instance.settings)
    private val remoteMcp get() = NebulaApp.instance.remoteMcpManager

    fun attachBridge(bridge: WebViewBridge) {
        bridgeRef = WeakReference(bridge)
    }

    fun detachBridge() {
        bridgeRef = null
    }

    private fun workspaceDir(): File {
        val custom = NebulaApp.instance.settings.workspacePath
        val dir = if (custom.isNotBlank()) File(custom)
        else File(context.getExternalFilesDir(null), "Workspace")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** JSON-RPC style tool descriptors returned by tools/list. */
    fun listToolDescriptors(): JSONArray {
        fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()): JSONObject {
            val schema = JSONObject()
                .put("type", "object")
                .put("properties", props)
                .put("required", JSONArray(required))
            return JSONObject().put("name", name).put("description", desc).put("inputSchema", schema)
        }
        val list = JSONArray()
        list.put(tool("browser_navigate", "Navigate the browser WebView to a URL",
            JSONObject().put("url", JSONObject().put("type", "string")), listOf("url")))
        list.put(tool("browser_current_url", "Get the current page URL", JSONObject()))
        list.put(tool("browser_eval_js", "Evaluate JavaScript in the current page and return the result",
            JSONObject().put("code", JSONObject().put("type", "string")), listOf("code")))
        list.put(tool("browser_get_html", "Get the current page's outer HTML", JSONObject()))
        list.put(tool("browser_page_text", "Get readable text from the current page", JSONObject()))
        list.put(tool("browser_page_info", "Get title, URL, language, viewport and element counts", JSONObject()))
        list.put(tool("browser_ai_context", "Build a compact AI context with page text, tabs and semantic DOM targets", JSONObject()))
        list.put(tool("browser_tabs", "List all open browser tabs", JSONObject()))
        list.put(tool("browser_new_tab", "Open a new browser tab", JSONObject().put("url", JSONObject().put("type", "string")), listOf("url")))
        list.put(tool("browser_back", "Go back in the current tab", JSONObject()))
        list.put(tool("browser_click", "Click a DOM element by Nebula AI target id or CSS selector", JSONObject().put("selector", JSONObject().put("type", "string")), listOf("selector")))
        list.put(tool("browser_type", "Type text into a DOM input/textarea/contenteditable element", JSONObject().put("selector", JSONObject().put("type", "string")).put("text", JSONObject().put("type", "string")), listOf("selector", "text")))
        list.put(tool("browser_scroll", "Scroll the current page", JSONObject().put("direction", JSONObject().put("type", "string")).put("amount", JSONObject().put("type", "number"))))
        list.put(tool("browser_ai_search", "Search the web using Nebula's configured search engine", JSONObject().put("query", JSONObject().put("type", "string")), listOf("query")))
        list.put(tool("mcp_list_tools", "List local, GitHub and remote MCP tools available to the Agent", JSONObject()))
        list.put(tool("mcp_call", "Call a local or remote MCP tool by its advertised name", JSONObject().put("name", JSONObject().put("type", "string")).put("arguments", JSONObject().put("type", "object")), listOf("name")))
        list.put(tool("fs_list_dir", "List a directory's contents (files and subdirs, with size and mtime)",
            JSONObject().put("path", JSONObject().put("type", "string")), listOf("path")))
        list.put(tool("fs_read_file", "Read a file's contents; binary files can be returned as base64",
            JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("maxBytes", JSONObject().put("type", "number"))
                .put("binary", JSONObject().put("type", "boolean")),
            listOf("path")))
        list.put(tool("fs_write_file", "Write text or base64 content to a file (relative path = inside workspace, /absolute = real device path)",
            JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("content", JSONObject().put("type", "string"))
                .put("binary", JSONObject().put("type", "boolean")),
            listOf("path", "content")))
        list.put(tool("fs_append_file", "Append content to a file, creating it if needed",
            JSONObject()
                .put("path", JSONObject().put("type", "string"))
                .put("content", JSONObject().put("type", "string")),
            listOf("path", "content")))
        list.put(tool("fs_create_dir", "Create a directory, including parent directories",
            JSONObject().put("path", JSONObject().put("type", "string")), listOf("path")))
        list.put(tool("fs_find_file", "Search a directory tree for filenames matching a glob pattern (e.g. *.apk)",
            JSONObject()
                .put("dir", JSONObject().put("type", "string"))
                .put("pattern", JSONObject().put("type", "string")),
            listOf("pattern")))
        list.put(tool("fs_workspace_info", "Show the current workspace path and its status (exists, writable, free space)", JSONObject()))

        // Agent Memory: lets the AI Agent persist facts/notes across tasks and sessions
        // instead of starting from zero every time (e.g. user preferences, ongoing task state).
        list.put(tool("agent_remember", "Save a fact the Agent should remember across sessions, keyed by name",
            JSONObject()
                .put("key", JSONObject().put("type", "string"))
                .put("value", JSONObject().put("type", "string")),
            listOf("key", "value")))
        list.put(tool("agent_recall", "Recall a previously remembered fact by key",
            JSONObject().put("key", JSONObject().put("type", "string")), listOf("key")))
        list.put(tool("agent_forget", "Delete a previously remembered fact",
            JSONObject().put("key", JSONObject().put("type", "string")), listOf("key")))
        list.put(tool("agent_list_memory", "List all remembered facts and recent notes", JSONObject()))
        list.put(tool("agent_add_note", "Append a freeform note to Agent memory (e.g. a task outcome worth remembering)",
            JSONObject().put("text", JSONObject().put("type", "string")), listOf("text")))

        list.put(tool("browser_download", "Download a direct file URL through the browser's download manager",
            JSONObject().put("url", JSONObject().put("type", "string")), listOf("url")))
        list.put(tool("net_rule_add", "Add a network block/allow rule matched against request host",
            JSONObject()
                .put("pattern", JSONObject().put("type", "string"))
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(listOf("block", "allow")))),
            listOf("pattern", "action")))
        list.put(tool("net_rule_list", "List current network rules", JSONObject()))
        list.put(tool("net_rule_remove", "Remove a network rule by id",
            JSONObject().put("id", JSONObject().put("type", "string")), listOf("id")))
        list.put(tool("mcp_list_remote_servers", "List configured and auto-discovered remote MCP servers with URLs and discovery state", JSONObject()))
        list.put(tool("mcp_discover_local", "Probe common localhost MCP ports on this Android device and discover reachable /mcp endpoints", JSONObject()))
        list.put(tool("mcp_remote_diagnostics", "Test every configured/discovered remote MCP server and report tool counts or protocol/auth/network errors", JSONObject()))
        githubTools.descriptors().forEach { list.put(it) }
        return list
    }

    /** Executes [name] with [args] and returns MCP-style content: {content:[{type:"text",text:...}], isError} */
    fun call(name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "browser_navigate" -> ok(withBridgeSync { it.navigate(args.getString("url")); "navigated" })
                "browser_current_url" -> ok(withBridgeSync { it.currentUrl() })
                "browser_eval_js" -> ok(withBridgeAsync { bridge, latch, out ->
                    bridge.evalJs(args.getString("code")) { result -> out[0] = result; latch.countDown() }
                })
                "browser_get_html" -> ok(withBridgeAsync { bridge, latch, out ->
                    bridge.getHtml { html -> out[0] = html; latch.countDown() }
                })
                "browser_page_text" -> browserEval("(function(){return (document.body&&document.body.innerText||'').slice(0,30000);})()")
                "browser_page_info" -> browserEval("(function(){return JSON.stringify({title:document.title,url:location.href,language:document.documentElement.lang||navigator.language,viewport:{width:innerWidth,height:innerHeight},elements:document.querySelectorAll('*').length,links:document.links.length,inputs:document.querySelectorAll('input,textarea,select').length});})()")
                "browser_ai_context" -> browserAiContext()
                "browser_tabs" -> ok(bridgeRef?.get()?.aiListTabs() ?: "[]")
                "browser_new_tab" -> withBridgeSync { it.aiNewTab(args.getString("url")); "opened" }.let(::ok)
                "browser_back" -> withBridgeSync { it.aiBack(); "back" }.let(::ok)
                "browser_click" -> browserAction("click", args)
                "browser_type" -> browserAction("type", args)
                "browser_scroll" -> browserAction("scroll", args)
                "browser_ai_search" -> { val q=java.net.URLEncoder.encode(args.getString("query"), "UTF-8"); withBridgeSync { it.navigate("https://www.bing.com/search?q=$q"); "searching" }.let(::ok) }
                "mcp_list_tools" -> ok(listToolDescriptors().toString() + "\nremote=" + remoteMcp.aggregateRemoteTools().toString())
                "mcp_call" -> {
                    val name = args.getString("name")
                    val callArgs = args.optJSONObject("arguments") ?: JSONObject()
                    if (name.startsWith("remote.")) remoteMcp.forwardCall(name, callArgs) else call(name, callArgs)
                }
                "fs_list_dir" -> fsListDir(args.getString("path"))
                "fs_read_file" -> fsReadFile(args.getString("path"), args.optInt("maxBytes", 1_048_576), args.optBoolean("binary", false))
                "fs_write_file" -> fsWriteFile(args.getString("path"), args.getString("content"), args.optBoolean("binary", false), append = false)
                "fs_append_file" -> fsWriteFile(args.getString("path"), args.getString("content"), binary = false, append = true)
                "fs_create_dir" -> fsCreateDir(args.getString("path"))
                "fs_find_file" -> fsFindFile(args.optString("dir", "/sdcard/Download"), args.getString("pattern"))
                "fs_workspace_info" -> fsWorkspaceInfo()
                "agent_remember" -> { agentMemory.remember(args.getString("key"), args.getString("value")); ok("已记住：${args.getString("key")}") }
                "agent_recall" -> ok(agentMemory.recall(args.getString("key")) ?: "（没有找到这条记忆）")
                "agent_forget" -> { agentMemory.forget(args.getString("key")); ok("已忘记：${args.getString("key")}") }
                "agent_list_memory" -> ok(agentMemory.summaryText().ifBlank { "（暂无记忆）" })
                "agent_add_note" -> { agentMemory.addNote(args.getString("text")); ok("已记录笔记") }
                "browser_download" -> browserDownload(args.getString("url"))
                "net_rule_add" -> ok(netRules.add(args.getString("pattern"), args.getString("action")))
                "net_rule_list" -> ok(netRules.listJson())
                "net_rule_remove" -> ok(netRules.remove(args.getString("id")))
                "mcp_list_remote_servers" -> ok(remoteServersJson())
                "mcp_discover_local" -> ok(remoteMcp.discoverLocalMcp(true).let { remoteServersJson() })
                "mcp_remote_diagnostics" -> ok(remoteMcp.diagnostics().toString())
                else -> if (name.startsWith("github_")) githubTools.call(name, args) else error("unknown tool: $name")
            }
        } catch (e: Exception) {
            error(e.message ?: e.toString())
        }
    }


    private fun browserEval(code: String): JSONObject {
        return okJson(JSONObject().put("result", withBridgeAsync { bridge, latch, out ->
            bridge.evalJs(code) { value -> out[0] = value; latch.countDown() }
        }))
    }

    private fun browserDownload(url: String): JSONObject {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return errJson("only http/https URLs can be downloaded")
        }
        val result = withBridgeSync { it.aiDownload(url) }
        return if (result == "unsupported") errJson("download bridge not available")
        else ok(result)
    }

    private fun browserAction(kind: String, args: JSONObject): JSONObject {
        val query = args.optString("selector", args.optString("target", args.optString("text", ""))).trim()
        val escaped = JSONObject.quote(query)
        val text = JSONObject.quote(args.optString("text"))
        val js = when (kind) {
            "click" -> """
                (function(){
                  function norm(s){return String(s||'').replace(/\s+/g,' ').trim().toLowerCase()}
                  function visible(e){if(!e)return false;var r=e.getBoundingClientRect();var cs=getComputedStyle(e);return r.width>0&&r.height>0&&cs.visibility!=='hidden'&&cs.display!=='none'}
                  function score(e,q){
                    if(!visible(e))return -1;
                    var nq=norm(q), vals=[e.innerText,e.getAttribute('aria-label'),e.getAttribute('title'),e.getAttribute('data-testid'),e.getAttribute('name'),e.id,e.getAttribute('placeholder')];
                    var best=0; vals.forEach(function(x){var v=norm(x);if(!v)return; if(v===nq)best=Math.max(best,100); else if(v.indexOf(nq)>=0)best=Math.max(best,78); else if(nq.indexOf(v)>=0&&v.length>1)best=Math.max(best,55)});
                    if(/^(button|a|summary)$/.test((e.tagName||'').toLowerCase())||e.getAttribute('role')==='button')best+=8;
                    if(e.disabled||e.getAttribute('aria-disabled')==='true')best-=40;
                    return best;
                  }
                  function find(q){
                    var e=document.querySelector('[data-nebula-ai-id='+$escaped+']'); if(e&&visible(e))return e;
                    try{e=document.querySelector(q);if(e&&visible(e))return e}catch(x){}
                    var nodes=document.querySelectorAll('button,a,input[type=button],input[type=submit],[role=button],summary,[onclick],[aria-label],[data-testid]');
                    var best=null,bs=0;for(var i=0;i<nodes.length;i++){var sc=score(nodes[i],q);if(sc>bs){bs=sc;best=nodes[i]}}
                    return bs>=45?best:null;
                  }
                  var e=find($escaped);if(!e)return JSON.stringify({ok:false,error:'element not found',query:$escaped});
                  e.scrollIntoView({block:'center',inline:'center',behavior:'instant'});
                  try{e.focus({preventScroll:true})}catch(x){}
                  try{e.click()}catch(x){e.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}))}
                  return JSON.stringify({ok:true,tag:e.tagName.toLowerCase(),text:(e.innerText||e.value||e.getAttribute('aria-label')||'').trim().slice(0,160),id:e.getAttribute('data-nebula-ai-id')||''});
                })()
            """.trimIndent()
            "type" -> """
                (function(){
                  function norm(s){return String(s||'').replace(/\s+/g,' ').trim().toLowerCase()}
                  function visible(e){if(!e)return false;var r=e.getBoundingClientRect();var cs=getComputedStyle(e);return r.width>0&&r.height>0&&cs.visibility!=='hidden'&&cs.display!=='none'}
                  function find(q){
                    var e=document.querySelector('[data-nebula-ai-id='+$escaped+']');if(e&&visible(e))return e;
                    try{e=document.querySelector(q);if(e&&visible(e))return e}catch(x){}
                    var nq=norm(q),nodes=document.querySelectorAll('input:not([type=hidden]),textarea,[contenteditable=true],[role=textbox]'),best=null,bs=0;
                    for(var i=0;i<nodes.length;i++){var n=nodes[i];if(!visible(n))continue;var vals=[n.getAttribute('aria-label'),n.getAttribute('placeholder'),n.getAttribute('name'),n.id,n.getAttribute('title')];var sc=0;vals.forEach(function(x){var v=norm(x);if(v===nq)sc=Math.max(sc,100);else if(v&&v.indexOf(nq)>=0)sc=Math.max(sc,75);else if(v&&nq.indexOf(v)>=0&&v.length>1)sc=Math.max(sc,50)});if(sc>bs){bs=sc;best=n}}
                    if(best)return best;
                    return nodes.length===1&&visible(nodes[0])?nodes[0]:null;
                  }
                  var e=find($escaped);if(!e)return JSON.stringify({ok:false,error:'input not found',query:$escaped});
                  e.scrollIntoView({block:'center',inline:'center',behavior:'instant'});e.focus();var v=$text;
                  if(e.isContentEditable){e.textContent=v}else if('value' in e){var proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(e,v);else e.value=v}
                  e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:v}));e.dispatchEvent(new Event('change',{bubbles:true}));
                  return JSON.stringify({ok:true,value:(e.value||e.textContent||'').slice(0,200),id:e.getAttribute('data-nebula-ai-id')||''});
                })()
            """.trimIndent()
            else -> {
                val direction = if (args.optString("direction", "down").equals("up", true)) -1 else 1
                val amount = args.optInt("amount", 700).coerceIn(50, 5000)
                "window.scrollBy({top:${direction * amount},behavior:'smooth'}); JSON.stringify({ok:true,scrollY:window.scrollY,scrollHeight:document.documentElement.scrollHeight,viewport:innerHeight});"
            }
        }
        return browserEval("JSON.stringify({result:$js})")
    }

    private fun browserAiContext(): JSONObject {
        val code = """(function(){
            function clean(s){return String(s||'').replace(/\s+/g,' ').trim().slice(0,220)}
            function visible(e){if(!e)return false;var r=e.getBoundingClientRect(),c=getComputedStyle(e);return r.width>0&&r.height>0&&c.visibility!=='hidden'&&c.display!=='none'}
            function label(e){return clean(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('title')||e.getAttribute('placeholder')||e.getAttribute('name')||'')}
            var els=[],n=0;
            var nodes=document.querySelectorAll('a,button,input,textarea,select,[role=button],[role=link],[role=textbox],[contenteditable=true],[aria-label],[data-testid],summary');
            for(var i=0;i<nodes.length&&els.length<250;i++){
              var e=nodes[i];if(!visible(e))continue;
              var id=e.getAttribute('data-nebula-ai-id');if(!id){id='ai-'+(++n);e.setAttribute('data-nebula-ai-id',id)}
              var r=e.getBoundingClientRect();
              els.push({id:id,tag:(e.tagName||'').toLowerCase(),label:label(e),text:clean(e.innerText||''),aria:e.getAttribute('aria-label')||'',role:e.getAttribute('role')||'',name:e.getAttribute('name')||'',placeholder:e.getAttribute('placeholder')||'',href:e.href||'',type:e.getAttribute('type')||'',disabled:!!e.disabled||e.getAttribute('aria-disabled')==='true',rect:{x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height)}});
            }
            var body=(document.body&&document.body.innerText||'').replace(/\\n{3,}/g,'\\n\\n').slice(0,40000);
            return JSON.stringify({page:{title:document.title,url:location.href,language:document.documentElement.lang||navigator.language,viewport:{width:innerWidth,height:innerHeight},scrollY:scrollY,scrollHeight:document.documentElement.scrollHeight},text:body,elements:els});
        })()"""
        val raw = withBridgeAsync { bridge, latch, out -> bridge.evalJs(code) { v -> out[0]=v; latch.countDown() } }
        val decoded = try { org.json.JSONTokener(raw).nextValue() as? String ?: raw } catch (_: Exception) { raw }
        val root = try { JSONObject(decoded) } catch (_: Exception) { JSONObject().put("raw", decoded) }
        val allTabs = withBridgeAsync { bridge, latch, out ->
            bridge.aiAllTabsContext { value -> out[0] = value; latch.countDown() }
        }
        root.put("tabs", try { JSONArray(allTabs) } catch (_: Exception) { JSONArray() })

        val toolSummary = JSONArray()
        listToolDescriptors().let { tools ->
            for (i in 0 until tools.length()) {
                val t = tools.optJSONObject(i) ?: continue
                toolSummary.put(JSONObject().put("name", t.optString("name")).put("description", t.optString("description")))
            }
        }
        root.put("mcpTools", toolSummary)

        val memorySummary = agentMemory.summaryText()
        if (memorySummary.isNotBlank()) root.put("memory", memorySummary)

        return okJson(root)
    }

    private fun remoteServersJson(): String {
        val arr = JSONArray()
        remoteMcp.list().forEach {
            arr.put(JSONObject().put("name", it.name).put("url", it.url).put("tokenConfigured", it.token.isNotBlank()).put("discovered", it.discovered))
        }
        return arr.toString()
    }

    private fun withBridgeSync(block: (WebViewBridge) -> String): String {
        val bridge = bridgeRef?.get() ?: return "error: no active WebView"
        // Simple calls (navigate / currentUrl) are safe to run cross-thread via post,
        // but currentUrl needs to read the field synchronously on UI thread — MainActivity
        // caches it so this is a plain field read, not a WebView method call.
        return block(bridge)
    }

    private fun withBridgeAsync(block: (WebViewBridge, CountDownLatch, Array<String>) -> Unit): String {
        val bridge = bridgeRef?.get() ?: return "error: no active WebView"
        val latch = CountDownLatch(1)
        val out = arrayOf("")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            block(bridge, latch, out)
        }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return out[0]
    }

    // ---- fs_* tool implementations -----------------------------------
    // Relative paths resolve inside the workspace; a leading "/" means a
    // real absolute device path (e.g. /sdcard/Download) — this mirrors
    // dspp_mainworld.js's own tool descriptions verbatim, so changing it
    // would silently break every fs_* call the injected script makes.

    private fun resolvePath(path: String): File =
        if (path.startsWith("/")) File(path) else File(workspaceDir(), path)

    /** Reads up to [max] bytes — InputStream#readNBytes(int) needs API 33, but minSdk here is lower. */
    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (total < max) {
            val toRead = minOf(chunk.size, max - total)
            val n = input.read(chunk, 0, toRead)
            if (n <= 0) break
            buf.write(chunk, 0, n)
            total += n
        }
        return buf.toByteArray()
    }

    private fun fsListDir(path: String): JSONObject {
        val dir = resolvePath(path)
        if (!dir.exists() || !dir.isDirectory) return errJson("not a directory: $path")
        val entries = JSONArray()
        dir.listFiles()?.sortedBy { it.name }?.forEach {
            entries.put(
                JSONObject()
                    .put("name", it.name)
                    .put("dir", it.isDirectory)
                    .put("size", if (it.isDirectory) 0 else it.length())
                    .put("mtime", it.lastModified())
            )
        }
        return okJson(JSONObject().put("path", dir.path).put("entries", entries))
    }

    private fun fsReadFile(path: String, maxBytes: Int, binary: Boolean): JSONObject {
        val f = resolvePath(path)
        if (!f.exists() || !f.isFile) return errJson("file not found: $path")
        val bytes = f.inputStream().use { readBounded(it, maxBytes.coerceAtLeast(0)) }
        val truncated = f.length() > bytes.size
        val content = if (binary) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } else {
            String(bytes, Charsets.UTF_8)
        }
        return okJson(
            JSONObject()
                .put("path", f.path)
                .put("content", content)
                .put("binary", binary)
                .put("size", f.length())
                .put("truncated", truncated)
        )
    }

    private fun fsWriteFile(path: String, content: String, binary: Boolean, append: Boolean): JSONObject {
        val f = resolvePath(path)
        f.parentFile?.mkdirs()
        val bytes = if (binary) android.util.Base64.decode(content, android.util.Base64.DEFAULT)
        else content.toByteArray(Charsets.UTF_8)
        java.io.FileOutputStream(f, append).use { it.write(bytes) }
        return okJson(JSONObject().put("path", f.path).put("bytesWritten", bytes.size))
    }

    private fun fsCreateDir(path: String): JSONObject {
        val f = resolvePath(path)
        val created = f.exists() || f.mkdirs()
        return if (created) okJson(JSONObject().put("path", f.path).put("created", true))
        else errJson("failed to create directory: $path")
    }

    private fun fsFindFile(dir: String, pattern: String): JSONObject {
        val root = resolvePath(dir)
        if (!root.exists() || !root.isDirectory) return errJson("not a directory: $dir")
        val regex = globToRegex(pattern)
        val matches = JSONArray()
        var scanned = 0
        fun walk(d: File) {
            val children = d.listFiles() ?: return
            for (c in children) {
                if (scanned++ > 20_000) return
                if (c.isDirectory) walk(c)
                else if (regex.matches(c.name)) {
                    matches.put(JSONObject().put("path", c.path).put("size", c.length()))
                }
            }
        }
        walk(root)
        return okJson(JSONObject().put("dir", root.path).put("pattern", pattern).put("matches", matches))
    }

    private fun fsWorkspaceInfo(): JSONObject {
        val dir = workspaceDir()
        return okJson(
            JSONObject()
                .put("path", dir.path)
                .put("exists", dir.exists())
                .put("writable", dir.canWrite())
                .put("freeBytes", dir.freeSpace)
                .put("totalBytes", dir.totalSpace)
        )
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '[', ']', '{', '}', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    /** Wraps a payload for dspp_mainworld.js: content[0].text must itself parse as JSON. */
    private fun okJson(payload: JSONObject): JSONObject {
        val withOk = JSONObject(payload.toString()).put("ok", true)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", withOk.toString()))
        return JSONObject().put("content", content).put("isError", false)
    }

    private fun errJson(message: String): JSONObject {
        val payload = JSONObject().put("ok", false).put("error", message)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", payload.toString()))
        return JSONObject().put("content", content).put("isError", false)
    }

    private fun ok(text: String): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        return JSONObject().put("content", content).put("isError", false)
    }

    private fun error(text: String): JSONObject {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        return JSONObject().put("content", content).put("isError", true)
    }
}

/**
 * Simple domain-pattern based network rules, persisted as JSON. Consulted from
 * MainActivity's WebViewClient#shouldInterceptRequest to allow the assistant
 * (via net_rule_add) to block/allow specific hosts on the fly.
 */
class NetRuleStore(context: Context) {
    private val prefs = context.getSharedPreferences("nebula_net_rules", Context.MODE_PRIVATE)
    private val idGen = AtomicLong(System.currentTimeMillis())

    data class Rule(val id: String, val pattern: String, val action: String)

    @Synchronized
    fun all(): List<Rule> {
        val arr = JSONArray(prefs.getString("rules", "[]"))
        val list = mutableListOf<Rule>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Rule(o.getString("id"), o.getString("pattern"), o.getString("action")))
        }
        return list
    }

    @Synchronized
    fun add(pattern: String, action: String): String {
        val rules = all().toMutableList()
        val id = idGen.incrementAndGet().toString()
        rules.add(Rule(id, pattern, action))
        persist(rules)
        return "added rule $id: $action $pattern"
    }

    @Synchronized
    fun remove(id: String): String {
        val rules = all().filterNot { it.id == id }
        persist(rules)
        return "removed rule $id"
    }

    fun listJson(): String {
        val arr = JSONArray()
        all().forEach { arr.put(JSONObject().put("id", it.id).put("pattern", it.pattern).put("action", it.action)) }
        return arr.toString()
    }

    /** Returns true if [host] should be blocked. Explicit allow rules take precedence. */
    fun isBlocked(host: String): Boolean {
        val normalized = host.lowercase()
        val rules = all()
        if (rules.any { it.action.equals("allow", true) && normalized.contains(it.pattern.trim().lowercase()) }) return false
        return rules.any { it.action.equals("block", true) && normalized.contains(it.pattern.trim().lowercase()) }
    }

    private fun persist(rules: List<Rule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(JSONObject().put("id", it.id).put("pattern", it.pattern).put("action", it.action)) }
        prefs.edit().putString("rules", arr.toString()).apply()
    }
}
