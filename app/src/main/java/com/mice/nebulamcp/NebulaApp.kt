package com.mice.nebulamcp

import android.app.Application

/**
 * Holds process-wide singletons: settings store, tool registry, remote MCP
 * manager and the local MCP HTTP server itself. Kept deliberately simple
 * (no DI framework) to match the size of this project.
 */
class NebulaApp : Application() {

    lateinit var settings: SettingsStore
        private set

    lateinit var remoteMcpManager: RemoteMcpManager
        private set

    lateinit var toolRegistry: ToolRegistry
        private set

    var mcpServer: McpServer? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsStore(this)
        remoteMcpManager = RemoteMcpManager(settings)
        toolRegistry = ToolRegistry(this)

        if (settings.mcpServiceEnabled) {
            startMcpServer()
        }
    }

    fun startMcpServer() {
        if (mcpServer != null) return
        val server = McpServer(
            app = this,
            port = MCP_PORT,
            bindLan = settings.lanAccessEnabled,
            toolRegistry = toolRegistry,
            remoteMcpManager = remoteMcpManager,
            settings = settings
        )
        try {
            server.start()
            mcpServer = server
        } catch (e: Exception) {
            mcpServer = null
        }
    }

    fun stopMcpServer() {
        mcpServer?.stop()
        mcpServer = null
    }

    fun restartMcpServer() {
        stopMcpServer()
        if (settings.mcpServiceEnabled) startMcpServer()
    }

    companion object {
        const val MCP_PORT = 8788
        lateinit var instance: NebulaApp
            private set
    }
}
