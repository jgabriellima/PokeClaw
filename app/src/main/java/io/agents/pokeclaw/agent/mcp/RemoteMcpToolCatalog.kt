// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema
import dev.langchain4j.model.chat.request.json.JsonNumberSchema
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import dev.langchain4j.model.chat.request.json.JsonSchemaElement
import dev.langchain4j.model.chat.request.json.JsonStringSchema
import io.agents.pokeclaw.utils.XLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches extra [ToolSpecification]s from an HTTP URL for MCP / bridge integration.
 *
 * Supported JSON shapes:
 * - `[{ "name", "description", "parameters": { OpenAI-style JSON schema } }, ...]`
 * - `{ "tools": [ { "type":"function", "function": { "name", "description", "parameters" } }, ... ] }`
 */
object RemoteMcpToolCatalog {

    private const val TAG = "RemoteMcpToolCatalog"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun fetch(catalogUrl: String): List<ToolSpecification> {
        val url = catalogUrl.trim()
        if (url.isEmpty()) return emptyList()
        return try {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    XLog.w(TAG, "Catalog HTTP ${resp.code}")
                    return emptyList()
                }
                val body = resp.body?.string().orEmpty()
                parseToolsJson(body)
            }
        } catch (e: Exception) {
            XLog.e(TAG, "fetch failed: $url", e)
            emptyList()
        }
    }

    internal fun parseToolsJson(body: String): List<ToolSpecification> {
        if (body.isBlank()) return emptyList()
        return try {
            val root = JsonParser.parseString(body)
            val array: JsonArray = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject && root.asJsonObject.has("tools") -> root.asJsonObject.getAsJsonArray("tools")
                else -> return emptyList()
            }
            val out = ArrayList<ToolSpecification>()
            for (el in array) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val fn = when {
                    obj.has("function") && obj.get("function").isJsonObject -> obj.getAsJsonObject("function")
                    else -> obj
                }
                val name = fn.get("name")?.asString?.trim().orEmpty()
                if (name.isEmpty()) continue
                val desc = fn.get("description")?.asString?.trim().orEmpty()
                val paramsEl = fn.get("parameters")
                val schema = if (paramsEl != null && paramsEl.isJsonObject) {
                    jsonObjectToParametersSchema(paramsEl.asJsonObject)
                } else {
                    null
                }
                val spec = if (schema != null) {
                    ToolSpecification.builder()
                        .name(name)
                        .description(desc)
                        .parameters(schema)
                        .build()
                } else {
                    ToolSpecification.builder()
                        .name(name)
                        .description(desc)
                        .build()
                }
                out.add(spec)
            }
            XLog.i(TAG, "Parsed ${out.size} remote tool(s)")
            out
        } catch (e: Exception) {
            XLog.e(TAG, "parseToolsJson failed", e)
            emptyList()
        }
    }

    private fun jsonObjectToParametersSchema(root: JsonObject): JsonObjectSchema? {
        val propsObj = root.getAsJsonObject("properties") ?: return null
        val required = mutableSetOf<String>()
        if (root.has("required") && root.get("required").isJsonArray) {
            for (r in root.getAsJsonArray("required")) {
                if (r.isJsonPrimitive) required.add(r.asString)
            }
        }
        val props = LinkedHashMap<String, JsonSchemaElement>()
        for (key in propsObj.keySet()) {
            val value = propsObj.get(key) ?: continue
            if (!value.isJsonObject) continue
            val p = value.asJsonObject
            val type = p.get("type")?.asString ?: "string"
            val desc = p.get("description")?.asString.orEmpty()
            props[key] = when (type) {
                "integer" -> JsonIntegerSchema.builder().description(desc).build()
                "number" -> JsonNumberSchema.builder().description(desc).build()
                "boolean" -> JsonBooleanSchema.builder().description(desc).build()
                else -> JsonStringSchema.builder().description(desc).build()
            }
        }
        if (props.isEmpty()) return null
        val b = JsonObjectSchema.builder().addProperties(props)
        if (required.isNotEmpty()) b.required(required.toList())
        return b.build()
    }
}
