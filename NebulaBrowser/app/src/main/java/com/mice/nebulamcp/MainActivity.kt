package com.mice.nebulamcp

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.provider.MediaStore
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.pm.ActivityInfo
import android.os.Build
import android.app.PictureInPictureParams
import android.util.Rational
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.content.pm.PackageManager
import android.os.Message
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity(), WebViewBridge {
    private lateinit var webViewContainer: FrameLayout
    private lateinit var etUrl: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var scriptInjector: ScriptInjector
    private lateinit var scripts: UserScriptStore
    private lateinit var adBlock: AdBlockStore
    private lateinit var settings: SettingsStore
    private lateinit var tabContainer: LinearLayout
    private lateinit var bookmarkStore: BookmarkStore
    private lateinit var historyStore: HistoryStore
    private lateinit var downloadHelper: DownloadHelper
    private lateinit var translationManager: TranslationManager
    private lateinit var browserRepository: BrowserRepository
    private lateinit var sitePermissionStore: SitePermissionStore
    private lateinit var novelRuleStore: NovelRuleStore
    private val devToolsSession = DevToolsSession()
    private lateinit var aiAgent: AiAgent
    private lateinit var webAiProvider: WebAiProvider
    private var aiAgentOutput: TextView? = null
    private var aiFileContext: String = ""
    private var aiAgentRequestInput: EditText? = null

    private val aiFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            val text = runCatching { contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(200_000) } ?: "" }.getOrDefault("")
            aiFileContext = text
            runOnUiThread { toast(if (text.isBlank()) "文件为空或无法读取" else "已载入文件分析上下文（${text.length} 字符）") }
        }
    }

    private val webViews = mutableMapOf<String, WebView>()
    // shouldInterceptRequest() runs on WebView Chromium background threads.
    // Never call WebView.getUrl()/getTitle()/getSettings() from that callback.
    private val pageUrlsByWebView = ConcurrentHashMap<WebView, String>()
    // Verification/challenge pages must not be damaged by the browser's ad-block/network-rule layer. This map is only read from callbacks and
    // never requires a WebView API call, so it is safe in Chromium callbacks.
    private val verificationModeByWebView = ConcurrentHashMap<WebView, Boolean>()
    private val tabTitles = mutableMapOf<String, String>()
    private val tabUrls = mutableMapOf<String, String>()
    private val tabOriginalUrls = mutableMapOf<String, String>()
    private val tabReadingMode = mutableMapOf<String, Boolean>()
    private var currentTabId: String? = null
    @Volatile private var cachedUrl = ""
    private var independentVideoButton: TextView? = null
    private val videoButtonHandler = Handler(Looper.getMainLooper())
    private val videoButtonProbe = object : Runnable {
        override fun run() {
            probeCurrentVideoForIndependentPlayer()
            videoButtonHandler.postDelayed(this, 1500L)
        }
    }

    // Prevent duplicate imports when an install page fires both its click handler
    // and a normal WebView navigation/download callback.
    private val pendingUserScriptImports = ConcurrentHashMap.newKeySet<String>()

    private val scriptPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importScriptFile(uri)
    }
    private val filterPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFilterFile(uri)
    }
    private val novelRulePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importNovelRuleFile(uri)
    }

    private var pendingFileChooser: android.webkit.ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserWebView: WebView? = null

    private var pendingWebPermissionRequest: android.webkit.PermissionRequest? = null

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var wasFullscreen = false
    private var fullscreenRoot: ViewGroup? = null

    private val webPermissionRequestCode = 701

    private val webFileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileChooser
        pendingFileChooser = null
        pendingFileChooserWebView = null

        if (callback == null) return@registerForActivityResult
        if (result.resultCode != RESULT_OK || result.data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data!!
        val uris = buildList {
            data.data?.let { add(it) }
            data.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { add(it) }
                }
            }
        }.distinct()

        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            } catch (_: UnsupportedOperationException) {
            }
        }

        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private val networkLogs = mutableListOf<NetworkLogEntry>()
    private var networkCaptureEnabled = false
    private var resourceSnifferUpdater: ((List<String>, List<String>, List<String>, List<String>) -> Unit)? = null

    private data class DevConsoleLog(
        val time: String,
        val level: String,
        val message: String
    )

    private val devConsoleLogs = mutableListOf<DevConsoleLog>()
    private var devConsoleOutput: TextView? = null
    private var devConsoleScroll: ScrollView? = null

    private data class NetworkLogEntry(
        val time: String,
        val method: String,
        val url: String
    )

    companion object {
        const val START_URL = "https://www.bing.com/"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val searchEngines = mapOf(
        "baidu" to "https://www.baidu.com/s?wd={query}",
        "google" to "https://www.google.com/search?q={query}",
        "bing" to "https://www.bing.com/search?q={query}",
        "metaso" to "https://metaso.cn/?q={query}",
        "sogou" to "https://www.sogou.com/web?query={query}",
        "toutiao" to "https://so.toutiao.com/search?keyword={query}",
        "sm" to "https://m.sm.cn/s?q={query}",
        "so360" to "https://www.so.com/s?q={query}",
        "duckduckgo" to "https://duckduckgo.com/?q={query}"
    )

    private val searchEngineLabels = mapOf(
        "baidu" to "百度",
        "google" to "谷歌",
        "bing" to "必应",
        "metaso" to "秘塔 AI",
        "sogou" to "搜狗",
        "toutiao" to "头条",
        "sm" to "神马",
        "so360" to "360 搜索",
        "duckduckgo" to "DuckDuckGo"
    )

    private fun mobileUserAgent(): String = WebSettings.getDefaultUserAgent(this)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Debug builds can be inspected from desktop Chrome DevTools (chrome://inspect).
        // The in-app Console below works independently and is also available on-device.
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val app = application as NebulaApp
        settings = app.settings
        scripts = UserScriptStore(this)
        adBlock = AdBlockStore(this)
        scriptInjector = ScriptInjector(this, settings, scripts)
        bookmarkStore = BookmarkStore(this)
        historyStore = HistoryStore(this)
        downloadHelper = DownloadHelper(this)
        translationManager = TranslationManager()
        browserRepository = BrowserRepository(this)
        sitePermissionStore = SitePermissionStore(this)
        novelRuleStore = NovelRuleStore(this)
        webAiProvider = WebAiProvider(this, settings)
        aiAgent = AiAgent(this, settings, app.toolRegistry, webAiProvider)
        bootstrapDeepSeekScripts()

        webViewContainer = findViewById(R.id.webViewContainer)
        installIndependentVideoButton()
        videoButtonHandler.post(videoButtonProbe)
        etUrl = findViewById(R.id.etUrl)
        progressBar = findViewById(R.id.progressBar)
        tabContainer = findViewById(R.id.tabContainer)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { currentWebView()?.goBack() }
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener { currentWebView()?.goForward() }
        findViewById<ImageButton>(R.id.btnReload).setOnClickListener { currentWebView()?.reload() }
        findViewById<ImageButton>(R.id.btnAi).setOnClickListener { showAiAgent() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { showQuickActions() }

        app.toolRegistry.attachBridge(this)

        etUrl.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val text = etUrl.text.toString().trim()
                if (text.isBlank()) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(etUrl.windowToken, 0)
                    etUrl.clearFocus()
                } else {
                    loadInput(text)
                }
                true
            } else false
        }

        val initialUrl = intent?.dataString?.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        } ?: homeUrl()

        addNewTab(initialUrl)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val url = intent?.dataString?.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        }
        if (url != null) {
            loadUrlInCurrentTab(url)
            etUrl.setText(url)
        }
    }

    // ========== 全屏弹窗基础设施 ==========

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun space(height: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                height
            )
        }
    }

    private fun showFullScreenDialog(title: String, contentView: View): BottomSheetDialog {
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f, 0f, 0f
                )
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(12), dp(12))
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.parseColor("#111827"))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { dialog.dismiss() }
        }
        detachFromParent(titleView)
        header.addView(titleView)
        detachFromParent(closeBtn)
        header.addView(closeBtn)
        detachFromParent(header)
        root.addView(header)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
            )
            setBackgroundColor(Color.parseColor("#F3F4F6"))
        }
        detachFromParent(divider)
        root.addView(divider)

        val contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val scrollView = ScrollView(this).apply {
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        scrollView.addView(contentView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        contentContainer.addView(scrollView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        detachFromParent(contentContainer)
        root.addView(contentContainer)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.92).toInt()
        )
        dialog.show()
        return dialog
    }

    private fun createActionCard(
        iconRes: Int,
        title: String,
        subtitle: String? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F9FAFB"))
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        detachFromParent(icon)
        card.addView(icon)

        val textContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.parseColor("#111827"))
            typeface = Typeface.DEFAULT_BOLD
        }
        detachFromParent(titleView)
        textContainer.addView(titleView)

        if (subtitle != null) {
            val subView = TextView(this).apply {
                text = subtitle
                textSize = 13f
                setTextColor(Color.parseColor("#6B7280"))
                setPadding(0, dp(4), 0, 0)
            }
            detachFromParent(subView)
            textContainer.addView(subView)
        }

        detachFromParent(textContainer)

        card.addView(textContainer)

        val arrow = TextView(this).apply {
            text = "›"
            textSize = 24f
            setTextColor(Color.parseColor("#9CA3AF"))
            setPadding(dp(8), 0, 0, 0)
        }
        detachFromParent(arrow)
        card.addView(arrow)

        return card
    }

    private fun createButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#3B82F6"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFF6FF"))
                cornerRadius = dp(20).toFloat()
            }
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    // ========== 主页生成与主题 ==========

    private fun homeUrl(): String {
        val customHtml = settings.customHomeHtml.trim()
        val customCss = settings.customHomeCss.trim()
        if (customHtml.isEmpty() && customCss.isEmpty()) {
            return "data:text/html;charset=utf-8,${Uri.encode(generateDefaultHomeHtml())}"
        }
        val finalHtml = if (customHtml.contains("<html", ignoreCase = true)) {
            if (customCss.isNotEmpty()) {
                customHtml.replace(Regex("</head>", RegexOption.IGNORE_CASE), "<style>\n$customCss\n</style></head>")
            } else customHtml
        } else {
            """
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    body { margin:0; padding:0; font-family:sans-serif; background:#ffffff; }
                    $customCss
                </style>
            </head>
            <body>
                $customHtml
            </body>
            </html>
            """.trimIndent()
        }
        return "data:text/html;charset=utf-8,${Uri.encode(finalHtml)}"
    }

    private fun generateDefaultHomeHtml(): String {
    val bookmarks = bookmarkStore.list()
    val bookmarkCards = StringBuilder()

    for (bm in bookmarks) {
        val safeUrl = bm.url.replace("'", "\\'")
        val safeTitle = bm.title.replace("'", "\\'").replace("<", "&lt;").replace(">", "&gt;")
        bookmarkCards.append("""
            <a class="shortcut" href="${bm.url}">
                <span class="icon">
                    <svg width="26" height="26" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                        <path d="M6.5 3.5h11v17L12 15.5l-5.5 5V3.5z" stroke="#64748B" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
                    </svg>
                </span>
                <span class="label">${safeTitle}</span>
                <span class="del" onclick="event.preventDefault(); event.stopPropagation(); NebulaHome.removeBookmark('${safeUrl}'); return false;">✕</span>
            </a>
        """.trimIndent())
    }

    return """
    <!DOCTYPE html>
    <html lang="zh-CN">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Nebula</title>
        <style>
            :root {
                --bg: #0f172a;
                --card: #1e293b;
                --text: #f8fafc;
                --muted: #94a3b8;
                --accent: #3b82f6;
                --accent2: #22d3ee;
            }
            * { margin:0; padding:0; box-sizing:border-box; }
            body {
                min-height:100vh;
                display:flex;
                flex-direction:column;
                align-items:center;
                justify-content:flex-start;
                padding-top:6vh;
                background:radial-gradient(circle at 20% 20%, #1e293b, #0f172a);
                font-family:-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                color:var(--text);
                padding-left:24px;
                padding-right:24px;
                user-select:none;
                -webkit-user-select:none;
            }
            .logo {
                font-size:36px;
                font-weight:700;
                letter-spacing:2px;
                background:linear-gradient(135deg, var(--accent), var(--accent2));
                -webkit-background-clip:text;
                -webkit-text-fill-color:transparent;
                margin-bottom:16px;
            }
            .search-box {
                width:min(520px, 88vw);
                display:flex;
                gap:10px;
                pointer-events:auto;
            }
            .search-box input {
                flex:1;
                height:44px;
                border:1px solid #334155;
                background:#1e293bcc;
                border-radius:22px;
                padding:0 20px;
                font-size:15px;
                color:var(--text);
                outline:none;
                backdrop-filter:blur(10px);
                transition:border 0.2s;
                user-select:text;
                -webkit-user-select:text;
            }
            .search-box input:focus { border-color:var(--accent); }
            .search-box button {
                height:44px;
                padding:0 22px;
                border:none;
                border-radius:22px;
                background:var(--accent);
                color:white;
                font-size:15px;
                font-weight:600;
                cursor:pointer;
            }
            .shortcuts {
                display: flex;
                gap: 14px;
                margin-top: 32px;
                flex-wrap: wrap;
                justify-content: center;
            }
            .shortcut {
                position: relative;
                width: 80px;
                height: 88px;
                display: flex;
                flex-direction: column;
                align-items: center;
                justify-content: center;
                gap: 6px;
                background: rgba(30, 41, 59, 0.75);
                backdrop-filter: blur(14px);
                -webkit-backdrop-filter: blur(14px);
                border: 1px solid rgba(148, 163, 184, 0.3);
                border-radius: 22px;
                color: var(--text);
                text-decoration: none;
                font-size: 13px;
                box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
                transition: all 0.2s ease;
            }
            .shortcut:active {
                transform: scale(0.94);
                background: rgba(51, 65, 85, 0.85);
            }
            .shortcut .icon {
                width: 26px;
                height: 26px;
                display: flex;
                align-items: center;
                justify-content: center;
            }
            .shortcut .label {
                max-width: 68px;
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
                font-weight: 500;
                color: #e2e8f0;
                font-size: 13px;
            }
            .shortcut .del {
                position: absolute;
                top: -6px;
                right: -6px;
                width: 18px;
                height: 18px;
                background: rgba(15, 23, 42, 0.55);
                color: rgba(255,255,255,0.75);
                border-radius: 50%;
                display: flex;
                align-items: center;
                justify-content: center;
                font-size: 10px;
                line-height: 1;
                cursor: pointer;
                z-index: 10;
                border: 1px solid rgba(148,163,184,0.35);
                box-shadow: 0 2px 6px rgba(0,0,0,0.3);
                backdrop-filter: blur(4px);
            }
            .hint {
                margin-top: 32px;
                font-size: 13px;
                color: var(--muted);
            }
        </style>
    </head>
    <body id="home-body">
        <div class="logo">NEBULA</div>
        <form class="search-box" action="#" method="get" onsubmit="return NebulaHomeSubmitSearch(this);">
            <input id="nebula-search-input" type="text" name="q" placeholder="${searchEngineLabels[settings.searchEngine] ?: "搜索"} · 搜索网页或输入网址" autocomplete="off" autofocus>
            <button type="submit">${searchEngineLabels[settings.searchEngine] ?: "搜索"}</button>
        </form>
        <div class="shortcuts">
            ${bookmarkCards}
        </div>
        
        <script>
            function NebulaHomeSubmitSearch(form) {
                var input = form && form.querySelector ? form.querySelector('input[name="q"]') : null;
                var query = input ? input.value.trim() : '';
                if (!query) {
                    if (input) { input.focus(); input.setCustomValidity('请输入搜索内容'); input.reportValidity(); setTimeout(function(){ input.setCustomValidity(''); }, 1200); }
                    return false;
                }
                if (window.NebulaHome && typeof window.NebulaHome.search === 'function') {
                    window.NebulaHome.search(query);
                }
                return false;
            }
            (function() {
                var body = document.getElementById('home-body');
                if (!body) return;
                var timer = null;
                function isInteractive(el) {
                    return !!(el && el.closest && el.closest('input, button, a, textarea, select, [contenteditable="true"]'));
                }
                function start(e) {
                    var target = e.target || e.srcElement;
                    if (isInteractive(target)) return;
                    timer = setTimeout(function() {
                        timer = null;
                        NebulaHome.addBookmark();
                    }, 600);
                }
                function cancel(e) {
                    if (timer) { clearTimeout(timer); timer = null; }
                }
                body.addEventListener('touchstart', start, {passive: true});
                body.addEventListener('touchend', cancel);
                body.addEventListener('touchmove', cancel);
                body.addEventListener('mousedown', start);
                body.addEventListener('mouseup', cancel);
                body.addEventListener('mouseleave', cancel);
                body.addEventListener('contextmenu', function(e) {
                    var target = e.target || e.srcElement;
                    if (!isInteractive(target)) {
                        e.preventDefault();
                        NebulaHome.addBookmark();
                    }
                });
            })();
        </script>
    </body>
    </html>
    """.trimIndent()
}
    private fun showThemeEditor() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(TextView(this).apply {
            text = "HTML"
            textSize = 14f
            setTextColor(Color.parseColor("#374151"))
            typeface = Typeface.DEFAULT_BOLD
        })
        val htmlBox = EditText(this).apply {
            hint = "HTML 内容（可留空）"
            setText(settings.customHomeHtml)
            minLines = 8
            maxLines = 12
            setSingleLine(false)
            textSize = 14f
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.TOP
        }
        detachFromParent(htmlBox)
        container.addView(htmlBox)
        container.addView(space(dp(16)))

        container.addView(TextView(this).apply {
            text = "CSS"
            textSize = 14f
            setTextColor(Color.parseColor("#374151"))
            typeface = Typeface.DEFAULT_BOLD
        })
        val cssBox = EditText(this).apply {
            hint = "CSS 样式（可留空）"
            setText(settings.customHomeCss)
            minLines = 8
            maxLines = 12
            setSingleLine(false)
            textSize = 14f
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.TOP
        }
        detachFromParent(cssBox)
        container.addView(cssBox)
        container.addView(space(dp(20)))

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(Button(this).apply {
            text = "恢复默认"
            setTextColor(Color.parseColor("#EF4444"))
            setBackgroundColor(Color.parseColor("#FEE2E2"))
            setOnClickListener {
                settings.customHomeHtml = ""
                settings.customHomeCss = ""
                toast("已恢复默认主页")
            }
        })
        btnRow.addView(space(dp(8)))
        btnRow.addView(Button(this).apply {
            text = "保存"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setOnClickListener {
                settings.customHomeHtml = htmlBox.text.toString()
                settings.customHomeCss = cssBox.text.toString()
                toast("主题已保存")
            }
        })
        detachFromParent(btnRow)
        container.addView(btnRow)

        showFullScreenDialog("自定义主题", container)
    }

    // ========== 书签添加与删除（JS 接口） ==========

    private inner class HomeJsInterface {
        @JavascriptInterface
        fun removeBookmark(url: String) {
            runOnUiThread {
                bookmarkStore.remove(url)
                toast("已删除书签")
                refreshHomeIfNeeded()
            }
        }

        @JavascriptInterface
        fun addBookmark() {
            runOnUiThread { showAddBookmarkDialog() }
        }

        /** 主页搜索框统一走原生搜索引擎配置，不再固定提交到 Bing。 */
        @JavascriptInterface
        fun search(query: String) {
            runOnUiThread { loadInput(query) }
        }
    }

    private fun refreshHomeIfNeeded() {
        val current = currentWebView()
        if (current != null && (current.url?.startsWith("data:text/html") == true || current.url?.startsWith("file:///android_asset/home.html") == true)) {
            current.loadUrl(homeUrl())
        }
    }

    private fun showAddBookmarkDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(TextView(this).apply {
            text = "名称"
            textSize = 14f
            setTextColor(Color.parseColor("#374151"))
            typeface = Typeface.DEFAULT_BOLD
        })
        val titleInput = EditText(this).apply {
            hint = "名称（可选）"
            textSize = 15f
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        detachFromParent(titleInput)
        container.addView(titleInput)
        container.addView(space(dp(16)))

        container.addView(TextView(this).apply {
            text = "网址"
            textSize = 14f
            setTextColor(Color.parseColor("#374151"))
            typeface = Typeface.DEFAULT_BOLD
        })
        val urlInput = EditText(this).apply {
            hint = "网址，如 https://example.com"
            textSize = 15f
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        detachFromParent(urlInput)
        container.addView(urlInput)
        container.addView(space(dp(20)))
        container.addView(Button(this).apply {
            text = "添加"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setOnClickListener {
                val title = titleInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (url.isBlank()) {
                    toast("网址不能为空")
                    return@setOnClickListener
                }
                val finalUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                val finalTitle = title.ifBlank { finalUrl }
                bookmarkStore.add(finalTitle, finalUrl)
                toast("已添加书签")
                refreshHomeIfNeeded()
            }
        })
        showFullScreenDialog("添加书签", container)
    }

    // ========== 功能弹窗 ==========

    private fun captureCurrentPage() {
        val view = currentWebView() ?: return toast("当前没有网页")
        if (view.width <= 0 || view.height <= 0) return toast("网页尚未准备好")
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                withContext(Dispatchers.IO) {
                    val name = "Nebula_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.png"
                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("无法创建图片")
                    contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    } ?: throw IllegalStateException("无法写入图片")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    }
                    bitmap.recycle()
                }
                toast("网页截图已保存到 Pictures/Screenshots")
            } catch (e: Exception) {
                toast("截图失败：${e.message ?: "未知错误"}")
            }
        }
    }

    private fun showAiSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val mode = android.widget.Spinner(this)
        mode.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("浏览器 AI（已登录网页，无需 API Key）", "API AI（需要 API Key）", "本地 AI（OpenAI 兼容接口）"))
        mode.setSelection(when (settings.aiProviderMode) { "api" -> 1; "local" -> 2; else -> 0 })

        val provider = EditText(this).apply { hint = "AI 网页名称，例如 DeepSeek Web"; setSingleLine(true); setText(settings.aiWebProvider) }
        val webUrl = EditText(this).apply { hint = "AI 网页地址"; setSingleLine(true); setText(settings.aiWebUrl) }
        val inputSelector = EditText(this).apply { hint = "输入框 CSS Selector（可留空自动识别）"; setSingleLine(true); setText(settings.aiWebInputSelector) }
        val submitSelector = EditText(this).apply { hint = "发送按钮 CSS Selector（可留空自动识别）"; setSingleLine(true); setText(settings.aiWebSubmitSelector) }
        val responseSelector = EditText(this).apply { hint = "AI 回复 CSS Selector（可留空自动识别）"; setSingleLine(true); setText(settings.aiWebResponseSelector) }

        val key = EditText(this).apply { hint = "API Key（仅 API 模式需要）"; setSingleLine(true); setText(settings.aiApiKey); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val model = EditText(this).apply { hint = "模型"; setSingleLine(true); setText(settings.aiModel) }
        val base = EditText(this).apply { hint = "API 地址"; setSingleLine(true); setText(settings.aiBaseUrl) }
        val localTitle = TextView(this).apply { text = "本地 AI：支持 Ollama / LM Studio / vLLM 等 OpenAI 兼容服务"; textSize = 15f; setTypeface(null, Typeface.BOLD) }
        val localBase = EditText(this).apply { hint = "本地 AI 地址，例如 http://127.0.0.1:11434/v1"; setSingleLine(true); setText(settings.localAiBaseUrl) }
        val localModel = EditText(this).apply { hint = "本地模型名称，例如 llama3.2"; setSingleLine(true); setText(settings.localAiModel) }
        val localKey = EditText(this).apply { hint = "本地服务 API Key（可留空）"; setSingleLine(true); setText(settings.localAiApiKey) }
        val confirm = android.widget.CheckBox(this).apply { text = "执行网页操作前必须确认"; isChecked = settings.aiRequireConfirmation }

        val webTitle = TextView(this).apply { text = "浏览器 AI：复用已登录的 AI 网页会话"; textSize = 15f; setTypeface(null, Typeface.BOLD) }
        val apiTitle = TextView(this).apply { text = "API AI：可选的第三方 API 模式"; textSize = 15f; setTypeface(null, Typeface.BOLD) }
        box.addView(TextView(this).apply { text = "AI 提供方式"; textSize = 20f; setTypeface(null, Typeface.BOLD) })
        box.addView(space(dp(8))); box.addView(mode); box.addView(space(dp(12))); box.addView(webTitle)
        box.addView(provider); box.addView(space(dp(6))); box.addView(webUrl); box.addView(space(dp(6)))
        box.addView(inputSelector); box.addView(space(dp(6))); box.addView(submitSelector); box.addView(space(dp(6))); box.addView(responseSelector)
        box.addView(space(dp(14))); box.addView(apiTitle); box.addView(key); box.addView(space(dp(6))); box.addView(model); box.addView(space(dp(6))); box.addView(base)
        box.addView(space(dp(14))); box.addView(localTitle); box.addView(localBase); box.addView(space(dp(6))); box.addView(localModel); box.addView(space(dp(6))); box.addView(localKey); box.addView(confirm)
        box.addView(space(dp(12)))
        box.addView(createButton("保存 AI 设置") {
            settings.aiProviderMode = when (mode.selectedItemPosition) { 1 -> "api"; 2 -> "local"; else -> "web" }
            settings.aiWebProvider = provider.text.toString().trim().ifBlank { "DeepSeek Web" }
            settings.aiWebUrl = webUrl.text.toString().trim().ifBlank { "https://chat.deepseek.com/" }
            settings.aiWebInputSelector = inputSelector.text.toString().trim()
            settings.aiWebSubmitSelector = submitSelector.text.toString().trim()
            settings.aiWebResponseSelector = responseSelector.text.toString().trim()
            settings.aiApiKey = key.text.toString().trim()
            settings.aiModel = model.text.toString().trim().ifBlank { "deepseek-chat" }
            settings.aiBaseUrl = base.text.toString().trim().ifBlank { "https://api.deepseek.com" }
            settings.aiRequireConfirmation = confirm.isChecked
            settings.localAiBaseUrl = localBase.text.toString().trim().ifBlank { "http://127.0.0.1:11434/v1" }
            settings.localAiModel = localModel.text.toString().trim().ifBlank { "llama3.2" }
            settings.localAiApiKey = localKey.text.toString().trim()
            toast(when (settings.aiProviderMode) { "web" -> "已保存：浏览器 AI 模式，不需要 API Key"; "local" -> "已保存：本地 AI 模式"; else -> "已保存：API AI 模式" })
        })
        box.addView(space(dp(8)))
        box.addView(createButton("测试浏览器 AI 会话") {
            settings.aiWebUrl = webUrl.text.toString().trim().ifBlank { "https://chat.deepseek.com/" }
            webAiProvider.loadProvider { toast("AI 网页已加载，请确认账号已经登录") }
        })
        showFullScreenDialog("Nebula AI → AI 设置", box)
    }

    private fun detachFromParent(view: android.view.View?) {
        val parent = view?.parent
        if (parent is android.view.ViewGroup) {
            parent.removeView(view)
        }
    }

