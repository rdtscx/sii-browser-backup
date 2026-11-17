// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.core.ai

import com.sii.browser.core.dns.DohDns
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 助手的 OpenAI 兼容客户端（Chat Completions，非流式）。
 *
 * 只做一件事：把「消息列表」发给用户配置的接口，返回第一条回复文本。
 * 协议错误映射为中文短句；网络层走 DoH DNS，与下载/订阅一致。
 */
class AiClient {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(DohDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 一条对话消息。role 为 "user" / "assistant" / "system"。 */
    data class Message(val role: String, val content: String)

    /** 请求进行中的 Call，用于「停止」按钮取消。 */
    private var activeCall: Call? = null

    /** 最近一次错误的人类可读描述；成功时为 null。 */
    var lastError: String? = null
        private set

    /**
     * 发送对话并取回回复文本。
     *
     * @param endpoint 接口地址（到 /v1 为止，不含 /chat/completions）
     * @param apiKey   Bearer 密钥；本地免鉴权服务允许为空
     * @param model    模型名称
     * @param messages 完整消息列表（含 system 与历史）
     * @return 回复文本；失败返回 null，原因见 [lastError]
     */
    @Synchronized
    fun chat(endpoint: String, apiKey: String, model: String, messages: List<Message>): String? {
        lastError = null
        val url = normalizeEndpoint(endpoint) ?: run {
            lastError = "接口地址无效"
            return null
        }
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.5)
            put("messages", JSONArray().apply {
                messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        }
        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        val call = client.newCall(builder.build())
        activeCall = call
        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    lastError = describeHttpError(response.code, text)
                    return null
                }
                return parseReply(text) ?: run {
                    lastError = "返回内容无法解析"
                    null
                }
            }
        } catch (e: java.io.IOException) {
            if (call.isCanceled()) {
                lastError = null
                return null
            }
            lastError = if (e is java.net.SocketTimeoutException) "响应超时，请稍后重试"
            else "网络错误：${e.message?.take(80) ?: "连接失败"}"
            return null
        } finally {
            activeCall = null
        }
    }

    /** 取消进行中的请求（「停止」按钮）。 */
    @Synchronized
    fun cancel() {
        activeCall?.cancel()
    }

    /** 是否有请求在进行。 */
    val busy: Boolean get() = activeCall != null

    /**
     * 拉取服务商可用模型列表（GET /models，OpenAI 兼容标准端点）。
     *
     * @return 模型 ID 列表（按字母排序）；失败返回 null，原因见 [lastError]
     */
    @Synchronized
    fun listModels(endpoint: String, apiKey: String): List<String>? {
        lastError = null
        val url = normalizeEndpoint(endpoint)?.removeSuffix("/chat/completions") ?: run {
            lastError = "接口地址无效"
            return null
        }
        val builder = Request.Builder().url("$url/models")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")

        val call = client.newCall(builder.build())
        activeCall = call
        try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    lastError = describeHttpError(response.code, text)
                    return null
                }
                val models = runCatching {
                    val arr = JSONObject(text).getJSONArray("data")
                    (0 until arr.length()).mapNotNull { i ->
                        arr.getJSONObject(i).optString("id").takeIf { it.isNotBlank() }
                    }.sorted()
                }.getOrNull()
                if (models.isNullOrEmpty()) {
                    lastError = "服务商未返回模型列表"
                    return null
                }
                return models
            }
        } catch (e: java.io.IOException) {
            lastError = if (e is java.net.SocketTimeoutException) "响应超时，请稍后重试"
            else "网络错误：${e.message?.take(80) ?: "连接失败"}"
            return null
        } finally {
            activeCall = null
        }
    }

    // ---- 内部 ----

    /** 规范化接口地址：剥掉多余的 /chat/completions 尾巴与末尾斜杠。 */
    private fun normalizeEndpoint(raw: String): String? {
        var url = raw.trim()
        if (url.isEmpty()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        listOf("/chat/completions", "/v1/chat/completions").forEach { tail ->
            if (url.endsWith(tail)) url = url.removeSuffix(tail)
        }
        while (url.endsWith("/")) url = url.removeSuffix("/")
        return "$url/chat/completions"
    }

    /** 从 OpenAI 兼容响应中取第一条回复文本。 */
    private fun parseReply(text: String): String? = runCatching {
        val json = JSONObject(text)
        json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
            .takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** HTTP 状态码 → 中文短句；能取到服务端 message 时附加。 */
    private fun describeHttpError(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).getJSONObject("error").getString("message").take(120)
        }.getOrNull()
        val base = when (code) {
            401 -> "密钥无效或未授权"
            404 -> "接口地址或模型名不对（地址应到 /v1 为止）"
            429 -> "请求太频繁，稍后再试"
            in 500..599 -> "服务商暂时不可用（$code）"
            else -> "请求失败（$code）"
        }
        return if (detail.isNullOrBlank()) base else "$base：$detail"
    }
}