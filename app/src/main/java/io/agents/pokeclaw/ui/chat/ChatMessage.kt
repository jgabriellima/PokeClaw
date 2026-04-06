// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

data class ChatMessage(
    val role: Role,
    val content: String,
    /** Model reasoning / thinking trace (e.g. Kimi reasoning_content, LiteRT enable_thinking). */
    val reasoning: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val toolSteps: List<ToolStep>? = null,
    /** In-session attachment for UI (not serialized to markdown history). JPEG bytes. */
    val attachmentImageJpeg: ByteArray? = null,
    /** In-session voice clip for replay in the bubble. */
    val attachmentVoiceWav: ByteArray? = null,
) {
    enum class Role { USER, ASSISTANT, SYSTEM, TOOL_GROUP }
}

data class ToolStep(
    val toolName: String,
    val summary: String,
    val success: Boolean = false
)
