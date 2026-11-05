// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sii.browser.R
import com.sii.browser.core.webview.JsConsoleStore
import com.sii.browser.core.webview.PageRequests
import com.sii.browser.core.webview.SiiWebView
import com.sii.browser.di.AppServices
import org.json.JSONArray
import org.json.JSONObject

/**
 * 半屏开发者工具浮层：网页保持可见可操作，面板贴底、可拖动调高。
 * 数据来自 onConsoleMessage、[JsConsoleStore]、[PageRequests] 与页面拾取脚本。
 */
class DevToolsPanel(
    private val context: Context,
    private val host: FrameLayout,
    private val webViewProvider: () -> SiiWebView?,
    private val onHidden: () -> Unit
) {

    private val density = context.resources.displayMetrics.density
    private val tabs = arrayOf("控制台", "元素", "网络", "信息")
    private var currentTab = 0
    private var heightPercent = 55

    private lateinit var panel: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var content: LinearLayout
    private var consoleFilter = "ALL"
    private var networkFilter = "ALL"
    private var networkSelected = -1

    private data class Node(
        val depth: Int,
        val tag: String,
        val id: String,
        val cls: String,
        val text: String,
        val parent: Int,
        val hasChildren: Boolean,
        val selector: String
    )

    private var nodes: List<Node> = emptyList()
    private val expanded = mutableSetOf<Int>()
    private var treeLoading = false
    private var picked: JSONObject? = null
    private var pickerOn = false
    private var bridgeAttached = false
    private var bridgedWebView: SiiWebView? = null

    val isShowing: Boolean get() = host.visibility == View.VISIBLE

    fun show() {
        if (isShowing) return
        if (!built) build()
        host.visibility = View.VISIBLE
        applyHeight()
        attachBridge()
        if (!AppServices.settings.jsConsoleLog()) AppServices.settings.setJsConsoleLog(true)
        JsConsoleStore.onChange = { host.post { if (currentTab == 0) renderConsoleList() } }
        renderTabs()
        render()
    }

    fun hide() {
        if (!isShowing) return
        JsConsoleStore.onChange = null
        if (pickerOn) setPicker(false)
        detachBridge()
        host.removeAllViews()
        host.visibility = View.GONE
        built = false
        onHidden()
    }

    fun toggle() {
        if (isShowing) hide() else show()
    }

    /** 返回键优先收面板。 */
    fun onBack(): Boolean {
        if (!isShowing) return false
        hide()
        return true
    }

    private var built = false

    @SuppressLint("ClickableViewAccessibility")
    private fun build() {
        built = true
        panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(context.getColor(R.color.bg))
            elevation = 24f * density
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(
                        0, 0, view.width, view.height + dp(28), dp(22) * density
                    )
                }
            }
        }

        // 拖动条
        val handle = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2) * density
                setColor(0x33000000)
            }
        }
        val handleWrap = FrameLayout(context).apply {
            setPadding(dp(8), dp(10), dp(8), dp(6))
            addView(
                handle,
                FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER_HORIZONTAL)
            )
        }
        panel.addView(handleWrap)

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(10), dp(4))
        }
        header.addView(
            TextView(context).apply {
                text = "开发者工具"
                setTextSize(14f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(context.getColor(R.color.text_primary))
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(TextView(context).apply {
            text = "全屏"
            setTextSize(12f)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setTextColor(context.getColor(R.color.text_secondary))
            setOnClickListener {
                heightPercent = if (heightPercent >= 90) 55 else 95
                applyHeight()
                text = if (heightPercent >= 90) "半屏" else "全屏"
            }
        })
        header.addView(TextView(context).apply {
            text = "收起"
            setTextSize(12f)
            setPadding(dp(8), dp(6), dp(4), dp(6))
            setTextColor(context.getColor(R.color.text_secondary))
            setOnClickListener { hide() }
        })
        panel.addView(header)

        tabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        tabs.forEachIndexed { index, label ->
            tabsRow.addView(TextView(context).apply {
                text = label
                setTextSize(13f)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    currentTab = index
                    renderTabs()
                    render()
                }
            })
        }
        panel.addView(tabsRow)

        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // 拖动调高
        handleWrap.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0f
            private var startH = 0
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                return when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        startH = panel.layoutParams.height
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val delta = (startY - event.rawY).toInt()
                        val target = (startH + delta).coerceIn(
                            (host.height * 0.25f).toInt(),
                            (host.height * 0.95f).toInt()
                        )
                        panel.layoutParams = panel.layoutParams.apply { height = target }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        heightPercent = ((panel.layoutParams.height * 100f / host.height)
                            .toInt()).coerceIn(25, 95)
                        applyHeight()
                        true
                    }
                    else -> false
                }
            }
        })

        host.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(320), Gravity.BOTTOM
            )
        )
    }

    private fun applyHeight() {
        if (host.height <= 0) {
            host.post { applyHeight() }
            return
        }
        val h = (host.height * heightPercent / 100f).toInt().coerceAtLeast(dp(180))
        panel.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM
        )
    }

    private fun renderTabs() {
        val accent = ThemeAccent.color(context)
        for (i in 0 until tabsRow.childCount) {
            (tabsRow.getChildAt(i) as? TextView)?.setTextColor(
                if (i == currentTab) accent else context.getColor(R.color.text_secondary)
            )
        }
    }

    private fun render() {
        consoleOutput = null
        consoleScroll = null
        consoleFilterRow = null
        content.removeAllViews()
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
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(2), dp(10), dp(2))
            }
            consoleFilterRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            bar.addView(
                consoleFilterRow,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            bar.addView(Button(context).apply {
                text = "清空"
                setTextSize(11f)
                setOnClickListener { JsConsoleStore.clear(); renderConsoleList() }
            })
            content.addView(bar)

            val output = TextView(context).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(9.5f)
                setTextColor(context.getColor(R.color.text_primary))
                setPadding(dp(8), dp(4), dp(8), dp(6))
                setTextIsSelectable(true)
            }
            consoleOutput = output
            consoleScroll = ScrollView(context).apply { addView(output) }
            content.addView(
                consoleScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )

            val input = EditText(context).apply {
                hint = "输入 JavaScript 后执行"
                setSingleLine(true)
                setTextSize(12f)
            }
            val run = Button(context).apply {
                text = "执行"
                setTextSize(11f)
                setOnClickListener {
                    val code = input.text.toString().trim()
                    if (code.isEmpty()) return@setOnClickListener
                    webViewProvider()?.let { wv ->
                        JsConsoleStore.add("INPUT", "", 0, code)
                        wv.evaluateJavascript(code) { r ->
                            JsConsoleStore.add("RESULT", "", 0, r.orEmpty())
                        }
                    }
                    input.setText("")
                }
            }
            content.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(2), dp(8), dp(6))
                addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(run)
            })
        }
        renderConsoleFilters()
        renderConsoleList()
    }

    private fun renderConsoleFilters() {
        val row = consoleFilterRow ?: return
        row.removeAllViews()
        listOf("ALL" to "全部", "LOG" to "LOG", "WARNING" to "警告", "ERROR" to "错误")
            .forEach { (key, label) ->
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
            "（暂无输出，只记录打开面板之后的信息）"
        } else {
            entries.joinToString("\n") { e ->
                val t = android.text.format.DateFormat.format("HH:mm:ss", e.time)
                "$t [${e.level}] ${e.message}"
            }
        }
        consoleScroll?.post { consoleScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // --- 元素 ---

    private fun renderElements() {
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(8), dp(2))
        }
        top.addView(Button(context).apply {
            text = "刷新"
            setTextSize(11f)
            setOnClickListener {
                nodes = emptyList()
                expanded.clear()
                treeLoading = false
                loadTree()
            }
        })
        top.addView(Button(context).apply {
            text = if (pickerOn) "停止点选" else "点选"
            setTextSize(11f)
            setOnClickListener { setPicker(!pickerOn) }
        })
        content.addView(top)

        if (treeLoading) {
            content.addView(hint("读取中…"))
            return
        }
        if (nodes.isEmpty()) {
            loadTree()
            return
        }
        val treeScroll = ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                tag = "tree"
            })
        }
        content.addView(
            treeScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f)
        )
        renderTreeRows(treeScroll.getChildAt(0) as LinearLayout)

        val detail = TextView(context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(9.5f)
            setTextColor(context.getColor(R.color.text_primary))
            setPadding(dp(10), dp(4), dp(10), dp(6))
            setTextIsSelectable(true)
            text = picked?.let { formatPicked(it) } ?: "点选页面元素或点树节点查看详情"
        }
        val detailWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        detailWrap.addView(
            ScrollView(context).apply { addView(detail) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(6))
        }
        actions.addView(Button(context).apply {
            text = "属性"
            setTextSize(11f)
            isEnabled = picked != null
            setOnClickListener { editAttributes() }
        })
        actions.addView(Button(context).apply {
            text = "样式"
            setTextSize(11f)
            isEnabled = picked != null
            setOnClickListener { editStyles() }
        })
        actions.addView(Button(context).apply {
            text = "取消高亮"
            setTextSize(11f)
            setOnClickListener { clearHighlight() }
        })
        detailWrap.addView(actions)
        content.addView(
            detailWrap,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f)
        )
    }

    private fun formatPicked(json: JSONObject): String = buildString {
        append("<").append(json.optString("tag")).append(">")
        if (json.optString("id").isNotBlank()) append("#").append(json.optString("id"))
        val cls = json.optString("cls")
        if (cls.isNotBlank()) append(".").append(cls.replace(' ', '.'))
        append('\n').append("selector: ").append(json.optString("selector"))
        val rect = json.optJSONObject("rect")
        if (rect != null) {
            append('\n').append("rect: ")
                .append(rect.optInt("x")).append(", ").append(rect.optInt("y"))
                .append("  ").append(rect.optInt("w")).append(" × ").append(rect.optInt("h"))
        }
        val style = json.optJSONObject("style")
        if (style != null) {
            append('\n').append("display: ").append(style.optString("display"))
            append('\n').append("position: ").append(style.optString("position"))
            append('\n').append("color: ").append(style.optString("color"))
            append('\n').append("font-size: ").append(style.optString("fontSize"))
            append('\n').append("opacity: ").append(style.optString("opacity"))
        }
        val text = json.optString("text")
        if (text.isNotBlank()) append('\n').append("text: ").append(text)
    }

    private fun loadTree() {
        if (treeLoading) return
        treeLoading = true
        val script = "(function(){try{" +
            "var selOf=function(el){if(el.id)return '#'+CSS.escape(el.id);" +
            "var parts=[];while(el&&el.nodeType===1&&parts.length<5){" +
            "var p=el.nodeName.toLowerCase();" +
            "if(el.id){parts.unshift('#'+CSS.escape(el.id));break;}" +
            "var c=(el.className&&el.className.baseVal!==undefined?el.className.baseVal:el.className||'').toString().trim().split(/\\s+/).filter(Boolean)[0];" +
            "if(c)p+='.'+CSS.escape(c);" +
            "var par=el.parentElement;if(par){var i=1,s=el;while((s=s.previousElementSibling)){if(s.nodeName===el.nodeName)i++;}" +
            "p+=':nth-of-type('+i+')';}parts.unshift(p);el=el.parentElement;}" +
            "return parts.join(' > ');};" +
            "var out=[];var walk=function(el,d,parent){if(!el||out.length>=800||d>18)return;var idx=out.length;" +
            "var c=el.className;if(c&&c.baseVal!==undefined)c=c.baseVal;" +
            "out.push({d:d,t:el.tagName.toLowerCase(),i:el.id||'',c:(c||'').toString().slice(0,60)," +
            "x:(el.innerText||'').trim().slice(0,40),p:parent,k:el.children.length>0,s:selOf(el)});" +
            "for(var j=0;j<el.children.length;j++)walk(el.children[j],d+1,idx);};" +
            "if(document.body)walk(document.body,0,-1);return JSON.stringify(out);}catch(e){return 'ERR:'+e}})()"
        webViewProvider()?.injectJavaScript(script) { raw ->
            treeLoading = false
            val arr = runCatching { JSONArray(raw.orEmpty()) }.getOrNull()
            nodes = if (arr == null) emptyList() else buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        Node(
                            depth = o.optInt("d"),
                            tag = o.optString("t"),
                            id = o.optString("i"),
                            cls = o.optString("c"),
                            text = o.optString("x"),
                            parent = o.optInt("p", -1),
                            hasChildren = o.optBoolean("k"),
                            selector = o.optString("s")
                        )
                    )
                }
            }
            expanded.clear()
            nodes.forEachIndexed { index, node ->
                if (node.hasChildren && node.depth < 2) expanded.add(index)
            }
            if (currentTab == 1) render()
        }
    }

    private fun renderTreeRows(list: LinearLayout) {
        list.removeAllViews()
        nodes.forEachIndexed { index, node ->
            if (!visible(index)) return@forEachIndexed
            val arrow = when {
                !node.hasChildren -> "  "
                expanded.contains(index) -> "▾ "
                else -> "▸ "
            }
            val label = buildString {
                append(arrow).append(node.tag)
                if (node.id.isNotBlank()) append('#').append(node.id)
                if (node.cls.isNotBlank()) append('.').append(node.cls.substringBefore(' '))
                if (node.text.isNotBlank()) append("  ").append(node.text)
            }
            list.addView(TextView(context).apply {
                text = label
                setTextSize(10.5f)
                typeface = Typeface.MONOSPACE
                setPadding(dp(6 + node.depth * 10), dp(3), dp(8), dp(3))
                setTextColor(
                    if (node.id.isNotBlank()) ThemeAccent.color(context)
                    else context.getColor(R.color.text_primary)
                )
                setOnClickListener {
                    if (node.hasChildren) {
                        if (!expanded.add(index)) expanded.remove(index)
                        renderTreeRows(list)
                    }
                    highlight(node.selector)
                }
                setOnLongClickListener {
                    copy(node.selector)
                    true
                }
            })
        }
    }

    private fun visible(index: Int): Boolean {
        var parent = nodes[index].parent
        while (parent >= 0) {
            if (!expanded.contains(parent)) return false
            parent = nodes[parent].parent
        }
        return true
    }

    private fun highlight(selector: String) {
        val q = JSONObject.quote(selector)
        val script = "(function(){try{var e=document.querySelector($q);if(!e)return 0;" +
            "e.style.outline='2px solid #2F7CF6';e.style.outlineOffset='1px';" +
            "e.scrollIntoView({block:'center'});return 1;}catch(x){return 0}})()"
        webViewProvider()?.evaluateJavascript(script, null)
    }

    private fun clearHighlight() {
        val p = picked ?: return
        val q = JSONObject.quote(p.optString("selector"))
        webViewProvider()?.evaluateJavascript(
            "(function(){try{var e=document.querySelector($q);if(e){e.style.outline='';}return 1;}catch(x){return 0}})()",
            null
        )
    }

    private fun editAttributes() {
        val p = picked ?: return
        val idField = EditText(context).apply { hint = "id"; setText(p.optString("id")) }
        val clsField = EditText(context).apply { hint = "class"; setText(p.optString("cls")) }
        val textField = EditText(context).apply { hint = "文本内容（留空不改）" }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(idField)
            addView(clsField)
            addView(textField)
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("<${p.optString("tag")}> 属性")
            .setView(box)
            .setPositiveButton("应用") { _, _ ->
                applyAttributes(
                    p.optString("selector"),
                    idField.text.toString(),
                    clsField.text.toString(),
                    textField.text.toString()
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyAttributes(selector: String, id: String, cls: String, textValue: String) {
        val q = JSONObject.quote(selector)
        val script = "(function(){try{var e=document.querySelector($q);if(!e)return 0;" +
            "e.id=" + JSONObject.quote(id) + ";" +
            "e.className=" + JSONObject.quote(cls) + ";" +
            (if (textValue.isBlank()) "" else "e.textContent=" + JSONObject.quote(textValue) + ";") +
            "return 1;}catch(x){return 0}})()"
        applyAndRefresh(script)
    }

    private fun editStyles() {
        val p = picked ?: return
        val field = EditText(context).apply {
            hint = "每行一条，例如：\ncolor: #ff0000\ndisplay: none\npadding: 8px"
            setTextSize(12f)
            minLines = 4
            maxLines = 8
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("<${p.optString("tag")}> 样式（内联）")
            .setView(field)
            .setPositiveButton("应用") { _, _ ->
                val pairs = field.text.toString().lines().mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) return@mapNotNull null
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isEmpty() || value.isEmpty()) null else key to value
                }
                if (pairs.isEmpty()) return@setPositiveButton
                val calls = pairs.joinToString("") { (k, v) ->
                    "e.style.setProperty(" + JSONObject.quote(k) + "," + JSONObject.quote(v) + ");"
                }
                val q = JSONObject.quote(p.optString("selector"))
                applyAndRefresh(
                    "(function(){try{var e=document.querySelector($q);if(!e)return 0;$calls" +
                        "return 1;}catch(x){return 0}})()"
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyAndRefresh(script: String) {
        webViewProvider()?.evaluateJavascript(script) { result ->
            if (!isShowing) return@evaluateJavascript
            if (result?.trim() == "1") {
                android.widget.Toast.makeText(context, "已应用", android.widget.Toast.LENGTH_SHORT).show()
                loadTree()
            } else {
                android.widget.Toast.makeText(context, "未找到元素", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    // --- 网络 ---

    private fun renderNetwork() {
        val wv = webViewProvider()
        if (wv == null) {
            content.addView(hint("没有可用的网页"))
            return
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(8), dp(2))
        }
        val filters = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(filters, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(context).apply {
            text = "清空"
            setTextSize(11f)
            setOnClickListener {
                PageRequests.clear(wv)
                networkSelected = -1
                renderNetwork()
            }
        })
        content.addView(bar)
        listOf(
            "ALL" to "全部", "document" to "文档", "xhr" to "XHR",
            "media" to "媒体", "image" to "图片", "other" to "其他"
        ).forEach { (key, label) ->
            filters.addView(chip(label, networkFilter == key) {
                networkFilter = key
                networkSelected = -1
                renderNetwork()
            })
        }

        val all = PageRequests.list(wv).reversed()
        val entries = all.filter { networkFilter == "ALL" || it.kind == networkFilter }
        if (entries.isEmpty()) {
            content.addView(hint("暂无记录"))
            return
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        entries.forEachIndexed { i, entry ->
            list.addView(TextView(context).apply {
                text = "${entry.method}  ${entry.kind}  ${shortUrl(entry.url)}" +
                    if (entry.blocked) "  [已拦截]" else ""
                setTextSize(10.5f)
                typeface = Typeface.MONOSPACE
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setTextColor(
                    if (entry.blocked) ThemeAccent.color(context)
                    else context.getColor(R.color.text_primary)
                )
                setBackgroundColor(
                    if (networkSelected == i) 0x142F7CF6 else Color.TRANSPARENT
                )
                setOnClickListener {
                    networkSelected = if (networkSelected == i) -1 else i
                    renderNetwork()
                }
            })
        }
        content.addView(
            ScrollView(context).apply { addView(list) },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0,
                if (networkSelected >= 0) 3f else 1f
            )
        )
        if (networkSelected in entries.indices) {
            val entry = entries[networkSelected]
            val detail = TextView(context).apply {
                text = entry.url + "\n\n" +
                    "${entry.method}  ${entry.kind}" +
                    (if (entry.blocked) "  已拦截" else "") +
                    (if (entry.mainFrame) "  主框架" else "") +
                    "\n" + android.text.format.DateFormat.format("HH:mm:ss", entry.time)
                setTextSize(10f)
                typeface = Typeface.MONOSPACE
                setTextColor(context.getColor(R.color.text_primary))
                setPadding(dp(10), dp(4), dp(10), dp(6))
                setTextIsSelectable(true)
            }
            content.addView(
                ScrollView(context).apply { addView(detail) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f)
            )
        }
    }

    // --- 信息 ---

    private fun renderInfo() {
        val contentCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        val card = CardList.pageCard(context)
        contentCard.addView(card)
        content.addView(
            ScrollView(context).apply { addView(contentCard) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val script = "(function(){try{return JSON.stringify({" +
            "url:location.href,title:document.title,ua:navigator.userAgent," +
            "vw:window.innerWidth,vh:window.innerHeight,dpr:window.devicePixelRatio||1," +
            "ls:(function(){try{return localStorage.length}catch(e){return -1}})()," +
            "ss:(function(){try{return sessionStorage.length}catch(e){return -1}})()," +
            "cookie:(document.cookie||'').split(';').filter(function(s){return s.trim()}).length" +
            "});}catch(e){return 'ERR:'+e}})()"
        webViewProvider()?.injectJavaScript(script) { raw ->
            val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull()
            if (json == null) {
                CardList.addRows(card, listOf(hint("读取失败")))
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

    // --- 点选 ---

    private fun setPicker(on: Boolean) {
        pickerOn = on
        val wv = webViewProvider() ?: return
        if (on) {
            heightPercent = 35
            applyHeight()
            wv.evaluateJavascript(PICK_INIT, null)
            wv.evaluateJavascript("window.__siiPickOn=true", null)
        } else {
            wv.evaluateJavascript("try{window.__siiPickOff&&window.__siiPickOff()}catch(e){}", null)
            heightPercent = 55
            applyHeight()
        }
        if (currentTab == 1) render()
    }

    private fun onPicked(json: String) {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
        picked = obj
        if (pickerOn) {
            pickerOn = false
            heightPercent = 55
            applyHeight()
        }
        currentTab = 1
        renderTabs()
        render()
    }

    private fun attachBridge() {
        val wv = webViewProvider() ?: return
        if (bridgeAttached && bridgedWebView === wv) return
        detachBridge()
        wv.addJavascriptInterface(PickBridge(), "SiiDevToolsPick")
        bridgedWebView = wv
        bridgeAttached = true
    }

    private fun detachBridge() {
        if (!bridgeAttached) return
        runCatching { bridgedWebView?.removeJavascriptInterface("SiiDevToolsPick") }
        bridgedWebView = null
        bridgeAttached = false
    }

    inner class PickBridge {
        @android.webkit.JavascriptInterface
        fun picked(json: String) {
            host.post { onPicked(json) }
        }
    }

    // --- 通用 ---

    private fun chip(label: String, active: Boolean, onClick: () -> Unit): View =
        TextView(context).apply {
            text = label
            setTextSize(10.5f)
            setPadding(dp(9), dp(4), dp(9), dp(4))
            setTextColor(if (active) ThemeAccent.color(context) else context.getColor(R.color.text_secondary))
            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(
                    if (active) ((0x1A shl 24) or (ThemeAccent.color(context) and 0x00FFFFFF))
                    else context.getColor(R.color.card)
                )
            }
            setOnClickListener { onClick() }
        }

    private fun infoRow(title: String, value: String): View {
        val name = TextView(context).apply {
            text = title
            setTextSize(11f)
            setTextColor(context.getColor(R.color.text_secondary))
        }
        val valueView = TextView(context).apply {
            text = value
            setTextSize(11f)
            setTextColor(context.getColor(R.color.text_primary))
            setTextIsSelectable(true)
        }
        return CardList.row(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(name)
            addView(valueView)
        }
    }

    private fun hint(text: String): View = TextView(context).apply {
        this.text = text
        setTextSize(11f)
        setTextColor(context.getColor(R.color.text_secondary))
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("selector", text))
        android.widget.Toast.makeText(context, "已复制 $text", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun shortUrl(url: String): String {
        val clean = url.substringAfter("://", url)
        return if (clean.length > 80) clean.take(80) + "…" else clean
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private companion object {
        val PICK_INIT = "(function(){" +
            "if(window.__siiPickInit)return 'OK';window.__siiPickInit=true;window.__siiPickOn=false;" +
            "var lastEl=null,lastOutline='';" +
            "function sel(el){if(el.id)return '#'+CSS.escape(el.id);var parts=[];" +
            "while(el&&el.nodeType===1&&parts.length<5){var p=el.nodeName.toLowerCase();" +
            "if(el.id){parts.unshift('#'+CSS.escape(el.id));break;}" +
            "var c=(el.className&&el.className.baseVal!==undefined?el.className.baseVal:el.className||'').toString().trim().split(/\\s+/).filter(Boolean)[0];" +
            "if(c)p+='.'+CSS.escape(c);var par=el.parentElement;" +
            "if(par){var i=1,s=el;while((s=s.previousElementSibling)){if(s.nodeName===el.nodeName)i++;}p+=':nth-of-type('+i+')';}" +
            "parts.unshift(p);el=el.parentElement;}return parts.join(' > ');}" +
            "function info(el){var r=el.getBoundingClientRect();var cs=getComputedStyle(el);" +
            "return JSON.stringify({tag:el.tagName.toLowerCase(),id:el.id||''," +
            "cls:(el.className&&el.className.baseVal!==undefined?el.className.baseVal:el.className||'').toString()," +
            "text:(el.innerText||'').slice(0,200),selector:sel(el)," +
            "rect:{x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)}," +
            "style:{display:cs.display,position:cs.position,color:cs.color,fontSize:cs.fontSize,opacity:cs.opacity}});}" +
            "window.__siiPickClear=function(){if(lastEl){lastEl.style.outline=lastOutline;lastEl=null;}};" +
            "window.__siiPickOff=function(){window.__siiPickOn=false;try{window.__siiPickClear()}catch(e){}};" +
            "document.addEventListener('mousemove',function(e){if(!window.__siiPickOn)return;var el=e.target;" +
            "if(lastEl===el)return;try{window.__siiPickClear()}catch(x){}lastEl=el;lastOutline=el.style.outline;" +
            "el.style.outline='2px solid #FF6D00';},true);" +
            "document.addEventListener('click',function(e){if(!window.__siiPickOn)return;" +
            "e.preventDefault();e.stopPropagation();var el=e.target;window.__siiPickOn=false;" +
            "lastEl=el;lastOutline='';el.style.outline='2px solid #2F7CF6';" +
            "try{window.SiiDevToolsPick.picked(info(el))}catch(err){}return false;},true);" +
            "return 'OK';})()"
    }
}
