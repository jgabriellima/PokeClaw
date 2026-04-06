// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.agents.pokeclaw.utils.XLog
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Records mono 16-bit PCM at 16 kHz and returns a WAV byte array (RIFF) suitable for LiteRT audio content.
 */
class PcmWavRecorder {

    companion object {
        private const val TAG = "PcmWavRecorder"
        const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val pcmBuffer = ByteArrayOutputStream()
    @Volatile private var capturing = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Called on the main thread ~every 50ms while capturing; RMS 0..1 */
    var onAmplitude: ((Float) -> Unit)? = null

    private var lastLevelPostMs = 0L

    fun start(): Boolean {
        if (capturing) return true
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding)
        if (minBuf <= 0) {
            XLog.e(TAG, "Invalid min buffer size: $minBuf")
            return false
        }
        val bufferSize = max(minBuf, SAMPLE_RATE / 2)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                channel,
                encoding,
                bufferSize * 2,
            )
        } catch (e: Exception) {
            XLog.e(TAG, "AudioRecord ctor failed", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            XLog.e(TAG, "AudioRecord not initialized")
            record.release()
            return false
        }
        pcmBuffer.reset()
        lastLevelPostMs = 0L
        capturing = true
        audioRecord = record
        record.startRecording()
        captureThread = Thread({
            val buf = ByteArray(bufferSize)
            while (capturing) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) {
                    pcmBuffer.write(buf, 0, n)
                    val cb = onAmplitude
                    if (cb != null) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastLevelPostMs >= 50L) {
                            lastLevelPostMs = now
                            val rms = rmsPcm16LeMono(buf, n)
                            mainHandler.post { cb(rms) }
                        }
                    }
                }
            }
        }, "pokeclaw-wav-capture").also { it.start() }
        return true
    }

    /**
     * Stops capture and returns WAV bytes, or null if nothing was recorded.
     */
    fun stop(): ByteArray? {
        if (!capturing && audioRecord == null) return null
        capturing = false
        try {
            captureThread?.join(2000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureThread = null
        val rec = audioRecord
        audioRecord = null
        try {
            rec?.stop()
        } catch (_: Exception) { }
        try {
            rec?.release()
        } catch (_: Exception) { }

        val pcm = pcmBuffer.toByteArray()
        if (pcm.isEmpty()) {
            XLog.w(TAG, "No PCM captured")
            return null
        }
        return wrapPcm16leMonoToWav(pcm, SAMPLE_RATE)
    }

    private fun wrapPcm16leMonoToWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val bitsPerSample = 16
        val channels = 1
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val dataSize = pcmData.size
        val headerSize = 44
        val totalSize = headerSize + dataSize - 8

        val out = ByteArray(headerSize + dataSize)
        var o = 0
        fun w(bytes: ByteArray) {
            bytes.copyInto(out, o)
            o += bytes.size
        }
        w("RIFF".toByteArray())
        w(intToLe(totalSize))
        w("WAVE".toByteArray())
        w("fmt ".toByteArray())
        w(intToLe(16))
        w(shortToLe(1))
        w(shortToLe(channels.toShort()))
        w(intToLe(sampleRate))
        w(intToLe(byteRate))
        w(shortToLe(blockAlign.toShort()))
        w(shortToLe(bitsPerSample.toShort()))
        w("data".toByteArray())
        w(intToLe(dataSize))
        pcmData.copyInto(out, o)
        return out
    }

    private fun intToLe(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(),
        ((v shr 24) and 0xff).toByte(),
    )

    private fun shortToLe(v: Short) = byteArrayOf(
        (v.toInt() and 0xff).toByte(),
        ((v.toInt() shr 8) and 0xff).toByte(),
    )
}
