package com.mice.nebulamcp

data class DownloadTask(
    val id: Long,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val status: Status,
    val progress: Int = 0,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L
) {
    enum class Status { QUEUED, RUNNING, PAUSED, COMPLETE, FAILED, CANCELLED }
}
