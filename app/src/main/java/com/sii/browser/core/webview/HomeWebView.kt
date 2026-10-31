// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.sii.browser.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网页主页容器（独立于标签栈，挂在 home_container 里）。
 *
 * 两种数据源：内置 HTML 模板（注入常用网站/搜索引擎/主色/夜间与自定义 CSS）
 * 或用户指定的主页地址（本地 HTML / 网址，自定义 CSS 另行注入）。
 * 页面里的搜索与跳转通过 `window.sii` 桥交回原生，避免污染标签历史。
 */
class HomeWebView(
    context: Context,
    private val onSearch: (String) -> Unit,
    private val onOpen: (String) -> Unit,
    private val onSiteMenu: (String) -> Unit,
    private val onSetDefaultEngine: (String) -> Unit,
    private val onSuggest: (String) -> Unit,
    private val onReorderSites: (String) -> Unit
) : WebView(context) {

    /** 一次渲染所需的全部状态；签名不变时不重新加载页面。 */
    data class Page(
        val template: Boolean,
        val url: String,
        val css: String,
        val title: String,
        val night: Boolean,
        val accent: String,
        val searchBase: String,
        val engines: List<Pair<String, String>>,
        /** 每页一个列表（index = 页码），每项 名称 to 地址。 */
        val sitePages: List<List<Pair<String, String>>> = emptyList(),
        /** 是否显示常用网站网格（跟随原生「显示常用网站」开关）。 */
        val showSites: Boolean = true,
        /** 主页背景图（data URI），空 = 纯色底。 */
        val background: String = "",
        /** 标题图标（data URI），空 = 显示文字。 */
        val logoImage: String = "",
        /** 自定义 HTML 片段，插入位置见 [customHtmlPos]。 */
        val customHtml: String = "",
        /** 0 搜索框下方 / 1 页面底部 / 2 页面顶部。 */
        val customHtmlPos: Int = 0
    ) {
        fun signature(): String = buildString {
            append(template).append('|').append(url).append('|').append(css).append('|')
            append(title).append('|').append(night).append('|').append(accent).append('|')
            append(searchBase).append('|')
            append(background.hashCode()).append('|').append(logoImage.hashCode()).append('|')
            append(customHtml.hashCode()).append('|').append(customHtmlPos).append('|')
            append(showSites).append('|')
            sitePages.forEachIndexed { page, list ->
                append(page).append(':')
                list.forEach { append(it.first).append('→').append(it.second).append(';') }
                append('|')
            }
            engines.forEach { append(it.first).append('→').append(it.second).append(';') }
            append('|')
        }
    }

    private var appliedSignature = ""
    private var pendingCss = ""
    private var templateLoaded = false

    init {
        setBackgroundColor(Color.WHITE)
        settings.apply {
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100
            mediaPlaybackRequiresUserGesture = false
        }
        addJavascriptInterface(Bridge(), "sii")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = intercept(request.url.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                intercept(url)

            override fun onPageFinished(view: WebView, url: String) {
                injectCss()
            }
        }
    }

    /** 主页页面内的跳转一律交给标签打开，主页自身不产生历史。 */
    private fun intercept(url: String): Boolean {
        if (url.isBlank() || url == "about:blank") return false
        if (url.startsWith(BASE_URL)) return false
        // 自定义主页可能是多页的本地文件，同目录跳转留在主页内，外部链接才开新标签。
        if (url.startsWith("file:")) return false
        onOpen(url)
        return true
    }

    fun render(page: Page) {
        val signature = page.signature()
        pendingCss = page.css
        if (signature == appliedSignature) return
        appliedSignature = signature
        setBackgroundColor(if (page.night) 0xFF121417.toInt() else 0xFFF6F7F9.toInt())
        if (page.template) {
            val html = templateHtml(page)
            if (html != null) {
                templateLoaded = true
                loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
                return
            }
        }
        templateLoaded = false
        loadUrl(page.url.ifBlank { "about:blank" })
    }

    /** 外部修改（自定义 CSS / 主页地址）后强制重载。 */
    fun reset() {
        appliedSignature = ""
    }

    private fun templateHtml(page: Page): String? = runCatching {
        val raw = context.resources.openRawResource(R.raw.home_template)
            .bufferedReader()
            .use { it.readText() }
        val css = sanitizeCss(page.css)
        val themeCss = buildString {
            if (page.background.isNotBlank()) {
                append("body{background-image:")
                if (page.night) {
                    append("linear-gradient(rgba(0,0,0,.55),rgba(0,0,0,.55)),")
                }
                append("url(\"").append(page.background).append("\");}")
            }
            if (page.logoImage.isNotBlank()) {
                append(".page > #logo{background:none;-webkit-text-fill-color:initial;}")
            }
        }
        val config = JSONObject().apply {
            put("title", page.title)
            put("night", if (page.night) 1 else 0)
            put("accent", page.accent)
            put("logoImage", page.logoImage)
            put("customHtml", page.customHtml)
            put("customHtmlPos", page.customHtmlPos)
            put("showSites", page.showSites)
            put("searchBase", page.searchBase)
            put("engines", JSONArray().apply {
                page.engines.forEach { (name, url) ->
                    put(JSONObject().apply { put("name", name); put("url", url) })
                }
            })
            put("pages", JSONArray().apply {
                page.sitePages.forEach { list ->
                    put(JSONArray().apply {
                        list.forEach { (name, url) ->
                            put(JSONObject().apply { put("title", name); put("url", url) })
                        }
                    })
                }
            })
        }.toString().replace("<", "\\u003c")
        raw.replace("/*__SII_THEME__*/", themeCss)
            .replace("/*__SII_CSS__*/", css)
            .replace("__SII_CONFIG__", config)
    }.getOrNull()

    /** 自定义地址主页：整页加载后追加用户 CSS，不干扰页面自身样式表。 */
    private fun injectCss() {
        if (templateLoaded) return
        val css = sanitizeCss(pendingCss)
        if (css.isBlank()) return
        val quoted = JSONObject.quote(css)
        evaluateJavascript(
            "(function(){var s=document.getElementById('sii-home-css');" +
                "if(!s){s=document.createElement('style');s.id='sii-home-css';" +
                "document.head.appendChild(s);}s.textContent=$quoted;})();",
            null
        )
    }

    /** 自定义 CSS 会被拼进 <style>：去掉可能提前闭合标签的片段，并限制体积。 */
    private fun sanitizeCss(css: String): String =
        css.take(200_000).replace("</style", "<\\/style", ignoreCase = true)

    private inner class Bridge {
        @JavascriptInterface
        fun search(query: String) {
            val value = query.trim()
            if (value.isEmpty()) return
            post { onSearch(value) }
        }

        @JavascriptInterface
        fun open(url: String) {
            val value = url.trim()
            if (value.isEmpty()) return
            post { onOpen(value) }
        }

        @JavascriptInterface
        fun menuSite(url: String) {
            val value = url.trim()
            if (value.isEmpty()) return
            post { onSiteMenu(value) }
        }

        @JavascriptInterface
        fun setDefaultEngine(url: String) {
            val value = url.trim()
            if (value.isEmpty()) return
            post { onSetDefaultEngine(value) }
        }

        @JavascriptInterface
        fun suggest(query: String) {
            val value = query.trim()
            if (value.isEmpty()) return
            post { onSuggest(value) }
        }

        @JavascriptInterface
        fun reorderSites(urlsJson: String) {
            if (urlsJson.isBlank()) return
            post { onReorderSites(urlsJson) }
        }
    }

    /** 把原生建议（历史/书签/联网）回送页面；页面按 query 自行判断是否过期。 */
    fun deliverSuggestions(query: String, itemsJson: String) {
        val script = "window.siiOnSuggest&&window.siiOnSuggest(" +
            JSONObject.quote(query) + "," + JSONObject.quote(itemsJson) + ");"
        evaluateJavascript(script, null)
    }

    companion object {
        /** 模板的虚拟来源：给页面一个稳定的 https 来源，localStorage 等才可用。 */
        private const val BASE_URL = "https://sii.home/"
    }
}
