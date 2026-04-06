// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.audio

import io.agents.pokeclaw.utils.KVUtils

/**
 * Routing for native multimodal audio vs transcribe-then-text.
 *
 * LiteRT-LM exposes [com.google.ai.edge.litertlm.Content.AudioBytes]; Gemma 4 IT checkpoints on device
 * are the intended consumers (see Google AI Edge / Gemma 4 multimodal docs).
 */
object ModelAudioSupport {

    fun localGemma4SupportsNativeAudio(): Boolean {
        val path = KVUtils.getLocalModelPath()
        if (path.isBlank()) return false
        return path.contains("gemma-4", ignoreCase = true) ||
            path.contains("gemma_4", ignoreCase = true) ||
            path.contains("gemma4", ignoreCase = true)
    }

    /**
     * OpenAI-compatible chat completions with input_audio / multimodal user content (e.g. gpt-4o family).
     * Heuristic only — unsupported endpoints fall back to Whisper transcript.
     */
    fun remoteOpenAiStyleModelMayAcceptAudio(modelName: String): Boolean {
        val m = modelName.lowercase()
        if (m.contains("gpt-4o")) return true
        if (m.contains("gpt-4.1")) return true
        if (m.contains("o3") || m.contains("o4")) return true
        if (m.contains("audio-preview") || m.contains("realtime")) return true
        if (m.contains("gemini") && (m.contains("2.0") || m.contains("2.5") || m.contains("flash") || m.contains("pro"))) {
            return true
        }
        return false
    }
}
