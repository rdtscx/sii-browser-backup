// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

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

/**
 * 内置原生播放页：ExoPlayer/Media3 直接播放嗅探到的 mp4/m3u8，
 * 带当前页面的 Referer/UA/Cookie，解决 WebView HTML5 播不动的问题。
 * 传入的是当前页可播放资源列表，左右滑动切换。
 */
class PlayerFragment : Fragment() {

    private var player: ExoPlayer? = null
    private var items: List<SniffedResource> = emptyList()
    private var position: Int = 0
    private var referer: String? = null
    private var userAgent: String? = null

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
        (view as? SwipeFrameLayout)?.onHorizontalSwipe = { right ->
            val next = if (right) position - 1 else position + 1
            if (next in items.indices) load(next)
        }
        load(position)

        view.findViewById<View>(R.id.player_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.player_download).setOnClickListener { download() }

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

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        player?.playWhenReady = false
    }

    override fun onDestroyView() {
        view?.findViewById<PlayerView>(R.id.player_view)?.player = null
        player?.release()
        player = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_ITEMS = "items"
        private const val ARG_INDEX = "index"
        private const val ARG_REFERER = "referer"
        private const val ARG_UA = "ua"

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
