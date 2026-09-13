package com.mice.nebulamcp

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

/**
 * Nebula 影视级独立播放器。
 *
 * 底层：AndroidX Media3 / ExoPlayer
 *
 * 核心能力：
 * - 左/右滑动实时快退/快进，松手提交
 * - 双击左/右区域 ±10 秒，双击中央播放/暂停
 * - 上下滑动调节亮度/音量
 * - 5/10/30 秒快退/快进
 * - 倍速 0.5x ~ 3x
 * - 清晰度选择（多码率流）
 * - 音轨/字幕选择
 * - 画面比例：适应/裁剪/拉伸
 * - 循环播放、静音
 * - 全屏、锁屏
 * - Android PIP
 * - 记忆播放位置
 * - HLS / DASH / Progressive（由 Media3 source factory 自动识别）
 *
 * 注意：播放器能否播放某个资源还取决于服务器鉴权、Cookie、Referer、
 * DRM、媒体格式以及源站是否允许第三方播放器访问。
 */
class VideoPlayerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "video_url"
        const val EXTRA_TITLE = "video_title"
        const val EXTRA_REFERER = "video_referer"
        const val EXTRA_COOKIE = "video_cookie"

        private const val RESUME_THRESHOLD_MS = 3_000L
        private const val SWIPE_SEEK_MS_PER_PX = 75L
        private const val DOUBLE_TAP_SKIP_MS = 10_000L
        private const val CONTROLLER_TIMEOUT_MS = 4_000L
        private const val PREFS = "nebula_video_positions"

        private const val KEY_SPEED = "speed"
        private const val KEY_LOOP = "loop"
        private const val KEY_MUTED = "muted"
        private const val KEY_RESIZE = "resize"
        private const val KEY_QUALITY_MODE = "quality_mode"
        private const val REQUEST_SUBTITLE_FILE = 8101
    }

    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var gestureLayer: View
    private lateinit var controlsLayer: FrameLayout
    private lateinit var seekBar: SeekBar

    private lateinit var titleText: TextView
    private lateinit var timeText: TextView
    private lateinit var gestureText: TextView
    private lateinit var centerPlay: TextView
    private lateinit var playButton: TextView
    private lateinit var speedButton: TextView
    private lateinit var muteButton: TextView
    private lateinit var loopButton: TextView
    private lateinit var lockButton: TextView
    private lateinit var independentPlayButton: TextView

    private lateinit var audioManager: AudioManager
    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var downloadHelper: DownloadHelper? = null
    private var mediaUrl = ""
    private var mediaTitle = ""
    private var referer: String? = null
    private var cookie: String? = null

    private var fullscreen = false
    private var locked = false
    private var controlsVisible = true
    private var muted = false
    private var loop = false
    private var speed = 1f
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var qualityMode = QualityMode.AUTO

    private enum class QualityMode {
        AUTO, MAX, P1080, P720, P480
    }
    private var externalSubtitle: MediaItem.SubtitleConfiguration? = null

    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureStartPosition = 0L
    private var gestureStartVolume = 0
    private var gestureStartBrightness = 0.5f
    private var gestureMode = GestureMode.NONE
    private var gestureChanged = false

    private var maxVolume = 15

    private enum class GestureMode {
        NONE, SEEK, VOLUME, BRIGHTNESS
    }

    private val controllerHider = Runnable {
        if (!locked) setControlsVisible(false)
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500L)
        }
    }

    private val gestureHideRunnable = Runnable {
        if (::gestureText.isInitialized) gestureText.visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        mediaUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        mediaTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        referer = intent.getStringExtra(EXTRA_REFERER)
        cookie = intent.getStringExtra(EXTRA_COOKIE)

        if (mediaUrl.isBlank()) {
            finish()
            return
        }

        loadPreferences()
        buildUi()
        buildPlayer()

        handler.post(progressUpdater)
    }

    private fun loadPreferences() {
        speed = prefs.getFloat(KEY_SPEED, 1f).coerceIn(0.5f, 3f)
        loop = prefs.getBoolean(KEY_LOOP, false)
        muted = prefs.getBoolean(KEY_MUTED, false)
        resizeMode = prefs.getInt(KEY_RESIZE, AspectRatioFrameLayout.RESIZE_MODE_FIT)
        qualityMode = when (prefs.getString(KEY_QUALITY_MODE, QualityMode.AUTO.name)) {
            QualityMode.MAX.name -> QualityMode.MAX
            QualityMode.P1080.name -> QualityMode.P1080
            QualityMode.P720.name -> QualityMode.P720
            QualityMode.P480.name -> QualityMode.P480
            else -> QualityMode.AUTO
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = false
            resizeMode = this@VideoPlayerActivity.resizeMode
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            subtitleView?.setApplyEmbeddedStyles(true)
            subtitleView?.setApplyEmbeddedFontSizes(true)
        }
        root.addView(playerView)

        // 全屏透明手势层。控制按钮放在它上面，因此不会抢控制按钮点击。
        gestureLayer = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.addView(gestureLayer)

        controlsLayer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false
        }
        controlsLayer.addView(buildTopBar())
        controlsLayer.addView(buildCenterControls())
        controlsLayer.addView(buildBottomControls())
        root.addView(controlsLayer)

        // 独立播放按钮：不依赖播放器控制栏，控制栏隐藏、暂停或播放结束时仍可一键播放。
        independentPlayButton = makeButton("▶ 播放", 12f) {
            playImmediately()
            scheduleHide()
        }.apply {
            background = GradientDrawable().apply {
                setColor(0xCC1565C0.toInt())
                cornerRadius = dp(20).toFloat()
            }
            elevation = dp(6).toFloat()
            contentDescription = "立即播放当前视频"
        }
        root.addView(independentPlayButton, FrameLayout.LayoutParams(dp(88), dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(14)
        })

        root.addView(buildGestureIndicator())

        setContentView(root)
        setupGestures()
        updateButtonStates()
        setControlsVisible(true)
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(0xAA000000.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(58),
                Gravity.TOP
            )
        }

        val back = makeButton("‹", 28f) {
            if (fullscreen) exitFullscreen() else finish()
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(46), dp(46)))

        titleText = TextView(this).apply {
            text = if (mediaTitle.isBlank()) "Nebula 视频播放器" else mediaTitle
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(6)
            }
        }
        bar.addView(titleText)

        bar.addView(makeButton("清晰度", 12f) { showQualityDialog() })
        bar.addView(makeButton("音轨", 12f) { showAudioDialog() })
        bar.addView(makeButton("字幕", 12f) { showSubtitleDialog() })
        bar.addView(makeButton("下载", 12f) { downloadCurrentVideo() })
        bar.addView(makeButton("⋮", 24f) { showMoreDialog() })

        return bar
    }

    private fun buildCenterControls(): View {
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(90),
                Gravity.CENTER
            )
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        row.addView(makeButton("↶ 10", 14f) { seekRelative(-10_000L) })

        centerPlay = makeButton("▶", 30f) {
            togglePlayPause()
            scheduleHide()
        }
        centerPlay.background = circleBackground()
        row.addView(centerPlay, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            marginStart = dp(20)
            marginEnd = dp(20)
        })

        row.addView(makeButton("30 ↷", 14f) { seekRelative(30_000L) })

        container.addView(row)
        return container
    }

    private fun buildBottomControls(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        seekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)
            )
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val p = player ?: return
                        if (p.duration > 0) {
                            p.seekTo((p.duration * progress / 1000L))
                            updateTimeOnly()
                        }
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {
                    handler.removeCallbacks(controllerHider)
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    scheduleHide()
                }
            })
        }
        panel.addView(seekBar)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        playButton = makeButton("▶", 18f) { togglePlayPause(); scheduleHide() }
        row.addView(playButton, LinearLayout.LayoutParams(dp(48), dp(42)))

        row.addView(makeButton("−10", 12f) { seekRelative(-10_000L); scheduleHide() },
            LinearLayout.LayoutParams(dp(52), dp(42)))
        row.addView(makeButton("+10", 12f) { seekRelative(10_000L); scheduleHide() },
            LinearLayout.LayoutParams(dp(52), dp(42)))

        timeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            text = "00:00 / 00:00"
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
        }
        row.addView(timeText)

        speedButton = makeButton("${speedText(speed)}", 12f) {
            showSpeedDialog()
        }
        row.addView(speedButton, LinearLayout.LayoutParams(dp(60), dp(42)))

        muteButton = makeButton(if (muted) "静音" else "🔊", 12f) {
            toggleMute()
            scheduleHide()
        }
        row.addView(muteButton, LinearLayout.LayoutParams(dp(48), dp(42)))

        loopButton = makeButton("循环", 12f) {
            toggleLoop()
            scheduleHide()
        }
        row.addView(loopButton, LinearLayout.LayoutParams(dp(48), dp(42)))

        lockButton = makeButton("🔒", 13f) {
            toggleLock()
        }
        row.addView(lockButton, LinearLayout.LayoutParams(dp(48), dp(42)))

        row.addView(makeButton("⛶", 18f) {
            toggleFullscreen()
        }, LinearLayout.LayoutParams(dp(48), dp(42)))

        panel.addView(row)
        return panel
    }

    private fun buildGestureIndicator(): View {
        gestureText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                setColor(0xDD111111.toInt())
                cornerRadius = dp(12).toFloat()
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        return gestureText
    }

    private fun makeButton(text: String, size: Float, action: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = size
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = GradientDrawable().apply {
                setColor(0x66000000.toInt())
                cornerRadius = dp(9).toFloat()
            }
            isClickable = true
            setOnClickListener { action() }
        }
    }

    private fun circleBackground(): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xAA000000.toInt())
        }

    // ---------------------------------------------------------------------
    // Player
    // ---------------------------------------------------------------------

    private fun buildPlayer() {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            .setAllowCrossProtocolRedirects(true)

        val headers = mutableMapOf<String, String>()
        if (!referer.isNullOrBlank()) headers["Referer"] = referer!!
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie!!
        if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(headers)

        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)

        val selector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParametersForQuality(qualityMode))
        }
        trackSelector = selector

        val exo = ExoPlayer.Builder(this)
            .setTrackSelector(selector)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exo.setMediaItem(MediaItem.fromUri(Uri.parse(mediaUrl)))
        exo.repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exo.playbackParameters = PlaybackParameters(speed)

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayButtons()
                if (isPlaying) scheduleHide()
                else setControlsVisible(true)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateProgress()
                if (playbackState == Player.STATE_ENDED && !loop) {
                    setControlsVisible(true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showError(
                    "视频播放失败\n\n" +
                        "错误：${error.errorCodeName}\n\n" +
                        "可能原因：媒体地址失效、Referer/Cookie/登录校验、" +
                        "DRM 限制、服务器不允许第三方播放器或格式不兼容。"
                )
            }
        })

        player = exo
        playerView.player = exo

        val saved = prefs.getLong(positionKey(mediaUrl), 0L)

        exo.prepare()

        if (saved > RESUME_THRESHOLD_MS) {
            exo.seekTo(saved)
            toast("已恢复上次播放位置")
        }

        exo.volume = if (muted) 0f else 1f
        exo.playWhenReady = true
        scheduleHide()
    }

    // ---------------------------------------------------------------------
    // Gesture engine
    // ---------------------------------------------------------------------

    private fun setupGestures() {
        val detector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onDown(e: MotionEvent): Boolean {
                    gestureStartX = e.x
                    gestureStartY = e.y
                    gestureStartPosition = player?.currentPosition ?: 0L
                    gestureStartVolume =
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                    val lp = window.attributes
                    gestureStartBrightness =
                        if (lp.screenBrightness in 0f..1f) lp.screenBrightness else 0.5f

                    gestureMode = GestureMode.NONE
                    gestureChanged = false
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (locked) {
                        // 锁屏状态只允许点击屏幕中央解锁。
                        if (e.x in widthCenterRange()) toggleLock()
                        return true
                    }

                    if (!gestureChanged) {
                        setControlsVisible(!controlsVisible)
                    }
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (locked) return true

                    val w = gestureLayer.width.toFloat()
                    when {
                        e.x < w * 0.33f -> {
                            seekRelative(-DOUBLE_TAP_SKIP_MS)
                            showGesture("↶  -10 秒")
                        }
                        e.x > w * 0.67f -> {
                            seekRelative(DOUBLE_TAP_SKIP_MS)
                            showGesture("↷  +10 秒")
                        }
                        else -> togglePlayPause()
                    }
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    if (locked || e1 == null) return true

                    val dx = e2.x - gestureStartX
                    val dy = e2.y - gestureStartY

                    if (gestureMode == GestureMode.NONE) {
                        if (abs(dx) > dp(12) || abs(dy) > dp(12)) {
                            gestureMode =
                                if (abs(dx) >= abs(dy)) GestureMode.SEEK
                                else if (gestureStartX < gestureLayer.width / 2f)
                                    GestureMode.BRIGHTNESS
                                else GestureMode.VOLUME
                        }
                    }

                    when (gestureMode) {
                        GestureMode.SEEK -> {
                            gestureChanged = true
                            val delta = (dx * SWIPE_SEEK_MS_PER_PX).toLong()
                            val duration = player?.duration ?: 0L
                            val target = if (duration > 0) {
                                (gestureStartPosition + delta).coerceIn(0L, duration)
                            } else {
                                max(0L, gestureStartPosition + delta)
                            }
                            showGesture(
                                "${formatTime(target)}  ${if (delta >= 0) "+" else "-"}${abs(delta) / 1000}s"
                            )
                        }

                        GestureMode.BRIGHTNESS -> {
                            gestureChanged = true
                            val next =
                                (gestureStartBrightness - dy / 700f).coerceIn(0.02f, 1f)
                            val lp = window.attributes
                            lp.screenBrightness = next
                            window.attributes = lp
                            showGesture("☀ ${(next * 100).toInt()}%")
                        }

                        GestureMode.VOLUME -> {
                            gestureChanged = true
                            val ratio = -dy / 700f
                            val next = (gestureStartVolume + ratio * maxVolume)
                                .toInt()
                                .coerceIn(0, maxVolume)
                            audioManager.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                next,
                                0
                            )
                            val percent = next * 100 / maxVolume
                            showGesture("🔊 $percent%")
                        }

                        else -> Unit
                    }
                    return true
                }
            })

        gestureLayer.setOnTouchListener { _, event ->
            if (locked && event.action == MotionEvent.ACTION_UP) {
                // 锁屏时整个画面仍可响应中间解锁点击。
            }

            detector.onTouchEvent(event)

            if (event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL
            ) {
                if (gestureMode == GestureMode.SEEK && gestureChanged) {
                    val dx = event.x - gestureStartX
                    val delta = (dx * SWIPE_SEEK_MS_PER_PX).toLong()
                    seekToPosition(gestureStartPosition + delta)
                }

                gestureMode = GestureMode.NONE
                gestureChanged = false
                hideGestureDelayed()
            }

            true
        }
    }

    private fun widthCenterRange(): ClosedFloatingPointRange<Float> {
        val center = gestureLayer.width / 2f
        return (center - dp(80)).toFloat()..(center + dp(80)).toFloat()
    }

    private fun seekToPosition(position: Long) {
        val p = player ?: return
        val duration = p.duration
        val target = if (duration > 0)
            position.coerceIn(0L, duration)
        else
            max(0L, position)
        p.seekTo(target)
    }

    private fun seekRelative(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration
        val target = if (duration > 0)
            (p.currentPosition + deltaMs).coerceIn(0L, duration)
        else
            max(0L, p.currentPosition + deltaMs)

        p.seekTo(target)
        showGesture(
            "${if (deltaMs >= 0) "↷ +" else "↶ "}${abs(deltaMs) / 1000}s"
        )
        hideGestureDelayed()
    }

    // ---------------------------------------------------------------------
    // Playback controls
    // ---------------------------------------------------------------------

    /** 立即播放/继续播放：用于播放器界面上的独立播放按钮。
     * 如果播放器刚刚处于结束状态，则从头重新播放；否则直接继续当前进度。
     */
    private fun playImmediately() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0L)
        p.playWhenReady = true
        p.play()
        updatePlayButtons()
        showGesture("▶ 正在播放")
        hideGestureDelayed()
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        updatePlayButtons()
    }

    private fun toggleMute() {
        muted = !muted
        player?.volume = if (muted) 0f else 1f
        prefs.edit().putBoolean(KEY_MUTED, muted).apply()
        updateButtonStates()
        showGesture(if (muted) "🔇 已静音" else "🔊 已恢复声音")
    }

    private fun toggleLoop() {
        loop = !loop
        player?.repeatMode =
            if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        prefs.edit().putBoolean(KEY_LOOP, loop).apply()
        updateButtonStates()
        toast(if (loop) "已开启单片循环" else "已关闭循环")
    }

    private fun showSpeedDialog() {
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)
        val labels = values.map { speedText(it) }.toTypedArray()
        val checked = values.indices.minByOrNull {
            abs(values[it] - speed)
        } ?: 2

        AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                speed = values[which]
                player?.playbackParameters = PlaybackParameters(speed)
                prefs.edit().putFloat(KEY_SPEED, speed).apply()
                speedButton.text = speedText(speed)
                dialog.dismiss()
            }
            .show()
    }

    private fun showQualityDialog() {
        val p = player ?: return

        val labels = arrayOf(
            "自动（自适应）",
            "最高画质（最高 4K）",
            "1080P 高清",
            "720P 高清",
            "480P 标清"
        )
        val modes = arrayOf(
            QualityMode.AUTO,
            QualityMode.MAX,
            QualityMode.P1080,
            QualityMode.P720,
            QualityMode.P480
        )
        val checked = modes.indexOf(qualityMode).coerceAtLeast(0)
        val current = currentVideoQualityLabel(p)
        val variantCount = p.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
            .sumOf { it.length }

        if (variantCount <= 1) {
            // 单一 MP4/单一视频轨道不能凭播放器“变出”更高画质。
            // 仍然允许用户保存画质偏好，以便后续 HLS/DASH 多码率资源使用。
            toast("当前视频只有一个视频轨道，无法切换真实清晰度；高清取决于源站提供的码率。")
        }

        AlertDialog.Builder(this)
            .setTitle("清晰度 · 当前 $current")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                qualityMode = modes[which]
                trackSelector?.setParameters(buildUponParametersForQuality(qualityMode))
                prefs.edit().putString(KEY_QUALITY_MODE, qualityMode.name).apply()
                toast(when (qualityMode) {
                    QualityMode.AUTO -> "已开启自适应高清画质"
                    QualityMode.MAX -> "已选择最高可用画质（最高 4K）"
                    QualityMode.P1080 -> "已选择 1080P 高清"
                    QualityMode.P720 -> "已选择 720P 高清"
                    QualityMode.P480 -> "已选择 480P"
                })
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildUponParametersForQuality(mode: QualityMode): DefaultTrackSelector.Parameters {
        // 保留当前音轨/字幕等 TrackSelection 参数，只改变视频最大分辨率。
        // AUTO/MAX 允许播放器在最高 4K 范围内根据带宽自适应选择。
        val base = trackSelector?.parameters
        val builder = if (base != null) {
            base.buildUpon()
        } else {
            DefaultTrackSelector.Parameters.Builder(this)
        }

        return when (mode) {
            QualityMode.AUTO -> builder
                .setMaxVideoSize(3840, 2160)
                .build()
            QualityMode.MAX -> builder
                .setMaxVideoSize(3840, 2160)
                .build()
            QualityMode.P1080 -> builder
                .setMaxVideoSize(1920, 1080)
                .build()
            QualityMode.P720 -> builder
                .setMaxVideoSize(1280, 720)
                .build()
            QualityMode.P480 -> builder
                .setMaxVideoSize(854, 480)
                .build()
        }
    }

    private fun currentVideoQualityLabel(p: ExoPlayer): String {
        val videoGroups = p.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_VIDEO && it.isSupported }
        var best: Format? = null
        videoGroups.forEach { group ->
            for (i in 0 until group.length) {
                val f = group.getTrackFormat(i)
                if (group.isTrackSelected(i) && (best == null || f.height > best!!.height)) {
                    best = f
                }
            }
        }
        return best?.let { qualityLabel(it) } ?: qualityModeLabel(qualityMode)
    }

    private fun qualityModeLabel(mode: QualityMode): String = when (mode) {
        QualityMode.AUTO -> "自动"
        QualityMode.MAX -> "最高"
        QualityMode.P1080 -> "1080P"
        QualityMode.P720 -> "720P"
        QualityMode.P480 -> "480P"
    }

    private fun showAudioDialog() {
        val p = player ?: return
        val tracks = ArrayList<Pair<Tracks.Group, Int>>()

        p.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
            .forEach { group ->
                for (i in 0 until group.length) {
                    tracks.add(group to i)
                }
            }

        if (tracks.isEmpty()) {
            toast("当前视频没有可选择的音轨")
            return
        }

        val labels = tracks.map { audioLabel(it.first.getTrackFormat(it.second)) }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("音轨")
            .setItems(labels) { _, which ->
                val (group, index) = tracks[which]
                p.trackSelectionParameters =
                    p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .addOverride(
                            TrackSelectionOverride(
                                group.mediaTrackGroup,
                                listOf(index)
                            )
                        )
                        .build()
                toast("已切换音轨")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSubtitleDialog() {
        val p = player ?: return
        val tracks = ArrayList<Pair<Tracks.Group, Int>>()

        p.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
            .forEach { group ->
                for (i in 0 until group.length) tracks.add(group to i)
            }

        val items = ArrayList<String>()
        items.add("关闭字幕")
        if (tracks.isNotEmpty()) items.addAll(tracks.map { subtitleLabel(it.first.getTrackFormat(it.second)) })
        items.add("添加字幕文件（SRT / VTT）")
        items.add("添加字幕 URL（SRT / VTT）")

        AlertDialog.Builder(this)
            .setTitle(if (tracks.isEmpty()) "字幕（当前视频没有内置字幕）" else "字幕")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        externalSubtitle = null
                        p.trackSelectionParameters =
                            p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .build()
                        toast("字幕已关闭")
                    }
                    which <= tracks.size -> {
                        val (group, index) = tracks[which - 1]
                        externalSubtitle = null
                        p.trackSelectionParameters =
                            p.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .addOverride(
                                    TrackSelectionOverride(group.mediaTrackGroup, listOf(index))
                                )
                                .build()
                        toast("已启用：${subtitleLabel(group.getTrackFormat(index))}")
                    }
                    which == tracks.size + 1 -> openSubtitleFilePicker()
                    else -> showSubtitleUrlDialog()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openSubtitleFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/vtt", "application/x-subrip", "text/plain"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_SUBTITLE_FILE)
        } catch (_: Exception) {
            toast("无法打开字幕文件选择器")
        }
    }

    private fun showSubtitleUrlDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "https://example.com/subtitle.vtt"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFAAAAAA.toInt())
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(52)
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("添加字幕 URL")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("加载") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) { toast("字幕 URL 不能为空"); return@setPositiveButton }
                loadExternalSubtitle(Uri.parse(url), guessSubtitleMime(url), "zh")
            }
            .show()
    }

    private fun guessSubtitleMime(value: String): String {
        val u = value.lowercase(Locale.US).substringBefore('?').substringBefore('#')
        return when {
            u.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            u.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            else -> MimeTypes.TEXT_VTT
        }
    }

    private fun loadExternalSubtitle(uri: Uri, mimeType: String, language: String?) {
        val p = player ?: return
        val position = p.currentPosition
        val wasPlaying = p.isPlaying || p.playWhenReady
        val config = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLanguage(language ?: "zh")
            .setLabel(if (language == "zh") "中文" else "外部字幕")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        externalSubtitle = config
        val item = MediaItem.Builder()
            .setUri(Uri.parse(mediaUrl))
            .setSubtitleConfigurations(listOf(config))
            .build()
        p.setMediaItem(item, position)
        p.prepare()
        p.playWhenReady = wasPlaying
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        toast("字幕加载中…")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SUBTITLE_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { }
        val mime = contentResolver.getType(uri) ?: guessSubtitleMime(uri.toString())
        val normalized = when {
            mime.contains("vtt", true) -> MimeTypes.TEXT_VTT
            mime.contains("subrip", true) || mime.contains("srt", true) -> MimeTypes.APPLICATION_SUBRIP
            else -> guessSubtitleMime(uri.toString())
        }
        loadExternalSubtitle(uri, normalized, "zh")
    }

    /**
     * 下载当前独立播放器正在播放的媒体地址。
     *
     * DownloadManager 适合 MP4/WebM 等普通直链。HLS/DASH 是分片流，不能把
     * .m3u8/.mpd 清单文件直接当成视频保存，因此这里明确提示用户，避免得到
     * 一个无法播放的“视频文件”。
     */
    private fun downloadCurrentVideo() {
        val url = mediaUrl.trim()
        if (url.isBlank()) {
            toast("当前没有可下载的视频地址")
            return
        }

        val lower = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        if (lower.startsWith("blob:") || lower.startsWith("data:")) {
            toast("当前视频是网页 blob/data 资源，无法直接下载")
            return
        }

        if (lower.endsWith(".m3u8") || lower.contains(".m3u8/") || lower.endsWith(".mpd") || lower.contains(".mpd/")) {
            AlertDialog.Builder(this)
                .setTitle("分片视频暂不直接保存")
                .setMessage(
                    "当前视频是 HLS/DASH 分片流。\\n\\n" +
                        "直接下载 .m3u8/.mpd 只会得到播放清单，不是完整视频。\\n" +
                        "当前版本的下载按钮仅对 MP4/WebM 等普通视频直链执行系统下载。"
                )
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        val safeTitle = mediaTitle.trim().ifBlank { "Nebula视频" }
        val extension = when {
            lower.endsWith(".webm") -> ".webm"
            lower.endsWith(".mov") -> ".mov"
            lower.endsWith(".mkv") -> ".mkv"
            lower.endsWith(".m4v") -> ".m4v"
            lower.endsWith(".3gp") -> ".3gp"
            lower.endsWith(".avi") -> ".avi"
            else -> ".mp4"
        }
        val fileName = if (safeTitle.contains('.')) safeTitle else safeTitle + extension

        if (downloadHelper == null) downloadHelper = DownloadHelper(this)
        downloadHelper?.download(
            url = url,
            fileName = fileName,
            mimeType = when (extension) {
                ".webm" -> "video/webm"
                ".mov" -> "video/quicktime"
                ".m4v" -> "video/mp4"
                ".3gp" -> "video/3gpp"
                else -> "video/mp4"
            },
            referer = referer,
            cookie = cookie
        )
    }

    private fun showMoreDialog() {
        val items = arrayOf(
            "快退 5 秒",
            "快退 30 秒",
            "快进 5 秒",
            "快进 30 秒",
            "下载当前视频",
            "画面比例",
            "进入画中画",
            "重新播放",
            "锁定屏幕",
            "全屏/退出全屏"
        )

        AlertDialog.Builder(this)
            .setTitle("播放器设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> seekRelative(-5_000L)
                    1 -> seekRelative(-30_000L)
                    2 -> seekRelative(5_000L)
                    3 -> seekRelative(30_000L)
                    4 -> downloadCurrentVideo()
                    5 -> showResizeDialog()
                    6 -> enterPip()
                    7 -> {
                        player?.seekTo(0)
                        player?.play()
                    }
                    8 -> toggleLock()
                    9 -> toggleFullscreen()
                }
            }
            .show()
    }

    private fun showResizeDialog() {
        val labels = arrayOf("适应", "裁剪填充", "拉伸填满")
        val modes = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL
        )
        val checked = modes.indexOf(resizeMode).takeIf { it >= 0 } ?: 0

        AlertDialog.Builder(this)
            .setTitle("画面比例")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                resizeMode = modes[which]
                playerView.resizeMode = resizeMode
                prefs.edit().putInt(KEY_RESIZE, resizeMode).apply()
                dialog.dismiss()
            }
            .show()
    }

    // ---------------------------------------------------------------------
    // Track helpers
    // ---------------------------------------------------------------------

    private fun qualityLabel(f: Format): String {
        val h = f.height
        val w = f.width
        return when {
            h >= 2160 -> "4K UHD"
            h >= 1440 -> "1440P 2K"
            h >= 1080 -> "1080P 高清"
            h >= 720 -> "720P 高清"
            h >= 480 -> "480P"
            h >= 360 -> "360P"
            w > 0 && h > 0 -> "${w}×${h}"
            else -> "自动"
        }
    }

    private fun qualityRank(label: String): Int = when (label) {
        "4K UHD" -> 2160
        "1440P 2K" -> 1440
        "1080P 高清" -> 1080
        "720P 高清" -> 720
        "480P" -> 480
        "360P" -> 360
        else -> 0
    }

    private fun audioLabel(f: Format): String {
        val language = f.language?.takeIf { it.isNotBlank() } ?: "未知语言"
        val name = f.label?.takeIf { it.isNotBlank() }
        return if (name != null) "$name · $language" else language
    }

    private fun subtitleLabel(f: Format): String {
        val language = f.language?.takeIf { it.isNotBlank() } ?: "未知语言"
        val name = f.label?.takeIf { it.isNotBlank() }
        return if (name != null) "$name · $language" else language
    }


    // ---------------------------------------------------------------------
    // Fullscreen / PIP / lock
    // ---------------------------------------------------------------------

    private fun toggleFullscreen() {
        if (fullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        fullscreen = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
        setControlsVisible(true)
        scheduleHide()
    }

    private fun exitFullscreen() {
        fullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showSystemBars()
        setControlsVisible(true)
    }

    private fun toggleLock() {
        locked = !locked
        if (locked) {
            setControlsVisible(false)
            lockButton.text = "🔓"
            showGesture("🔒 已锁定 · 点击中间解锁")
        } else {
            lockButton.text = "🔒"
            setControlsVisible(true)
            showGesture("🔓 已解锁")
        }
        hideGestureDelayed()
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast("当前 Android 版本不支持画中画")
            return
        }

        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } catch (e: Exception) {
            toast("无法进入画中画")
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            setControlsVisible(false)
        } else if (!locked) {
            setControlsVisible(true)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 不自动进入 PIP，避免用户按 Home 时产生意外行为。
    }

    // ---------------------------------------------------------------------
    // UI state
    // ---------------------------------------------------------------------

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible

        if (visible) {
            controlsLayer.visibility = View.VISIBLE
            controlsLayer.animate().cancel()
            controlsLayer.alpha = 0f
            controlsLayer.animate()
                .alpha(1f)
                .setDuration(160L)
                .start()
        } else {
            controlsLayer.animate().cancel()
            controlsLayer.animate()
                .alpha(0f)
                .setDuration(160L)
                .withEndAction {
                    if (!controlsVisible) controlsLayer.visibility = View.GONE
                }
                .start()
        }
    }

    private fun scheduleHide() {
        if (!controlsVisible || locked) return
        handler.removeCallbacks(controllerHider)
        handler.postDelayed(controllerHider, CONTROLLER_TIMEOUT_MS)
    }

    private fun updateButtonStates() {
        if (::speedButton.isInitialized) speedButton.text = speedText(speed)
        if (::muteButton.isInitialized) muteButton.text = if (muted) "静音" else "🔊"
        if (::loopButton.isInitialized) loopButton.alpha = if (loop) 1f else 0.55f
        if (::lockButton.isInitialized) lockButton.text = if (locked) "🔓" else "🔒"
        if (::independentPlayButton.isInitialized) independentPlayButton.text = if (player?.isPlaying == true) "Ⅱ 播放" else "▶ 播放"
        updatePlayButtons()
    }

    private fun updatePlayButtons() {
        val playing = player?.isPlaying == true
        if (::playButton.isInitialized) playButton.text = if (playing) "Ⅱ" else "▶"
        if (::centerPlay.isInitialized) centerPlay.text = if (playing) "Ⅱ" else "▶"
        if (::independentPlayButton.isInitialized) independentPlayButton.text = if (playing) "⏸ 播放中" else "▶ 播放"
    }

    private fun updateProgress() {
        val p = player ?: return
        val duration = p.duration
        val position = p.currentPosition.coerceAtLeast(0L)

        if (duration > 0) {
            val progress = (position * 1000L / duration).toInt().coerceIn(0, 1000)
            if (!seekBar.isPressed) seekBar.progress = progress
        }

        updateTimeOnly()
    }

    private fun updateTimeOnly() {
        if (!::timeText.isInitialized) return
        val p = player ?: return
        val duration = p.duration
        val position = p.currentPosition.coerceAtLeast(0L)
        timeText.text =
            "${formatTime(position)} / ${if (duration > 0) formatTime(duration) else "--:--"}"
    }

    private fun showGesture(text: String) {
        gestureText.text = text
        gestureText.visibility = View.VISIBLE
    }

    private fun hideGestureDelayed() {
        handler.removeCallbacks(gestureHideRunnable)
        handler.postDelayed(gestureHideRunnable, 650L)
    }

    private fun showError(message: String) {
        val error = TextView(this).apply {
            text = message + "\n\n点击关闭播放器"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            setBackgroundColor(0xDD000000.toInt())
            setOnClickListener { finish() }
        }
        root.addView(error, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    // ---------------------------------------------------------------------
    // Lifecycle / persistence
    // ---------------------------------------------------------------------

    private fun positionKey(url: String): String = "pos_${url.hashCode()}"

    private fun savePosition() {
        val p = player ?: return
        val position = p.currentPosition
        val duration = p.duration

        if (duration > 0 &&
            position > RESUME_THRESHOLD_MS &&
            position < duration - RESUME_THRESHOLD_MS
        ) {
            prefs.edit().putLong(positionKey(mediaUrl), position).apply()
        } else {
            prefs.edit().remove(positionKey(mediaUrl)).apply()
        }
    }

    override fun onPause() {
        savePosition()
        // 进入 Android PIP 时 Activity 会进入 paused 状态，但视频应该继续播放。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            !isInPictureInPictureMode
        ) {
            player?.pause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        savePosition()
        handler.removeCallbacks(progressUpdater)
        handler.removeCallbacks(controllerHider)
        handler.removeCallbacks(gestureHideRunnable)
        if (fullscreen) showSystemBars()
        player?.release()
        player = null
        trackSelector = null
        downloadHelper?.destroy()
        downloadHelper = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (locked) {
            toggleLock()
            return
        }

        if (fullscreen) {
            exitFullscreen()
            return
        }

        super.onBackPressed()
    }

    // ---------------------------------------------------------------------
    // System / formatting
    // ---------------------------------------------------------------------

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(true)
            window.insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = max(0L, ms / 1000L)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600

        return if (hours > 0)
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun speedText(value: Float): String {
        return if (value == value.toInt().toFloat())
            String.format(Locale.US, "%.1fx", value)
        else
            String.format(Locale.US, "%.2fx", value)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
