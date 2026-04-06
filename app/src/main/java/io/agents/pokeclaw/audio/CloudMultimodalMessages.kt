// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.audio

import android.util.Base64
import dev.langchain4j.data.audio.Audio
import dev.langchain4j.data.image.Image
import dev.langchain4j.data.message.AudioContent
import dev.langchain4j.data.message.ImageContent
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

    fun userTextPlusImage(text: String, imageBytes: ByteArray, mimeType: String): UserMessage {
        val label = text.ifBlank { "(image)" }
        val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val img = Image.builder()
            .base64Data(b64)
            .mimeType(mimeType)
            .build()
        return UserMessage.from(label, ImageContent.from(img))
    }

    fun userTextImageAndWav(text: String, imageBytes: ByteArray, mimeType: String, wavBytes: ByteArray): UserMessage {
        val label = text.ifBlank { "(image + voice)" }
        val b64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        val img = Image.builder()
            .base64Data(b64)
            .mimeType(mimeType)
            .build()
        val audio = Audio.builder()
            .binaryData(wavBytes)
            .mimeType("audio/wav")
            .build()
        return UserMessage.from(
            label,
            ImageContent.from(img),
            AudioContent.from(audio),
        )
    }
}
