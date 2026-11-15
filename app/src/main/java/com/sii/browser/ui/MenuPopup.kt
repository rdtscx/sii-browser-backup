// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.sii.browser.R
import com.sii.browser.di.AppServices

/**
 * 统一的页头 ⋮ 弹出菜单（对齐 index.html 的圆角卡片浮层）。
 * 支持普通项、分组标题和可展开的二级选项。
 *
 * 定位不再交给 showAsDropDown：它会先尝试滚动锚点的父容器（书签/历史这类列表里
 * 长按最底下一条时父容器已滚到底，滚不动），再考虑翻到上方；展开二级分组后
 * update() 又只按原位置重排。两者叠加就是「长按最底下的书签，菜单被挡住、显示不全」。
 *
 * 现在改为自算坐标：内容外包 ScrollView 限高，measure 出真实宽高后在窗口可见区内
 * 夹取 —— 优先锚点下方，放不下翻到上方，两边都不够就选空间大的一侧并让内容滚动。
 */
object MenuPopup {

    private var activePopup: PopupWindow? = null

    fun dismiss() {
        activePopup?.dismiss()
        activePopup = null
    }

    /** 菜单项。 */
    class Item(val label: CharSequence, val checked: Boolean = false, val action: () -> Unit)

    /** 分组标题。 */
    class Header(val label: CharSequence)

    /** 可展开的二级选项组。 */
    class Group(val label: CharSequence, val options: List<Item>)

    fun show(anchor: View, entries: List<Any>, labelTransform: (String) -> String = { it }) {
        val context = anchor.context
        val modern = AppServices.settings.uiStyle() == 1
        val dark = modern && AppServices.settings.isNightMode()
        val primary = if (dark) Color.WHITE else context.getColor(R.color.text_primary)
        val secondary = if (dark) 0xFF9AA0A8.toInt()
        else context.getColor(R.color.text_secondary)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(if (dark) R.drawable.bg_dialog_dark else R.drawable.bg_card)
            clipToOutline = true
            setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
        }
        var popup: PopupWindow? = null
        val subContainers = mutableListOf<LinearLayout>()

        fun itemRow(item: Item, indented: Boolean): TextView = TextView(context).apply {
            text = if (item.checked && indented) "✓  ${labelTransform(item.label.toString())}"
            else labelTransform(item.label.toString())
            setTextSize(if (indented) 13f else 14f)
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(context, if (indented) 40 else 44)
            setTextColor(
                if (item.checked) ThemeAccent.color(context) else primary
            )
            setPadding(
                dp(context, if (indented) 28 else 12),
                dp(context, if (indented) 9 else 12),
                dp(context, 12),
                dp(context, if (indented) 9 else 12)
            )
            setBackgroundResource(R.drawable.bg_list_item)
            isClickable = true
            setOnClickListener {
                popup?.dismiss()
                item.action()
            }
        }

