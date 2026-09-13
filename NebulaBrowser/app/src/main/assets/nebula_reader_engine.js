/*
 * Nebula Reader Engine 2
 *
 * Architecture inspired by X阅读模式 1.3.x (MIT) and Mozilla Readability's
 * content-density approach (Apache-2.0), but implemented independently for
 * Android WebView.  The engine deliberately reads the live DOM first: SPA
 * sites often render chapter text after document-start and detached clones
 * lose layout/state information.
 */
(function (global) {
  'use strict';

  var NOISE_RE = /(comment|comments|sidebar|side-bar|footer|header|navbar|menu|advert|ads|popup|modal|share|recommend|related|login|register|download|app-download|toolbar|breadcrumb|pagination|pager)/i;
  var CHAPTER_RE = /(?:^|\s)(?:第\s*[0-9零〇一二三四五六七八九十百千万两]+\s*[章节回卷集篇部]|chapter\s*[-_ ]?\d+|(?:序章|楔子|引子|前言|后记|尾声|终章|大结局|番外|正文))(?:\s|$)/i;

  function cleanText(v) {
    return String(v || '')
      .replace(/\u00a0/g, ' ')
      .replace(/[\u200B-\u200D\uFEFF]/g, '')
      .replace(/[ \t]+/g, ' ')
      .replace(/ *\n */g, '\n')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
  }

  function abs(v) {
    try { return new URL(v || '', location.href).href; } catch (_) { return String(v || ''); }
  }

  function visible(el) {
    if (!el || el.nodeType !== 1) return false;
    try {
      var s = getComputedStyle(el);
      return s.display !== 'none' && s.visibility !== 'hidden' && parseFloat(s.opacity || '1') > 0;
    } catch (_) { return true; }
  }

  function textOf(el) { return cleanText(el && (el.innerText || el.textContent)); }
  function className(el) { return String(el && el.className || ''); }
  function idClass(el) { return String(el && el.id || '') + ' ' + className(el); }
  function isNoise(el) {
    if (!el || !el.tagName) return true;
    var tag = el.tagName.toLowerCase();
    if (/^(script|style|noscript|template|svg|canvas|iframe|nav|footer|header|form|button|input|select|textarea|aside)$/i.test(tag)) return true;
    return NOISE_RE.test(idClass(el));
  }

  function chapterLinkText(a) {
    return cleanText(a && (a.innerText || a.textContent || a.getAttribute('aria-label') || a.getAttribute('title') || a.getAttribute('data-title')) || '');
  }

  function chapterNumber(t) {
    t = cleanText(t);
    var m = t.match(/第\s*([0-9]+)/i) || t.match(/chapter\s*[-_ ]?(\d+)/i);
    return m ? parseInt(m[1], 10) : null;
  }

  function getHref(el) {
    if (!el || !el.getAttribute) return '';
    var raw = el.getAttribute('href') || el.getAttribute('data-href') || el.getAttribute('data-url') || el.getAttribute('data-chapter-url') || el.getAttribute('data-link') || '';
    if (!raw) {
      var oc = el.getAttribute('onclick') || '';
      var m = oc.match(/(?:location(?:\.href)?|open|pushState)\s*\(\s*['"]([^'"]+)['"]/i);
      if (m) raw = m[1];
    }
    if (!raw || /^(javascript:|mailto:|tel:|#)/i.test(raw)) return '';
    return abs(raw);
  }

  var SITE_PROFILES = [
    { hosts: [/github\.com$/i, /githubusercontent\.com$/i],
      content: ['.markdown-body','.comment-body','.js-comment-body','.Box-body','article','main'],
      toc: [] },
    { hosts: [/qidian\.com$/i, /qidian\.com\.cn$/i], content: ['#chapter-content','.read-content','.content-text','article'], toc: ['#catalog','.catalog','.volume-list'] },
    { hosts: [/jjwxc\.net$/i], content: ['#content','.noveltext','.content','article'], toc: ['#novel-content','.chapter-list','.subnav'] },
    { hosts: [/fanqienovel\.com$/i,/fanqie\.com$/i], content: ['[class*="chapter-content"]','[class*="content"]','article'], toc: ['[class*="chapter-list"]','[class*="catalog"]'] },
    { hosts: [/biquge/i,/69shu/i,/uukanshu/i,/shubaow/i,/23qb/i,/wodebook/i,/shuquge/i,/xbiquge/i], content: ['#content','.content','.showtxt','.read-content','.chapter-content','.txt','article'], toc: ['#list','.listmain','.chapter-list','.catalog','.mulu','.book-list'] },
    { hosts: [/novelbin/i,/webnovel/i,/wuxiaworld/i], content: ['#chr-content','.chr-c','.chapter-content','.read-content','article'], toc: ['.chr-catalog','.chapter-list','.catalog','.list-chapter'] },
    { hosts: [/royalroad/i,/archiveofourown/i], content: ['.chapter-content','#chapters','article','.userstuff'], toc: ['.chapter-list','.chapter-listing','.toc'] }
  ];

  function profileFor(host) {
    for (var i=0;i<SITE_PROFILES.length;i++) for (var j=0;j<SITE_PROFILES[i].hosts.length;j++) if (SITE_PROFILES[i].hosts[j].test(host || location.hostname)) return SITE_PROFILES[i];
    return null;
  }

  function findToc(root) {
    var profile = profileFor(location.hostname);
    var selectors = (profile ? profile.toc.slice() : []).concat([
      '#catalog','#chapters','#chapter-list','#chapterlist','#allchapter','#all-chapter','#directory','#book-catalog',
      '.catalog','.chapter-list','.chapter_list','.chapterlist','.book-list','.book_list','.listmain','.mulu','.dir','.directory','.all-chapter','.section-box','.box_con',
      '[class*="catalog"]','[class*="chapter-list"]','[class*="chapter_list"]','[class*="chapterlist"]','[id*="chapter-list"]','[id*="chapterlist"]','[class*="directory"]','[class*="mulu"]'
    ]);
    for (var i=0;i<selectors.length;i++) {
      try {
        var list = root.querySelectorAll(selectors[i]);
        for (var j=0;j<list.length;j++) {
          var n = list[j].querySelectorAll('a[href],a[data-href],a[data-url],a[data-chapter-url],a[data-link]').length;
          if (n >= 2) return list[j];
        }
      } catch (_) {}
    }
    return null;
  }

  function linkScore(a, toc) {
    var text = chapterLinkText(a), href = getHref(a);
    if (!href || !/^https?:/i.test(href) || href === location.href || !text || text.length > 160) return -1e9;
    if (/^(首页|主页|登录|注册|书架|收藏|评论|下载|关于|举报|搜索|设置|上一章|下一章|下一页|上一页|目录)$/i.test(text)) return -1e9;
    var score = 0;
    if (toc && toc.contains(a)) score += 90;
    if (CHAPTER_RE.test(text)) score += 90;
    if (/第.{0,24}[章节回卷篇]/i.test(text)) score += 35;
    if (/^\d{1,7}$/.test(text)) score += 25;
    if (/\bchapter[-_ /]?\d+\b/i.test(text)) score += 70;
    if (/[/_-](?:chapter|read|book|novel)[/_-]?\d+/i.test(href)) score += 45;
    if (/\/\d{1,8}(?:\.html?|\/)?(?:\?|#|$)/i.test(href)) score += 22;
    if (text.length <= 80) score += 8;
    return score;
  }

  function discoverChapters(root) {
    var toc = findToc(root), all = [];
    try {
      root.querySelectorAll('a[href],a[data-href],a[data-url],a[data-chapter-url],a[data-link]').forEach(function(a){
        if (linkScore(a, toc) > 0) all.push(a);
      });
    } catch (_) {}
    // SPA chapter buttons and onclick links.
    try {
      root.querySelectorAll('[data-href],[data-url],[data-chapter-url],[data-link],[onclick]').forEach(function(el){
        if (el.tagName && el.tagName.toLowerCase() === 'a') return;
        var href = getHref(el), text = cleanText(el.innerText || el.textContent);
        if (href && text && CHAPTER_RE.test(text)) all.push({getAttribute:function(k){return k==='href'?href:null;}, innerText:text, textContent:text});
      });
    } catch (_) {}

    var seen = {}, links = [];
    all.forEach(function(a){
      var href = getHref(a), text = chapterLinkText(a);
      if (!href || !text || seen[href]) return;
      seen[href] = 1;
      links.push({title:text,url:href,number:chapterNumber(text)});
    });

    // When a site has no explicit catalog, detect repeated chapter URL families.
    if (links.length < 3) {
      var groups = {};
      try { root.querySelectorAll('a[href]').forEach(function(a){
        var href=getHref(a), text=chapterLinkText(a);
        if(!href||!text||text.length>120||href===location.href) return;
        var key=href.replace(/\d+/g,'#').replace(/[?#].*$/,'');
        (groups[key]||(groups[key]=[])).push({title:text,url:href,number:chapterNumber(text)});
      }); } catch (_) {}
      var best=[]; Object.keys(groups).forEach(function(k){ if(groups[k].length>best.length) best=groups[k]; });
      if(best.length>=4) links=best;
    }

    links.sort(function(a,b){
      if (a.number != null && b.number != null) return a.number-b.number;
      return 0;
    });
    return links.slice(0,2000).map(function(x){ return {title:x.title,url:x.url}; });
  }

  function candidateScore(el, profile) {
    if (!el || isNoise(el)) return -1e9;
    var text = textOf(el); if (text.length < 120) return -1e8 + text.length;
    var tag = el.tagName.toLowerCase(), ic = idClass(el);
    var p = 0, links=0, linkChars=0, paras=0, headings=0;
    try { paras=el.querySelectorAll('p,blockquote,pre').length; headings=el.querySelectorAll('h1,h2,h3').length; links=el.querySelectorAll('a').length; el.querySelectorAll('a').forEach(function(a){linkChars+=textOf(a).length;}); } catch (_) {}
    var density = Math.max(0.05, 1 - linkChars / Math.max(1,text.length));
    if (profile && profile.content.some(function(s){try{return el.matches(s)}catch(_){return false}})) p += 1000;
    if (/^(article|main)$/i.test(tag)) p += 300;
    if (/(chapter|content|read-content|reader|novel|article|markdown-body|comment-body|userstuff|entry|post|text)/i.test(ic)) p += 280;
    if (paras >= 2) p += Math.min(180, paras*12);
    if (headings > 0) p += 20;
    p += Math.min(900, Math.sqrt(text.length)*18) * density;
    p -= Math.min(250, links*2);
    // Prefer a compact article over body/document wrappers.
    if (tag === 'body' || tag === 'html') p -= 220;
    if (text.length > 250000) p -= 180;
    return p;
  }

  function cloneForReading(el) {
    var c = el.cloneNode(true);
    try { c.querySelectorAll('script,style,noscript,template,iframe,canvas,svg,nav,footer,header,aside,form,button,input,select,textarea').forEach(function(n){n.remove();}); } catch (_) {}
    try { c.querySelectorAll('[class*="ad"],[id*="ad"],[class*="recommend"],[class*="comment"],[class*="share"]').forEach(function(n){ if (textOf(n).length < 3000) n.remove(); }); } catch (_) {}
    return c;
  }

  function paragraphize(root) {
    var out=[];
    var nodes=root.querySelectorAll('h1,h2,h3,h4,h5,h6,p,blockquote,pre,li,div');
    for(var i=0;i<nodes.length;i++){
      var el=nodes[i]; if(isNoise(el))continue;
      var hasBlock=false; try{hasBlock=!!el.querySelector('p,h1,h2,h3,h4,h5,h6,blockquote,pre,li');}catch(_){ }
      if (/^div$/i.test(el.tagName) && hasBlock) continue;
      var t=cleanText(el.innerText || el.textContent); if(!t||t.length<2)continue;
      // Do not treat navigation links as prose.
      if (/^a$/i.test(el.tagName)) continue;
      if(t.length>8000){t.split(/\n{2,}/).forEach(function(x){x=cleanText(x);if(x)out.push(x);});}
      else out.push(t);
    }
    // Plain-text readers often use <br> rather than <p>.
    if(out.length<2){
      var raw=cleanText(root.innerText||root.textContent);
      raw.split(/\n{2,}/).forEach(function(x){x=cleanText(x);if(x)out.push(x);});
    }
    var result=[]; out.forEach(function(t){ if(!result.length || result[result.length-1]!==t) result.push(t); });
    return result;
  }

  function extract(selectors) {
    var root=document;
    var profile=profileFor(location.hostname);
    var candidates=[];
    var add=function(el){if(el&&candidates.indexOf(el)<0)candidates.push(el);};

    // 1) Site-specific selectors first. This mirrors the rule-first strategy of X阅读模式.
    if(profile) profile.content.forEach(function(sel){try{root.querySelectorAll(sel).forEach(add);}catch(_){} });
    // 2) Common reader/article containers.
    ['article','main','[role="main"]','.markdown-body','.comment-body','.chapter-content','.read-content','.reader-content','.novel-content','.content','#content','.showtxt','.txt','.userstuff','.entry-content','.post-content','section'].forEach(function(sel){try{root.querySelectorAll(sel).forEach(add);}catch(_){} });
    // 3) Bounded generic div scan.
    try { root.querySelectorAll('div').forEach(function(el){ if(candidates.length<9000) add(el); }); } catch (_) {}

    var best=null,bestScore=-Infinity;
    candidates.forEach(function(el){var s=candidateScore(el,profile);if(s>bestScore){bestScore=s;best=el;}});
    if(!best) best=root.body||root.documentElement;

    var content=cloneForReading(best);
    var paragraphs=paragraphize(content);
    var text=paragraphs.join('\n\n');

    // Last resort: try all explicit profile selectors and choose the longest usable text.
    if(text.replace(/\s/g,'').length<80 && profile){
      profile.content.forEach(function(sel){try{root.querySelectorAll(sel).forEach(function(el){var c=paragraphize(cloneForReading(el));var t=c.join('\n\n');if(t.length>text.length){paragraphs=c;text=t;}});}catch(_){} });
    }

    var chapters=discoverChapters(root);
    var title=cleanText(document.title||'');
    var heading=null;
    try { heading=best.querySelector('h1,h2,.chapter-title,[class*="chapter_title"],[class*="chapter-title"]'); } catch (_) {}
    var currentTitle=cleanText(heading && (heading.innerText||heading.textContent)) || title || '阅读模式';
    var prev='',next='';
    try { root.querySelectorAll('a[href],a[data-href],a[data-url]').forEach(function(a){
      var t=chapterLinkText(a),h=getHref(a);if(!h)return;
      var rel=(a.getAttribute('rel')||'').toLowerCase();
      if(rel==='prev'||/^(上一章|上章|上一页|prev|previous)$/i.test(t))prev=h;
      if(rel==='next'||/^(下一章|下章|下一页|next|next chapter)$/i.test(t))next=h;
    }); } catch (_) {}

    return JSON.stringify({title:currentTitle,text:text,paragraphs:paragraphs,chapters:chapters,prev:prev,next:next,url:location.href,host:location.hostname,contentLength:text.length,chapterCount:chapters.length,debug:{bestTag:best&&best.tagName,bestId:best&&best.id,bestClass:className(best),score:bestScore}});
  }

  global.NebulaReaderEngine={extract:extract};
})(window);
