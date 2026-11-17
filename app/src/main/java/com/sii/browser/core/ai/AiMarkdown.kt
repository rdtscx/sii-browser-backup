// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.ai

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.graphics.Typeface

/**
 * 轻量 Markdown → Spannable 渲染，专为 AI 回复设计。
 *
 * 覆盖 LLM 输出中最常见的形态：**粗体**、*斜体*、`行内代码`、
 * ``` 代码块、# 标题（1-3 级）、- / * 无序列表、1. 有序列表。
 *
 * 不引库：Markdown 库（Markwon 等）约 0.5-1 MB，只为渲染回复不值；
 * 手写解析按行处理，无法嵌套的场景直接降级为普通文本——AI 回复够用。
 */
object AiMarkdown {

    /** 把 Markdown 文本转成带样式的 Spannable；解析失败时原样返回。 */
    fun render(text: String, accentColor: Int): CharSequence {
        return runCatching { renderInternal(text, accentColor) }.getOrDefault(text)
    }

    private fun renderInternal(text: String, accent: Int): CharSequence {
        val out = SpannableStringBuilder()
        val lines = text.replace("\r\n", "\n").split('\n')
        var inCodeBlock = false
        val codeLines = mutableListOf<String>()
        var listIndex = 0

        fun flushCode() {
            if (codeLines.isEmpty()) return
            if (out.isNotEmpty()) out.append('\n')
            val start = out.length
            out.append(codeLines.joinToString("\n"))
            // 代码块样式：等宽 + 淡底（用 accent 极低透明度模拟底色）
            out.setSpan(
                TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.setSpan(
                BackgroundColorSpan((accent and 0x00FFFFFF) or 0x18000000),
                start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            out.append('\n')
            codeLines.clear()
        }

        for (raw in lines) {
            val line = raw.trimEnd()

            // 代码块开关
            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    flushCode()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                    listIndex = 0
                }
                continue
            }
            if (inCodeBlock) {
                codeLines.add(raw)
                continue
            }

            // 空行
            if (line.isBlank()) {
                if (out.isNotEmpty()) out.append('\n')
                listIndex = 0
                continue
            }

            // 标题 # ~ ###
            val headingMatch = Regex("^(#{1,3})\\s+(.*)$").find(line)
            if (headingMatch != null) {
                if (out.isNotEmpty()) out.append('\n')
                val level = headingMatch.groupValues[1].length
                val content = headingMatch.groupValues[2]
                val start = out.length
                out.append(renderInline(content, accent, out.length))
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(
                    RelativeSizeSpan(if (level == 1) 1.15f else 1.05f),
                    start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                listIndex = 0
                continue
            }

            // 无序列表 - / * / •
            val bulletMatch = Regex("^[-*•]\\s+(.*)$").find(line)
            if (bulletMatch != null) {
                if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
                val start = out.length
                out.append("• ")
                out.append(renderInline(bulletMatch.groupValues[1], accent, out.length))
                out.setSpan(BulletSpan(12), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                listIndex = 0
                continue
            }

            // 有序列表 1. / 1)
            val orderedMatch = Regex("^(\\d{1,2})[.)]\\s+(.*)$").find(line)
            if (orderedMatch != null) {
                if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
                listIndex++
                val start = out.length
                out.append("${orderedMatch.groupValues[1]}. ")
                out.append(renderInline(orderedMatch.groupValues[2], accent, out.length))
                continue
            }

            // 普通段落
            if (out.isNotEmpty() && !out.endsWith('\n')) out.append('\n')
            out.append(renderInline(line, accent, out.length))
            listIndex = 0
        }
        if (inCodeBlock) flushCode()
        return out
    }

    /** 行内样式：**粗体**、*斜体*、`代码`。 [offsetBase] 用于 span 定位。 */
    private fun renderInline(
        text: String,
        accent: Int,
        offsetBase: Int
    ): CharSequence {
        val out = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            when {
                // **粗体**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > 0) {
                        val start = out.length
                        out.append(text.substring(i + 2, end))
                        out.setSpan(
                            StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        i = end + 2
                        continue
                    }
                }
                // `行内代码`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > 0) {
                        val start = out.length
                        out.append(text.substring(i + 1, end))
                        out.setSpan(
                            TypefaceSpan("monospace"), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        out.setSpan(
                            BackgroundColorSpan((accent and 0x00FFFFFF) or 0x18000000),
                            start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        i = end + 1
                        continue
                    }
                }
                // *斜体*（不与 ** 冲突：到这里说明后面不是 *）
                text[i] == '*' && (i == 0 || text[i - 1] != '*') -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1 && (end + 1 >= text.length || text[end + 1] != '*')) {
                        val start = out.length
                        out.append(text.substring(i + 1, end))
                        out.setSpan(
                            StyleSpan(Typeface.ITALIC), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        i = end + 1
                        continue
                    }
                }
            }
            out.append(text[i])
            i++
        }
        return out
    }

}