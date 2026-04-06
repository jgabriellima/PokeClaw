// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.ai.edge.litertlm.Content
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.LlmProvider
import io.agents.pokeclaw.audio.CloudMultimodalMessages
import io.agents.pokeclaw.audio.downscaleToJpeg
import io.agents.pokeclaw.audio.ModelAudioSupport
import io.agents.pokeclaw.audio.PcmWavRecorder
import io.agents.pokeclaw.audio.VoicePreviewPlayer
import io.agents.pokeclaw.audio.waveformBarsFromWav
import io.agents.pokeclaw.audio.wavDurationMsMono16k
import io.agents.pokeclaw.audio.WhisperKitTranscriber
import io.agents.pokeclaw.audio.stripOurWavHeaderToPcm16le
import io.agents.pokeclaw.agent.llm.EngineHolder
import io.agents.pokeclaw.agent.llm.LiteRtSampling
import io.agents.pokeclaw.agent.llm.LlmClientFactory
import io.agents.pokeclaw.agent.llm.LocalModelManager
import io.agents.pokeclaw.agent.llm.LlmResponse
import io.agents.pokeclaw.agent.llm.StreamingListener
import com.google.ai.edge.litertlm.MessageCallback
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.channel.Channel as ChannelEnum
import io.agents.pokeclaw.floating.FloatingCircleManager
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.ui.settings.LlmConfigActivity
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.TaskShortcuts
import io.agents.pokeclaw.utils.XLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.util.concurrent.Executors

/**
 * PokeClaw Chat Activity — Compose UI with LiteRT-LM backend.
 *
 * Backend logic (LLM engine, chat history, compaction) stays here.
 * UI is delegated to ChatScreen composable.
 */
class ComposeChatActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ComposeChatActivity"
        private const val VOICE_MAX_MS = 45_000L
    }

    private val chatDraftText = mutableStateOf("")
    private val chatVoiceRecording = mutableStateOf(false)
    private val chatVoiceDraft = mutableStateOf<ByteArray?>(null)
    private val chatVoiceDraftWaveform = mutableStateOf<List<Float>>(emptyList())
    private val chatVoiceRecordingLevels = mutableStateOf<List<Float>>(emptyList())
    private val chatVoicePreviewPlaying = mutableStateOf(false)
    private val chatImageDraft = mutableStateOf<ByteArray?>(null)
    private var voiceWavRecorder: PcmWavRecorder? = null
    private val voiceStopHandler = Handler(Looper.getMainLooper())
    private val voiceStopRunnable = Runnable { stopVoiceRecordingToDraft() }
    private val voicePreviewPlayer by lazy { VoicePreviewPlayer(this) }

    private lateinit var recordAudioLauncher: ActivityResultLauncher<String>
    private lateinit var pickImageLauncher: ActivityResultLauncher<PickVisualMediaRequest>

    private var conversationId = "chat_${System.currentTimeMillis()}"
    private val executor = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private var conversation: Conversation? = null
    private var isModelReady = false

    // Compose state — observed by ChatScreen
    private val _messages = mutableStateListOf<ChatMessage>()
    private val _modelStatus = mutableStateOf("No model loaded")
    private val _needsPermission = mutableStateOf(false)
    private val _isProcessing = mutableStateOf(false)
    private val _conversations = mutableStateListOf<ChatHistoryManager.ConversationSummary>()
    private val _isDownloading = mutableStateOf(false)
    private val _downloadProgress = mutableStateOf(0)
    /** True while a phone-control task is in progress (for Stop button). */
    private val _taskRunning = mutableStateOf(false)

    // Permission polling
    private val permHandler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            _needsPermission.value = !ClawAccessibilityService.isRunning()
            permHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordAudioLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) startVoiceRecordingInternal()
            else Toast.makeText(this, getString(R.string.voice_need_mic_permission), Toast.LENGTH_LONG).show()
        }
        pickImageLauncher = registerForActivityResult(PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            executor.execute {
                try {
                    val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@execute
                    val (jpeg, _) = downscaleToJpeg(raw)
                    runOnUiThread { chatImageDraft.value = jpeg }
                } catch (e: Exception) {
                    XLog.e(TAG, "pick image failed", e)
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.chat_image_load_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Hide floating circle
        try { FloatingCircleManager.hide() } catch (_: Exception) {}

        val themeColors = ThemeManager.getColors()
        window.statusBarColor = themeColors.toolbarBg
        // Match navigation bar to app chrome so the system bar does not "float" with a default color
        window.navigationBarColor = themeColors.bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.navigationBarDividerColor = AndroidColor.TRANSPARENT
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightNavigationBars = !ThemeManager.isDark()
        }

        // Build Compose colors from ThemeManager
        val composeColors = with(ThemeManager) { themeColors.toComposeColors() }

        setContent {
            ChatScreen(
                messages = _messages.toList(),
                modelStatus = _modelStatus.value,
                needsPermission = _needsPermission.value,
                isProcessing = _isProcessing.value,
                taskRunning = _taskRunning.value,
                onStopTask = { stopRunningTask() },
                isDownloading = _isDownloading.value,
                downloadProgress = _downloadProgress.value,
                draftText = chatDraftText.value,
                onDraftTextChange = { chatDraftText.value = it },
                isVoiceRecording = chatVoiceRecording.value,
                voiceRecordingLevels = chatVoiceRecordingLevels.value,
                voiceDraftWav = chatVoiceDraft.value,
                voiceDraftWaveform = chatVoiceDraftWaveform.value,
                voicePreviewPlaying = chatVoicePreviewPlaying.value,
                voiceDraftDurationLabel = chatVoiceDraft.value?.let { w ->
                    formatVoiceDurationMs(wavDurationMsMono16k(w))
                } ?: "",
                onVoiceToggle = { toggleVoiceRecording() },
                onVoiceDraftPlayPause = { toggleVoiceDraftPlayback() },
                onVoiceDraftDiscard = { discardVoiceDraft() },
                imageAttachmentJpeg = chatImageDraft.value,
                onImageAttachmentClear = { chatImageDraft.value = null },
                onPlayMessageVoice = { wav -> playVoiceAttachment(wav) },
                onSendChat = { text, voiceWav -> sendChat(text, voiceWav) },
                onSendTask = { sendTask(it) },
                onNewChat = {
                    chatDraftText.value = ""
                    discardVoiceDraft()
                    chatImageDraft.value = null
                    newChat()
                },
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onOpenModels = { startActivity(Intent(this, LlmConfigActivity::class.java)) },
                onFixPermissions = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onAttach = {
                    pickImageLauncher.launch(
                        PickVisualMediaRequest(PickVisualMedia.ImageOnly),
                    )
                },
                conversations = _conversations.toList(),
                onSelectConversation = {
                    chatDraftText.value = ""
                    discardVoiceDraft()
                    chatImageDraft.value = null
                    loadConversation(it)
                },
                colors = composeColors,
            )
        }

        loadSidebarHistory()
        loadModelIfReady()

        // Debug: auto-trigger task from ADB intent
        // Usage: adb shell am start -n io.agents.pokeclaw/.ui.chat.ComposeChatActivity --es task "open my camera"
        intent?.getStringExtra("task")?.let { taskText ->
            XLog.i(TAG, "Auto-task from intent: $taskText")
            // Wait for model to load, then send task
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (isModelReady) {
                        sendTask(taskText)
                    } else {
                        handler.postDelayed(this, 1000)
                    }
                }
            }, 2000)
        }

    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle task from broadcast receiver (SINGLE_TOP re-delivery)
        intent.getStringExtra("task")?.let { taskText ->
            XLog.i(TAG, "Task from onNewIntent: $taskText")
            if (isModelReady) {
                sendTask(taskText)
            } else {
                val handler = Handler(Looper.getMainLooper())
                handler.postDelayed(object : Runnable {
                    override fun run() {
                        if (isModelReady) sendTask(taskText)
                        else handler.postDelayed(this, 1000)
                    }
                }, 1000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        _taskRunning.value = appViewModel.isTaskRunning()
        _needsPermission.value = !ClawAccessibilityService.isRunning()
        loadSidebarHistory()
        permHandler.removeCallbacks(permPoller)
        permHandler.postDelayed(permPoller, 1000)

        // Reload model if changed, or reconnect if needed
        val currentModelPath = KVUtils.getLocalModelPath()
        if (currentModelPath.isNotEmpty() && currentModelPath != loadedModelPath) {
            loadModelIfReady()
        } else if (!isModelReady && engine != null && currentModelPath.isNotEmpty()) {
            executor.submit {
                try {
                    conversation = engine!!.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                            samplerConfig = LiteRtSampling.fromAgentConfig(appViewModel.getAgentConfig())
                        )
                    )
                    isModelReady = true
                    runOnUiThread { setButtonsEnabled(true) }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to recreate conversation", e)
                }
            }
        } else if (!isModelReady && engine == null && currentModelPath.isNotEmpty()) {
            loadModelIfReady()
        } else if (!isModelReady && KVUtils.isRemoteLlmConfigured()) {
            loadModelIfReady()
        }
    }

    override fun onPause() {
        super.onPause()
        saveChat()
        if (engine != null && ConversationCompactor.needsCompaction(_messages)) {
            executor.submit {
                try { conversation?.close() } catch (_: Exception) {}
                conversation = null
                ConversationCompactor.compact(engine!!, _messages, this, conversationId)
                isModelReady = false
            }
        }
        permHandler.removeCallbacks(permPoller)
        executor.submit {
            try { conversation?.close() } catch (_: Exception) {}
            conversation = null
            isModelReady = false
        }
    }

    override fun onDestroy() {
        voiceStopHandler.removeCallbacks(voiceStopRunnable)
        if (chatVoiceRecording.value) {
            chatVoiceRecording.value = false
            try {
                voiceWavRecorder?.onAmplitude = null
                voiceWavRecorder?.stop()
            } catch (_: Exception) { }
            voiceWavRecorder = null
        }
        try {
            voicePreviewPlayer.stop()
        } catch (_: Exception) { }
        super.onDestroy()
        // Close the Conversation but leave the Engine in EngineHolder.
        // The engine will be reused if the Activity is recreated (e.g. rotation).
        // EngineHolder.close() is only called when the model file is being changed/deleted.
        executor.submit {
            XLog.i(TAG, "onDestroy: closing conversation (engine stays in EngineHolder)")
            try { conversation?.close() } catch (e: Exception) { XLog.w(TAG, "onDestroy: conversation close error", e) }
            conversation = null
        }
        executor.shutdown()
    }

    // ==================== MODEL LOADING ====================

    private fun loadModelIfReady() {
        val modelPath = KVUtils.getLocalModelPath()
        XLog.d(TAG, "loadModelIfReady: stored=$modelPath loaded=$loadedModelPath engine=${engine != null} remote=${KVUtils.isRemoteLlmConfigured()}")

        if (KVUtils.isRemoteLlmConfigured()) {
            isModelReady = true
            _isDownloading.value = false
            _modelStatus.value = "● ${KVUtils.getLlmModelName()} · Cloud"
            setButtonsEnabled(true)
            executor.submit {
                try { conversation?.close() } catch (_: Exception) {}
                conversation = null
                engine = null
                loadedModelPath = null
                EngineHolder.close()
            }
            return
        }

        if (!KVUtils.shouldLoadLocalLiteRt()) {
            _isDownloading.value = false
            _modelStatus.value = "No model — open Models (menu) to download on-device or configure Cloud LLM"
            isModelReady = false
            setButtonsEnabled(false)
            return
        }

        val localPath = KVUtils.getLocalModelPath()
        if (localPath.isEmpty()) {
            _modelStatus.value = "No on-device model — download one in Models"
            isModelReady = false
            setButtonsEnabled(false)
            return
        }

        // If model changed OR engine not ready, close conversation and let EngineHolder
        // swap the engine on next getOrCreate() call.
        if (localPath.isNotEmpty() && engine != null && localPath != loadedModelPath) {
            XLog.d(TAG, "loadModelIfReady: model changed ($loadedModelPath -> $localPath), closing conversation")
            val oldConv = conversation
            engine = null
            conversation = null
            isModelReady = false
            loadedModelPath = null
            executor.submit {
                try { oldConv?.close() } catch (e: Exception) { XLog.w(TAG, "loadModelIfReady: conv close error", e) }
                runOnUiThread { loadModelIfReady() }
            }
            return
        }

        _modelStatus.value = "Loading..."
        setButtonsEnabled(false)
        executor.submit { loadModel(localPath) }
    }

    private fun loadModel(modelPath: String) {
        try {
            loadModelWithBackend(modelPath, Backend.CPU())
        } catch (gpuError: Exception) {
            XLog.w(TAG, "Load failed: ${gpuError.message}, retrying with CPU")
            try {
                engine?.close()
                engine = null
                loadModelWithBackend(modelPath, Backend.CPU())
            } catch (cpuError: Exception) {
                throw cpuError
            }
        }
    }

    private fun loadModelWithBackend(modelPath: String, backend: com.google.ai.edge.litertlm.Backend) {
        try {
            // Use shared EngineHolder — avoids 2-3 s reinit when switching between chat
            // and task mode, since the task agent uses the same engine via LocalLlmClient.
            XLog.i(TAG, "loadModelWithBackend: requesting engine from EngineHolder for $modelPath")
            try { conversation?.close() } catch (_: Exception) {}
            conversation = null

            // Wait for task agent's conversation to fully close before creating new one
            Thread.sleep(1000)

            engine = EngineHolder.getOrCreate(modelPath, cacheDir.path)
            XLog.i(TAG, "loadModelWithBackend: engine ready")

            // Retry createConversation with backoff — task conversation may still be closing
            var created = false
            for (attempt in 1..5) {
                try {
                    conversation = engine!!.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                            samplerConfig = LiteRtSampling.fromAgentConfig(appViewModel.getAgentConfig())
                        )
                    )
                    created = true
                    break
                } catch (e: Exception) {
                    XLog.w(TAG, "loadModelWithBackend: createConversation attempt $attempt failed: ${e.message}")
                    if (attempt < 5) Thread.sleep(1500)
                }
            }
            if (!created) throw RuntimeException("Failed to create conversation after 5 retries")

            isModelReady = true
            loadedModelPath = modelPath
            val modelInfo = LocalModelManager.AVAILABLE_MODELS.find { modelPath.endsWith(it.fileName) }
            val modelName = modelInfo?.displayName ?: modelPath.substringAfterLast('/').substringBeforeLast('.')
            val backendLabel = if (backend is Backend.CPU) "CPU" else "GPU"
            runOnUiThread {
                _modelStatus.value = "● $modelName · $backendLabel"
                setButtonsEnabled(true)
                if (_messages.isEmpty()) {
                    _messages.add(ChatMessage(ChatMessage.Role.ASSISTANT,
                        "I'm a small AI running entirely on your phone — no cloud, no internet needed. I work best with simple questions and phone tasks. Switch to Task mode to let me control your phone for you."))
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Model load failed", e)
            runOnUiThread {
                _modelStatus.value = "Error: ${e.message}"
                addSystem("Failed: ${e.message}")
            }
        }
    }

    // ==================== CHAT ====================

    private fun sendChat(text: String, voiceWavFromBar: ByteArray? = null) {
        val trimmed = text.trim()
        if (!isModelReady) return
        val imageJpeg = chatImageDraft.value.also { chatImageDraft.value = null }
        val wavFromUi = voiceWavFromBar ?: chatVoiceDraft.value
        discardVoiceDraft()
        if (trimmed.isEmpty() && (wavFromUi == null || wavFromUi.isEmpty()) && imageJpeg == null) return

        val userVisible = when {
            imageJpeg != null && wavFromUi != null && trimmed.isNotEmpty() -> "🖼️ 🎤 $trimmed"
            imageJpeg != null && wavFromUi != null -> "🖼️ 🎤"
            imageJpeg != null && trimmed.isNotEmpty() -> "🖼️ $trimmed"
            imageJpeg != null -> "🖼️"
            wavFromUi != null && trimmed.isNotEmpty() -> "🎤 $trimmed"
            wavFromUi != null -> "🎤 Voice message"
            else -> trimmed
        }
        addUser(userVisible, imageJpeg, wavFromUi)
        _isProcessing.value = true
        _messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        executor.submit {
            try {
                when {
                    KVUtils.isRemoteLlmConfigured() -> {
                        val cfg = appViewModel.getAgentConfig()
                        val wavForFallback = wavFromUi
                        var wav = wavFromUi
                        var textIn = trimmed
                        val tryOpenAiAudio = cfg.provider == LlmProvider.OPENAI &&
                            wav != null &&
                            ModelAudioSupport.remoteOpenAiStyleModelMayAcceptAudio(cfg.modelName)
                        if (wav != null && !tryOpenAiAudio) {
                            val pcm = stripOurWavHeaderToPcm16le(wav)
                            val tr = WhisperKitTranscriber.transcribePcm16MonoBlocking(
                                ClawApplication.instance,
                                pcm,
                            )
                            textIn = listOf(textIn, tr ?: "").filter { it.isNotBlank() }.joinToString("\n")
                            wav = null
                        }
                        val client = LlmClientFactory.create(cfg)
                        try {
                            val lastUser: UserMessage? = when {
                                imageJpeg != null && wav != null ->
                                    CloudMultimodalMessages.userTextImageAndWav(textIn, imageJpeg, "image/jpeg", wav)
                                imageJpeg != null ->
                                    CloudMultimodalMessages.userTextPlusImage(textIn, imageJpeg, "image/jpeg")
                                tryOpenAiAudio && wav != null ->
                                    CloudMultimodalMessages.userTextPlusWav(textIn, wav)
                                else -> null
                            }
                            val lcMessages = uiMessagesToLangchain(_messages.dropLast(1), lastUser)
                            fun runRemoteStream(msgs: List<dev.langchain4j.data.message.ChatMessage>): Pair<String, String> {
                                val contentSb = StringBuilder()
                                val reasoningSb = StringBuilder()
                                val response = client.chatStreaming(msgs, emptyList(), object : StreamingListener {
                                    override fun onPartialText(token: String) {
                                        contentSb.append(token)
                                        val c = contentSb.toString().ifBlank { "..." }
                                        runOnUiThread { patchLastAssistantContent(c, reasoningSb.toString()) }
                                    }
                                    override fun onPartialReasoning(chunk: String) {
                                        reasoningSb.append(chunk)
                                        val c = contentSb.toString().ifBlank { "..." }
                                        runOnUiThread { patchLastAssistantContent(c, reasoningSb.toString()) }
                                    }
                                    override fun onComplete(response: LlmResponse) {
                                        val finalText = (response.text ?: contentSb.toString()).ifBlank { "(no response)" }
                                        val think = response.reasoning?.takeIf { it.isNotBlank() } ?: reasoningSb.toString()
                                        runOnUiThread { patchLastAssistantContent(finalText, think) }
                                    }
                                    override fun onError(error: Throwable) {
                                        XLog.e(TAG, "Remote chat stream error", error)
                                    }
                                })
                                val outText = (response.text ?: contentSb.toString()).ifBlank { "(no response)" }
                                val outReason = response.reasoning?.takeIf { it.isNotBlank() } ?: reasoningSb.toString()
                                return Pair(outText, outReason)
                            }
                            val (responseText, reasoningText) = try {
                                runRemoteStream(lcMessages)
                            } catch (e: Exception) {
                                if (tryOpenAiAudio) {
                                    XLog.w(TAG, "Multimodal audio request failed, falling back to Whisper text", e)
                                    val pcm = stripOurWavHeaderToPcm16le(wavForFallback)
                                    val tr = WhisperKitTranscriber.transcribePcm16MonoBlocking(
                                        ClawApplication.instance,
                                        pcm,
                                    )
                                    val merged = listOf(trimmed, tr ?: "").filter { it.isNotBlank() }.joinToString("\n")
                                    val history = uiMessagesToLangchain(_messages.dropLast(2))
                                    val retryMsgs = if (imageJpeg != null) {
                                        history + CloudMultimodalMessages.userTextPlusImage(merged, imageJpeg, "image/jpeg")
                                    } else {
                                        history + UserMessage.from(merged)
                                    }
                                    runRemoteStream(retryMsgs)
                                } else {
                                    throw e
                                }
                            }
                            runOnUiThread {
                                val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                                if (idx >= 0) {
                                    _messages[idx] = _messages[idx].copy(
                                        content = responseText,
                                        reasoning = reasoningText,
                                    )
                                }
                                _isProcessing.value = false
                                saveChat()
                            }
                        } finally {
                            client.close()
                        }
                    }
                    conversation != null -> {
                        var wavLocal = wavFromUi
                        var textIn = trimmed
                        if (wavLocal != null && !ModelAudioSupport.localGemma4SupportsNativeAudio()) {
                            val pcm = stripOurWavHeaderToPcm16le(wavLocal)
                            val tr = WhisperKitTranscriber.transcribePcm16MonoBlocking(
                                ClawApplication.instance,
                                pcm,
                            )
                            textIn = listOf(textIn, tr ?: "").filter { it.isNotBlank() }.joinToString("\n")
                            wavLocal = null
                        }
                        val thinkingCtx = mapOf<String, Any>("enable_thinking" to true)
                        val latch = CountDownLatch(1)
                        val errorRef = AtomicReference<Throwable>(null)
                        var textSoFar = ""
                        var reasoningSoFar = ""
                        val callback = object : MessageCallback {
                            override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                                val fullText = liteRtAssistantPlainText(message)
                                if (fullText.length > textSoFar.length) {
                                    textSoFar = fullText
                                } else if (fullText.isNotEmpty()) {
                                    textSoFar = fullText
                                }
                                val r = liteRtReasoningFromMessage(message)
                                if (r.length > reasoningSoFar.length) {
                                    reasoningSoFar = r
                                } else if (r.isNotEmpty()) {
                                    reasoningSoFar = r
                                }
                                val show = textSoFar.ifBlank { "..." }
                                runOnUiThread { patchLastAssistantContent(show, reasoningSoFar) }
                            }
                            override fun onDone() {
                                latch.countDown()
                            }
                            override fun onError(throwable: Throwable) {
                                errorRef.set(throwable)
                                latch.countDown()
                            }
                        }
                        val hasMultimodal = imageJpeg != null || wavLocal != null
                        if (hasMultimodal) {
                            val parts = mutableListOf<Content>()
                            if (textIn.isNotBlank()) {
                                parts.add(Content.Text(textIn))
                            }
                            if (imageJpeg != null) {
                                parts.add(Content.ImageBytes(imageJpeg))
                            }
                            if (wavLocal != null) {
                                parts.add(Content.AudioBytes(wavLocal))
                            }
                            if (parts.isEmpty()) {
                                parts.add(Content.Text("(empty)"))
                            }
                            conversation!!.sendMessageAsync(Contents.of(parts), callback, thinkingCtx)
                        } else {
                            conversation!!.sendMessageAsync(textIn, callback, thinkingCtx)
                        }
                        latch.await()
                        errorRef.get()?.let { throw it }
                        val responseText = textSoFar.ifBlank { "(no response)" }
                        runOnUiThread {
                            val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                            if (idx >= 0) {
                                _messages[idx] = _messages[idx].copy(
                                    content = responseText,
                                    reasoning = reasoningSoFar,
                                )
                            }
                            _isProcessing.value = false
                            saveChat()
                        }
                    }
                    else -> {
                        runOnUiThread {
                            val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                            if (idx >= 0) {
                                _messages[idx] = _messages[idx].copy(
                                    content = "Configure an on-device or cloud model in Models (menu).",
                                )
                            }
                            _isProcessing.value = false
                        }
                    }
                }
            } catch (e: Exception) {
                XLog.e(TAG, "Chat error", e)
                runOnUiThread {
                    val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                    if (idx >= 0) _messages[idx] = _messages[idx].copy(content = "Error: ${e.message}")
                    _isProcessing.value = false
                }
            }
        }
    }

    private fun uiMessagesToLangchain(
        messages: List<ChatMessage>,
        lastUserReplacement: UserMessage? = null,
    ): List<dev.langchain4j.data.message.ChatMessage> {
        val source = if (lastUserReplacement != null &&
            messages.isNotEmpty() &&
            messages.last().role == ChatMessage.Role.USER
        ) {
            messages.dropLast(1)
        } else {
            messages
        }
        val out = ArrayList<dev.langchain4j.data.message.ChatMessage>()
        for (m in source) {
            when (m.role) {
                ChatMessage.Role.USER -> out.add(UserMessage.from(m.content))
                ChatMessage.Role.ASSISTANT ->
                    if (m.content.isNotBlank()) out.add(AiMessage.from(m.content))
                ChatMessage.Role.SYSTEM -> out.add(SystemMessage.from(m.content))
                ChatMessage.Role.TOOL_GROUP -> { }
            }
        }
        if (lastUserReplacement != null) {
            out.add(lastUserReplacement)
        }
        return out
    }

    private fun formatVoiceDurationMs(ms: Long): String {
        val s = (ms / 1000L).toInt().coerceAtLeast(0)
        val m = s / 60
        return if (m > 0) String.format("%d:%02d", m, s % 60) else "${s}s"
    }

    private fun discardVoiceDraft() {
        voiceStopHandler.removeCallbacks(voiceStopRunnable)
        voicePreviewPlayer.stop()
        chatVoicePreviewPlaying.value = false
        chatVoiceDraft.value = null
        chatVoiceDraftWaveform.value = emptyList()
    }

    private fun toggleVoiceDraftPlayback() {
        val w = chatVoiceDraft.value ?: return
        if (voicePreviewPlayer.isPlaying) {
            voicePreviewPlayer.stop()
            chatVoicePreviewPlaying.value = false
        } else {
            voicePreviewPlayer.play(w) {
                runOnUiThread { chatVoicePreviewPlaying.value = false }
            }
            chatVoicePreviewPlaying.value = true
        }
    }

    private fun playVoiceAttachment(wav: ByteArray) {
        voicePreviewPlayer.stop()
        chatVoicePreviewPlaying.value = false
        voicePreviewPlayer.play(wav) { }
    }

    private fun toggleVoiceRecording() {
        if (chatVoiceRecording.value) {
            stopVoiceRecordingToDraft()
        } else {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED -> {
                    recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                else -> startVoiceRecordingInternal()
            }
        }
    }

    private fun startVoiceRecordingInternal() {
        if (chatVoiceRecording.value) return
        discardVoiceDraft()
        voicePreviewPlayer.stop()
        chatVoicePreviewPlaying.value = false
        chatVoiceRecordingLevels.value = emptyList()
        val rec = PcmWavRecorder()
        rec.onAmplitude = { rms ->
            runOnUiThread {
                val cur = chatVoiceRecordingLevels.value
                chatVoiceRecordingLevels.value = (cur + rms).takeLast(56)
            }
        }
        voiceWavRecorder = rec
        if (rec.start() != true) {
            voiceWavRecorder = null
            Toast.makeText(this, "Could not start microphone", Toast.LENGTH_SHORT).show()
            return
        }
        chatVoiceRecording.value = true
        voiceStopHandler.postDelayed(voiceStopRunnable, VOICE_MAX_MS)
    }

    private fun stopVoiceRecordingToDraft() {
        voiceStopHandler.removeCallbacks(voiceStopRunnable)
        if (!chatVoiceRecording.value) return
        chatVoiceRecording.value = false
        voiceWavRecorder?.onAmplitude = null
        val wav = try {
            voiceWavRecorder?.stop()
        } finally {
            voiceWavRecorder = null
        }
        chatVoiceRecordingLevels.value = emptyList()
        if (wav != null && wav.isNotEmpty()) {
            chatVoiceDraft.value = wav
            chatVoiceDraftWaveform.value = waveformBarsFromWav(wav)
        } else {
            Toast.makeText(this, getString(R.string.chat_voice_too_short), Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendTask(text: String) {
        if (!ClawAccessibilityService.isRunning()) {
            // Lazy permission — only ask when Task mode is first used
            addSystem("Task mode needs Accessibility permission to control your phone.")
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Enable PokeClaw in Accessibility settings", Toast.LENGTH_LONG).show()
            return
        }

        // Ensure notification permission + foreground service for task progress visibility
        ensureNotificationPermission()

        // Reset stuck processing state from previous task
        _isProcessing.value = false

        if (!KVUtils.hasLlmConfig()) {
            Toast.makeText(this, "Configure LLM in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        addUser("🚀 $text")
        _isProcessing.value = true
        addSystem("Starting task...")

        // Register progress callback so TaskOrchestrator events appear in chat.
        val taskStartTime = System.currentTimeMillis()
        appViewModel.taskOrchestrator.taskProgressCallback = { msg ->
            val elapsed = (System.currentTimeMillis() - taskStartTime) / 1000.0
            XLog.i(TAG, "sendTask progress [${elapsed}s]: $msg")
            runOnUiThread {
                try {
                    addSystem(msg)
                } catch (e: Exception) {
                    XLog.w(TAG, "sendTask: addSystem error", e)
                }
                // When task finishes (complete / error / dialog-blocked), reload chat engine.
                // Wait 500ms after the callback fires so DefaultAgentService's finally block
                // (which calls llmClient.close()) has time to complete before we allocate a
                // new chat engine. This prevents two LiteRT-LM engines coexisting = OOM.
                if (isTerminalTaskProgress(msg)) {
                    XLog.i(TAG, "sendTask: task done via progress callback, scheduling chat engine reload")
                    _isProcessing.value = false
                    _taskRunning.value = false
                    appViewModel.taskOrchestrator.taskProgressCallback = null
                    Handler(Looper.getMainLooper()).postDelayed({
                        XLog.i(TAG, "sendTask: reloading chat engine after task engine released")
                        try {
                            loadModelIfReady()
                        } catch (e: Exception) {
                            XLog.e(TAG, "sendTask: loadModelIfReady error", e)
                            addSystem("Error reloading model: ${e.message}")
                        }
                    }, 500)
                }
            }
        }

        // Close only the chat Conversation — the Engine stays alive in EngineHolder so
        // LocalLlmClient (used by the task agent) can reuse it immediately without the
        // 2-3 s reinit cost. LiteRT-LM's constraint is one Conversation at a time, not
        // one Engine at a time, so releasing the Conversation is sufficient.
        val taskText = text
        val taskId = "task_${System.currentTimeMillis()}"
        executor.submit {
            // Cancel any running task first
            try {
                appViewModel.taskOrchestrator.cancelCurrentTask()
                Thread.sleep(500)
            } catch (_: Exception) {}

            XLog.i(TAG, "sendTask: closing chat conversation before task id=$taskId (engine stays in EngineHolder)")
            try { conversation?.close() } catch (e: Exception) { XLog.w(TAG, "sendTask: conversation close error", e) }
            conversation = null
            isModelReady = false
            XLog.i(TAG, "sendTask: chat conversation closed, launching task")

            // Now safe to start task — engine is released
            runOnUiThread {
                try {
                    val started = appViewModel.startNewTask(ChannelEnum.LOCAL, taskText, taskId)
                    _taskRunning.value = started && appViewModel.isTaskRunning()
                    if (!started) {
                        addSystem(getString(R.string.channel_msg_task_in_progress))
                        _isProcessing.value = false
                        appViewModel.taskOrchestrator.taskProgressCallback = null
                        XLog.w(TAG, "sendTask: could not start, another task running")
                        return@runOnUiThread
                    }
                    XLog.i(TAG, "sendTask: task started id=$taskId")
                } catch (e: Exception) {
                    XLog.e(TAG, "sendTask: failed to start task", e)
                    addSystem("Error starting task: ${e.message}")
                    _isProcessing.value = false
                    appViewModel.taskOrchestrator.taskProgressCallback = null
                    try {
                        loadModelIfReady()
                    } catch (re: Exception) {
                        XLog.e(TAG, "sendTask: loadModelIfReady after start failure", re)
                    }
                }
            }
        }
    }

    private fun newChat() {
        saveChat()
        conversationId = "chat_${System.currentTimeMillis()}"
        _messages.clear()
        if (KVUtils.shouldLoadLocalLiteRt() && engine != null) {
            executor.submit {
                try { conversation?.close() } catch (_: Exception) {}
                conversation = engine?.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                        samplerConfig = LiteRtSampling.fromAgentConfig(appViewModel.getAgentConfig())
                    )
                )
                runOnUiThread {
                    addSystem("New conversation started.")
                    loadSidebarHistory()
                }
            }
        } else {
            addSystem("New conversation started.")
            loadSidebarHistory()
        }
    }

    private fun loadConversation(conv: ChatHistoryManager.ConversationSummary) {
        saveChat()
        conversationId = conv.id
        _messages.clear()
        val messages = ChatHistoryManager.load(conv.file)
        _messages.addAll(messages)

        if (KVUtils.shouldLoadLocalLiteRt() && engine != null) {
            executor.submit {
                try {
                    try { conversation?.close() } catch (_: Exception) {}
                    val recentMsgs = messages.takeLast(5)
                    val systemPrompt = ConversationCompactor.buildRestoredSystemPrompt(this, conv.id, recentMsgs)
                    conversation = engine!!.createConversation(
                        ConversationConfig(
                            systemInstruction = Contents.of(systemPrompt),
                            samplerConfig = LiteRtSampling.fromAgentConfig(appViewModel.getAgentConfig())
                        )
                    )
                    isModelReady = true
                    runOnUiThread {
                        setButtonsEnabled(true)
                        addSystem("Conversation restored.")
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to restore conversation", e)
                    runOnUiThread { addSystem("History loaded. New context started.") }
                }
            }
        } else if (KVUtils.isRemoteLlmConfigured()) {
            addSystem("History loaded. Cloud mode uses this thread without on-device context replay.")
        }
    }

    // ==================== HELPERS ====================

    /**
     * Request notification permission (Android 13+) and start ForegroundService
     * so the user sees task progress in the status bar while PokeClaw is in background.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
        // Start foreground service if not running
        if (!io.agents.pokeclaw.service.ForegroundService.isRunning()) {
            io.agents.pokeclaw.service.ForegroundService.start(this)
        }
    }

    private fun addUser(
        text: String,
        imageJpeg: ByteArray? = null,
        voiceWav: ByteArray? = null,
    ) {
        _messages.add(
            ChatMessage(
                ChatMessage.Role.USER,
                text,
                attachmentImageJpeg = imageJpeg,
                attachmentVoiceWav = voiceWav,
            ),
        )
    }
    private fun addSystem(text: String) { _messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text)) }

    private fun setButtonsEnabled(enabled: Boolean) {
        _isProcessing.value = !enabled
    }

    private fun saveChat() {
        val modelName = when {
            KVUtils.isRemoteLlmConfigured() -> KVUtils.getLlmModelName()
            KVUtils.getLocalModelPath().isNotEmpty() ->
                KVUtils.getLocalModelPath().substringAfterLast('/').substringBeforeLast('.')
            else -> "none"
        }
        ChatHistoryManager.save(this, conversationId, _messages, modelName)
        loadSidebarHistory()
    }

    private fun loadSidebarHistory() {
        val convos = ChatHistoryManager.listConversations(this)
        _conversations.clear()
        _conversations.addAll(convos)
    }

    /** Matches [TaskOrchestrator] progress strings (localized). */
    private fun isTerminalTaskProgress(msg: String): Boolean =
        msg == getString(R.string.chat_task_progress_completed) ||
            msg == getString(R.string.chat_task_progress_cancelled) ||
            msg.startsWith(getString(R.string.chat_task_progress_failed_prefix)) ||
            msg == getString(R.string.chat_task_progress_blocked)

    private fun stopRunningTask() {
        if (!appViewModel.isTaskRunning()) return
        appViewModel.cancelCurrentTask()
        _taskRunning.value = false
    }

    private fun patchLastAssistantContent(content: String, reasoning: String) {
        val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (idx >= 0) {
            _messages[idx] = _messages[idx].copy(content = content, reasoning = reasoning)
        }
    }

    private fun liteRtAssistantPlainText(m: com.google.ai.edge.litertlm.Message): String {
        val wrapper = m.contents ?: return ""
        val parts = wrapper.contents ?: return m.toString()
        val sb = StringBuilder()
        for (part in parts) {
            if (part is Content.Text) {
                sb.append(part.text)
            }
        }
        return sb.toString().ifEmpty { m.toString() }
    }

    private fun liteRtReasoningFromMessage(m: com.google.ai.edge.litertlm.Message): String {
        val ch = m.channels ?: return ""
        for (k in listOf("thinking", "reasoning", "thought", "internal")) {
            val v = ch[k]?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        return ""
    }
}
