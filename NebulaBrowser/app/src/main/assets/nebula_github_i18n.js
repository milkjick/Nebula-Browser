/* NebulaBrowser GitHub 中文化 layer.
 * The dictionary is intentionally limited to stable GitHub UI vocabulary.
 * Repository/README/Issue prose is handled by the normal multi-engine translator,
 * so code blocks and identifiers are never machine-translated by this layer.
 */
(function (global) {
  'use strict';
  if (!/^(www\.)?(github\.com|gist\.github\.com)$/i.test(location.hostname)) return;
  if (global.__nebulaGithubI18n) return;

  var dict = {
    'Code':'代码','Issues':'议题','Pull requests':'拉取请求','Discussions':'讨论','Actions':'操作','Projects':'项目',
    'Wiki':'Wiki','Security':'安全','Insights':'洞察','Repositories':'仓库','Repository':'仓库','Repositories':'仓库',
    'Stars':'星标','Forks':'复刻','Watchers':'关注者','Star':'星标','Unstar':'取消星标','Fork':'复刻','Watch':'关注','Unwatch':'取消关注',
    'Notifications':'通知','New issue':'新建议题','New pull request':'新建拉取请求','Compare':'比较','Releases':'发行版','Tags':'标签',
    'Branches':'分支','Commits':'提交','Blame':'逐行追溯','History':'历史','Raw':'原始文件','Edit':'编辑','Delete':'删除',
    'Copy':'复制','Download':'下载','Open':'打开','Close':'关闭','Cancel':'取消','Save changes':'保存更改','Save':'保存',
    'Submit new issue':'提交新议题','Submit pull request':'提交拉取请求','Comment':'评论','Leave a comment':'发表评论',
    'Write':'编写','Preview':'预览','Reply':'回复','Resolve conversation':'解决对话','Reopen':'重新打开','Close issue':'关闭议题',
    'Open issue':'打开议题','Open pull request':'打开拉取请求','Merged':'已合并','Closed':'已关闭','Open':'开启',
    'New repository':'新建仓库','Create repository':'创建仓库','New':'新建','Search':'搜索','Search or jump to…':'搜索或跳转…',
    'Search or jump to...':'搜索或跳转…','Sign in':'登录','Sign up':'注册','Sign out':'退出登录','Settings':'设置',
    'Your repositories':'你的仓库','Your profile':'你的个人资料','Your organizations':'你的组织','Your projects':'你的项目',
    'Dashboard':'控制面板','Home':'首页','Explore':'探索','Marketplace':'市场','Sponsors':'赞助者',
    'About':'关于','Readme':'自述文件','README':'自述文件','Contributors':'贡献者','Languages':'语言','License':'许可证',
    'Code of conduct':'行为准则','Contributing':'贡献指南','Security policy':'安全策略','Changelog':'更新日志',
    'Latest commit':'最新提交','Latest release':'最新发行版','View all branches':'查看全部分支','View all tags':'查看全部标签',
    'Go to file':'转到文件','Add file':'添加文件','Create new file':'创建新文件','Upload files':'上传文件',
    'New pull request':'新建拉取请求','Draft':'草稿','Draft pull request':'草稿拉取请求','Milestone':'里程碑',
    'Assignee':'负责人','Assignees':'负责人','Labels':'标签','Projects':'项目','Reviewers':'审查者',
    'Approve':'批准','Request changes':'请求修改','Comment on this pull request':'评论此拉取请求',
    'Files changed':'文件变更','Conversation':'对话','Commits':'提交','Checks':'检查','Changes':'变更',
    'Merge pull request':'合并拉取请求','Confirm merge':'确认合并','Squash and merge':'压缩并合并','Rebase and merge':'变基并合并',
    'Delete branch':'删除分支','Restore branch':'恢复分支','View code':'查看代码','View source':'查看源代码',
    'Copied!':'已复制！','Copied':'已复制','Loading…':'加载中…','Loading...':'加载中…','No results':'没有结果',
    'No contributions':'暂无贡献','This repository is empty.':'此仓库为空。','Go to your repositories':'前往你的仓库',
    'Public':'公开','Private':'私有','Internal':'内部','Archived':'已归档','Template':'模板','Forked from':'复刻自',
    'generated from':'生成自','Report repository':'举报仓库','Sponsor this project':'赞助此项目','Give feedback':'提供反馈',
    'Dismiss':'忽略','Learn more':'了解更多','Documentation':'文档','Help':'帮助','Keyboard shortcuts':'键盘快捷键'
  };

  function normalize(s){ return String(s||'').replace(/\s+/g,' ').trim(); }
  function skip(el){
    if(!el || !el.tagName) return true;
    var tag=el.tagName.toLowerCase();
    return /^(script|style|noscript|template|pre|code|textarea|input|select|option|svg|canvas)$/.test(tag) ||
      !!(el.closest && el.closest('pre,code,[data-nebula-github-skip="1"],[contenteditable="true"]'));
  }
  function translateElement(el){
    if(skip(el)) return false;
    var changed=false;
    var walker=document.createTreeWalker(el, NodeFilter.SHOW_TEXT, {
      acceptNode:function(n){
        if(!n.nodeValue || !normalize(n.nodeValue)) return NodeFilter.FILTER_REJECT;
        var p=n.parentElement;
        if(!p || skip(p)) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var nodes=[]; while(walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(function(n){
      var raw=n.nodeValue, key=normalize(raw), value=dict[key];
      if(value && key!==value){
        var leading=raw.match(/^\s*/)[0], trailing=raw.match(/\s*$/)[0];
        n.nodeValue=leading+value+trailing; changed=true;
      }
    });
    ['aria-label','title','placeholder'].forEach(function(attr){
      try{
        if(el.hasAttribute && el.hasAttribute(attr)){
          var raw=el.getAttribute(attr), key=normalize(raw), value=dict[key];
          if(value && value!==raw){el.setAttribute(attr,value);changed=true;}
        }
      }catch(_){ }
    });
    return changed;
  }
  function scan(root){
    root=root||document.body||document.documentElement;
    if(!root) return;
    translateElement(root);
    try{root.querySelectorAll('*').forEach(translateElement);}catch(_){ }
  }

  function mount(){
    scan(document.body);
    if(global.__nebulaGithubObserver) return;
    var queued=[],timer=0;
    var observer=new MutationObserver(function(mutations){
      mutations.forEach(function(m){
        m.addedNodes && Array.prototype.forEach.call(m.addedNodes,function(n){
          if(n.nodeType===1) queued.push(n);
        });
      });
      if(timer || !queued.length) return;
      timer=setTimeout(function(){
        timer=0; var batch=queued.splice(0,40);
        batch.forEach(function(n){try{scan(n);}catch(_){}});
        if(queued.length) { timer=setTimeout(function(){timer=0;var rest=queued.splice(0,40);rest.forEach(function(n){try{scan(n);}catch(_){}});},80); }
      },80);
    });
    observer.observe(document.documentElement||document.body,{childList:true,subtree:true});
    global.__nebulaGithubObserver=observer;
  }

  global.__nebulaGithubI18n={
    scan:scan,
    dictionary:dict,
    enabled:true,
    version:'20.3.4'
  };
  if(document.readyState==='loading') document.addEventListener('DOMContentLoaded',mount,{once:true}); else mount();
})(window);
