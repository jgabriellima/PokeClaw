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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.ai.edge.litertlm.Content
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.agent.LlmProvider
import io.agents.pokeclaw.audio.CloudMultimodalMessages
import io.agents.pokeclaw.audio.ModelAudioSupport
import io.agents.pokeclaw.audio.PcmWavRecorder
import io.agents.pokeclaw.audio.WhisperKitTranscriber
import io.agents.pokeclaw.audio.stripOurWavHeaderToPcm16le
import io.agents.pokeclaw.agent.llm.EngineHolder
import io.agents.pokeclaw.agent.llm.LlmClientFactory
import io.agents.pokeclaw.agent.llm.LocalModelManager
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
import com.google.ai.edge.litertlm.SamplerConfig
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
    private var voiceWavRecorder: PcmWavRecorder? = null
    private val voiceStopHandler = Handler(Looper.getMainLooper())
    private val voiceStopRunnable = Runnable { stopVoiceRecording() }

    private lateinit var recordAudioLauncher: ActivityResultLauncher<String>

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
                isDownloading = _isDownloading.value,
                downloadProgress = _downloadProgress.value,
                draftText = chatDraftText.value,
                onDraftTextChange = { chatDraftText.value = it },
                isVoiceRecording = chatVoiceRecording.value,
                onVoiceToggle = { toggleVoiceRecording() },
                onSendChat = { text, voiceWav -> sendChat(text, voiceWav) },
                onSendTask = { sendTask(it) },
                onNewChat = {
                    chatDraftText.value = ""
                    newChat()
                },
                onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onOpenModels = { startActivity(Intent(this, LlmConfigActivity::class.java)) },
                onFixPermissions = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onAttach = { Toast.makeText(this, "Image upload coming soon", Toast.LENGTH_SHORT).show() },
                conversations = _conversations.toList(),
                onSelectConversation = {
                    chatDraftText.value = ""
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
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
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
                voiceWavRecorder?.stop()
            } catch (_: Exception) { }
            voiceWavRecorder = null
        }
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
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
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

    private fun sendChat(text: String, voiceWav: ByteArray? = null) {
        val trimmed = text.trim()
        if (!isModelReady) return
        if (trimmed.isEmpty() && (voiceWav == null || voiceWav.isEmpty())) return

        val userVisible = when {
            voiceWav != null && trimmed.isNotEmpty() -> "🎤 $trimmed"
            voiceWav != null -> "🎤 Voice message"
            else -> trimmed
        }
        addUser(userVisible)
        _isProcessing.value = true
        _messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        executor.submit {
            try {
                when {
                    KVUtils.isRemoteLlmConfigured() -> {
                        val cfg = appViewModel.getAgentConfig()
                        var wav = voiceWav
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
                            val lastUser: UserMessage? =
                                if (tryOpenAiAudio && wav != null) {
                                    CloudMultimodalMessages.userTextPlusWav(textIn, wav)
                                } else {
                                    null
                                }
                            val lcMessages = uiMessagesToLangchain(_messages.dropLast(1), lastUser)
                            fun runRemote(msgs: List<dev.langchain4j.data.message.ChatMessage>): String {
                                val response = client.chat(msgs, emptyList())
                                return (response.text ?: "").ifEmpty { "(no response)" }
                            }
                            val responseText = try {
                                runRemote(lcMessages)
                            } catch (e: Exception) {
                                if (tryOpenAiAudio) {
                                    val capturedWav = voiceWav ?: throw e
                                    XLog.w(TAG, "Multimodal audio request failed, falling back to Whisper text", e)
                                    val pcm = stripOurWavHeaderToPcm16le(capturedWav)
                                    val tr = WhisperKitTranscriber.transcribePcm16MonoBlocking(
                                        ClawApplication.instance,
                                        pcm,
                                    )
                                    val merged = listOf(trimmed, tr ?: "").filter { it.isNotBlank() }.joinToString("\n")
                                    val history = uiMessagesToLangchain(_messages.dropLast(2))
                                    val retryMsgs = history + UserMessage.from(merged)
                                    runRemote(retryMsgs)
                                } else {
                                    throw e
                                }
                            }
                            runOnUiThread {
                                val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                                if (idx >= 0) _messages[idx] = _messages[idx].copy(content = responseText)
                                _isProcessing.value = false
                                saveChat()
                            }
                        } finally {
                            client.close()
                        }
                    }
                    conversation != null -> {
                        var wavLocal = voiceWav
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
                        val response = when {
                            wavLocal != null -> {
                                val parts = mutableListOf<Content>()
                                if (textIn.isNotBlank()) {
                                    parts.add(Content.Text(textIn))
                                }
                                parts.add(Content.AudioBytes(wavLocal))
                                conversation!!.sendMessage(Contents.of(parts), emptyMap())
                            }
                            else -> conversation!!.sendMessage(textIn)
                        }
                        val responseText = response?.toString() ?: "(no response)"
                        runOnUiThread {
                            val idx = _messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
                            if (idx >= 0) _messages[idx] = _messages[idx].copy(content = responseText)
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

    private fun toggleVoiceRecording() {
        if (chatVoiceRecording.value) {
            stopVoiceRecording()
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
        voiceWavRecorder = PcmWavRecorder()
        if (voiceWavRecorder?.start() != true) {
            voiceWavRecorder = null
            Toast.makeText(this, "Could not start microphone", Toast.LENGTH_SHORT).show()
            return
        }
        chatVoiceRecording.value = true
        voiceStopHandler.postDelayed(voiceStopRunnable, VOICE_MAX_MS)
    }

    private fun stopVoiceRecording() {
        voiceStopHandler.removeCallbacks(voiceStopRunnable)
        if (!chatVoiceRecording.value) return
        chatVoiceRecording.value = false
        val wav = try {
            voiceWavRecorder?.stop()
        } finally {
            voiceWavRecorder = null
        }
        if (wav != null && wav.isNotEmpty()) {
            sendChat(chatDraftText.value.trim(), wav)
            chatDraftText.value = ""
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
                if (msg.startsWith("Task completed") || msg.startsWith("Task failed") || msg.startsWith("Blocked")) {
                    XLog.i(TAG, "sendTask: task done via progress callback, scheduling chat engine reload")
                    _isProcessing.value = false
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
                    appViewModel.startNewTask(ChannelEnum.LOCAL, taskText, taskId)
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
                        samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
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
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
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

    private fun addUser(text: String) { _messages.add(ChatMessage(ChatMessage.Role.USER, text)) }
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
}
