package com.mice.nebulamcp

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * Tampermonkey/Via compatible userscript runtime for NebulaBrowser.
 *
 * Improvements over the old shim:
 * - persistent GM_getValue/GM_setValue per script
 * - GM_registerMenuCommand/notification/openInTab/clipboard stubs that are safe
 * - real cross-origin GM_xmlhttpRequest through Android networking (not XHR/CORS)
 * - each script gets a private GM scope so scripts cannot overwrite each other's store
 * - exceptions in one script never prevent the remaining scripts from running
 */
class ScriptInjector(
    private val context: Context,
    private val settings: SettingsStore,
    private val scripts: UserScriptStore
) {
    private val io = Executors.newCachedThreadPool()
    private val gmPrefs = context.getSharedPreferences("nebula_userscript_gm", Context.MODE_PRIVATE)

    /**
     * Installs one true document-start dispatcher. This fixes userscripts such as
     * Pagetual/东方永页机 that hook history, fetch, MutationObserver or DOM nodes
     * before the page has finished loading. The dispatcher asks the native bridge
     * for the scripts matching the current URL and runs document-start scripts
     * immediately, document-end scripts at DOMContentLoaded and idle scripts at load.
     */
    fun installDocumentStartRuntime(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        installBridge(webView)
        runCatching { webView.addJavascriptInterface(DocumentStartBridge(), "__nebulaUserscriptBridge") }
        val fixedDispatcher = """
            (function(){
              if(window.__nebulaUserscriptRuntime)return;
              window.__nebulaUserscriptRuntime=true;
              function runOne(s){
                try{
                  var code=String(s.wrapped||'');
                  var node=document.createElement('script');
                  node.setAttribute('data-nebula-userscript',String(s.id||''));
                  node.textContent=code;
                  (document.documentElement||document.head||document.body).appendChild(node);
                  node.remove();
                }catch(e){try{console.error('[Nebula Userscript] '+String(s.name||'Script')+':',e)}catch(_){} }
              }
              function run(list,phase){(list||[]).forEach(function(s){var at=String(s.runAt||'document-start').toLowerCase();if((phase==='start'&&at==='document-start')||(phase==='end'&&at!=='document-start'&&at!=='document-idle')||(phase==='idle'&&at==='document-idle'))runOne(s);});}
              try{
                var raw=window.__nebulaUserscriptBridge&&window.__nebulaUserscriptBridge.getScripts(location.href);
                var list=JSON.parse(raw||'[]');
                run(list,'start');
                var end=function(){run(list,'end')},idle=function(){run(list,'idle')};
                if(document.readyState==='loading'){document.addEventListener('DOMContentLoaded',end,{once:true});window.addEventListener('load',idle,{once:true});}
                else{end();setTimeout(idle,0);}
              }catch(e){try{console.error('[Nebula Userscript Bootstrap]',e)}catch(_){} }
            })();
        """.trimIndent()
        runCatching { WebViewCompat.addDocumentStartJavaScript(webView, fixedDispatcher, setOf("*")) }
    }

    fun injectStartScripts(webView: WebView, url: String) {
        if (!settings.scriptsEnabled) return
        installBridge(webView)
        scripts.matchingScripts(url).filter { it.runAt.equals("document-start", true) }.forEach { injectOne(webView, it) }
    }

    fun injectEndScripts(webView: WebView, url: String) {
        if (!settings.scriptsEnabled) return
        installBridge(webView)
        scripts.matchingScripts(url).filter { !it.runAt.equals("document-start", true) }.forEach { injectOne(webView, it) }
    }

    fun apply(webView: WebView) = installBridge(webView)

    fun clear() { /* bridge lifetime follows WebView */ }

    private fun installBridge(webView: WebView) {
        runCatching { webView.addJavascriptInterface(GmBridge(webView), "__nebulaGMBridge") }
    }

    private fun injectOne(webView: WebView, script: UserScriptStore.Script) {
        val shim = gmApiShim(script.id, script.name)
        val source = script.source
        // Keep every userscript in its own function scope. This is important for
        // scripts that declare GM_* globals or helper variables with the same name.
        val wrapped = """
            (function(){
              'use strict';
              try {
                window.__nebulaUserscriptRan=window.__nebulaUserscriptRan||{};
                if(window.__nebulaUserscriptRan[${JSONObject.quote(script.id)}]) return;
                window.__nebulaUserscriptRan[${JSONObject.quote(script.id)}]=1;
                $shim
                (function(){
                  try {
                    $source
                  } catch(__nebulaScriptError) {
                    console.error('[Nebula Userscript] ${jsQuote(script.name)}:', __nebulaScriptError);
                  }
                }).call(window);
              } catch(__nebulaRuntimeError) {
                console.error('[Nebula Userscript Runtime] ${jsQuote(script.name)}:', __nebulaRuntimeError);
              }
            })();
        """.trimIndent()
        runCatching { webView.evaluateJavascript(wrapped, null) }
            .onFailure { android.util.Log.w("NebulaScript", "inject failed: ${script.name}", it) }
    }

    private fun jsQuote(s: String): String = JSONObject.quote(s).removePrefix("\"").removeSuffix("\"")
        .replace("\\", "\\\\").replace("'", "\\'")

    private fun gmApiShim(scriptId: String, scriptName: String): String {
        val ns = JSONObject.quote(scriptId)
        val name = JSONObject.quote(scriptName)
        return """
        (function(){
          const __ns=$ns, __name=$name;
          function __load(k,d){
            try{const raw=window.__nebulaGMValues&&window.__nebulaGMValues[__ns+'::'+k];return raw===undefined?d:JSON.parse(raw);}catch(e){return d;}
          }
          function __save(k,v){
            try{window.__nebulaGMValues=window.__nebulaGMValues||{};window.__nebulaGMValues[__ns+'::'+k]=JSON.stringify(v);window.__nebulaGMBridge.saveValue(__ns+'::'+k,JSON.stringify(v));}catch(e){}
          }
          window.__nebulaGMValues=window.__nebulaGMValues||{};
          var GM_getValue=function(k,d){return __load(String(k),d)};
          var GM_setValue=function(k,v){__save(String(k),v)};
          var GM_deleteValue=function(k){try{delete window.__nebulaGMValues[__ns+'::'+k];window.__nebulaGMBridge.deleteValue(__ns+'::'+k);}catch(e){}};
          var GM_listValues=function(){return Object.keys(window.__nebulaGMValues).filter(function(k){return k.indexOf(__ns+'::')===0}).map(function(k){return k.substring(__ns.length+2)})};
          var GM_addStyle=function(css){var s=document.createElement('style');s.setAttribute('data-nebula-userscript',__ns);s.textContent=String(css||'');(document.head||document.documentElement).appendChild(s);return s;};
          var GM_addElement=function(parent,tag,attrs){try{if(typeof parent==='string'){attrs=tag;tag=parent;parent=document.head||document.documentElement;}var e=document.createElement(tag||'div');attrs=attrs||{};Object.keys(attrs).forEach(function(k){try{if(k==='textContent')e.textContent=attrs[k];else if(k==='innerHTML')e.innerHTML=attrs[k];else e.setAttribute(k,String(attrs[k]));}catch(_){}});(parent||document.documentElement).appendChild(e);return e;}catch(e){return null;}};
          var GM_log=function(){try{console.log('[GM '+__name+']',...arguments)}catch(e){}};
          var GM_info={scriptHandler:'NebulaBrowser',version:'20.3.4',scriptMetaStr:__name,script:{name:__name}};
          var GM_registerMenuCommand=function(label,fn,opts){try{window.__nebulaGMMenus=window.__nebulaGMMenus||{};var id=__ns+'::'+String(label)+'::'+Date.now();window.__nebulaGMMenus[id]={label:String(label),callback:fn,script:__name,accessKey:opts&&opts.accessKey};return id;}catch(e){return null;}};
          var GM_unregisterMenuCommand=function(id){try{if(window.__nebulaGMMenus)delete window.__nebulaGMMenus[id];}catch(e){}};
          var GM_openInTab=function(url,opts){try{window.open(String(url),'_blank');}catch(e){location.href=String(url);}return {closed:false,close:function(){}};};
          var GM_notification=function(details,onclick){try{var text=typeof details==='string'?details:(details&&details.text)||'';if(window.__nebulaToast)window.__nebulaToast(String(text));else console.log('[GM notification]',text);if(typeof onclick==='function')setTimeout(onclick,0);}catch(e){}};
          var GM_setClipboard=function(text){try{window.__nebulaGMBridge.clipboard(String(text||''));return Promise.resolve();}catch(e){return Promise.reject(e);}};
          var GM_getTab=function(cb){var tab={id:__ns};try{if(typeof cb==='function')cb(tab);}catch(e){}return tab;};
          var GM_saveTab=function(tab){return tab;};
          var GM_getResourceText=function(name){try{return window.__nebulaGMBridge.getResourceText(__ns,String(name))||'';}catch(e){return '';}};
          var GM_getResourceURL=function(name){try{return window.__nebulaGMBridge.getResourceUrl(__ns,String(name))||'';}catch(e){return '';}};
          var GM_xmlhttpRequest=function(opts){
            opts=opts||{};var id='gm_'+Date.now()+'_'+Math.random().toString(36).slice(2);window.__nebulaGMCallbacks=window.__nebulaGMCallbacks||{};window.__nebulaGMCallbacks[id]=opts;
            try{window.__nebulaGMBridge.request(id,JSON.stringify({method:opts.method||'GET',url:opts.url||'',headers:opts.headers||{},data:opts.data||null,responseType:opts.responseType||'text',overrideMimeType:opts.overrideMimeType||'',pageUrl:location.href,timeout:Number(opts.timeout||0),anonymous:!!opts.anonymous}));}
            catch(e){try{opts.onerror&&opts.onerror({error:String(e)});opts.onloadend&&opts.onloadend({error:String(e)});}catch(x){}delete window.__nebulaGMCallbacks[id];}
            return {abort:function(){try{window.__nebulaGMBridge.abort(id);}catch(e){}}};
          };
          var GM_download=function(details){var d=typeof details==='string'?{url:details}:details||{};try{window.__nebulaGMBridge.download(String(d.url||''),String(d.name||''));}catch(e){}};
          var unsafeWindow=window;
          window.__nebulaGMComplete=window.__nebulaGMComplete||function(id,json){
            try{var o=window.__nebulaGMCallbacks&&window.__nebulaGMCallbacks[id];if(!o)return;var r=JSON.parse(json||'{}');var responseValue=r.responseText||'';if(r.responseType==='json'){try{responseValue=JSON.parse(responseValue);}catch(_){responseValue=null;}}var resp={status:r.status||0,statusText:r.statusText||'',responseText:r.responseText||'',response:responseValue,responseHeaders:r.responseHeaders||'',finalUrl:r.finalUrl||r.url||''};if(r.ok){if(o.onreadystatechange)o.onreadystatechange(resp);if(o.onload)o.onload(resp);}else{if(o.onreadystatechange)o.onreadystatechange(resp);if(o.onerror)o.onerror(resp);}if(o.onloadend)o.onloadend(resp);}catch(e){}finally{try{delete window.__nebulaGMCallbacks[id];}catch(e){}}
          };
          try{Object.assign(window.__nebulaGMValues,JSON.parse(window.__nebulaGMBridge.loadValues(__ns)||'{}'));}catch(e){}
          window.GM_getValue=GM_getValue;window.GM_setValue=GM_setValue;window.GM_deleteValue=GM_deleteValue;window.GM_listValues=GM_listValues;
          window.GM_addStyle=GM_addStyle;window.GM_addElement=GM_addElement;window.GM_log=GM_log;window.GM_info=GM_info;window.GM_registerMenuCommand=GM_registerMenuCommand;window.GM_unregisterMenuCommand=GM_unregisterMenuCommand;
          window.GM_openInTab=GM_openInTab;window.GM_notification=GM_notification;window.GM_setClipboard=GM_setClipboard;window.GM_getResourceText=GM_getResourceText;window.GM_getResourceURL=GM_getResourceURL;window.GM_xmlhttpRequest=GM_xmlhttpRequest;window.GM_download=GM_download;window.GM_getTab=GM_getTab;window.GM_saveTab=GM_saveTab;window.unsafeWindow=unsafeWindow;
          window.GM=window.GM||{};window.GM.getValue=function(k,d){return Promise.resolve(GM_getValue(k,d))};window.GM.setValue=function(k,v){GM_setValue(k,v);return Promise.resolve()};window.GM.deleteValue=function(k){GM_deleteValue(k);return Promise.resolve()};window.GM.listValues=function(){return Promise.resolve(GM_listValues())};window.GM.xmlHttpRequest=GM_xmlhttpRequest;window.GM.addStyle=GM_addStyle;window.GM.addElement=GM_addElement;window.GM.getResourceText=function(k){return Promise.resolve(GM_getResourceText(k))};window.GM.getResourceUrl=function(k){return Promise.resolve(GM_getResourceURL(k))};
        })();
        """
    }

    private inner class DocumentStartBridge {
        @android.webkit.JavascriptInterface
        fun getScripts(url: String): String {
            if (!settings.scriptsEnabled) return "[]"
            return try {
                val arr = org.json.JSONArray()
                scripts.matchingScripts(url).forEach { script ->
                    arr.put(JSONObject().apply {
                        put("id", script.id)
                        put("name", script.name)
                        put("runAt", script.runAt)
                        put("wrapped", injectSourceForDocumentStart(script))
                    })
                }
                arr.toString()
            } catch (_: Exception) { "[]" }
        }
    }

    private fun injectSourceForDocumentStart(script: UserScriptStore.Script): String {
        val shim = gmApiShim(script.id, script.name)
        val source = script.source
        return """
            (function(){
              'use strict';
              try {
                window.__nebulaUserscriptRan=window.__nebulaUserscriptRan||{};
                if(window.__nebulaUserscriptRan[${JSONObject.quote(script.id)}]) return;
                window.__nebulaUserscriptRan[${JSONObject.quote(script.id)}]=1;
                $shim
                (function(){
                  try { $source }
                  catch(__nebulaScriptError){ try{console.error('[Nebula Userscript] ${jsQuote(script.name)}:',__nebulaScriptError)}catch(_){} }
                }).call(window);
              }catch(__nebulaRuntimeError){try{console.error('[Nebula Userscript Runtime] ${jsQuote(script.name)}:',__nebulaRuntimeError)}catch(_){} }
            })();
        """.trimIndent()
    }

    private inner class GmBridge(private val webView: WebView) {
        @JavascriptInterface fun loadValues(namespace: String): String {
            val all = gmPrefs.all
            val payload = JSONObject()
            all.filterKeys { it.startsWith(namespace + "::") }.forEach { (k,v) -> if (v is String) payload.put(k, v) }
            return payload.toString()
        }

        @JavascriptInterface fun saveValue(key: String, value: String) {
            gmPrefs.edit().putString(key, value).apply()
        }

        @JavascriptInterface fun deleteValue(key: String) { gmPrefs.edit().remove(key).apply() }

        @JavascriptInterface fun clipboard(text: String) {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Nebula", text))
            }
        }

        @JavascriptInterface fun getResourceText(scriptId: String, resourceName: String): String {
            return scripts.readCachedResource(scriptId, resourceName).orEmpty()
        }

        @JavascriptInterface fun getResourceUrl(scriptId: String, resourceName: String): String {
            return scripts.readCachedResource(scriptId, resourceName)?.let {
                "data:text/plain;charset=utf-8;base64," + android.util.Base64.encodeToString(it.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            }.orEmpty()
        }

        @JavascriptInterface fun download(url: String, name: String) {
            // Keep GM_download safe: open the URL through the browser download path.
            runCatching { webView.post { webView.loadUrl(url) } }
        }

        @JavascriptInterface fun abort(id: String) { /* best effort; request is short-lived */ }

        @JavascriptInterface fun request(id: String, payload: String) {
            io.execute {
                val out = JSONObject()
                var conn: HttpURLConnection? = null
                try {
                    val p = JSONObject(payload)
                    val url = p.optString("url")
                    if (url.isBlank()) throw IllegalArgumentException("empty url")
                    val pageUrl = p.optString("pageUrl")
                    val timeout = p.optInt("timeout", 0).coerceIn(1000, 60000)
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = p.optString("method", "GET").uppercase()
                        connectTimeout = if (timeout > 0) minOf(12000, timeout) else 12000
                        readTimeout = if (timeout > 0) timeout else 20000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
                        setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml,text/plain,application/json,*/*")
                        if (pageUrl.startsWith("http")) setRequestProperty("Referer", pageUrl)
                    }
                    val headers = p.optJSONObject("headers")
                    if (headers != null) {
                        headers.keys().forEach { k -> runCatching { conn!!.setRequestProperty(k, headers.optString(k)) } }
                    }
                    if (pageUrl.startsWith("http") && headers?.keys()?.asSequence()?.none { it.equals("Cookie", true) } == true) {
                        runCatching { CookieManager.getInstance().getCookie(pageUrl) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { conn!!.setRequestProperty("Cookie", it) }
                    }
                    val data = p.opt("data")
                    if (data != null && data != JSONObject.NULL && conn!!.requestMethod !in listOf("GET", "HEAD")) {
                        conn!!.doOutput = true
                        val bytes = data.toString().toByteArray(Charsets.UTF_8)
                        if (conn!!.getRequestProperty("Content-Type").isNullOrBlank()) conn!!.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        conn!!.outputStream.use { it.write(bytes) }
                    }
                    val status = conn!!.responseCode
                    val stream = if (status in 200..399) conn!!.inputStream else conn!!.errorStream
                    val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                    val headerObj = JSONObject()
                    conn!!.headerFields.forEach { (k,v) -> if (k != null) headerObj.put(k, v?.joinToString(", ").orEmpty()) }
                    val contentType = conn!!.contentType.orEmpty()
                    val overrideMime = p.optString("overrideMimeType")
                    val charsetName = Regex("charset\\s*=\\s*[\\\"']?([^;\\\"'\\s]+)", RegexOption.IGNORE_CASE).find(overrideMime)?.groupValues?.getOrNull(1)
                        ?: Regex("charset\\s*=\\s*[\\\"']?([^;\\\"'\\s]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.getOrNull(1)
                        ?: runCatching {
                            val head = bytes.copyOfRange(0, minOf(bytes.size, 16384)).toString(Charsets.ISO_8859_1)
                            Regex("<meta[^>]+charset\\s*=\\s*[\\\"']?([^\\\"'\\s>/]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.getOrNull(1)
                                ?: Regex("charset\\s*=\\s*([^;\\\"'\\s]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.getOrNull(1)
                        }.getOrNull()
                    val charset = runCatching { Charset.forName(charsetName ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
                    val body = bytes.toString(charset)
                    out.put("ok", status in 200..399)
                    out.put("status", status)
                    out.put("statusText", conn!!.responseMessage ?: "")
                    out.put("responseText", body)
                    out.put("responseHeaders", headerObj.toString())
                    out.put("finalUrl", conn!!.url?.toString().orEmpty())
                    out.put("responseType", p.optString("responseType", "text"))
                } catch (e: Exception) {
                    out.put("ok", false).put("status", 0).put("statusText", e.message ?: e.javaClass.simpleName).put("responseText", "")
                } finally { conn?.disconnect() }
                val json = JSONObject.quote(out.toString())
                val js = "window.__nebulaGMComplete&&window.__nebulaGMComplete(${JSONObject.quote(id)},$json);"
                webView.post { runCatching { webView.evaluateJavascript(js, null) } }
            }
        }
    }
}
