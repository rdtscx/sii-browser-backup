// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

/**
 * 内置主页模板对外承诺的 DOM 钩子。
 *
 * 供用户 CSS 的兼容性自检：只列出模板真实存在的 class / id，规则里用到其它名字会提示"找不到元素"。
 * 新增模板元素时同步这里，否则自检会误报。
 */
object HomeTemplateHooks {

    private val classes = setOf(
        "page", "gradient-title", "logo-img", "clock", "t", "sec", "d",
        "search_bar", "icon", "search_wrap", "opSug_wpr", "opSug_item", "opSug_sub",
        "nav-grid", "nav-card", "nav-icon", "nav-title", "nav-pager", "sii-dots", "sii-dot",
        "switch_engine", "engine_item", "engine_tip", "sii-slot"
    )

    private val ids = setOf(
        "logo", "vtime", "vsec", "vdate",
        "searchBar", "q", "engineName", "search_bar_trigger", "sugList",
        "switch_window", "engineList",
        "siiSlotTop", "siiSlotBottom", "siiSlotHead"
    )

    /** 返回 CSS 里用到、但模板中没有对应元素的 class / id（带 . 或 # 前缀，最多 20 条）。 */
    fun unmatched(css: String): List<String> {
        if (css.isBlank()) return emptyList()
        // url(...) 里的文件名、数字、伪类参数都不算选择器
        val cleaned = css.replace(Regex("""url\([^)]*\)"""), " ")
            .replace(Regex("""(?i)@[a-z-]+[^{]*\{"""), "{")
        val result = LinkedHashSet<String>()
        Regex("""\.([A-Za-z][A-Za-z0-9_-]*)""").findAll(cleaned).forEach { match ->
            val name = match.groupValues[1]
            if (name !in classes) result.add(".$name")
        }
        Regex("""#([A-Za-z][A-Za-z0-9_-]*)""").findAll(cleaned).forEach { match ->
            val name = match.groupValues[1]
            // #fff / #a5b4fc 这类十六进制颜色不是 ID
            if (name.matches(Regex("[0-9a-fA-F]{3,8}"))) return@forEach
            if (name !in ids) result.add("#$name")
        }
        return result.take(20)
    }
}
