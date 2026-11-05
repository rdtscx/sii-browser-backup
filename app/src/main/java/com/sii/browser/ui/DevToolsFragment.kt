// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.sii.browser.R
import com.sii.browser.core.webview.JsConsoleStore
import com.sii.browser.core.webview.PageRequests
import com.sii.browser.core.webview.SiiWebView
import com.sii.browser.di.AppServices
import org.json.JSONArray

/**
 * 应用内开发者工具：控制台 / 元素 / 网络 / 信息。
 * 数据全部来自现有 JS 桥、[JsConsoleStore] 与 [PageRequests]，不引入 CDP 与前端资源。
 */
class DevToolsFragment : Fragment() {

    private var currentTab = 0
    private lateinit var tabsRow: LinearLayout
    private lateinit var body: LinearLayout
    private var webView: SiiWebView? = null
    private val labels = arrayOf("控制台", "元素", "网络", "信息")

    override fun onCreateView(
        inflater: LayoutInflater,
        parent: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ctx.getColor(R.color.bg))
        }
        root.addView(
            PageHeader.build(ctx, "开发者工具", onBack = { parentFragmentManager.popBackStack() })
        )
        tabsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        labels.forEachIndexed { index, label ->
            tabsRow.addView(
                TextView(ctx).apply {
                    text = label
                    setTextSize(13f)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        currentTab = index
                        renderTabs()
                        render()
                    }
                }
            )
        }
        root.addView(tabsRow)
        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView = AppServices.sniffTarget
        JsConsoleStore.onChange = { view.post { if (currentTab == 0) renderConsole() } }
        renderTabs()
        render()
    }

    override fun onDestroyView() {
        JsConsoleStore.onChange = null
        super.onDestroyView()
    }

    private fun renderTabs() {
        val accent = ThemeAccent.color(requireContext())
        for (i in 0 until tabsRow.childCount) {
            (tabsRow.getChildAt(i) as? TextView)?.setTextColor(
                if (i == currentTab) accent else requireContext().getColor(R.color.text_secondary)
            )
        }
    }

    private fun render() {
        body.removeAllViews()
        when (currentTab) {
            0 -> renderConsole()
            1 -> renderElements()
            2 -> renderNetwork()
            else -> renderInfo()
        }
    }

    // --- 控制台 ---

    private var consoleOutput: TextView? = null
    private var consoleScroll: ScrollView? = null

    private fun renderConsole() {
        if (consoleOutput == null) {
            val ctx = requireContext()
            val outputView = TextView(ctx).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextSize(10f)
                setTextColor(ctx.getColor(R.color.text_primary))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            consoleOutput = outputView
            consoleScroll = ScrollView(ctx).apply { addView(outputView) }
            body.addView(
                consoleScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
            val input = EditText(ctx).apply {
                hint = getString(R.string.js_console_hint)
                setSingleLine(true)
                setTextSize(13f)
            }
            val run = Button(ctx).apply {
                text = getString(R.string.js_console_run)
                setOnClickListener {
                    val code = input.text.toString().trim()
                    if (code.isEmpty()) return@setOnClickListener
                    webView?.let { wv ->
                        JsConsoleStore.add("INPUT", "", 0, code)
                        wv.evaluateJavascript(code) { result ->
                            JsConsoleStore.add("RESULT", "", 0, result.orEmpty())
                        }
                    }
                    input.setText("")
                }
            }
            val clear = Button(ctx).apply {
                text = getString(R.string.js_console_clear)
                setOnClickListener { JsConsoleStore.clear(); renderConsole() }
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
                addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(run)
                addView(clear)
            }
            body.addView(row)
        }
        val entries = JsConsoleStore.snapshot()
        consoleOutput?.text = if (entries.isEmpty()) {
            "（暂无输出）"
        } else {
            entries.joinToString("\n") { entry ->
                val tag = entry.level.ifBlank { "LOG" }
                "[$tag] ${entry.message}"
            }
        }
        consoleScroll?.post { consoleScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // --- 元素 ---

    private fun renderElements() {
        val ctx = requireContext()
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val reload = Button(ctx).apply {
            text = "刷新"
            setOnClickListener { renderElements() }
        }
        bar.addView(reload)
        bar.addView(TextView(ctx).apply {
            text = "  限制 500 个节点，点击查看属性"
            setTextSize(11f)
            setTextColor(ctx.getColor(R.color.text_secondary))
        })
        body.addView(bar)

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply { addView(list) }
        body.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val script = "(function(){try{var out=[];var walk=function(el,d){if(!el||out.length>500||d>12)return;" +
            "var c=el.className;if(c&&c.baseVal!==undefined)c=c.baseVal;" +
            "out.push({d:d,t:el.tagName.toLowerCase(),i:el.id||'',c:(c||'').toString().slice(0,60)," +
            "x:(el.innerText||'').trim().slice(0,40)});" +
            "for(var i=0;i<el.children.length;i++)walk(el.children[i],d+1);};" +
            "if(document.body)walk(document.body,0);return JSON.stringify(out);}catch(e){return 'ERR:'+e}})()"
        webView?.injectJavaScript(script) { raw ->
            if (!isAdded) return@injectJavaScript
            val arr = runCatching { JSONArray(raw.orEmpty()) }.getOrNull()
            if (arr == null) {
                list.addView(hint("读取失败：${raw ?: "无结果"}"))
                return@injectJavaScript
            }
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val depth = item.optInt("d")
                val tag = item.optString("t")
                val id = item.optString("i")
                val cls = item.optString("c")
                val text = item.optString("x")
                val label = buildString {
                    append(tag)
                    if (id.isNotBlank()) append('#').append(id)
                    if (cls.isNotBlank()) append('.').append(cls.substringBefore(' '))
                    if (text.isNotBlank()) append("  ").append(text)
                }
                list.addView(TextView(ctx).apply {
                    this.text = label
                    setTextSize(11f)
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(dp(8 + depth * 12), dp(4), dp(8), dp(4))
                    setTextColor(ctx.getColor(R.color.text_primary))
                    setOnClickListener {
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("元素")
                            .setMessage(label)
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                })
            }
        }
    }

    // --- 网络 ---

    private fun renderNetwork() {
        val ctx = requireContext()
        val wv = webView
        if (wv == null) {
            body.addView(hint("没有可用的网页"))
            return
        }
        val entries = PageRequests.list(wv).reversed()
        if (entries.isEmpty()) {
            body.addView(hint("暂无记录（需在设置里开启请求记录）"))
            return
        }
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        entries.forEach { entry ->
            list.addView(TextView(ctx).apply {
                text = "${entry.method}  ${entry.kind}  ${shortUrl(entry.url)}" +
                    if (entry.blocked) "  [已拦截]" else ""
                setTextSize(11f)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setTextColor(
                    if (entry.blocked) ThemeAccent.color(ctx)
                    else ctx.getColor(R.color.text_primary)
                )
                setOnClickListener {
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("${entry.method} ${entry.kind}")
                        .setMessage(
                            entry.url + "\n\n" +
                                (if (entry.blocked) "已拦截\n" else "") +
                                (if (entry.mainFrame) "主框架请求" else "子资源请求")
                        )
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            })
        }
        val scroll = ScrollView(ctx).apply { addView(list) }
        body.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    // --- 信息 ---

    private fun renderInfo() {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val card = CardList.pageCard(ctx)
        content.addView(card)
        val scroll = ScrollView(ctx).apply { addView(content) }
        body.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val script = "(function(){try{return JSON.stringify({" +
            "url:location.href,title:document.title," +
            "ua:navigator.userAgent," +
            "vw:window.innerWidth,vh:window.innerHeight,dpr:window.devicePixelRatio||1," +
            "ls:(function(){try{return localStorage.length}catch(e){return -1}})()," +
            "ss:(function(){try{return sessionStorage.length}catch(e){return -1}})()," +
            "cookie:(document.cookie||'').split(';').filter(function(s){return s.trim()}).length" +
            "});}catch(e){return 'ERR:'+e}})()"
        webView?.injectJavaScript(script) { raw ->
            if (!isAdded) return@injectJavaScript
            val json = runCatching { org.json.JSONObject(raw.orEmpty()) }.getOrNull()
            if (json == null) {
                CardList.addRows(card, listOf(hint("读取失败：${raw ?: "无结果"}")))
                return@injectJavaScript
            }
            val rows = listOf(
                infoRow("URL", json.optString("url")),
                infoRow("标题", json.optString("title")),
                infoRow("视口", "${json.optInt("vw")} × ${json.optInt("vh")}"),
                infoRow("DPR", json.optString("dpr")),
                infoRow("localStorage", json.optInt("ls").toString()),
                infoRow("sessionStorage", json.optInt("ss").toString()),
                infoRow("Cookie", json.optInt("cookie").toString()),
                infoRow("UA", json.optString("ua"))
            )
            CardList.addRows(card, rows)
        }
    }

    private fun infoRow(title: String, value: String): View {
        val ctx = requireContext()
        val name = TextView(ctx).apply {
            text = title
            setTextSize(12f)
            setTextColor(ctx.getColor(R.color.text_secondary))
        }
        val body = TextView(ctx).apply {
            text = value
            setTextSize(12f)
            setTextColor(ctx.getColor(R.color.text_primary))
            setTextIsSelectable(true)
        }
        return CardList.row(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(name)
            addView(body)
        }
    }

    private fun hint(text: String): View = TextView(requireContext()).apply {
        this.text = text
        setTextSize(12f)
        setTextColor(requireContext().getColor(R.color.text_secondary))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun shortUrl(url: String): String {
        val clean = url.substringAfter("://", url)
        return if (clean.length > 90) clean.take(90) + "…" else clean
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
