package com.mice.nebulamcp

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Uses an AI website through the browser's normal WebView session.
 * No cookies, access tokens or passwords are extracted from the page.
 * WebView instances share the Android CookieManager profile, so a user who
 * has already logged into the selected provider can normally reuse that login.
 */
class WebAiProvider(private val context: Context, private val settings: SettingsStore) {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(): WebView {
        webView?.let { return it }
        val view = WebView(context)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.databaseEnabled = true
        view.settings.loadsImagesAutomatically = true
        view.webViewClient = object : WebViewClient() {}
        CookieManager.getInstance().setAcceptCookie(true)
        webView = view
        return view
    }

    fun loadProvider(onReady: (WebView) -> Unit = {}) {
        main.post {
            val v = ensureWebView()
            v.loadUrl(settings.aiWebUrl)
            waitForPage(v, 0, onReady)
        }
    }

    private fun waitForPage(v: WebView, attempt: Int, cb: (WebView) -> Unit) {
        if (v.progress >= 90 || attempt >= 80) {
            cb(v)
        } else {
            main.postDelayed({ waitForPage(v, attempt + 1, cb) }, 250)
        }
    }

    /**
     * Sends a prompt using configurable selectors. The default selectors cover
     * common textarea/contenteditable layouts. Users can override them in AI settings.
     */
    fun ask(prompt: String, done: (Result<String>) -> Unit) {
        main.post {
            val v = ensureWebView()
            if (v.progress < 90 || v.url.isNullOrBlank()) {
                waitForPage(v, 0) { ready -> askOnReady(ready, prompt, done) }
            } else {
                askOnReady(v, prompt, done)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun askOnReady(v: WebView, prompt: String, done: (Result<String>) -> Unit) {
        main.post {
            try {
                val input = JSONObject.quote(prompt)
                val inputSelector = JSONObject.quote(settings.aiWebInputSelector)
                val submitSelector = JSONObject.quote(settings.aiWebSubmitSelector)
                val responseSelector = JSONObject.quote(settings.aiWebResponseSelector)
                val js = """
                    (async function(){
                      const inputSel=$inputSelector, submitSel=$submitSelector, responseSel=$responseSelector;
                      const find=(sel)=>{try{return sel?document.querySelector(sel):null}catch(e){return null}};
                      const visible=(e)=>{if(!e)return false;const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'};
                      const label=(e)=>((e.innerText||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||'')+'').trim().toLowerCase();
                      let input=find(inputSel);
                      if(!input||!visible(input)) input=[...document.querySelectorAll('textarea,[contenteditable="true"],[role="textbox"],input[type="text"]')].find(visible);
                      if(!input) throw new Error('找不到 AI 输入框，请先打开并登录 AI 网页；也可在 AI 设置中填写输入框 Selector');

                      // Capture the current last assistant text so an old answer cannot be
                      // mistaken for the answer to this request.
                      const responseNodes=()=>{
                        const custom=find(responseSel);
                        const selectors=[
                          '[data-message-author-role="assistant"]',
                          '[data-testid*="assistant"]', '[data-testid*="message"]',
                          'main article', 'main [class*="markdown"]', 'main [class*="prose"]',
                          '[class*="markdown"]', '[class*="prose"]'
                        ];
                        let nodes=[]; if(custom) nodes.push(custom);
                        for(const sel of selectors) nodes.push(...document.querySelectorAll(sel));
                        return [...new Set(nodes)].filter(visible);
                      };
                      const readLast=()=>{const ns=responseNodes();return ns.map(x=>(x.innerText||x.textContent||'').trim()).filter(Boolean).pop()||''};
                      const before=readLast();

                      input.focus();
                      if(input.isContentEditable){
                        input.textContent=$input;
                        input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:$input}));
                        input.dispatchEvent(new Event('change',{bubbles:true}));
                      } else {
                        const proto=input.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                        const setter=Object.getOwnPropertyDescriptor(proto,'value')?.set;
                        if(setter) setter.call(input,$input); else input.value=$input;
                        input.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:$input}));
                        input.dispatchEvent(new Event('change',{bubbles:true}));
                      }

                      let submit=find(submitSel);
                      if(!submit||!visible(submit)) submit=[...document.querySelectorAll('button,[role="button"]')].find(x=>visible(x)&&/发送|send|submit|提交|enter/i.test(label(x)));
                      if(submit) submit.click();
                      else input.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));

                      const started=Date.now(); let last=before, stable=0;
                      while(Date.now()-started<120000){
                        await new Promise(r=>setTimeout(r,700));
                        const current=readLast();
                        if(current && current!==before){
                          if(current===last) stable++; else stable=0;
                          last=current;
                          // Two stable polls reduce the chance of reading a streaming answer too early.
                          if(stable>=2) return current;
                        }
                      }
                      if(last && last!==before) return last;
                      return '';
                    })()
                """.trimIndent()
                v.evaluateJavascript(js) { raw ->
                    main.post {
                        val value = try {
                            if (raw == null || raw == "null") "" else org.json.JSONTokener(raw).nextValue()?.toString().orEmpty()
                        } catch (_: Exception) { "" }
                        if (value.isNotBlank()) done(Result.success(value))
                        else done(Result.failure(IllegalStateException("AI 网页没有返回新回答。请确认已登录、输入框可用；必要时在 AI 设置中配置 Selector。")))
                    }
                }
            } catch (e: Exception) {
                done(Result.failure(e))
            }
        }
    }

    fun webView(): WebView = ensureWebView()
}
