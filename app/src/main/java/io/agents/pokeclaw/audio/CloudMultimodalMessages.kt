// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.audio

import dev.langchain4j.data.audio.Audio
import dev.langchain4j.data.message.AudioContent
import dev.langchain4j.data.message.UserMessage

object CloudMultimodalMessages {

    fun userTextPlusWav(text: String, wavBytes: ByteArray): UserMessage {
        val label = text.ifBlank { "(voice message)" }
        val audio = Audio.builder()
            .binaryData(wavBytes)
            .mimeType("audio/wav")
            .build()
        return UserMessage.from(label, AudioContent.from(audio))
    }
}
