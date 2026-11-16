// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.sii.browser.R
import com.sii.browser.core.ai.AiClient
import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 助手浮窗：抓当前页正文，预设「摘要 / 要点 / 译中文」+ 自由追问。
 *
 * 底部 sheet 与菜单面板同款交互（拖拽关闭）；非流式，一次请求一段回复。
 * 未配置 Key 时显示引导卡；回答长按可复制/分享。
 */
class AiChatPanel(
    private val context: Context,
    private val lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    /** 抓当前页正文；由 BrowserFragment 提供。 */
    private val pageTextProvider: (onResult: (String?) -> Unit) -> Unit,
    /** 当前页标题，用于上下文与展示。 */
    private val pageTitle: () -> String,
    /** 打开 AI 设置页（未配置时引导跳转）。 */
    private val openSettings: () -> Unit
) {

    private val client = AiClient()

    /** 本轮会话消息（含 system），浮窗关闭即清。 */
    private val history = mutableListOf<AiClient.Message>()

    /** 已抓取的页正文；null = 未抓取或失败。 */
    private var pageText: String? = null
    private var pageTextState = STATE_IDLE

    private var panelView: View? = null
    private var chatScroll: ScrollView? = null
    private var chatFlow: LinearLayout? = null
    private var input: EditText? = null
    private var sendButton: TextView? = null
    private var pageTitleView: TextView? = null
    private var statusView: TextView? = null

    private val settings get() = AppServices.settings

    companion object {
        private const val STATE_IDLE = 0
        private const val STATE_LOADING = 1
        private const val STATE_READY = 2
        private const val STATE_FAILED = 3

        /** 正文发送上限：超出则保头去尾。 */
        private const val PAGE_TEXT_LIMIT = 12000
        private const val PAGE_HEAD_KEEP = 8000
        private const val PAGE_TAIL_KEEP = 4000

        /** 会话内保留的最大轮数（user+assistant 各算一条）。 */
        private const val HISTORY_KEEP = 12

        /** 发送按钮禁用态的透明度。 */
        private const val ALPHA_DISABLED = 0.45f
    }

    /**
     * 挂载浮窗。
     *
     * 关闭浮窗只拆视图，不清会话：history 与已渲染的气泡在下次 show 时恢复，
     * 直到浏览器切换页面（BrowserFragment 在导航时调 [resetSession]）才清空。
     */
    fun show(host: FrameLayout) {
        hide()
        val view = buildPanel()
        host.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                marginStart = dp(10)
                marginEnd = dp(10)
                bottomMargin = dp(10)
            }
        )
        panelView = view
        refreshHeader()
        // 恢复上次会话的气泡（关闭浮窗不丢记录）。
        if (history.isNotEmpty()) {
            history.forEachIndexed { index, msg ->
                if (msg.role == "user") {
                    appendBubble(msg.content, isUser = true)
                } else if (msg.role == "assistant") {
                    appendBubble(msg.content, isUser = false)
                }
            }
        }
        ensurePageText()
    }

    /** 只收起浮窗；会话数据保留，下次 [show] 恢复聊天记录。 */
    fun dismiss() {
        hide()
    }

    private fun hide() {
        client.cancel()
        panelView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        panelView = null
    }

    /**
     * 清空会话：切换页面 / 关闭标签时由 BrowserFragment 调用。
     * 页面变了，旧页的正文与对话上下文不再有意义。
     */
    fun resetSession() {
        hide()
        history.clear()
        chatFlow?.removeAllViews()
        pageText = null
        pageTextState = STATE_IDLE
    }

    val isShowing: Boolean get() = panelView != null

    /**
     * 自定义预设：内置三个 + 用户自定义（设置里存 JSON，[ {label, prompt} ]）。
     * label 为空时回退 prompt 前 6 字。
     */
    private fun presets(): List<Pair<String, String>> {
        val builtIn = listOf(
            context.getString(R.string.ai_chip_summarize) to
                context.getString(R.string.ai_prompt_summarize),
            context.getString(R.string.ai_chip_keypoints) to
                context.getString(R.string.ai_prompt_keypoints),
            context.getString(R.string.ai_chip_translate) to
                context.getString(R.string.ai_prompt_translate)
        )
        val custom = runCatching {
            val arr = org.json.JSONArray(settings.aiCustomPresets().orEmpty())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val label = o.optString("label").trim()
                val prompt = o.optString("prompt").trim()
                if (prompt.isBlank()) null
                else label.ifBlank { prompt.take(6) } to prompt
            }
        }.getOrDefault(emptyList())
        return builtIn + custom
    }

    // ---- UI 构建 ----

    private fun buildPanel(): View {
        val context = context
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_modern_sheet)
            elevation = dp(12).toFloat()
            clipToOutline = true
            setPadding(dp(6), dp(4), dp(6), dp(2))
        }

        // 标题行：AI 助手 + 模型名 + 关闭
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(6))
        }
        head.addView(TextView(context).apply {
            text = context.getString(R.string.ai_panel_title)
            setTextSize(15f)
            setTextColor(context.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL
            )
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        pageTitleView = TextView(context).apply {
            setTextSize(11.5f)
            setTextColor(context.getColor(R.color.text_secondary))
            alpha = 0.7f
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { openSettings() }
        }
        head.addView(pageTitleView)
        head.addView(TextView(context).apply {
            text = "✕"
            setTextSize(16f)
            setTextColor(context.getColor(R.color.text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
            setOnClickListener { dismiss() }
        })
        root.addView(head)

        // 预设动作行
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        // 芯片行横向滚动：自定义预设多了也不挤。
        val chipScroll = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        presets().forEach { (label, prompt) ->
            chips.addView(TextView(context).apply {
                text = label
                setTextSize(12.5f)
                setTextColor(ThemeAccent.color(context))
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setBackgroundResource(R.drawable.bg_card)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener { send(prompt) }
            })
        }
        root.addView(chipScroll)

        // 正文状态行
        statusView = TextView(context).apply {
            setTextSize(11.5f)
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        root.addView(statusView)

        // 对话流
        chatFlow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        chatScroll = ScrollView(context).apply { addView(chatFlow) }
        root.addView(
            chatScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        // 输入行
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        input = EditText(context).apply {
            hint = context.getString(R.string.ai_input_hint)
            background = null
            setSingleLine(false)
            maxLines = 3
            setTextSize(14f)
            setTextColor(context.getColor(R.color.text_primary))
            setHintTextColor(context.getColor(R.color.text_secondary))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val has = !s.isNullOrBlank()
                    if (!client.busy) {
                        sendButton?.isEnabled = has
                        sendButton?.alpha = if (has) 1f else ALPHA_DISABLED
                    }
                    alpha = 1f
                }
            })
        }
        inputRow.addView(
            input,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        sendButton = TextView(context).apply {
            text = context.getString(R.string.ai_send)
            setTextSize(13.5f)
            setTextColor(ThemeAccent.color(context))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(4), dp(8))
            isEnabled = false
            alpha = ALPHA_DISABLED
            setOnClickListener { sendFreeForm() }
        }
        inputRow.addView(sendButton)
        root.addView(inputRow)

        // 未配置：引导卡覆盖输入区；已配置但历史为空显示欢迎语。
        if (!configured()) {
            chatFlow?.addView(buildOnboarding())
            input?.isEnabled = false
            input?.hint = context.getString(R.string.ai_not_configured_hint)
        } else if (history.isEmpty()) {
            chatFlow?.addView(buildWelcome())
        }
        return root
    }

    /** 已配置但还没问过：一句话欢迎，填充空对话区。 */
    private fun buildWelcome(): View = TextView(context).apply {
        text = context.getString(R.string.ai_welcome)
        setTextSize(13f)
        setTextColor(context.getColor(R.color.text_secondary))
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.6f)
        setPadding(dp(20), dp(48), dp(20), dp(20))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** 未配置时的引导卡：说明 + 去设置按钮。 */
    private fun buildOnboarding(): View {
        val context = context
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(36), dp(20), dp(20))
            addView(TextView(context).apply {
                text = context.getString(R.string.ai_onboard_title)
                setTextSize(15f)
                setTextColor(context.getColor(R.color.text_primary))
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.ai_onboard_body)
                setTextSize(12.5f)
                setTextColor(context.getColor(R.color.text_secondary))
                setLineSpacing(0f, 1.5f)
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(18))
            })
            addView(TextView(context).apply {
                text = context.getString(R.string.ai_onboard_go_settings)
                setTextSize(13.5f)
                setTextColor(ThemeAccent.color(context))
                gravity = Gravity.CENTER
                setPadding(dp(22), dp(10), dp(22), dp(10))
                setBackgroundResource(R.drawable.bg_card)
                setOnClickListener {
                    dismiss()
                    openSettings()
                }
            })
        }
    }

    // ---- 状态与数据 ----

    private fun configured(): Boolean = settings.aiEnabled() && !settings.aiApiKey().isNullOrBlank()

    private fun refreshHeader() {
        pageTitleView?.text = (settings.aiModel() ?: context.getString(R.string.ai_no_model))
        updateStatusLine()
    }

    private fun updateStatusLine() {
        val view = statusView ?: return
        when (pageTextState) {
            STATE_IDLE -> view.text = context.getString(R.string.ai_status_loading)
            STATE_LOADING -> view.text = context.getString(R.string.ai_status_loading)
            STATE_READY -> view.text = context.getString(
                R.string.ai_status_ready, pageText?.length ?: 0, pageTitle()
            )
            else -> view.text = context.getString(R.string.ai_status_failed)
        }
    }

    private fun ensurePageText() {
        if (pageTextState == STATE_READY || pageTextState == STATE_LOADING) return
        pageTextState = STATE_LOADING
        updateStatusLine()
        pageTextProvider { text ->
            if (!isShowing) return@pageTextProvider
            if (text.isNullOrBlank()) {
                pageTextState = STATE_FAILED
                pageText = null
            } else {
                pageText = text
                pageTextState = STATE_READY
            }
            panelView?.post { updateStatusLine() }
        }
    }

    // ---- 发送 ----

    private fun sendFreeForm() {
        val text = input?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || !configured()) return
        input?.setText("")
        send(text)
    }

    private fun send(question: String) {
        if (client.busy) return
        val endpoint = settings.aiEndpoint().orEmpty()
        val key = settings.aiApiKey().orEmpty()
        val model = settings.aiModel().orEmpty()
        if (endpoint.isBlank() || model.isBlank()) {
            Toast.makeText(context, R.string.ai_error_not_configured, Toast.LENGTH_SHORT).show()
            openSettings()
            return
        }

        appendBubble(question, isUser = true)

        // 组装消息：system + 页正文（首轮）+ 最近历史 + 本次提问
        val messages = mutableListOf(AiClient.Message("system", systemPrompt()))
        if (!pageText.isNullOrBlank()) {
            messages.add(AiClient.Message("system", "【当前网页正文】\n${clampPageText(pageText!!)}"))
        }
        history.forEach { messages.add(it) }
        messages.add(AiClient.Message("user", question))

        setBusy(true)
        lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val reply = withContext(Dispatchers.IO) {
                client.chat(endpoint, key, model, messages)
            }
            setBusy(false)
            if (!isShowing) return@launch
            if (reply != null) {
                history.add(AiClient.Message("user", question))
                history.add(AiClient.Message("assistant", reply))
                while (history.size > HISTORY_KEEP) history.removeAt(0)
                appendBubble(reply, isUser = false)
            } else {
                client.lastError?.let { appendBubble(it, isUser = false, isError = true) }
            }
        }
    }

    private fun systemPrompt(): String = context.getString(R.string.ai_system_prompt)

    /** 正文超长时保头去尾。 */
    private fun clampPageText(text: String): String =
        if (text.length <= PAGE_TEXT_LIMIT) text
        else text.take(PAGE_HEAD_KEEP) + "\n……（中间内容已省略）……\n" + text.takeLast(PAGE_TAIL_KEEP)

    private fun setBusy(busy: Boolean) {
        val button = sendButton ?: return
        button.text = context.getString(if (busy) R.string.ai_stop else R.string.ai_send)
        if (busy) {
            // 停止态：全不透明 + 主色淡底，一眼看出可点。
            button.isEnabled = true
            button.alpha = 1f
            button.setTypeface(button.typeface, android.graphics.Typeface.BOLD)
            button.setBackgroundResource(R.drawable.bg_accent_soft)
            button.setPadding(dp(14), dp(8), dp(14), dp(8))
            button.setOnClickListener { client.cancel() }
        } else {
            val has = !input?.text.isNullOrBlank()
            button.isEnabled = has
            button.alpha = if (has) 1f else ALPHA_DISABLED
            button.setTypeface(button.typeface, android.graphics.Typeface.NORMAL)
            button.setBackgroundResource(0)
            button.setPadding(dp(12), dp(8), dp(4), dp(8))
            button.setOnClickListener { sendFreeForm() }
        }
    }

    // ---- 气泡 ----

    private fun appendBubble(text: String, isUser: Boolean, isError: Boolean = false) {
        val flow = chatFlow ?: return
        val context = context
        val bubble = TextView(context).apply {
            // AI 回复走 Markdown 渲染；用户/错误气泡纯文本。
            this.text = if (isUser || isError) text
                else com.sii.browser.core.ai.AiMarkdown.render(
                    text, ThemeAccent.color(context)
                )
            setTextSize(13.5f)
            setTextColor(
                when {
                    isError -> context.getColor(R.color.danger)
                    isUser -> context.getColor(R.color.text_secondary)
                    else -> context.getColor(R.color.text_primary)
                }
            )
            setLineSpacing(0f, 1.4f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextIsSelectable(!isUser && !isError)
            setBackgroundResource(
                when {
                    isError -> R.drawable.bg_card
                    isUser -> R.drawable.bg_hint_bar
                    else -> R.drawable.bg_card
                }
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
            // 非用户气泡：长按复制 / 分享（复制的是 Markdown 原文）
            if (!isUser && !isError) {
                setOnLongClickListener {
                    showReplyActions(text)
                    true
                }
            }
        }
        flow.addView(bubble)
        chatScroll?.post { chatScroll?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /** AI 回复长按：复制 / 分享。 */
    private fun showReplyActions(text: String) {
        val items = arrayOf(
            context.getString(R.string.ai_reply_copy),
            context.getString(R.string.ai_reply_share)
        )
        android.app.AlertDialog.Builder(context)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ai_reply", text))
                        Toast.makeText(context, R.string.ai_reply_copied, Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .putExtra(android.content.Intent.EXTRA_TEXT, text)
                            .setType("text/plain")
                        context.startActivity(android.content.Intent.createChooser(share, null))
                    }
                }
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}