// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 只观察横向滑动、不消费事件，子 View（播放器控制器）的点击与拖动照常生效。
 */
class SwipeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** true = 向右滑（上一条），false = 向左滑（下一条）。 */
    var onHorizontalSwipe: ((right: Boolean) -> Unit)? = null

    private val minDistance = 80f * resources.displayMetrics.density

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) < minDistance || abs(dx) < abs(dy) * 1.5f) return false
                onHorizontalSwipe?.invoke(dx > 0)
                return true
            }
        }
    )

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