fun showAiAgent() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val request = EditText(this).apply { hint = "例如：找到登录按钮并点击；打开 GitHub 搜索 Nebula Browser"; minLines = 3; gravity = Gravity.TOP; setPadding(dp(12),dp(12),dp(12),dp(12)) }
        val output = TextView(this).apply { textSize=14f; setTextColor(Color.parseColor("#263238")); setPadding(0,dp(12),0,dp(12)); text="AI 会读取当前网页、所有标签页和可用 MCP 工具，再生成执行计划。" }
        aiAgentOutput = output
        val run = createButton("分析并生成执行计划") {
            if (settings.aiProviderMode == "api" && settings.aiApiKey.isBlank()) { showAiSettings(); return@createButton }
            output.text = if (settings.aiProviderMode == "web") "正在通过已登录的 ${settings.aiWebProvider} 网页 AI 分析…" else "正在读取网页、DOM、标签页和 MCP 工具…"
            aiAgent.buildContext { ctx ->
                val fullRequest = request.text.toString().trim() + if (aiFileContext.isNotBlank()) "\n\n附加文件内容（UTF-8）：\n$aiFileContext" else ""
                aiAgent.plan(fullRequest, ctx) { result ->
                    result.onSuccess { plan ->
                        val sb=StringBuilder("${plan.message}\n\n执行步骤：\n")
                        plan.actions.forEachIndexed { i,a -> sb.append("${i+1}. ${a.action}").append(if(a.selector.isNotBlank()) " 目标=${a.selector}" else "").append(if(a.text.isNotBlank()) " 文本=${a.text}" else "").append(if(a.url.isNotBlank()) " URL=${a.url}" else "").append(if(a.name.isNotBlank()) " MCP=${a.name}" else "").append('\n') }
                        output.text=sb.toString()
                        val execute=createButton("确认执行全部步骤") { executeAiPlan(plan, output) }
                        box.addView(space(dp(8))); box.addView(execute); execute.setOnClickListener { executeAiPlan(plan, output) }
                    }.onFailure { output.text="AI 规划失败：${it.message}" }
                }
            }
        }
        aiAgentRequestInput = request
        val fileButton = createButton("选择文件并加入 AI 分析") {
            aiFilePicker.launch(arrayOf("text/*", "application/json", "application/xml", "application/javascript", "application/x-javascript", "text/csv", "text/markdown"))
        }
        box.addView(TextView(this).apply { text="Nebula AI Agent"; textSize=22f; setTypeface(null,Typeface.BOLD) })
        box.addView(TextView(this).apply { text=when (settings.aiProviderMode) { "web" -> "当前 AI：${settings.aiWebProvider}（浏览器登录会话）"; "local" -> "当前 AI：${settings.localAiModel}（本地模型）"; else -> "当前 AI：${settings.aiModel}（API）" }; textSize=13f; setTextColor(Color.DKGRAY) })
        if (settings.aiProviderMode == "web") {
            // WebAiProvider intentionally keeps one WebView instance so the AI
            // website session/cookies survive dialog changes. A View can only
            // have one parent, however; showAiAgent may be opened again after
            // the same WebView was attached to a previous dialog. Always detach
            // it from its previous parent before attaching it to this panel.
            val aiWeb = webAiProvider.webView()
            detachFromParent(aiWeb)
            box.addView(aiWeb, LinearLayout.LayoutParams(-1, dp(220)))
            webAiProvider.loadProvider()
        }
        box.addView(space(dp(8))); box.addView(request); box.addView(space(dp(8))); box.addView(fileButton); box.addView(space(dp(8))); box.addView(run); box.addView(output)
        box.addView(createButton("AI 设置") { showAiSettings() })
        box.addView(createButton("Agent 高级功能（长任务 / 向量记忆 / 工作流 / 本地 AI / 视频理解）") { showAgentAdvanced() })
        showFullScreenDialog("Nebula AI Agent", box)
    }

    private fun showAgentAdvanced() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val info = TextView(this).apply { text = "长任务、语义记忆、工作流、本地模型和视频理解都由 ToolRegistry 统一管理。"; textSize = 13f; setTextColor(Color.DKGRAY) }
        box.addView(info); box.addView(space(dp(10)))
        box.addView(createButton("长任务 Agent") {
            val goal = EditText(this).apply { hint = "任务目标" }
            val steps = EditText(this).apply { hint = "步骤 JSON，例如 [{\"action\":\"browser_page_text\",\"arguments\":{}}]"; minLines = 4; gravity = Gravity.TOP }
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8),0,dp(8),0); addView(goal); addView(steps) }
            MaterialAlertDialogBuilder(this).setTitle("创建可恢复长任务").setView(c).setNegativeButton("取消", null).setPositiveButton("创建") { _,_ ->
                try { val t=(application as NebulaApp).longTaskAgent.create(goal.text.toString().trim(), org.json.JSONArray(steps.text.toString().trim())); toast("任务已创建：${t.id.take(8)}") }
                catch(e:Exception){ toast("步骤 JSON 无效：${e.message}") }
            }.show()
        })
        box.addView(createButton("语义记忆搜索") {
            val q=EditText(this).apply{hint="输入要回忆的内容"}
            MaterialAlertDialogBuilder(this).setTitle("Agent 长记忆向量检索").setView(q).setNegativeButton("取消",null).setPositiveButton("搜索"){_,_->
                lifecycleScope.launch(Dispatchers.IO){val r=(application as NebulaApp).vectorMemory.summary(q.text.toString(),8);runOnUiThread{MaterialAlertDialogBuilder(this@MainActivity).setTitle("相关记忆").setMessage(r.ifBlank{"没有找到相关记忆"}).setPositiveButton("确定",null).show()}}
            }.show()
        })
        box.addView(createButton("本地 AI 测试") {
            val q=EditText(this).apply{hint="给本地模型发送测试问题";minLines=3}
            MaterialAlertDialogBuilder(this).setTitle("本地 AI").setView(q).setNegativeButton("取消",null).setPositiveButton("发送"){_,_->
                lifecycleScope.launch(Dispatchers.IO){val r=(application as NebulaApp).localAiProvider.ask(q.text.toString());runOnUiThread{MaterialAlertDialogBuilder(this@MainActivity).setTitle("本地 AI 回复").setMessage(r.getOrElse{"错误：${it.message}"}).setPositiveButton("确定",null).show()}}
            }.show()
        })
        box.addView(createButton("视频 AI 理解") {
            val q=EditText(this).apply{hint="视频 URI（content://、file:// 或可访问的 http(s) URL）"}
            MaterialAlertDialogBuilder(this).setTitle("视频 AI 理解").setView(q).setNegativeButton("取消",null).setPositiveButton("分析"){_,_->
                lifecycleScope.launch(Dispatchers.IO){val r=(application as NebulaApp).videoUnderstanding.analyze(q.text.toString().trim());runOnUiThread{MaterialAlertDialogBuilder(this@MainActivity).setTitle("视频分析结果").setMessage(r.getOrElse{"错误：${it.message}"}).setPositiveButton("确定",null).show()}}
            }.show()
        })
        box.addView(createButton("工作流") {
            val name=EditText(this).apply{hint="工作流名称"}; val steps=EditText(this).apply{hint="步骤 JSON";minLines=4;gravity=Gravity.TOP}
            val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(name);addView(steps)}
            MaterialAlertDialogBuilder(this).setTitle("保存工作流").setView(c).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->try{val w=(application as NebulaApp).workflowEngine.save(name.text.toString().trim(),org.json.JSONArray(steps.text.toString()));toast("工作流已保存：${w.name}")}catch(e:Exception){toast("步骤 JSON 无效：${e.message}")}}.show()
        })
        showFullScreenDialog("Agent 高级功能", box)
    }

    private fun showAgentMemory() {
        val memory = (application as NebulaApp).toolRegistry.agentMemory
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val summary = memory.summaryText()
        container.addView(TextView(this).apply {
            text = "AI Agent 在执行任务时可以主动记住一些事情（用户偏好、任务进度等），下次执行任务时会带上这些记忆。"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, 0, 0, dp(14))
        })
        container.addView(TextView(this).apply {
            text = summary.ifBlank { "（暂无记忆）" }
            textSize = 14f
            setTextColor(Color.parseColor("#111827"))
            setTextIsSelectable(true)
        })
        container.addView(space(dp(16)))
        container.addView(createButton("清空全部记忆") {
            memory.clearAll()
            toast("已清空")
            showAgentMemory()
        })
        showFullScreenDialog("Agent 记忆", container)
    }

    private fun executeAiPlan(plan: AiAgent.Plan, output: TextView) {
        val summary = plan.actions.mapIndexed { i,a -> "${i+1}. ${a.action} ${a.selector.ifBlank { a.name.ifBlank { a.text.ifBlank { a.url } } }}${if (a.confirmation) " [AI 标记为需要确认]" else ""}" }.joinToString("\n")
        val runNow = {
            lifecycleScope.launch(Dispatchers.IO) {
                val lines=StringBuilder("开始执行 ${plan.actions.size} 个步骤…\n")
                for ((index, action) in plan.actions.withIndex()) {
                    val result=aiAgent.execute(action)
                    lines.append("${index+1}. ${action.action}: ${aiAgent.extractText(result).take(500)}\n")
                    runOnUiThread { output.text=lines.toString() }
                    if (result.optBoolean("isError", false)) break
                    val verify=aiAgent.verify(action)
                    val verifyText = aiAgent.extractText(verify).take(260)
                    lines.append("   ✓ Verify: $verifyText\n")
                    runOnUiThread { output.text=lines.toString() }
                    if (verify.optBoolean("isError", false) || verifyText.contains("\"ok\":false")) {
                        lines.append("   ✗ Verify 失败，停止后续步骤，避免错误继续扩散。\n")
                        runOnUiThread { output.text=lines.toString() }
                        break
                    }
                }
            }
        }
        if (settings.aiRequireConfirmation) {
            MaterialAlertDialogBuilder(this).setTitle("确认 AI 执行计划").setMessage(summary.ifBlank { "AI 没有生成可执行步骤" }).setNegativeButton("取消", null).setPositiveButton("确认执行") { _,_ -> runNow() }.show()
        } else runNow()
    }

    private fun showQuickActions() {
        val dialog = MaterialAlertDialogBuilder(this).create()
        val scrollView = HorizontalScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isHorizontalScrollBarEnabled = false
            setPadding(8, 16, 8, 16)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun addItem(label: String, iconRes: Int, onClick: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(18, 10, 18, 10)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick(); dialog.dismiss() }
            }
            val iconSize = (28 * resources.displayMetrics.density).toInt()
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }
            val text = TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor("#111827"))
                gravity = Gravity.CENTER
                setPadding(0, 6, 0, 0)
                includeFontPadding = false
            }
            detachFromParent(icon)
            item.addView(icon)
            detachFromParent(text)
            item.addView(text)
            detachFromParent(item)
            container.addView(item)
        }

        addItem("脚本", R.drawable.ic_script) { showScriptManager() }
        addItem("拦截", R.drawable.ic_adblock) { showAdBlockManager() }
        addItem("隐藏元素", R.drawable.ic_element_hider) { startElementPicker() }
        addItem("书签", R.drawable.ic_bookmark) { showBookmarks() }
        addItem("添加书签", R.drawable.ic_bookmark_add) { showAddBookmarkDialog() }
        addItem("历史", R.drawable.ic_history) { showHistory() }
        addItem("MCP", R.drawable.ic_mcp) { McpInfoSheet.newInstance().show(supportFragmentManager, "mcp_info") }
        addItem("新标签", R.drawable.ic_newtab) { addNewTab(homeUrl()) }
        addItem("搜索", R.drawable.ic_search) { showSearchEngineSetting() }
        addItem("页内查找", R.drawable.ic_find_in_page) { showFindInPage() }
        addItem("翻译此页", R.drawable.ic_translate) { translateCurrentPage(true) }
        addItem("自动翻译", R.drawable.ic_translate_auto) { toggleAutoTranslate() }
        addItem("翻译设置", R.drawable.ic_translate_settings) { showTranslationSettings() }
        addItem("Nebula AI Agent", R.drawable.ic_ai_translate) { showAiAgent() }
        addItem("Agent 记忆", R.drawable.ic_ai_translate) { showAgentMemory() }
        addItem("AI 设置", R.drawable.ic_translate_settings) { showAiSettings() }
        addItem("分享网页", R.drawable.ic_share) { shareCurrentPage() }
        addItem("复制网址", R.drawable.ic_copy_link) { copyCurrentUrl() }
        addItem("清除网页缓存", R.drawable.ic_clear_cache) { clearCurrentWebData() }
        addItem("保存网页", R.drawable.ic_save_page) { saveCurrentPage() }
        addItem("网页文本", R.drawable.ic_copy_text) { copyPageText() }
        addItem("在外部浏览器打开", R.drawable.ic_open_external) { openCurrentInExternalBrowser() }
        addItem("截图", R.drawable.ic_screenshot) { captureCurrentPage() }
        addItem("主题", R.drawable.ic_palette) { showThemeEditor() }
        addItem("电脑模式", R.drawable.ic_desktop) { toggleDesktopMode() }
        addItem("无痕", R.drawable.ic_incognito) { toggleIncognitoMode() }
        addItem("阅读模式", R.drawable.ic_reading_mode) { toggleReadingMode() }
        addItem("开发者", R.drawable.ic_developer) { showDeveloperMenu() }
        addItem("资源嗅探", R.drawable.ic_sniffer) { showResourceSniffer() }
        addItem("视频增强", R.drawable.ic_video_enhance) { showVideoEnhancePanel() }
        addItem("下载", R.drawable.ic_download) { showDownloadManager() }
        addItem("画中画", R.drawable.ic_pip) { enterPictureInPictureModeCompat() }
        addItem("权限", R.drawable.ic_permission) { showPrivacyCenter() }
        addItem("刷新", R.drawable.ic_refresh) { currentWebView()?.reload() }

        detachFromParent(container)

        scrollView.addView(container)
        dialog.setTitle("功能")
        dialog.setView(scrollView)
        dialog.show()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 0 -> "未知大小"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun downloadStatusLabel(task: DownloadHelper.Task): String = when (task.status) {
        android.app.DownloadManager.STATUS_RUNNING -> "正在下载"
        android.app.DownloadManager.STATUS_PENDING -> "等待下载"
        android.app.DownloadManager.STATUS_PAUSED -> "已暂停"
        android.app.DownloadManager.STATUS_SUCCESSFUL -> "已完成"
        android.app.DownloadManager.STATUS_FAILED -> "下载失败（${task.reason}）"
        else -> "未知状态"
    }

    private fun showDownloadManager() {
        val allTasks = downloadHelper.tasks()
        val files = downloadHelper.listDownloadFiles()
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val activeTasks = allTasks.filter {
            it.status == android.app.DownloadManager.STATUS_RUNNING || it.status == android.app.DownloadManager.STATUS_PENDING
        }
        val otherTasks = allTasks.filterNot { t -> activeTasks.any { it.id == t.id } }

        // 正在下载：带实时进度条，每秒轮询更新，不用手动重开弹窗才能看到最新进度
        val progressRows = mutableMapOf<Long, Pair<ProgressBar, TextView>>()
        if (activeTasks.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "正在下载 · ${activeTasks.size}"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#111827"))
                setPadding(0, 0, 0, dp(10))
            })
            activeTasks.take(20).forEach { task ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#F9FAFB"))
                        cornerRadius = dp(16).toFloat()
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(task.name)
                            .setMessage("取消后可重新从网页下载。")
                            .setPositiveButton("取消下载") { _, _ ->
                                downloadHelper.cancel(task.id)
                                showDownloadManager()
                            }
                            .setNegativeButton("关闭", null)
                            .show()
                    }
                }
                row.addView(TextView(this).apply {
                    text = task.name
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#111827"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
                row.addView(space(dp(8)))
                val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = task.progress
                    isIndeterminate = task.total <= 0
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6))
                    progressTintList = ColorStateList.valueOf(Color.parseColor("#3B82F6"))
                    progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#E5E7EB"))
                }
                detachFromParent(bar)
                row.addView(bar)
                row.addView(space(dp(6)))
                val detail = TextView(this).apply {
                    text = "${downloadStatusLabel(task)} · ${task.progress}% · ${formatBytes(task.downloaded)} / ${formatBytes(task.total)}"
                    textSize = 12f
                    setTextColor(Color.parseColor("#6B7280"))
                }
                detachFromParent(detail)
                row.addView(detail)
                detachFromParent(row)
                container.addView(row)
                container.addView(space(dp(8)))
                progressRows[task.id] = bar to detail
            }
            container.addView(createButton("清除已完成任务") {
                downloadHelper.clearFinished()
                showDownloadManager()
            })
            container.addView(space(dp(18)))
        }

        if (otherTasks.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "任务记录"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#111827"))
                setPadding(0, 0, 0, dp(10))
            })
            otherTasks.take(30).forEach { task ->
                val state = downloadStatusLabel(task)
                container.addView(createActionCard(
                    R.drawable.ic_download,
                    task.name,
                    "$state · ${formatBytes(task.downloaded)} / ${formatBytes(task.total)}"
                ) {
                    when (task.status) {
                        android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                            files.firstOrNull { it.name == task.name }?.let(downloadHelper::openFile)
                                ?: toast("文件已完成，但本地文件不存在")
                        }
                        android.app.DownloadManager.STATUS_FAILED,
                        android.app.DownloadManager.STATUS_PAUSED -> {
                            MaterialAlertDialogBuilder(this)
                                .setTitle(task.name)
                                .setItems(arrayOf("重新下载", "删除任务")) { _, which ->
                                    if (which == 0) {
                                        downloadHelper.retry(task.id)
                                        showDownloadManager()
                                    } else {
                                        downloadHelper.removeTask(task.id, false)
                                        showDownloadManager()
                                    }
                                }
                                .show()
                        }
                        else -> toast(state)
                    }
                })
                container.addView(space(dp(6)))
            }
            if (activeTasks.isEmpty()) {
                container.addView(createButton("清除已完成任务") {
                    downloadHelper.clearFinished()
                    showDownloadManager()
                })
            }
            container.addView(space(dp(18)))
        }

        container.addView(TextView(this).apply {
            text = "下载文件 · ${files.size}"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#111827"))
            setPadding(0, 0, 0, dp(10))
        })

        if (files.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "还没有下载文件\n点击网页中的下载按钮即可自动进入下载中心"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 15f
                setPadding(dp(24), dp(36), dp(24), dp(36))
            })
        } else {
            files.take(50).forEach { file ->
                container.addView(createActionCard(
                    R.drawable.ic_download, file.name, formatBytes(file.length())
                ) { showDownloadFileAction(file) })
                container.addView(space(dp(6)))
            }
        }

        container.addView(space(dp(12)))
        container.addView(createButton("打开下载目录") { downloadHelper.openDownloadDir() })
        val dialog = showFullScreenDialog("下载中心", container)

        // 每秒轮询正在下载的任务，实时刷新进度条 —— 全部结束后自动重开一次弹窗，
        // 把它们从「正在下载」移到「任务记录」，不需要用户手动关闭再打开。
        if (activeTasks.isNotEmpty()) {
            val handler = Handler(Looper.getMainLooper())
            var alive = true
            dialog.setOnDismissListener { alive = false }
            lateinit var tick: () -> Unit
            tick = {
                if (alive) {
                    var anyStillActive = false
                    progressRows.forEach { (id, views) ->
                        val fresh = downloadHelper.query(id) ?: return@forEach
                        val (bar, detail) = views
                        bar.isIndeterminate = fresh.total <= 0
                        bar.progress = fresh.progress
                        detail.text = "${downloadStatusLabel(fresh)} · ${fresh.progress}% · ${formatBytes(fresh.downloaded)} / ${formatBytes(fresh.total)}"
                        if (fresh.status == android.app.DownloadManager.STATUS_RUNNING ||
                            fresh.status == android.app.DownloadManager.STATUS_PENDING) {
                            anyStillActive = true
                        }
                    }
                    if (anyStillActive) {
                        handler.postDelayed(tick, 1000)
                    } else {
                        alive = false
                        dialog.dismiss()
                        showDownloadManager()
                    }
                }
            }
            handler.postDelayed(tick, 1000)
        }
    }

    private fun showDownloadFileAction(file: File) {
        val items = arrayOf("打开", "分享", "删除")
        MaterialAlertDialogBuilder(this)
            .setTitle(file.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> downloadHelper.openFile(file)
                    1 -> downloadHelper.shareFile(file)
                    2 -> {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("删除文件")
                            .setMessage("确定要删除 ${file.name} 吗？")
                            .setPositiveButton("删除") { _, _ ->
                                downloadHelper.deleteFile(file) {
                                    showDownloadManager()
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    else -> {}
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }


    private fun copyPageText() {
        val view = currentWebView() ?: return toast("当前没有网页")
        view.evaluateJavascript("(document.body && document.body.innerText) || ''") { raw ->
            try {
                val text = org.json.JSONTokener(raw ?: "\"\"").nextValue()?.toString().orEmpty()
                if (text.isBlank()) {
                    toast("当前页面没有可复制的正文")
                    return@evaluateJavascript
                }
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("网页文本", text))
                toast("网页文本已复制")
            } catch (_: Exception) {
                toast("复制失败")
            }
        }
    }

    private fun openCurrentInExternalBrowser() {
        val url = currentWebView()?.url?.takeIf {
            it.startsWith("http://") || it.startsWith("https://")
        } ?: return toast("当前页面无法在外部打开")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("没有可用的浏览器")
        }
    }

    private fun showPrivacyCenter() {
        val view = currentWebView()
        val origin = view?.url?.let { runCatching {
            val u = URL(it); "${u.protocol}://${u.host}"
        }.getOrNull() } ?: "当前网页"
        val current = sitePermissionStore.get(origin)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        container.addView(TextView(this).apply {
            text = "当前站点\n$origin"
            textSize = 15f
            setTextColor(Color.parseColor("#374151"))
            setPadding(0, 0, 0, dp(14))
        })

        fun addSwitch(label: String, checked: Boolean, save: (Boolean) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.parseColor("#111827"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = android.widget.Switch(this).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, value -> save(value) }
            }
            row.addView(tv); row.addView(sw); container.addView(row)
        }

        addSwitch("摄像头", current.camera) { sitePermissionStore.set(origin, camera = it) }
        addSwitch("麦克风", current.microphone) { sitePermissionStore.set(origin, microphone = it) }
        addSwitch("定位", current.location) { sitePermissionStore.set(origin, location = it) }

        container.addView(space(dp(12)))
        container.addView(createButton("清除当前网页 Cookie") {
            view?.url?.let { CookieManager.getInstance().setCookie(it, "") }
            CookieManager.getInstance().flush()
            toast("已请求清理当前站点 Cookie")
        })
        container.addView(space(dp(8)))
        container.addView(createButton("清除 WebView 缓存") { clearCurrentWebData() })
        container.addView(space(dp(8)))
        container.addView(createButton("清除全部站点权限记录") {
            sitePermissionStore.clear()
            toast("站点权限记录已清除")
        })
        showFullScreenDialog("隐私与网站权限", container)
    }

    // ========== 开发者功能 ==========

    private fun showDeveloperMenu() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(createActionCard(R.drawable.ic_script, "查看网页源码", "查看当前页面 HTML") { viewSource() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_bookmark, "保存 Cookie", "复制当前页面 Cookie") { saveCookie() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_developer, "JavaScript 控制台", "F12 Console：实时日志、JS 执行、对象查看") { showConsole() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_developer, "Chrome F12 远程调试", "USB/ADB 连接电脑后使用 chrome://inspect") {
            WebView.setWebContentsDebuggingEnabled(true)
            toast("已开启远程调试：电脑 Chrome 打开 chrome://inspect")
        })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_mcp, "网络抓包", "查看网络请求") { showNetworkCapture() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_mcp, "页面信息", "标题 / 编码 / viewport / 元素数量") { showPageInfo() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_developer, "存储查看器", "localStorage / sessionStorage") { showStorageViewer() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_desktop, "User-Agent", "查看、复制、切换当前 UA") { showUserAgentInfo() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_mcp, "性能计时", "DNS / TCP / TTFB / 加载耗时") { showPerformanceTiming() })
        container.addView(space(dp(8)))
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_adblock, "清除网站数据", "清空 Cookie / 缓存 / 本地存储") { clearSiteData() })

        showFullScreenDialog("开发者工具", container)
    }

    private fun showPageInfo() {
        val webView = currentWebView() ?: return
        val js = """
            (function(){
              function meta(name){ var m=document.querySelector('meta[name="'+name+'"]'); return m?m.content:''; }
              return JSON.stringify({
                title: document.title,
                url: location.href,
                charset: document.characterSet,
                viewport: meta('viewport'),
                description: meta('description'),
                linkCount: document.links.length,
                imgCount: document.images.length,
                scriptCount: document.scripts.length
              });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            try {
                val json = org.json.JSONObject(decodeJsStringResult(result))
                val text = buildString {
                    appendLine("标题：${json.optString("title")}")
                    appendLine("地址：${json.optString("url")}")
                    appendLine("编码：${json.optString("charset")}")
                    appendLine("Viewport：${json.optString("viewport").ifBlank { "（未设置）" }}")
                    appendLine("描述：${json.optString("description").ifBlank { "（无）" }}")
                    appendLine()
                    appendLine("链接数：${json.optInt("linkCount")}")
                    appendLine("图片数：${json.optInt("imgCount")}")
                    appendLine("脚本数：${json.optInt("scriptCount")}")
                }
                val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                container.addView(TextView(this).apply {
                    this.text = text
                    textSize = 14f
                    setTextColor(Color.parseColor("#111827"))
                    setTextIsSelectable(true)
                })
                container.addView(space(dp(16)))
                container.addView(createButton("复制") { copyToClipboard(text, "页面信息"); toast("已复制") })
                showFullScreenDialog("页面信息", container)
            } catch (e: Exception) {
                toast("获取页面信息失败")
            }
        }
    }

    private fun showStorageViewer() {
        val webView = currentWebView() ?: return
        val js = """
            (function(){
              function dump(s){ var o={}; try{ for(var i=0;i<s.length;i++){ var k=s.key(i); o[k]=s.getItem(k);} }catch(e){} return o; }
              return JSON.stringify({local: dump(window.localStorage), session: dump(window.sessionStorage)});
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            try {
                val json = org.json.JSONObject(decodeJsStringResult(result))
                val local = json.optJSONObject("local") ?: org.json.JSONObject()
                val session = json.optJSONObject("session") ?: org.json.JSONObject()
                val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

                fun renderSection(title: String, obj: org.json.JSONObject) {
                    container.addView(TextView(this).apply {
                        text = "$title（${obj.length()}）"
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.parseColor("#111827"))
                        setPadding(0, dp(8), 0, dp(6))
                    })
                    if (obj.length() == 0) {
                        container.addView(TextView(this).apply {
                            text = "（空）"
                            setTextColor(Color.parseColor("#9CA3AF"))
                            textSize = 13f
                        })
                    } else {
                        obj.keys().forEach { k ->
                            container.addView(TextView(this).apply {
                                text = "$k = ${obj.optString(k)}"
                                textSize = 13f
                                setTextColor(Color.parseColor("#374151"))
                                typeface = Typeface.MONOSPACE
                                setTextIsSelectable(true)
                                setPadding(0, dp(4), 0, dp(4))
                            })
                        }
                    }
                }
                renderSection("localStorage", local)
                container.addView(space(dp(12)))
                renderSection("sessionStorage", session)
                container.addView(space(dp(16)))
                container.addView(createButton("清空 localStorage") {
                    currentWebView()?.evaluateJavascript("window.localStorage.clear();", null)
                    toast("已清空 localStorage")
                })
                container.addView(space(dp(8)))
                container.addView(createButton("清空 sessionStorage") {
                    currentWebView()?.evaluateJavascript("window.sessionStorage.clear();", null)
                    toast("已清空 sessionStorage")
                })
                showFullScreenDialog("存储查看器", container)
            } catch (e: Exception) {
                toast("读取存储失败：${e.message}")
            }
        }
    }

    private fun showUserAgentInfo() {
        val webView = currentWebView() ?: return
        val ua = webView.settings.userAgentString ?: "未知"
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(TextView(this).apply {
            text = ua
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#111827"))
            setTextIsSelectable(true)
        })
        container.addView(space(dp(16)))
        container.addView(createButton("复制") { copyToClipboard(ua, "User-Agent"); toast("已复制") })
        container.addView(space(dp(8)))
        container.addView(createButton(if (settings.desktopModeEnabled) "切换为移动版 UA" else "切换为桌面版 UA") {
            toggleDesktopMode()
            showUserAgentInfo()
        })
        showFullScreenDialog("User-Agent", container)
    }

    private fun showPerformanceTiming() {
        val webView = currentWebView() ?: return
        val js = """
            (function(){
              try {
                var t = performance.timing;
                var nav = performance.getEntriesByType('navigation')[0];
                var r = {
                  dns: t.domainLookupEnd - t.domainLookupStart,
                  tcp: t.connectEnd - t.connectStart,
                  ttfb: t.responseStart - t.requestStart,
                  download: t.responseEnd - t.responseStart,
                  domReady: t.domContentLoadedEventEnd - t.navigationStart,
                  loadTotal: t.loadEventEnd - t.navigationStart,
                  transferSize: nav ? nav.transferSize : -1
                };
                return JSON.stringify(r);
              } catch(e) { return JSON.stringify({error: String(e)}); }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            try {
                val json = org.json.JSONObject(decodeJsStringResult(result))
                if (json.has("error")) {
                    toast("该页面暂无计时数据")
                    return@evaluateJavascript
                }
                val text = buildString {
                    appendLine("DNS 解析：${json.optLong("dns")} ms")
                    appendLine("TCP 连接：${json.optLong("tcp")} ms")
                    appendLine("首字节响应 (TTFB)：${json.optLong("ttfb")} ms")
                    appendLine("内容下载：${json.optLong("download")} ms")
                    appendLine("DOM 就绪：${json.optLong("domReady")} ms")
                    appendLine("完整加载：${json.optLong("loadTotal")} ms")
                    val size = json.optLong("transferSize", -1)
                    if (size >= 0) appendLine("传输大小：${size / 1024} KB")
                }
                val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                container.addView(TextView(this).apply {
                    this.text = text
                    textSize = 14f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.parseColor("#111827"))
                    setTextIsSelectable(true)
                })
                container.addView(space(dp(16)))
                container.addView(createButton("复制") { copyToClipboard(text, "性能计时"); toast("已复制") })
                showFullScreenDialog("性能计时", container)
            } catch (e: Exception) {
                toast("获取性能数据失败")
            }
        }
    }

    private fun clearSiteData() {
        val webView = currentWebView()
        CookieManager.getInstance().removeAllCookies(null)
        webView?.clearCache(true)
        webView?.clearFormData()
        WebStorage.getInstance().deleteAllData()
        toast("已清除 Cookie / 缓存 / 本地存储")
        webView?.reload()
    }

    // ========== 资源嗅探 ==========

    private fun showResourceSniffer() {
        val webView = currentWebView() ?: return
        val js = """
            (function(){
              function abs(u){ try { return new URL(u, location.href).href; } catch(e) { return u || ''; } }
              var links=[], imgs=[], vids=[], audios=[];
              document.querySelectorAll('a[href]').forEach(function(a){ links.push(abs(a.getAttribute('href'))); });
              document.querySelectorAll('img').forEach(function(i){
                ['src','data-src','data-original','data-lazy-src','data-url','data-fallback-src'].forEach(function(k){ var v=i.getAttribute(k); if(v) imgs.push(abs(v)); });
                var ss=i.getAttribute('srcset'); if(ss) ss.split(',').forEach(function(x){ var u=x.trim().split(/\s+/)[0]; if(u) imgs.push(abs(u)); });
              });
              document.querySelectorAll('[style*="background-image"]').forEach(function(i){ var m=(i.getAttribute('style')||'').match(/url\([\"']?([^\"')]+)[\"']?\)/i); if(m) imgs.push(abs(m[1])); });
              document.querySelectorAll('video').forEach(function(v){
                var u=v.currentSrc||v.src; if(u) vids.push(abs(u));
                var p=v.getAttribute('poster'); if(p) imgs.push(abs(p));
                v.querySelectorAll('source[src]').forEach(function(x){ vids.push(abs(x.getAttribute('src'))); });
              });
              document.querySelectorAll('audio').forEach(function(a){
                var u=a.currentSrc||a.src; if(u) audios.push(abs(u));
                a.querySelectorAll('source[src]').forEach(function(x){ audios.push(abs(x.getAttribute('src'))); });
              });
              document.querySelectorAll('source[src]').forEach(function(x){
                var u=abs(x.getAttribute('src')), t=(x.getAttribute('type')||'').toLowerCase();
                if(/audio|mpegurl|aac|ogg|wav|flac/.test(t)||/\.(mp3|m4a|aac|ogg|oga|wav|flac|opus)(\?|$)/i.test(u)) audios.push(u);
                if(/video|mp4|webm|quicktime|mpegurl|dash/.test(t)||/\.(mp4|m4v|webm|mov|m3u8|mpd|ts|m4s)(\?|$)/i.test(u)) vids.push(u);
              });
              try { performance.getEntriesByType('resource').forEach(function(e){
                var u=e.name||'', t=(e.initiatorType||'').toLowerCase();
                if(/\.(mp4|m4v|webm|mov|m3u8|mpd|ts|m4s)(\?|$)/i.test(u)||/video|media|mpegurl|dash/.test(t)) vids.push(abs(u));
                if(/\.(mp3|m4a|aac|ogg|oga|wav|flac|opus)(\?|$)/i.test(u)||/audio/.test(t)||/audio|mpeg|aac|ogg|wav|flac/i.test(e.name||'')) audios.push(abs(u));
              }); } catch(e) {}
              function uniq(a){ var o={}; return a.filter(function(x){ return x && !o[x] && (o[x]=1); }); }
              return JSON.stringify({links:uniq(links),images:uniq(imgs),videos:uniq(vids),audios:uniq(audios)});
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            try {
                val json = org.json.JSONObject(decodeJsStringResult(result))
                showSnifferResults(
                    jsonArrayToList(json.optJSONArray("links")),
                    jsonArrayToList(json.optJSONArray("images")),
                    jsonArrayToList(json.optJSONArray("videos")),
                    jsonArrayToList(json.optJSONArray("audios"))
                )
            } catch (e: Exception) {
                toast("嗅探失败：${e.message}")
            }
        }
    }

    private fun jsonArrayToList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun showSnifferResults(links: List<String>, images: List<String>, videos: List<String>, audios: List<String>) {
        var currentVideos = videos.distinct()
        var currentAudios = audios.distinct()
        var currentImages = images.distinct()
        var currentLinks = links.distinct()
        val tabKeys = listOf("videos", "audios", "images", "links")
        val tabNames = listOf("视频", "音频", "图片", "链接")
        val tabViews = mutableListOf<TextView>()
        var mode = when {
            currentVideos.isNotEmpty() -> "videos"
            currentAudios.isNotEmpty() -> "audios"
            currentImages.isNotEmpty() -> "images"
            else -> "links"
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabRow = HorizontalScrollView(this)
        val tabInner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun loadThumbnail(url: String, imageView: ImageView) {
            val pageView = currentWebView()
            val pageUrl = pageView?.url.orEmpty()
            val userAgent = pageView?.settings?.userAgentString ?: mobileUserAgent()
            val cookie = if (pageUrl.startsWith("http")) CookieManager.getInstance().getCookie(pageUrl) else null
            imageView.tag = url
            lifecycleScope.launch(Dispatchers.IO) {
                val bitmap = try {
                    if (url.startsWith("data:image/")) {
                        val encoded = url.substringAfter("base64,", "")
                        android.util.Base64.decode(encoded, android.util.Base64.DEFAULT).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    } else {
                        val conn = (URL(url).openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 10000; readTimeout = 15000; instanceFollowRedirects = true
                            setRequestProperty("User-Agent", userAgent)
                            setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                            if (pageUrl.startsWith("http")) setRequestProperty("Referer", pageUrl)
                            cookie?.let { setRequestProperty("Cookie", it) }
                        }
                        try {
                            if (conn.responseCode !in 200..299) null else conn.inputStream.use { stream ->
                                val opts = BitmapFactory.Options().apply { inSampleSize = 2; inPreferredConfig = Bitmap.Config.RGB_565 }
                                BitmapFactory.decodeStream(stream, null, opts)
                            }
                        } finally { conn.disconnect() }
                    }
                } catch (_: Exception) { null }
                withContext(Dispatchers.Main) { if (bitmap != null && imageView.tag == url) imageView.setImageBitmap(bitmap) }
            }
        }

        fun currentItems(): List<String> = when (mode) {
            "videos" -> currentVideos
            "audios" -> currentAudios
            "images" -> currentImages
            else -> currentLinks
        }

        fun renderList(items: List<String>, isImages: Boolean) {
            listContainer.removeAllViews()
            if (items.isEmpty()) {
                listContainer.addView(TextView(this).apply { text = "未找到资源"; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#9CA3AF")); setPadding(0, dp(32), 0, dp(32)) })
                return
            }
            items.forEach { url ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(9), dp(8), dp(9))
                    background = GradientDrawable().apply { setColor(Color.parseColor("#F9FAFB")); cornerRadius = dp(12).toFloat() }
                }
                if (isImages) {
                    row.addView(ImageView(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = GradientDrawable().apply { setColor(Color.parseColor("#E5E7EB")); cornerRadius = dp(8).toFloat() }
                        clipToOutline = true
                        setOnClickListener { addNewTab(url) }
                        loadThumbnail(url, this)
                    })
                }
                row.addView(TextView(this).apply {
                    text = url; textSize = 12f; setTextColor(Color.parseColor("#374151")); maxLines = 2
                    ellipsize = TextUtils.TruncateAt.MIDDLE; setTextIsSelectable(true)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this).apply {
                    text = "复制"; textSize = 12f; setTextColor(Color.parseColor("#2563EB")); setPadding(dp(8), dp(6), dp(6), dp(6))
                    setOnClickListener { copyToClipboard(url, "资源链接"); toast("已复制") }
                })
                if (mode == "videos") {
                    row.addView(TextView(this).apply {
                        text = "播放"; textSize = 12f; setTextColor(Color.parseColor("#059669")); setPadding(dp(6), dp(6), dp(6), dp(6))
                        setOnClickListener { startActivity(Intent(this@MainActivity, VideoPlayerActivity::class.java).apply { putExtra(VideoPlayerActivity.EXTRA_URL, url); putExtra(VideoPlayerActivity.EXTRA_TITLE, currentWebView()?.title ?: "Nebula 视频"); putExtra(VideoPlayerActivity.EXTRA_REFERER, currentWebView()?.url); putExtra(VideoPlayerActivity.EXTRA_COOKIE, runCatching { CookieManager.getInstance().getCookie(url) ?: "" }.getOrDefault("")) }) }
                    })
                }
                row.addView(TextView(this).apply {
                    text = "打开"; textSize = 12f; setTextColor(Color.parseColor("#2563EB")); setPadding(dp(6), dp(6), 0, dp(6))
                    setOnClickListener { addNewTab(url) }
                })
                listContainer.addView(row); listContainer.addView(space(dp(6)))
            }
        }

        fun repaintTabs() {
            tabViews.forEachIndexed { i, tv ->
                val active = tabKeys[i] == mode
                tv.setTextColor(Color.parseColor(if (active) "#2563EB" else "#6B7280"))
                tv.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        tabKeys.forEachIndexed { index, key ->
            val tv = TextView(this).apply {
                text = "${tabNames[index]} (${when(index){0->currentVideos.size;1->currentAudios.size;2->currentImages.size;else->currentLinks.size}})"
                textSize = 13f; setPadding(dp(8), dp(8), dp(14), dp(8)); setOnClickListener { mode = key; renderList(currentItems(), key == "images"); repaintTabs() }
            }
            tabViews += tv; tabInner.addView(tv)
        }
        tabRow.addView(tabInner)
        container.addView(tabRow)
        container.addView(space(dp(6)))
        container.addView(listContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        container.addView(createButton("复制当前分类全部链接") {
            val items = currentItems()
            if (items.isEmpty()) toast("当前分类没有内容") else { copyToClipboard(items.joinToString("\n"), "资源链接"); toast("已复制 ${items.size} 条") }
        })
        container.addView(space(dp(6)))
        val toolRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolRow.addView(createButton("源码查看/编辑") { showPageSourceEditor() }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        toolRow.addView(createButton("SEO") { showSeoInspector() }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        container.addView(toolRow)
        repaintTabs(); renderList(currentItems(), mode == "images")

        fun updateLive(newLinks: List<String>, newImages: List<String>, newVideos: List<String>, newAudios: List<String>) {
            currentLinks = newLinks.distinct(); currentImages = newImages.distinct(); currentVideos = newVideos.distinct(); currentAudios = newAudios.distinct()
            tabViews.forEachIndexed { i, tv -> tv.text = "${tabNames[i]} (${when(i){0->currentVideos.size;1->currentAudios.size;2->currentImages.size;else->currentLinks.size}})" }
            renderList(currentItems(), mode == "images")
        }
        resourceSnifferUpdater = ::updateLive
        showFullScreenDialog("资源嗅探 · 视频/音频/图片/链接", container).setOnDismissListener { resourceSnifferUpdater = null }
    }

    private fun showPageSourceEditor() {
        val view = currentWebView() ?: return toast("当前没有网页")
        val pageUrl = view.url.orEmpty()
        view.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''") { raw ->
            val html = decodeJsStringResult(raw)
            val box = EditText(this).apply {
                setText(html); setSingleLine(false); minLines = 24; textSize = 11f; typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor("#111827")); setBackgroundColor(Color.parseColor("#F8FAFC")); gravity = Gravity.TOP; setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            container.addView(TextView(this).apply { text = "编辑的是当前页面 DOM 源码；应用后会在当前 WebView 中重新加载该 HTML。"; textSize = 12f; setTextColor(Color.parseColor("#6B7280")); setPadding(0,0,0,dp(8)) })
            container.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(createButton("刷新源码") { view.evaluateJavascript("document.documentElement ? document.documentElement.outerHTML : ''") { r -> box.setText(decodeJsStringResult(r)) } }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            row.addView(createButton("复制") { copyToClipboard(box.text.toString(), "HTML 源码"); toast("源码已复制") }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            row.addView(createButton("应用") { val edited = box.text.toString(); view.loadDataWithBaseURL(pageUrl.ifBlank { null }, edited, "text/html", "utf-8", pageUrl.ifBlank { null }); toast("已应用编辑后的源码") }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
            container.addView(row)
            showFullScreenDialog("网页源码查看 / 编辑", container)
        }
    }

    private fun showSeoInspector() {
        val view = currentWebView() ?: return toast("当前没有网页")
        val js = """
            (function(){
              function meta(n){var e=document.querySelector('meta[name="'+n+'" i]');return e?e.content||'':''}
              function prop(n){var e=document.querySelector('meta[property="'+n+'"]');return e?e.content||'':''}
              var text=(document.body&&document.body.innerText)||'';
              var imgs=[].slice.call(document.images), links=[].slice.call(document.querySelectorAll('a[href]'));
              var data={url:location.href,title:document.title||'',description:meta('description'),keywords:meta('keywords'),robots:meta('robots'),lang:document.documentElement.lang||'',canonical:(document.querySelector('link[rel="canonical"]')||{}).href||'',h1:document.querySelectorAll('h1').length,h2:document.querySelectorAll('h2').length,images:imgs.length,imagesMissingAlt:imgs.filter(function(i){return !String(i.alt||'').trim()}).length,links:links.length,internal:links.filter(function(a){try{return new URL(a.href).host===location.host}catch(e){return false}}).length,external:links.filter(function(a){try{return new URL(a.href).host!==location.host}catch(e){return false}}).length,visibleChars:text.replace(/\s/g,'').length,og:{title:prop('og:title'),description:prop('og:description'),image:prop('og:image'),type:prop('og:type'),url:prop('og:url')},twitter:{card:meta('twitter:card'),title:meta('twitter:title'),description:meta('twitter:description'),image:meta('twitter:image')},structuredData:[].slice.call(document.querySelectorAll('script[type="application/ld+json"]')).map(function(s){try{var o=JSON.parse(s.textContent||'{}');return o['@type']||''}catch(e){return ''}}).filter(Boolean)};return JSON.stringify(data);
            })();
        """.trimIndent()
        view.evaluateJavascript(js) { raw ->
            try {
                val o = org.json.JSONObject(decodeJsStringResult(raw))
                val lines = buildString {
                    appendLine("URL：${o.optString("url")}")
                    appendLine("Title：${o.optString("title")}")
                    appendLine("Description：${o.optString("description").ifBlank { "（无）" }}")
                    appendLine("Keywords：${o.optString("keywords").ifBlank { "（无）" }}")
                    appendLine("Canonical：${o.optString("canonical").ifBlank { "（无）" }}")
                    appendLine("Robots：${o.optString("robots").ifBlank { "（无）" }}")
                    appendLine("语言：${o.optString("lang").ifBlank { "（未声明）" }}")
                    appendLine("H1：${o.optInt("h1")}    H2：${o.optInt("h2")}")
                    appendLine("图片：${o.optInt("images")}    缺少 ALT：${o.optInt("imagesMissingAlt")}")
                    appendLine("链接：${o.optInt("links")}    内链：${o.optInt("internal")}    外链：${o.optInt("external")}")
                    appendLine("可见文字：${o.optInt("visibleChars")} 字符")
                    val og=o.optJSONObject("og"); appendLine("OG：${og?.optString("title").orEmpty()} / ${og?.optString("type").orEmpty()} / ${og?.optString("image").orEmpty()}")
                    val tw=o.optJSONObject("twitter"); appendLine("Twitter Card：${tw?.optString("card").orEmpty()}")
                    val sd=o.optJSONArray("structuredData"); appendLine("JSON-LD：${if(sd!=null && sd.length()>0) (0 until sd.length()).map{sd.optString(it)}.distinct().joinToString(", ") else "无"}")
                }
                val box=EditText(this).apply{setText(lines);setSingleLine(false);isFocusable=false;setTextIsSelectable(true);textSize=13f;typeface=Typeface.MONOSPACE;setTextColor(Color.parseColor("#111827"));setBackgroundColor(Color.parseColor("#F8FAFC"));gravity=Gravity.TOP;setPadding(dp(12),dp(12),dp(12),dp(12))}
                val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(box)}
                c.addView(createButton("复制 SEO 报告"){copyToClipboard(lines,"SEO");toast("已复制")})
                showFullScreenDialog("SEO 页面分析",c)
            } catch(e:Exception){ toast("SEO 分析失败：${e.message}") }
        }
    }



    private fun viewSource() {
        val webView = currentWebView() ?: return
        webView.evaluateJavascript("(function(){return document.documentElement.outerHTML;})();") { result ->
            val html = result?.trim()?.removeSurrounding("\"") ?: ""
            if (html.isBlank()) {
                toast("无法获取源码")
                return@evaluateJavascript
            }
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val textView = TextView(this).apply {
                text = html
                textSize = 12f
                setTextColor(Color.parseColor("#111827"))
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            detachFromParent(textView)
            container.addView(textView)
            container.addView(space(dp(16)))
            container.addView(createButton("复制源码") {
                copyToClipboard(html, "网页源码")
                toast("源码已复制")
            })
            showFullScreenDialog("网页源码 (${html.length} 字符)", container)
        }
    }

    private fun saveCookie() {
        val webView = currentWebView() ?: return
        val url = webView.url ?: ""
        val cookieManager = CookieManager.getInstance()
        val cookie = cookieManager.getCookie(url) ?: ""
        if (cookie.isBlank()) {
            toast("当前页面无 Cookie")
            return
        }
        copyToClipboard(cookie, "Cookie")
        toast("Cookie 已复制到剪贴板")
    }

    private fun showConsole() {
        val webView = currentWebView() ?: run {
            toast("当前没有可调试的页面")
            return
        }

        installDevConsoleHook(webView)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(10))
            setBackgroundColor(Color.parseColor("#0B1020"))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(this).apply {
            text = "● Console  ·  ${webView.url ?: "about:blank"}"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        detachFromParent(status)
        toolbar.addView(status)

        val clearButton = Button(this).apply {
            text = "清空"
            setTextColor(Color.parseColor("#CBD5E1"))
            setBackgroundColor(Color.TRANSPARENT)
        }
        val copyButton = Button(this).apply {
            text = "复制"
            setTextColor(Color.parseColor("#CBD5E1"))
            setBackgroundColor(Color.TRANSPARENT)
        }
        val hookButton = Button(this).apply {
            text = "重连"
            setTextColor(Color.parseColor("#93C5FD"))
            setBackgroundColor(Color.TRANSPARENT)
        }
        detachFromParent(clearButton)
        toolbar.addView(clearButton)
        detachFromParent(copyButton)
        toolbar.addView(copyButton)
        detachFromParent(hookButton)
        toolbar.addView(hookButton)
        detachFromParent(toolbar)
        container.addView(toolbar)

        val outputScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#0F172A"))
            isFillViewport = true
        }
        val outputText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#E2E8F0"))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            text = buildDevConsoleSpannable()
        }
        detachFromParent(outputText)
        outputScroll.addView(outputText)
        devConsoleOutput = outputText
        devConsoleScroll = outputScroll
        detachFromParent(outputScroll)
        container.addView(outputScroll)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val prompt = TextView(this).apply {
            text = ">"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#60A5FA"))
            setPadding(dp(6), 0, dp(6), 0)
        }
        val input = EditText(this).apply {
            hint = "输入 JavaScript，例如 document.title"
            textSize = 13f
            minLines = 1
            maxLines = 4
            gravity = Gravity.TOP
            setSingleLine(false)
            setTextColor(Color.parseColor("#F8FAFC"))
            setHintTextColor(Color.parseColor("#64748B"))
            setBackgroundColor(Color.parseColor("#111827"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val runButton = Button(this).apply {
            text = "执行"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2563EB"))
            setOnClickListener {
                val code = input.text.toString().trim()
                if (code.isEmpty()) {
                    toast("请输入 JavaScript")
                    return@setOnClickListener
                }
                appendDevConsole("input", code)
                webView.evaluateJavascript("(function(){try{return ${code}}catch(e){throw e}})();") { result ->
                    val value = decodeJsStringResult(result ?: "null")
                    appendDevConsole("result", value.ifBlank { "undefined" })
                }
                input.setText("")
            }
        }
        detachFromParent(prompt)
        inputRow.addView(prompt)
        detachFromParent(input)
        inputRow.addView(input)
        detachFromParent(runButton)
        inputRow.addView(runButton)
        detachFromParent(inputRow)
        container.addView(inputRow)

        clearButton.setOnClickListener {
            devConsoleLogs.clear()
            outputText.text = ""
            outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
            webView.evaluateJavascript("console.clear();", null)
        }
        copyButton.setOnClickListener {
            copyToClipboard(outputText.text.toString(), "Console")
            toast("控制台日志已复制")
        }
        hookButton.setOnClickListener {
            installDevConsoleHook(webView)
            toast("Console Hook 已重新注入")
        }

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                runButton.performClick()
                true
            } else false
        }

        showFullScreenDialog("开发者工具 · Console", container)
        outputScroll.post { outputScroll.fullScroll(View.FOCUS_DOWN) }
        input.requestFocus()
    }

    private fun buildDevConsoleText(): String = buildString {
        if (devConsoleLogs.isEmpty()) {
            append("Nebula DevTools Console\n")
            append("实时接收 console.log / warn / error / info / debug\n")
            append("输入 JavaScript 后点击“执行”\n")
        } else {
            devConsoleLogs.forEach { log ->
                append("[${log.time}] ${log.level.uppercase()}  ${log.message}\n")
            }
        }
    }

    private fun devConsoleLevelColor(level: String): Int = when (level.lowercase()) {
        "error" -> Color.parseColor("#F87171")
        "warn" -> Color.parseColor("#FBBF24")
        "info" -> Color.parseColor("#60A5FA")
        "input" -> Color.parseColor("#34D399")
        "result" -> Color.parseColor("#C4B5FD")
        "clear" -> Color.parseColor("#64748B")
        else -> Color.parseColor("#E2E8F0")
    }

    /** Same content as buildDevConsoleText() but colored per log level, like real DevTools. */
    private fun buildDevConsoleSpannable(): CharSequence {
        if (devConsoleLogs.isEmpty()) return buildDevConsoleText()
        val sb = SpannableStringBuilder()
        devConsoleLogs.forEach { log ->
            val start = sb.length
            sb.append("[${log.time}] ${log.level.uppercase()}  ${log.message}\n")
            sb.setSpan(
                ForegroundColorSpan(devConsoleLevelColor(log.level)),
                start, sb.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return sb
    }

    private fun appendDevConsole(level: String, message: String) {
        val clean = message.ifBlank { "undefined" }
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        devToolsSession.add(level, clean)
        synchronized(devConsoleLogs) {
            devConsoleLogs.add(DevConsoleLog(time, level, clean))
            if (devConsoleLogs.size > 500) devConsoleLogs.removeAt(0)
        }
        runOnUiThread {
            devConsoleOutput?.text = buildDevConsoleSpannable()
            devConsoleScroll?.post { devConsoleScroll?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun devConsoleHookScript(): String = """
            (function(){
              if (window.__nebulaDevConsoleInstalled) return true;
              window.__nebulaDevConsoleInstalled = true;
              function stringify(value){
                try {
                  if (value === undefined) return 'undefined';
                  if (value === null) return 'null';
                  if (typeof value === 'string') return value;
                  if (typeof value === 'function') return String(value);
                  if (typeof value === 'object') {
                    try { return JSON.stringify(value, function(k,v){
                      if (typeof v === 'bigint') return String(v) + 'n';
                      if (v instanceof Error) return {name:v.name,message:v.message,stack:v.stack};
                      return v;
                    }); } catch(e) { return String(value); }
                  }
                  return String(value);
                } catch(e) { return String(value); }
              }
              function send(level, args){
                try {
                  var text = Array.prototype.map.call(args, stringify).join(' ');
                  if (window.NebulaDevConsole && NebulaDevConsole.post) NebulaDevConsole.post(level, text);
                } catch(e) {}
              }
              ['log','info','warn','error','debug','dir','table'].forEach(function(level){
                var old = console[level];
                if (typeof old !== 'function') old = console.log;
                console[level] = function(){
                  try { old.apply(console, arguments); } catch(e) {}
                  send(level, arguments);
                };
              });
              var oldClear = console.clear;
              console.clear = function(){
                try { oldClear.apply(console, arguments); } catch(e) {}
                send('clear', ['Console was cleared']);
              };
              window.addEventListener('error', function(e){
                send('error', [e.message + ' @ ' + e.filename + ':' + e.lineno]);
              });
              window.addEventListener('unhandledrejection', function(e){
                send('error', ['UnhandledPromiseRejection', e.reason]);
              });
              return true;
            })();
        """.trimIndent()

    private fun installDevConsoleHook(webView: WebView) {
        webView.evaluateJavascript(devConsoleHookScript(), null)
    }

    /**
     * Registers the console hook via WebViewCompat's true document-start
     * injection (same mechanism as the DeepSeek++ bridge) so console.log
     * calls that fire *before* onPageFinished — which is most of them on a
     * typical page — are captured too. The evaluateJavascript-based
     * installDevConsoleHook() calls elsewhere stay as a fallback/re-arm path
     * for WebView builds that don't support this API; both are idempotent
     * (window.__nebulaDevConsoleInstalled guards re-entry) so having both is safe.
     */
    private fun installDevConsoleHookAtDocumentStart(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, devConsoleHookScript(), setOf("*"))
        } catch (_: Exception) {
            // Falls back to the per-page-load evaluateJavascript hook already wired elsewhere.
        }
    }

    private fun showNetworkCapture() {
        networkCaptureEnabled = true

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val logScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.parseColor("#F8FAFC"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val logText = TextView(this).apply {
            text = if (networkLogs.isEmpty()) "暂无抓包记录\n点击“刷新”查看最新请求" else buildNetworkLogText()
            textSize = 12f
            setTextColor(Color.parseColor("#1E293B"))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        detachFromParent(logText)
        logScroll.addView(logText)

        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        val toggleButton = Button(this).apply {
            text = if (networkCaptureEnabled) "停止抓包" else "开始抓包"
            setTextColor(Color.WHITE)
            setBackgroundColor(if (networkCaptureEnabled) Color.parseColor("#EF4444") else Color.parseColor("#3B82F6"))
            setOnClickListener {
                networkCaptureEnabled = !networkCaptureEnabled
                text = if (networkCaptureEnabled) "停止抓包" else "开始抓包"
                setBackgroundColor(if (networkCaptureEnabled) Color.parseColor("#EF4444") else Color.parseColor("#3B82F6"))
                toast(if (networkCaptureEnabled) "抓包已开启" else "抓包已停止")
            }
        }
        detachFromParent(toggleButton)
        btnContainer.addView(toggleButton)
        btnContainer.addView(space(dp(8)))

        val refreshButton = Button(this).apply {
            text = "刷新"
            setTextColor(Color.parseColor("#3B82F6"))
            setBackgroundColor(Color.parseColor("#EFF6FF"))
            setOnClickListener {
                logText.text = if (networkLogs.isEmpty()) "暂无抓包记录\n点击“刷新”查看最新请求" else buildNetworkLogText()
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        detachFromParent(refreshButton)
        btnContainer.addView(refreshButton)
        btnContainer.addView(space(dp(8)))

        val clearButton = Button(this).apply {
            text = "清空"
            setTextColor(Color.parseColor("#EF4444"))
            setBackgroundColor(Color.parseColor("#FEE2E2"))
            setOnClickListener {
                synchronized(networkLogs) { networkLogs.clear() }
                logText.text = "暂无抓包记录\n点击“刷新”查看最新请求"
            }
        }
        detachFromParent(clearButton)
        btnContainer.addView(clearButton)
        btnContainer.addView(space(dp(8)))

        val copyButton = Button(this).apply {
            text = "复制"
            setTextColor(Color.parseColor("#6B7280"))
            setBackgroundColor(Color.parseColor("#F3F4F6"))
            setOnClickListener {
                val text = if (networkLogs.isEmpty()) "暂无抓包记录" else buildNetworkLogText()
                copyToClipboard(text, "抓包记录")
                toast("已复制抓包记录")
            }
        }
        detachFromParent(copyButton)
        btnContainer.addView(copyButton)

        detachFromParent(logScroll)

        container.addView(logScroll)
        detachFromParent(btnContainer)
        container.addView(btnContainer)

        showFullScreenDialog("网络抓包 · ${networkLogs.size} 条", container)
    }

    private fun buildNetworkLogText(): String {
        val sb = StringBuilder()
        synchronized(networkLogs) {
            networkLogs.takeLast(200).forEachIndexed { index, log ->
                sb.append("[").append(index + 1).append("] ")
                  .append(log.time).append(" ")
                  .append(log.method).append(" ")
                  .append(log.url).append("\n")
            }
        }
        if (sb.isEmpty()) return "暂无抓包记录"
        return sb.toString()
    }

    private fun addNetworkLog(method: String, url: String) {
        if (!networkCaptureEnabled) return
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        synchronized(networkLogs) {
            networkLogs.add(NetworkLogEntry(time, method, url))
            if (networkLogs.size > 500) {
                networkLogs.removeAt(0)
            }
        }
    }

    private fun copyToClipboard(text: String, label: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /**
     * evaluateJavascript's callback returns a JSON-encoded string when the JS
     * expression evaluates to a string (e.g. JSON.stringify(...) results come
     * back as `"{\"key\":\"value\"}"`, quotes and escapes included). Decoding
     * through JSONTokener handles all the escaping correctly instead of the
     * naive `.removeSurrounding("\"")` that breaks on any escaped character.
     */
    private fun decodeJsStringResult(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return try {
            org.json.JSONTokener(raw).nextValue() as? String ?: raw
        } catch (_: Exception) {
            raw
        }
    }

    // ========== 阅读模式 ==========

    private data class ReadingChapter(
        val title: String,
        val text: String = "",
        val url: String = ""
    )

    private val tabReadingContent = mutableMapOf<String, Pair<String, String>>()
    private val tabReadingChapters = mutableMapOf<String, List<ReadingChapter>>()
    private val tabReadingCurrentIndex = mutableMapOf<String, Int>()
    private val tabReadingPrevUrl = mutableMapOf<String, String>()
    private val tabReadingNextUrl = mutableMapOf<String, String>()
    private val tabReadingPendingUrl = mutableMapOf<String, Boolean>()
    private val tabReadingAutoPage = mutableMapOf<String, Boolean>()
    private val tabReadingAutoNext = mutableMapOf<String, Boolean>()

    private fun toggleReadingMode() {
        val webView = currentWebView() ?: return
        val tabId = currentTabId ?: return

        if (tabReadingMode[tabId] == true) {
            val original = tabOriginalUrls[tabId]
            tabReadingMode[tabId] = false
            tabReadingPendingUrl.remove(tabId)
            tabReadingAutoPage.remove(tabId)
            tabReadingAutoNext.remove(tabId)
            tabReadingCurrentIndex.remove(tabId)
            if (!original.isNullOrBlank()) {
                webView.loadUrl(original)
                tabOriginalUrls.remove(tabId)
                tabReadingContent.remove(tabId)
                tabReadingChapters.remove(tabId)
                tabReadingPrevUrl.remove(tabId)
                tabReadingNextUrl.remove(tabId)
                toast("已退出阅读模式")
            } else {
                toast("无原页面可返回")
            }
            return
        }

        val currentUrl = webView.url ?: ""
        if (currentUrl.isBlank() || currentUrl.startsWith("data:text/html")) {
            toast("当前页面无法进入阅读模式")
            return
        }
        tabOriginalUrls[tabId] = currentUrl
        tabReadingMode[tabId] = true
        extractReadingMode(webView, tabId, currentUrl)
    }

    /**
     * Extract without modifying the live page.  This is important: the old version
     * removed ad/popup selectors directly from document before cloning the content,
     * which could delete real novel text and also interfere with site JS.
     */
    private fun extractReadingMode(webView: WebView, tabId: String, pageUrl: String) {
        val selectors = novelRuleStore.enabledSelectors()
        val selectorJson = org.json.JSONArray(selectors).toString()
        val pendingIndex = tabReadingCurrentIndex[tabId] ?: 0
        val wasPendingChapter = tabReadingPendingUrl[tabId] == true

        // The reader engine lives in assets so the extraction logic can evolve
        // independently from the Activity. It uses content-density scoring,
        // semantic article candidates and multi-pattern chapter discovery rather
        // than depending on one site's CSS class names.
        val engine = runCatching {
            assets.open("nebula_reader_engine.js").bufferedReader().use { it.readText() }
        }.getOrElse {
            toast("阅读引擎加载失败：${it.message ?: "未知错误"}")
            return
        }
        val extractionJs = """
            (function(){
              try {
                $engine
                return window.NebulaReaderEngine.extract($selectorJson);
              } catch(e) {
                return JSON.stringify({title:document.title||'阅读模式',text:'',paragraphs:[],chapters:[],prev:'',next:'',error:String(e)});
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(extractionJs) { result ->
            try {
                val json = org.json.JSONObject(decodeJsStringResult(result))
                val raw = json.optString("text").trim()
                val paragraphs = json.optJSONArray("paragraphs")
                val chapters = mutableListOf<ReadingChapter>()
                json.optJSONArray("chapters")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val title = o.optString("title").trim()
                        val url = o.optString("url").trim()
                        if (title.isNotBlank() && url.isNotBlank()) {
                            chapters += ReadingChapter(title, "", url)
                        }
                    }
                }

                val title = json.optString("title").trim().ifBlank { webView.title ?: "阅读模式" }
                var extractedText = if (raw.isNotBlank()) raw else buildString {
                    if (paragraphs != null) {
                        for (i in 0 until paragraphs.length()) {
                            val p = paragraphs.optString(i).trim()
                            if (p.isNotBlank()) {
                                if (isNotEmpty()) append("\n\n")
                                append(p)
                            }
                        }
                    }
                }.trim()

                // A chapter list is navigation metadata, not the current chapter's
                // body. Always create a real current chapter with the extracted body
                // at index 0 when the page itself contains readable text. This fixes
                // the old blank-reader problem where every discovered TOC item had
                // text="" and the first visible chapter was therefore empty.
                val current = ReadingChapter(title, extractedText, pageUrl)
                val oldChapters = tabReadingChapters[tabId].orEmpty()

                val finalChapters: MutableList<ReadingChapter> = when {
                    wasPendingChapter && oldChapters.size >= 2 -> {
                        val idx = pendingIndex.coerceIn(0, oldChapters.lastIndex)
                        oldChapters.toMutableList().also { list ->
                            val old = list[idx]
                            list[idx] = old.copy(
                                title = title.ifBlank { old.title },
                                text = extractedText,
                                url = pageUrl
                            )
                        }
                    }
                    chapters.isNotEmpty() -> {
                        // Keep the complete TOC while attaching the currently loaded
                        // chapter's text to the matching URL whenever possible.
                        val list = chapters.toMutableList()
                        val match = list.indexOfFirst { sameChapterUrl(it.url, pageUrl) }
                        if (match >= 0) {
                            list[match] = list[match].copy(title = title.ifBlank { list[match].title }, text = extractedText, url = pageUrl)
                            tabReadingCurrentIndex[tabId] = match
                        } else {
                            // The current page can be outside the discovered TOC
                            // (common with paginated/JS-generated chapter lists).
                            // Put it first, then retain the complete discovered TOC.
                            list.add(0, current)
                            tabReadingCurrentIndex[tabId] = 0
                        }
                        list
                    }
                    else -> mutableListOf(current)
                }

                val safeIndex = (tabReadingCurrentIndex[tabId] ?: pendingIndex).coerceIn(0, finalChapters.lastIndex.coerceAtLeast(0))
                tabReadingContent[tabId] = title to extractedText
                tabReadingChapters[tabId] = finalChapters
                tabReadingCurrentIndex[tabId] = safeIndex
                tabReadingPrevUrl[tabId] = json.optString("prev")
                tabReadingNextUrl[tabId] = json.optString("next")
                tabReadingPendingUrl.remove(tabId)

                webView.addJavascriptInterface(ReadingModeBridge(webView), "NebulaReadingMode")
                webView.loadDataWithBaseURL(
                    pageUrl,
                    buildReadingHtml(title, finalChapters, safeIndex, tabReadingAutoPage[tabId] == true),
                    "text/html",
                    "utf-8",
                    null
                )
                // Reader pages are generated as data: documents, so the normal
                // onPageFinished auto-translation guard intentionally does not run.
                // Trigger it here after the generated DOM has been attached.
                if (settings.autoTranslateEnabled) {
                    webView.postDelayed({
                        if (webView.isAttachedToWindow && tabReadingMode[tabId] == true) {
                            maybeAutoTranslate(webView, force = true)
                        }
                    }, 180L)
                }
                val bodyMsg = if (extractedText.isBlank()) "正文未识别，可返回原网页重试" else "正文 ${extractedText.length} 字"
                toast("已发现 ${finalChapters.size} 个章节 · $bodyMsg")
            } catch (e: Exception) {
                Log.w("NebulaReader", "extract failed", e)
                // Do not leave the user in a broken half-reader state.
                tabReadingMode[tabId] = false
                tabOriginalUrls.remove(tabId)
                toast("阅读模式提取失败：${e.message ?: "未知错误"}")
            }
        }
    }

    private fun sameChapterUrl(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return runCatching {
            val ua = android.net.Uri.parse(a)
            val ub = android.net.Uri.parse(b)
            ua.scheme.equals(ub.scheme, true) &&
                ua.host.equals(ub.host, true) &&
                ua.path.orEmpty().trimEnd('/') == ub.path.orEmpty().trimEnd('/') &&
                (ua.query.orEmpty() == ub.query.orEmpty())
        }.getOrDefault(a == b)
    }

    private fun showReadingSettings() {
        val tabId = currentTabId
        if (tabId == null || tabReadingMode[tabId] != true) {
            toast("请先进入阅读模式")
            return
        }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun applyLive() {
            val id = currentTabId ?: return
            if (tabReadingMode[id] != true) return
            val (title, _) = tabReadingContent[id] ?: return
            val chapters = tabReadingChapters[id].orEmpty()
            currentWebView()?.loadDataWithBaseURL(null, buildReadingHtml(title, chapters, tabReadingCurrentIndex[id] ?: 0, tabReadingAutoPage[id] == true), "text/html", "utf-8", null)
        }
        container.addView(TextView(this).apply { text="字号：${settings.readingFontSize}px"; textSize=15f; typeface=Typeface.DEFAULT_BOLD; setTextColor(Color.DKGRAY) })
        val fontRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        lateinit var fontLabel:TextView
        fontRow.addView(createButton("A-"){settings.readingFontSize=(settings.readingFontSize-1).coerceAtLeast(14);fontLabel.text="${settings.readingFontSize}px";applyLive()}.also{it.layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f)})
        fontLabel=TextView(this).apply{text="${settings.readingFontSize}px";gravity=Gravity.CENTER;setTextColor(Color.DKGRAY);layoutParams=LinearLayout.LayoutParams(dp(60),ViewGroup.LayoutParams.WRAP_CONTENT)}
        fontRow.addView(fontLabel)
        fontRow.addView(createButton("A+"){settings.readingFontSize=(settings.readingFontSize+1).coerceAtMost(32);fontLabel.text="${settings.readingFontSize}px";applyLive()}.also{it.layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f)})
        container.addView(fontRow); container.addView(space(dp(12)))
        container.addView(TextView(this).apply{text="行距：${String.format(Locale.US,"%.1f",settings.readingLineHeight)}";textSize=15f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.DKGRAY)})
        val lineRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        lateinit var lineLabel:TextView
        lineRow.addView(createButton("窄"){settings.readingLineHeight=(settings.readingLineHeight-0.1f).coerceAtLeast(1.3f);lineLabel.text=String.format(Locale.US,"%.1f",settings.readingLineHeight);applyLive()}.also{it.layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f)})
        lineLabel=TextView(this).apply{text=String.format(Locale.US,"%.1f",settings.readingLineHeight);gravity=Gravity.CENTER;setTextColor(Color.DKGRAY);layoutParams=LinearLayout.LayoutParams(dp(60),ViewGroup.LayoutParams.WRAP_CONTENT)}
        lineRow.addView(lineLabel)
        lineRow.addView(createButton("宽"){settings.readingLineHeight=(settings.readingLineHeight+0.1f).coerceAtMost(2.6f);lineLabel.text=String.format(Locale.US,"%.1f",settings.readingLineHeight);applyLive()}.also{it.layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f)})
        container.addView(lineRow); container.addView(space(dp(12)))
        container.addView(TextView(this).apply{text="主题";textSize=15f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.DKGRAY)})
        val themeRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("sepia" to "护眼","day" to "白色","green" to "绿色","night" to "夜间","black" to "纯黑").forEach{(id,label)->
            themeRow.addView(createButton(label){settings.readingTheme=id;applyLive()}.also{it.layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f)})
        }
        container.addView(themeRow); container.addView(space(dp(12)))
        container.addView(createButton(if(settings.readingSerifFont) "字体：衬线" else "字体：无衬线"){settings.readingSerifFont=!settings.readingSerifFont;applyLive()})
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_adblock,"小说净化规则","仅在阅读模式提取时生效"){showNovelRuleManager()})
        showFullScreenDialog("阅读设置",container)
    }

    private fun buildReadingHtml(title: String, chapters: List<ReadingChapter>, activeIndex: Int = 0, autoPageActive: Boolean = false): String {
        fun esc(v:String)=v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
        val (bg,fg)=when(settings.readingTheme){"day"->"#FFFFFF" to "#111827";"night"->"#1A1A1A" to "#E5E7EB";"green"->"#C7EDCC" to "#1F2937";"black"->"#000000" to "#D1D5DB";else->"#FAF8F5" to "#1A1A1A"}
        val font=if(settings.readingSerifFont)"serif" else "sans-serif"
        val options=chapters.mapIndexed{i,c->"<option value=\"c$i\">${esc(c.title)}</option>"}.joinToString("")
        val sections = chapters.mapIndexed { i, c ->
            val body = if (c.text.isBlank()) {
                "<p class=\"empty\">本章需要打开原网页加载。</p>"
            } else {
                // Real <p> per paragraph (not one block joined only by <br>) so
                // paragraphs get spacing + first-line indent like an actual novel
                // reader, instead of reading as one dense undifferentiated wall of text.
                c.text.split(Regex("\n+"))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString("") { "<p>${esc(it)}</p>" }
            }
            "<section id=\"c$i\" class=\"chapter\"><h2>${esc(c.title)}</h2><div>$body</div></section>"
        }.joinToString("")
        return """
        <!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
        :root{--bg:$bg;--fg:$fg}*{box-sizing:border-box}html,body{margin:0;background:var(--bg);color:var(--fg)}body{max-width:860px;margin:0 auto;padding:70px 24px 80px;font-family:$font;line-height:${settings.readingLineHeight};font-size:${settings.readingFontSize}px}.toolbar{position:fixed;top:0;left:0;right:0;height:58px;background:var(--bg);border-bottom:1px solid rgba(128,128,128,.28);display:flex;align-items:center;gap:6px;padding:7px 8px;z-index:20}.toolbar button,.toolbar select{background:transparent;color:var(--fg);border:1px solid rgba(128,128,128,.4);border-radius:18px;padding:7px 9px;font-size:13px}.toolbar select{flex:1;min-width:60px}.chapter{display:none}.chapter.active{display:block}.chapter h2{font-size:${settings.readingFontSize + 4}px;line-height:1.4;margin:12px 0 24px}.chapter div{overflow-wrap:break-word;word-break:normal;text-align:justify}.chapter div p{margin:0 0 1.1em;text-indent:2em}.empty{opacity:.65}.auto{background:rgba(80,120,255,.15)!important}
        </style></head><body><div class="toolbar"><button onclick="NebulaReadingMode.openSettings()">设置</button><button onclick="NebulaReadingMode.previousChapter()">‹</button><select id="chapters" onchange="showChapter(parseInt(this.value.slice(1),10))">$options</select><button onclick="NebulaReadingMode.nextChapter()">›</button><button id="auto" onclick="NebulaReadingMode.toggleAutoPage()">自动翻页</button><button onclick="NebulaReadingMode.toggleAutoNext()">自动下一章</button><button onclick="NebulaReadingMode.openChapters()">章节</button></div><h1>${esc(title)}</h1>$sections
        <script>
        var autoTimer=null;
        function showChapter(i){document.querySelectorAll('.chapter').forEach(function(x){x.classList.remove('active')});var e=document.getElementById('c'+i);if(e)e.classList.add('active');var s=document.getElementById('chapters');if(s)s.value='c'+i;window.scrollTo(0,0)}
        function toggleAutoPage(){if(autoTimer){clearInterval(autoTimer);autoTimer=null;return}autoTimer=setInterval(function(){var atBottom=window.innerHeight+window.scrollY>=document.body.scrollHeight-8;if(atBottom){clearInterval(autoTimer);autoTimer=null;if(NebulaReadingMode.isAutoNextEnabled())NebulaReadingMode.nextChapter()}else{window.scrollBy(0,Math.max(40,Math.floor(window.innerHeight*.72))) }},1600)}
        showChapter(${activeIndex.coerceIn(0, chapters.lastIndex.coerceAtLeast(0))});
        if (${autoPageActive}) { toggleAutoPage(); }
        </script></body></html>
        """.trimIndent()
    }

    private fun showReadingChapterPicker() {
        val tabId=currentTabId ?: return
        val chapters=tabReadingChapters[tabId].orEmpty()
        if(chapters.isEmpty()) return toast("暂无章节")
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val search=EditText(this).apply{hint="搜索章节名称";setSingleLine(true);setPadding(dp(12),dp(8),dp(12),dp(8))}
        val scroll=ScrollView(this)
        val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        scroll.addView(list)
        root.addView(search);root.addView(scroll,LinearLayout.LayoutParams(-1,dp(420)))
        val dialog=MaterialAlertDialogBuilder(this).setTitle("选择章节 · ${chapters.size}").setView(root).setNegativeButton("关闭",null).create()
        fun render(){
            list.removeAllViews()
            val q=search.text.toString().trim().lowercase(Locale.getDefault())
            chapters.forEachIndexed{i,c->
                if(q.isNotBlank() && !c.title.lowercase(Locale.getDefault()).contains(q)) return@forEachIndexed
                val item=TextView(this).apply{text="${i+1}. ${c.title}";textSize=15f;setTextColor(Color.DKGRAY);setPadding(dp(12),dp(12),dp(12),dp(12));isClickable=true;setOnClickListener{dialog.dismiss();openReadingChapter(i)}}
                list.addView(item)
            }
            if(list.childCount==0) list.addView(TextView(this).apply{text="没有匹配的章节";setPadding(dp(12),dp(24),dp(12),dp(24));gravity=Gravity.CENTER})
        }
        search.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){render()};override fun afterTextChanged(s:android.text.Editable?){} })
        render();dialog.show()
    }

    private fun openReadingChapter(index:Int){
        val tabId=currentTabId ?: return
        val chapters=tabReadingChapters[tabId].orEmpty()
        if(index !in chapters.indices) return
        tabReadingCurrentIndex[tabId]=index
        val chapter=chapters[index]
        val view=currentWebView() ?: return
        if(chapter.url.isNotBlank()){
            tabReadingPendingUrl[tabId]=true
            view.loadUrl(chapter.url)
        }else{
            view.evaluateJavascript("showChapter($index)",null)
        }
    }

    private fun showNovelRuleManager() {
        val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        container.addView(createActionCard(R.drawable.ic_adblock,"净化阅读页面","只作用于阅读模式，不参与普通网页请求拦截"){settings.novelPurifyEnabled=!settings.novelPurifyEnabled;toast(if(settings.novelPurifyEnabled)"小说净化已开启" else "小说净化已关闭")})
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_script,"导入规则","JSON 规则或每行一个 CSS selector"){novelRulePicker.launch(arrayOf("application/json","text/*","text/plain","*/*"))})
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_script,"自定义规则","手动添加 CSS selector"){showAddNovelRuleDialog()})
        container.addView(space(dp(12)))
        novelRuleStore.list().forEachIndexed{index,rule->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            row.addView(TextView(this).apply{text="${if(rule.enabled)"✓" else "○"} ${rule.name}\n${rule.selectors.joinToString(", ")}";textSize=13f;setTextColor(Color.parseColor("#374151"));layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f)})
            row.addView(createButton(if(rule.enabled)"关闭" else "开启"){novelRuleStore.setEnabled(index,!rule.enabled);showNovelRuleManager()})
            row.addView(createButton("删除"){novelRuleStore.remove(index);showNovelRuleManager()})
            container.addView(row);container.addView(space(dp(6)))
        }
        showFullScreenDialog("小说净化规则",container)
    }

    private fun showAddNovelRuleDialog(){
        val box=EditText(this).apply{hint="例如：.ad, .popup, .recommend";setTextColor(Color.DKGRAY)}
        val name=EditText(this).apply{hint="规则名称";setTextColor(Color.DKGRAY)}
        val c=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(name);addView(box)}
        MaterialAlertDialogBuilder(this).setTitle("添加净化规则").setView(c).setNegativeButton("取消",null).setPositiveButton("保存"){_,_->novelRuleStore.addRule(name.text.toString(),box.text.toString().split(',', '\n', ';'));toast("规则已保存")}.show()
    }

    private fun importNovelRuleFile(uri:Uri){
        lifecycleScope.launch{try{val text=withContext(Dispatchers.IO){contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}?:""};val count=novelRuleStore.importText(text);toast(if(count>0)"已导入 $count 条规则" else "未发现有效规则")}catch(e:Exception){toast("导入规则失败：${e.message?:"未知错误"}")}}
    }

    // ========== 视频增强 ==========

    private fun showVideoEnhancePanel() {
        val webView = currentWebView() ?: return toast("当前没有网页")

        fun applyToVideos(js: String, doneMsg: String) {
            webView.evaluateJavascript(
                "(function(){var vs=document.querySelectorAll('video');vs.forEach(function(video){$js});return vs.length;})();"
            ) { result ->
                val count = result?.toIntOrNull() ?: 0
                if (count == 0) toast("当前页面没有找到 <video> 元素") else toast(doneMsg)
            }
        }

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(TextView(this).apply {
            text = "作用于当前网页里所有 <video> 播放器"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, 0, 0, dp(12))
        })

        container.addView(createActionCard(R.drawable.ic_video_enhance, "独立播放器立即播放", "检测当前正在播放的网页视频，并立刻交给 Media3 播放器播放") {
            launchCurrentWebVideo()
        })
        container.addView(space(dp(8)))

        container.addView(TextView(this).apply {
            text = "播放速度"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#111827"))
            setPadding(0, 0, 0, dp(8))
        })
        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0.5, 1.0, 1.25, 1.5, 2.0).forEach { speed ->
            speedRow.addView(createButton("${speed}x") {
                applyToVideos("video.playbackRate=$speed;", "已设置为 ${speed}x 倍速")
            }.also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        }
        container.addView(speedRow)
        container.addView(space(dp(16)))

        container.addView(createActionCard(R.drawable.ic_video_enhance, "画中画播放", "让视频以小窗形式悬浮播放（需要浏览器支持）") {
            webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(!v)return 'none';if(!document.pictureInPictureEnabled)return 'unsupported';v.requestPictureInPicture().catch(function(){});return 'ok';})();"
            ) { result ->
                when (result?.trim('"')) {
                    "none" -> toast("没有找到视频")
                    "unsupported" -> toast("该网页/系统不支持网页画中画")
                    else -> toast("已尝试进入画中画")
                }
            }
        })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_video_enhance, "强制显示播放控件", "有些网站会隐藏原生控件（进度条/音量等）") {
            applyToVideos("video.controls=true;video.style.zIndex=9999;", "已显示播放控件")
        })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_video_enhance, "循环播放", "视频结束后自动重播") {
            applyToVideos("video.loop=!video.loop;", "已切换循环播放")
        })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_video_enhance, "静音 / 取消静音", "") {
            applyToVideos("video.muted=!video.muted;", "已切换静音状态")
        })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_sniffer, "嗅探视频地址", "跳转到资源嗅探的视频分类") {
            showResourceSniffer()
        })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_permission, "拦截跳转 App", if (settings.blockAppRedirect) "已开启（推荐）" else "已关闭") {
            settings.blockAppRedirect = !settings.blockAppRedirect
            toast(if (settings.blockAppRedirect) "已开启：仅拦截 intent:// 和 App 自定义 Scheme，普通网页跳转不受影响" else "已关闭：网页可以跳转到已安装的 App")
        })

        showFullScreenDialog("视频增强", container)
    }

    // ========== 多标签管理 ==========

    private fun installIndependentVideoButton() {
        val button = TextView(this).apply {
            text = "▶ 独立播放"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        button.setOnClickListener {
            launchCurrentWebVideo()
        }
        independentVideoButton = button
        webViewContainer.addView(button, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, dp(46),
            Gravity.END or Gravity.CENTER_VERTICAL
        ).apply {
            marginEnd = dp(14)
        })
    }

    private fun decodeJavascriptString(result: String?): String {
        if (result.isNullOrBlank() || result == "null") return ""
        return runCatching { org.json.JSONTokener(result).nextValue()?.toString() ?: "" }
            .getOrElse { result.removePrefix("\"").removeSuffix("\"") }
    }

    private fun probeCurrentVideoForIndependentPlayer() {
        val view = currentWebView() ?: run { independentVideoButton?.visibility = View.GONE; return }
        if (!view.isAttachedToWindow) return
        val js = """(function(){
          try {
            var vs=[].slice.call(document.querySelectorAll('video'));
            var best=null;
            vs.forEach(function(v){
              var u=v.currentSrc||v.src||'';
              if(!u){var s=v.querySelector('source[src]');if(s)u=s.src||'';}
              if(u && (!best || (!v.paused && best.paused))) best={url:u,paused:v.paused,title:document.title||''};
            });
            return JSON.stringify(best||{});
          } catch(e){ return '{}'; }
        })();"""
        view.evaluateJavascript(js) { result ->
            if (view != currentWebView()) return@evaluateJavascript
            val raw = decodeJavascriptString(result)
            val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
            val url = json?.optString("url").orEmpty()
            val button = independentVideoButton ?: return@evaluateJavascript
            val valid = (url.startsWith("http://") || url.startsWith("https://")) && !url.startsWith("blob:")
            button.visibility = if (valid) View.VISIBLE else View.GONE
            if (valid) {
                button.tag = url
                button.contentDescription = if (json?.optBoolean("paused", true) == false)
                    "独立播放当前正在播放的视频" else "独立播放当前视频"
            }
        }
    }

    private fun launchCurrentWebVideo() {
        val view = currentWebView() ?: return toast("当前没有网页")
        val js = """(function(){
          try {
            var vs=[].slice.call(document.querySelectorAll('video'));
            var active=vs.find(function(v){return !v.paused && (v.currentSrc||v.src);}) || vs.find(function(v){return v.currentSrc||v.src;});
            if(!active) return '';
            return JSON.stringify({url:active.currentSrc||active.src||'', title:document.title||''});
          } catch(e){return '';}
        })();"""
        view.evaluateJavascript(js) { result ->
            val raw = decodeJavascriptString(result)
            val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
            val url = json?.optString("url").orEmpty()
            if (url.isBlank() || url.startsWith("blob:")) {
                toast("当前视频是 blob/无直链资源，请先使用资源嗅探")
                return@evaluateJavascript
            }
            val referer = view.url.orEmpty()
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) ?: "" }.getOrDefault("")
            startActivity(Intent(this@MainActivity, VideoPlayerActivity::class.java).apply {
                putExtra(VideoPlayerActivity.EXTRA_URL, url)
                putExtra(VideoPlayerActivity.EXTRA_TITLE, json?.optString("title").orEmpty().ifBlank { view.title ?: "Nebula 视频" })
                putExtra(VideoPlayerActivity.EXTRA_REFERER, referer)
                putExtra(VideoPlayerActivity.EXTRA_COOKIE, cookie)
            })
        }
    }

    private fun currentWebView(): WebView? = currentTabId?.let { webViews[it] }

    private fun addNewTab(url: String) {
        val tabId = UUID.randomUUID().toString()
        val webView = createWebView()
        webViews[tabId] = webView
        tabTitles[tabId] = "新标签"
        tabUrls[tabId] = url
        webViewContainer.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        switchToTab(tabId)
        webView.loadUrl(url)
    }

    private inner class BlobDownloadBridge(private val owner: WebView) {
        @JavascriptInterface
        fun save(fileName: String?, mimeType: String?, base64: String?) {
            if (owner != currentWebView() || base64.isNullOrBlank()) return
            runOnUiThread { downloadHelper.saveBase64(fileName, mimeType, base64) }
        }
    }

    private inner class DevConsoleBridge(private val owner: WebView) {
        @JavascriptInterface
        fun post(level: String?, message: String?) {
            if (owner != currentWebView()) return
            val safeLevel = level?.takeIf { it.isNotBlank() } ?: "log"
            appendDevConsole(safeLevel, message ?: "undefined")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != webPermissionRequestCode) return
        val request = pendingWebPermissionRequest ?: return
        pendingWebPermissionRequest = null
        val granted = permissions.indices.all { grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            request.deny()
            toast("网页未获得相机/麦克风权限")
            return
        }
        val resources = request.resources.filter {
            it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }.toTypedArray()
        request.grant(resources)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 704)
        }
    }

    /** Lightweight DOM observer used by the AI Agent. It never clicks or types by itself.
     * It only refreshes semantic IDs after SPA/React/Vue DOM mutations so AI targets
     * remain valid without relying on brittle CSS selectors.
     */
    private fun installDomAgentObserver(view: WebView) {
        val js = """(function(){
          if(window.__nebulaDomAgentObserver)return;window.__nebulaDomAgentObserver=true;
          function mark(){var n=0;document.querySelectorAll('a,button,input,textarea,select,[role=button],[role=link],[role=textbox],[contenteditable=true],[aria-label],[data-testid],summary').forEach(function(e){if(n>=250)return;if(!e.getAttribute('data-nebula-ai-id'))e.setAttribute('data-nebula-ai-id','ai-live-'+(++n))});}
          mark();new MutationObserver(function(){clearTimeout(window.__nebulaDomAgentTimer);window.__nebulaDomAgentTimer=setTimeout(mark,180)}).observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['aria-label','title','placeholder','role']});
        })();""".trimIndent()
        view.evaluateJavascript(js, null)
    }

    private inner class ResourceSnifferBridge(private val owner: WebView) {
        @JavascriptInterface
        fun update(payload: String) {
            if (owner != currentWebView()) return
            try {
                val json = org.json.JSONObject(payload)
                val links = jsonArrayToList(json.optJSONArray("links"))
                val images = jsonArrayToList(json.optJSONArray("images"))
                val videos = jsonArrayToList(json.optJSONArray("videos"))
                val audios = jsonArrayToList(json.optJSONArray("audios"))
                runOnUiThread { resourceSnifferUpdater?.invoke(links, images, videos, audios) }
            } catch (_: Exception) {}
        }
    }

    private fun installResourceSnifferMonitor(view: WebView) {
        val js = """
          (function(){
            if(window.__nebulaResourceMonitor)return;
            window.__nebulaResourceMonitor=true;
            function abs(u){try{return new URL(u,location.href).href}catch(e){return u||''}}
            function scan(){
              var links=[],imgs=[],vids=[],audios=[];
              document.querySelectorAll('a[href]').forEach(function(a){links.push(abs(a.getAttribute('href')))});
              document.querySelectorAll('img').forEach(function(i){['src','data-src','data-original','data-lazy-src','data-url','data-fallback-src'].forEach(function(k){var v=i.getAttribute(k);if(v)imgs.push(abs(v))});var ss=i.getAttribute('srcset');if(ss)ss.split(',').forEach(function(x){var u=x.trim().split(/\\s+/)[0];if(u)imgs.push(abs(u))})});
              document.querySelectorAll('[style*="background-image"]').forEach(function(i){var m=(i.getAttribute('style')||'').match(/url\([\"']?([^\"')]+)[\"']?\)/i);if(m)imgs.push(abs(m[1]))});
              document.querySelectorAll('video').forEach(function(v){var u=v.currentSrc||v.src;if(u)vids.push(abs(u));var p=v.getAttribute('poster');if(p)imgs.push(abs(p));v.querySelectorAll('source[src]').forEach(function(x){vids.push(abs(x.getAttribute('src')))})});
              document.querySelectorAll('audio').forEach(function(a){var u=a.currentSrc||a.src;if(u)audios.push(abs(u));a.querySelectorAll('source[src]').forEach(function(x){audios.push(abs(x.getAttribute('src')))})});
              document.querySelectorAll('source[src]').forEach(function(x){var u=abs(x.getAttribute('src')),t=(x.getAttribute('type')||'').toLowerCase();if(/audio|aac|mpeg|ogg|wav|flac/.test(t)||/\\.(mp3|m4a|aac|ogg|oga|wav|flac|opus)(\\?|$)/i.test(u))audios.push(u);if(/video|mp4|webm|quicktime|mpegurl|dash/.test(t)||/\\.(mp4|m4v|webm|mov|m3u8|mpd|ts|m4s)(\\?|$)/i.test(u))vids.push(u)});
              try{performance.getEntriesByType('resource').forEach(function(e){var u=e.name||'',t=(e.initiatorType||'').toLowerCase();if(/\\.(mp4|m4v|webm|mov|m3u8|mpd|ts|m4s)(\\?|$)/i.test(u)||/video|media|mpegurl|dash/.test(t))vids.push(abs(u));if(/\\.(mp3|m4a|aac|ogg|oga|wav|flac|opus)(\\?|$)/i.test(u)||/audio/.test(t)||/audio|mpeg|aac|ogg|wav|flac/i.test(u))audios.push(abs(u))})}catch(e){}
              function uniq(a){var o={};return a.filter(function(x){return x&&!o[x]&&(o[x]=1)})}
              try{NebulaResourceSniffer.update(JSON.stringify({links:uniq(links),images:uniq(imgs),videos:uniq(vids),audios:uniq(audios)}))}catch(e){}
            }
            scan();setInterval(scan,1000);
            new MutationObserver(function(){clearTimeout(window.__nebulaResourceTimer);window.__nebulaResourceTimer=setTimeout(scan,120)}).observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['src','srcset','poster','style']});
          })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    // ========== 元素隐藏（手动挑选并隐藏页面元素） ==========

    private inner class ElementHiderBridge(private val owner: WebView) {
        @JavascriptInterface
        fun confirm(selector: String, hostname: String) {
            if (owner != currentWebView()) return
            if (selector.isBlank() || hostname.isBlank()) return
            runOnUiThread {
                adBlock.addCustomRules("$hostname##$selector")
                toast("已隐藏该元素")
                owner.reload()
            }
        }

        @JavascriptInterface
        fun cancelled() {
            if (owner != currentWebView()) return
            runOnUiThread { toast("已取消") }
        }
    }

    /**
     * 手动"元素隐藏"工具：点一下按钮进入取点模式，点页面上任意元素会高亮并弹出确认条，
     * 确认后把生成的选择器写成 AdBlockStore 的 `域名##选择器` 规则——复用已有的按域名生效的
     * 元素隐藏 CSS 注入管线（cosmeticCss），不用另起一套存储和渲染逻辑。
     */
    private fun startElementPicker() {
        val webView = currentWebView() ?: return toast("当前没有网页")
        val js = """
          (function(){
            if (window.__nebulaPickerActive) return;
            window.__nebulaPickerActive = true;

            var overlay = document.createElement('div');
            overlay.style.cssText = 'position:fixed;left:0;right:0;top:0;padding:10px 14px;background:#111827;color:#fff;font:14px sans-serif;z-index:2147483647;text-align:center;';
            overlay.textContent = '点击页面上要隐藏的元素（再次点击顶部条可取消）';
            overlay.addEventListener('click', function(e){ e.stopPropagation(); cleanup(); if (window.NebulaElementHider) NebulaElementHider.cancelled(); });
            document.documentElement.appendChild(overlay);

            var highlighted = null;
            var confirmBar = null;
            function clearHighlight(){
              if (highlighted) { highlighted.style.outline=''; highlighted.style.backgroundColor=''; highlighted=null; }
            }
            function highlight(el){
              clearHighlight();
              highlighted = el;
              el.style.outline = '2px solid #EF4444';
              el.style.backgroundColor = 'rgba(239,68,68,.15)';
            }

            function cssSelector(el){
              if (el.id) return '#' + CSS.escape(el.id);
              var path = [];
              var node = el;
              while (node && node.nodeType === 1 && node !== document.body && path.length < 4) {
                var part = node.tagName.toLowerCase();
                if (typeof node.className === 'string' && node.className.trim()) {
                  var cls = node.className.trim().split(/\s+/).filter(Boolean).slice(0, 2);
                  if (cls.length) part += '.' + cls.map(function(c){ return CSS.escape(c); }).join('.');
                }
                var parent = node.parentElement;
                if (parent) {
                  var siblings = Array.prototype.filter.call(parent.children, function(c){ return c.tagName === node.tagName; });
                  if (siblings.length > 1) part += ':nth-of-type(' + (Array.prototype.indexOf.call(siblings, node) + 1) + ')';
                }
                path.unshift(part);
                node = parent;
              }
              return path.join('>');
            }

            function onClick(e){
              // 顶部提示条和底部确认条自己的按钮点击不能走"选中新元素"这条逻辑——
              // 之前漏排除了确认条，导致点"隐藏该元素"按钮时，这个捕获阶段的监听器
              // 先一步 stopPropagation，把点击当成"又选中了一个新元素（按钮本身）"，
              // 按钮自己的确认回调永远没机会执行，所以点了没反应。
              if (overlay.contains(e.target)) return;
              if (confirmBar && confirmBar.contains(e.target)) return;
              e.preventDefault(); e.stopPropagation();
              var el = e.target;
              var selector = cssSelector(el);
              highlight(el);
              if (confirmBar) confirmBar.remove();
              var bar = document.createElement('div');
              confirmBar = bar;
              bar.id = '__nebula_picker_confirm';
              bar.style.cssText = 'position:fixed;left:0;right:0;bottom:0;padding:12px;background:#111827;z-index:2147483647;display:flex;gap:10px;';
              var hideBtn = document.createElement('button');
              hideBtn.textContent = '隐藏该元素';
              hideBtn.style.cssText = 'flex:1;padding:12px;border:none;border-radius:10px;background:#EF4444;color:#fff;font-size:15px;';
              hideBtn.addEventListener('click', function(evt){
                evt.stopPropagation();
                if (window.NebulaElementHider) NebulaElementHider.confirm(selector, location.hostname);
                cleanup();
              });
              var cancelBtn = document.createElement('button');
              cancelBtn.textContent = '取消';
              cancelBtn.style.cssText = 'flex:1;padding:12px;border:none;border-radius:10px;background:#374151;color:#fff;font-size:15px;';
              cancelBtn.addEventListener('click', function(evt){
                evt.stopPropagation();
                clearHighlight();
                bar.remove();
                confirmBar = null;
              });
              bar.appendChild(hideBtn); bar.appendChild(cancelBtn);
              document.documentElement.appendChild(bar);
            }

            function cleanup(){
              window.__nebulaPickerActive = false;
              clearHighlight();
              overlay.remove();
              if (confirmBar) { confirmBar.remove(); confirmBar = null; }
              document.removeEventListener('click', onClick, true);
            }

            document.addEventListener('click', onClick, true);
          })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        toast("已进入元素隐藏模式，点页面上要隐藏的元素")
    }

    private inner class ReadingModeBridge(private val owner: WebView) {
        @JavascriptInterface
        fun openSettings() {
            if (owner != currentWebView()) return
            runOnUiThread { showReadingSettings() }
        }

        @JavascriptInterface
        fun openChapters() {
            if (owner != currentWebView()) return
            runOnUiThread { showReadingChapterPicker() }
        }

        @JavascriptInterface
        fun previousChapter() {
            if (owner != currentWebView()) return
            runOnUiThread {
                val id=currentTabId ?: return@runOnUiThread
                val i=(tabReadingCurrentIndex[id] ?: 0)-1
                if(i>=0) openReadingChapter(i) else toast("已经是第一章")
            }
        }

        @JavascriptInterface
        fun nextChapter() {
            if (owner != currentWebView()) return
            runOnUiThread {
                val id=currentTabId ?: return@runOnUiThread
                val chapters=tabReadingChapters[id].orEmpty()
                val i=(tabReadingCurrentIndex[id] ?: 0)+1
                if(i<chapters.size) openReadingChapter(i)
                else {
                    val next=tabReadingNextUrl[id].orEmpty()
                    if(next.isNotBlank()){tabReadingPendingUrl[id]=true;owner.loadUrl(next)} else toast("没有发现下一章")
                }
            }
        }

        @JavascriptInterface
        fun toggleAutoPage() {
            if (owner != currentWebView()) return
            runOnUiThread {
                if (owner != currentWebView()) return@runOnUiThread
                owner.evaluateJavascript("toggleAutoPage()", null)
                val id=currentTabId ?: return@runOnUiThread
                val enabled=!(tabReadingAutoPage[id] ?: false)
                tabReadingAutoPage[id]=enabled
                toast(if(enabled) "自动翻页已开启" else "自动翻页已关闭")
            }
        }

        @JavascriptInterface
        fun toggleAutoNext() {
            if (owner != currentWebView()) return
            runOnUiThread {
                val id=currentTabId ?: return@runOnUiThread
                val enabled=!(tabReadingAutoNext[id] ?: false)
                tabReadingAutoNext[id]=enabled
                toast(if(enabled) "自动下一章已开启" else "自动下一章已关闭")
            }
        }

        @JavascriptInterface
        fun isAutoNextEnabled(): Boolean {
            val id=currentTabId ?: return false
            return tabReadingAutoNext[id] == true
        }
    }

    private fun isLikelyStaticOrMedia(url: String): Boolean {
        val path = runCatching { Uri.parse(url).path.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        return path.matches(Regex(".*\\.(png|jpe?g|gif|webp|avif|svg|ico|bmp|mp4|m4v|webm|mov|m3u8|ts|m4s|aac|mp3|wav|ogg|woff2?|ttf|otf|css)(?:$|/).*"))
    }

    /**
     * Detect a first-party verification/challenge document from URL alone.
     * This deliberately does NOT solve or bypass a CAPTCHA; it only prevents
     * Nebula's own filters/scripts from breaking the verification flow.
     */
    private fun isVerificationUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val u = url.lowercase(Locale.US)
        return u.contains("/cdn-cgi/challenge") ||
            u.contains("/cdn-cgi/challenge-platform") ||
            u.contains("challenges.cloudflare.com") ||
            u.contains("challenge-platform") ||
            u.contains("/turnstile/") ||
            u.contains("/recaptcha/") ||
            u.contains("/hcaptcha/") ||
            u.contains("/captcha/") ||
            u.contains("verify-human") ||
            u.contains("verify_you_are_human") ||
            u.contains("human-verification") ||
            u.contains("security-check")
    }

    private fun isVerificationResource(url: String): Boolean {
        if (url.isBlank()) return false
        val u = url.lowercase(Locale.US)
        return u.contains("captcha") ||
            u.contains("recaptcha") ||
            u.contains("hcaptcha") ||
            u.contains("challenges.cloudflare.com") ||
            u.contains("turnstile") ||
            u.contains("challenge-platform") ||
            u.contains("/cdn-cgi/challenge") ||
            u.contains("cloudflare") ||
            u.contains("verify-human") ||
            u.contains("security-check")
    }

    /**
     * Returns true only for URLs that look like an actual userscript payload or
     * a known userscript installation endpoint. Ordinary .js resources are not
     * intercepted, otherwise websites that load JavaScript files would break.
     */
    private fun isLikelyUserscriptInstallUrl(rawUrl: String): Boolean {
        val url = rawUrl.trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false
        val u = url.lowercase(Locale.US)
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val path = uri?.path.orEmpty().lowercase(Locale.US)
        val query = uri?.query.orEmpty().lowercase(Locale.US)

        if (path.endsWith(".user.js") || path.endsWith(".userjs")) return true
        if (query.contains("userscript") || query.contains("user.js")) return true
        if ((query.contains("download=1") || query.contains("download=true")) &&
            (path.contains("/script") || path.contains("/code") || path.endsWith(".js"))) return true

        val host = uri?.host.orEmpty().lowercase(Locale.US)
        val knownHost = host.contains("greasyfork") ||
            host.contains("sleazyfork") ||
            host.contains("openuserjs") ||
            host.contains("userscripts.org")
        if (knownHost && (path.contains("/code/") || path.contains("/install/") || path.endsWith("/install"))) return true

        // A number of self-hosted script repositories expose /scripts/<id>/code
        // without the .user.js suffix. Restrict this heuristic to a script/code
        // path so normal site navigation is unaffected.
        return path.contains("/userscript/") ||
            path.contains("/userscripts/") ||
            path.contains("/user-script/") ||
            path.contains("/userjs/")
    }

    /** Extract a nested URL from tampermonkey/violentmonkey style install schemes. */
    private fun extractUserscriptInstallTarget(rawUrl: String): String? {
        val lower = rawUrl.lowercase(Locale.US)
        if (lower.startsWith("tampermonkey://") ||
            lower.startsWith("violentmonkey://") ||
            lower.startsWith("userscript://")) {
            val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
            return uri.getQueryParameter("url")
                ?: uri.getQueryParameter("script")
                ?: uri.getQueryParameter("src")
        }
        return null
    }

    private fun installUserscriptFromWeb(view: WebView, scriptUrl: String) {
        val target = scriptUrl.trim()
        if (!isLikelyUserscriptInstallUrl(target)) return
        if (!pendingUserScriptImports.add(target)) return

        val referer = pageUrlsByWebView[view] ?: runCatching { view.url.orEmpty() }.getOrDefault("")
        lifecycleScope.launch {
            try {
                val (script, isNew) = withContext(Dispatchers.IO) {
                    scripts.importUrl(target, referer)
                }
                toast(if (isNew) "脚本已自动安装：${script.name}" else "脚本已安装：${script.name}")
                // Reload the current page so a document-start userscript is applied
                // immediately without requiring the user to leave the install page.
                if (view.isAttachedToWindow) view.reload()
            } catch (e: Exception) {
                toast("脚本安装失败：${e.message ?: e.javaClass.simpleName}")
            } finally {
                pendingUserScriptImports.remove(target)
            }
        }
    }

    /**
     * Installs a small page-side bridge. It catches dynamically-created install
     * anchors used by Greasy Fork/OpenUserJS and other userscript repositories.
     * The handler only claims links that are clearly userscript installation links.
     */
    private fun installUserscriptInstallBridge(view: WebView) {
        val js = """
        (function(){
          if(window.__nebulaUserscriptInstallBridge)return;
          window.__nebulaUserscriptInstallBridge=true;
          window.__nebulaUserscriptInstall=window.__nebulaUserscriptInstall||{install:function(u){try{window.NebulaUserscriptInstall.install(String(u));}catch(e){}}};
          function textOf(a){
            try{
              return String((a.innerText||a.textContent||a.getAttribute('aria-label')||a.title||a.id||a.className||'')).toLowerCase();
            }catch(e){return '';}
          }
          function looksLike(href,a){
            try{
              var h=String(href||'').toLowerCase();
              if(!/^https?:\\/\\//.test(h))return false;
              var path='';try{path=new URL(h,location.href).pathname.toLowerCase();}catch(e){}
              if(/\\.user\\.js$|\\.userjs$/.test(path))return true;
              if(/[?&](userscript|user\\.js)=/i.test(h))return true;
              if(/[?&]download=(1|true)/i.test(h) && /(script|code|user)/i.test(path))return true;
              var host='';try{host=new URL(h,location.href).hostname.toLowerCase();}catch(e){}
              if(/greasyfork|sleazyfork|openuserjs|userscripts\\.org/.test(host) && /\\/(code|install)\\//i.test(path))return true;
              var t=textOf(a);
              return /(安装脚本|安装此脚本|安装用户脚本|install(\\s+this)?\\s+(userscript|script)|userscript\\s+install|tampermonkey|violentmonkey)/i.test(t) && /(script|code|user)/i.test(path);
            }catch(e){return false;}
          }
          function handle(ev){
            try{
              var el=ev.target;
              var a=el&&el.closest?el.closest('a'):null;
              if(!a)return;
              var href=a.href||a.getAttribute('href')||'';
              if(!looksLike(href,a))return;
              if(window.__nebulaUserscriptInstall&&window.__nebulaUserscriptInstall.install){
                ev.preventDefault();ev.stopPropagation();
                window.__nebulaUserscriptInstall.install(String(href));
              }
            }catch(e){}
          }
          document.addEventListener('click',handle,true);
          document.addEventListener('auxclick',handle,true);
          var oldOpen=window.open;
          window.open=function(url){
            try{
              var h=String(url||'');
              if(looksLike(h,null) && window.__nebulaUserscriptInstall&&window.__nebulaUserscriptInstall.install){
                window.__nebulaUserscriptInstall.install(h);return null;
              }
            }catch(e){}
            return oldOpen.apply(this,arguments);
          };
        })();
        """.trimIndent()
        runCatching { view.evaluateJavascript(js, null) }
    }

    private inner class UserscriptInstallBridge(private val owner: WebView) {
        @JavascriptInterface
        fun install(url: String) {
            if (owner != currentWebView()) return
            runOnUiThread { installUserscriptFromWeb(owner, url) }
        }
    }

    private fun createWebView(): WebView {
        val webView = WebView(this)
        applyWebSettings(webView)
        webView.addJavascriptInterface(HomeJsInterface(), "NebulaHome")
        webView.addJavascriptInterface(DevConsoleBridge(webView), "NebulaDevConsole")
        webView.addJavascriptInterface(BlobDownloadBridge(webView), "NebulaBlobDownload")
        webView.addJavascriptInterface(UserscriptInstallBridge(webView), "NebulaUserscriptInstall")
        webView.addJavascriptInterface(ReadingModeBridge(webView), "NebulaReadingMode")
        webView.addJavascriptInterface(ResourceSnifferBridge(webView), "NebulaResourceSniffer")
        webView.addJavascriptInterface(ElementHiderBridge(webView), "NebulaElementHider")
        webView.evaluateJavascript("window.__nebulaUserscriptInstall=window.__nebulaUserscriptInstall||{install:function(u){window.NebulaUserscriptInstall.install(String(u));}};", null)
        scriptInjector.installDocumentStartRuntime(webView)
        installDevConsoleHookAtDocumentStart(webView)

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            // Userscript repositories may hand the .user.js payload to the WebView
            // download callback rather than navigation. Install it instead of saving
            // it as a generic download.
            val userScriptDownload = contentDisposition.orEmpty().contains(".user.js", ignoreCase = true) ||
                contentDisposition.orEmpty().contains(".userjs", ignoreCase = true)
            if (isLikelyUserscriptInstallUrl(url) ||
                (userScriptDownload && (mimetype.equals("application/javascript", true) ||
                    mimetype.equals("text/javascript", true) ||
                    mimetype.equals("application/x-javascript", true) ||
                    mimetype.equals("text/plain", true)))) {
                installUserscriptFromWeb(webView, url)
            } else {
                requestNotificationPermissionIfNeeded()
                downloadHelper.download(url, null, contentDisposition, mimetype, userAgent)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                addNetworkLog(request.method, url)

                // Userscript repositories usually make the Install button navigate
                // directly to a .user.js payload. Consume that navigation and install
                // it instead of showing raw JavaScript in the browser.
                extractUserscriptInstallTarget(url)?.let { target ->
                    installUserscriptFromWeb(view, target)
                    return true
                }
                if (isLikelyUserscriptInstallUrl(url)) {
                    installUserscriptFromWeb(view, url)
                    return true
                }

                // CAPTCHA/Cloudflare/Turnstile verification redirects are part of
                // the site's authentication flow. Never let the app-redirect guard
                // cancel them merely because they are background redirects.
                if (settings.humanVerificationCompatEnabled && request.isForMainFrame &&
                    isVerificationUrl(url)) {
                    verificationModeByWebView[view] = true
                    return false
                }

                // 注意：这里不要拦截普通 HTTP/HTTPS 重定向。
                // 搜索引擎、登录、地区站点、CDN、短链接和站内跳转都可能发生
                // 正常的跨域 HTTPS 重定向，例如 Google -> google.com.hk。
                // “拦截跳转 App”只负责拦截 intent:// 和自定义 App Scheme，
                // 不能把“第三方网页跳转”误判成“打开 App”。

                // V15: handle common download redirects (LanZou/other cloud disks)
                // Some download links return files instead of HTML. Let WebView download them.
                if (url.contains("lanzou", ignoreCase = true) ||
                    url.contains("pan", ignoreCase = true) ||
                    url.contains("download", ignoreCase = true)) {
                    view.postDelayed({
                        try {
                            val cookie = CookieManager.getInstance().getCookie(url)
                            if (cookie != null) {
                                // keep session cookie for cloud download
                            }
                        } catch (_: Exception) {}
                    }, 100)
                }

                // 所有正常网页导航一律交给 WebView。
                // 只有下面的非 HTTP(S) scheme 才进入 App 跳转处理。
                val scheme = runCatching { Uri.parse(url).scheme?.lowercase(Locale.US) }.getOrNull()
                if (scheme == "http" || scheme == "https") {
                    return false
                }

                // 系统级链接（打电话/发短信/邮件/地图）：这是用户明确想要的操作，始终放行。
                val alwaysAllowPrefixes = listOf("tel:", "sms:", "smsto:", "mailto:", "geo:")
                if (alwaysAllowPrefixes.any { url.startsWith(it) }) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                    return true
                }

                // intent:// 链接（B 站等视频站点常用来"强制唤起App"）：优先跟随它自带的
                // 网页兜底地址（browser_fallback_url），而不是真的去拉起原生 App。
                if (url.startsWith("intent://")) {
                    return try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (settings.blockAppRedirect) {
                            val fallback = intent.getStringExtra("browser_fallback_url")
                            if (!fallback.isNullOrBlank()) view.loadUrl(fallback)
                            true
                        } else {
                            startActivity(intent)
                            true
                        }
                    } catch (_: Exception) {
                        true // 解析失败就什么都不做，总比强行跳转/崩溃好
                    }
                }

                // 其它自定义 scheme（bilibili://、weixin:// 等）：默认直接拦截、留在网页里播放，
                // 不再像以前那样每次点视频都被弹去应用商店或原生 App。
                if (settings.blockAppRedirect) {
                    return true
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (_: Exception) {
                    true
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.proceed()
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                val tabId = webViews.entries.find { it.value == view }?.key
                if (tabId != null) {
                    webViews.remove(tabId)
                    pageUrlsByWebView.remove(view)
                    verificationModeByWebView.remove(view)
                    view.destroy()
                    val url = tabUrls[tabId] ?: homeUrl()
                    addNewTab(url)
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageUrlsByWebView[view] = url
                verificationModeByWebView[view] = settings.humanVerificationCompatEnabled && isVerificationUrl(url)
                addNetworkLog("START", url)
                cachedUrl = url
                // Do not inject user/dev/AI scripts into a verification document.
                // Some challenge providers treat DOM mutation as a failed check.
                if (verificationModeByWebView[view] != true) {
                    installUserscriptInstallBridge(view)
                    scriptInjector.injectStartScripts(view, url)
                }
                if (currentWebView() == view) {
                    etUrl.setText(if (url.startsWith("data:text/html")) "" else url)
                    progressBar.visibility = View.VISIBLE
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageUrlsByWebView[view] = url
                val verificationPage = settings.humanVerificationCompatEnabled && isVerificationUrl(url)
                verificationModeByWebView[view] = verificationPage
                addNetworkLog("FINISH", url)
                cachedUrl = url
                if (!verificationPage) {
                    installBlobDownloadBridge(view)
                    installUserscriptInstallBridge(view)
                    scriptInjector.injectEndScripts(view, url)
                    installDevConsoleHook(view)
                    installDomAgentObserver(view)
                    installGithubChineseLayer(view, url)
                }
                // V16: avoid blank WebView pages caused by target="_blank" download links.
                // Challenge documents are left untouched.
                if (!verificationPage) view.evaluateJavascript("""
                    (function(){
                      if (window.__nebulaBlankTargetFix) return;
                      window.__nebulaBlankTargetFix = true;
                      document.addEventListener('click', function(e){
                        var a = e.target && e.target.closest ? e.target.closest('a[target="_blank"]') : null;
                        if (a && a.href) a.target = '_self';
                      }, true);
                    })();
                """.trimIndent(), null)
                if (settings.humanVerificationCompatEnabled) {
                    installVerificationCompatibility(view)
                }
                val pendingTabId = webViews.entries.find { it.value == view }?.key
                if (pendingTabId != null && tabReadingMode[pendingTabId] == true && tabReadingPendingUrl[pendingTabId] == true && !url.startsWith("data:text/html")) {
                    updateTabInfo(view, view.title ?: url, url)
                    // A chapter link was selected from the reading-mode chapter list.
                    // Extract the newly opened chapter and immediately return to the reader.
                    view.postDelayed({
                        if (view.isAttachedToWindow && tabReadingMode[pendingTabId] == true) {
                            extractReadingMode(view, pendingTabId, url)
                        }
                    }, 120L)
                    return
                }
                if (settings.autoTranslateEnabled && !url.startsWith("data:text/html")) {
                    // Many modern pages render their actual text after onPageFinished.
                    // Try once immediately and again after the SPA/async content settles.
                    maybeAutoTranslate(view)
                    view.postDelayed({
                        if (!view.isAttachedToWindow) return@postDelayed
                        maybeAutoTranslate(view)
                    }, 1800L)
                    view.postDelayed({
                        if (!view.isAttachedToWindow) return@postDelayed
                        maybeAutoTranslate(view)
                    }, 4500L)
                }
                val title = view.title ?: url

                if (!settings.incognitoEnabled && !url.startsWith("data:text/html")) {
                    historyStore.add(title, url)
                }

                updateTabInfo(view, title, url)

                if (!verificationPage && settings.adBlockEnabled && !url.startsWith("data:text/html")) {
                    val css = adBlock.cosmeticCss(url)
                    if (css.isNotBlank()) {
                        val js = "(function(){var s=document.createElement('style');s.id='__nebula_adblock';s.textContent=${org.json.JSONObject.quote(css)};document.documentElement.appendChild(s);})();"
                        view.evaluateJavascript(js, null)
                    }
                }

                if (currentWebView() == view) {
                    etUrl.setText(if (url.startsWith("data:text/html")) "" else url)
                    progressBar.visibility = View.GONE
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val method = request.method ?: "GET"
                addNetworkLog(method, url)
                val host = request.url.host ?: return super.shouldInterceptRequest(view, request)
                val app = application as NebulaApp

                val canSafelyBlock = (method.equals("GET", true) || method.equals("HEAD", true)) && !request.isForMainFrame
                val lowerUrl = url.lowercase(Locale.US)
                val verificationResource = lowerUrl.contains("captcha") || lowerUrl.contains("recaptcha") || lowerUrl.contains("hcaptcha") || lowerUrl.contains("challenge") || lowerUrl.contains("turnstile") || lowerUrl.contains("cloudflare")
                // IMPORTANT: shouldInterceptRequest() is called on Chromium's background
                // thread. WebView.getUrl() is forbidden here and causes a fatal crash.
                // Use the URL captured by onPageStarted/onPageFinished instead, with
                // Referer as a useful fallback.
                val pageUrl = pageUrlsByWebView[view]
                    ?: request.requestHeaders["Referer"]
                    ?: ""
                val accept = request.requestHeaders["Accept"].orEmpty().lowercase(Locale.US)
                // Keep top-level documents and same-site JSON navigations intact.
                // Everything else can be blocked when a filter rule explicitly matches,
                // including ad images, ad scripts, tracking XHR, fonts and media.
                val isMainDocumentLike = accept.contains("text/html")
                val isSameSiteApi = accept.contains("application/json") && runCatching {
                    val ph = Uri.parse(pageUrl).host?.lowercase(Locale.US).orEmpty()
                    host == ph || host.endsWith(".$ph") || ph.endsWith(".$host")
                }.getOrDefault(false)
                // IMPORTANT: this callback runs on Chromium's background thread.
                // verificationModeByWebView is a concurrent snapshot; do not call
                // view.getUrl(), view.getSettings(), evaluateJavascript(), etc. here.
                val verificationMode = settings.humanVerificationCompatEnabled &&
                    (verificationModeByWebView[view] == true || isVerificationResource(url))
                val blocked = canSafelyBlock && !verificationMode && !isMainDocumentLike && !isSameSiteApi &&
                    ((settings.adBlockEnabled && adBlock.isBlocked(url, pageUrl)) ||
                        app.toolRegistry.netRules.isBlocked(host))
                return if (blocked) WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                else super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                pendingFileChooser?.onReceiveValue(null)
                pendingFileChooser = filePathCallback
                pendingFileChooserWebView = view

                return try {
                    val accepted = fileChooserParams.acceptTypes
                        .flatMap { it.split(',') }
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && it != "*" }
                        .distinct()

                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        putExtra(
                            Intent.EXTRA_ALLOW_MULTIPLE,
                            fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
                        )

                        if (accepted.isEmpty()) {
                            type = "*/*"
                        } else {
                            val mimeTypes = accepted.filter { it.contains('/') }
                            if (mimeTypes.size == 1) {
                                type = mimeTypes.first()
                            } else {
                                type = "*/*"
                                if (mimeTypes.isNotEmpty()) {
                                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                                }
                            }
                        }
                    }

                    Log.d(
                        "NebulaFileChooser",
                        "OPEN_DOCUMENT mode=${fileChooserParams.mode}, accept=${accepted.joinToString()}"
                    )
                    webFileChooser.launch(intent)
                    true
                } catch (e: Exception) {
                    Log.e("NebulaFileChooser", "Unable to open Android file picker", e)
                    pendingFileChooser = null
                    pendingFileChooserWebView = null
                    filePathCallback.onReceiveValue(null)
                    toast("无法打开文件选择器：${e.message ?: "未知错误"}")
                    true
                }
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                (view.parent as? ViewGroup)?.removeView(view)
                customView = view
                customViewCallback = callback
                wasFullscreen = requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                fullscreenRoot = findViewById(android.R.id.content) as? ViewGroup
                fullscreenRoot?.addView(view, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                ))
                view.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                view.bringToFront()
            }

            override fun onHideCustomView() {
                exitCustomView()
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (currentWebView() == view) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
                }
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                runOnUiThread {
                    val needed = request.resources.filter {
                        it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                            it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    }
                    if (needed.isEmpty()) {
                        request.deny()
                        return@runOnUiThread
                    }

                    val androidPermissions = buildList {
                        if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE in needed &&
                            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                            add(Manifest.permission.CAMERA)
                        }
                        if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE in needed &&
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            add(Manifest.permission.RECORD_AUDIO)
                        }
                    }

                    pendingWebPermissionRequest?.deny()
                    pendingWebPermissionRequest = request
                    if (androidPermissions.isEmpty()) {
                        request.grant(needed.toTypedArray())
                        pendingWebPermissionRequest = null
                    } else {
                        requestPermissions(androidPermissions.toTypedArray(), webPermissionRequestCode)
                    }
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    "NebulaWebConsole",
                    "${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                )
                return true
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                applyWebSettings(newWebView)
                newWebView.webViewClient = view.webViewClient
                newWebView.webChromeClient = view.webChromeClient

                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()

                runOnUiThread {
                    val tabId = UUID.randomUUID().toString()
                    webViews[tabId] = newWebView
                    tabTitles[tabId] = "新标签"
                    tabUrls[tabId] = "about:blank"
                    webViewContainer.addView(newWebView, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    switchToTab(tabId)
                }
                return true
            }
        }

        return webView
    }

    private fun exitCustomView() {
        val view = customView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        fullscreenRoot = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun enterPictureInPictureModeCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast("当前 Android 版本不支持画中画")
            return
        }
        if (customView == null) {
            toast("请先播放视频并进入全屏")
            return
        }
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            toast("无法进入画中画：${e.message ?: "未知错误"}")
        }
    }

    private fun installBlobDownloadBridge(view: WebView) {
        val js = """
        (function(){
          if(window.__nebulaBlobDownload)return;
          window.__nebulaBlobDownload=true;
          document.addEventListener('click', function(ev){
            var a=ev.target&&ev.target.closest?ev.target.closest('a'):null;
            if(!a||!a.href)return;
            var href=a.href;
            if(href.indexOf('blob:')!==0 && href.indexOf('data:')!==0)return;
            ev.preventDefault();
            var name=(a.getAttribute('download')||'').trim() || ('nebula_'+Date.now());
            fetch(href).then(function(r){return r.blob();}).then(function(blob){
              if(blob.size>32*1024*1024){ throw new Error('large'); }
              var reader=new FileReader();
              reader.onloadend=function(){
                var s=String(reader.result||'');
                var comma=s.indexOf(',');
                if(comma<0)return;
                if(window.NebulaBlobDownload) window.NebulaBlobDownload.save(name, blob.type||'application/octet-stream', s.slice(comma+1));
              };
              reader.readAsDataURL(blob);
            }).catch(function(e){ console.warn('[Nebula] blob download failed',e); });
          }, true);
        })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyWebSettings(webView: WebView) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = !settings.incognitoEnabled
            databaseEnabled = !settings.incognitoEnabled
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            builtInZoomControls = false
            displayZoomControls = false
            // Keep target=_blank downloads/navigation in the current tab instead of creating a blank WebView.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            savePassword = false
            saveFormData = false
            cacheMode = if (settings.incognitoEnabled) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            userAgentString = if (settings.desktopModeEnabled) {
                DESKTOP_UA
            } else {
                mobileUserAgent()
            }
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(!settings.incognitoEnabled)
        if (!settings.incognitoEnabled) {
            cookieManager.setAcceptThirdPartyCookies(webView, true)
        }
    }

    private fun switchToTab(tabId: String) {
        currentTabId = tabId
        webViews.forEach { (id, view) ->
            view.visibility = if (id == tabId) View.VISIBLE else View.GONE
        }
        val url = tabUrls[tabId] ?: ""
        etUrl.setText(if (url.startsWith("data:text/html")) "" else url)
        renderTabs()
        webViewContainer.post { probeCurrentVideoForIndependentPlayer() }
    }

    private fun updateTabInfo(webView: WebView, title: String, url: String) {
        val tabId = webViews.entries.find { it.value == webView }?.key ?: return
        tabTitles[tabId] = title
        tabUrls[tabId] = url
        if (currentTabId == tabId) {
            etUrl.setText(if (url.startsWith("data:text/html")) "" else url)
        }
        renderTabs()
    }

    private fun renderTabs() {
        tabContainer.removeAllViews()
        webViews.keys.forEach { tabId ->
            val isCurrent = tabId == currentTabId
            val title = tabTitles[tabId]?.take(8) ?: "新标签"

            val tabView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(14, 8, 14, 8)
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    cornerRadius = 18f
                    setColor(if (isCurrent) Color.parseColor("#DBEAFE") else Color.TRANSPARENT)
                }
                setOnClickListener { switchToTab(tabId) }
            }

            val titleView = TextView(this).apply {
                text = title
                textSize = 12f
                setTextColor(Color.parseColor("#111827"))
                maxLines = 1
                setPadding(0, 0, 8, 0)
            }

            val closeView = TextView(this).apply {
                text = "✕"
                textSize = 13f
                setTextColor(Color.parseColor("#6B7280"))
                setPadding(8, 0, 0, 0)
                setOnClickListener { closeTab(tabId) }
            }

            detachFromParent(titleView)

            tabView.addView(titleView)
            detachFromParent(closeView)
            tabView.addView(closeView)
            detachFromParent(tabView)
            tabContainer.addView(tabView)
        }
    }

    private fun closeTab(tabId: String) {
        if (webViews.size <= 1) {
            toast("至少保留一个标签页")
            return
        }
        webViews[tabId]?.let { view ->
            webViewContainer.removeView(view)
            verificationModeByWebView.remove(view)
            view.destroy()
        }
        webViews.remove(tabId)
        tabTitles.remove(tabId)
        tabUrls.remove(tabId)
        tabOriginalUrls.remove(tabId)
        tabReadingMode.remove(tabId)
        tabReadingContent.remove(tabId)
        tabReadingChapters.remove(tabId)
        tabReadingCurrentIndex.remove(tabId)
        tabReadingPrevUrl.remove(tabId)
        tabReadingNextUrl.remove(tabId)
        tabReadingPendingUrl.remove(tabId)
        tabReadingAutoPage.remove(tabId)
        tabReadingAutoNext.remove(tabId)
        if (currentTabId == tabId) {
            val remaining = webViews.keys.lastOrNull()
            if (remaining != null) switchToTab(remaining)
        } else {
            renderTabs()
        }
    }

    // ========== 书签与历史 ==========

    private fun addCurrentPageBookmark() {
        val url = currentWebView()?.url ?: ""
        val title = currentWebView()?.title ?: url
        if (url.isBlank()) {
            toast("当前页面无 URL")
            return
        }
        bookmarkStore.add(title, url)
        toast("已添加书签：$title")
    }

    private fun showBookmarks() {
        val list = bookmarkStore.list()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无书签"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 16f
                setPadding(dp(24), dp(48), dp(24), dp(48))
            })
        } else {
            list.forEach { bookmark ->
                container.addView(createActionCard(
                    iconRes = R.drawable.ic_bookmark,
                    title = bookmark.title,
                    subtitle = bookmark.url,
                    onClick = {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(bookmark.title)
                            .setMessage(bookmark.url)
                            .setPositiveButton("打开") { _, _ -> loadUrlInCurrentTab(bookmark.url) }
                            .setNegativeButton("删除") { _, _ ->
                                bookmarkStore.remove(bookmark.url)
                                toast("已删除书签")
                                showBookmarks()
                            }
                            .setNeutralButton("取消", null)
                            .show()
                    }
                ))
                container.addView(space(dp(8)))
            }
        }

        container.addView(space(dp(12)))
        container.addView(createButton("添加当前页") { addCurrentPageBookmark() })

        showFullScreenDialog("书签 · ${list.size}", container)
    }

    private fun showHistory() {
        val list = historyStore.list()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无历史记录"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 16f
                setPadding(dp(24), dp(48), dp(24), dp(48))
            })
        } else {
            list.forEach { item ->
                container.addView(createActionCard(
                    iconRes = R.drawable.ic_history,
                    title = item.title,
                    subtitle = item.url,
                    onClick = { loadUrlInCurrentTab(item.url) }
                ))
                container.addView(space(dp(8)))
            }
        }

        container.addView(space(dp(12)))
        container.addView(createButton("清空历史") {
            historyStore.clear()
            toast("历史已清空")
        })

        showFullScreenDialog("历史记录 · ${list.size}", container)
    }

    private fun loadUrlInCurrentTab(url: String) {
        currentWebView()?.loadUrl(url)
    }

    // ========== 电脑模式与无痕模式 ==========

    private fun toggleDesktopMode() {
        settings.desktopModeEnabled = !settings.desktopModeEnabled
        toast(if (settings.desktopModeEnabled) "电脑模式已开启" else "电脑模式已关闭")
        webViews.values.forEach { view ->
            view.settings.userAgentString = if (settings.desktopModeEnabled) {
                DESKTOP_UA
            } else {
                mobileUserAgent()
            }
        }
        currentWebView()?.reload()
    }

    private fun toggleIncognitoMode() {
        settings.incognitoEnabled = !settings.incognitoEnabled
        toast(if (settings.incognitoEnabled) "无痕模式已开启" else "无痕模式已关闭")
        val url = currentWebView()?.url ?: homeUrl()
        webViews.values.forEach {
            verificationModeByWebView.remove(it)
            it.destroy()
        }
        webViews.clear()
        tabTitles.clear()
        tabUrls.clear()
        tabOriginalUrls.clear()
        tabReadingMode.clear()
        tabReadingContent.clear()
        tabReadingChapters.clear()
        tabReadingCurrentIndex.clear()
        tabReadingPrevUrl.clear()
        tabReadingNextUrl.clear()
        tabReadingPendingUrl.clear()
        tabReadingAutoPage.clear()
        tabReadingAutoNext.clear()
        webViewContainer.removeAllViews()
        addNewTab(url)
    }

    private fun shareCurrentPage() {
        val view = currentWebView() ?: return toast("当前没有网页")
        val url = view.url ?: return toast("当前页面没有网址")
        val title = view.title?.takeIf { it.isNotBlank() } ?: url
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_TITLE, title)
        }, "分享网页"))
    }

    private fun copyCurrentUrl() {
        val url = currentWebView()?.url?.takeIf { it.isNotBlank() } ?: return toast("当前页面没有网址")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("网页地址", url))
        toast("网址已复制")
    }

    private fun toggleAutoTranslate() {
        settings.autoTranslateEnabled = !settings.autoTranslateEnabled
        toast(if (settings.autoTranslateEnabled) "自动翻译已开启" else "自动翻译已关闭")
        if (settings.autoTranslateEnabled) currentWebView()?.let { maybeAutoTranslate(it) }
    }

    private fun clearCurrentWebData() {
        val view = currentWebView() ?: return toast("当前没有网页")
        view.clearCache(true)
        view.clearHistory()
        WebStorage.getInstance().deleteOrigin(view.url ?: "")
        CookieManager.getInstance().flush()
        toast("当前网页缓存已清除")
        view.reload()
    }

    private fun saveCurrentPage() {
        val view = currentWebView() ?: return toast("当前没有网页")
        val url = view.url?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return toast("当前页面无法保存")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val base = (view.title ?: "nebula_page").replace(Regex("[^\u4e00-\u9fa5A-Za-z0-9._-]"), "_").take(60).ifBlank { "nebula_page" }
        val path = File(dir, "$base-${System.currentTimeMillis()}.mht").absolutePath
        try {
            view.saveWebArchive(path, false) { file ->
                runOnUiThread {
                    if (file != null) toast("网页已保存到 Downloads") else toast("网页保存失败")
                }
            }
        } catch (e: Exception) {
            toast("网页保存失败：${e.message ?: "未知错误"}")
        }
    }

    // ========== 浏览器菜单（备用） ==========

    private fun showBrowserMenu() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(createActionCard(R.drawable.ic_bookmark_add, "添加书签", "收藏当前页面") { addCurrentPageBookmark() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_bookmark, "书签", "管理书签") { showBookmarks() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_history, "历史记录", "浏览历史") { showHistory() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_script, "脚本管理", "用户脚本") { showScriptManager() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_adblock, "广告拦截", "过滤规则") { showAdBlockManager() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_mcp, "MCP", "远端服务器") { McpInfoSheet.newInstance().show(supportFragmentManager, "mcp_info") })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_search, "搜索引擎", "切换默认搜索引擎") { showSearchEngineSetting() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_search, "页内查找", "在当前网页中查找文字") { showFindInPage() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_article, "翻译此页", "翻译为简体中文") { translateCurrentPage(true) })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_article, "自动翻译", if (settings.autoTranslateEnabled) "已开启" else "已关闭") { toggleAutoTranslate() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_bookmark_add, "分享网页", "分享当前网址") { shareCurrentPage() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_bookmark_add, "复制网址", "复制当前页面地址") { copyCurrentUrl() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_refresh, "清除网页数据", "清除当前站点缓存与 WebView 数据") { clearCurrentWebData() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_download, "保存网页", "保存为 Web Archive 离线查看") { saveCurrentPage() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_palette, "自定义主题", "主页 HTML/CSS") { showThemeEditor() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_desktop, "电脑模式", if (settings.desktopModeEnabled) "已开启" else "已关闭") { toggleDesktopMode() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_incognito, "无痕模式", if (settings.incognitoEnabled) "已开启" else "已关闭") { toggleIncognitoMode() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_article, "阅读模式", "提取正文") { toggleReadingMode() })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_newtab, "新建标签页", "打开主页") { addNewTab(homeUrl()) })
        container.addView(space(dp(8)))
        container.addView(createActionCard(R.drawable.ic_download, "下载管理", "查看下载文件") { showDownloadManager() })

        showFullScreenDialog("Nebula Browser", container)
    }

    // ========== 页内查找 ==========

    private fun showFindInPage() {
        val webView = currentWebView() ?: run {
            toast("当前没有可查找的网页")
            return
        }

        val input = EditText(this).apply {
            hint = "输入要查找的文字"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("页内查找")
            .setView(input)
            .setNegativeButton("关闭") { _, _ ->
                webView.clearMatches()
            }
            .setNeutralButton("清除") { _, _ ->
                input.text.clear()
                webView.clearMatches()
            }
            .setPositiveButton("查找") { _, _ ->
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isEmpty()) {
                    webView.clearMatches()
                    toast("请输入查找内容")
                } else {
                    webView.findAllAsync(query)
                    webView.findNext(false)
                }
            }
            .create()

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = input.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    webView.findAllAsync(query)
                    webView.findNext(false)
                }
                true
            } else false
        }

        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_F) {
            showFindInPage()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ========== 搜索引擎设置 ==========

    private data class CustomEngine(val id: String, val name: String, val template: String)

    private fun customEngines(): List<CustomEngine> = try {
        val arr = org.json.JSONArray(settings.customSearchEnginesJson)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CustomEngine(o.getString("id"), o.getString("name"), o.getString("template"))
        }
    } catch (_: Exception) { emptyList() }

    private fun saveCustomEngines(list: List<CustomEngine>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("template", it.template)) }
        settings.customSearchEnginesJson = arr.toString()
    }

    private fun showSearchEngineSetting() {
        val keys = searchEngineLabels.keys.toList()
        val labels = searchEngineLabels.values.toList()
        val currentEngine = settings.searchEngine
        val customs = customEngines()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        keys.forEachIndexed { index, key ->
            val isCurrent = currentEngine == key
            container.addView(createActionCard(
                iconRes = if (isCurrent) R.drawable.ic_bookmark else R.drawable.ic_search,
                title = labels[index],
                subtitle = if (isCurrent) "当前使用" else null,
                onClick = {
                    settings.searchEngine = key
                    refreshHomeIfNeeded()
                    toast("已切换：${labels[index]}")
                    showSearchEngineSetting()
                }
            ))
            container.addView(space(dp(8)))
        }

        if (customs.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = "自定义搜索引擎"
                textSize = 13f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(dp(2), dp(8), 0, dp(6))
            })
            customs.forEach { engine ->
                val engineKey = "custom:${engine.id}"
                val isCurrent = currentEngine == engineKey
                container.addView(createActionCard(
                    iconRes = if (isCurrent) R.drawable.ic_bookmark else R.drawable.ic_search,
                    title = engine.name,
                    subtitle = if (isCurrent) "当前使用 · ${engine.template}" else engine.template,
                    onClick = {
                        settings.searchEngine = engineKey
                        refreshHomeIfNeeded()
                        toast("已切换：${engine.name}")
                        showSearchEngineSetting()
                    }
                ))
                container.addView(space(dp(6)))
                container.addView(createButton("删除「${engine.name}」") {
                    saveCustomEngines(customEngines().filterNot { it.id == engine.id })
                    if (currentEngine == engineKey) settings.searchEngine = "bing"
                    toast("已删除")
                    showSearchEngineSetting()
                })
                container.addView(space(dp(8)))
            }
        }

        container.addView(space(dp(8)))
        container.addView(createButton("+ 添加自定义搜索引擎") { showAddCustomEngineDialog() })

        showFullScreenDialog("搜索引擎", container)
    }

    private fun showAddCustomEngineDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val nameBox = EditText(this).apply {
            hint = "名称，例如：知乎"
            textSize = 15f
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        detachFromParent(nameBox)
        container.addView(nameBox)
        container.addView(space(dp(12)))
        val templateBox = EditText(this).apply {
            hint = "搜索地址，用 {query} 代表关键词\n例如 https://www.zhihu.com/search?q={query}"
            textSize = 14f
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setSingleLine(false)
            minLines = 2
        }
        detachFromParent(templateBox)
        container.addView(templateBox)
        container.addView(space(dp(16)))
        container.addView(Button(this).apply {
            text = "保存"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setOnClickListener {
                val name = nameBox.text.toString().trim()
                val template = templateBox.text.toString().trim()
                if (name.isBlank() || template.isBlank()) {
                    toast("名称和地址都不能为空")
                    return@setOnClickListener
                }
                if (!template.contains("{query}")) {
                    toast("地址必须包含 {query} 占位符")
                    return@setOnClickListener
                }
                val id = UUID.randomUUID().toString()
                saveCustomEngines(customEngines() + CustomEngine(id, name, template))
                settings.searchEngine = "custom:$id"
                refreshHomeIfNeeded()
                toast("已添加并切换为：$name")
                showSearchEngineSetting()
            }
        })

        showFullScreenDialog("添加自定义搜索引擎", container)
    }

    // ========== 脚本管理 ==========

    private fun showScriptManager() {
        val list = scripts.list()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (list.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无脚本"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 16f
                setPadding(dp(24), dp(48), dp(24), dp(48))
            })
        } else {
            list.forEach { script ->
                val isWildcard = script.matches.size == 1 && script.matches[0] == "*://*/*"
                container.addView(createActionCard(
                    iconRes = R.drawable.ic_script,
                    title = script.name,
                    subtitle = if (isWildcard) "⚠️ 未限定网站，将在所有网页运行" else "匹配：${script.matches.joinToString(", ")}",
                    onClick = { showScriptDetail(script) }
                ))
                container.addView(space(dp(8)))
            }
        }

        container.addView(space(dp(12)))
        container.addView(createButton("本地导入") {
            scriptPicker.launch(arrayOf("text/*", "application/javascript", "application/x-javascript", "*/*"))
        })
        container.addView(space(dp(8)))
        container.addView(createButton("URL 导入") {
            askUrl("导入脚本 URL") { url -> importScriptUrl(url) }
        })
        container.addView(space(dp(8)))
        container.addView(createButton("新建脚本") { editScript(null) })
        container.addView(space(dp(8)))
        container.addView(createButton("去重（清理重复脚本）") {
            val removed = scripts.deduplicate()
            currentWebView()?.reload()
            toast(if (removed > 0) "已清理 $removed 个重复脚本" else "没有发现重复脚本")
            showScriptManager()
        })

        showFullScreenDialog("脚本管理 · ${list.size}", container)
    }

    private fun showScriptDetail(script: UserScriptStore.Script) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(TextView(this).apply {
            text = script.name
            textSize = 18f
            setTextColor(Color.parseColor("#111827"))
            typeface = Typeface.DEFAULT_BOLD
        })
        container.addView(space(dp(8)))
        container.addView(TextView(this).apply {
            text = "匹配：${script.matches.joinToString("\n")}\n运行：${script.runAt}\n状态：${if (script.enabled) "启用" else "停用"}"
            textSize = 14f
            setTextColor(Color.parseColor("#6B7280"))
        })
        container.addView(space(dp(16)))
        container.addView(createButton(if (script.enabled) "停用" else "启用") {
            scripts.setEnabled(script.id, !script.enabled)
            currentWebView()?.reload()
            toast("已${if (script.enabled) "停用" else "启用"}")
        })
        container.addView(space(dp(8)))
        container.addView(createButton("限制生效网站") { restrictScriptToDomain(script) })
        container.addView(space(dp(8)))
        container.addView(createButton("编辑") { editScript(script) })
        container.addView(space(dp(8)))
        container.addView(createButton("删除") {
            scripts.delete(script.id)
            currentWebView()?.reload()
            toast("脚本已删除")
        })

        showFullScreenDialog("脚本详情", container)
    }

    /**
     * 给"脚本在所有网页都生效"（比如新建标签页也弹欢迎页）这类问题提供一个不用手改代码的解法：
     * 直接改写脚本源码里 ==UserScript== 头部的 @match/@include 为指定域名，一步限定作用范围。
     * matches/runAt 都是每次读盘时从 source 里的头部重新解析的，所以这里必须真的改 source 本身，
     * 只改内存里的 Script 对象没用。
     */
    private fun restrictScriptToDomain(script: UserScriptStore.Script) {
        val input = EditText(this).apply {
            hint = "例如：www.qidian.com（不用写 https://）"
            textSize = 14f
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("限制「${script.name}」生效网站")
            .setMessage("当前匹配范围：${script.matches.joinToString(", ")}\n\n输入一个域名，保存后脚本只会在这个网站运行（不再在新标签页/其它网站弹出）。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val domain = input.text.toString().trim()
                    .removePrefix("https://").removePrefix("http://")
                    .substringBefore("/")
                if (domain.isBlank()) {
                    toast("域名不能为空")
                } else {
                    val newPattern = "*://$domain/*"
                    val newSource = rewriteScriptMatchDirective(script.source, newPattern)
                    scripts.save(script.copy(source = newSource, matches = listOf(newPattern)))
                    currentWebView()?.reload()
                    toast("已限制到 $domain")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun rewriteScriptMatchDirective(source: String, newPattern: String): String {
        val start = source.indexOf("==UserScript==")
        val end = source.indexOf("==/UserScript==")
        if (start < 0 || end <= start) {
            // 脚本压根没有可解析的头部——这正是它默认落到 *://*/* 到处生效的常见原因之一，
            // 直接给它补一个正确的头部，而不是留着无法收窄范围。
            return "// ==UserScript==\n// @name        Imported Script\n// @match       $newPattern\n// @run-at      document-start\n// ==/UserScript==\n\n$source"
        }
        val header = source.substring(start, end)
        val keptLines = header.lines().filterNot {
            val t = it.trim()
            t.startsWith("// @match") || t.startsWith("//@match") ||
                t.startsWith("// @include") || t.startsWith("//@include")
        }.toMutableList()
        val nameIdx = keptLines.indexOfFirst { it.contains("@name") }
        val insertAt = (if (nameIdx >= 0) nameIdx + 1 else 1).coerceIn(0, keptLines.size)
        keptLines.add(insertAt, "// @match       $newPattern")
        return source.substring(0, start) + keptLines.joinToString("\n") + source.substring(end)
    }

    private fun editScript(existing: UserScriptStore.Script?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val box = EditText(this).apply {
            setText(existing?.source ?: "// ==UserScript==\n// @name        My Script\n// @match       *://*/*\n// @run-at      document-start\n// ==/UserScript==\n\n")
            setSingleLine(false)
            minLines = 14
            textSize = 13f
            setTextColor(Color.parseColor("#111827"))
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.TOP
            setSelection(text.length)
        }
        detachFromParent(box)
        container.addView(box)
        container.addView(space(dp(16)))
        container.addView(Button(this).apply {
            text = "保存"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setOnClickListener {
                if (existing == null) {
                    val (script, isNew) = scripts.importText(box.text.toString(), "My Script")
                    currentWebView()?.reload()
                    toast(if (isNew) "脚本已保存" else "已存在相同脚本「${script.name}」，未重复添加")
                } else {
                    scripts.save(existing.copy(source = box.text.toString()))
                    currentWebView()?.reload()
                    toast("脚本已保存")
                }
            }
        })

        showFullScreenDialog(if (existing == null) "新建脚本" else "编辑脚本", container)
    }

    // ========== 广告拦截 ==========

    private fun showAdBlockManager() {
        val subs = adBlock.subscriptions()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (subs.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无订阅"
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 16f
                setPadding(dp(24), dp(48), dp(24), dp(48))
            })
        } else {
            subs.forEach { sub ->
                container.addView(createActionCard(
                    iconRes = R.drawable.ic_adblock,
                    title = sub.name,
                    subtitle = "${sub.ruleCount} 条规则",
                    onClick = { showFilterDetail(sub) }
                ))
                container.addView(space(dp(8)))
            }
        }

        container.addView(space(dp(12)))
        container.addView(createButton("URL 订阅") {
            askUrl("过滤订阅 URL") { url -> importFilterUrl(url) }
        })
        container.addView(space(dp(8)))
        container.addView(createButton("文件导入") {
            filterPicker.launch(arrayOf("text/*", "text/plain", "*/*"))
        })
        container.addView(space(dp(8)))
        container.addView(createButton("更新全部") {
            lifecycleScope.launch {
                val count = withContext(Dispatchers.IO) { adBlock.updateAll() }
                toast("已更新 $count 个订阅")
                currentWebView()?.reload()
            }
        })
        container.addView(space(dp(8)))
        container.addView(createButton("自定义规则") { editCustomRules() })
        container.addView(space(dp(8)))
        container.addView(createButton("去重（清理重复订阅与规则）") {
            val removedSubs = adBlock.deduplicateSubscriptions()
            val removedLines = adBlock.deduplicateCustomRules()
            currentWebView()?.reload()
            val msg = if (removedSubs > 0 || removedLines > 0)
                "已清理 $removedSubs 个重复订阅，$removedLines 条重复规则"
            else "没有发现重复内容"
            toast(msg)
            showAdBlockManager()
        })
        container.addView(space(dp(8)))
        container.addView(createButton(if (settings.adBlockEnabled) "关闭总开关" else "开启总开关") {
            settings.adBlockEnabled = !settings.adBlockEnabled
            currentWebView()?.reload()
            toast(if (settings.adBlockEnabled) "广告拦截已开启" else "广告拦截已关闭")
        })

        showFullScreenDialog("广告拦截 · ${subs.sumOf { it.ruleCount }} 条规则", container)
    }

    private fun showFilterDetail(sub: AdBlockStore.Subscription) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(TextView(this).apply {
            text = sub.name
            textSize = 18f
            setTextColor(Color.parseColor("#111827"))
            typeface = Typeface.DEFAULT_BOLD
        })
        container.addView(space(dp(8)))
        container.addView(TextView(this).apply {
            text = "规则：${sub.ruleCount}\n来源：${sub.url}"
            textSize = 14f
            setTextColor(Color.parseColor("#6B7280"))
        })
        container.addView(space(dp(16)))
        container.addView(createButton(if (sub.enabled) "停用" else "启用") {
            adBlock.setEnabled(sub.id, !sub.enabled)
            currentWebView()?.reload()
            toast("已${if (sub.enabled) "停用" else "启用"}")
        })
        container.addView(space(dp(8)))
        container.addView(createButton("删除") {
            adBlock.remove(sub.id)
            currentWebView()?.reload()
            toast("订阅已删除")
        })

        showFullScreenDialog("订阅详情", container)
    }

    private fun editCustomRules() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val box = EditText(this).apply {
            setText(adBlock.customRules())
            setSingleLine(false)
            minLines = 14
            textSize = 13f
            setTextColor(Color.parseColor("#111827"))
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.TOP
        }
        detachFromParent(box)
        container.addView(box)
        container.addView(space(dp(16)))
        container.addView(Button(this).apply {
            text = "保存"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setOnClickListener {
                adBlock.setCustomRules(box.text.toString())
                currentWebView()?.reload()
                toast("规则已保存")
            }
        })

        showFullScreenDialog("自定义 Adblock 规则", container)
    }

    // ========== 导入 ==========

    private fun askUrl(title: String, callback: (String) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val box = EditText(this).apply {
            hint = "https://..."
            textSize = 15f
            setTextColor(Color.parseColor("#111827"))
            setHintTextColor(Color.parseColor("#9CA3AF"))
            setBackgroundColor(Color.parseColor("#F9FAFB"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        detachFromParent(box)
        container.addView(box)
        container.addView(space(dp(16)))
        container.addView(Button(this).apply {
            text = "导入"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setOnClickListener {
                val u = box.text.toString().trim()
                if (u.isNotBlank()) callback(u)
            }
        })

        showFullScreenDialog(title, container)
    }

    private fun importScriptFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                val (result, isNew) = withContext(Dispatchers.IO) { scripts.importFile(uri) }
                toast(if (isNew) "已导入：${result.name}" else "已存在相同脚本「${result.name}」，未重复导入")
                currentWebView()?.reload()
            } catch (e: Exception) {
                toast("导入失败：${e.javaClass.simpleName} ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun importScriptUrl(url: String) {
        lifecycleScope.launch {
            try {
                val (result, isNew) = withContext(Dispatchers.IO) { scripts.importUrl(url) }
                toast(if (isNew) "已导入：${result.name}" else "已存在相同脚本「${result.name}」，未重复导入")
                currentWebView()?.reload()
            } catch (e: Exception) {
                toast("导入脚本失败：${e.message}")
            }
        }
    }

    private fun importFilterFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { adBlock.importSubscriptionFile("本地订阅", uri) }
                toast("已导入 ${result.ruleCount} 条规则")
                currentWebView()?.reload()
            } catch (e: Exception) {
                toast("导入过滤器失败：${e.message}")
            }
        }
    }

    private fun importFilterUrl(url: String) {
        lifecycleScope.launch {
            try {
                val name = URL(url).path.substringAfterLast('/').ifBlank { "远程订阅" }
                val result = withContext(Dispatchers.IO) { adBlock.importSubscriptionFromUrl(name, url) }
                toast("已导入 ${result.ruleCount} 条规则")
                currentWebView()?.reload()
            } catch (e: Exception) {
                toast("订阅失败：${e.message}")
            }
        }
    }

    /** Built-in GitHub UI Chinese layer. README/issues/code prose still use the
     * multi-engine page translator; this layer only localizes stable UI labels and
     * deliberately skips pre/code blocks.
     */
    private fun installGithubChineseLayer(view: WebView, url: String) {
        val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        if (!(host == "github.com" || host.endsWith(".github.com"))) return
        val js = runCatching {
            assets.open("nebula_github_i18n.js").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return
        view.evaluateJavascript(js, null)
        view.postDelayed({
            if (view.isAttachedToWindow) {
                view.evaluateJavascript("window.__nebulaGithubI18n&&window.__nebulaGithubI18n.scan(document.body);", null)
            }
        }, 900L)
    }

    // ========== 自动翻译 / 人机验证兼容 ==========

    /**
     * Translate the visible page text without trusting document.documentElement.lang.
     *
     * A number of multilingual sites (including documentation sites) declare a
     * Chinese UI language while their article body is English. The old implementation
     * treated the declared lang as authoritative and could therefore incorrectly show
     * "页面已经是中文".  We now detect the language from visible text and attach a
     * stable data-nebula-translate-id to each candidate text node before the network
     * request starts. This also prevents DOM changes during translation from mapping
     * results to the wrong node.
     */
    private fun maybeAutoTranslate(
        view: WebView,
        force: Boolean = false,
        onFinished: ((Boolean, String?) -> Unit)? = null
    ) {
        val engine = runCatching {
            assets.open("nebula_immersive_translate.js").bufferedReader().use { it.readText() }
        }.getOrElse {
            onFinished?.invoke(false, "翻译引擎加载失败")
            return
        }

        val js = """
            (function(){
              try {
                $engine
                window.NebulaImmersiveTranslate.restore();
                window.NebulaImmersiveTranslate.clearSourceMarks();
                return window.NebulaImmersiveTranslate.collect(${if (force) "true" else "false"});
              } catch(e) {
                return JSON.stringify({items:[],foreign:0,total:0,translatable:false,error:String(e)});
              }
            })();
        """.trimIndent()

        view.evaluateJavascript(js) { raw ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val decoded = decodeJsStringResult(raw)
                    if (decoded.isBlank()) {
                        withContext(Dispatchers.Main) { onFinished?.invoke(false, "页面没有可翻译内容") }
                        return@launch
                    }
                    val obj = org.json.JSONObject(decoded)
                    val arr = obj.optJSONArray("items")
                    val foreign = obj.optInt("foreign")
                    if (arr == null || arr.length() == 0 || (!obj.optBoolean("translatable") && !force)) {
                        withContext(Dispatchers.Main) {
                            onFinished?.invoke(false, if (foreign == 0) "没有检测到需要翻译的外文正文" else "没有找到可翻译的正文")
                        }
                        return@launch
                    }

                    val all = ArrayList<TranslationManager.Segment>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val text = item.optString("text").trim()
                        val id = item.optInt("id", i + 1)
                        if (text.isNotBlank()) all += TranslationManager.Segment(id, text)
                    }
                    if (all.isEmpty()) {
                        withContext(Dispatchers.Main) { onFinished?.invoke(false, "没有找到可翻译的正文") }
                        return@launch
                    }

                    val provider = when (settings.translationProvider) {
                        "google" -> TranslationManager.Provider.GOOGLE
                        "bing" -> TranslationManager.Provider.BING
                        "tencent" -> TranslationManager.Provider.TENCENT
                        else -> TranslationManager.Provider.AUTO
                    }
                    val translated = ArrayList<Pair<Int, String>>()
                    // TranslationManager now performs the provider fallback and parallel
                    // paragraph requests internally. Do not serialize four-paragraph
                    // chunks here; that was the main reason long GitHub pages felt slow.
                    val result = translationManager.translateBatch(all, provider = provider)
                    if (result != null) {
                        all.forEachIndexed { index, seg ->
                            val translatedText = result.translations.getOrNull(index)?.trim()
                            if (!translatedText.isNullOrBlank() && !translatedText.equals(seg.text, true)) {
                                translated += seg.id to translatedText
                            }
                        }
                    }

                    if (translated.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            onFinished?.invoke(false, "翻译服务暂时不可用，请检查网络后重试")
                        }
                        return@launch
                    }

                    val payload = org.json.JSONArray().apply {
                        translated.forEach { (id, text) ->
                            put(org.json.JSONObject().apply {
                                put("id", id)
                                put("text", text)
                            })
                        }
                    }
                    withContext(Dispatchers.Main) {
                        val applyJs = """
                            (function(items){
                              try {
                                var data=items||[];
                                var applied=window.NebulaImmersiveTranslate.apply(data);
                                window.__nebulaTranslated=true;
                                window.__nebulaTranslationLastCount=applied;
                                return applied;
                              } catch(e){ return 0; }
                            })($payload);
                        """.trimIndent()
                        view.evaluateJavascript(applyJs, null)
                        onFinished?.invoke(true, "已翻译 ${translated.size} 段")
                    }
                } catch (e: Exception) {
                    Log.w("NebulaTranslate", "translate page failed", e)
                    withContext(Dispatchers.Main) {
                        onFinished?.invoke(false, "翻译失败：${e.message ?: "未知错误"}")
                    }
                }
            }
        }
    }

    private fun translateCurrentPage(manual: Boolean = false) {
        val view = currentWebView() ?: return toast("当前没有网页")
        if (manual) toast("正在读取网页正文并翻译…")
        // Manual translation is deliberately more permissive than auto translation.
        // This fixes pages whose HTML language declaration is Chinese while the body
        // is actually English (common on localized documentation sites).
        maybeAutoTranslate(view, force = manual) { success, message ->
            if (manual && !message.isNullOrBlank()) toast(message)
        }
    }

    private fun showTranslationSettings() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(TextView(this).apply {
            text = "用于「翻译此页」和「自动翻译」的整页翻译引擎"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, 0, 0, dp(12))
        })

        val providers = listOf(
            "auto" to "智能翻译（腾讯 → 必应 → 谷歌）",
            "tencent" to "腾讯 Transmart",
            "bing" to "必应翻译",
            "google" to "谷歌翻译"
        )
        providers.forEach { (id, label) ->
            val isCurrent = settings.translationProvider == id
            container.addView(createActionCard(
                R.drawable.ic_translate_settings,
                label,
                if (isCurrent) "当前使用" else when (id) {
                    "auto" -> "多引擎并发/故障切换，适合 GitHub、文档和动态网页"
                    "tencent" -> "优先腾讯，失败后自动切换必应/谷歌"
                    "bing" -> "优先必应，失败后自动切换腾讯/谷歌"
                    else -> "优先谷歌，失败后自动切换腾讯/必应"
                }
            ) {
                settings.translationProvider = id
                toast("已切换：$label")
                showTranslationSettings()
            })
            container.addView(space(dp(8)))
        }

        showFullScreenDialog("翻译设置", container)
    }

    /**
     * 不绕过 CAPTCHA/Cloudflare 等人机验证。这里只做兼容处理：
     * 检测挑战页后暂时停止广告/网络规则对该页面的 GET/HEAD 资源拦截，并保持 JS/Cookie/DOM 存储，
     * 避免 Nebula 自己的拦截器误伤验证脚本。用户仍需按网站要求完成验证。
     */
    private fun installVerificationCompatibility(view: WebView) {
        val js = """
          (function(){
            if(window.__nebulaVerifyCompat)return;
            window.__nebulaVerifyCompat=true;
            function detect(){
              var t=(document.title+' '+(document.body?document.body.innerText:'' )).toLowerCase();
              var hit=/captcha|verify you are human|human verification|checking your browser|security check|cloudflare|hcaptcha|recaptcha|challenge/i.test(t);
              if(hit){
                document.documentElement.setAttribute('data-nebula-verification','1');
                console.log('[Nebula] 检测到网站人机验证页面，已启用兼容模式');
              }
            }
            detect();setTimeout(detect,1500);setTimeout(detect,4000);
          })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    // ========== 导航与搜索 ==========

    private fun loadInput(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = if (t.startsWith("http://") || t.startsWith("https://")) {
            t
        } else if (t.contains(".") && !t.contains(" ")) {
            "https://$t"
        } else {
            val engineId = settings.searchEngine
            val template = searchEngines[engineId]
                ?: customEngines().find { "custom:${it.id}" == engineId }?.template
                ?: searchEngines.getValue("bing")
            template.replace("{query}", Uri.encode(t))
        }
        currentWebView()?.loadUrl(url)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    // ========== WebViewBridge 接口 ==========

    override fun navigate(url: String) {
        runOnUiThread { currentWebView()?.loadUrl(url) }
    }

    override fun currentUrl(): String = cachedUrl

    override fun evalJs(code: String, onResult: (String) -> Unit) {
        currentWebView()?.evaluateJavascript(code) { onResult(it ?: "null") }
    }

    override fun getHtml(onResult: (String) -> Unit) {
        currentWebView()?.evaluateJavascript("(function(){return document.documentElement.outerHTML;})();") { onResult(it ?: "null") }
    }

    override fun aiNewTab(url: String) { runOnUiThread { addNewTab(url) } }

    override fun aiBack() { runOnUiThread { currentWebView()?.goBack() } }

    override fun aiDownload(url: String): String {
        val id = downloadHelper.download(url, userAgent = currentWebView()?.settings?.userAgentString)
        return if (id != null) {
            runOnUiThread { toast("Agent 已发起下载") }
            "download started (id=$id)"
        } else {
            "download failed to start"
        }
    }

    override fun aiListTabs(): String {
        val arr=org.json.JSONArray()
        for ((id, _) in webViews) {
            arr.put(org.json.JSONObject().put("id",id).put("title",tabTitles[id] ?: "").put("url",tabUrls[id] ?: "").put("active",id==currentTabId))
        }
        return arr.toString()
    }

    /**
     * Collect lightweight readable context from every live tab. This method is only
     * invoked on the Android main thread by ToolRegistry; WebView.evaluateJavascript
     * is therefore never called from MCP/IO threads. Hidden tabs remain hidden and
     * are not navigated or otherwise mutated.
     */
    override fun aiAllTabsContext(onResult: (String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { aiAllTabsContext(onResult) }
            return
        }
        if (webViews.isEmpty()) { onResult("[]"); return }
        val result = org.json.JSONArray()
        val pending = java.util.concurrent.atomic.AtomicInteger(webViews.size)
        val lock = Any()
        webViews.forEach { (id, view) ->
            val js = """(function(){return JSON.stringify({id:${org.json.JSONObject.quote(id)},title:document.title||'',url:location.href,text:(document.body&&document.body.innerText||'').replace(/\n{3,}/g,'\n\n').slice(0,12000)})})()"""
            view.evaluateJavascript(js) { raw ->
                val item = try { org.json.JSONObject(decodeJsStringResult(raw)) } catch (_: Exception) {
                    org.json.JSONObject().put("id", id).put("title", tabTitles[id] ?: "").put("url", tabUrls[id] ?: "").put("text", "")
                }
                synchronized(lock) {
                    result.put(item)
                    if (pending.decrementAndGet() == 0) onResult(result.toString())
                }
            }
        }
    }

    private fun bootstrapDeepSeekScripts() {
        val existingIds = scripts.list().map { it.id }.toSet()
        if ("dspp-mainworld" !in existingIds) {
            runCatching {
                val body = assets.open("dspp_mainworld.js").bufferedReader().use { it.readText() }
                scripts.save(
                    UserScriptStore.Script(
                        id = "dspp-mainworld",
                        name = "DeepSeek++ MainWorld Bridge",
                        source = wrapAsUserScript("DeepSeek++ MainWorld Bridge", "*://chat.deepseek.com/*", "document-start", body),
                        enabled = settings.deepSeekPlusPlusEnabled,
                        matches = listOf("*://chat.deepseek.com/*"),
                        runAt = "document-start"
                    )
                )
            }
        }
        if ("dspp-content" !in existingIds) {
            runCatching {
                val body = assets.open("dspp_content.js").bufferedReader().use { it.readText() }
                scripts.save(
                    UserScriptStore.Script(
                        id = "dspp-content",
                        name = "DeepSeek++ Content Bridge",
                        source = wrapAsUserScript("DeepSeek++ Content Bridge", "*://chat.deepseek.com/*", "document-start", body),
                        enabled = settings.deepSeekPlusPlusEnabled,
                        matches = listOf("*://chat.deepseek.com/*"),
                        runAt = "document-start"
                    )
                )
            }
        }
    }

    private fun wrapAsUserScript(name: String, matchPattern: String, runAt: String, body: String): String {
        val header = buildString {
            appendLine("// ==UserScript==")
            appendLine("// @name         $name")
            appendLine("// @match        $matchPattern")
            appendLine("// @run-at       $runAt")
            appendLine("// ==/UserScript==")
        }
        return header + body
    }

    fun setDeepSeekPlusPlusEnabled(enabled: Boolean) {
        settings.deepSeekPlusPlusEnabled = enabled
        bootstrapDeepSeekScripts()
        scripts.setEnabled("dspp-mainworld", enabled)
        scripts.setEnabled("dspp-content", enabled)
        currentWebView()?.reload()
    }

    fun reapplyScriptInjection() {
        currentWebView()?.reload()
    }

    override fun onBackPressed() {
        if (customView != null) { exitCustomView(); return }
        val current = currentWebView()
        if (current != null && current.canGoBack()) {
            current.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        videoButtonHandler.removeCallbacks(videoButtonProbe)
        resourceSnifferUpdater = null
        exitCustomView()
        pendingFileChooser?.onReceiveValue(null)
        pendingFileChooser = null
        pendingFileChooserWebView = null
        pendingUserScriptImports.clear()
        downloadHelper.destroy()
        (application as NebulaApp).toolRegistry.detachBridge()
        scriptInjector.clear()
        webViews.values.forEach {
            verificationModeByWebView.remove(it)
            it.destroy()
        }
        webViews.clear()
        verificationModeByWebView.clear()
        super.onDestroy()
    }
}