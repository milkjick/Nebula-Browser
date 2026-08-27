
# 🌌 Nebula Browser

> 一个面向 Android 的 AI Browser Agent。
>
> 让浏览器不仅能够“打开网页”，还能够理解网页、规划任务、执行操作、调用 MCP 工具，并持续记住用户的任务上下文。



---

# 📖 项目介绍

Nebula Browser 是一个基于 Kotlin + Android WebView 构建的现代化 AI 浏览器。

项目的核心目标不是简单地：

```text
浏览器 + AI 聊天窗口
而是构建：
                    Nebula Browser
                           │
              ┌────────────┴────────────┐
              │                         │
           Browser                   AI Agent
              │                         │
      ┌───────┼────────┐        ┌───────┼────────┐
      │       │        │        │       │        │
     DOM     Tabs     Media    理解    规划     执行
      │       │        │        │       │        │
      └───────┴────────┘        └───────┼────────┘
                                        │
                              ┌─────────┴─────────┐
                              │                   │
                          Browser Actions        MCP
                              │                   │
                        ┌─────┼─────┐       ┌────┴────┐
                        │     │     │       │         │
                       DOM   Web   Media   Local    Remote
Nebula Browser 希望最终实现：
用户描述任务 → AI 理解 → AI 规划 → 浏览器执行 → 验证结果 → 继续执行 → 返回结果

✨ 核心能力

🤖 AI Browser Agent
Nebula AI Agent 是整个项目的核心。
AI 不再只是回答问题，而是可以理解当前浏览器环境并执行任务。
支持：
当前网页理解
多标签页理解
DOM 分析
页面文本分析
AI 搜索
AI 网页总结
AI 翻译
AI 页面解释
AI 点击
AI 输入
AI 滚动
AI 返回
AI 打开网页
AI 新建标签页
MCP 工具调用
多步骤任务执行
Agent 任务验证
例如：
找到这个页面的登录按钮并点击
Agent：
1. 获取当前网页
2. 分析 DOM
3. 查找可能的登录按钮
4. 判断元素可见性
5. 选择最佳元素
6. 请求操作确认
7. 执行点击
8. 检查页面变化
9. 验证操作结果
🧠 当前网页理解
AI 可以读取当前网页上下文：
标题
URL
页面语言
页面正文
DOM
可见元素
链接
按钮
输入框
媒体
例如：
用户：

总结当前网页

AI：

读取当前页面
↓
提取正文
↓
过滤导航/广告等无关内容
↓
生成摘要
🗂️ 多标签页 AI Context
AI 可以同时理解浏览器中的多个标签页。
例如：
Tab 1
GitHub

Tab 2
Google

Tab 3
项目文档

Tab 4
当前网页
用户可以直接询问：
总结我现在打开的所有网页。
或者：
比较 GitHub 页面和项目文档有什么区别。
Agent Context 会自动组合：
Current Tab
+
Other Tabs
+
Page Information
+
Page Text
+
DOM
形成 AI 上下文。
🎯 DOM 智能元素定位
传统自动化通常依赖：
#login
或者：
.button-login
但现代网页经常动态改变 DOM。
Nebula Browser 的目标是使用多维度信息寻找元素：
文本
Tag
ID
Class
name
placeholder
title
aria-label
role
DOM 层级
可见性
位置
例如用户：
点击登录按钮
Agent 可以分析：
<button>
登录
</button>
以及：
aria-label="登录"
title="Login"
最终选择最匹配的真实 DOM 元素。
🖱️ AI Browser Actions
Agent 可以生成浏览器动作。
打开网页
{
  "action": "open",
  "url": "https://github.com/"
}
新建标签页
{
  "action": "new_tab",
  "url": "https://github.com/"
}
返回
{
  "action": "back"
}
点击
{
  "action": "click",
  "target": "登录按钮"
}
输入
{
  "action": "input",
  "target": "搜索框",
  "text": "Nebula Browser"
}
滚动
{
  "action": "scroll",
  "direction": "down",
  "amount": 700
}
🧩 Agent Planner
复杂任务不会直接执行单个操作。
例如：
打开 GitHub，搜索 Nebula Browser，然后打开第一个结果。
Agent Planner 可以拆解：
Task
 │
 ├── Open GitHub
 │
 ├── Find search input
 │
 ├── Input "Nebula Browser"
 │
 ├── Submit search
 │
 ├── Wait page update
 │
 ├── Analyze search results
 │
 └── Open first result
最终形成：
Planner
   ↓
Action Queue
   ↓
Agent Executor
   ↓
Browser
   ↓
Verification
🔁 Agent Executor
Agent Executor 负责真正执行规划后的操作。
流程：
AI Planner
    ↓
Action
    ↓
ToolRegistry
    ↓
Browser Tool
    ↓
WebView
    ↓
网页变化
    ↓
Agent Verify
如果执行失败：
Action Failed
      ↓
重新读取页面
      ↓
重新定位元素
      ↓
重新规划
      ↓
Retry
✅ Agent Verify
Nebula Browser 不仅执行操作，还会验证结果。
例如：
AI：

点击登录按钮
执行：
click()
之后 Agent 检查：
URL 是否变化
DOM 是否变化
是否出现登录表单
是否出现错误信息
页面标题是否变化
然后判断：
SUCCESS
或者：
FAILED
必要时重新规划。
🧠 Agent 长任务
V20.2.2 开始支持长任务 Agent 架构。
例如：
帮我研究 Nebula Browser，
搜索多个网站，
阅读资料，
整理信息，
最后生成总结。
任务可以拆成：
Task
 │
 ├── Step 1
 ├── Step 2
 ├── Step 3
 ├── Step 4
 ├── Step 5
 └── Final Result
每个任务保存状态：
QUEUED
RUNNING
PAUSED
DONE
FAILED
CANCELLED
支持：
长任务
多步骤任务
Task Checkpoint
任务恢复
任务状态
任务失败重试
🧠 Agent Long Memory
Nebula Browser 提供本地 Agent Memory。
Agent 可以保存：
用户任务
网页信息
任务结果
历史操作
工作流
用户偏好
重要上下文
记忆检索采用：
Query
 ↓
Embedding
 ↓
Vector Search
 ↓
Cosine Similarity
 ↓
Relevant Memories
 ↓
AI Context
这样 Agent 可以从过去任务中找到相关信息。
例如：
用户：

继续上次的 GitHub 分析任务。
Agent 可以检索之前保存的：
GitHub 页面
分析结果
任务状态
历史上下文
继续任务。
🔎 本地向量检索
Agent Memory 不要求额外部署数据库。
当前设计支持本地：
Memory Store
+
Vector
+
Similarity Search
核心目标：
无需云端数据库
无需额外服务器
本地保存
快速检索
🔄 Web Workflow Engine
Nebula Browser 支持网页工作流。
例如：
打开网站
 ↓
登录
 ↓
搜索
 ↓
打开结果
 ↓
读取内容
 ↓
保存结果
可以保存为 Workflow。
结构：
Workflow
 │
 ├── Step 1
 ├── Step 2
 ├── Step 3
 ├── Step 4
 └── Step N
每个步骤可以调用：
Browser Action
或者：
MCP Tool
最终：
Workflow
    │
    ├── Browser
    │
    └── MCP
🔌 MCP
Nebula Browser 集成 Model Context Protocol。
MCP 可以让 Agent 使用外部工具。
架构：
Nebula AI Agent
       │
       ▼
  Tool Registry
       │
 ┌─────┴─────┐
 │           │
Browser      MCP
Tools        Tools
             │
      ┌──────┴──────┐
      │             │
   Local          Remote
   MCP             MCP
🔍 MCP Tool Discovery
Agent 可以获取 MCP Server 提供的工具。
例如：
browser_page_text
browser_page_info
browser_click
browser_type
browser_scroll
browser_back
browser_new_tab
browser_ai_context
以及：
github_*
fs_*
remote_*
AI 可以根据任务自动选择工具。
🖥️ Local MCP
支持连接本地 MCP Server。
例如：
http://127.0.0.1:8788/mcp
适用于：
本地自动化
文件操作
浏览器控制
本地 AI
开发工具
🌐 Remote MCP
支持远程 MCP Server。
例如：
https://example.com/mcp
认证信息由用户配置。
支持：
URL
Authorization
Token
🧠 MCP Agent Planner
未来 Agent 可以：
User
 ↓
AI
 ↓
分析任务
 ↓
选择 Browser Tool
或
MCP Tool
 ↓
执行
 ↓
验证
例如：
分析当前 GitHub 项目，
然后把结果保存到文件。
Agent：
browser_page_text
        ↓
AI Analysis
        ↓
fs_write_file
🖥️ Local AI
Nebula Browser 支持本地 AI Provider。
目标是允许连接：
Ollama
LM Studio
vLLM
OpenAI-Compatible Server
架构：
Nebula AI
    │
    ▼
AiProvider
    │
 ┌──┼─────────────┐
 │  │             │
API Local        Web
AI  AI           AI
本地 AI 的优势：
不需要云端 API
数据可以留在本地
可以运行自己的模型
支持开发测试
支持离线/局域网 AI
🌐 Web AI
Nebula Browser 可以设计 Web AI Provider。
用户可以在浏览器中正常登录 Web AI 服务。
例如：
Nebula Browser
      ↓
Web AI
      ↓
用户登录
      ↓
AI 页面
这种模式不一定需要 API Key。
但是：
Web AI 是否允许自动化操作取决于对应服务的网站设计和服务条款。
Nebula Browser 不绕过：
CAPTCHA
Cloudflare 人机验证
登录保护
访问控制
网站安全机制
📁 AI 文件分析
支持 AI 分析常见文本文件。
包括：
TXT
Markdown
JSON
XML
HTML
CSS
JS
TS
Kotlin
Java
Python
CSV
LOG
例如：
分析这个 Kotlin 文件为什么编译失败。
AI 可以：
读取文件
 ↓
分析代码
 ↓
定位错误
 ↓
解释原因
 ↓
生成修改建议
🎬 AI 视频理解
Nebula Browser 支持视频分析架构。
可以获取：
视频 URL
MIME
Duration
Width
Height
并可以进一步提取：
视频帧
交给支持视觉输入的 AI 模型。
流程：
Video
 ↓
Frame Extraction
 ↓
Vision Model
 ↓
AI Understanding
例如：
这个视频里面发生了什么？
Agent 可以分析视频关键画面。
📺 独立视频播放器
Nebula Browser 提供独立视频播放器。
资源发现：
HTML
 ↓
video
 ↓
source
 ↓
Video URL
 ↓
Nebula Video Player
支持：
播放
暂停
进度控制
横屏
全屏
屏幕常亮
视频信息
播放器与网页页面分离。
📚 阅读模式
Nebula Browser 提供独立阅读模式。
阅读设置只在进入阅读模式后显示。
支持：
字体大小
行距
阅读主题
主题可以包括：
浅色
深色
护眼
羊皮纸
夜间
📖 小说阅读
针对小说网站提供阅读辅助能力。
包括：
章节发现
章节列表
章节搜索
上一章
下一章
自动翻页
自动下一章
阅读模式
自定义阅读主题
字号调整
行距调整
🧹 小说净化
支持小说页面净化规则。
用于移除：
广告
弹窗
无关推荐
悬浮元素
第三方推广
无关导航
支持：
内置规则
自定义规则
本地规则导入
规则可以针对 DOM 元素进行处理。
🛡️ 网页跳转防护
针对部分小说网站中的第三方跳转，可以进行浏览器侧的安全控制。
目标：
正常小说页面
        │
        ▼
用户点击
        │
        ├── 正常链接 → 允许
        │
        └── 可疑第三方跳转 → 提示/阻止
不会绕过网站安全验证。
🔎 Resource Inspector
Resource Inspector 用于开发和网页分析。
支持查看网页中的公开资源。
包括：
Images
Videos
Links
HTML
视频资源可以实时发现：
Page
 ↓
WebView Request
 ↓
Resource Inspector
 ↓
Video Detection
🖼️ 图片资源
Resource Inspector 可以显示页面图片。
支持：
预览
打开
复制链接
图片资源应该尽可能保留：
原始 URL
MIME
尺寸
页面来源
避免因为资源处理错误导致图片无法显示。
🔧 Developer Tools
Nebula Browser 提供开发者功能。
包括：
页面信息
DOM
HTML
Console
Storage
Cookie
SEO
Resource Inspector
主要用于：
Web 开发
页面调试
网页结构分析
AI Agent 调试
🧵 WebView Thread Safety
Nebula Browser 特别重视 Android WebView 线程安全。
WebView 的结构：
                 WebView
                    │
        ┌───────────┴───────────┐
        │                       │
    UI Thread             Chromium Thread
        │                       │
        │                shouldInterceptRequest
        │                       │
        ▼                       ▼
 WebView API              Request Only
        │
        │
        └────────────┐
                     ▼
              runOnUiThread
                     │
                     ▼
               WebView 操作
原则：
shouldInterceptRequest() 中不直接调用 WebView API。
例如不要在：
shouldInterceptRequest()
里面直接：
webView.url
webView.title
webView.evaluateJavascript()
需要 UI 操作时：
runOnUiThread {
    // WebView 操作
}
这样可以避免：
A WebView method was called on thread 'ThreadPoolForeg'
类型的崩溃。
🏗️ 项目架构
NebulaBrowser
│
├── app
│   │
│   └── src/main
│       │
│       ├── java/com/mice/nebulamcp
│       │
│       ├── MainActivity.kt
│       │
│       ├── AiAgent.kt
│       ├── AiProvider.kt
│       ├── AgentPlanner.kt
│       ├── AgentExecutor.kt
│       ├── AgentMemory.kt
│       ├── AgentTask.kt
│       │
│       ├── WorkflowEngine.kt
│       │
│       ├── ToolRegistry.kt
│       ├── McpClient.kt
│       │
│       ├── BrowserAction.kt
│       ├── BrowserContext.kt
│       │
│       ├── DownloadHelper.kt
│       ├── VideoPlayerActivity.kt
│       │
│       └── ResourceInspector.kt
│
├── res
│   ├── layout
│   ├── drawable
│   ├── mipmap
│   ├── values
│   └── xml
│
├── build.gradle
├── settings.gradle
└── README.md
🔄 Agent 总体架构
                    User
                     │
                     ▼
              ┌─────────────┐
              │  AI Agent   │
              └──────┬──────┘
                     │
                     ▼
              Context Builder
                     │
          ┌──────────┼──────────┐
          │          │          │
        Page        Tabs       Memory
        DOM         Context    Vector
          │          │          │
          └──────────┼──────────┘
                     │
                     ▼
                  Planner
                     │
                     ▼
               Action Queue
                     │
              ┌──────┴──────┐
              │             │
          Browser        MCP
           Tools         Tools
              │             │
              └──────┬──────┘
                     ▼
                  Executor
                     │
                     ▼
                 Browser
                     │
                     ▼
                  Verify
                     │
              ┌──────┴──────┐
              │             │
           Success         Fail
              │             │
              ▼             ▼
           Memory        Re-plan
🔐 安全设计
Nebula Browser 遵循本地优先原则。
尽可能将：
浏览历史
AI 设置
Agent Memory
MCP 配置
Workflow
保存在本地。
API Key 不应该硬编码到：
源码
Git
README
生产版本推荐：
Android Keystore
+
加密存储
⚠️ 安全边界
Nebula Browser 不提供：
CAPTCHA 自动绕过
Cloudflare 绕过
Turnstile 绕过
reCAPTCHA 绕过
登录保护绕过
访问控制绕过
浏览器提供的是正常的：
JavaScript
Cookie
HTTPS
网页渲染
媒体播放
浏览器自动化
如果网站要求人机验证，应按照网站正常流程完成。
📱 系统要求
建议：
Android 8.0+
推荐：
Android 10+
开发环境：
Android Studio
JDK 17
Android SDK
Gradle
项目同时考虑：
AIDE
AndroidCS
等移动端 Android 开发环境。
🔨 构建
使用 Android Studio 打开项目。
执行：
./gradlew assembleDebug
APK：
app/build/outputs/apk/debug/
Release：
./gradlew assembleRelease
🧪 调试
开发过程中推荐重点测试：
AI Agent
WebView
DOM
MCP
Resource Inspector
Video Player
Reading Mode
Workflow
Agent Memory
尤其需要检查：
WebView UI Thread
Chromium Thread
之间的调用。
🗺️ Roadmap
V20.x
AI Agent
[x] AI Agent 基础架构
[x] 当前网页理解
[x] 多标签页 Context
[x] AI Planner
[x] AI Executor
[x] Agent Verify
[x] Browser Actions
[x] MCP 基础集成
[x] 长任务 Agent
[x] Agent Memory
[x] 本地向量检索
[x] Web Workflow
[x] Local AI Provider
[x] AI Video Analysis 基础架构
Browser
[x] 多标签页
[x] 页面浏览
[x] DOM
[x] HTML
[x] SEO
[x] Resource Inspector
[x] 视频资源发现
[x] 独立视频播放器
[x] 阅读模式
[x] 小说章节
[x] 自动翻页
[x] 小说净化
🚀 V21 计划
[ ] 更强 DOM 智能定位
[ ] 多候选元素评分
[ ] AI 操作自动纠错
[ ] Agent 自主重规划
[ ] Agent Memory 自动摘要
[ ] Memory 生命周期管理
[ ] 更强 Vector Search
[ ] Workflow 可视化编辑器
[ ] Workflow 条件分支
[ ] Workflow 循环
[ ] Workflow 定时执行
[ ] MCP 自动发现
[ ] MCP Tool 自动选择
[ ] 多 MCP Server
[ ] MCP Tool 权限管理
[ ] 本地视觉模型
[ ] AI 视频理解增强
🎯 最终目标
Nebula Browser 最终希望实现：
                    User
                     │
                     ▼
              ┌─────────────┐
              │ Nebula AI   │
              │    Agent    │
              └──────┬──────┘
                     │
              ┌──────┴──────┐
              │             │
          Understand      Memory
              │             │
              └──────┬──────┘
                     │
                  Planner
                     │
              ┌──────┴──────┐
              │             │
          Browser          MCP
           Tools           Tools
              │             │
              └──────┬──────┘
                     │
                  Executor
                     │
                  Browser
                     │
                  Verify
                     │
                  Memory
用户最终只需要告诉浏览器：
帮我查找这个网页的主要内容。
找到这个页面的登录按钮并点击。
打开 GitHub，搜索 Nebula Browser。
分析这个 JSON 文件。
总结我当前打开的所有网页。
找到页面中的视频并播放。
继续完成上次没有完成的任务。
调用 MCP 工具完成这个任务。
浏览器负责：
理解
   ↓
规划
   ↓
执行
   ↓
验证
   ↓
记忆
   ↓
继续
这就是 Nebula Browser 的核心方向。
📄 License
项目许可证根据最终发布版本确定。
推荐：
MIT License
⭐ 项目状态
Nebula Browser 当前处于持续开发阶段。
项目重点方向：
AI Browser
+
Agent
+
MCP
+
Long Task
+
Memory
+
Workflow
+
Local AI
+
Web Automation
功能和内部 API 可能持续变化。
欢迎：
Bug Report
Feature Request
Pull Request
Issue
🌌 Nebula Browser
让浏览器从“打开网页的工具”，变成“能够理解、规划、执行和记忆任务的 AI Agent”。

