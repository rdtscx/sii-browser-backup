// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils

/**
 * 页面加载进度条，一套绘制核心供两种工具栏使用。
 *
 * 悬浮胶囊：横向渐变填充地址行（左淡右浓），末端柔化收细；
 * 经典工具栏：3dp 细条，两端收圆，末端同样柔化收细。
 *
 * 末端处理的关键：不再往填充上**叠一层白**（那会在末端留下亮度台阶，
 * 看起来像「多叠了一小段」），而是把柔化直接写进填充自身的渐变里
 * —— 主色走到底、末端微微提亮、再渐变到透明。任何进度下填充都裁在同一
 * 个圆角形状内，100% 时右端圆角与轨道/胶囊严丝合缝，不会盖出方角。
 *
 * 0% 不是「什么都不画」：加载中但还没有进度时画一条极淡底线，
 * 让 DNS/TLS 首包阶段也有「正在加载」的反馈。
 *
 * 用自绘 Drawable 而非往 LayerDrawable 塞图层：旧做法里无效化传不到父容器、
 * 子层可见性传播时机不可靠，是「流光不出现」的根因。
 */
class DockProgressDrawable(
    context: Context,
    /** 左缘圆角（跟随胶囊 28dp）；经典细条可传 0，绘制时按半高收圆。 */
    private val radius: Float,
    night: Boolean,
    /** 是否悬浮胶囊样式：只影响边缘柔化宽度与渐变浓度。 */
    private val dockStyle: Boolean = true
) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val accent = ThemeAccent.color(context)

    /** 填充基准不透明度：悬浮沿用原来的 0x33/0x3D（观感不变），经典细条做实。 */
    private val baseAlpha = if (dockStyle) {
        if (night) 0x3D else 0x33
    } else {
        0xFF
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val glossMatrix = Matrix()

    private var progress = 0
    private var running = false
    /** 加载中：用于 0% 时显示极淡底线，避免连接阶段毫无反馈。 */
    private var active = false
    private var phase = 0f
    private var alphaScale = 255
    private var animator: ValueAnimator? = null

    /**
     * 宿主的重绘对象。
     *
     * 本 Drawable 是塞在 LayerDrawable 里当胶囊背景用的，而 LayerDrawable 不会把子层的
     * invalidateSelf 冒泡成父 View 的 invalidate —— 动画每帧都在跑，屏幕却一帧不刷新，
     * 这就是上一版「流光看不见」的真正原因。把自己的无效化手动转给宿主 View 才能动起来。
     */
    var hostView: android.view.View? = null
        set(value) {
            field = value
            invalidateSelf()
        }

    private fun invalidateHost() {
        invalidateSelf()
        hostView?.invalidate()
    }

    /**
     * 进度条所在的地址行高度：胶囊整体高度会因芯片行显示/隐藏而变，
     * 进度层被 inset 压到地址行之后，用它来重算柔化宽度与圆角上限。
     */
    var hostHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            shaderRight = -1f
            invalidateHost()
        }

    /** 渐变按 bounds 与进度重建，只有这些量变化时才刷新 shader。 */
    private var shaderLeft = -1f
    private var shaderRight = -1f
    private var shaderFade = -1f

    /** 右缘柔化宽度：悬浮跟着胶囊圆角走，只需极窄一段避免硬边；细条 2dp 收尖。 */
    private val fadeWidth: Float = 2f * density

    /** 扫光带宽：悬浮略宽更醒目，细条窄一些避免糊成一片。 */
    private val bandWidth: Float = (if (dockStyle) 56f else 40f) * density

    init {
        // 轨道底：只暗示「这里有进度条」，不能让人以为已经有进度。
        trackPaint.color = ColorUtils.setAlphaComponent(accent, 0x14)
        // 0% 加载中的极淡底线：比轨道再淡一档，不抢视线。
        hintPaint.color = ColorUtils.setAlphaComponent(accent, 0x24)
        // 停滞流光：叠在主色填充上，55% 白足够清楚又不刺眼。
        glossPaint.shader = LinearGradient(
            0f, 0f, bandWidth, 0f,
            intArrayOf(0x00FFFFFF, 0x8CFFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /** 进度 0–100。 */
    fun setProgress(value: Int) {
        val next = value.coerceIn(0, 100)
        if (next == progress) return
        progress = next
        invalidateHost()
    }

    /** 加载中标记：只影响 0% 时是否画极淡底线，不影响正常填充与收尾动画。 */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        invalidateHost()
    }

    /** 是否播放停滞流光；由调用方按「进度是否停滞」判断，不要全程常开。 */
    fun setRunning(running: Boolean) {
        if (this.running == running) return
        this.running = running
        if (running) startAnimator() else stopAnimator()
        invalidateHost()
    }

    /** 离开界面时停掉动画，避免 Drawable 被持有后仍在跑帧。 */
    fun release() {
        running = false
        stopAnimator()
    }

    private fun startAnimator() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidateHost()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
        phase = 0f
        invalidateHost()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        shaderRight = -1f
    }

    private fun alphaOf(ratio: Float): Int =
        (baseAlpha * ratio).toInt().coerceIn(1, 255)

    /**
     * 填充渐变：主色从左侧淡到右侧浓，末端 [fade] 宽度内微微提亮后渐变到透明。
     *
     * 亮点只在末端一小段且**随淡出一起减弱**，所以不会再出现「叠了一层」的亮度台阶，
     * 但保留一点向前的推进感。
     */
    private fun ensureFillShader(left: Float, right: Float, fade: Float) {
        // 动画每帧都会进来，位移不到 0.75px 就复用旧 shader，避免高频分配。
        if (left == shaderLeft && fade == shaderFade &&
            kotlin.math.abs(right - shaderRight) < 0.75f
        ) return
        shaderLeft = left
        shaderRight = right
        shaderFade = fade

        val length = (right - left).coerceAtLeast(1f)
        val startRatio = if (dockStyle) 0.85f else 0.70f
        val endRatio = if (dockStyle) 1.15f else 1.00f
        val solid = ColorUtils.setAlphaComponent(accent, alphaOf(endRatio))
        fillPaint.shader = if (fade <= 0f) {
            LinearGradient(
                left, 0f, right, 0f,
                intArrayOf(ColorUtils.setAlphaComponent(accent, alphaOf(startRatio)), solid),
                null, Shader.TileMode.CLAMP
            )
        } else {
            val fadeStop = ((length - fade) / length).coerceIn(0.06f, 0.94f)
            // 末端提亮仅 12% 白，且止于渐隐起点，肉眼只留下「还在往前」的暗示。
            val tipGlow = ColorUtils.setAlphaComponent(
                ColorUtils.blendARGB(accent, android.graphics.Color.WHITE, 0.12f),
                alphaOf(endRatio)
            )
            LinearGradient(
                left, 0f, right, 0f,
                intArrayOf(
                    ColorUtils.setAlphaComponent(accent, alphaOf(startRatio)),
                    solid,
                    tipGlow,
                    0x00000000
                ),
                floatArrayOf(0f, (fadeStop * 0.55f).coerceAtLeast(0.02f), fadeStop, 1f),
                Shader.TileMode.CLAMP
            )
        }
        fillPaint.alpha = alphaScale
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val fullWidth = bounds.width()
        val height = bounds.height()
        if (fullWidth <= 0 || height <= 0) return

        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val bottom = bounds.bottom.toFloat()
        val right = left + fullWidth * progress / 100f
        val half = height / 2f
        val corner = radius.coerceAtMost(half)

        // 轨道底：经典细条常驻；悬浮胶囊只在「加载中且 0%」时画一条极淡底线。
        // 悬浮平时不铺轨道——胶囊本身已有玻璃底，再铺会整块变色。
        if (!dockStyle) {
            trackPaint.color = ColorUtils.setAlphaComponent(
                accent, if (active && progress <= 0) 0x26 else 0x14
            )
            rect.set(left, top, left + fullWidth, bottom)
            path.reset()
            path.addRoundRect(rect, half, half, Path.Direction.CW)
            canvas.drawPath(path, trackPaint)
        } else if (progress <= 0 && active) {
            val lineHeight = (1.5f * density).coerceAtMost(height / 3f)
            rect.set(left, bottom - lineHeight, left + fullWidth, bottom)
            path.reset()
            path.addRoundRect(rect, lineHeight / 2f, lineHeight / 2f, Path.Direction.CW)
            canvas.drawPath(path, hintPaint)
        }

        if (progress > 0 && right > left) {
            val length = right - left
            // 100% 不再渐隐：右端圆角收尾与轨道严丝合缝，避免末端缺一小口。
            val fade = if (progress >= 100) 0f else fadeWidth.coerceAtMost(length * 0.5f)
            ensureFillShader(left, right, fade)

            // 填充：四角圆角，保证任何进度都不会伸出圆头；右端不再有直角。
            rect.set(left, top, right, bottom)
            path.reset()
            path.addRoundRect(
                rect,
                floatArrayOf(corner, corner, corner, corner, corner, corner, corner, corner),
                Path.Direction.CW
            )
            canvas.drawPath(path, fillPaint)

            if (running) {
                // 流光在已填充段内扫过：主色饱和，白光对比够，不需要暗带补偿。
                val band = bandWidth
                val travel = length + band
                val x = left - band + travel * phase
                glossMatrix.setTranslate(x, 0f)
                glossPaint.shader?.setLocalMatrix(glossMatrix)
                glossPaint.alpha = alphaScale

                val glowSave = canvas.save()
                canvas.clipRect(left, top, right, bottom)
                canvas.drawRect(left, top, right, bottom, glossPaint)
                canvas.restoreToCount(glowSave)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        val next = alpha.coerceIn(0, 255)
        if (next == alphaScale) return
        alphaScale = next
        fillPaint.alpha = next
        glossPaint.alpha = next
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        glossPaint.colorFilter = colorFilter
        trackPaint.colorFilter = colorFilter
        hintPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) {
            if (running) startAnimator()
        } else {
            stopAnimator()
        }
        return changed
    }
}
