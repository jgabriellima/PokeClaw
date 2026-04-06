// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.audio

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * RMS of 16-bit LE mono PCM in [buffer] interpreting only the first [length] bytes.
 */
fun rmsPcm16LeMono(buffer: ByteArray, length: Int): Float {
    if (length < 2) return 0f
    val n = length / 2
    var sum = 0.0
    var i = 0
    var samples = 0
    while (i + 1 < length && samples < n) {
        val lo = buffer[i].toInt() and 0xff
        val hi = buffer[i + 1].toInt()
        val s = (hi shl 8) or lo
        val v = s.toShort().toInt()
        sum += (v * v).toDouble()
        samples++
        i += 2
    }
    if (samples == 0) return 0f
    return (sqrt(sum / samples) / 32768.0).toFloat().coerceIn(0f, 1f)
}

/**
 * Builds normalized bar heights (0..1) from a WAV byte array (44-byte PCM header assumed).
 */
fun waveformBarsFromWav(wav: ByteArray, barCount: Int = 48): List<Float> {
    if (wav.size < 48) return List(barCount) { 0.05f }
    val pcmStart = findPcmDataOffset(wav) ?: 44
    if (pcmStart >= wav.size) return List(barCount) { 0.05f }
    val pcm = wav.copyOfRange(pcmStart, wav.size)
    if (pcm.isEmpty()) return List(barCount) { 0.05f }
    val samples = pcm.size / 2
    val buckets = barCount.coerceAtLeast(8)
    val out = FloatArray(buckets)
    val samplesPerBucket = (samples / buckets).coerceAtLeast(1)
    for (b in 0 until buckets) {
        val start = b * samplesPerBucket * 2
        var max = 0
        var i = start
        val end = min(start + samplesPerBucket * 2, pcm.size - 1)
        while (i < end) {
            val lo = pcm[i].toInt() and 0xff
            val hi = pcm[i + 1].toInt()
            val s = (hi shl 8) or lo
            val v = abs(s.toShort().toInt())
            if (v > max) max = v
            i += 2
        }
        out[b] = (max / 32768f).coerceIn(0f, 1f)
    }
    val peak = out.maxOrNull()?.takeIf { it > 1e-4f } ?: 1f
    return out.map { (it / peak).coerceIn(0.05f, 1f) }
}

fun wavDurationMsMono16k(wav: ByteArray): Long {
    val pcmStart = findPcmDataOffset(wav) ?: 44
    val bytes = (wav.size - pcmStart).coerceAtLeast(0)
    val ms = bytes * 1000L / (PcmWavRecorder.SAMPLE_RATE * 2)
    return ms.coerceIn(0L, 600_000L)
}

private fun findPcmDataOffset(wav: ByteArray): Int? {
    if (wav.size < 12) return null
    if (wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte()) return null
    var i = 12
    while (i + 8 <= wav.size) {
        val id = String(wav, i, 4, Charsets.US_ASCII)
        val size = (wav[i + 4].toInt() and 0xff) or
            ((wav[i + 5].toInt() and 0xff) shl 8) or
            ((wav[i + 6].toInt() and 0xff) shl 16) or
            ((wav[i + 7].toInt() and 0xff) shl 24)
        i += 8
        if (id == "data") return i
        i += size
        if (i and 1 == 1) i++
    }
    return null
}
