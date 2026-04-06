// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.agents.pokeclaw.R
import io.agents.pokeclaw.agent.llm.LiteRtSampling
import io.agents.pokeclaw.agent.llm.LlmClientFactory
import io.agents.pokeclaw.agent.llm.LocalModelManager
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.agents.pokeclaw.appViewModel
import io.agents.pokeclaw.base.BaseActivity
import androidx.appcompat.app.AppCompatDelegate
import io.agents.pokeclaw.floating.FloatingCircleManager
import io.agents.pokeclaw.channel.Channel as ChannelEnum
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.util.concurrent.Executors

class ChatActivity : BaseActivity() {

    companion object {
        private const val TAG = "ChatActivity"
    }

    override fun isApplyStatusBarPadding(): Boolean = false


    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tvStatus: TextView
    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: TextView
    private lateinit var btnTask: TextView
    private lateinit var layoutPermBanner: LinearLayout

    private val permHandler = Handler(Looper.getMainLooper())
    private val permPoller = object : Runnable {
        override fun run() {
            updatePermBanner()
            permHandler.postDelayed(this, 1000)
        }
    }
    private var conversationId = "chat_${System.currentTimeMillis()}"
    private val adapter = ChatMessageAdapter()
    private val executor = Executors.newSingleThreadExecutor()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var isModelReady = false
    private var isProcessing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure correct night mode before setContentView
        val savedDark = ThemeManager.isDark()
        val currentNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (savedDark != currentNight) {
            AppCompatDelegate.setDefaultNightMode(if (savedDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }

        setContentView(R.layout.activity_chat)

        val themeColors = ThemeManager.getColors()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = themeColors.toolbarBg
        window.navigationBarColor = themeColors.bg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.navigationBarDividerColor = AndroidColor.TRANSPARENT
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightNavigationBars = !ThemeManager.isDark()
        }
        window.decorView.setBackgroundColor(themeColors.bg)

        val bottomDock = findViewById<LinearLayout>(R.id.layoutChatBottomDock)
        ViewCompat.setOnApplyWindowInsetsListener(bottomDock) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom)
            insets
        }

        drawerLayout = findViewById(R.id.drawerLayout)
        tvStatus = findViewById(R.id.tvStatus)
        rvMessages = findViewById(R.id.rvMessages)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnTask = findViewById(R.id.btnTask)
        layoutPermBanner = findViewById(R.id.layoutPermBanner)

        // RecyclerView
        rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rvMessages.adapter = adapter
        rvMessages.setBackgroundColor(themeColors.bg)

        // Apply theme to toolbar, input bar, sidebar
        // Toolbar bg applied via layout; send button color applied here
        // Toolbar and input backgrounds are set via layout @color refs,
        // but we override programmatically for runtime theme switching
        btnSend.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(themeColors.sendColor)
        }
        findViewById<View>(R.id.navDrawer)?.setBackgroundColor(themeColors.toolbarBg)

        // Toolbar actions
        findViewById<TextView>(R.id.btnMenu).setOnClickListener { drawerLayout.open() }
        findViewById<TextView>(R.id.btnNewChat).setOnClickListener { newChat() }
        findViewById<TextView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Drawer actions
        findViewById<TextView>(R.id.btnDrawerNewChat).setOnClickListener {
            drawerLayout.close()
            newChat()
        }
        findViewById<TextView>(R.id.btnDrawerSettings).setOnClickListener {
            drawerLayout.close()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btnDrawerModels).setOnClickListener {
            drawerLayout.close()
            startActivity(Intent(this, io.agents.pokeclaw.ui.settings.LlmConfigActivity::class.java))
        }

        // Tap chat area to dismiss keyboard
        rvMessages.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                etInput.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etInput.windowToken, 0)
            }
            false
        }

        // Attach button (placeholder)
        findViewById<TextView>(R.id.btnAttach).setOnClickListener {
            Toast.makeText(this, "Image upload coming soon", Toast.LENGTH_SHORT).show()
        }

        // Permission banner
        layoutPermBanner.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        updatePermBanner()

        // Send = chat
        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty() || !isModelReady || isProcessing) return@setOnClickListener
            sendChat(text)
        }

        // Task = agent
        btnTask.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty() || isProcessing) return@setOnClickListener
            sendTask(text)
        }

        // Hide floating circle in chat
        try { FloatingCircleManager.hide() } catch (_: Exception) {}

        // Load sidebar conversation history
        loadSidebarHistory()

        loadModelIfReady()
    }

    override fun onResume() {
        super.onResume()
        updatePermBanner()
        loadSidebarHistory()
        permHandler.removeCallbacks(permPoller)
        permHandler.postDelayed(permPoller, 1000)
        // Only reload if engine was released (onPause closes conversation)
        if (!isModelReady && engine != null && KVUtils.getLocalModelPath().isNotEmpty()) {
            // Engine still alive, just need new conversation
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
        } else if (!isModelReady && engine == null && KVUtils.getLocalModelPath().isNotEmpty()) {
            loadModelIfReady()
        } else if (!isModelReady && KVUtils.isRemoteLlmConfigured()) {
            loadModelIfReady()
        }
    }

    private fun updatePermBanner() {
        val needsPerm = !ClawAccessibilityService.isRunning()
        layoutPermBanner.visibility = if (needsPerm) View.VISIBLE else View.GONE
    }

    private fun loadModelIfReady() {
        if (KVUtils.isRemoteLlmConfigured()) {
            isModelReady = true
            tvStatus.text = "● ${KVUtils.getLlmModelName()} · Cloud"
            setButtonsEnabled(true)
            executor.submit {
                try { conversation?.close() } catch (_: Exception) {}
                conversation = null
                engine = null
                io.agents.pokeclaw.agent.llm.EngineHolder.close()
            }
            return
        }

        if (!KVUtils.shouldLoadLocalLiteRt()) {
            tvStatus.text = "No model — open Models to download or configure Cloud LLM"
            isModelReady = false
            setButtonsEnabled(false)
            return
        }

        val modelPath = KVUtils.getLocalModelPath()
        if (modelPath.isEmpty()) {
            tvStatus.text = "No on-device model — download in Models"
            isModelReady = false
            setButtonsEnabled(false)
            return
        }

        tvStatus.text = "Loading: ${modelPath.substringAfterLast('/')}"
        setButtonsEnabled(false)
        executor.submit { loadModel(modelPath) }
    }

    private fun loadModel(modelPath: String) {
        try {
            val backend = try { Backend.GPU() } catch (e: Exception) {
                XLog.w(TAG, "GPU unavailable, using CPU", e)
                Backend.CPU()
            }

            runOnUiThread { tvStatus.text = "Loading model..." }

            val cfg = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                maxNumTokens = 4096,
                cacheDir = cacheDir.path
            )
            engine = Engine(cfg)
            engine!!.initialize()

            conversation = engine!!.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                    samplerConfig = LiteRtSampling.fromAgentConfig(appViewModel.getAgentConfig())
                )
            )

            isModelReady = true
            val modelName = modelPath.substringAfterLast('/').substringBeforeLast('.')
            runOnUiThread {
                tvStatus.text = "● $modelName"
                setButtonsEnabled(true)
                addSystem("Model loaded. Send = chat, 🚀 = phone task.")
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Model load failed", e)
            runOnUiThread {
                tvStatus.text = "Error: ${e.message}"
                addSystem("Failed: ${e.message}")
            }
        }
    }

    private fun sendChat(text: String) {
        if (!isModelReady) return
        addUser(text)
        etInput.text.clear()
        setProcessing(true)

        adapter.addMessage(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))
        scrollBottom()

        executor.submit {
            try {
                when {
                    KVUtils.isRemoteLlmConfigured() -> {
                        val client = LlmClientFactory.create(appViewModel.getAgentConfig())
                        try {
                            val lcMessages = uiMessagesToLangchain(adapter.getAllMessages().dropLast(1))
                            val response = client.chat(lcMessages, emptyList())
                            val responseText = (response.text ?: "").ifEmpty { "(no response)" }
                            runOnUiThread {
                                adapter.updateLastAssistant(responseText)
                                scrollBottom()
                                setProcessing(false)
                                saveChat()
                            }
                        } finally {
                            client.close()
                        }
                    }
                    conversation != null -> {
                        val response = conversation!!.sendMessage(text)
                        val responseText = response?.toString() ?: "(no response)"
                        runOnUiThread {
                            adapter.updateLastAssistant(responseText)
                            scrollBottom()
                            setProcessing(false)
                            saveChat()
                        }
                    }
                    else -> {
                        runOnUiThread {
                            adapter.updateLastAssistant("Configure an on-device or cloud model in Models.")
                            setProcessing(false)
                        }
                    }
                }
            } catch (e: Exception) {
                XLog.e(TAG, "Chat error", e)
                runOnUiThread {
                    adapter.updateLastAssistant("Error: ${e.message}")
                    setProcessing(false)
                }
            }
        }
    }

    private fun uiMessagesToLangchain(messages: List<ChatMessage>): List<dev.langchain4j.data.message.ChatMessage> {
        val out = ArrayList<dev.langchain4j.data.message.ChatMessage>()
        for (m in messages) {
            when (m.role) {
                ChatMessage.Role.USER -> out.add(UserMessage.from(m.content))
                ChatMessage.Role.ASSISTANT -> if (m.content.isNotBlank()) out.add(AiMessage.from(m.content))
                ChatMessage.Role.SYSTEM -> out.add(SystemMessage.from(m.content))
                ChatMessage.Role.TOOL_GROUP -> { }
            }
        }
        return out
    }

    private fun sendTask(text: String) {
        if (!ClawAccessibilityService.isRunning()) {
            Toast.makeText(this, "Enable Accessibility in Settings first", Toast.LENGTH_LONG).show()
            return
        }
        if (!KVUtils.hasLlmConfig()) {
            Toast.makeText(this, "Configure LLM in Settings first", Toast.LENGTH_LONG).show()
            return
        }

        addUser("🚀 $text")
        etInput.text.clear()
        setProcessing(true)
        addSystem("Starting task...")

        if (!appViewModel.startNewTask(ChannelEnum.LOCAL, text, "chat_${System.currentTimeMillis()}")) {
            addSystem(getString(R.string.channel_msg_task_in_progress))
            setProcessing(false)
            return
        }

        // Poll for completion
        val check = object : Runnable {
            override fun run() {
                if (appViewModel.isTaskRunning()) {
                    rvMessages.postDelayed(this, 1000)
                } else {
                    runOnUiThread {
                        addSystem("Task completed.")
                        setProcessing(false)
                    }
                }
            }
        }
        rvMessages.postDelayed(check, 1000)
    }

    private fun loadSidebarHistory() {
        val container = findViewById<LinearLayout>(R.id.layoutSidebarHistory) ?: return
        container.removeAllViews()

        val conversations = ChatHistoryManager.listConversations(this)

        if (conversations.isEmpty()) {
            val empty = TextView(this).apply {
                text = "💬 No conversations yet"
                textSize = 14f
                setTextColor(getColor(R.color.colorTextTertiary))
                setPadding(dp(20), dp(10), dp(20), dp(10))
            }
            container.addView(empty)
            return
        }

        conversations.forEach { conv ->
            val item = TextView(this).apply {
                text = "💬 ${conv.title}"
                textSize = 14f
                setTextColor(getColor(R.color.colorTextPrimary))
                setPadding(dp(20), dp(12), dp(20), dp(12))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setBackgroundResource(android.R.attr.selectableItemBackground.let {
                    val attrs = intArrayOf(it)
                    val ta = obtainStyledAttributes(attrs)
                    val res = ta.getResourceId(0, 0)
                    ta.recycle()
                    res
                })
                setOnClickListener {
                    // Load this conversation
                    saveChat() // save current first
                    conversationId = conv.id
                    adapter.clear()
                    val messages = ChatHistoryManager.load(conv.file)
                    adapter.addAll(messages)
                    scrollBottom()
                    drawerLayout.close()

                    // Restore LLM context using digest + recent messages
                    if (KVUtils.shouldLoadLocalLiteRt() && engine != null) {
                        executor.submit {
                            try {
                                try { conversation?.close() } catch (_: Exception) {}

                                val recentMsgs = messages.takeLast(5)
                                val systemPrompt = ConversationCompactor.buildRestoredSystemPrompt(
                                    this@ChatActivity, conv.id, recentMsgs
                                )

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
                        addSystem("History loaded. Cloud mode does not replay on-device context.")
                    }
                }
            }
            container.addView(item)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun newChat() {
        saveChat()
        conversationId = "chat_${System.currentTimeMillis()}"
        adapter.clear()
        if (KVUtils.shouldLoadLocalLiteRt() && engine != null) {
            executor.submit {
                try { conversation?.close() } catch (_: Exception) {}
                conversation = engine?.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of("You are a helpful AI assistant on an Android phone."),
                        samplerConfig = LiteRtSampling.fromAgentConfig(appViewModel.getAgentConfig())
                    )
                )
                runOnUiThread { addSystem("New conversation started.") }
            }
        } else {
            addSystem("New conversation started.")
        }
    }

    private fun addUser(text: String) {
        adapter.addMessage(ChatMessage(ChatMessage.Role.USER, text))
        scrollBottom()
    }

    private fun addSystem(text: String) {
        adapter.addMessage(ChatMessage(ChatMessage.Role.SYSTEM, text))
        scrollBottom()
    }

    private fun saveChat() {
        val modelName = when {
            KVUtils.isRemoteLlmConfigured() -> KVUtils.getLlmModelName()
            KVUtils.getLocalModelPath().isNotEmpty() ->
                KVUtils.getLocalModelPath().substringAfterLast('/').substringBeforeLast('.')
            else -> "none"
        }
        ChatHistoryManager.save(this, conversationId, adapter.getAllMessages(), modelName)
        loadSidebarHistory()
    }

    private fun scrollBottom() {
        rvMessages.post {
            val count = adapter.itemCount
            if (count > 0) rvMessages.smoothScrollToPosition(count - 1)
        }
    }

    private fun setProcessing(v: Boolean) {
        isProcessing = v
        setButtonsEnabled(!v && isModelReady)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnSend.isEnabled = enabled
        btnSend.alpha = if (enabled) 1f else 0.4f
        btnTask.isEnabled = enabled
        btnTask.alpha = if (enabled) 1f else 0.4f
    }

    override fun onPause() {
        super.onPause()
        saveChat()
        // Compact in background if needed (only when user leaves — no session conflict)
        if (engine != null && ConversationCompactor.needsCompaction(adapter.getAllMessages())) {
            executor.submit {
                try { conversation?.close() } catch (_: Exception) {}
                conversation = null
                ConversationCompactor.compact(engine!!, adapter.getAllMessages(), this, conversationId)
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
        super.onDestroy()
        executor.submit {
            try { conversation?.close() } catch (_: Exception) {}
            try { engine?.close() } catch (_: Exception) {}
        }
        executor.shutdown()
    }
}
