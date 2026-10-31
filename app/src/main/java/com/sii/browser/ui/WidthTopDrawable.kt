// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 主页背景图绘制：按控件宽度等比缩放、顶部对齐，可叠加一层纯色遮罩。
 *
 * 窗口背景、主页区域、网页主页（CSS `background-size:100% auto`）必须用同一尺度，
 * 否则底栏与主页之间会出现明显的横向接缝。
 */
class WidthTopDrawable(
    private val bitmap: Bitmap,
    private val overlayColor: Int = 0
) : Drawable() {

    /** 控件顶部距离窗口顶部的距离；用于与窗口背景对齐（状态栏高度）。 */
    var topOffsetPx: Int = 0

    /** 目标高度（窗口高度）；> 0 时竖向拉伸到该高度，与窗口背景完全重合。 */
    var targetHeightPx: Int = 0

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val overlayPaint = Paint()

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        if (width <= 0f || bitmap.width <= 0 || bitmap.height <= 0) return
        val height = if (targetHeightPx > 0) targetHeightPx.toFloat() else bounds.height().toFloat()
        val top = -topOffsetPx.toFloat()
        val dst = RectF(0f, top, width, top + height)
        canvas.drawBitmap(bitmap, null, dst, paint)
        if (Color.alpha(overlayColor) > 0) {
            overlayPaint.color = overlayColor
            canvas.drawRect(0f, 0f, width, bounds.height().toFloat(), overlayPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
