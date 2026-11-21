// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sii.browser.R

/**
 * 网页页的迷你底栏：一个右下方的小胶囊。
 *
 * 悬浮底栏在网页上占 56~98dp 高、直接压在正文上。这里把常态换成 40dp 的小胶囊，
 * 中心显示**当前站点图标**（抓不到回退首字母，再回退盾牌），右侧是本页拦截数，
 * 加载中沿胶囊轮廓走一圈环形进度。
 *
 * 只负责视图与自身动画；显隐时机（主页 / 编辑态 / 滚动）由 BrowserFragment 决定。
 */
class MiniDock(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onTapTranslate: () -> Unit
) {

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Int): Int = (value * density).toInt()

    /**
     * 翻译入口：与地址栏里的翻译按钮同款文案与状态。
     * 视觉 13sp，但触区扩大到 32dp（视觉 20dp 槽位 + 负 margin 补偿），
     * 避免「原/译」单字太小点不住。
     */
    private val translateMark = TextView(context).apply {
        text = context.getString(R.string.translate_mark)
        setTextSize(13f)
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        minWidth = dp(32)
        minHeight = dp(32)
        includeFontPadding = false
        visibility = View.GONE
    }

    private val icon = ImageView(context).apply {
        setImageResource(R.drawable.ic_mini_shield)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private val letter = TextView(context).apply {
        text = ""
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        visibility = View.GONE
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private val badge = TextView(context).apply {
        text = ""
        setTextSize(11.5f)
        gravity = Gravity.CENTER
        visibility = View.GONE
        setPadding(dp(3), 0, 0, 0)
    }

    /** 环形进度，画在胶囊轮廓上。 */
    private var ring: MiniProgressRingDrawable? = null

    val view: View = object : LinearLayout(context) {
        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            ring?.let { r ->
                r.setBounds(0, 0, width, height)
                r.draw(canvas)
            }
        }
    }.apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(10), 0)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.adblock_badge_desc)
        addView(translateMark, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            // 触区比视觉槽位大：左右各吃进 4dp 间隙，避免贴着站点图标误触。
            marginStart = dp(-4)
            marginEnd = dp(0)
        })
        // 翻译槽位的点击同样交给 installDrag() 统一判定，这里不单独设监听。
        translateMark.isClickable = false
        addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(letter, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(badge, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        // 注意：这里**不设** setOnClickListener——点击由 installDrag() 的 OnTouchListener
        // 在 ACTION_UP 里统一判定（拖动 / 翻译 / 展开），否则会与拖动检测重复触发。
        visibility = View.GONE
        alpha = 0f
        scaleX = 0.85f
        scaleY = 0.85f
        // 缩放锚点固定在底边右侧：胶囊贴右下角，收敛方向就是它所在的位置。
        pivotX = 0f
        pivotY = 0f
    }

    init {
        // 注意：必须在 view 完成赋值之后再安装拖动监听。
        // 之前把 installDrag() 写在上面 apply{} 里，而 installDrag 内部会访问 view，
        // 此时 view 还没完成初始化（为 null），构造 MiniDock 直接抛 NPE 导致启动崩溃。
        installDrag()
    }

    // ---- 拖动（长按移动位置）----

    /** 拖动状态：按下时的位置基准。 */
    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragging = false

    /** 用户拖动后记录的位置（相对父容器的 x/y），null 表示还没拖过、用默认贴底居右。 */
    var customX: Float? = null
        private set
    var customY: Float? = null
        private set

    /** 拖动阈值：小于该距离视为点击，避免拖完手一松就误触展开。 */
    private val dragSlopPx: Float by lazy { (8 * density) }

    /** 拖动结束回调（用于持久化位置）。 */
    var onDragFinished: ((x: Float, y: Float) -> Unit)? = null

    /** 首次拖动提示回调（只在第一次真正拖动时触发一次）。 */
    var onFirstDrag: (() -> Unit)? = null

    private fun installDrag() {
        // 注意：view 上原本有 setOnClickListener(onTap) 与 translateMark 的点击。
        // 一旦设置 OnTouchListener 并在 ACTION_DOWN 返回 true，点击事件会被吞掉，
        // 所以这里必须自己判定「点击 vs 拖动」并回调，不能依赖 performClick 以外的方式。
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragDownRawX = event.rawX
                    dragDownRawY = event.rawY
                    dragStartX = touched.x
                    dragStartY = touched.y
                    dragging = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val parent = touched.parent as? ViewGroup ?: return@setOnTouchListener false
                    val dx = event.rawX - dragDownRawX
                    val dy = event.rawY - dragDownRawY
                    if (!dragging) {
                        if (kotlin.math.abs(dx) > dragSlopPx || kotlin.math.abs(dy) > dragSlopPx) {
                            dragging = true
                            // 确认是拖动后禁止父容器（网页）抢走后续事件。
                            parent.requestDisallowInterceptTouchEvent(true)
                            onFirstDrag?.invoke()
                            onFirstDrag = null
                        } else {
                            return@setOnTouchListener true
                        }
                    }
                    // 限制在父容器范围内，避免拖出屏幕再也点不到。
                    val maxX = (parent.width - touched.width).coerceAtLeast(0).toFloat()
                    val maxY = (parent.height - touched.height).coerceAtLeast(0).toFloat()
                    touched.x = (dragStartX + dx).coerceIn(0f, maxX)
                    touched.y = (dragStartY + dy).coerceIn(0f, maxY)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    touched.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragging) {
                        customX = touched.x
                        customY = touched.y
                        onDragFinished?.invoke(touched.x, touched.y)
                        true
                    } else {
                        // 未达到拖动阈值：按点击处理。
                        // 点在「文A」翻译槽位上时走翻译，其余区域走展开。
                        val onTranslate = translateMark.visibility == View.VISIBLE &&
                            event.x >= translateMark.x &&
                            event.x <= translateMark.x + translateMark.width
                        if (onTranslate) onTapTranslate() else onTap()
                        true
                    }
                }
                else -> false
            }
        }
    }

    /**
     * 恢复用户上次拖动的位置（按父容器比例存，避免换分辨率后错位）。
     * 传 null 表示回到默认贴底居右。
     */
    fun restorePosition(ratioX: Float?, ratioY: Float?) {
        val parent = view.parent as? ViewGroup ?: return
        view.post {
            val p = view.parent as? ViewGroup ?: return@post
            if (ratioX == null || ratioY == null) {
                customX = null
                customY = null
                return@post
            }
            val maxX = (p.width - view.width).coerceAtLeast(0).toFloat()
            val maxY = (p.height - view.height).coerceAtLeast(0).toFloat()
            val x = (ratioX * maxX).coerceIn(0f, maxX)
            val y = (ratioY * maxY).coerceIn(0f, maxY)
            view.x = x
            view.y = y
            customX = x
            customY = y
        }
    }

    /** 当前位置相对父容器的比例（未拖动过返回 null）。 */
    fun positionRatio(): Pair<Float, Float>? {
        val parent = view.parent as? ViewGroup ?: return null
        val cx = customX ?: return null
        val cy = customY ?: return null
        val maxX = (parent.width - view.width).coerceAtLeast(1).toFloat()
        val maxY = (parent.height - view.height).coerceAtLeast(1).toFloat()
        return (cx / maxX).coerceIn(0f, 1f) to (cy / maxY).coerceIn(0f, 1f)
    }

    private var ringNight = true

    /** 当前中心图标是否是站点原图（favicon）；原图不能再被 tint 成纯色。 */
    private var hasFavicon = false

    /**
     * 迷你胶囊贴底居右，与完整底栏同一套边距基准。
     * [index] 用于指定在父容器里的层级位置（0 = 最底层），默认追加到末尾。
     */
    fun attach(parent: FrameLayout, index: Int = -1) {
        if (view.parent != null) return
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            dp(40),
            Gravity.BOTTOM or Gravity.END
        ).apply {
            marginEnd = dp(14)
            bottomMargin = dp(12)
        }
        if (index < 0 || index > parent.childCount) parent.addView(view, params)
        else parent.addView(view, index, params)
        view.elevation = dp(10).toFloat()
    }

    /** 重新按当前日夜与主题上材质；尺寸不变。 */
    fun applyStyle(night: Boolean, strength: Int = 40) {
        val radius = dp(20).toFloat()
        (view as? LinearLayout)?.background = GlassStyle.surface(
            context, night, radius, strength,
            minAlpha = GlassStyle.DOCK_GLASS_MIN_ALPHA,
            highlight = 0.06f,
            shade = 0.03f,
            strokeOverride = if (night) 0x2AFFFFFF else 0x1C000000
        )
        val fg = if (night) Color.WHITE else context.getColor(R.color.text_primary)
        // favicon 模式下图标是原图，不能被 tint 成纯色；只有回退到内置盾牌时才上色。
        if (letter.visibility == View.VISIBLE) {
            icon.imageTintList = android.content.res.ColorStateList.valueOf(fg)
        } else if (!hasFavicon) {
            icon.imageTintList = android.content.res.ColorStateList.valueOf(fg)
        }
        letter.setTextColor(fg)
        badge.setTextColor(fg)
        translateMark.setTextColor(fg)
        // 环形进度跟日夜走；重建时把当前进度带过去，避免换主题丢进度。
        val old = ring
        val fresh = MiniProgressRingDrawable(context, night).apply {
            hostView = view
        }
        if (old != null) {
            fresh.setProgress(old.currentProgress())
            fresh.setActive(old.isActiveNow())
            fresh.setRunning(old.isRunningNow())
            old.release()
        }
        ring = fresh
        ringNight = night
    }

    /** 拦截提醒：ads/privacy 是本页计数；show=false 时只显示站点图标。 */
    fun updateBadge(ads: Int, privacy: Int, show: Boolean) {
        val total = ads + privacy
        if (!show || total <= 0) {
            badge.visibility = View.GONE
            view.contentDescription = context.getString(R.string.adblock_badge_desc)
            return
        }
        badge.visibility = View.VISIBLE
        badge.text = if (total > 99) "99+" else total.toString()
        view.contentDescription = context.getString(
            R.string.adblock_badge_detail, ads, privacy
        )
    }

    /**
     * 中心图标：优先站点原图（favicon），失败回退域名首字母，再失败回退盾牌。
     *
     * [initial] 为空表示没有可用域名（如 about:blank），直接显示盾牌。
     */
    fun updateSiteIcon(bitmap: Bitmap?, initial: String?) {
        if (bitmap != null) {
            hasFavicon = true
            icon.visibility = View.VISIBLE
            letter.visibility = View.GONE
            icon.setImageBitmap(bitmap)
            // 原图自带配色，不能再套 tint，否则会被染成纯色。
            icon.imageTintList = null
            return
        }
        hasFavicon = false
        if (!initial.isNullOrBlank()) {
            icon.visibility = View.GONE
            letter.visibility = View.VISIBLE
            letter.text = initial.take(1).uppercase()
            return
        }
        icon.visibility = View.VISIBLE
        letter.visibility = View.GONE
        icon.setImageResource(R.drawable.ic_mini_shield)
        val night = ringNight
        val fg = if (night) Color.WHITE else context.getColor(R.color.text_primary)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(fg)
    }

    /**
     * 翻译入口显示状态，取值与地址栏翻译按钮一致：
     * 0 隐藏 / 1 显示「文A」/ 2 翻译中（保持「文A」，点击取消）/ 3 已翻译。
     */
    fun setTranslateState(state: Int, label: String? = null) {
        if (state == 0) {
            translateMark.visibility = View.GONE
            return
        }
        translateMark.visibility = View.VISIBLE
        translateMark.text = label ?: context.getString(R.string.translate_mark)
    }

    /** 环形进度：0..100。 */
    fun setProgress(value: Int) {
        ring?.setProgress(value)
    }

    /** 是否处于加载中（决定是否画环形轨道）。 */
    fun setProgressActive(value: Boolean) {
        ring?.setActive(value)
    }

    /** 停滞流光。 */
    fun setProgressRunning(value: Boolean) {
        ring?.setRunning(value)
    }

    fun showMini(animated: Boolean) {
        if (view.visibility == View.VISIBLE && view.alpha > 0.9f) return
        view.animate().cancel()
        view.visibility = View.VISIBLE
        if (!animated) {
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.alpha = 0f
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(160L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    fun hideMini(animated: Boolean) {
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
        if (!animated) {
            view.visibility = View.GONE
            view.alpha = 0f
            return
        }
        view.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f)
            .setDuration(140L)
            .withEndAction { view.visibility = View.GONE }
            .start()
    }

    fun isVisible(): Boolean = view.visibility == View.VISIBLE

    /** 迷你胶囊占用高度（含上下留白），供浮动条抬升使用。 */
    fun liftPx(): Int = dp(40) + dp(12)

    /** 胶囊中心相对其父容器的 X 偏移（用于衔接动画算位移）。 */
    fun centerX(): Float = view.x + view.width / 2f

    /** 胶囊中心相对其父容器的 Y 偏移。 */
    fun centerY(): Float = view.y + view.height / 2f

    /** 动画期间允许临时把 alpha/scale 设成中间值。 */
    fun setTransition(alpha: Float, scale: Float) {
        view.alpha = alpha
        view.scaleX = scale
        view.scaleY = scale
    }
}