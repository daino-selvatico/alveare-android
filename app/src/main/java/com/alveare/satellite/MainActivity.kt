package com.alveare.satellite

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.alveare.satellite.audio.AudioCaptureManager
import com.alveare.satellite.audio.AudioPlaybackManager
import com.alveare.satellite.audio.SoundEffects
import com.alveare.satellite.data.AppPreferences
import com.alveare.satellite.data.ChatMessage
import com.alveare.satellite.databinding.ActivityMainBinding
import com.alveare.satellite.network.AlveareLiveWebSocket
import com.alveare.satellite.tts.AndroidNativeTts
import com.alveare.satellite.ui.ChatAdapter
import com.alveare.satellite.ui.VisualizerView
import com.alveare.satellite.wakeword.WakeWordDetector
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity(), AlveareLiveWebSocket.LiveWebSocketListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    // Audio & Network Engines
    private var playbackManager: AudioPlaybackManager? = null
    private var captureManager: AudioCaptureManager? = null
    private var nativeTts: AndroidNativeTts? = null
    private var wakeWordDetector: WakeWordDetector? = null
    private var webSocket: AlveareLiveWebSocket? = null

    // Real-Time Chat Conversation History
    private lateinit var chatAdapter: ChatAdapter

    private var isAssistantSpeaking = false

    companion object {
        private const val PERMISSION_REQ_RECORD_AUDIO = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        applySmartDisplayMode()

        initUi()
        initAudioEngines()

        checkPermissionsAndStart()
    }

    private fun applySmartDisplayMode() {
        if (prefs.isSmartDisplayEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun initUi() {
        binding.tvRoomSubtitle.text = "${prefs.roomName} • Smart Satellite"

        // 1. Setup Chat RecyclerView
        chatAdapter = ChatAdapter()
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChatHistory.layoutManager = layoutManager
        binding.rvChatHistory.adapter = chatAdapter

        // Load saved conversation history
        val savedHistory = prefs.getConversationHistory()
        if (savedHistory.isNotEmpty()) {
            chatAdapter.setMessages(savedHistory)
            binding.layoutEmptyChat.visibility = View.GONE
            binding.rvChatHistory.scrollToPosition(savedHistory.size - 1)
        } else {
            binding.layoutEmptyChat.visibility = View.VISIBLE
        }

        updateBadges()

        // 2. Connection Triggers (Click on Pill or Banner to connect/reconnect)
        binding.statusPill.setOnClickListener {
            connectWebSocket()
        }

        binding.btnBannerConnect.setOnClickListener {
            connectWebSocket()
        }

        binding.layoutConnectionBanner.setOnClickListener {
            connectWebSocket()
        }

        // 3. Clear Memory / Delete Chat Button
        binding.btnClearMemory.setOnClickListener {
            showClearMemoryDialog()
        }

        binding.badgeMemory.setOnClickListener {
            showClearMemoryDialog()
        }

        // 4. Listen Mode Quick Toggle (Tap badge to switch between Continuous and Push-to-Talk)
        binding.badgeListenMode.setOnClickListener {
            toggleListenMode()
        }

        // 5. Settings Button
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // 6. Push-To-Talk / Tap-to-Talk Mic Button
        var touchDownTime = 0L
        var isActionDown = false

        binding.btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    touchDownTime = System.currentTimeMillis()
                    isActionDown = true
                    false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val duration = System.currentTimeMillis() - touchDownTime
                    if (isActionDown && duration >= 450) {
                        isActionDown = false
                        if (captureManager?.isStreamingAudio?.get() == true) {
                            finishListeningAndProcess()
                        }
                        true
                    } else {
                        isActionDown = false
                        false
                    }
                }
                else -> false
            }
        }

        binding.btnMic.setOnClickListener {
            handleMicButtonClicked()
        }

        // 7. Interrupt Button
        binding.btnInterrupt.setOnClickListener {
            interruptSession()
        }
    }

    private fun showClearMemoryDialog() {
        AlertDialog.Builder(this)
            .setTitle("Nuova Conversazione / Cancella Memoria")
            .setMessage("Vuoi azzerare la memoria della conversazione e cancellare la cronologia dei messaggi?")
            .setPositiveButton("Azzera") { _, _ ->
                webSocket?.sendClearMemory()
                chatAdapter.clearMessages()
                prefs.clearConversationHistory()
                updateMemoryBadge()
                binding.layoutEmptyChat.visibility = View.VISIBLE
                Toast.makeText(this, "Memoria conversazione azzerata.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun updateMemoryBadge() {
        val turnCount = chatAdapter.getMessages().count { it.sender == "user" }
        binding.badgeMemory.text = "🧠 Memoria: $turnCount ${if (turnCount == 1) "turno" else "turni"}"
    }

    private fun toggleListenMode() {
        if (prefs.listenMode == AppPreferences.LISTEN_MODE_CONTINUOUS) {
            prefs.listenMode = AppPreferences.LISTEN_MODE_PUSH_TO_TALK
            captureManager?.isContinuousMode = false
            captureManager?.stopStreaming()
            binding.visualizerView.setState(VisualizerView.State.IDLE)
            binding.tvStateLabel.text = getString(R.string.status_ready)
            binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            Toast.makeText(this, "Modalità: Tocca per parlare (Push-to-Talk)", Toast.LENGTH_SHORT).show()
        } else {
            prefs.listenMode = AppPreferences.LISTEN_MODE_CONTINUOUS
            captureManager?.isContinuousMode = true
            captureManager?.startStreaming()
            binding.visualizerView.setState(VisualizerView.State.IDLE)
            binding.tvStateLabel.text = "Sempre in ascolto (parla liberamente)"
            binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.secondary))
            Toast.makeText(this, "Modalità: Sempre in ascolto (Smart Speaker)", Toast.LENGTH_SHORT).show()
        }
        updateBadges()
    }

    private fun updateBadges() {
        val listenLabel = if (prefs.listenMode == AppPreferences.LISTEN_MODE_CONTINUOUS) {
            "🎙️ Sempre in ascolto"
        } else {
            "👆 Tocca per parlare"
        }
        binding.badgeListenMode.text = listenLabel
        binding.badgeListenMode.setTextColor(
            if (prefs.listenMode == AppPreferences.LISTEN_MODE_CONTINUOUS)
                ContextCompat.getColor(this, R.color.secondary)
            else
                ContextCompat.getColor(this, R.color.text_muted)
        )

        val ttsLabel = if (prefs.ttsMode == AppPreferences.MODE_SERVER) {
            "🔊 TTS: Kokoro 24kHz"
        } else {
            "🔊 TTS: Device (Nativo)"
        }
        binding.badgeTtsMode.text = ttsLabel
        updateMemoryBadge()
    }

    private fun initAudioEngines() {
        // 1. Playback Manager (Server TTS)
        playbackManager = AudioPlaybackManager(
            sampleRate = 24000,
            onPlaybackStateChanged = { isPlaying ->
                runOnUiThread {
                    if (isPlaying && prefs.ttsMode == AppPreferences.MODE_SERVER) {
                        setAssistantSpeakingState(true)
                    } else if (!isPlaying && prefs.ttsMode == AppPreferences.MODE_SERVER) {
                        setAssistantSpeakingState(false)
                    }
                }
            }
        ).apply { start() }

        // 2. Native TTS (Device TTS)
        nativeTts = AndroidNativeTts(
            context = this,
            onPlaybackStarted = {
                runOnUiThread { setAssistantSpeakingState(true) }
            },
            onPlaybackFinished = {
                runOnUiThread { setAssistantSpeakingState(false) }
            }
        ).apply {
            setSpeechRate(prefs.speechRate)
            setPitch(prefs.pitch)
        }

        // 3. Wake Word Detector
        wakeWordDetector = WakeWordDetector {
            runOnUiThread {
                if (!isAssistantSpeaking && captureManager?.isStreamingAudio?.get() != true && prefs.isWakeWordEnabled && prefs.listenMode == AppPreferences.LISTEN_MODE_WAKE_WORD) {
                    startListeningSession(isWakeWordTriggered = true)
                }
            }
        }.apply {
            isEnabled.set(prefs.isWakeWordEnabled && prefs.listenMode == AppPreferences.LISTEN_MODE_WAKE_WORD)
        }

        // 4. Capture Manager (Mic)
        captureManager = AudioCaptureManager(
            sampleRate = 16000,
            onAudioChunkReady = { chunk ->
                if (!isAssistantSpeaking && captureManager?.isMuted?.get() != true) {
                    webSocket?.sendAudioChunk(chunk)
                }
            },
            onAmplitudeChanged = { amp ->
                runOnUiThread {
                    binding.visualizerView.setAmplitude(amp)

                    val progress = (amp * 100).toInt().coerceIn(0, 100)
                    binding.pbMicLevel.progress = progress

                    if (isAssistantSpeaking) {
                        binding.tvMicLevelLabel.text = "🔇 Mic in pausa (Alveare parla)"
                        binding.tvMicLevelLabel.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                    } else if (amp > 0.12f) {
                        binding.tvMicLevelLabel.text = "🎙️ Voce ($progress%)"
                        binding.tvMicLevelLabel.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.secondary))
                    } else {
                        binding.tvMicLevelLabel.text = "🎙️ Mic pronto"
                        binding.tvMicLevelLabel.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                    }
                }
                if (prefs.isWakeWordEnabled && prefs.listenMode == AppPreferences.LISTEN_MODE_WAKE_WORD) {
                    wakeWordDetector?.processAudioSample(amp)
                }
            },
            onSilenceDetected = {
                runOnUiThread {
                    if (captureManager?.isStreamingAudio?.get() == true && !isAssistantSpeaking && prefs.listenMode != AppPreferences.LISTEN_MODE_CONTINUOUS) {
                        finishListeningAndProcess()
                    }
                }
            }
        ).apply {
            isContinuousMode = (prefs.listenMode == AppPreferences.LISTEN_MODE_CONTINUOUS)
        }
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_REQ_RECORD_AUDIO
            )
        } else {
            startAudioAndConnect()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudioAndConnect()
            } else {
                Toast.makeText(this, "Permesso microfono necessario per il satellite Alveare", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAudioAndConnect() {
        captureManager?.start()
        connectWebSocket()
    }

    private fun connectWebSocket() {
        webSocket?.disconnect()
        updateConnectionStatus("Connessione...", Color.parseColor("#F59E0B"))

        webSocket = AlveareLiveWebSocket(
            serverUrl = prefs.serverUrl,
            trustSelfSignedSsl = prefs.trustSelfSignedSsl,
            roomName = prefs.roomName,
            listener = this
        ).apply {
            connect()
        }
    }

    private fun handleMicButtonClicked() {
        if (isAssistantSpeaking) {
            interruptSession()
            return
        }

        if (captureManager?.isStreamingAudio?.get() == true) {
            finishListeningAndProcess()
        } else {
            startListeningSession(isWakeWordTriggered = false)
        }
    }

    private fun startListeningSession(isWakeWordTriggered: Boolean) {
        captureManager?.muteFor(250)
        SoundEffects.playWakeChime()
        captureManager?.startStreaming()

        binding.visualizerView.setState(VisualizerView.State.LISTENING)
        binding.tvStateLabel.text = getString(R.string.status_listening)
        binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.secondary))
        binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.secondary)
        binding.btnInterrupt.visibility = View.VISIBLE
    }

    private fun finishListeningAndProcess() {
        captureManager?.stopStreaming()
        captureManager?.muteFor(300)
        webSocket?.sendEndOfSpeech()
        SoundEffects.playProcessingChime()

        binding.visualizerView.setState(VisualizerView.State.PROCESSING)
        binding.tvStateLabel.text = getString(R.string.status_processing)
        binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.primary))
        binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
    }

    private fun interruptSession(sendToServer: Boolean = true) {
        captureManager?.isMuted?.set(true)
        captureManager?.muteFor(500)
        playbackManager?.stopAndFlush()
        nativeTts?.stop()
        if (prefs.listenMode != AppPreferences.LISTEN_MODE_CONTINUOUS) {
            captureManager?.stopStreaming()
        }

        if (sendToServer) {
            webSocket?.sendInterrupt()
            SoundEffects.playInterruptChime()
        }

        chatAdapter.finalizeAssistant(null)

        setAssistantSpeakingState(false)
        binding.visualizerView.setState(VisualizerView.State.IDLE)
        binding.tvStateLabel.text = if (prefs.listenMode == AppPreferences.LISTEN_MODE_CONTINUOUS)
            "Sempre in ascolto (parla liberamente)"
        else
            getString(R.string.status_ready)
        binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.btnInterrupt.visibility = View.GONE
        binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
    }

    private fun setAssistantSpeakingState(speaking: Boolean) {
        isAssistantSpeaking = speaking
        chatAdapter.setAssistantPlaying(speaking)
        if (speaking) {
            captureManager?.isMuted?.set(true)
            binding.visualizerView.setState(VisualizerView.State.SPEAKING)
            binding.tvStateLabel.text = getString(R.string.status_speaking)
            binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_purple))
            binding.btnInterrupt.visibility = View.VISIBLE
        } else {
            captureManager?.muteFor(500)
            captureManager?.isMuted?.set(false)
            binding.visualizerView.setState(VisualizerView.State.IDLE)
            binding.tvStateLabel.text = if (prefs.listenMode == AppPreferences.LISTEN_MODE_CONTINUOUS)
                "Sempre in ascolto (parla liberamente)"
            else
                getString(R.string.status_ready)
            binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.btnInterrupt.visibility = View.GONE
            binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
        }
    }

    private fun updateConnectionStatus(text: String, color: Int) {
        runOnUiThread {
            binding.tvConnectionStatus.text = text
            binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    private fun scrollToBottom() {
        val count = chatAdapter.itemCount
        if (count > 0) {
            binding.rvChatHistory.smoothScrollToPosition(count - 1)
        }
    }

    // --- WebSocket Listener Callbacks ---

    override fun onConnecting() {
        runOnUiThread {
            updateConnectionStatus("Connessione...", Color.parseColor("#F59E0B"))
            binding.layoutConnectionBanner.visibility = View.VISIBLE
            binding.tvBannerTitle.text = "Connessione ad Alveare..."
            binding.tvBannerSubtitle.text = "Connessione in corso a ${prefs.serverUrl}..."
            binding.btnBannerConnect.isEnabled = false
            binding.btnBannerConnect.text = "..."
        }
    }

    override fun onConnected(sessionId: String?, sampleRate: Int) {
        runOnUiThread {
            updateConnectionStatus("Connesso", Color.parseColor("#10B981"))
            binding.layoutConnectionBanner.visibility = View.GONE
            binding.btnBannerConnect.isEnabled = true
            binding.btnBannerConnect.text = "Connetti"

            webSocket?.sendConfig(prefs.contextTurns)

            if (prefs.listenMode == AppPreferences.LISTEN_MODE_CONTINUOUS) {
                captureManager?.isContinuousMode = true
                captureManager?.startStreaming()
                binding.visualizerView.setState(VisualizerView.State.IDLE)
                binding.tvStateLabel.text = "Sempre in ascolto (parla liberamente)"
                binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.secondary))
            } else {
                captureManager?.isContinuousMode = false
                captureManager?.stopStreaming()
                binding.visualizerView.setState(VisualizerView.State.IDLE)
                binding.tvStateLabel.text = getString(R.string.status_ready)
                binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
            }
        }
    }

    override fun onDisconnected(reason: String) {
        runOnUiThread {
            updateConnectionStatus("Disconnesso", Color.parseColor("#EF4444"))
            binding.layoutConnectionBanner.visibility = View.VISIBLE
            binding.tvBannerTitle.text = "Server Alveare Disconnesso"
            binding.tvBannerSubtitle.text = reason
            binding.btnBannerConnect.isEnabled = true
            binding.btnBannerConnect.text = "Riconnetti"

            captureManager?.stopStreaming()
            binding.visualizerView.setState(VisualizerView.State.IDLE)
            binding.tvStateLabel.text = "Disconnesso da Alveare"
            binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
        }
    }

    override fun onVadSpeechStart() {
        runOnUiThread {
            if (!isAssistantSpeaking) {
                binding.visualizerView.setState(VisualizerView.State.LISTENING)
                binding.tvStateLabel.text = "In ascolto... (parla ora)"
                binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.secondary))
                binding.btnInterrupt.visibility = View.VISIBLE
            }
        }
    }

    override fun onVadSpeechEnd() {
        runOnUiThread {
            if (!isAssistantSpeaking) {
                captureManager?.isMuted?.set(true)
                captureManager?.muteFor(1000)
                SoundEffects.playProcessingChime()
                binding.visualizerView.setState(VisualizerView.State.PROCESSING)
                binding.tvStateLabel.text = "Sto elaborando..."
                binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.primary))
            }
        }
    }

    override fun onTtft(ttftMs: Float) {
        runOnUiThread {
            if (ttftMs > 0) {
                binding.badgeLatency.visibility = View.VISIBLE
                binding.badgeLatency.text = "⚡ TTFT: ${ttftMs.toInt()} ms"
                chatAdapter.updateAssistantMetrics(ttftMs = ttftMs)
                scrollToBottom()
            }
        }
    }

    override fun onTtfa(ttfaMs: Float) {
        runOnUiThread {
            if (ttfaMs > 0) {
                chatAdapter.updateAssistantMetrics(ttfaMs = ttfaMs)
                scrollToBottom()
            }
        }
    }

    override fun onUserTranscript(text: String, latencyMs: Float) {
        runOnUiThread {
            captureManager?.isMuted?.set(true)
            captureManager?.muteFor(1000)

            binding.layoutEmptyChat.visibility = View.GONE

            // 1. Add User Message to Chat History
            chatAdapter.addMessage(
                ChatMessage(
                    sender = "user",
                    text = text,
                    sttLatencyMs = latencyMs,
                    latencyMs = latencyMs
                )
            )

            // 2. Add Streaming Assistant Placeholder
            chatAdapter.addMessage(
                ChatMessage(
                    sender = "assistant",
                    text = "Sto pensando...",
                    isStreaming = true
                )
            )

            if (latencyMs > 0) {
                binding.badgeLatency.visibility = View.VISIBLE
                binding.badgeLatency.text = "⚡ STT: ${latencyMs.toInt()} ms"
            }

            updateMemoryBadge()
            scrollToBottom()
        }
    }

    override fun onAssistantDelta(delta: String) {
        runOnUiThread {
            binding.layoutEmptyChat.visibility = View.GONE
            chatAdapter.appendAssistantDelta(delta)
            scrollToBottom()
        }
    }

    override fun onAssistantSentence(sentence: String) {
        runOnUiThread {
            binding.layoutEmptyChat.visibility = View.GONE
            scrollToBottom()

            // If Device Native TTS is active, synthesize on client!
            if (prefs.ttsMode == AppPreferences.MODE_DEVICE) {
                nativeTts?.speak(sentence)
            }
        }
    }

    override fun onAudioChunkReceived(pcmBytes: ByteArray, sampleRate: Int) {
        // If Server TTS is active, play stream
        if (prefs.ttsMode == AppPreferences.MODE_SERVER) {
            playbackManager?.enqueueAudio(pcmBytes)
        }
    }

    override fun onToolCall(toolName: String, args: String?, summary: String?, status: String?) {
        runOnUiThread {
            binding.layoutEmptyChat.visibility = View.GONE
            chatAdapter.updateOrAddToolCall(toolName, args, summary, status)
            scrollToBottom()
        }
    }

    override fun onTurnCompleted(turnId: Int, fullText: String?) {
        runOnUiThread {
            chatAdapter.setAssistantPlaying(false)
            chatAdapter.finalizeAssistant(fullText)
            prefs.saveConversationHistory(chatAdapter.getMessages())
            updateMemoryBadge()
            scrollToBottom()

            binding.btnInterrupt.visibility = View.GONE
            if (!isAssistantSpeaking) {
                captureManager?.muteFor(500)
                captureManager?.isMuted?.set(false)
                if (prefs.listenMode == AppPreferences.LISTEN_MODE_CONTINUOUS) {
                    binding.visualizerView.setState(VisualizerView.State.IDLE)
                    binding.tvStateLabel.text = "Sempre in ascolto (parla liberamente)"
                    binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.secondary))
                } else {
                    binding.visualizerView.setState(VisualizerView.State.IDLE)
                    binding.tvStateLabel.text = getString(R.string.status_ready)
                    binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                    binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
                }
            }
        }
    }

    override fun onMemoryCleared(message: String) {
        runOnUiThread {
            chatAdapter.clearMessages()
            prefs.clearConversationHistory()
            updateMemoryBadge()
            binding.layoutEmptyChat.visibility = View.VISIBLE
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onInterrupted() {
        runOnUiThread {
            interruptSession(sendToServer = false)
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            binding.badgeLatency.visibility = View.VISIBLE
            binding.badgeLatency.text = "⚠️ $error"
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
            setAssistantSpeakingState(false)
        }
    }

    // --- Settings Dialog ---

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        val etRoomName = dialogView.findViewById<EditText>(R.id.etRoomName)
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val switchTrustSsl = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchTrustSsl)
        val btnTestConnection = dialogView.findViewById<MaterialButton>(R.id.btnTestConnection)
        val btnResetDefaultUrl = dialogView.findViewById<MaterialButton>(R.id.btnResetDefaultUrl)
        val tvTestResult = dialogView.findViewById<TextView>(R.id.tvTestResult)

        val rgTtsMode = dialogView.findViewById<RadioGroup>(R.id.rgTtsMode)
        val rbTtsServer = dialogView.findViewById<RadioButton>(R.id.rbTtsServer)
        val rbTtsDevice = dialogView.findViewById<RadioButton>(R.id.rbTtsDevice)
        val layoutDeviceTtsSettings = dialogView.findViewById<LinearLayout>(R.id.layoutDeviceTtsSettings)
        val seekSpeechRate = dialogView.findViewById<SeekBar>(R.id.seekSpeechRate)
        val tvSpeechRateLabel = dialogView.findViewById<TextView>(R.id.tvSpeechRateLabel)
        val seekPitch = dialogView.findViewById<SeekBar>(R.id.seekPitch)
        val tvPitchLabel = dialogView.findViewById<TextView>(R.id.tvPitchLabel)

        val rbListenContinuous = dialogView.findViewById<RadioButton>(R.id.rbListenContinuous)
        val rbListenPushToTalk = dialogView.findViewById<RadioButton>(R.id.rbListenPushToTalk)
        val switchWakeWord = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchWakeWord)
        val switchKeepScreenOn = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchKeepScreenOn)
        val btnClearMemoryNow = dialogView.findViewById<MaterialButton>(R.id.btnClearMemoryNow)
        val seekContextTurns = dialogView.findViewById<SeekBar>(R.id.seekContextTurns)
        val tvContextTurnsLabel = dialogView.findViewById<TextView>(R.id.tvContextTurnsLabel)

        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        // Populate current values
        etRoomName.setText(prefs.roomName)
        etServerUrl.setText(prefs.serverUrl)
        switchTrustSsl.isChecked = prefs.trustSelfSignedSsl

        // Test Connection Button
        btnTestConnection.setOnClickListener {
            val urlToCheck = etServerUrl.text.toString().trim()
            tvTestResult.visibility = View.VISIBLE
            tvTestResult.text = "Verifica connessione in corso..."
            tvTestResult.setTextColor(Color.parseColor("#F59E0B"))

            Thread {
                val result = testServerHttpReachable(urlToCheck, switchTrustSsl.isChecked)
                runOnUiThread {
                    if (result.first) {
                        tvTestResult.text = "✓ Connesso ad Alveare (${result.second})\n${result.third}"
                        tvTestResult.setTextColor(Color.parseColor("#10B981"))
                    } else {
                        tvTestResult.text = "✗ Connessione fallita: ${result.second}"
                        tvTestResult.setTextColor(Color.parseColor("#EF4444"))
                    }
                }
            }.start()
        }

        // Reset to default LAN PC IP with immediate diagnostic test
        btnResetDefaultUrl.setOnClickListener {
            etServerUrl.setText(AppPreferences.DEFAULT_SERVER_URL)
            btnTestConnection.performClick()
        }

        // Dedicated Clear Memory button in Settings
        btnClearMemoryNow.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Nuova Conversazione / Cancella Memoria")
                .setMessage("Vuoi azzerare la memoria della conversazione e cancellare la cronologia dei messaggi?")
                .setPositiveButton("Azzera") { _, _ ->
                    webSocket?.sendClearMemory()
                    chatAdapter.clearMessages()
                    prefs.clearConversationHistory()
                    updateMemoryBadge()
                    binding.layoutEmptyChat.visibility = View.VISIBLE
                    Toast.makeText(this, "Memoria conversazione azzerata.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }

        // TTS Mode
        if (prefs.ttsMode == AppPreferences.MODE_SERVER) {
            rbTtsServer.isChecked = true
            layoutDeviceTtsSettings.visibility = View.GONE
        } else {
            rbTtsDevice.isChecked = true
            layoutDeviceTtsSettings.visibility = View.VISIBLE
        }

        rgTtsMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbTtsDevice) {
                layoutDeviceTtsSettings.visibility = View.VISIBLE
            } else {
                layoutDeviceTtsSettings.visibility = View.GONE
            }
        }

        seekSpeechRate.progress = ((prefs.speechRate - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)
        tvSpeechRateLabel.text = "Velocità Voce: ${String.format("%.1fx", prefs.speechRate)}"
        seekSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + (progress / 100f) * 1.5f
                tvSpeechRateLabel.text = "Velocità Voce: ${String.format("%.1fx", rate)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekPitch.progress = ((prefs.pitch - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)
        tvPitchLabel.text = "Intonazione (Pitch): ${String.format("%.1fx", prefs.pitch)}"
        seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = 0.5f + (progress / 100f) * 1.5f
                tvPitchLabel.text = "Intonazione (Pitch): ${String.format("%.1fx", pitch)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Listen Mode
        if (prefs.listenMode == AppPreferences.LISTEN_MODE_CONTINUOUS) {
            rbListenContinuous.isChecked = true
        } else {
            rbListenPushToTalk.isChecked = true
        }

        switchWakeWord.isChecked = prefs.isWakeWordEnabled
        switchKeepScreenOn.isChecked = prefs.isSmartDisplayEnabled

        // Context Turns Slider (range 2 to 16)
        val initialTurns = prefs.contextTurns.coerceIn(2, 16)
        seekContextTurns.max = 14
        seekContextTurns.progress = initialTurns - 2
        tvContextTurnsLabel.text = "Memoria Contestuale: $initialTurns turni (da 2 a 16)"
        seekContextTurns.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val turns = progress + 2
                tvContextTurnsLabel.text = "Memoria Contestuale: $turns turni (da 2 a 16)"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSave.setOnClickListener {
            prefs.roomName = etRoomName.text.toString().trim()
            prefs.serverUrl = etServerUrl.text.toString().trim()
            prefs.trustSelfSignedSsl = switchTrustSsl.isChecked

            prefs.ttsMode = if (rbTtsServer.isChecked) AppPreferences.MODE_SERVER else AppPreferences.MODE_DEVICE
            prefs.speechRate = 0.5f + (seekSpeechRate.progress / 100f) * 1.5f
            prefs.pitch = 0.5f + (seekPitch.progress / 100f) * 1.5f
            nativeTts?.setSpeechRate(prefs.speechRate)
            nativeTts?.setPitch(prefs.pitch)

            prefs.listenMode = if (rbListenContinuous.isChecked) AppPreferences.LISTEN_MODE_CONTINUOUS else AppPreferences.LISTEN_MODE_PUSH_TO_TALK
            captureManager?.isContinuousMode = (prefs.listenMode == AppPreferences.LISTEN_MODE_CONTINUOUS)

            prefs.isWakeWordEnabled = switchWakeWord.isChecked
            wakeWordDetector?.isEnabled?.set(prefs.isWakeWordEnabled && prefs.listenMode == AppPreferences.LISTEN_MODE_WAKE_WORD)

            prefs.isSmartDisplayEnabled = switchKeepScreenOn.isChecked
            applySmartDisplayMode()

            val selectedTurns = (seekContextTurns.progress + 2).coerceIn(2, 16)
            prefs.contextTurns = selectedTurns
            webSocket?.sendConfig(selectedTurns)

            binding.tvRoomSubtitle.text = "${prefs.roomName} • Smart Satellite"
            updateBadges()

            connectWebSocket()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun testServerHttpReachable(wsUrl: String, trustSsl: Boolean): Triple<Boolean, String, String> {
        val httpUrl = wsUrl
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .replace("/ws/live", "/api/status")

        val startTime = System.currentTimeMillis()
        return try {
            val clientBuilder = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)

            if (trustSsl && httpUrl.startsWith("https://")) {
                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                clientBuilder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                clientBuilder.hostnameVerifier { _, _ -> true }
            }

            val request = okhttp3.Request.Builder().url(httpUrl).get().build()
            val response = clientBuilder.build().newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime

            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val modelsSummary = if (body.contains("\"slots\"")) {
                    "Modelli attivi rilevati (Gemma 4 / Kokoro / Whisper)"
                } else {
                    "Endpoint API attivo"
                }
                Triple(true, "${latency}ms", modelsSummary)
            } else {
                Triple(false, "HTTP ${response.code}", "")
            }
        } catch (e: Exception) {
            Triple(false, "${e.javaClass.simpleName}: ${e.message}", "")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocket?.disconnect()
        captureManager?.release()
        playbackManager?.stopAndFlush()
        nativeTts?.shutdown()
        wakeWordDetector?.reset()
    }
}
