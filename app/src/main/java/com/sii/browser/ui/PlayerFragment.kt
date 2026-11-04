// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.sii.browser.R
import com.sii.browser.core.webview.SniffType
import com.sii.browser.core.webview.SniffedResource
import com.sii.browser.di.AppServices
import com.sii.browser.download.DownloadService
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 内置原生播放页：ExoPlayer/Media3 直接播放嗅探到的 mp4/m3u8，
 * 带当前页面的 Referer/UA/Cookie，解决 WebView HTML5 播不动的问题。
 * 控制条自绘（不用 Media3 自带控制器，避免全屏黑遮罩与居中按钮）；
 * 手势：左/右竖直滑亮度/音量、横滑进度、双击左右快退/快进、长按倍速。
 */
class PlayerFragment : Fragment() {

    private var player: ExoPlayer? = null
    private var items: List<SniffedResource> = emptyList()
    private var position: Int = 0
    private var referer: String? = null
    private var userAgent: String? = null

    private var dim: View? = null
    private var osd: TextView? = null
    private var topBar: View? = null
    private var bottomBar: View? = null
    private var playPause: ImageButton? = null
    private var progressBar: SeekBar? = null
    private var positionText: TextView? = null
    private var durationText: TextView? = null
    private var buffering: View? = null
    private lateinit var audio: AudioManager

    /** 画面亮度 0.02..1，通过黑色遮罩实现，不改系统亮度。 */
    private var brightness = 1f
    private var verticalStart = 1f
    private var volumeStart = 0
    private var seekStart = 0L
    private var seekDuration = 0L
    private var seekTarget = -1L

    private var tracking = false
    private var controlsVisible = true
    private var resumeOnReturn = false