        entries.forEach { entry ->
            when (entry) {
                is Header -> container.addView(TextView(context).apply {
                    text = labelTransform(entry.label.toString())
                    setTextSize(12f)
                    setTextColor(secondary)
                    setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 4))
                })
                is Item -> container.addView(itemRow(entry, indented = false))
                is Group -> {
                    val sub = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = View.GONE
                    }
                    val row = TextView(context).apply {
                        text = "${labelTransform(entry.label.toString())}  ›"
                        setTextSize(14f)
                        setTextColor(primary)
                        setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
                        setBackgroundResource(R.drawable.bg_list_item)
                        isClickable = true
                        setOnClickListener {
                            val expand = sub.visibility != View.VISIBLE
                            subContainers.forEach { it.visibility = View.GONE }
                            sub.visibility = if (expand) View.VISIBLE else View.GONE
                            text = if (expand) "${labelTransform(entry.label.toString())}  ⌄"
                            else "${labelTransform(entry.label.toString())}  ›"
                            // 展开会改变高度：必须重算坐标并夹取，不能只 update()。
                            sub.post { popup?.let { relayout(anchor, it) } }
                        }
                    }
                    entry.options.forEach { sub.addView(itemRow(it, indented = true)) }
                    subContainers.add(sub)
                    container.addView(row)
                    container.addView(sub)
                }
            }
        }

        // 内容外包 ScrollView：屏幕放不下时靠滚动，而不是被裁掉。
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                container,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val window = PopupWindow(scroll, 0, 0, true).apply {
            isOutsideTouchable = true
            elevation = dp(context, 8).toFloat()
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        activePopup?.dismiss()
        activePopup = window
        window.setOnDismissListener {
            if (activePopup === window) activePopup = null
        }
        popup = window
        if (!anchor.isAttachedToWindow) {
            window.dismiss()
            return
        }
        relayout(anchor, window)
    }

    /**
     * 量一次内容，按锚点与窗口可见区决定位置与最大高度，然后显示或原地更新。
     *
     * 展开/收起二级分组后也走这里：坐标、尺寸、限高一起重算，保证弹窗始终完整可见。
     */
    private fun relayout(anchor: View, window: PopupWindow) {
        val context = anchor.context
        val scroll = window.contentView as? ScrollView ?: return
        val container = scroll.getChildAt(0) as? View ?: return
        if (!anchor.isAttachedToWindow) {
            window.dismiss()
            return
        }

        val margin = dp(context, 8)
        val gap = dp(context, 4)

        // 可见区：避开状态栏/导航栏；键盘弹出时也会收窄可用高度。
        val frame = android.graphics.Rect()
        anchor.getWindowVisibleDisplayFrame(frame)
        val anchorLoc = IntArray(2)
        anchor.getLocationInWindow(anchorLoc)
        val anchorBottom = anchorLoc[1] + anchor.height
        val anchorRight = anchorLoc[0] + anchor.width

        // 1. 先按不受限宽度量出内容自然尺寸。
        container.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val minWidth = dp(context, 176)
        val maxWidth = (frame.width() - margin * 2).coerceAtLeast(minWidth)
        val width = (container.measuredWidth + scroll.paddingLeft + scroll.paddingRight)
            .coerceAtLeast(minWidth)
            .coerceAtMost(maxWidth)
        val naturalHeight = container.measuredHeight + scroll.paddingTop + scroll.paddingBottom

        // 2. 垂直空间：优先下方，放不下再翻到上方；都不够就选大的那边并限高。
        val spaceBelow = (frame.bottom - margin - (anchorBottom + gap)).coerceAtLeast(0)
        val spaceAbove = (anchorLoc[1] - gap - (frame.top + margin)).coerceAtLeast(0)
        val placeBelow = naturalHeight <= spaceBelow ||
            (spaceBelow >= spaceAbove && spaceBelow > 0)
        val available = (if (placeBelow) spaceBelow else spaceAbove).coerceAtLeast(0)
        val height = if (naturalHeight <= available) naturalHeight
        else available.coerceAtLeast(dp(context, 80))
        val above = !placeBelow ||
            (naturalHeight > spaceBelow && spaceAbove > spaceBelow)

        // 3. 横向：右对齐锚点，再夹进屏幕安全边距。
        val x = (anchorRight - width)
            .coerceIn(frame.left + margin, (frame.right - margin - width).coerceAtLeast(frame.left + margin))
        // 4. 纵向：above 时贴锚点上方，否则贴锚点下方；最后再夹一次防越界。
        val y = if (above) (anchorLoc[1] - gap - height).coerceAtLeast(frame.top + margin)
        else (anchorBottom + gap).coerceAtMost((frame.bottom - margin - height).coerceAtLeast(frame.top + margin))

        // 高度直接交给 PopupWindow 固定：ScrollView 会按窗口高度填充，
        // 内容超出即滚动，不需要（也没有）ScrollView.maxHeight 这种 API。
        window.width = width
        window.height = height
        if (window.isShowing) {
            window.update(x, y, width, height)
        } else {
            window.showAtLocation(
                anchor,
                android.view.Gravity.TOP or android.view.Gravity.START,
                x,
                y
            )
        }
    }

    private fun dp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}