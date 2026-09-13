package com.mice.nebulamcp

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import android.util.Base64
import java.net.URL
import java.net.URLDecoder

/**
 * V16 download subsystem.
 * Uses Android DownloadManager so downloads continue when the Activity is not visible.
 * Keeps the WebView session cookie/User-Agent for cloud-drive links.
 */
class DownloadHelper(private val context: Context) {
    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = context.getSharedPreferences("nebula_downloads_v16", Context.MODE_PRIVATE)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id <= 0) return
            val title = prefs.getString("name_$id", "下载任务")
            val status = query(id)?.status
            Toast.makeText(ctx, if (status == DownloadManager.STATUS_SUCCESSFUL) "下载完成：$title" else "下载失败：$title", Toast.LENGTH_SHORT).show()
        }
    }

    init {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    fun download(
        url: String,
        fileName: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        userAgent: String? = null,
        referer: String? = null,
        cookie: String? = null
    ): Long? {
        if (url.isBlank()) return null
        return try {
            val request = DownloadManager.Request(Uri.parse(url))
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            request.setAllowedOverMetered(true)
            request.setAllowedOverRoaming(true)
            request.setDescription("Nebula Browser")

            (cookie?.takeIf { it.isNotBlank() }
                ?: CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() })?.let {
                request.addRequestHeader("Cookie", it)
            }
            request.addRequestHeader(
                "User-Agent",
                userAgent ?: "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
            )
            request.addRequestHeader("Accept", mimeType?.takeIf { it.isNotBlank() } ?: "*/*")
            request.addRequestHeader("Referer", referer?.takeIf { it.isNotBlank() } ?: refererFor(url))

            val name = sanitizeFileName(
                fileName
                    ?: extractFileName(contentDisposition)
                    ?: URLUtil.guessFileName(url, contentDisposition, mimeType)
            ).ifBlank { "nebula_${System.currentTimeMillis()}" }

            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            val id = dm.enqueue(request)
            prefs.edit()
                .putString("name_$id", name)
                .putString("url_$id", url)
                .putLong("time_$id", System.currentTimeMillis())
                .apply()
            Toast.makeText(context, "开始下载：$name", Toast.LENGTH_SHORT).show()
            id
        } catch (e: Exception) {
            Toast.makeText(context, "下载失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            null
        }
    }

    data class Task(
        val id: Long,
        val name: String,
        val url: String,
        val status: Int,
        val downloaded: Long,
        val total: Long,
        val reason: Int
    ) {
        val progress: Int
            get() = if (total > 0) ((downloaded * 100L) / total).coerceIn(0, 100).toInt() else 0
    }


    /** Save a blob/data URL delivered by the page JavaScript bridge.
     * Limited to 32 MiB decoded data to avoid unbounded memory use.
     */
    fun saveBase64(fileName: String?, mimeType: String?, base64: String): Boolean {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.size > 32 * 1024 * 1024) {
                Toast.makeText(context, "网页文件超过 32 MB，请使用网页原生下载", Toast.LENGTH_LONG).show()
                return false
            }
            val safe = sanitizeFileName(fileName?.ifBlank { null } ?: "nebula_${System.currentTimeMillis()}")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val target = uniqueFile(dir, safe)
            FileOutputStream(target).use { it.write(bytes) }
            Toast.makeText(context, "已保存：${target.name}", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "保存网页文件失败：${e.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 10000) {
            val candidate = File(dir, "${base} ($i)$ext")
            if (!candidate.exists()) return candidate
            i++
        }
        return File(dir, "${base}_${System.currentTimeMillis()}$ext")
    }

    fun tasks(limit: Int = 100): List<Task> {
        val ids = prefs.all.keys
            .filter { it.startsWith("name_") }
            .mapNotNull { it.removePrefix("name_").toLongOrNull() }
            .distinct()
        return ids.mapNotNull { query(it) }
            .sortedByDescending { prefs.getLong("time_${it.id}", 0L) }
            .take(limit)
    }

    fun query(id: Long): Task? {
        return try {
            val c = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
            c.use {
                if (!it.moveToFirst()) return null
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                Task(
                    id,
                    prefs.getString("name_$id", "下载") ?: "下载",
                    prefs.getString("url_$id", "") ?: "",
                    status,
                    downloaded,
                    total,
                    reason
                )
            }
        } catch (_: Exception) {
            null
        }
    }


    /**
     * Retry a failed/cancelled task using the same URL and display name.
     * DownloadManager itself has no portable resume API, so retry creates a new task.
     */
    fun retry(id: Long): Long? {
        val task = query(id) ?: return null
        if (task.status != DownloadManager.STATUS_FAILED &&
            task.status != DownloadManager.STATUS_PAUSED) return null
        val newId = download(task.url, task.name, null, guessMimeType(task.name), null)
        if (newId != null) {
            try { dm.remove(id) } catch (_: Exception) {}
            prefs.edit().remove("name_$id").remove("url_$id").remove("time_$id").apply()
        }
        return newId
    }

    fun cancel(id: Long) {
        try { dm.remove(id) } catch (_: Exception) {}
        prefs.edit().remove("name_$id").remove("url_$id").remove("time_$id").apply()
    }

    fun removeTask(id: Long, deleteFile: Boolean = false) {
        try {
            dm.remove(id)
        } catch (_: Exception) {}
        if (deleteFile) {
            listDownloadFiles().firstOrNull {
                prefs.getString("name_$id", "") == it.name
            }?.delete()
        }
        prefs.edit().remove("name_$id").remove("url_$id").remove("time_$id").apply()
    }

    fun clearFinished() {
        tasks().filter {
            it.status == DownloadManager.STATUS_SUCCESSFUL ||
                it.status == DownloadManager.STATUS_FAILED
        }.forEach { removeTask(it.id, false) }
    }

    fun openDownloadDir() {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        try {
            val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            copyText("Nebula 下载路径", dir.absolutePath)
            Toast.makeText(context, "已复制下载路径", Toast.LENGTH_SHORT).show()
        }
    }

    fun listDownloadFiles(): List<File> {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return dir.listFiles()?.filter(File::isFile)?.sortedByDescending(File::lastModified) ?: emptyList()
    }

    fun openFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, guessMimeType(file.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = guessMimeType(file.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun deleteFile(file: File, onDeleted: () -> Unit) {
        if (file.delete()) {
            Toast.makeText(context, "已删除：${file.name}", Toast.LENGTH_SHORT).show()
            onDeleted()
        } else {
            Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun destroy() {
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    private fun extractFileName(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        val m = Regex("""filename\*=(?:UTF-8'')?([^;]+)|filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)
        val raw = m?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() } ?: return null
        return try { URLDecoder.decode(raw.trim().trim('"'), "UTF-8") } catch (_: Exception) { raw.trim().trim('"') }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(180)

    private fun refererFor(url: String): String =
        runCatching {
            val u = URL(url)
            "${u.protocol}://${u.host}/"
        }.getOrDefault(url)

    private fun guessMimeType(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    private fun copyText(label: String, text: String) {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
