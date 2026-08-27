package com.mice.nebulamcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

/**
 * Mirrors the "MCP 连接信息" bottom sheet from the reference screenshots:
 * addresses, token, service/LAN/DeepSeek++ toggles, quick actions (copy
 * config/curl, status, restart), remote MCP server management, workspace
 * and GitHub token settings.
 */
class McpInfoSheet : BottomSheetDialogFragment() {

    private val app get() = requireActivity().application as NebulaApp
    private val settings get() = app.settings

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.sheet_mcp_info, container, false)
        val rowContainer = view.findViewById<LinearLayout>(R.id.rowContainer)
        view.findViewById<View>(R.id.btnClose).setOnClickListener { dismiss() }
        buildRows(rowContainer)
        return view
    }

    private fun buildRows(container: LinearLayout) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        // 本机地址（稳定）
        addActionRow(container, inflater,
            getString(R.string.local_address), localUrl()) {
            copyToClipboard(localUrl(), "本机地址")
        }

        // 局域网地址
        val lanSuffix = if (settings.lanAccessEnabled) "" else "（未开启）"
        addActionRow(container, inflater,
            getString(R.string.lan_address), "${lanUrl()}$lanSuffix") {
            if (settings.lanAccessEnabled) copyToClipboard(lanUrl(), "局域网地址")
            else Toast.makeText(requireContext(), "请先开启局域网访问", Toast.LENGTH_SHORT).show()
        }

        // Token
        addActionRow(container, inflater,
            getString(R.string.token_label), maskToken(settings.token)) {
            copyToClipboard(settings.token, "Token")
        }

        // MCP 服务 开关
        addSwitchRow(container, inflater,
            getString(R.string.mcp_service_label), stateLabel(settings.mcpServiceEnabled),
            settings.mcpServiceEnabled) { checked ->
            settings.mcpServiceEnabled = checked
            if (checked) app.startMcpServer() else app.stopMcpServer()
            buildRows(container)
        }

        // 局域网访问 开关
        addSwitchRow(container, inflater,
            getString(R.string.lan_access_label),
            if (settings.lanAccessEnabled) getString(R.string.enabled) else getString(R.string.disabled),
            settings.lanAccessEnabled) { checked ->
            settings.lanAccessEnabled = checked
            app.restartMcpServer()
            buildRows(container)
        }

        // 复制 MCP 配置（JSON）
        addActionRow(container, inflater, getString(R.string.copy_config_json), "") {
            copyToClipboard(mcpConfigJson(), "MCP 配置")
        }

        // 复制 curl 测试命令
        addActionRow(container, inflater, getString(R.string.copy_curl), "") {
            copyToClipboard(curlCommand(), "curl 命令")
        }

        // 查看状态与统计
        addActionRow(container, inflater, getString(R.string.view_status), "") {
            showStatusDialog()
        }

        // 重启 MCP 服务
        addActionRow(container, inflater, getString(R.string.restart_mcp), "") {
            app.restartMcpServer()
            Toast.makeText(requireContext(), "MCP 服务已重启", Toast.LENGTH_SHORT).show()
            buildRows(container)
        }

        // 远端 MCP 服务器管理
        addActionRow(container, inflater, getString(R.string.remote_mcp_manage),
            "${app.remoteMcpManager.configuredList().size} 个配置 / ${app.remoteMcpManager.list().count { it.discovered }} 个自动发现") {
            showRemoteServerDialog(container)
        }

        addSwitchRow(container, inflater,
            getString(R.string.mcp_auto_discovery_label),
            if (settings.mcpAutoDiscoveryEnabled) getString(R.string.enabled) else getString(R.string.disabled),
            settings.mcpAutoDiscoveryEnabled) { checked ->
            settings.mcpAutoDiscoveryEnabled = checked
            buildRows(container)
        }

        addActionRow(container, inflater, getString(R.string.mcp_remote_diagnostics), "测试所有远端 MCP、认证与工具列表") {
            showRemoteDiagnosticsDialog()
        }

        // DeepSeek++ 模式 开关
        addSwitchRow(container, inflater,
            getString(R.string.dspp_mode_label), stateLabel(settings.deepSeekPlusPlusEnabled),
            settings.deepSeekPlusPlusEnabled) { checked ->
            (activity as? MainActivity)?.setDeepSeekPlusPlusEnabled(checked)
            buildRows(container)
        }

        // 工作区
        val workspace = settings.workspacePath.ifBlank {
            requireContext().getExternalFilesDir(null)?.path + "/Workspace"
        }
        addActionRow(container, inflater, getString(R.string.workspace_label), workspace) {
            showWorkspaceDialog(container)
        }

        // GitHub Token 设置
        val githubSubtitle = if (settings.githubToken.isBlank()) getString(R.string.not_configured) else "已配置 · GitHub 工具已启用"
        addActionRow(container, inflater, getString(R.string.github_token_label), githubSubtitle) {
            showGithubTokenDialog(container)
        }
    }

    // ---------------- row builders ----------------

    private fun addActionRow(
        parent: LinearLayout, inflater: LayoutInflater,
        title: String, subtitle: String, onClick: () -> Unit
    ) {
        val row = inflater.inflate(R.layout.item_mcp_row, parent, false)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        val sub = row.findViewById<TextView>(R.id.rowSubtitle)
        if (subtitle.isBlank()) sub.visibility = View.GONE else sub.text = subtitle
        row.setOnClickListener { onClick() }
        parent.addView(row)
    }

    private fun addSwitchRow(
        parent: LinearLayout, inflater: LayoutInflater,
        title: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit
    ) {
        val row = inflater.inflate(R.layout.item_mcp_switch_row, parent, false)
        row.findViewById<TextView>(R.id.rowTitle).text = title
        row.findViewById<TextView>(R.id.rowSubtitle).text = subtitle
        val sw = row.findViewById<SwitchMaterial>(R.id.rowSwitch)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        parent.addView(row)
    }

    // ---------------- data helpers ----------------

    private fun localUrl() = "http://127.0.0.1:${NebulaApp.MCP_PORT}/mcp"

    private fun lanUrl(): String {
        val ip = lanIpAddress() ?: "0.0.0.0"
        return "http://$ip:${NebulaApp.MCP_PORT}/mcp"
    }

    private fun lanIpAddress(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun stateLabel(enabled: Boolean) =
        if (enabled) getString(R.string.enabled) else getString(R.string.disabled)

    private fun maskToken(token: String): String =
        if (token.length <= 8) token else token.take(8) + "..."

    private fun mcpConfigJson(): String {
        val obj = JSONObject()
            .put("mcpServers", JSONObject().put("nebula", JSONObject()
                .put("url", if (settings.lanAccessEnabled) lanUrl() else localUrl())
                .put("headers", JSONObject().put("Authorization", "Bearer ${settings.token}"))
            ))
        return obj.toString(2)
    }

    private fun curlCommand(): String {
        val url = localUrl()
        return "curl -X POST $url -H 'Authorization: Bearer ${settings.token}' " +
            "-H 'Content-Type: application/json' " +
            "-d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}'"
    }

    private fun copyToClipboard(text: String, label: String) {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), "已复制：$label", Toast.LENGTH_SHORT).show()
    }

    // ---------------- dialogs ----------------

    private fun showStatusDialog() {
        val server = app.mcpServer
        val msg = if (server == null) {
            "MCP 服务当前未运行"
        } else {
            "运行时长：${(System.currentTimeMillis() - server.startedAt) / 1000}s\n" +
                "调用次数：${server.callCount.get()}\n" +
                "局域网访问：${if (settings.lanAccessEnabled) "开" else "关"}\n" +
                "本地工具数：${app.toolRegistry.listToolDescriptors().length()}\n" +
                "远端服务器数：${app.remoteMcpManager.list().size}"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.view_status))
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showWorkspaceDialog(container: LinearLayout) {
        val input = EditText(requireContext()).apply {
            setText(settings.workspacePath)
            hint = requireContext().getExternalFilesDir(null)?.path + "/Workspace"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.workspace_label))
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                settings.workspacePath = input.text.toString().trim()
                buildRows(container)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showGithubTokenDialog(container: LinearLayout) {
        val input = EditText(requireContext()).apply {
            setText(settings.githubToken)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "ghp_xxxxxxxxxxxx"
        }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.github_token_label))
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                settings.githubToken = input.text.toString().trim()
                buildRows(container)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRemoteServerDialog(container: LinearLayout) {
        val configured = app.remoteMcpManager.configuredList()
        val discovered = app.remoteMcpManager.list().filter { it.discovered }
        val summary = buildString {
            append("已配置：\n")
            if (configured.isEmpty()) append("暂无\n") else configured.forEach { append("• ${it.name} → ${it.url}\n") }
            if (discovered.isNotEmpty()) {
                append("\n自动发现：\n")
                discovered.forEach { append("• ${it.name} → ${it.url}\n") }
            }
        }

        val nameInput = EditText(requireContext()).apply { hint = "名称，如 github-mcp" }
        val urlInput = EditText(requireContext()).apply { hint = "https://host:port/mcp" }
        val tokenInput = EditText(requireContext()).apply { hint = "Token（可选）" }
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 0)
            addView(TextView(requireContext()).apply { text = summary; setPadding(0,0,0,16) })
            addView(nameInput); addView(urlInput); addView(tokenInput)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.remote_mcp_manage))
            .setView(layout)
            .setPositiveButton("添加") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isNotBlank() && url.isNotBlank()) {
                    app.remoteMcpManager.add(name, url, tokenInput.text.toString().trim())
                    buildRows(container)
                }
            }
            .setNeutralButton("扫描本机") { _, _ ->
                Thread {
                    val found = app.remoteMcpManager.discoverLocalMcp(true)
                    activity?.runOnUiThread {
                        Toast.makeText(requireContext(), "发现 ${found.size} 个本机 MCP", Toast.LENGTH_SHORT).show()
                        buildRows(container)
                    }
                }.start()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showRemoteDiagnosticsDialog() {
        val progress = TextView(requireContext()).apply { text = "正在测试远端 MCP…" }
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.mcp_remote_diagnostics))
            .setView(progress)
            .setPositiveButton("关闭", null)
            .show()
        Thread {
            val result = app.remoteMcpManager.diagnostics()
            val text = buildString {
                for (i in 0 until result.length()) {
                    val o = result.getJSONObject(i)
                    append(if (o.optBoolean("ok")) "✓ " else "✗ ")
                    append(o.optString("name")); append("\n")
                    append(o.optString("url")); append("\n")
                    if (o.optBoolean("ok")) append("工具：${o.optInt("toolCount")}\n")
                    else append("错误：${o.optString("error")}\n")
                    append("\n")
                }
                if (isEmpty()) append("没有已配置或已发现的远端 MCP。")
            }
            activity?.runOnUiThread { progress.text = text }
        }.start()
    }

    companion object {
        fun newInstance() = McpInfoSheet()
    }
}
