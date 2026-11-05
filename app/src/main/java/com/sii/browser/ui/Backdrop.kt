// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 主页背景的「真模糊」采样源：窗口背景图会被换成一张缩小并降采样的位图，
 * 卡片按自己在窗口里的位置取样，看起来就是卡片下垫着模糊的背景。
 *
 * 背景图由 ShellActivity 画在窗口根容器上，所以源矩形就是整个窗口。
 */
object HomeBackdrop {

    private var bitmap: Bitmap? = null
    private var source = RectF()

    fun set(src: Bitmap, windowWidth: Int, windowHeight: Int, overlayPercent: Int = 0) {
        if (windowWidth <= 0 || windowHeight <= 0) return
        val w = (windowWidth / 10).coerceIn(24, 256)
        val h = (src.height * w / src.width).coerceAtLeast(1)
        var current = Bitmap.createScaledBitmap(src, w, h, true)
        // 再折半两次，放大回去就是明显的模糊；位图很小，不占内存。
        repeat(2) {
            val nw = (current.width / 2).coerceAtLeast(1)
            val nh = (current.height / 2).coerceAtLeast(1)
            current = Bitmap.createScaledBitmap(current, nw, nh, true)
        }
        val overlay = overlayPercent.coerceIn(0, 100)
        if (overlay > 0) {
            val darkened = current.copy(Bitmap.Config.ARGB_8888, true)
            darkened?.let {
                val canvas = Canvas(it)
                val paint = Paint()
                paint.color = android.graphics.Color.argb(overlay * 255 / 100, 0, 0, 0)
                canvas.drawRect(0f, 0f, it.width.toFloat(), it.height.toFloat(), paint)
                current = it
            }
        }
        bitmap = current
        source.set(0f, 0f, windowWidth.toFloat(), windowHeight.toFloat())
    }

    fun clear() {
        bitmap = null
        source.setEmpty()
    }

    fun hasBitmap(): Boolean = bitmap != null && !source.isEmpty

    /** 为某个视图生成背景模糊层；没有背景图时返回 null，调用方退回磨砂填充。 */
    fun drawable(
        context: Context,
        radiusPx: Float,
        dark: Boolean,
        position: () -> RectF,
        fillOverride: Int? = null,
        strokeOverride: Int? = null
    ): Drawable? {
        val bm = bitmap ?: return null
        if (source.isEmpty) return null
        return BackdropDrawable(
            context, bm, RectF(source), radiusPx, dark, position, fillOverride, strokeOverride
        )
    }

    fun windowRect(view: View): RectF {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return RectF(
            location[0].toFloat(),
            location[1].toFloat(),
            (location[0] + view.width).toFloat(),
            (location[1] + view.height).toFloat()
        )
    }
}

/** 取样窗口背景并裁剪到圆角，再叠磨砂白底、顶部高光、底部内阴影与描边。 */
private class BackdropDrawable(
    private val context: Context,
    private val bitmap: Bitmap,
    private val source: RectF,
    private val radiusPx: Float,
    private val dark: Boolean,
    private val position: () -> RectF,
    fillOverride: Int? = null,
    strokeOverride: Int? = null
) : Drawable() {

    private val fillColor = fillOverride
        ?: (if (dark) 0x851B1E23 else 0x61FFFFFF).toInt()
    private val strokeColor = strokeOverride
        ?: (if (dark) 0x3DFFFFFF else 0x24000000).toInt()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val overlayPaint = Paint()
    private val path = Path()
    private val srcRect = Rect()
    private val srcRectF = RectF()
    private val dstRect = RectF()
    private val dstInt = Rect()
    private var highlight: Paint? = null
    private var shade: Paint? = null

    private val density = context.resources.displayMetrics.density

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        highlight = Paint().apply {
            shader = LinearGradient(
                0f, bounds.top.toFloat(), 0f, bounds.top + bounds.height() * 0.35f,
                0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
            )
        }
        shade = Paint().apply {
            shader = LinearGradient(
                0f, bounds.bottom - bounds.height() * 0.3f, 0f, bounds.bottom.toFloat(),
                0x00000000, if (dark) 0x1A000000 else 0x0D000000, Shader.TileMode.CLAMP
            )
        }
    }

    override fun draw(canvas: Canvas) {
        val pos = position()
        if (pos.width() <= 0f || pos.height() <= 0f) return
        val b = bounds
        if (b.isEmpty) return
        val scaleX = bitmap.width / source.width()
        val scaleY = bitmap.height / source.height()
        srcRectF.set(
            ((pos.left - source.left) * scaleX).coerceIn(0f, bitmap.width.toFloat()),
            ((pos.top - source.top) * scaleY).coerceIn(0f, bitmap.height.toFloat()),
            ((pos.right - source.left) * scaleX).coerceIn(0f, bitmap.width.toFloat()),
            ((pos.bottom - source.top) * scaleY).coerceIn(0f, bitmap.height.toFloat())
        )
        srcRect.set(
            srcRectF.left.toInt(), srcRectF.top.toInt(),
            srcRectF.right.toInt(), srcRectF.bottom.toInt()
        )
        dstRect.set(b)
        dstInt.set(b)
        path.reset()
        path.addRoundRect(dstRect, radiusPx, radiusPx, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(path)
        if (srcRect.width() > 0 && srcRect.height() > 0) {
            canvas.drawBitmap(bitmap, srcRect, dstInt, bitmapPaint)
        }
        overlayPaint.color = fillColor
        canvas.drawRect(dstRect, overlayPaint)
        highlight?.let { canvas.drawRect(dstRect, it) }
        shade?.let { canvas.drawRect(dstRect, it) }
        canvas.restoreToCount(save)
        overlayPaint.color = strokeColor
        overlayPaint.style = Paint.Style.STROKE
        overlayPaint.strokeWidth = density
        canvas.drawRoundRect(dstRect, radiusPx, radiusPx, overlayPaint)
        overlayPaint.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
