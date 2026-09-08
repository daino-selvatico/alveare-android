package com.alveare.satellite

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.alveare.satellite.audio.AudioCaptureManager
import com.alveare.satellite.audio.AudioPlaybackManager
import com.alveare.satellite.audio.SoundEffects
import com.alveare.satellite.data.AppPreferences
import com.alveare.satellite.databinding.ActivityMainBinding
import com.alveare.satellite.network.AlveareLiveWebSocket
import com.alveare.satellite.tts.AndroidNativeTts
import com.alveare.satellite.ui.VisualizerView
import com.alveare.satellite.wakeword.WakeWordDetector

class MainActivity : AppCompatActivity(), AlveareLiveWebSocket.LiveWebSocketListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences

    // Audio & Network Engines
    private var playbackManager: AudioPlaybackManager? = null
    private var captureManager: AudioCaptureManager? = null
    private var nativeTts: AndroidNativeTts? = null
    private var wakeWordDetector: WakeWordDetector? = null
    private var webSocket: AlveareLiveWebSocket? = null

    private var isAssistantSpeaking = false
    private var currentAssistantText = StringBuilder()

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

        updateBadges()

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnMic.setOnClickListener {
            handleMicButtonClicked()
        }

        binding.btnInterrupt.setOnClickListener {
            interruptSession()
        }
    }

    private fun updateBadges() {
        val ttsLabel = if (prefs.ttsMode == AppPreferences.MODE_SERVER) {
            "🔊 TTS: Server (Kokoro)"
        } else {
            "🔊 TTS: Device (Nativo)"
        }
        binding.badgeTtsMode.text = ttsLabel

        val wakeLabel = if (prefs.isWakeWordEnabled) {
            "👂 Wake Word: Ehi Alveare"
        } else {
            "👂 Wake Word: Spenta"
        }
        binding.badgeWakeWord.text = wakeLabel
        binding.badgeWakeWord.setTextColor(
            if (prefs.isWakeWordEnabled) ContextCompat.getColor(this, R.color.secondary)
            else ContextCompat.getColor(this, R.color.text_muted)
        )
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
                if (!isAssistantSpeaking && captureManager?.isStreamingAudio?.get() != true) {
                    startListeningSession(isWakeWordTriggered = true)
                }
            }
        }.apply {
            isEnabled.set(prefs.isWakeWordEnabled)
        }

        // 4. Capture Manager (Mic)
        captureManager = AudioCaptureManager(
            sampleRate = 16000,
            onAudioChunkReady = { chunk ->
                webSocket?.sendAudioChunk(chunk)
            },
            onAmplitudeChanged = { amp ->
                runOnUiThread {
                    binding.visualizerView.setAmplitude(amp)
                }
                wakeWordDetector?.processAudioSample(amp)
            },
            onSilenceDetected = {
                runOnUiThread {
                    if (captureManager?.isStreamingAudio?.get() == true) {
                        finishListeningAndProcess()
                    }
                }
            }
        )
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
        SoundEffects.playWakeChime()
        captureManager?.startStreaming()

        binding.visualizerView.setState(VisualizerView.State.LISTENING)
        binding.tvStateLabel.text = getString(R.string.status_listening)
        binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.secondary))
        binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.secondary)
        binding.btnInterrupt.visibility = View.VISIBLE

        // Reset conversation cards for new turn
        currentAssistantText.clear()
        binding.cardTool.visibility = View.GONE
    }

    private fun finishListeningAndProcess() {
        captureManager?.stopStreaming()
        SoundEffects.playProcessingChime()

        binding.visualizerView.setState(VisualizerView.State.PROCESSING)
        binding.tvStateLabel.text = getString(R.string.status_processing)
        binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.primary))
        binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
    }

    private fun interruptSession() {
        SoundEffects.playInterruptChime()
        webSocket?.sendInterrupt()

        playbackManager?.stopAndFlush()
        nativeTts?.stop()
        captureManager?.stopStreaming()

        setAssistantSpeakingState(false)
        binding.visualizerView.setState(VisualizerView.State.IDLE)
        binding.tvStateLabel.text = getString(R.string.status_ready)
        binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.btnInterrupt.visibility = View.GONE
        binding.btnMic.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)
    }

    private fun setAssistantSpeakingState(speaking: Boolean) {
        isAssistantSpeaking = speaking
        if (speaking) {
            binding.visualizerView.setState(VisualizerView.State.SPEAKING)
            binding.tvStateLabel.text = getString(R.string.status_speaking)
            binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_purple))
            binding.btnInterrupt.visibility = View.VISIBLE
        } else {
            binding.visualizerView.setState(VisualizerView.State.IDLE)
            binding.tvStateLabel.text = getString(R.string.status_ready)
            binding.tvStateLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.btnInterrupt.visibility = View.GONE
        }
    }

    private fun updateConnectionStatus(text: String, color: Int) {
        runOnUiThread {
            binding.tvConnectionStatus.text = text
            binding.statusDot.backgroundTintList = ContextCompat.getColorStateList(this, R.color.secondary)
            binding.statusDot.setBackgroundColor(color)
        }
    }

    // --- WebSocket Listener Callbacks ---

    override fun onConnected(sessionId: String?, sampleRate: Int) {
        updateConnectionStatus("Connesso", Color.parseColor("#10B981"))
        playbackManager?.setSampleRate(sampleRate)
        webSocket?.sendConfig(prefs.contextTurns)
    }

    override fun onDisconnected(reason: String) {
        updateConnectionStatus("Disconnesso", Color.parseColor("#EF4444"))
    }

    override fun onUserTranscript(text: String, latencyMs: Float) {
        runOnUiThread {
            binding.cardUser.visibility = View.VISIBLE
            binding.tvUserText.text = text
            if (latencyMs > 0) {
                binding.badgeLatency.visibility = View.VISIBLE
                binding.badgeLatency.text = "⚡ STT: ${latencyMs.toInt()} ms"
            }
        }
    }

    override fun onAssistantDelta(delta: String) {
        runOnUiThread {
            currentAssistantText.append(delta)
            binding.cardAssistant.visibility = View.VISIBLE
            binding.tvAssistantText.text = currentAssistantText.toString()
        }
    }

    override fun onAssistantSentence(sentence: String) {
        runOnUiThread {
            binding.cardAssistant.visibility = View.VISIBLE
            binding.tvAssistantText.text = currentAssistantText.toString()

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

    override fun onToolCall(toolName: String) {
        runOnUiThread {
            binding.cardTool.visibility = View.VISIBLE
            binding.tvToolText.text = "⚡ Esecuzione: $toolName"
        }
    }

    override fun onTurnCompleted(turnId: Int) {
        runOnUiThread {
            binding.btnInterrupt.visibility = View.GONE
        }
    }

    override fun onInterrupted() {
        runOnUiThread {
            interruptSession()
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
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
        val rgTtsMode = dialogView.findViewById<RadioGroup>(R.id.rgTtsMode)
        val rbTtsServer = dialogView.findViewById<RadioButton>(R.id.rbTtsServer)
        val rbTtsDevice = dialogView.findViewById<RadioButton>(R.id.rbTtsDevice)
        val layoutDeviceTtsSettings = dialogView.findViewById<LinearLayout>(R.id.layoutDeviceTtsSettings)
        val seekSpeechRate = dialogView.findViewById<SeekBar>(R.id.seekSpeechRate)
        val tvSpeechRateLabel = dialogView.findViewById<TextView>(R.id.tvSpeechRateLabel)
        val seekPitch = dialogView.findViewById<SeekBar>(R.id.seekPitch)
        val tvPitchLabel = dialogView.findViewById<TextView>(R.id.tvPitchLabel)
        val switchWakeWord = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchWakeWord)
        val switchKeepScreenOn = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchKeepScreenOn)
        val seekContextTurns = dialogView.findViewById<SeekBar>(R.id.seekContextTurns)
        val tvContextTurnsLabel = dialogView.findViewById<TextView>(R.id.tvContextTurnsLabel)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        // Populate current values
        etRoomName.setText(prefs.roomName)
        etServerUrl.setText(prefs.serverUrl)
        switchTrustSsl.isChecked = prefs.trustSelfSignedSsl

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

        seekSpeechRate.progress = ((prefs.speechRate - 0.5f) * 10f).toInt().coerceIn(0, 20)
        tvSpeechRateLabel.text = "Velocità Voce Locale: ${String.format("%.1f", prefs.speechRate)}x"
        seekSpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val rate = 0.5f + (progress / 10f)
                tvSpeechRateLabel.text = "Velocità Voce Locale: ${String.format("%.1f", rate)}x"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekPitch.progress = ((prefs.pitch - 0.5f) * 10f).toInt().coerceIn(0, 20)
        tvPitchLabel.text = "Intonazione (Pitch): ${String.format("%.1f", prefs.pitch)}x"
        seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val pitch = 0.5f + (progress / 10f)
                tvPitchLabel.text = "Intonazione (Pitch): ${String.format("%.1f", pitch)}x"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        switchWakeWord.isChecked = prefs.isWakeWordEnabled
        switchKeepScreenOn.isChecked = prefs.isSmartDisplayEnabled

        seekContextTurns.progress = prefs.contextTurns - 2
        tvContextTurnsLabel.text = "Finestra Contesto: ${prefs.contextTurns} turni memorizzati"
        seekContextTurns.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvContextTurnsLabel.text = "Finestra Contesto: ${progress + 2} turni memorizzati"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val oldUrl = prefs.serverUrl
            prefs.roomName = etRoomName.text.toString().trim()
            prefs.serverUrl = etServerUrl.text.toString().trim()
            prefs.trustSelfSignedSsl = switchTrustSsl.isChecked
            prefs.ttsMode = if (rbTtsServer.isChecked) AppPreferences.MODE_SERVER else AppPreferences.MODE_DEVICE
            prefs.speechRate = 0.5f + (seekSpeechRate.progress / 10f)
            prefs.pitch = 0.5f + (seekPitch.progress / 10f)
            prefs.isWakeWordEnabled = switchWakeWord.isChecked
            prefs.isSmartDisplayEnabled = switchKeepScreenOn.isChecked
            prefs.contextTurns = seekContextTurns.progress + 2

            binding.tvRoomSubtitle.text = "${prefs.roomName} • Smart Satellite"
            updateBadges()
            applySmartDisplayMode()

            wakeWordDetector?.isEnabled?.set(prefs.isWakeWordEnabled)
            nativeTts?.setSpeechRate(prefs.speechRate)
            nativeTts?.setPitch(prefs.pitch)

            if (oldUrl != prefs.serverUrl) {
                connectWebSocket()
            } else {
                webSocket?.sendConfig(prefs.contextTurns)
            }

            dialog.dismiss()
        }

        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        captureManager?.release()
        playbackManager?.release()
        nativeTts?.shutdown()
        webSocket?.disconnect()
    }
}
