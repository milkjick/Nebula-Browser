package com.mice.nebulamcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the process (and therefore the NanoHTTPD MCP server) alive while the
 * app is backgrounded, same pattern as the earlier DeepSeek gateway. Started
 * lazily; not strictly required for the MCP server to function while the
 * activity is foregrounded, but avoids the OS killing the process mid-task.
 */
class McpForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): android.app.Notification {
        ensureChannel()
        val app = application as NebulaApp
        val text = if (app.mcpServer != null) {
            "MCP 网关运行中 · 127.0.0.1:${NebulaApp.MCP_PORT}/mcp"
        } else {
            "MCP 服务已停止"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "MCP 网关", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "nebula_mcp_service"
        private const val NOTIFICATION_ID = 1001
    }
}
