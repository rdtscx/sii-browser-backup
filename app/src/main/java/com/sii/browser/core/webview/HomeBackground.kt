// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 主页背景图统一处理：解码 → 裁成窗口比例 → 可选模糊与遮罩。
 *
 * 窗口背景、原生主页、网页主页都用同一张处理结果，尺寸与位置才可能完全重合；
 * 裁成窗口比例也保证按宽度绘制时不会变形、不会露出底色。
 */
object HomeBackground {

    private var cacheKey: String? = null
    private var cacheBitmap: Bitmap? = null

    fun load(
        context: Context,
        raw: String?,
        overlayPercent: Int = 0,
        blurPercent: Int = 0
    ): Bitmap? {
        if (raw.isNullOrBlank()) return null
        val metrics = context.resources.displayMetrics
        val key = "$raw|$overlayPercent|$blurPercent|${metrics.widthPixels}x${metrics.heightPixels}"
        if (key == cacheKey) return cacheBitmap
        val uri = when {
            raw.startsWith("content://") || raw.startsWith("file://") -> Uri.parse(raw)
            else -> Uri.fromFile(File(raw))
        }
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val targetWidth = 1080
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        val source = runCatching {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                })
            }
        }.getOrNull() ?: return null

        // 裁成窗口比例：按宽度绘制时正好铺满窗口，不拉伸变形
        val aspect = metrics.heightPixels.toFloat() / metrics.widthPixels.coerceAtLeast(1)
        var cropWidth = source.width
        var cropHeight = (cropWidth * aspect).toInt()
        if (cropHeight > source.height) {
            cropHeight = source.height
            cropWidth = (cropHeight / aspect).toInt()
        }
        cropWidth = cropWidth.coerceAtLeast(1)
        cropHeight = cropHeight.coerceAtLeast(1)
        val left = ((source.width - cropWidth) / 2).coerceAtLeast(0)
        val top = ((source.height - cropHeight) / 2).coerceAtLeast(0)
        var output = Bitmap.createBitmap(source, left, top, cropWidth, cropHeight)
        if (blurPercent > 0) output = blur(output, blurPercent)
        if (overlayPercent > 0) {
            val mutable = output.copy(Bitmap.Config.ARGB_8888, true) ?: output
            Canvas(mutable).drawColor(
                Color.argb(overlayPercent.coerceIn(0, 100) * 255 / 100, 0, 0, 0)
            )
            output = mutable
        }
        cacheKey = key
        cacheBitmap = output
        return output
    }

    fun dataUri(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** 先缩小再放大做近似模糊，避免整图高斯带来的内存与耗时。 */
    private fun blur(source: Bitmap, percent: Int): Bitmap {
        val factor = (percent.coerceIn(1, 100) / 12f).coerceIn(1f, 8f)
        val smallWidth = (source.width / (1f + factor)).toInt().coerceAtLeast(1)
        val smallHeight = (source.height / (1f + factor)).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(source, smallWidth, smallHeight, true)
        val restored = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        if (small !== restored) small.recycle()
        if (restored !== source) source.recycle()
        return restored
    }
}
