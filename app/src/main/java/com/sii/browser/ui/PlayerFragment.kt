// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import com.sii.browser.download.DownloadService
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 内置原生播放页：ExoPlayer/Media3 直接播放嗅探到的 mp4/m3u8，
 * 带当前页面的 Referer/UA/Cookie，解决 WebView HTML5 播不动的问题。
 * 传入的是当前页可播放资源列表，标题栏箭头切换；左/右竖直滑调亮度/音量，横向滑拉进度。
 */
class PlayerFragment : Fragment() {

    private var player: ExoPlayer? = null
    private var items: List<SniffedResource> = emptyList()
    private var position: Int = 0
    private var referer: String? = null
    private var userAgent: String? = null

    private var dim: View? = null
    private var osd: TextView? = null
    private lateinit var audio: AudioManager

    /** 画面亮度 0.02..1，通过黑色遮罩实现，不改系统亮度。 */
    private var brightness = 1f
    private var verticalStart = 1f
    private var volumeStart = 0
    private var seekStart = 0L
    private var seekDuration = 0L
    private var seekTarget = -1L

    private val hideOsd = Runnable { osd?.visibility = View.GONE }

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
        audio = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

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
        })

        (view as PlayerGestureLayout).listener = object : PlayerGestureLayout.Listener {
            override fun onVerticalStart(side: PlayerGestureLayout.Side) {
                if (side == PlayerGestureLayout.Side.RIGHT) {
                    volumeStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                } else {
                    verticalStart = brightness
                }
            }

            override fun onVerticalMove(side: PlayerGestureLayout.Side, delta: Float) {
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
                val p = player
                seekStart = p?.currentPosition ?: 0L
                seekDuration = p?.duration?.coerceAtLeast(0L) ?: 0L
                seekTarget = -1L
            }

            override fun onSeekMove(fraction: Float) {
                if (seekDuration <= 0L) return
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
        }

        view.findViewById<View>(R.id.player_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.player_prev).setOnClickListener {
            if (position > 0) load(position - 1)
        }
        view.findViewById<View>(R.id.player_next).setOnClickListener {
            if (position < items.size - 1) load(position + 1)
        }
        view.findViewById<View>(R.id.player_download).setOnClickListener { download() }
        load(position)

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    parentFragmentManager.popBackStack()
                }
            }
        )
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
            prev?.visibility = View.VISIBLE
            next?.visibility = View.VISIBLE
            prev?.alpha = if (index > 0) 1f else 0.3f
            next?.alpha = if (index < items.size - 1) 1f else 0.3f
        } else {
            prev?.visibility = View.GONE
            next?.visibility = View.GONE
        }
    }

    private fun showOsd(text: String) {
        val view = view ?: return
        view.removeCallbacks(hideOsd)
        osd?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun scheduleHideOsd() {
        val view = view ?: return
        view.removeCallbacks(hideOsd)
        view.postDelayed(hideOsd, 700L)
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
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
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroyView() {
        view?.removeCallbacks(hideOsd)
        view?.findViewById<PlayerView>(R.id.player_view)?.player = null
        player?.release()
        player = null
        dim = null
        osd = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_ITEMS = "items"
        private const val ARG_INDEX = "index"
        private const val ARG_REFERER = "referer"
        private const val ARG_UA = "ua"

        /** 横向滑满屏对应的进度跨度。 */
        private const val SEEK_SPAN_MS = 90_000f

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
