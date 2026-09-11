package com.alveare.satellite

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.alveare.satellite.data.AppPreferences
import com.alveare.satellite.data.ChatMessage
import com.alveare.satellite.databinding.ActivityMainBinding
import com.alveare.satellite.network.AlveareLiveWebSocket
import com.alveare.satellite.service.AlveareSatelliteService
import com.alveare.satellite.state.SatelliteMode
import com.alveare.satellite.state.SatelliteState
import com.alveare.satellite.ui.ChatAdapter
import com.alveare.satellite.ui.VisualizerView
import com.alveare.satellite.wakeword.VoskModelManager
import com.alveare.satellite.wakeword.VoskModelStatus
import com.google.android.material.button.MaterialButton
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity(), AlveareSatelliteService.ServiceListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    // Bound Foreground Service
    private var satelliteService: AlveareSatelliteService? = null
    private var isBound = false

    // Real-Time Chat Conversation History
    private lateinit var chatAdapter: ChatAdapter

    companion object {
        private const val PERMISSION_REQ_CODE = 101
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AlveareSatelliteService.LocalBinder
            satelliteService = localBinder?.getService()
            isBound = true
            satelliteService?.setServiceListener(this@MainActivity)
            satelliteService?.stateMachine?.let { sm ->
                updateUiForState(sm.currentState)
                updateModeSelector(sm.mode)
            }
            updateMuteButtonUi(satelliteService?.isPrivacyMuted ?: false)
            satelliteService?.webSocket?.let { ws ->
                val connected = ws.isConnected.get()
                updateConnectionStatus(
                    if (connected) getString(R.string.status_connected) else getString(R.string.status_disconnected),
                    connected
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            satelliteService?.setServiceListener(null)
            satelliteService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        applySmartDisplayMode()

        initUi()
        checkPermissionsAndStartService()
    }

    private fun applySmartDisplayMode() {
        if (prefs.isSmartDisplayEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun initUi() {
        binding.tvRoomSubtitle.text = "${prefs.roomName} • ${getString(R.string.app_subtitle)}"

        // 1. Setup Chat RecyclerView with bounded smooth scroll
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

        // 2. Mode Selector (Assistente vs Live)
        val currentMode = if (prefs.satelliteMode == AppPreferences.SATELLITE_MODE_LIVE) {
            SatelliteMode.LIVE
        } else {
            SatelliteMode.ASSISTANT
        }
        updateModeSelector(currentMode)

        binding.btnModeAssistant.setOnClickListener {
            switchMode(SatelliteMode.ASSISTANT)
        }

        binding.btnModeLive.setOnClickListener {
            switchMode(SatelliteMode.LIVE)
        }

        // 3. Connection Pill & Banner (Click to reconnect)
        binding.statusPill.setOnClickListener {
            reconnectServer()
        }

        binding.btnBannerConnect.setOnClickListener {
            reconnectServer()
        }

        binding.layoutConnectionBanner.setOnClickListener {
            reconnectServer()
        }

        // 4. Privacy Mute Buttons (Header and Bottom)
        binding.btnQuickMute.setOnClickListener {
            togglePrivacyMute()
        }

        binding.btnBottomMute.setOnClickListener {
            togglePrivacyMute()
        }

        // 5. Clear Memory / Delete Chat Button
        binding.btnClearMemory.setOnClickListener {
            showClearMemoryDialog()
        }

        binding.badgeMemory.setOnClickListener {
            showClearMemoryDialog()
        }

        // 6. Settings Button
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // 7. Mic Button (Tap-to-talk & Push-to-talk fallback)
        binding.btnMic.setOnClickListener {
            satelliteService?.handleTapToTalk()
        }

        // 8. Interrupt Button
        binding.btnInterrupt.setOnClickListener {
            satelliteService?.interruptSession()
        }
    }

    private fun updateModeSelector(mode: SatelliteMode) {
        if (mode == SatelliteMode.LIVE) {
            binding.btnModeLive.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.btnModeLive.setTextColor(ContextCompat.getColor(this, R.color.on_primary))

            binding.btnModeAssistant.setBackgroundColor(Color.TRANSPARENT)
            binding.btnModeAssistant.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        } else {
            binding.btnModeAssistant.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.btnModeAssistant.setTextColor(ContextCompat.getColor(this, R.color.on_primary))

            binding.btnModeLive.setBackgroundColor(Color.TRANSPARENT)
            binding.btnModeLive.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
        }
    }

    private fun switchMode(newMode: SatelliteMode) {
        prefs.satelliteMode = if (newMode == SatelliteMode.LIVE) {
            AppPreferences.SATELLITE_MODE_LIVE
        } else {
            AppPreferences.SATELLITE_MODE_ASSISTANT
        }
        updateModeSelector(newMode)
        satelliteService?.setMode(newMode)
        updateBadges()

        val toastText = if (newMode == SatelliteMode.LIVE) {
            "Modalità: Live (Conversazione continua con interruzione)"
        } else {
            "Modalità: Assistente (Turno singolo • Attesa vocale)"
        }
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
    }

    private fun togglePrivacyMute() {
        satelliteService?.togglePrivacyMute()
        val isMuted = satelliteService?.isPrivacyMuted ?: false
        updateMuteButtonUi(isMuted)

        val msg = if (isMuted) {
            "Microfono disattivato (Privacy attiva • Nessun audio catturato)"
        } else {
            "Microfono riattivato"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun updateMuteButtonUi(isMuted: Boolean) {
        val iconRes = if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic
        val tintColor = if (isMuted) {
            ContextCompat.getColor(this, R.color.accent_red)
        } else {
            ContextCompat.getColor(this, R.color.text_secondary)
        }

        binding.btnQuickMute.setImageResource(iconRes)
        binding.btnQuickMute.imageTintList = ColorStateList.valueOf(tintColor)

        binding.btnBottomMute.setIconResource(iconRes)
        binding.btnBottomMute.setIconTint(ColorStateList.valueOf(tintColor))
        binding.btnBottomMute.strokeColor = ColorStateList.valueOf(
            if (isMuted) ContextCompat.getColor(this, R.color.accent_red)
            else ContextCompat.getColor(this, R.color.surface_card_stroke)
        )
    }

    private fun reconnectServer() {
        satelliteService?.connectWebSocket()
    }

    private fun showClearMemoryDialog() {
        val isConnected = satelliteService?.webSocket?.isConnected?.get() == true
        if (isConnected) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.action_clear_memory))
                .setMessage("Vuoi azzerare la memoria della conversazione sul server e cancellare la cronologia locale?")
                .setPositiveButton("Azzera") { _, _ ->
                    satelliteService?.webSocket?.sendClearMemory()
                    Toast.makeText(this, "Richiesta azzeramento memoria inviata al server...", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Cancellare Cronologia Locale?")
                .setMessage("Il server non è connesso. Vuoi cancellare la cronologia locale del dispositivo? (La memoria sul server non verrà modificata).")
                .setPositiveButton("Cancella solo locale") { _, _ ->
                    chatAdapter.clearMessages()
                    prefs.clearConversationHistory()
                    updateMemoryBadge()
                    binding.layoutEmptyChat.visibility = View.VISIBLE
                    Toast.makeText(this, "Cronologia locale azzerata (solo locale)", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show()
        }
    }

    private fun updateMemoryBadge() {
        val turnCount = chatAdapter.getMessages().count { it.sender == "user" }
        binding.badgeMemory.text = "🧠 Memoria: $turnCount ${if (turnCount == 1) "turno" else "turni"}"
    }

    private fun updateBadges() {
        val ttsLabel = if (prefs.ttsMode == AppPreferences.MODE_SERVER) {
            "🔊 TTS: Kokoro 24kHz"
        } else {
            "🔊 TTS: Locale Android"
        }
        binding.badgeTtsMode.text = ttsLabel
        updateMemoryBadge()
    }

    private fun checkPermissionsAndStartService() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQ_CODE)
        } else {
            startAndBindService()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ_CODE) {
            var micGranted = false
            for (i in permissions.indices) {
                if (permissions[i] == Manifest.permission.RECORD_AUDIO && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    micGranted = true
                }
            }
            if (micGranted) {
                startAndBindService()
            } else {
                Toast.makeText(this, "Il microfono è necessario per il funzionamento del satellite Alveare", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, AlveareSatelliteService::class.java).apply {
            action = AlveareSatelliteService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun conditionalScrollToBottom() {
        val lm = binding.rvChatHistory.layoutManager as? LinearLayoutManager
        val isAtBottom = if (lm != null) {
            val last = lm.findLastVisibleItemPosition()
            val total = chatAdapter.itemCount
            total == 0 || last >= total - 2
        } else true

        if (isAtBottom && chatAdapter.itemCount > 0) {
            binding.rvChatHistory.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    // --- ServiceListener Callbacks ---

    override fun onStateChanged(state: SatelliteState) {
        runOnUiThread {
            updateUiForState(state)
        }
    }

    private fun updateUiForState(state: SatelliteState) {
        val isMuted = satelliteService?.isPrivacyMuted ?: false
        updateMuteButtonUi(isMuted)

        when (state) {
            SatelliteState.MUTED -> {
                binding.visualizerView.setState(VisualizerView.State.IDLE)
                binding.tvStateLabel.text = getString(R.string.status_privacy_muted)
                binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                binding.btnInterrupt.visibility = View.INVISIBLE
                binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.text_muted)
            }
            SatelliteState.IDLE_WAITING_ACTIVATION -> {
                binding.visualizerView.setState(VisualizerView.State.IDLE)
                val label = if (prefs.isWakeWordEnabled) {
                    if (VoskModelManager.isModelInstalled(this)) {
                        getString(R.string.status_ready_assistant_wake)
                    } else {
                        "Pronto (Tocca il microfono • Wake Word non installata)"
                    }
                } else {
                    getString(R.string.status_ready_assistant_tap)
                }
                binding.tvStateLabel.text = label
                binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                binding.btnInterrupt.visibility = View.INVISIBLE
                binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
            }
            SatelliteState.LIVE_IDLE_STREAMING -> {
                binding.visualizerView.setState(VisualizerView.State.IDLE)
                binding.tvStateLabel.text = getString(R.string.status_live_streaming)
                binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.secondary))
                binding.btnInterrupt.visibility = View.INVISIBLE
                binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.secondary)
            }
            SatelliteState.LISTENING -> {
                binding.visualizerView.setState(VisualizerView.State.LISTENING)
                binding.tvStateLabel.text = getString(R.string.status_listening)
                binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.secondary))
                binding.btnInterrupt.visibility = View.VISIBLE
                binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.secondary)
            }
            SatelliteState.PROCESSING -> {
                binding.visualizerView.setState(VisualizerView.State.PROCESSING)
                binding.tvStateLabel.text = getString(R.string.status_processing)
                binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.primary))
                binding.btnInterrupt.visibility = View.VISIBLE
                binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
            }
            SatelliteState.SPEAKING -> {
                binding.visualizerView.setState(VisualizerView.State.SPEAKING)
                binding.tvStateLabel.text = getString(R.string.status_speaking)
                binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_purple))
                binding.btnInterrupt.visibility = View.VISIBLE
                binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
            }
        }
    }

    override fun onAmplitudeChanged(amplitude: Float) {
        runOnUiThread {
            binding.visualizerView.setAmplitude(amplitude)
            val progress = (amplitude * 100).toInt().coerceIn(0, 100)
            binding.pbMicLevel.progress = progress

            val isSpeaking = satelliteService?.stateMachine?.currentState == SatelliteState.SPEAKING
            val isMuted = satelliteService?.isPrivacyMuted ?: false

            if (isMuted) {
                binding.tvMicLevelLabel.text = "🔇 Muto"
                binding.tvMicLevelLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            } else if (isSpeaking) {
                binding.tvMicLevelLabel.text = "🔇 Mic in pausa (Alveare parla)"
                binding.tvMicLevelLabel.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            } else if (amplitude > 0.12f) {
                binding.tvMicLevelLabel.text = "🎙️ Voce ($progress%)"
                binding.tvMicLevelLabel.setTextColor(ContextCompat.getColor(this, R.color.secondary))
            } else {
                binding.tvMicLevelLabel.text = "🎙️ Mic pronto"
                binding.tvMicLevelLabel.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            }
        }
    }

    override fun onConnectionStatusChanged(status: String, isConnected: Boolean) {
        runOnUiThread {
            updateConnectionStatus(status, isConnected)
        }
    }

    private fun updateConnectionStatus(status: String, isConnected: Boolean) {
        binding.tvConnectionStatus.text = if (isConnected) "Connesso" else "Disconnesso"
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(
            if (isConnected) Color.parseColor("#10B981") else Color.parseColor("#EF4444")
        )

        if (isConnected) {
            binding.layoutConnectionBanner.visibility = View.GONE
        } else {
            binding.layoutConnectionBanner.visibility = View.VISIBLE
            binding.tvBannerTitle.text = getString(R.string.status_disconnected)
            binding.tvBannerSubtitle.text = status
        }
    }

    override fun onUserTranscript(text: String, latencyMs: Float) {
        runOnUiThread {
            binding.layoutEmptyChat.visibility = View.GONE
            chatAdapter.addMessage(
                ChatMessage(
                    sender = "user",
                    text = text,
                    sttLatencyMs = latencyMs,
                    latencyMs = latencyMs
                )
            )
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
            conditionalScrollToBottom()
        }
    }

    override fun onAssistantDelta(delta: String) {
        runOnUiThread {
            binding.layoutEmptyChat.visibility = View.GONE
            chatAdapter.appendAssistantDelta(delta)
            conditionalScrollToBottom()
        }
    }

    override fun onAssistantSentence(sentence: String) {
        runOnUiThread {
            binding.layoutEmptyChat.visibility = View.GONE
            conditionalScrollToBottom()
        }
    }

    override fun onToolCall(toolName: String, args: String?, summary: String?, status: String?) {
        runOnUiThread {
            binding.layoutEmptyChat.visibility = View.GONE
            chatAdapter.updateOrAddToolCall(toolName, args, summary, status)
            conditionalScrollToBottom()
        }
    }

    override fun onTurnCompleted(turnId: Int, fullText: String?) {
        runOnUiThread {
            chatAdapter.setAssistantPlaying(false)
            chatAdapter.finalizeAssistant(fullText)
            prefs.saveConversationHistory(chatAdapter.getMessages())
            updateMemoryBadge()
            conditionalScrollToBottom()
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

    override fun onError(error: String) {
        runOnUiThread {
            binding.badgeLatency.visibility = View.VISIBLE
            binding.badgeLatency.text = "⚠️ $error"
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
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
        val tvTlsWarning = dialogView.findViewById<TextView>(R.id.tvTlsWarning)
        val btnTestConnection = dialogView.findViewById<MaterialButton>(R.id.btnTestConnection)
        val btnResetDefaultUrl = dialogView.findViewById<MaterialButton>(R.id.btnResetDefaultUrl)
        val tvTestResult = dialogView.findViewById<TextView>(R.id.tvTestResult)

        val rbListenAssistant = dialogView.findViewById<RadioButton>(R.id.rbListenAssistant)
        val rbListenLive = dialogView.findViewById<RadioButton>(R.id.rbListenLive)

        val switchWakeWord = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchWakeWord)
        val tvVoskModelStatus = dialogView.findViewById<TextView>(R.id.tvVoskModelStatus)
        val pbVoskDownload = dialogView.findViewById<ProgressBar>(R.id.pbVoskDownload)
        val btnDownloadVoskModel = dialogView.findViewById<MaterialButton>(R.id.btnDownloadVoskModel)
        val btnCancelVoskDownload = dialogView.findViewById<MaterialButton>(R.id.btnCancelVoskDownload)
        val btnDeleteVoskModel = dialogView.findViewById<MaterialButton>(R.id.btnDeleteVoskModel)

        val rgTtsMode = dialogView.findViewById<RadioGroup>(R.id.rgTtsMode)
        val rbTtsServer = dialogView.findViewById<RadioButton>(R.id.rbTtsServer)
        val rbTtsDevice = dialogView.findViewById<RadioButton>(R.id.rbTtsDevice)
        val layoutDeviceTtsSettings = dialogView.findViewById<LinearLayout>(R.id.layoutDeviceTtsSettings)
        val seekSpeechRate = dialogView.findViewById<SeekBar>(R.id.seekSpeechRate)
        val tvSpeechRateLabel = dialogView.findViewById<TextView>(R.id.tvSpeechRateLabel)
        val seekPitch = dialogView.findViewById<SeekBar>(R.id.seekPitch)
        val tvPitchLabel = dialogView.findViewById<TextView>(R.id.tvPitchLabel)

        val switchKeepScreenOn = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchKeepScreenOn)
        val seekContextTurns = dialogView.findViewById<SeekBar>(R.id.seekContextTurns)
        val tvContextTurnsLabel = dialogView.findViewById<TextView>(R.id.tvContextTurnsLabel)
        val btnClearMemoryNow = dialogView.findViewById<MaterialButton>(R.id.btnClearMemoryNow)

        val btnSave = dialogView.findViewById<MaterialButton>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancel)

        // Initial values
        etRoomName.setText(prefs.roomName)
        etServerUrl.setText(prefs.serverUrl)
        switchTrustSsl.isChecked = prefs.trustSelfSignedSsl
        tvTlsWarning.visibility = if (prefs.trustSelfSignedSsl) View.VISIBLE else View.GONE

        switchTrustSsl.setOnCheckedChangeListener { _, isChecked ->
            tvTlsWarning.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Test Connection
        btnTestConnection.setOnClickListener {
            val urlToCheck = etServerUrl.text.toString().trim()
            tvTestResult.visibility = View.VISIBLE
            tvTestResult.text = "Verifica endpoint in corso..."
            tvTestResult.setTextColor(Color.parseColor("#F59E0B"))

            Thread {
                val res = testServerDiagnostic(urlToCheck, switchTrustSsl.isChecked)
                runOnUiThread {
                    if (res.first) {
                        tvTestResult.text = "✓ Connessione riuscita (${res.second})\n${res.third}"
                        tvTestResult.setTextColor(Color.parseColor("#10B981"))
                    } else {
                        tvTestResult.text = "✗ Fallito: ${res.second}"
                        tvTestResult.setTextColor(Color.parseColor("#EF4444"))
                    }
                }
            }.start()
        }

        btnResetDefaultUrl.setOnClickListener {
            etServerUrl.setText(AppPreferences.DEFAULT_SERVER_URL)
            btnTestConnection.performClick()
        }

        // Operational Mode
        if (prefs.satelliteMode == AppPreferences.SATELLITE_MODE_LIVE) {
            rbListenLive.isChecked = true
        } else {
            rbListenAssistant.isChecked = true
        }

        // Offline Vosk Model status
        fun refreshVoskUi() {
            val isInstalled = VoskModelManager.isModelInstalled(this)
            if (isInstalled) {
                tvVoskModelStatus.text = getString(R.string.settings_wake_word_ready)
                tvVoskModelStatus.setTextColor(ContextCompat.getColor(this, R.color.secondary))
                btnDownloadVoskModel.visibility = View.GONE
                btnCancelVoskDownload.visibility = View.GONE
                btnDeleteVoskModel.visibility = View.VISIBLE
            } else {
                tvVoskModelStatus.text = getString(R.string.settings_wake_word_missing)
                tvVoskModelStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btnDownloadVoskModel.visibility = View.VISIBLE
                btnCancelVoskDownload.visibility = View.GONE
                btnDeleteVoskModel.visibility = View.GONE
            }
        }
        refreshVoskUi()

        switchWakeWord.isChecked = prefs.isWakeWordEnabled
        switchWakeWord.setOnCheckedChangeListener { _, isChecked ->
            prefs.isWakeWordEnabled = isChecked
            satelliteService?.setWakeWordEnabled(isChecked)
        }

        btnDownloadVoskModel.setOnClickListener {
            btnDownloadVoskModel.visibility = View.GONE
            btnCancelVoskDownload.visibility = View.VISIBLE
            pbVoskDownload.visibility = View.VISIBLE
            pbVoskDownload.progress = 0
            tvVoskModelStatus.text = "Download modello in corso..."

            VoskModelManager.downloadAndInstall(
                context = this,
                onProgress = { pct ->
                    runOnUiThread {
                        pbVoskDownload.progress = pct
                        tvVoskModelStatus.text = "Download modello Vosk: $pct%"
                    }
                },
                onStatusChanged = { status ->
                    runOnUiThread {
                        when (status) {
                            is VoskModelStatus.Unpacking -> {
                                tvVoskModelStatus.text = "Estrazione archivio modello compatto in corso..."
                            }
                            is VoskModelStatus.Ready -> {
                                pbVoskDownload.visibility = View.GONE
                                refreshVoskUi()
                                satelliteService?.reloadWakeWordModel()
                                Toast.makeText(this, "Modello 'Ehi Alveare' installato con successo!", Toast.LENGTH_SHORT).show()
                            }
                            is VoskModelStatus.Error -> {
                                pbVoskDownload.visibility = View.GONE
                                refreshVoskUi()
                                tvVoskModelStatus.text = "Errore download: ${status.message}"
                                tvVoskModelStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                            }
                            else -> {}
                        }
                    }
                },
                onComplete = {
                    runOnUiThread {
                        refreshVoskUi()
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        pbVoskDownload.visibility = View.GONE
                        refreshVoskUi()
                        Toast.makeText(this, "Errore download: $err", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        btnCancelVoskDownload.setOnClickListener {
            VoskModelManager.cancelDownload()
            pbVoskDownload.visibility = View.GONE
            refreshVoskUi()
            Toast.makeText(this, "Download annullato", Toast.LENGTH_SHORT).show()
        }

        btnDeleteVoskModel.setOnClickListener {
            VoskModelManager.deleteModel(this)
            refreshVoskUi()
            satelliteService?.reloadWakeWordModel()
            Toast.makeText(this, "Modello rimosso", Toast.LENGTH_SHORT).show()
        }

        // TTS Settings
        if (prefs.ttsMode == AppPreferences.MODE_SERVER) {
            rbTtsServer.isChecked = true
            layoutDeviceTtsSettings.visibility = View.GONE
        } else {
            rbTtsDevice.isChecked = true
            layoutDeviceTtsSettings.visibility = View.VISIBLE
        }

        rgTtsMode.setOnCheckedChangeListener { _, checkedId ->
            layoutDeviceTtsSettings.visibility = if (checkedId == R.id.rbTtsDevice) View.VISIBLE else View.GONE
        }

        seekSpeechRate.progress = ((prefs.speechRate - 0.5f) / 1.5f * 20).toInt().coerceIn(0, 20)
        tvSpeechRateLabel.text = "Velocità Voce: ${String.format("%.1fx", prefs.speechRate)}"
        seekSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + (progress / 20f) * 1.5f
                tvSpeechRateLabel.text = "Velocità Voce: ${String.format("%.1fx", rate)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekPitch.progress = ((prefs.pitch - 0.5f) / 1.5f * 20).toInt().coerceIn(0, 20)
        tvPitchLabel.text = "Intonazione Voce: ${String.format("%.1fx", prefs.pitch)}"
        seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = 0.5f + (progress / 20f) * 1.5f
                tvPitchLabel.text = "Intonazione Voce: ${String.format("%.1fx", pitch)}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Smart Display
        switchKeepScreenOn.isChecked = prefs.isSmartDisplayEnabled

        // Context Memory Slider
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

        btnClearMemoryNow.setOnClickListener {
            showClearMemoryDialog()
        }

        // Save Button
        btnSave.setOnClickListener {
            prefs.roomName = etRoomName.text.toString().trim()
            prefs.serverUrl = etServerUrl.text.toString().trim()
            prefs.trustSelfSignedSsl = switchTrustSsl.isChecked

            val newMode = if (rbListenLive.isChecked) SatelliteMode.LIVE else SatelliteMode.ASSISTANT
            switchMode(newMode)

            val wakeEnabled = switchWakeWord.isChecked
            prefs.isWakeWordEnabled = wakeEnabled
            satelliteService?.setWakeWordEnabled(wakeEnabled)

            prefs.ttsMode = if (rbTtsServer.isChecked) AppPreferences.MODE_SERVER else AppPreferences.MODE_DEVICE
            prefs.speechRate = 0.5f + (seekSpeechRate.progress / 20f) * 1.5f
            prefs.pitch = 0.5f + (seekPitch.progress / 20f) * 1.5f
            satelliteService?.nativeTts?.setSpeechRate(prefs.speechRate)
            satelliteService?.nativeTts?.setPitch(prefs.pitch)

            prefs.isSmartDisplayEnabled = switchKeepScreenOn.isChecked
            applySmartDisplayMode()

            val selectedTurns = (seekContextTurns.progress + 2).coerceIn(2, 16)
            prefs.contextTurns = selectedTurns
            satelliteService?.updateConfig(selectedTurns, prefs.ttsMode)

            binding.tvRoomSubtitle.text = "${prefs.roomName} • ${getString(R.string.app_subtitle)}"
            updateBadges()

            satelliteService?.reloadWakeWordModel()
            reconnectServer()
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun testServerDiagnostic(wsUrl: String, trustSsl: Boolean): Triple<Boolean, String, String> {
        val httpUrl = AppPreferences.getBaseHttpUrl(wsUrl) + "/api/status"
        val t0 = System.currentTimeMillis()

        return try {
            val builder = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)

            if (trustSsl && httpUrl.startsWith("https://")) {
                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                })
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            }

            val client = builder.build()
            val req = Request.Builder().url(httpUrl).get().build()
            val resp = client.newCall(req).execute()
            val latency = System.currentTimeMillis() - t0

            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: "{}"
                val json = com.google.gson.JsonParser.parseString(body).asJsonObject
                val llm = json.get("model")?.asString ?: "LLM Pronto"
                val slots = json.getAsJsonObject("slots")
                val tts = slots?.getAsJsonObject("tts")?.get("model")?.asString ?: "Kokoro-82M"
                val stt = slots?.getAsJsonObject("stt")?.get("model")?.asString ?: "Whisper"
                Triple(true, "${latency}ms", "• LLM: $llm\n• STT: $stt\n• TTS: $tts")
            } else {
                Triple(false, "HTTP ${resp.code} ${resp.message}", "")
            }
        } catch (e: Exception) {
            Triple(false, e.localizedMessage ?: e.message ?: "Connessione fallita", "")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            satelliteService?.setServiceListener(null)
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
