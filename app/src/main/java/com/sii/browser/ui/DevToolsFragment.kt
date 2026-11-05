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
import org.json.JSONObject

/**
 * 应用内开发者工具：控制台 / 元素 / 网络 / 信息。
 * 数据来自 onConsoleMessage、[JsConsoleStore]、[PageRequests] 与 JS 桥，不引入 CDP。
 */
class DevToolsFragment : Fragment() {

    private var currentTab = 0
    private lateinit var tabsRow: LinearLayout
    private lateinit var body: LinearLayout
    private var webView: SiiWebView? = null
    private val labels = arrayOf("控制台", "元素", "网络", "信息")

    // 元素树状态
    private data class Node(
        val depth: Int,
        val tag: String,
        val id: String,
        val cls: String,
        val text: String,
        val parent: Int,
        val hasChildren: Boolean
    )

    private var nodes: List<Node> = emptyList()
    private val expanded = mutableSetOf<Int>()
    private var elementsLoading = false

    // 过滤
    private var consoleFilter = "ALL"
    private var networkFilter = "ALL"

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
        // 打开面板即开始记录控制台（默认设置是关的，否则这里永远是空的）。
        if (!AppServices.settings.jsConsoleLog()) {
            AppServices.settings.setJsConsoleLog(true)
        }
        JsConsoleStore.onChange = { view.post { if (currentTab == 0) renderConsoleList() } }
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
    private var consoleFilterRow: LinearLayout? = null

