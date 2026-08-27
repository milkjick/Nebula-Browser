package com.mice.nebulamcp

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nebula_mcp_prefs", Context.MODE_PRIVATE)
    var mcpServiceEnabled: Boolean get() = prefs.getBoolean(KEY_MCP_ENABLED, true); set(v) = prefs.edit().putBoolean(KEY_MCP_ENABLED, v).apply()
    var lanAccessEnabled: Boolean get() = prefs.getBoolean(KEY_LAN_ENABLED, false); set(v) = prefs.edit().putBoolean(KEY_LAN_ENABLED, v).apply()
    var deepSeekPlusPlusEnabled: Boolean get() = prefs.getBoolean(KEY_DSPP_ENABLED, true); set(v) = prefs.edit().putBoolean(KEY_DSPP_ENABLED, v).apply()
    var githubToken: String get() = prefs.getString(KEY_GITHUB_TOKEN, "") ?: ""; set(v) = prefs.edit().putString(KEY_GITHUB_TOKEN, v).apply()
    var workspacePath: String get() = prefs.getString(KEY_WORKSPACE, "") ?: ""; set(v) = prefs.edit().putString(KEY_WORKSPACE, v).apply()
    var remoteServersJson: String get() = prefs.getString(KEY_REMOTE_SERVERS, "[]") ?: "[]"; set(v) = prefs.edit().putString(KEY_REMOTE_SERVERS, v).apply()
    var mcpAutoDiscoveryEnabled: Boolean get() = prefs.getBoolean(KEY_MCP_AUTO_DISCOVERY, true); set(v) = prefs.edit().putBoolean(KEY_MCP_AUTO_DISCOVERY, v).apply()
    var adBlockEnabled: Boolean get() = prefs.getBoolean(KEY_ADBLOCK, true); set(v) = prefs.edit().putBoolean(KEY_ADBLOCK, v).apply()
    /** 拦截网页触发的"打开App"跳转（自定义 scheme / intent://），默认开启 */
    var blockAppRedirect: Boolean get() = prefs.getBoolean(KEY_BLOCK_APP_REDIRECT, true); set(v) = prefs.edit().putBoolean(KEY_BLOCK_APP_REDIRECT, v).apply()
    var novelPurifyEnabled: Boolean get() = prefs.getBoolean(KEY_NOVEL_PURIFY_ENABLED, true); set(v) = prefs.edit().putBoolean(KEY_NOVEL_PURIFY_ENABLED, v).apply()

    // ---- 阅读模式设置（对齐"阅读"App 的可调项） ----
    var readingFontSize: Int get() = prefs.getInt(KEY_READING_FONT_SIZE, 18); set(v) = prefs.edit().putInt(KEY_READING_FONT_SIZE, v).apply()
    var readingLineHeight: Float get() = prefs.getFloat(KEY_READING_LINE_HEIGHT, 1.8f); set(v) = prefs.edit().putFloat(KEY_READING_LINE_HEIGHT, v).apply()
    var readingSerifFont: Boolean get() = prefs.getBoolean(KEY_READING_SERIF, true); set(v) = prefs.edit().putBoolean(KEY_READING_SERIF, v).apply()
    /** "sepia" | "day" | "night" | "green" | "black" */
    var readingTheme: String get() = prefs.getString(KEY_READING_THEME, "sepia") ?: "sepia"; set(v) = prefs.edit().putString(KEY_READING_THEME, v).apply()
    var autoTranslateEnabled: Boolean get() = prefs.getBoolean(KEY_AUTO_TRANSLATE, true); set(v) = prefs.edit().putBoolean(KEY_AUTO_TRANSLATE, v).apply()
    /** "google" 或 "bing"，用于整页自动翻译流水线 */
    var translationProvider: String get() = prefs.getString(KEY_TRANSLATION_PROVIDER, "bing") ?: "bing"; set(v) = prefs.edit().putString(KEY_TRANSLATION_PROVIDER, v).apply()
    var humanVerificationCompatEnabled: Boolean get() = prefs.getBoolean(KEY_HUMAN_VERIFY_COMPAT, true); set(v) = prefs.edit().putBoolean(KEY_HUMAN_VERIFY_COMPAT, v).apply()
    var scriptsEnabled: Boolean get() = prefs.getBoolean(KEY_SCRIPTS, true); set(v) = prefs.edit().putBoolean(KEY_SCRIPTS, v).apply()
    var homePage: String get() = prefs.getString(KEY_HOME, "https://www.google.com/") ?: "https://www.google.com/"; set(v) = prefs.edit().putString(KEY_HOME, v).apply()

    /** 默认搜索引擎改为必应 */
    var searchEngine: String
        get() = prefs.getString(KEY_SEARCH_ENGINE, "bing") ?: "bing"
        set(v) = prefs.edit().putString(KEY_SEARCH_ENGINE, v).apply()

    /** JSON array of user-added custom search engines: [{"id","name","template"}] */
    var customSearchEnginesJson: String
        get() = prefs.getString(KEY_CUSTOM_SEARCH_ENGINES, "[]") ?: "[]"
        set(v) = prefs.edit().putString(KEY_CUSTOM_SEARCH_ENGINES, v).apply()

    var desktopModeEnabled: Boolean get() = prefs.getBoolean(KEY_DESKTOP_MODE, false); set(v) = prefs.edit().putBoolean(KEY_DESKTOP_MODE, v).apply()
    var incognitoEnabled: Boolean get() = prefs.getBoolean(KEY_INCOGNITO, false); set(v) = prefs.edit().putBoolean(KEY_INCOGNITO, v).apply()

    /** Nebula AI Agent configuration. API key is stored only in local SharedPreferences. */
    var aiProviderMode: String get() = prefs.getString(KEY_AI_PROVIDER_MODE, "web") ?: "web"; set(v) = prefs.edit().putString(KEY_AI_PROVIDER_MODE, v).apply()
    var aiWebProvider: String get() = prefs.getString(KEY_AI_WEB_PROVIDER, "DeepSeek Web") ?: "DeepSeek Web"; set(v) = prefs.edit().putString(KEY_AI_WEB_PROVIDER, v).apply()
    var aiWebUrl: String get() = prefs.getString(KEY_AI_WEB_URL, "https://chat.deepseek.com/") ?: "https://chat.deepseek.com/"; set(v) = prefs.edit().putString(KEY_AI_WEB_URL, v).apply()
    var aiWebInputSelector: String get() = prefs.getString(KEY_AI_WEB_INPUT_SELECTOR, "") ?: ""; set(v) = prefs.edit().putString(KEY_AI_WEB_INPUT_SELECTOR, v).apply()
    var aiWebSubmitSelector: String get() = prefs.getString(KEY_AI_WEB_SUBMIT_SELECTOR, "") ?: ""; set(v) = prefs.edit().putString(KEY_AI_WEB_SUBMIT_SELECTOR, v).apply()
    var aiWebResponseSelector: String get() = prefs.getString(KEY_AI_WEB_RESPONSE_SELECTOR, "") ?: ""; set(v) = prefs.edit().putString(KEY_AI_WEB_RESPONSE_SELECTOR, v).apply()
    var aiApiKey: String get() = prefs.getString(KEY_AI_API_KEY, "") ?: ""; set(v) = prefs.edit().putString(KEY_AI_API_KEY, v).apply()
    var aiModel: String get() = prefs.getString(KEY_AI_MODEL, "deepseek-chat") ?: "deepseek-chat"; set(v) = prefs.edit().putString(KEY_AI_MODEL, v).apply()
    var aiBaseUrl: String get() = prefs.getString(KEY_AI_BASE_URL, "https://api.deepseek.com") ?: "https://api.deepseek.com"; set(v) = prefs.edit().putString(KEY_AI_BASE_URL, v).apply()
    var aiRequireConfirmation: Boolean get() = prefs.getBoolean(KEY_AI_CONFIRM, true); set(v) = prefs.edit().putBoolean(KEY_AI_CONFIRM, v).apply()
    /** OpenAI-compatible local model endpoint, e.g. http://127.0.0.1:11434/v1 */
    var localAiBaseUrl: String get() = prefs.getString(KEY_LOCAL_AI_BASE_URL, "http://127.0.0.1:11434/v1") ?: "http://127.0.0.1:11434/v1"; set(v) = prefs.edit().putString(KEY_LOCAL_AI_BASE_URL, v).apply()
    var localAiModel: String get() = prefs.getString(KEY_LOCAL_AI_MODEL, "llama3.2") ?: "llama3.2"; set(v) = prefs.edit().putString(KEY_LOCAL_AI_MODEL, v).apply()
    var localAiApiKey: String get() = prefs.getString(KEY_LOCAL_AI_KEY, "") ?: ""; set(v) = prefs.edit().putString(KEY_LOCAL_AI_KEY, v).apply()
    var aiLongTaskBatchSize: Int get() = prefs.getInt(KEY_AI_LONG_BATCH, 5); set(v) = prefs.edit().putInt(KEY_AI_LONG_BATCH, v.coerceIn(1, 20)).apply()

    /** 自定义主页 HTML（片段或完整 HTML） */
    var customHomeHtml: String
        get() = prefs.getString(KEY_CUSTOM_HOME_HTML, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CUSTOM_HOME_HTML, v).apply()

    /** 自定义主页 CSS（独立样式表） */
    var customHomeCss: String
        get() = prefs.getString(KEY_CUSTOM_HOME_CSS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CUSTOM_HOME_CSS, v).apply()

    val token: String get() = prefs.getString(KEY_TOKEN, null) ?: generateToken().also { prefs.edit().putString(KEY_TOKEN, it).apply() }
    fun regenerateToken(): String = generateToken().also { prefs.edit().putString(KEY_TOKEN, it).apply() }

    private fun generateToken(): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val r = SecureRandom()
        return (1..32).map { chars[r.nextInt(chars.size)] }.joinToString("")
    }

    companion object {
        private const val KEY_MCP_ENABLED = "mcp_enabled"
        private const val KEY_LAN_ENABLED = "lan_enabled"
        private const val KEY_DSPP_ENABLED = "dspp_enabled"
        private const val KEY_GITHUB_TOKEN = "github_token"
        private const val KEY_WORKSPACE = "workspace_path"
        private const val KEY_REMOTE_SERVERS = "remote_servers"
        private const val KEY_MCP_AUTO_DISCOVERY = "mcp_auto_discovery"
        private const val KEY_TOKEN = "mcp_token"
        private const val KEY_ADBLOCK = "adblock_enabled"
        private const val KEY_BLOCK_APP_REDIRECT = "block_app_redirect"
        private const val KEY_NOVEL_PURIFY_ENABLED = "novel_purify_enabled"
        private const val KEY_READING_FONT_SIZE = "reading_font_size"
        private const val KEY_READING_LINE_HEIGHT = "reading_line_height"
        private const val KEY_READING_SERIF = "reading_serif_font"
        private const val KEY_READING_THEME = "reading_theme"
        private const val KEY_AUTO_TRANSLATE = "auto_translate_enabled"
        private const val KEY_TRANSLATION_PROVIDER = "translation_provider"
        private const val KEY_HUMAN_VERIFY_COMPAT = "human_verify_compat_enabled"
        private const val KEY_SCRIPTS = "scripts_enabled"
        private const val KEY_HOME = "home_page"
        private const val KEY_SEARCH_ENGINE = "search_engine"
        private const val KEY_CUSTOM_SEARCH_ENGINES = "custom_search_engines"
        private const val KEY_DESKTOP_MODE = "desktop_mode_enabled"
        private const val KEY_INCOGNITO = "incognito_enabled"
        private const val KEY_CUSTOM_HOME_HTML = "custom_home_html"
        private const val KEY_CUSTOM_HOME_CSS = "custom_home_css"
        private const val KEY_AI_PROVIDER_MODE = "ai_provider_mode"
        private const val KEY_AI_WEB_PROVIDER = "ai_web_provider"
        private const val KEY_AI_WEB_URL = "ai_web_url"
        private const val KEY_AI_WEB_INPUT_SELECTOR = "ai_web_input_selector"
        private const val KEY_AI_WEB_SUBMIT_SELECTOR = "ai_web_submit_selector"
        private const val KEY_AI_WEB_RESPONSE_SELECTOR = "ai_web_response_selector"
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_AI_BASE_URL = "ai_base_url"
        private const val KEY_AI_CONFIRM = "ai_require_confirmation"
        private const val KEY_LOCAL_AI_BASE_URL = "local_ai_base_url"
        private const val KEY_LOCAL_AI_MODEL = "local_ai_model"
        private const val KEY_LOCAL_AI_KEY = "local_ai_key"
        private const val KEY_AI_LONG_BATCH = "ai_long_task_batch"
    }
}