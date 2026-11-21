// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import android.animation.ValueAnimator

/**
 * 胶囊外圈环形进度。
 *
 * 胶囊本身是「两条半圆弧 + 两条直线」的完全胶囊形（圆角 = 半高），
 * 所以沿它的轮廓取弧长就能得到一条闭合环形轨道。
 *
 * 起点固定在顶部中点，顺时针推进。0% 时画一条极淡整环作「轨道」，
 * 表示正在加载；有进度后亮色弧从顶部顺时针增长。
 *
 * 不侵入胶囊自身的 GlassStyle 背景：作为独立 Drawable 由 MiniDock 画在描边外侧。
 */
class MiniProgressRingDrawable(
    context: Context,
    private val night: Boolean
) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val accent = ThemeAccent.color(context)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        color = if (night) 0x14FFFFFF else 0x0E000000
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        color = accent
    }

    private val track = Path()
    private val arc = Path()
    private val measure = PathMeasure()
    private val oval = RectF()

    /** 0..100 */
    private var progress = 0

    /** 是否处于「加载中」：决定轨道是否可见。 */
    private var active = false

    /** 停滞流光：轨道上跑一段亮弧。 */
    private var running = false
    private var runAnimator: ValueAnimator? = null
    private var runPhase = 0f

    var hostView: android.view.View? = null

    fun setProgress(value: Int) {
        val v = value.coerceIn(0, 100)
        if (v == progress) return
        progress = v
        invalidateSelf()
    }

    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) {
            stopRunning()
            progress = 0
        }
        invalidateSelf()
    }

    fun setRunning(value: Boolean) {
        if (running == value) {
            if (!value) stopRunning()
            return
        }
        running = value
        if (value) startRunning() else stopRunning()
    }

    private fun startRunning() {
        stopRunning()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                runPhase = it.animatedValue as Float
                invalidateSelf()
            }
        }
        runAnimator = animator
        animator.start()
    }

    private fun stopRunning() {
        runAnimator?.cancel()
        runAnimator = null
        runPhase = 0f
    }

    fun release() {
        stopRunning()
        hostView = null
    }

    /** 供重建时迁移状态用。 */
    fun currentProgress(): Int = progress

    fun isActiveNow(): Boolean = active

    fun isRunningNow(): Boolean = running

    override fun invalidateSelf() {
        super.invalidateSelf()
        // 宿主是普通 View / Drawable，不走 View 的 invalidate 链，必须显式通知。
        hostView?.invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (!active && progress <= 0) return
        buildPath()
        if (active) canvas.drawPath(track, trackPaint)
        val length = measure.length
        if (length <= 0f) return
        val inset = 2.5f * density
        // 环画在胶囊轮廓内侧一点，避免和描边重叠。
        canvas.save()
        canvas.translate(bounds.left + inset, bounds.top + inset)
        val scaleW = (bounds.width() - inset * 2) / (bounds.width().toFloat())
        val scaleH = (bounds.height() - inset * 2) / (bounds.height().toFloat())
        canvas.scale(scaleW, scaleH)
        buildPath()
        if (progress > 0) {
            val target = length * progress / 100f
            arc.reset()
            measure.getSegment(0f, target, arc, true)
            canvas.drawPath(arc, arcPaint)
            // 末端收尾圆点，让很小的进度也能看清。
            if (progress in 1..99) {
                val pos = FloatArray(2)
                val tan = FloatArray(2)
                measure.getPosTan(target, pos, tan)
                canvas.drawCircle(pos[0], pos[1], 1.3f * density, arcPaint)
            }
        }
        if (running) {
            // 停滞流光：一段占周长 18% 的亮弧沿轨道推进。
            val seg = length * 0.18f
            val start = ((runPhase * length) % length)
            arc.reset()
            val end = start + seg
            if (end <= length) {
                measure.getSegment(start, end, arc, true)
            } else {
                measure.getSegment(start, length, arc, true)
                val tail = end - length
                val tmp = Path()
                measure.getSegment(0f, tail, tmp, true)
                arc.addPath(tmp)
            }
            val glowAlpha = arcPaint.alpha
            arcPaint.alpha = 200
            canvas.drawPath(arc, arcPaint)
            arcPaint.alpha = glowAlpha
        }
        canvas.restore()
    }

    /** 按当前 bounds 构造闭合胶囊路径，起点在顶部中点、顺时针。 */
    private fun buildPath() {
        track.reset()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val r = minOf(w, h) / 2f
        val left = 0f
        val top = 0f
        val right = w
        val bottom = h
        val cx = w / 2f
        track.moveTo(cx, top)
        track.lineTo(right - r, top)
        oval.set(right - r * 2, top, right, top + r * 2)
        track.arcTo(oval, -90f, 90f, false)
        track.lineTo(right, bottom - r)
        oval.set(right - r * 2, bottom - r * 2, right, bottom)
        track.arcTo(oval, 0f, 90f, false)
        track.lineTo(left + r, bottom)
        oval.set(left, bottom - r * 2, left + r * 2, bottom)
        track.arcTo(oval, 90f, 90f, false)
        track.lineTo(left, top + r)
        oval.set(left, top, left + r * 2, top + r * 2)
        track.arcTo(oval, 180f, 90f, false)
        track.lineTo(cx, top)
        measure.setPath(track, false)
    }

    override fun setAlpha(alpha: Int) {
        trackPaint.alpha = (alpha * 0.12f).toInt().coerceIn(0, 255)
        arcPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        arcPaint.colorFilter = colorFilter
        trackPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}