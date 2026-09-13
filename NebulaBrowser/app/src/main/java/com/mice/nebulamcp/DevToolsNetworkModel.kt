package com.mice.nebulamcp

data class DevToolsNetworkModel(
    val requestId: Long,
    val url: String,
    val method: String,
    val statusCode: Int,
    val mimeType: String?,
    val timestamp: Long
)
