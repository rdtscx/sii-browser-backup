// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import org.json.JSONArray
import org.json.JSONObject

/**
 * 抖音分享链接处理：识别私有 scheme / 分享页，转成网页地址，并尽力解析直链。
 *
 * 只请求抖音自己的分享页，不经过任何第三方解析服务；解析失败时由调用方回落到
 * “打开页面 + 资源嗅探”。
 */
object DouyinLinks {

    data class Detected(val id: String?, val shortCode: String?)

    data class Result(
        val desc: String,
        val videos: List<Pair<String, String>>,
        val images: List<String>
    )

    private val schemeRegex = Regex("snssdk\\w*://aweme/detail/(\\d{6,})", RegexOption.IGNORE_CASE)
    private val shareRegex = Regex("iesdouyin\\.com/share/(?:video|note|slides)/(\\d{6,})", RegexOption.IGNORE_CASE)
    private val shortRegex = Regex("(?:v|www)\\.douyin\\.com/([A-Za-z0-9_-]{4,})", RegexOption.IGNORE_CASE)

    private const val UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

    fun detect(raw: String): Detected? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        schemeRegex.find(text)?.let { return Detected(it.groupValues[1], null) }
        shareRegex.find(text)?.let { return Detected(it.groupValues[1], null) }
        shortRegex.find(text)?.let { return Detected(null, it.groupValues[1]) }
        return null
    }

    fun shareUrl(id: String): String = "https://www.iesdouyin.com/share/video/$id"

    fun shortUrl(code: String): String = "https://v.douyin.com/$code/"

    fun isDouyinHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h.endsWith("douyin.com") || h.endsWith("iesdouyin.com")
    }

    private val client = okhttp3.OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun get(url: String): String? = runCatching {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://www.douyin.com/")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    }.getOrNull()

    data class Resolved(val id: String, val url: String)

    /** 短链解析结果缓存：短时间内重复打开复用第一次的地址，避免重复请求触发风控。 */
    private data class Cached(val resolved: Resolved, val at: Long)
    private val shortCache = java.util.concurrent.ConcurrentHashMap<String, Cached>()
    private const val CACHE_MS = 10 * 60 * 1000L

    /** 短链跟一次重定向，返回作品 id 和带参数的完整地址。 */
    fun resolveShort(code: String): Resolved? {
        shortCache[code]?.let { cached ->
            if (System.currentTimeMillis() - cached.at < CACHE_MS) return cached.resolved
        }
        var html: String? = null
        var finalUrl: String? = null
        runCatching {
            val request = okhttp3.Request.Builder()
                .url(shortUrl(code))
                .header("User-Agent", UA)
                .header("Referer", "https://www.douyin.com/")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    html = response.body?.string()
                    finalUrl = response.request.url.toString()
                }
            }
        }
        val page = html ?: return null
        val id = shareRegex.find(page)?.groupValues?.get(1)
            ?: schemeRegex.find(page)?.groupValues?.get(1)
            ?: Regex("\\\"lastPath\\\"\\s*:\\s*\\\"(\\d{6,})\\\"")
                .find(page)?.groupValues?.get(1)
            ?: finalUrl?.let { Regex("/(\\d{6,})").find(it)?.groupValues?.get(1) }
            ?: return null
        return Resolved(id, finalUrl ?: shareUrl(id)).also {
            shortCache[code] = Cached(it, System.currentTimeMillis())
        }
    }

    fun parse(id: String): Result? {
        val html = get(shareUrl(id)) ?: return null
        val jsonText = extractRouterData(html) ?: return null
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val info = findObjectWithKey(root, "videoInfoRes") ?: return null
        val item = info.optJSONArray("item_list")?.optJSONObject(0) ?: return null

        val videos = mutableListOf<Pair<String, String>>()
        val video = item.optJSONObject("video")
        video?.optJSONObject("play_addr")?.let { addr ->
            urls(addr)?.forEach { videos.add("视频" to it) }
        }
        video?.optJSONArray("bit_rate")?.let { list ->
            for (i in 0 until list.length()) {
                val gear = list.optJSONObject(i) ?: continue
                val label = gear.optString("gear_name").ifBlank { "视频${i + 1}" }
                gear.optJSONObject("play_addr")?.let { addr ->
                    urls(addr)?.firstOrNull()?.let { videos.add(label to it) }
                }
            }
        }

        val images = mutableListOf<String>()
        item.optJSONArray("images")?.let { list ->
            for (i in 0 until list.length()) {
                val image = list.optJSONObject(i) ?: continue
                image.optJSONArray("url_list")?.let { urls ->
                    for (j in 0 until urls.length()) {
                        val url = urls.optString(j)
                        if (url.isNotBlank()) {
                            images.add(url)
                            break
                        }
                    }
                }
            }
        }

        val uniqueVideos = videos
            .map { it.first to normalize(it.second) }
            .filter { it.second.isNotBlank() }
            .distinctBy { it.second }
        val uniqueImages = images.map { normalize(it) }.filter { it.isNotBlank() }.distinct()
        if (uniqueVideos.isEmpty() && uniqueImages.isEmpty()) return null
        return Result(item.optString("desc"), uniqueVideos, uniqueImages)
    }

    private fun urls(addr: JSONObject): List<String>? {
        val list = addr.optJSONArray("url_list") ?: return null
        return buildList {
            for (i in 0 until list.length()) {
                val url = list.optString(i)
                if (url.isNotBlank()) add(url)
            }
        }.takeIf { it.isNotEmpty() }
    }

    /** 去水印的常见改法：playwm → play。 */
    private fun normalize(url: String): String = url.replace("playwm", "play")

    private fun extractRouterData(html: String): String? {
        val marker = html.indexOf("_ROUTER_DATA")
        if (marker < 0) return null
        val start = html.indexOf('{', marker)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun findObjectWithKey(node: JSONObject, key: String): JSONObject? {
        if (node.has(key)) return node.optJSONObject(key)
        val names = node.keys()
        while (names.hasNext()) {
            val name = names.next()
            val child = node.optJSONObject(name)
            if (child != null) {
                findObjectWithKey(child, key)?.let { return it }
            }
            val arr = node.optJSONArray(name)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    findObjectWithKey(item, key)?.let { return it }
                }
            }
        }
        return null
    }
}