    private fun renderConsole() {
        if (consoleOutput == null) {
            val ctx = requireContext()
            val bar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            consoleFilterRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            bar.addView(consoleFilterRow, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(Button(ctx).apply {
                text = "清空"
                setTextSize(12f)
                setOnClickListener { JsConsoleStore.clear(); renderConsoleList() }
            })
            body.addView(bar)

            val outputView = TextView(ctx).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextSize(10f)
                setTextColor(ctx.getColor(R.color.text_primary))
                setPadding(dp(8), dp(6), dp(8), dp(8))
                setTextIsSelectable(true)
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
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(8))
                addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(run)
            }
            body.addView(row)
        }
        renderConsoleFilters()
        renderConsoleList()
    }

    private fun renderConsoleFilters() {
        val row = consoleFilterRow ?: return
        row.removeAllViews()
        val items = listOf("ALL" to "全部", "LOG" to "LOG", "WARNING" to "警告", "ERROR" to "错误")
        items.forEach { (key, label) ->
            row.addView(chip(label, consoleFilter == key) {
                consoleFilter = key
                renderConsoleFilters()
                renderConsoleList()
            })
        }
    }

    private fun renderConsoleList() {
        val output = consoleOutput ?: return
        val entries = JsConsoleStore.snapshot().filter {
            consoleFilter == "ALL" || it.level.equals(consoleFilter, true)
        }
        output.text = if (entries.isEmpty()) {
            "（暂无输出，只记录打开本页之后的控制台信息）"
        } else {
            entries.joinToString("\n") { entry ->
                val time = android.text.format.DateFormat.format("HH:mm:ss", entry.time)
                "$time [${entry.level}] ${entry.message}"
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
        bar.addView(Button(ctx).apply {
            text = "刷新"
            setTextSize(12f)
            setOnClickListener {
                elementsLoading = false
                nodes = emptyList()
                expanded.clear()
                loadTree()
            }
        })
        bar.addView(TextView(ctx).apply {
            text = "  点击展开 / 查看，长按复制选择器"
            setTextSize(11f)
            setTextColor(ctx.getColor(R.color.text_secondary))
        })
        body.addView(bar)

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        body.addView(
            ScrollView(ctx).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        if (elementsLoading) {
            list.addView(hint("读取中…"))
            return
        }
        if (nodes.isEmpty()) {
            loadTree()
            return
        }
        renderTreeRows(list)
    }

    private fun loadTree() {
        if (elementsLoading) return
        elementsLoading = true
        val script = "(function(){try{var out=[];var stack=[];var walk=function(el,d,parent){" +
            "if(!el||out.length>=800||d>18)return;var idx=out.length;" +
            "var c=el.className;if(c&&c.baseVal!==undefined)c=c.baseVal;" +
            "out.push({d:d,t:el.tagName.toLowerCase(),i:el.id||'',c:(c||'').toString().slice(0,60)," +
            "x:(el.innerText||'').trim().slice(0,40),p:parent,k:el.children.length>0});" +
            "for(var j=0;j<el.children.length;j++)walk(el.children[j],d+1,idx);};" +
            "if(document.body)walk(document.body,0,-1);return JSON.stringify(out);" +
            "}catch(e){return 'ERR:'+e}})()"
        webView?.injectJavaScript(script) { raw ->
            if (!isAdded) return@injectJavaScript
            elementsLoading = false
            val arr = runCatching { JSONArray(raw.orEmpty()) }.getOrNull()
            nodes = if (arr == null) emptyList() else buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(
                        Node(
                            depth = item.optInt("d"),
                            tag = item.optString("t"),
                            id = item.optString("i"),
                            cls = item.optString("c"),
                            text = item.optString("x"),
                            parent = item.optInt("p", -1),
                            hasChildren = item.optBoolean("k")
                        )
                    )
                }
            }
            // 默认展开前两层，避免一屏全是折叠行。
            expanded.clear()
            nodes.forEachIndexed { index, node ->
                if (node.hasChildren && node.depth < 2) expanded.add(index)
            }
            render()
        }
    }

    private fun renderTreeRows(list: LinearLayout) {
        val ctx = requireContext()
        list.removeAllViews()
        nodes.forEachIndexed { index, node ->
            if (!isVisible(index)) return@forEachIndexed
            val arrow = when {
                !node.hasChildren -> "  "
                expanded.contains(index) -> "▾ "
                else -> "▸ "
            }
            val label = buildString {
                append(arrow)
                append(node.tag)
                if (node.id.isNotBlank()) append('#').append(node.id)
                if (node.cls.isNotBlank()) append('.').append(node.cls.substringBefore(' '))
                if (node.text.isNotBlank()) append("  ").append(node.text)
            }
            list.addView(TextView(ctx).apply {
                text = label
                setTextSize(11f)
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(6 + node.depth * 12), dp(3), dp(8), dp(3))
                setTextColor(
                    if (node.id.isNotBlank()) ThemeAccent.color(ctx)
                    else ctx.getColor(R.color.text_primary)
                )
                setOnClickListener {
                    if (node.hasChildren) {
                        if (!expanded.add(index)) expanded.remove(index)
                        renderTreeRows(list)
                    } else {
                        showNodeInfo(node)
                    }
                }
                setOnLongClickListener {
                    val selector = if (node.id.isNotBlank()) "#${node.id}" else node.tag
                    copyText(selector)
                    true
                }
            })
        }
    }

    private fun isVisible(index: Int): Boolean {
        var parent = nodes[index].parent
        while (parent >= 0) {
            if (!expanded.contains(parent)) return false
            parent = nodes[parent].parent
        }
        return true
    }

    private fun showNodeInfo(node: Node) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("<${node.tag}>")
            .setMessage(
                buildString {
                    if (node.id.isNotBlank()) append("id: ").append(node.id).append('\n')
                    if (node.cls.isNotBlank()) append("class: ").append(node.cls).append('\n')
                    if (node.text.isNotBlank()) append("text: ").append(node.text)
                }.ifBlank { "无属性" }
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // --- 网络 ---

    private fun renderNetwork() {
        val ctx = requireContext()
        val wv = webView
        if (wv == null) {
            body.addView(hint("没有可用的网页"))
            return
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val filters = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(filters, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(ctx).apply {
            text = "清空"
            setTextSize(12f)
            setOnClickListener {
                PageRequests.clear(wv)
                renderNetwork()
            }
        })
        body.addView(bar)

        val options = listOf(
            "ALL" to "全部", "document" to "文档", "xhr" to "XHR",
            "media" to "媒体", "image" to "图片", "other" to "其他"
        )
        options.forEach { (key, label) ->
            filters.addView(chip(label, networkFilter == key) {
                networkFilter = key
                renderNetwork()
            })
        }

        val all = PageRequests.list(wv).reversed()
        val entries = all.filter {
            networkFilter == "ALL" || it.kind == networkFilter
        }
        if (entries.isEmpty()) {
            body.addView(hint(if (all.isEmpty()) "暂无记录" else "该分类下没有请求"))
            return
        }
        body.addView(TextView(ctx).apply {
            text = "共 ${entries.size} 条"
            setTextSize(11f)
            setTextColor(ctx.getColor(R.color.text_secondary))
            setPadding(dp(10), dp(2), dp(10), dp(4))
        })
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), 0, dp(6), dp(6))
        }
        entries.forEach { entry ->
            list.addView(TextView(ctx).apply {
                text = "${entry.method}  ${entry.kind}  ${shortUrl(entry.url)}" +
                    if (entry.blocked) "  [已拦截]" else ""
                setTextSize(11f)
                typeface = android.graphics.Typeface.MONOSPACE
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
        body.addView(
            ScrollView(ctx).apply { addView(list) },
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
        body.addView(
            ScrollView(ctx).apply { addView(content) },
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
            val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
            if (json == null) {
                CardList.addRows(card, listOf(hint("读取失败：${raw ?: "无结果"}")))
                return@injectJavaScript
            }
            CardList.addRows(
                card,
                listOf(
                    infoRow("URL", json.optString("url")),
                    infoRow("标题", json.optString("title")),
                    infoRow("视口", "${json.optInt("vw")} × ${json.optInt("vh")}"),
                    infoRow("DPR", json.optString("dpr")),
                    infoRow("localStorage", json.optInt("ls").toString()),
                    infoRow("sessionStorage", json.optInt("ss").toString()),
                    infoRow("Cookie", json.optInt("cookie").toString()),
                    infoRow("UA", json.optString("ua"))
                )
            )
        }
    }

    // --- 通用 ---

    private fun chip(label: String, active: Boolean, onClick: () -> Unit): View {
        val ctx = requireContext()
        return TextView(ctx).apply {
            text = label
            setTextSize(11f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setTextColor(if (active) ThemeAccent.color(ctx) else ctx.getColor(R.color.text_secondary))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(
                    if (active) ((0x1A shl 24) or (ThemeAccent.color(ctx) and 0x00FFFFFF))
                    else ctx.getColor(R.color.card)
                )
            }
            setOnClickListener { onClick() }
        }
    }

    private fun infoRow(title: String, value: String): View {
        val ctx = requireContext()
        val name = TextView(ctx).apply {
            text = title
            setTextSize(12f)
            setTextColor(ctx.getColor(R.color.text_secondary))
        }
        val valueView = TextView(ctx).apply {
            text = value
            setTextSize(12f)
            setTextColor(ctx.getColor(R.color.text_primary))
            setTextIsSelectable(true)
        }
        return CardList.row(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(name)
            addView(valueView)
        }
    }

    private fun hint(text: String): View = TextView(requireContext()).apply {
        this.text = text
        setTextSize(12f)
        setTextColor(requireContext().getColor(R.color.text_secondary))
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    private fun copyText(text: String) {
        val clipboard = requireContext()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("selector", text))
        android.widget.Toast.makeText(requireContext(), "已复制 $text", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun shortUrl(url: String): String {
        val clean = url.substringAfter("://", url)
        return if (clean.length > 90) clean.take(90) + "…" else clean
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
