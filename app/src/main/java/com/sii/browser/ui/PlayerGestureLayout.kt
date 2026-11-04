// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 播放器手势层：只观察触摸、不消费事件，播放器自身的点击与进度条拖动照常生效。
 * 左 1/3 竖直滑 = 亮度、右 1/3 竖直滑 = 音量、横向滑 = 进度；底部 15%（控制条区域）不接管。
 */
class PlayerGestureLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class Side { LEFT, RIGHT }

    interface Listener {
        fun onVerticalStart(side: Side)

        /** [delta] 为相对屏幕高度 0.6 倍的位移比例，向上为正。 */
        fun onVerticalMove(side: Side, delta: Float)

        fun onVerticalEnd()

        fun onSeekStart()

        /** [fraction] 为相对屏幕宽度的位移比例，向右为正。 */
        fun onSeekMove(fraction: Float)

        fun onSeekEnd()
    }

    var listener: Listener? = null

    private val slop = 12f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var mode = MODE_NONE
    private var side = Side.LEFT

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                mode = MODE_NONE
                side = if (ev.x < width / 3f) Side.LEFT else Side.RIGHT
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (mode == MODE_NONE) {
                    if (abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                        mode = MODE_SEEK
                        listener?.onSeekStart()
                    } else if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f &&
                        downY < height * 0.85f
                    ) {
                        mode = MODE_VERTICAL
                        listener?.onVerticalStart(side)
                    }
                }
                when (mode) {
                    MODE_VERTICAL -> listener?.onVerticalMove(
                        side,
                        (downY - ev.y) / (height.coerceAtLeast(1) * 0.6f)
                    )
                    MODE_SEEK -> listener?.onSeekMove((ev.x - downX) / width.coerceAtLeast(1))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                when (mode) {
                    MODE_VERTICAL -> listener?.onVerticalEnd()
                    MODE_SEEK -> listener?.onSeekEnd()
                }
                mode = MODE_NONE
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        const val MODE_NONE = 0
        const val MODE_VERTICAL = 1
        const val MODE_SEEK = 2
    }
}