    private val hideOsd = Runnable { osd?.visibility = View.GONE }
    private val hideControls = Runnable { if (player?.isPlaying == true) setControlsVisible(false) }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            view?.postDelayed(this, 500L)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        items = decode(arguments?.getString(ARG_ITEMS))
        position = (arguments?.getInt(ARG_INDEX) ?: 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        referer = arguments?.getString(ARG_REFERER)
        userAgent = arguments?.getString(ARG_UA)

        dim = view.findViewById(R.id.player_dim)
        osd = view.findViewById<TextView>(R.id.player_osd).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xCC000000.toInt())
            }
        }
        topBar = view.findViewById(R.id.player_top)
        bottomBar = view.findViewById(R.id.player_bottom)
        playPause = view.findViewById(R.id.player_play_pause)
        progressBar = view.findViewById(R.id.player_progress)
        positionText = view.findViewById(R.id.player_position)
        durationText = view.findViewById(R.id.player_duration)
        buffering = view.findViewById(R.id.player_buffering)
        audio = requireContext().getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager

        val playerView = view.findViewById<PlayerView>(R.id.player_view)
        val player = ExoPlayer.Builder(requireContext()).build()
        this.player = player
        playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    requireContext(),
                    R.string.player_play_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playPause?.setImageResource(
                    if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play
                )
                if (isPlaying) scheduleHideControls() else {
                    view?.removeCallbacks(hideControls)
                    setControlsVisible(true)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering?.visibility =
                    if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                updateProgress()
            }
        })

        val gestureLayout = view as PlayerGestureLayout
        gestureLayout.excluded = listOfNotNull(topBar, bottomBar)
        gestureLayout.listener = object : PlayerGestureLayout.Listener {
            override fun onVerticalStart(side: PlayerGestureLayout.Side) {
                if (!gesturesEnabled()) return
                if (side == PlayerGestureLayout.Side.RIGHT) {
                    volumeStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                } else {
                    verticalStart = brightness
                }
            }

            override fun onVerticalMove(side: PlayerGestureLayout.Side, delta: Float) {
                if (!gesturesEnabled()) return
                if (side == PlayerGestureLayout.Side.RIGHT) {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val target = (volumeStart + delta * max).roundToInt().coerceIn(0, max)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    showOsd(getString(R.string.player_volume, target * 100 / max))
                } else {
                    brightness = (verticalStart + delta).coerceIn(0.02f, 1f)
                    dim?.alpha = (1f - brightness) * 0.75f
                    showOsd(getString(R.string.player_brightness, (brightness * 100).roundToInt()))
                }
            }

            override fun onVerticalEnd() {
                scheduleHideOsd()
            }

            override fun onSeekStart() {
                if (!gesturesEnabled()) return
                val p = player
                seekStart = p?.currentPosition ?: 0L
                seekDuration = p?.duration?.coerceAtLeast(0L) ?: 0L
                seekTarget = -1L
            }

            override fun onSeekMove(fraction: Float) {
                if (!gesturesEnabled() || seekDuration <= 0L) return
                val target = (seekStart + fraction * SEEK_SPAN_MS).toLong()
                    .coerceIn(0L, seekDuration)
                seekTarget = target
                showOsd(formatTime(target) + " / " + formatTime(seekDuration))
            }

            override fun onSeekEnd() {
                val target = seekTarget
                seekTarget = -1L
                if (target >= 0L) player.seekTo(target)
                scheduleHideOsd()
            }

            override fun onTap() {
                setControlsVisible(!controlsVisible)
            }

            override fun onDoubleTap(side: PlayerGestureLayout.Side) {
                if (!gesturesEnabled()) return
                seekBy(if (side == PlayerGestureLayout.Side.LEFT) -SEEK_STEP_MS else SEEK_STEP_MS)
            }
        }

        playPause?.setOnClickListener { togglePlay() }
        progressBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                positionText?.text = formatTime(progressToMs(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                tracking = true
                view?.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                tracking = false
                if (seekBar != null) player.seekTo(progressToMs(seekBar.progress))
                scheduleHideControls()
            }
        })

        view.findViewById<View>(R.id.player_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.player_prev).setOnClickListener {
            if (position > 0) load(position - 1)
        }
        view.findViewById<View>(R.id.player_next).setOnClickListener {
            if (position < items.size - 1) load(position + 1)
        }
        view.findViewById<View>(R.id.player_rotate).setOnClickListener { toggleOrientation() }
        view.findViewById<View>(R.id.player_rewind).setOnClickListener { seekBy(-SEEK_STEP_MS) }
        view.findViewById<View>(R.id.player_forward).setOnClickListener { seekBy(SEEK_STEP_MS) }
        view.findViewById<View>(R.id.player_download).setOnClickListener { download() }

        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        load(position)
        applyImmersive()
        view.post {
            updateProgress()
            view.postDelayed(ticker, 500L)
            scheduleHideControls()
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    parentFragmentManager.popBackStack()
                }
            }
        )
    }

    private fun gesturesEnabled(): Boolean = AppServices.settings.videoGesture()

    private fun togglePlay() {
        val p = player ?: return
        if (p.playWhenReady) p.pause() else p.play()
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration
        if (duration <= 0L) return
        val target = (p.currentPosition + deltaMs).coerceIn(0L, duration)
        p.seekTo(target)
        showOsd(formatTime(target) + " / " + formatTime(duration))
        scheduleHideOsd()
    }

    /** 切换播放项：Cookie/Referer 按该资源自身计算，否则防盗链站会 403。 */
    private fun load(index: Int) {
        val res = items.getOrNull(index) ?: return
        val player = player ?: return
        position = index
        val mediaItem = MediaItem.Builder()
            .setUri(res.url)
            .setMimeType(if (res.type == SniffType.HLS) MimeTypes.APPLICATION_M3U8 else null)
            .build()
        player.setMediaSource(
            DefaultMediaSourceFactory(httpDataSourceFactory(res)).createMediaSource(mediaItem)
        )
        player.prepare()
        player.playWhenReady = true

        view?.findViewById<TextView>(R.id.player_title)?.text = fileName(res.url)
        view?.findViewById<TextView>(R.id.player_count)?.apply {
            visibility = if (items.size > 1) View.VISIBLE else View.GONE
            text = "${index + 1} / ${items.size}"
        }
        val prev = view?.findViewById<View>(R.id.player_prev)
        val next = view?.findViewById<View>(R.id.player_next)
        if (items.size > 1) {
            prev?.alpha = if (index > 0) 1f else 0.3f
            next?.alpha = if (index < items.size - 1) 1f else 0.3f
        } else {
            prev?.alpha = 0.3f
            next?.alpha = 0.3f
        }
        updateProgress()
    }

    private fun updateProgress() {
        val p = player ?: return
        val duration = p.duration.coerceAtLeast(0L)
        durationText?.text = formatTime(duration)
        if (tracking) return
        positionText?.text = formatTime(p.currentPosition)
        if (duration > 0L) {
            progressBar?.progress =
                (p.currentPosition * 1000 / duration).toInt().coerceIn(0, 1000)
        }
    }

    private fun progressToMs(progress: Int): Long {
        val duration = player?.duration ?: 0L
        if (duration <= 0L) return 0L
        return duration * progress / 1000
    }

    private fun setControlsVisible(show: Boolean) {
        controlsVisible = show
        listOfNotNull(topBar, bottomBar).forEach { bar ->
            bar.animate().cancel()
            if (show) {
                bar.visibility = View.VISIBLE
                bar.alpha = 1f
            } else {
                bar.animate().alpha(0f).setDuration(150).withEndAction {
                    if (!controlsVisible) bar.visibility = View.GONE
                }.start()
            }
        }
        val host = view ?: return
        host.removeCallbacks(hideControls)
        if (show) scheduleHideControls()
    }

    private fun scheduleHideControls() {
        val host = view ?: return
        host.removeCallbacks(hideControls)
        if (player?.isPlaying == true && controlsVisible) {
            host.postDelayed(hideControls, CONTROLS_HIDE_DELAY_MS)
        }
    }

    private fun showOsd(text: String) {
        val host = view ?: return
        host.removeCallbacks(hideOsd)
        osd?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun scheduleHideOsd() {
        val host = view ?: return
        host.removeCallbacks(hideOsd)
        host.postDelayed(hideOsd, OSD_HIDE_DELAY_MS)
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requireActivity().requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    /** 横屏时进沉浸式（隐藏系统栏），竖屏恢复；由方向按钮或设备旋转触发。 */
    private fun applyImmersive() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (landscape) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive()
    }

    private fun httpDataSourceFactory(res: SniffedResource): DefaultHttpDataSource.Factory {
        val cookie = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(res.url)
        }.getOrNull()
        val headers = mutableMapOf<String, String>()
        (res.pageUrl ?: referer)?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        return DefaultHttpDataSource.Factory()
            .setUserAgent(
                userAgent ?: android.webkit.WebSettings.getDefaultUserAgent(requireContext())
            )
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
    }

    private fun download() {
        val context = context ?: return
        val res = items.getOrNull(position) ?: return
        if (res.type == SniffType.HLS) {
            DownloadService.startHlsDownload(context, res.url, null, res.pageUrl ?: referer, userAgent)
        } else {
            DownloadService.startDownload(context, res.url, null, null)
        }
        Toast.makeText(context, R.string.sniff_download_started, Toast.LENGTH_SHORT).show()
    }

    private fun fileName(url: String): String =
        runCatching { Uri.parse(url).lastPathSegment }
            .getOrNull().orEmpty().ifEmpty { url }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (resumeOnReturn) player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        resumeOnReturn = player?.playWhenReady == true
        player?.playWhenReady = false
    }

    override fun onDestroyView() {
        val host = view
        host?.removeCallbacks(ticker)
        host?.removeCallbacks(hideOsd)
        host?.removeCallbacks(hideControls)
        host?.findViewById<PlayerView>(R.id.player_view)?.player = null
        player?.release()
        player = null
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        (activity as? ShellActivity)?.applyOrientation()
        dim = null
        osd = null
        topBar = null
        bottomBar = null
        playPause = null
        progressBar = null
        positionText = null
        durationText = null
        buffering = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_ITEMS = "items"
        private const val ARG_INDEX = "index"
        private const val ARG_REFERER = "referer"
        private const val ARG_UA = "ua"

        /** 横向滑满屏对应的进度跨度。 */
        private const val SEEK_SPAN_MS = 90_000f
        private const val SEEK_STEP_MS = 10_000L
        private const val CONTROLS_HIDE_DELAY_MS = 3_000L
        private const val OSD_HIDE_DELAY_MS = 700L

        fun newInstance(
            items: List<SniffedResource>,
            index: Int,
            referer: String?,
            userAgent: String?
        ): PlayerFragment = PlayerFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_ITEMS, encode(items))
                putInt(ARG_INDEX, index)
                putString(ARG_REFERER, referer)
                putString(ARG_UA, userAgent)
            }
        }

        private fun encode(items: List<SniffedResource>): String {
            val array = JSONArray()
            items.forEach {
                array.put(
                    JSONObject()
                        .put("u", it.url)
                        .put("t", it.type.name)
                        .put("p", it.pageUrl.orEmpty())
                )
            }
            return array.toString()
        }

        private fun decode(raw: String?): List<SniffedResource> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    val obj = array.optJSONObject(i) ?: return@mapNotNull null
                    val url = obj.optString("u")
                    if (url.isBlank()) return@mapNotNull null
                    SniffedResource(
                        url = url,
                        type = runCatching { SniffType.valueOf(obj.optString("t")) }
                            .getOrDefault(SniffType.VIDEO),
                        pageUrl = obj.optString("p").ifBlank { null }
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}
