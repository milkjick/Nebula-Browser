package com.mice.nebulamcp

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared AI-browser primitives.  The UI lives in MainActivity, while this class
 * owns page-understanding JavaScript and the small, explicit action vocabulary
 * used by the AI agent.  Keeping actions typed makes it much harder for a model
 * response to turn into arbitrary JavaScript execution.
 */
class AiAgentManager {
    sealed class Action {
        data class Click(val selector: String) : Action()
        data class Type(val selector: String, val text: String) : Action()
        data class Scroll(val direction: String, val amount: Int = 650) : Action()
        data object Back : Action()
        data object Forward : Action()
        data class OpenTab(val url: String) : Action()
    }

    fun pageContextScript(maxText: Int = 12000): String = """
        (function(){
          function clean(s){ return String(s||'').replace(/\\s+/g,' ').trim(); }
          var title=clean(document.title);
          var text=clean(document.body ? document.body.innerText : '').slice(0,$maxText);
          var headings=Array.from(document.querySelectorAll('h1,h2,h3')).slice(0,30).map(function(e){return clean(e.innerText)}).filter(Boolean);
          var buttons=Array.from(document.querySelectorAll('button,[role=button]')).slice(0,50).map(function(e){return clean(e.innerText||e.getAttribute('aria-label'))}).filter(Boolean);
          var inputs=Array.from(document.querySelectorAll('input,textarea,[contenteditable="true"]')).slice(0,30).map(function(e){return {tag:e.tagName,type:e.getAttribute('type')||'',placeholder:e.getAttribute('placeholder')||'',name:e.getAttribute('name')||'',aria:e.getAttribute('aria-label')||''};});
          var links=Array.from(document.querySelectorAll('a[href]')).slice(0,60).map(function(e){return {text:clean(e.innerText).slice(0,160),href:e.href};}).filter(function(x){return x.href;});
          return JSON.stringify({title:title,url:location.href,language:document.documentElement.lang||'',charset:document.characterSet||'',headings:headings,buttons:buttons,inputs:inputs,links:links,text:text});
        })();
    """.trimIndent()

    fun elementSummaryScript(): String = """
        (function(){
          function clean(s){return String(s||'').replace(/\\s+/g,' ').trim().slice(0,180);}
          function css(el){
            if(!el || el.nodeType!==1)return '';
            if(el.id)return '#'+CSS.escape(el.id);
            var parts=[]; var cur=el;
            for(var i=0;cur&&cur.nodeType===1&&i<4;i++,cur=cur.parentElement){
              var p=cur.tagName.toLowerCase();
              if(cur.classList&&cur.classList.length)p+='.'+Array.from(cur.classList).slice(0,2).map(function(c){return CSS.escape(c)}).join('.');
              var sib=cur.parentElement?Array.from(cur.parentElement.children).filter(function(x){return x.tagName===cur.tagName}):[];
              if(sib.length>1)p+=':nth-of-type('+(sib.indexOf(cur)+1)+')';
              parts.unshift(p);
            }
            return parts.join(' > ');
          }
          var all=Array.from(document.querySelectorAll('a,button,input,textarea,select,[role=button],[contenteditable=true]')).slice(0,120);
          return JSON.stringify(all.map(function(e,i){return {index:i,tag:e.tagName.toLowerCase(),text:clean(e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('placeholder')),selector:css(e),href:e.href||'',visible:!!(e.offsetWidth||e.offsetHeight||e.getClientRects().length)}}));
        })();
    """.trimIndent()

    fun clickScript(selector: String): String = """
        (function(){var e=document.querySelector(${JSONObject.quote(selector)});if(!e)return JSON.stringify({ok:false,error:'element not found'});e.scrollIntoView({block:'center',behavior:'smooth'});e.click();return JSON.stringify({ok:true});})();
    """.trimIndent()

    fun typeScript(selector: String, text: String): String = """
        (function(){
          var e=document.querySelector(${JSONObject.quote(selector)});
          if(!e)return JSON.stringify({ok:false,error:'element not found'});
          e.focus();
          var v=${JSONObject.quote(text)};
          if(e.isContentEditable){e.textContent=v;}
          else if('value' in e){var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(p,'value');if(d&&d.set)d.set.call(e,v);else e.value=v;}
          e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
          return JSON.stringify({ok:true});
        })();
    """.trimIndent()

    fun scrollScript(direction: String, amount: Int): String {
        val y = if (direction.equals("up", true)) -amount else amount
        return "window.scrollBy({top:$y,left:0,behavior:'smooth'});JSON.stringify({ok:true,scrollY:window.scrollY});"
    }

    fun actionPlanFromText(raw: String): List<Action> {
        val jsonText = raw.substringAfter('[', "").substringBeforeLast(']', "").let { if (it.isBlank()) "[]" else "[$it]" }
        return try {
            val arr = JSONArray(jsonText)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    when (o.optString("action")) {
                        "click" -> o.optString("selector").takeIf { it.isNotBlank() }?.let { add(Action.Click(it)) }
                        "type" -> { val s=o.optString("selector"); if(s.isNotBlank()) add(Action.Type(s,o.optString("text"))) }
                        "scroll" -> add(Action.Scroll(o.optString("direction","down"),o.optInt("amount",650).coerceIn(100,1600)))
                        "back" -> add(Action.Back)
                        "forward" -> add(Action.Forward)
                        "open_tab" -> o.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { add(Action.OpenTab(it)) }
                    }
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun searchUrl(query: String, engine: String = "bing"): String {
        val encoded = Uri.encode(query.trim())
        return when (engine) {
            "google" -> "https://www.google.com/search?q=$encoded"
            "baidu" -> "https://www.baidu.com/s?wd=$encoded"
            else -> "https://www.bing.com/search?q=$encoded"
        }
    }
}
