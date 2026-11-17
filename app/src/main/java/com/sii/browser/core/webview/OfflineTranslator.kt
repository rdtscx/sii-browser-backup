// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.webview

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.sii.browser.di.AppServices
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit 设备端翻译：语言包按需下载（默认中英），不自动联网。
 */
object OfflineTranslator {

    private val manager = RemoteModelManager.getInstance()
    private val clients = mutableMapOf<String, com.google.mlkit.nl.translate.Translator>()

    /** 译文缓存（语言对|原文→译文）：页面重建节点后重翻零开销，避免重复调 ML Kit。 */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 2000
    }

    /** 单个模型的下载状态缓存：isModelDownloaded 是跨进程调用，逐 chunk 查询会明显拖慢翻译。 */
    private val readyCache = mutableMapOf<String, Boolean>()

    private val defaultLanguages = listOf(TranslateLanguage.CHINESE, TranslateLanguage.ENGLISH)

    /**
     * 本机离线翻译是否可用。
     *
     * ML Kit 语言包必须经 Google Play 服务下载；设备缺 GMS 时，
     * ML Kit 内部的下载/检查链路会抛
     * `IllegalArgumentException: Model name expected to be matching [a-z]{2,3}_[a-z]{2,3}`，
     * 而且异常抛在主线程 Handler 上（zzai.onComplete 里 getResult()），
     * 外层 try/catch 与协程都拦不住，只会直接闪退。
     * 所以在调用任何 ML Kit 接口前先确认 GMS 可用，否则一律不碰。
     */
    @Volatile
    private var gmsChecked = false
    @Volatile
    private var gmsAvailable = false

    fun isOfflineAvailable(context: Context): Boolean {
        if (gmsChecked) return gmsAvailable
        val available = runCatching {
            val status = com.google.android.gms.common.GoogleApiAvailability
                .getInstance()
                .isGooglePlayServicesAvailable(context.applicationContext)
            status == com.google.android.gms.common.ConnectionResult.SUCCESS
        }.getOrDefault(false)
        gmsAvailable = available
        gmsChecked = true
        return available
    }

    /**
     * ML Kit translate 17.0.3 只支持「源/目标之一是英文且两者不同」的语言对，
     * 其余组合在模型名校验 `[a-z]{2,3}_[a-z]{2,3}` 处会抛异常。
     */
    fun legalPair(source: String?, target: String?): Boolean =
        source != null && target != null && source != target &&
            (source == TranslateLanguage.ENGLISH || target == TranslateLanguage.ENGLISH)

    @Volatile
    var downloading = false
        private set

    @Volatile
    var progressPercent = 0
        private set

    @Volatile
    var progressIndex = 0
        private set

    @Volatile
    var progressCount = 2
        private set

    @Synchronized
    fun clearCache() {
        cache.clear()
    }

    fun targetLanguage(context: Context): String? {
        val saved = AppServices.settings.translateTarget()
        TranslateLanguages.mlkit(saved)?.let { return it }
        return if (context.resources.configuration.locales[0].language == "zh") {
            TranslateLanguage.CHINESE
        } else {
            TranslateLanguage.ENGLISH
        }
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
        addOnFailureListener { error -> if (cont.isActive) cont.resumeWithException(error) }
    }

    private fun model(code: String): TranslateRemoteModel =
        TranslateRemoteModel.Builder(code).build()

    /** 单个模型是否已下载；必须在协程/IO 中调用。 */
    suspend fun isModelReady(context: Context, code: String): Boolean =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 无 GMS 时任何 ML Kit 调用都可能主线程崩溃，直接判为不可用。
            if (!isOfflineAvailable(context)) return@withContext false
            // 容错：允许调用方传 BCP-47 tag（"zh-CN"）或 ML Kit 码（"zh"），
            // 统一转成 ML Kit 模型码再查询，避免误判「未下载」。
            val modelCode = runCatching { TranslateLanguages.mlkit(code) }.getOrNull() ?: code
            if (!modelCode.matches(Regex("[a-z]{2,3}"))) return@withContext false
            synchronized(readyCache) { readyCache[modelCode] }?.let { return@withContext it }
            val ready = runCatching { manager.isModelDownloaded(model(modelCode)).awaitResult() }
                .getOrDefault(false)
            synchronized(readyCache) { readyCache[modelCode] = ready }
            ready
        }

    /** 指定语言中尚未下载的模型。 */
    suspend fun missingModels(context: Context, codes: Collection<String>): List<String> =
        codes.distinct().filter { !isModelReady(context, it) }

    /** 指定语言的模型是否全部就绪；默认检查中英。 */
    suspend fun isReady(
        context: Context,
        codes: Collection<String> = defaultLanguages
    ): Boolean = missingModels(context, codes).isEmpty()

    suspend fun downloadModels(
        context: Context,
        codes: Collection<String> = defaultLanguages,
        onProgress: (percent: Int, index: Int, count: Int, read: Long, total: Long) -> Unit =
            { _, _, _, _, _ -> }
    ): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (downloading) return@withContext false
        // 无 GMS 时 ML Kit 下载链路会在主线程抛未捕获异常，直接失败。
        if (!isOfflineAvailable(context)) {
            TranslationTrace.log(context, "ML Kit unavailable: no GMS")
            return@withContext false
        }
        val targets = codes.distinct().filter { !isModelReady(context, it) }
        if (targets.isEmpty()) return@withContext true
        downloading = true
        progressPercent = 0
        progressIndex = 0
        progressCount = targets.size
        try {
            val conditions = DownloadConditions.Builder().build()
            targets.forEachIndexed { index, code ->
                progressIndex = index + 1
                onProgress((index * 100) / targets.size, index + 1, targets.size, 0L, 0L)
                manager.download(model(code), conditions).awaitResult()
                synchronized(readyCache) { readyCache[code] = true }
            }
            progressPercent = 100
            onProgress(100, targets.size, targets.size, 0L, 0L)
            synchronized(clients) {
                clients.values.forEach { runCatching { it.close() } }
                clients.clear()
            }
            true
        } catch (e: Exception) {
            TranslationTrace.log(context, "ML Kit download failed: ${e.message}")
            targets.forEach { code ->
                synchronized(readyCache) { readyCache.remove(code) }
            }
            false
        } finally {
            downloading = false
        }
    }

    suspend fun deleteModels(
        context: Context,
        codes: Collection<String> = defaultLanguages
    ): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        var ok = true
        val targets = codes.distinct()
        for (code in targets) {
            runCatching { manager.deleteDownloadedModel(model(code)).awaitResult() }
                .onFailure { ok = false }
        }
        // 删除后全部重新查一次：readyCache 可能还留着其它语言的旧状态。
        synchronized(readyCache) { readyCache.clear() }
        synchronized(clients) {
            clients.values.forEach { runCatching { it.close() } }
            clients.clear()
        }
        progressPercent = 0
        synchronized(cache) { cache.clear() }
        ok
    }

    suspend fun translate(
        context: Context,
        text: String,
        to: String,
        from: String? = null
    ): Translator.Result {
        // 无 GMS 时不能碰 ML Kit（会在主线程抛未捕获异常导致闪退）。
        if (!isOfflineAvailable(context)) {
            TranslationTrace.log(context, "ML Kit unavailable: no GMS, skip translate")
            return Translator.Result(null, "offline_unavailable")
        }
        val target = TranslateLanguages.mlkit(to) ?: targetLanguage(context)
            ?: return Translator.Result(null, "offline_unsupported")
        val source = TranslateLanguages.mlkit(from.orEmpty())
            ?: return Translator.Result(null, "offline_source_unknown")
        if (source == target) return Translator.Result(text)
        // ML Kit 17.0.3 只支持含英文的语言对，其它组合会在模型名校验处抛异常。
        if (!legalPair(source, target)) {
            return Translator.Result(null, "offline_pair_unsupported")
        }
        val key = "$source-$target"
        val cacheKey = "$key|$text"
        synchronized(cache) { cache[cacheKey] }?.let { return Translator.Result(it) }
        if (!isReady(context, listOf(source, target))) {
            return Translator.Result(null, "offline_model_missing")
        }
        val client = runCatching {
            synchronized(clients) {
                clients.getOrPut(key) {
                    Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(source)
                            .setTargetLanguage(target)
                            .build()
                    )
                }
            }
        }.getOrElse {
            TranslationTrace.log(context, "ML Kit getClient failed: ${it.message}")
            return Translator.Result(null, it.message ?: "offline_error")
        }
        return runCatching { client.translate(text).awaitResult() }
            .fold(
                onSuccess = {
                    synchronized(cache) { cache[cacheKey] = it }
                    Translator.Result(it)
                },
                onFailure = { Translator.Result(null, it.message ?: "offline_error") }
            )
    }

    /** 逐条并发翻译（带 LRU 缓存）。不合并文本，避免分隔符被模型改坏导致错位。 */
    suspend fun translateBatch(
        context: Context,
        texts: List<String>,
        to: String,
        from: String,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): List<String?> {
        if (texts.isEmpty()) return emptyList()
        val startedAt = System.currentTimeMillis()
        val gate = Semaphore(6)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val results = coroutineScope {
            texts.map { text ->
                async {
                    gate.withPermit { translate(context, text.take(1000), to, from).text }.also {
                        onProgress?.invoke(done.incrementAndGet(), texts.size)
                    }
                }
            }.awaitAll()
        }
        TranslationTrace.log(
            context,
            "mlkit parallel count=${texts.size} ms=${System.currentTimeMillis() - startedAt}"
        )
        return results
    }

    /** ML Kit 没有 HTML 对齐接口，这里按分隔符合并成批翻译。 */
    suspend fun translateHtmlBatch(
        context: Context,
        texts: List<String>,
        to: String,
        from: String
    ): List<String?> =
        translateBatch(context, texts, to, from)
}