/*
 * Nebula Immersive Translation Engine 2
 *
 * Inspired by the public design of plateaukao/immersive-script (MIT):
 * bilingual blocks, lazy-ish batching, dynamic DOM support and caching.
 * Original implementation for NebulaBrowser.
 */
(function (global) {
  'use strict';
  var state = global.__nebulaImmersiveState || { nodes:{}, history:[], nextId:1 };
  global.__nebulaImmersiveState = state;

  var GITHUB = /(^|\.)github\.com$/i.test(location.hostname);
  var SKIP_SELECTOR = 'script,style,noscript,template,textarea,input,select,option,svg,canvas,video,audio,pre,code,nav,footer,header,button,[contenteditable="true"],[data-nebula-translation="1"],[data-nebula-translation-skip="1"]';

  function textOf(el) { return String(el && (el.innerText || el.textContent) || '').replace(/\u00a0/g,' ').replace(/[ \t]+/g,' ').replace(/\n{3,}/g,'\n\n').trim(); }
  function visible(el) { if(!el||el.nodeType!==1)return false; try{var s=getComputedStyle(el),r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&parseFloat(s.opacity||'1')>0&&r.width>2&&r.height>2;}catch(_){return true;} }
  function skip(el) { if(!el||!el.tagName)return true; try{if(el.matches(SKIP_SELECTOR))return true;}catch(_){} return !!(el.closest&&el.closest('[data-nebula-translation="1"],[contenteditable="true"],[data-nebula-translation-skip="1"]')); }
  function counts(t){var c={zh:0,latin:0,ja:0,ko:0,cy:0,ar:0};for(var i=0;i<t.length;i++){var ch=t.charAt(i);if(/[\u4e00-\u9fff]/.test(ch))c.zh++;else if(/[\u3040-\u30ff]/.test(ch))c.ja++;else if(/[\uac00-\ud7af]/.test(ch))c.ko++;else if(/[\u0400-\u04ff]/.test(ch))c.cy++;else if(/[\u0600-\u06ff]/.test(ch))c.ar++;else if(/[A-Za-z]/.test(ch))c.latin++;}return c;}
  function looksChinese(t){var c=counts(t);return c.zh>=Math.max(8,c.latin*0.55) && c.zh>c.latin;}

  function githubCandidates(){
    var sels=[
      '.markdown-body p','.markdown-body li','.markdown-body h1','.markdown-body h2','.markdown-body h3','.markdown-body h4',
      '.markdown-body blockquote','.comment-body p','.comment-body li','.js-comment-body p','.js-issue-title',
      '.Box-body p','.Box-body li','.timeline-comment .comment-body','.discussion-comment p',
      '[data-testid="issue-body"] p','[data-testid="issue-body"] li'
    ];
    var out=[];sels.forEach(function(sel){try{document.querySelectorAll(sel).forEach(function(e){if(out.indexOf(e)<0)out.push(e);});}catch(_){} });return out;
  }

  function normalCandidates(){
    var sels=['article p','article li','article h1','article h2','article h3','main p','main li','main h1','main h2','main h3','p','li','blockquote','h1','h2','h3','h4','h5','h6'];
    var out=[];sels.forEach(function(sel){try{document.querySelectorAll(sel).forEach(function(e){if(out.indexOf(e)<0)out.push(e);});}catch(_){} });
    // Some novel sites use divs with <br> and no p tags.
    if(out.length<3){try{document.querySelectorAll('.content,.chapter-content,.read-content,.novel-content,.showtxt,.txt,.userstuff,div').forEach(function(e){if(out.length<900&&out.indexOf(e)<0)out.push(e);});}catch(_){}
    }
    return out;
  }

  function collect(force){
    state.nodes={};
    var candidates=GITHUB?githubCandidates().concat(normalCandidates()):normalCandidates();
    var result=[],seen={},total={zh:0,latin:0,ja:0,ko:0,cy:0,ar:0};
    for(var i=0;i<candidates.length&&result.length<240;i++){
      var el=candidates[i];
      if(skip(el)||!visible(el))continue;
      // Never translate a parent whose child blocks will be translated separately.
      if(/^(ARTICLE|SECTION|DIV)$/i.test(el.tagName) && el.querySelector('p,li,blockquote,h1,h2,h3,h4,h5,h6'))continue;
      var t=textOf(el);if(t.length<3||t.length>4000)continue;
      // GitHub's UI contains many tiny labels; require a little more content there.
      if(GITHUB && t.length<12)continue;
      if(seen[t])continue;
      var c=counts(t),foreign=c.latin+c.ja+c.ko+c.cy+c.ar,all=foreign+c.zh;
      if(foreign<2)continue;
      if(looksChinese(t))continue;
      if(!force && foreign<5)continue;
      seen[t]=1;total.zh+=c.zh;total.latin+=c.latin;total.ja+=c.ja;total.ko+=c.ko;total.cy+=c.cy;total.ar+=c.ar;
      var id=state.nextId++;state.nodes[id]=el;el.setAttribute('data-nebula-translate-source',String(id));result.push({id:id,text:t});
    }
    var foreignTotal=total.latin+total.ja+total.ko+total.cy+total.ar, allTotal=foreignTotal+total.zh;
    return JSON.stringify({items:result,foreign:foreignTotal,total:allTotal,translatable:result.length>0&&(force||foreignTotal>=8&&foreignTotal/Math.max(1,allTotal)>=0.08),site:GITHUB?'github':'generic'});
  }

  function apply(items){
    var applied=0;
    (items||[]).forEach(function(item){
      var el=state.nodes[Number(item.id)];if(!el||!el.parentNode)return;
      var text=String(item.text||'').trim();if(!text||/^null$/i.test(text))return;
      var old=el.parentNode.querySelector('[data-nebula-translation-for="'+String(item.id).replace(/"/g,'\\"')+'"]');if(old)old.remove();
      var box=document.createElement('div');box.setAttribute('data-nebula-translation','1');box.setAttribute('data-nebula-translation-for',String(item.id));
      box.className='nebula-translation-result';box.textContent=text;
      box.style.cssText='margin:.35em 0 1em;padding:.15em 0;color:inherit;opacity:.82;font-size:.94em;line-height:1.65;font-weight:400;white-space:pre-wrap;overflow-wrap:anywhere;';
      if(GITHUB)box.style.cssText+='border-left:3px solid rgba(100,100,100,.25);padding-left:.65em;';
      el.parentNode.insertBefore(box,el.nextSibling);state.history.push(box);applied++;
    });
    return applied;
  }

  function restore(){try{state.history.forEach(function(n){if(n&&n.parentNode)n.remove();});}catch(_){}state.history=[];try{document.querySelectorAll('[data-nebula-translation="1"]').forEach(function(n){n.remove();});}catch(_){} }
  function clearSourceMarks(){try{document.querySelectorAll('[data-nebula-translate-source]').forEach(function(n){n.removeAttribute('data-nebula-translate-source');});}catch(_){} }

  global.NebulaImmersiveTranslate={collect:collect,apply:apply,restore:restore,clearSourceMarks:clearSourceMarks};
})(window);
