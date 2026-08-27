package com.mice.nebulamcp

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import org.json.JSONObject

/** Extracts local video metadata and one representative frame for optional vision analysis. */
class VideoUnderstanding(private val context: Context, private val localAi: LocalAiProvider) {
    fun analyze(uri: String): Result<String> = runCatching {
        val r = MediaMetadataRetriever()
        if (uri.startsWith("content://") || uri.startsWith("file://")) r.setDataSource(context, Uri.parse(uri))
        else r.setDataSource(uri, HashMap<String, String>())
        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
        val frame: Bitmap? = r.getFrameAtTime((duration / 2).coerceAtLeast(0L) * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        r.release()
        val base = JSONObject().put("durationMs", duration).put("width", width).put("height", height).put("mime", mime)
        if (frame != null) {
            localAi.askVision("分析这个视频代表性画面。只描述可见内容、场景、主要对象和文字，不要猜测。视频元数据：$base", frame)
                .onSuccess { base.put("aiDescription", it) }
            frame.recycle()
        }
        base.toString()
    }
}
