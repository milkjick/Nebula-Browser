package com.mice.nebulamcp

import android.content.Context
import android.webkit.WebView

/** Userscript injector with a minimal GM_* API shim. */
class ScriptInjector(
    private val context: Context,
    private val settings: SettingsStore,
    private val scripts: UserScriptStore
) {

    fun injectStartScripts(webView: WebView, url: String) {
        if (!settings.scriptsEnabled) return
        val matched = scripts.matchingScripts(url)
            .filter { it.runAt.equals("document-start", true) }
        matched.forEach { script ->
            runCatching {
                webView.evaluateJavascript(gmApiShim(), null)
                webView.evaluateJavascript(script.source, null)
            }
        }
    }

    fun injectEndScripts(webView: WebView, url: String) {
        if (!settings.scriptsEnabled) return
        val matched = scripts.matchingScripts(url)
            .filter { !it.runAt.equals("document-start", true) }
        matched.forEach { script ->
            runCatching {
                webView.evaluateJavascript(gmApiShim(), null)
                webView.evaluateJavascript(script.source, null)
            }
        }
    }

    fun apply(webView: WebView) {
        // 页面刷新时自动注入
    }

    fun clear() {
        // no-op
    }

    /**
     * 注入最小 GM API 兼容层，使常见油猴脚本可运行。
     * 支持：GM_addStyle, GM_setValue, GM_getValue, GM_deleteValue,
     *      GM_xmlhttpRequest, GM_log, GM_info, unsafeWindow
     */
    private fun gmApiShim(): String {
        return """
        (function(){
            if (window.__nebulaGmShimInjected) return;
            window.__nebulaGmShimInjected = true;
            const store = {};
            window.GM_addStyle = function(css){ const s=document.createElement('style');s.textContent=css;(document.head||document.documentElement).appendChild(s); };
            window.GM_setValue = function(k,v){ store[k]=v; };
            window.GM_getValue = function(k,d){ return k in store ? store[k] : d; };
            window.GM_deleteValue = function(k){ delete store[k]; };
            window.GM_log = function(...a){ console.log('[GM]', ...a); };
            window.GM_info = { scriptHandler:'NebulaBrowser', version:'1.0' };
            window.GM_xmlhttpRequest = function(opts){
                const xhr = new XMLHttpRequest();
                xhr.open(opts.method || 'GET', opts.url, true);
                if (opts.headers) for (const h in opts.headers) xhr.setRequestHeader(h, opts.headers[h]);
                xhr.onload = function(){ opts.onload && opts.onload({status:xhr.status, responseText:xhr.responseText, response:xhr.response}); };
                xhr.onerror = function(){ opts.onerror && opts.onerror(xhr); };
                xhr.send(opts.data || null);
            };
            window.unsafeWindow = window;
        })();
        """
    }
}