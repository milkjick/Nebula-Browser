package com.mice.nebulamcp

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.os.Build
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView

/** 独立视频播放器：提供真正的全屏切换，不依赖网页的 Fullscreen API。 */
class VideoPlayerActivity : Activity() {
    companion object {
        const val EXTRA_URL = "video_url"
        const val EXTRA_TITLE = "video_title"
    }

    private var videoView: VideoView? = null
    private var root: FrameLayout? = null
    private var fullscreen = false
    private var fullscreenButton: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (url.isBlank()) { finish(); return }

        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root = frame
        val video = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        videoView = video
        val controller = MediaController(this).apply { setAnchorView(video) }
        video.setMediaController(controller)
        video.setVideoURI(Uri.parse(url))
        video.setOnPreparedListener { mp ->
            mp.isLooping = false
            video.start()
        }
        video.setOnErrorListener { _, what, extra ->
            showError("视频无法播放（错误 $what/$extra）\n\n部分站点媒体需要登录、Referer、授权或网页播放器环境。")
            true
        }
        frame.addView(video)

        val titleView = TextView(this).apply {
            text = if (title.isBlank()) "Nebula 独立视频播放器" else title
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(18, 12, 18, 12)
            setBackgroundColor(0x88000000.toInt())
            maxLines = 2
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }
        frame.addView(titleView)

        fullscreenButton = TextView(this).apply {
            text = "⛶ 全屏"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(16, 10, 16, 10)
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { toggleFullscreen() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(0, 0, 18, 28) }
        }
        frame.addView(fullscreenButton)
        setContentView(frame)
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        if (fullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            hideSystemBars()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            showSystemBars()
        }
        fullscreenButton?.text = if (fullscreen) "⛶ 退出全屏" else "⛶ 全屏"
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreen) hideSystemBars()
    }

    private fun showError(message: String) {
        val parent = root ?: return
        parent.findViewWithTag<View>("nebula_video_error")?.let { parent.removeView(it) }
        val error = TextView(this).apply {
            tag = "nebula_video_error"
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(30, 30, 30, 30)
            setOnClickListener { finish() }
            setBackgroundColor(0xCC000000.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        parent.addView(error)
    }

    override fun onBackPressed() {
        if (fullscreen) { toggleFullscreen(); return }
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (fullscreen) showSystemBars()
        videoView?.stopPlayback()
        videoView = null
        root = null
        super.onDestroy()
    }
}
