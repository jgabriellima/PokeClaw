// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import com.google.gson.Gson
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Forwards unknown tool calls to an HTTP endpoint (MCP bridge / custom gateway).
 * Body: `{"name":"<tool>","arguments":{...}}` — response body is returned as success data.
 */
object RemoteMcpToolInvoker {

    private const val TAG = "RemoteMcpToolInvoker"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var invokeUrl: String = ""

    fun configure(url: String) {
        invokeUrl = url.trim()
    }

    fun isConfigured(): Boolean = invokeUrl.isNotEmpty()

    /**
     * @return null if remote MCP is not configured (caller should treat as unknown tool).
     */
    fun tryExecute(toolName: String, params: Map<String, Any?>): ToolResult? {
        val url = invokeUrl
        if (url.isEmpty()) return null
        return try {
            val bodyMap = mapOf("name" to toolName, "arguments" to params)
            val json = gson.toJson(bodyMap)
            val req = Request.Builder()
                .url(url)
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    ToolResult.success(text.ifBlank { "ok" })
                } else {
                    ToolResult.error("Remote tool HTTP ${resp.code}: ${text.take(500)}")
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "tryExecute failed: $toolName", e)
            ToolResult.error("Remote tool failed: ${e.message}")
        }
    }
}
