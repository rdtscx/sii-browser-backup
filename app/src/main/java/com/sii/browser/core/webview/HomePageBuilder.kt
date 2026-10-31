// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.sii.browser.data.SearchEngines
import com.sii.browser.data.Settings
import com.sii.browser.data.db.FavoriteItem
import org.json.JSONArray
import java.io.ByteArrayOutputStream

/**
 * 把当前设置与常用网站组装成网页主页的一次渲染参数。
 *
 * 浏览器主页与设置页预览共用，避免两处拼参数漂移；背景图/图标转 data URI 后缓存。
 */
object HomePageBuilder {

    private var logoData: String? = null

    fun build(
        context: Context,
        settings: Settings,
        favorites: List<FavoriteItem>,
        accentColor: Int,
        backgroundOffsetPx: Int = 0,
        cssOverride: String? = null
    ): HomeWebView.Page {
        val engines = runCatching {
            JSONArray(settings.searchEngines().orEmpty())
        }.getOrNull()
        val engineList = mutableListOf<Pair<String, String>>()
        if (engines != null) {
            for (i in 0 until engines.length()) {
                val item = engines.optJSONObject(i) ?: continue
                val name = item.optString("name")
                val url = item.optString("url")
                if (name.isNotBlank() && url.isNotBlank()) engineList.add(name to url)
            }
        }
        if (engineList.isEmpty()) engineList.addAll(SearchEngines.DEFAULT)
        val pageCount = settings.homeSitePages().coerceIn(1, 5)
        val sitePages = (0 until pageCount).map { page ->
            favorites.filter { it.page == page }
                .sortedBy { it.order }
                .take(30)
                .map { it.title to it.url }
        }
        return HomeWebView.Page(
            template = settings.homePageMode() == 1,
            url = settings.homeUrl(),
            css = cssOverride ?: settings.homeCustomCss(),
            title = settings.homeLogoText().orEmpty(),
            night = settings.isNightMode(),
            accent = String.format("#%06X", 0xFFFFFF and accentColor),
            searchBase = settings.searchEngineUrl() ?: SearchEngines.DEFAULT.first().second,
            engines = engineList,
            sitePages = sitePages,
            showSites = settings.showSites(),
            background = HomeBackground.load(
                context,
                settings.homeBackground(),
                settings.homeBgOverlay().coerceIn(0, 100) + if (settings.isNightMode()) 55 else 0,
                blurPercent(settings)
            )?.let { HomeBackground.dataUri(it) }.orEmpty(),
            backgroundOffsetPx = backgroundOffsetPx,
            logoImage = if (settings.homeLogoMode() == 1) logo(context) else "",
            customHtml = settings.homeCustomHtml(),
            customHtmlPos = settings.homeCustomHtmlPos()
        )
    }

    /** 背景模糊百分比（与主页预览、窗口背景保持一致）。 */
    private fun blurPercent(settings: Settings): Int {
        val percent = settings.homeBgBlurPercent()
        if (percent >= 0) return percent.coerceIn(0, 100)
        return when (settings.homeBgBlur()) {
            1 -> 33
            2 -> 66
            3 -> 100
            else -> 0
        }
    }

    /** 主页图标 Logo → data URI（矢量光栅化一次后缓存）。 */
    private fun logo(context: Context): String {
        logoData?.let { return it }
        val data = runCatching {
            val drawable = androidx.core.content.ContextCompat
                .getDrawable(context, com.sii.browser.R.drawable.ic_logo_home)
                ?: return@runCatching ""
            val size = 128
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(android.graphics.Canvas(bitmap))
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }.getOrDefault("")
        logoData = data
        return data
    }
}
