// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.audio

import android.app.Application
import com.argmaxinc.whisperkit.ExperimentalWhisperKit
import com.argmaxinc.whisperkit.WhisperKit
import io.agents.pokeclaw.utils.XLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

/**
 * Lazy on-device ASR using [WhisperKit](https://github.com/argmaxinc/WhisperKitAndroid).
 * QNN/NPU deps are optional and not on public Maven; CPU backend is used.
 */
@OptIn(ExperimentalWhisperKit::class)
object WhisperKitTranscriber {

    private const val TAG = "WhisperKitTranscriber"

    private val lock = Any()
    @Volatile private var kit: WhisperKit? = null
    @Volatile private var loadFailed = false

    private val aggregatedText = StringBuilder()
    private val lastPartial = AtomicReference("")

    /**
     * Transcribes 16 kHz mono s16le PCM (no WAV header). Returns null on failure.
     */
    fun transcribePcm16MonoBlocking(app: Application, pcmS16le: ByteArray): String? {
        if (pcmS16le.isEmpty()) return null
        if (loadFailed) return null
        synchronized(lock) {
            if (loadFailed) return null
            if (kit == null) {
                try {
                    buildKit(app)
                } catch (e: Exception) {
                    XLog.e(TAG, "WhisperKit setup failed", e)
                    loadFailed = true
                    return null
                }
            }
        }
        val k = kit ?: return null
        return try {
            synchronized(aggregatedText) {
                aggregatedText.clear()
            }
            lastPartial.set("")
            k.transcribe(pcmS16le)
            val fromCallback = synchronized(aggregatedText) { aggregatedText.toString().trim() }
            val partial = lastPartial.get().trim()
            (fromCallback.ifEmpty { partial }).ifEmpty { null }
        } catch (e: Exception) {
            XLog.e(TAG, "transcribe failed", e)
            null
        }
    }

    private fun buildKit(app: Application) {
        val wk = WhisperKit.Builder()
            .setApplicationContext(app)
            .setModel(WhisperKit.Builder.OPENAI_TINY_EN)
            .setEncoderBackend(WhisperKit.Builder.CPU_ONLY)
            .setDecoderBackend(WhisperKit.Builder.CPU_ONLY)
            .setCallback { what, result ->
                if (what == WhisperKit.TextOutputCallback.MSG_TEXT_OUT) {
                    val t = result.text ?: ""
                    lastPartial.set(t)
                    synchronized(aggregatedText) {
                        if (t.isNotBlank()) aggregatedText.append(t)
                    }
                }
            }
            .build()
        runBlocking(Dispatchers.IO) {
            wk.loadModel().collect { /* progress ignored; first download can be large */ }
            @Suppress("MagicNumber")
            wk.init(PcmWavRecorder.SAMPLE_RATE, 1, 0L)
        }
        kit = wk
        XLog.i(TAG, "WhisperKit ready (tiny.en, CPU)")
    }
}
