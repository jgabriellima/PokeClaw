// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.audio

import android.content.Context
import android.media.MediaPlayer
import io.agents.pokeclaw.utils.XLog
import java.io.File

/**
 * Plays in-memory WAV via a temp file (MediaPlayer cannot stream arbitrary byte arrays).
 */
class VoicePreviewPlayer(private val context: Context) {

    companion object {
        private const val TAG = "VoicePreviewPlayer"
    }

    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    val isPlaying: Boolean get() = player?.isPlaying == true

    fun play(wavBytes: ByteArray, onComplete: () -> Unit = {}) {
        stop()
        try {
            val f = File.createTempFile("pokeclaw_voice_preview_", ".wav", context.cacheDir)
            f.writeBytes(wavBytes)
            tempFile = f
            val mp = MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener {
                    stop()
                    onComplete()
                }
                setOnErrorListener { _, what, extra ->
                    XLog.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    stop()
                    onComplete()
                    true
                }
                prepare()
                start()
            }
            player = mp
        } catch (e: Exception) {
            XLog.e(TAG, "play failed", e)
            cleanupFile()
            onComplete()
        }
    }

    fun stop() {
        try {
            player?.stop()
        } catch (_: Exception) { }
        try {
            player?.release()
        } catch (_: Exception) { }
        player = null
        cleanupFile()
    }

    private fun cleanupFile() {
        try {
            tempFile?.delete()
        } catch (_: Exception) { }
        tempFile = null
    }
}
