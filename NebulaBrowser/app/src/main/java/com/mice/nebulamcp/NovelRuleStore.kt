package com.mice.nebulamcp

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 小说阅读净化规则：只负责网页阅读模式中的 DOM 清理，不参与普通浏览请求拦截。
 * 支持 JSON 规则与每行一个 CSS selector 的纯文本规则。
 */
class NovelRuleStore(context: Context) {
    data class Rule(val name: String, val selectors: List<String>, val enabled: Boolean = true)

    private val prefs = context.getSharedPreferences("nebula_novel_rules", Context.MODE_PRIVATE)
    private val key = "rules"

    init {
        if (prefs.getString(key, null).isNullOrBlank()) {
            saveRules(defaultRules())
        }
    }

    fun list(): List<Rule> {
        val raw = prefs.getString(key, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val selectors = o.optJSONArray("selectors")?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf(String::isNotBlank) }
                } ?: emptyList()
                Rule(o.optString("name", "未命名规则"), selectors, o.optBoolean("enabled", true))
            }
        }.getOrDefault(emptyList())
    }

    fun enabledSelectors(): List<String> = list().filter { it.enabled }.flatMap { it.selectors }.distinct()

    @Synchronized fun saveRules(rules: List<Rule>) {
        val arr = JSONArray()
        rules.forEach { rule ->
            val o = JSONObject()
            o.put("name", rule.name)
            o.put("enabled", rule.enabled)
            o.put("selectors", JSONArray(rule.selectors.distinct()))
            arr.put(o)
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    @Synchronized fun addRule(name: String, selectors: List<String>) {
        val cleaned = selectors.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleaned.isEmpty()) return
        saveRules(list() + Rule(name.ifBlank { "自定义规则" }, cleaned, true))
    }

    fun remove(index: Int) {
        val rules = list().toMutableList()
        if (index in rules.indices) {
            rules.removeAt(index)
            saveRules(rules)
        }
    }

    fun setEnabled(index: Int, enabled: Boolean) {
        val rules = list().toMutableList()
        if (index in rules.indices) {
            rules[index] = rules[index].copy(enabled = enabled)
            saveRules(rules)
        }
    }

    fun importText(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return 0
        val imported = runCatching { parseJson(trimmed) }.getOrElse { parseLines(trimmed) }
        if (imported.isEmpty()) return 0
        saveRules(list() + imported)
        return imported.size
    }

    private fun parseJson(text: String): List<Rule> {
        val root = JSONArray(text)
        return (0 until root.length()).mapNotNull { i ->
            val o = root.optJSONObject(i) ?: return@mapNotNull null
            val selectors = o.optJSONArray("selectors")?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it).trim().takeIf(String::isNotBlank) }
            } ?: o.optString("selector").split(',').map { it.trim() }.filter { it.isNotBlank() }
            if (selectors.isEmpty()) null else Rule(o.optString("name", "导入规则"), selectors, o.optBoolean("enabled", true))
        }
    }

    private fun parseLines(text: String): List<Rule> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("//") }
        .map { Rule("导入规则", listOf(it), true) }
        .toList()

    private fun defaultRules(): List<Rule> = listOf(
        Rule("常见广告", listOf(".ad", ".ads", ".advert", ".advertisement", ".adsbygoogle", "[class*=\"advert\"]")),
        Rule("弹窗与浮层", listOf(".popup", ".pop-up", ".modal", ".overlay", ".mask")),
        Rule("推荐与下载", listOf(".recommend", ".recommend-list", ".related", ".download-app", ".app-download", ".share")),
        Rule("评论与无关区域", listOf(".comments", ".comment-list", ".sidebar", ".footer", "aside"))
    )
}
