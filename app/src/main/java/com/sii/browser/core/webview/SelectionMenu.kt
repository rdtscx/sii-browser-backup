// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 划词菜单：自绘浮层，所有操作一次铺开。
 *
 * 系统原生的文本选择菜单在 Android 12+ 是浮动工具栏，宽度有限，
 * 多余项会被塞进「⋮」溢出菜单，需要上下滑动才能看全。
 * 这里改用自绘浮层：图标 + 文案平铺，样式与 Sii 其他菜单一致。
 */
object SelectionMenu {

    /** 一项操作：图标 + 文案 + 点击动作（动作收到选中的文本）。 */
    data class Item(
        val label: String,
        val iconRes: Int = 0,
        val action: (String) -> Unit
    )

    private const val MAX_WIDTH_DP = 264

    /**
     * 在锚点附近弹出菜单。
     *
     * @param host  宿主视图（用于取窗口坐标与上下文）
     * @param x/y   锚点坐标，相对 host 所在窗口
     * @param items 操作项
     * @param selected 当前选中的文本，点击时传给动作
     */
    fun show(host: View, x: Float, y: Float, items: List<Any>, selected: String) {
        val rows = items.filterIsInstance<Item>()
        if (rows.isEmpty()) return
        val context = host.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(com.sii.browser.R.drawable.bg_selection_menu)
            elevation = dp(14).toFloat()
            clipToOutline = true
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }

        var popup: PopupWindow? = null

        rows.forEach { entry ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(44)
                setPadding(dp(12), dp(10), dp(14), dp(10))
                setBackgroundResource(com.sii.browser.R.drawable.bg_list_item)
                isClickable = true
            }
            if (entry.iconRes != 0) {
                row.addView(ImageView(context).apply {
                    setImageResource(entry.iconRes)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        context.getColor(com.sii.browser.R.color.menu_icon)
                    )
                    val s = dp(18)
                    layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = dp(12) }
                })
            }
            row.addView(TextView(context).apply {
                text = entry.label
                setTextSize(14f)
                setTextColor(context.getColor(com.sii.browser.R.color.text_primary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            row.setOnClickListener {
                popup?.dismiss()
                entry.action(selected)
            }
            container.addView(row)
        }

        popup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setElevation(dp(14).toFloat())
            isOutsideTouchable = true
        }

        val loc = IntArray(2)
        host.getLocationInWindow(loc)
        val anchorX = (x - loc[0]).toInt()
        val anchorY = (y - loc[1]).toInt()
        val maxW = dp(MAX_WIDTH_DP)
        container.measure(
            View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuHeight = container.measuredHeight
        val menuWidth = container.measuredWidth
        val screenHeight = host.rootView.height
        val screenWidth = host.rootView.width
        // 菜单默认落在锚点上方；上方放不下就落到下方；左右也做夹取。
        var top = if (anchorY - menuHeight < 0) anchorY else anchorY - menuHeight
        if (top + menuHeight > screenHeight) top = (screenHeight - menuHeight).coerceAtLeast(0)
        var left = anchorX
        if (left + menuWidth > screenWidth) left = (screenWidth - menuWidth).coerceAtLeast(0)
        popup.showAtLocation(
            host,
            Gravity.TOP or Gravity.START,
            left,
            top
        )
    }
}