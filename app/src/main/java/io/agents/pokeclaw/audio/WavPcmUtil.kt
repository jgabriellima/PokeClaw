// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.audio

/**
 * Strips a standard 44-byte PCM WAV header produced by [PcmWavRecorder] to raw s16le mono samples
 * for WhisperKit.transcribe (expects PCM chunks, not RIFF).
 */
fun stripOurWavHeaderToPcm16le(wav: ByteArray): ByteArray {
    if (wav.size <= 44) return wav
    if (wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte() ||
        wav[2] != 'F'.code.toByte() || wav[3] != 'F'.code.toByte()
    ) {
        return wav
    }
    return wav.copyOfRange(44, wav.size)
}
