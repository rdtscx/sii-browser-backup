// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 播放器手势层：只观察触摸、不消费事件，播放器控件（按钮、进度条）照常工作。
 * 左 1/3 竖直滑 = 亮度、右 1/3 竖直滑 = 音量、横向滑 = 进度；
 * 单击 = 切换控制条，双击左/右 = 快退/快进，长按 = 倍速。
 * [excluded] 里的视图（顶部/底部控制条）上的触摸不参与手势，避免点按钮变成切控制条。
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

        fun onTap()

        fun onDoubleTap(side: Side)

        fun onLongPressStart()

        fun onLongPressEnd()
    }

    var listener: Listener? = null

    /** 控制条等视图：落在它们垂直范围内的触摸不参与手势。 */
    var excluded: List<View> = emptyList()

    private val slop = 12f * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var mode = MODE_NONE
    private var side = Side.LEFT
    private var ignored = false
    private var longPressed = false
    private var pressed = false
    private var longPressEndedAt = 0L
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    /** 长按自己计时：系统手势检测器的长按回调可能在抬手后才到达，导致倍速卡住不恢复。 */
    private val longPressRunnable = Runnable {
        if (!pressed || longPressed || ignored) return@Runnable
        longPressed = true
        listener?.onLongPressStart()
    }

    private val detector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (SystemClock.uptimeMillis() - longPressEndedAt < 400L) return true
                listener?.onTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (e.x < width / 3f) listener?.onDoubleTap(Side.LEFT)
                else if (e.x > width * 2f / 3f) listener?.onDoubleTap(Side.RIGHT)
                return true
            }
        }
    ).apply { setIsLongpressEnabled(false) }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                mode = MODE_NONE
                pressed = true
                longPressed = false
                side = if (ev.x < width / 3f) Side.LEFT else Side.RIGHT
                ignored = excluded.any {
                    it.visibility == View.VISIBLE && ev.y >= it.top && ev.y <= it.bottom
                }
                removeCallbacks(longPressRunnable)
                if (!ignored) {
                    detector.onTouchEvent(ev)
                    postDelayed(longPressRunnable, longPressTimeout)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (ignored) return super.dispatchTouchEvent(ev)
                detector.onTouchEvent(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!longPressed && (abs(dx) > slop || abs(dy) > slop)) {
                    removeCallbacks(longPressRunnable)
                }
                if (mode == MODE_NONE && !longPressed) {
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
                pressed = false
                removeCallbacks(longPressRunnable)
                if (longPressed) {
                    longPressed = false
                    longPressEndedAt = SystemClock.uptimeMillis()
                    listener?.onLongPressEnd()
                }
                if (!ignored) detector.onTouchEvent(ev)
                when (mode) {
                    MODE_VERTICAL -> listener?.onVerticalEnd()
                    MODE_SEEK -> listener?.onSeekEnd()
                }
                mode = MODE_NONE
                ignored = false
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
