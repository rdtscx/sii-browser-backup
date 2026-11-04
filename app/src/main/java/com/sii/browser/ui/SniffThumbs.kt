// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Semaphore

/**
 * 资源嗅探列表的图片缩略图：按需下载、降采样到列表尺寸、内存 LRU 缓存。
 * 只处理图片类资源，不落盘；无痕模式下由调用方直接跳过。
 */
object SniffThumbs {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .dns(com.sii.browser.core.dns.DohDns)
        .build()
    private val permits = Semaphore(3)
    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun cached(url: String): Bitmap? = cache.get(url)

    fun load(url: String, userAgent: String?, referer: String?, onReady: (Bitmap?) -> Unit) {
        cached(url)?.let {
            onReady(it)
            return
        }
        scope.launch {
            permits.acquire()
            val bitmap = try {
                runCatching { fetch(url, userAgent, referer) }.getOrNull()
            } finally {
                permits.release()
            }
            if (bitmap != null) cache.put(url, bitmap)
            withContext(Dispatchers.Main) { onReady(bitmap) }
        }
    }

    private fun fetch(url: String, userAgent: String?, referer: String?): Bitmap? {
        val builder = Request.Builder().url(url)
        userAgent?.takeIf { it.isNotBlank() }?.let { builder.header("User-Agent", it) }
        referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        android.webkit.CookieManager.getInstance().getCookie(url)
            ?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = body.bytes()
            if (bytes.size > MAX_BYTES) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > TARGET_PX || bounds.outHeight / sample > TARGET_PX) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }

    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val TARGET_PX = 160
}
