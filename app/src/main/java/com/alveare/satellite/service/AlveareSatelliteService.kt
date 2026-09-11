package com.alveare.satellite.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.alveare.satellite.MainActivity
import com.alveare.satellite.R
import com.alveare.satellite.audio.AudioCaptureManager
import com.alveare.satellite.audio.AudioPlaybackManager
import com.alveare.satellite.audio.SoundEffects
import com.alveare.satellite.data.AppPreferences
import com.alveare.satellite.network.AlveareLiveWebSocket
import com.alveare.satellite.state.SatelliteMode
import com.alveare.satellite.state.SatelliteState
import com.alveare.satellite.state.SatelliteStateMachine
import com.alveare.satellite.tts.AndroidNativeTts
import com.alveare.satellite.wakeword.VoskModelManager
import com.alveare.satellite.wakeword.VoskWakeWordDetector
import java.util.concurrent.atomic.AtomicBoolean

class AlveareSatelliteService : Service(), AlveareLiveWebSocket.LiveWebSocketListener {

    companion object {
        private const val TAG = "AlveareService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "alveare_satellite_channel"

        const val ACTION_START = "com.alveare.satellite.action.START"
        const val ACTION_STOP = "com.alveare.satellite.action.STOP"
        const val ACTION_TOGGLE_MUTE = "com.alveare.satellite.action.TOGGLE_MUTE"
        const val ACTION_INTERRUPT = "com.alveare.satellite.action.INTERRUPT"

        var isServiceRunning = false
            private set
    }

    interface ServiceListener {
        fun onStateChanged(state: SatelliteState)
        fun onAmplitudeChanged(amplitude: Float)
        fun onConnectionStatusChanged(status: String, isConnected: Boolean)
        fun onUserTranscript(text: String, latencyMs: Float)
        fun onAssistantDelta(delta: String)
        fun onAssistantSentence(sentence: String)
        fun onToolCall(toolName: String, args: String?, summary: String?, status: String?)
        fun onTurnCompleted(turnId: Int, fullText: String?)
        fun onMemoryCleared(message: String)
        fun onError(error: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): AlveareSatelliteService = this@AlveareSatelliteService
    }

    private val binder = LocalBinder()
    private var listener: ServiceListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isStopping = AtomicBoolean(false)

    private lateinit var prefs: AppPreferences
    lateinit var stateMachine: SatelliteStateMachine
        private set

    var webSocket: AlveareLiveWebSocket? = null
        private set
    var captureManager: AudioCaptureManager? = null
        private set
    var playbackManager: AudioPlaybackManager? = null
        private set
    var nativeTts: AndroidNativeTts? = null
        private set
    var wakeWordDetector: VoskWakeWordDetector? = null
        private set

    var isPrivacyMuted = false
        private set

    // Latched settings for pending turn completion
    private var pendingConfigTurns: Int? = null
    private var pendingConfigTtsMode: String? = null

    private fun notifyListener(block: (ServiceListener) -> Unit) {
        mainHandler.post {
            listener?.let(block)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        createNotificationChannel()

        val initialMode = if (prefs.satelliteMode == AppPreferences.SATELLITE_MODE_LIVE) {
            SatelliteMode.LIVE
        } else {
            SatelliteMode.ASSISTANT
        }

        stateMachine = SatelliteStateMachine(
            initialMode = initialMode,
            isWakeWordEnabled = prefs.isWakeWordEnabled
        ) { newState ->
            mainHandler.post {
                updateNotification()
                listener?.onStateChanged(newState)
            }
        }

        initAudioEngines()
        isServiceRunning = true
    }

    fun setServiceListener(l: ServiceListener?) {
        this.listener = l
        l?.onStateChanged(stateMachine.currentState)
    }

    private fun initAudioEngines() {
        // 1. Playback Manager
        playbackManager = AudioPlaybackManager(
            sampleRate = 24000,
            onPlaybackStateChanged = { isPlaying ->
                if (isPlaying) {
                    captureManager?.isAssistantSpeaking?.set(true)
                    stateMachine.onAssistantPlaybackStarted()
                }
            },
            onPlaybackDrained = {
                captureManager?.isAssistantSpeaking?.set(false)
                captureManager?.muteFor(350)
                stateMachine.onAssistantPlaybackFinished()
            }
        ).apply { start() }

        // 2. Native TTS
        nativeTts = AndroidNativeTts(
            context = this,
            onPlaybackStarted = {
                captureManager?.isAssistantSpeaking?.set(true)
                stateMachine.onAssistantPlaybackStarted()
            },
            onPlaybackFinished = {
                captureManager?.isAssistantSpeaking?.set(false)
                captureManager?.muteFor(350)
                stateMachine.onAssistantPlaybackFinished()
            }
        ).apply {
            setSpeechRate(prefs.speechRate)
            setPitch(prefs.pitch)
        }

        // 3. Offline Vosk Lexical Wake Word (Loaded asynchronously!)
        val modelDir = VoskModelManager.getInstalledModelDir(this)
        wakeWordDetector = VoskWakeWordDetector(
            modelDir = modelDir,
            sampleRate = 16000.0f,
            wakePhrase = "ehi alveare",
            isConnectedProvider = { webSocket?.isConnected?.get() == true },
            onWakeWordDetected = { preRollAudio ->
                handleWakeWordActivated(preRollAudio)
            }
        ).apply {
            isEnabled.set(prefs.isWakeWordEnabled && stateMachine.mode == SatelliteMode.ASSISTANT)
        }

        // 4. Capture Manager (Single AudioRecord owner!)
        captureManager = AudioCaptureManager(
            sampleRate = 16000,
            onAudioChunkReady = { chunk ->
                if (stateMachine.isStreamingAudioToWebSocket) {
                    webSocket?.sendAudioChunk(chunk)
                }
            },
            onAmplitudeChanged = { amp ->
                notifyListener { it.onAmplitudeChanged(amp) }
            },
            onSpeechStarted = {
                stateMachine.onVadSpeechStart()
            },
            onSilenceDetected = {
                if (stateMachine.mode == SatelliteMode.ASSISTANT && stateMachine.currentState == SatelliteState.LISTENING) {
                    finishListeningAndProcess()
                }
            },
            onWakeWordAudioChunk = { pcmChunk, len ->
                if (stateMachine.isWakeWordActive) {
                    wakeWordDetector?.feedAudio(pcmChunk, len)
                }
            }
        ).apply {
            isContinuousMode = (stateMachine.mode == SatelliteMode.LIVE)
            isAecSafeMode = prefs.isAecSafeMode
        }
    }

    private fun handleWakeWordActivated(preRollAudio: ByteArray) {
        if (isPrivacyMuted) return

        captureManager?.muteFor(500)
        SoundEffects.playWakeChime()
        stateMachine.onWakeWordTriggered()
        captureManager?.startStreaming()

        // Bound pre-roll audio to backend input maximum: 800ms at 16kHz mono 16-bit PCM = 25600 bytes
        val boundedPreRoll = if (preRollAudio.size > 25600) {
            preRollAudio.copyOfRange(preRollAudio.size - 25600, preRollAudio.size)
        } else {
            preRollAudio
        }

        if (boundedPreRoll.isNotEmpty()) {
            webSocket?.sendAudioChunk(boundedPreRoll)
        }
    }

    fun handleTapToTalk() {
        if (isPrivacyMuted) return

        when (stateMachine.currentState) {
            SatelliteState.SPEAKING, SatelliteState.PROCESSING -> {
                interruptSession()
            }
            SatelliteState.LISTENING -> {
                finishListeningAndProcess()
            }
            SatelliteState.IDLE_WAITING_ACTIVATION, SatelliteState.LIVE_IDLE_STREAMING -> {
                captureManager?.muteFor(450)
                SoundEffects.playWakeChime()
                stateMachine.onTapToTalkTriggered()
                captureManager?.startStreaming()
            }
            SatelliteState.MUTED -> {}
        }
    }

    fun finishListeningAndProcess() {
        if (stateMachine.currentState != SatelliteState.LISTENING) return

        captureManager?.stopStreaming()
        if (stateMachine.mode == SatelliteMode.ASSISTANT) {
            captureManager?.muteFor(300)
        }
        webSocket?.sendEndOfSpeech()
        SoundEffects.playProcessingChime()
        stateMachine.onEndOfSpeech()
    }

    fun interruptSession(sendToServer: Boolean = true) {
        if (stateMachine.mode == SatelliteMode.ASSISTANT) {
            captureManager?.muteFor(500)
        }
        playbackManager?.stopAndFlush()
        nativeTts?.stop()
        if (stateMachine.mode == SatelliteMode.ASSISTANT) {
            captureManager?.stopStreaming()
        }

        if (sendToServer) {
            webSocket?.sendInterrupt()
            SoundEffects.playInterruptChime()
        }

        stateMachine.onInterrupted()
    }

    fun togglePrivacyMute() {
        isPrivacyMuted = !isPrivacyMuted
        stateMachine.setPrivacyMuted(isPrivacyMuted)
        if (isPrivacyMuted) {
            captureManager?.isPrivacyMuted?.set(true)
            captureManager?.stop() // Real hardware release
            playbackManager?.stopAndFlush()
            nativeTts?.stop()
        } else {
            captureManager?.isPrivacyMuted?.set(false)
            if (isServiceRunning && (webSocket?.isConnected?.get() == true)) {
                val started = captureManager?.start() ?: false
                if (!started) {
                    notifyListener { it.onError("Impossibile avviare il microfono") }
                } else if (stateMachine.mode == SatelliteMode.LIVE) {
                    captureManager?.startStreaming()
                }
            }
        }
        updateNotification()
    }

    fun setMode(newMode: SatelliteMode) {
        // Mode switch cancels in-flight turn and playback immediately
        playbackManager?.stopAndFlush()
        nativeTts?.stop()
        stateMachine.setMode(newMode)
        captureManager?.isContinuousMode = (newMode == SatelliteMode.LIVE)
        captureManager?.isAecSafeMode = prefs.isAecSafeMode
        wakeWordDetector?.isEnabled?.set(prefs.isWakeWordEnabled && newMode == SatelliteMode.ASSISTANT)
        if (newMode == SatelliteMode.LIVE) {
            if (!isPrivacyMuted && isServiceRunning && (webSocket?.isConnected?.get() == true)) {
                captureManager?.start()
                captureManager?.startStreaming()
            }
        } else {
            captureManager?.stopStreaming()
        }
        updateNotification()
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        prefs.isWakeWordEnabled = enabled
        stateMachine.isWakeWordEnabled = enabled
        wakeWordDetector?.isEnabled?.set(enabled && stateMachine.mode == SatelliteMode.ASSISTANT)
        updateNotification()
    }

    fun updateConfig(contextTurns: Int, ttsMode: String) {
        if (stateMachine.currentState == SatelliteState.PROCESSING || stateMachine.currentState == SatelliteState.SPEAKING) {
            // Latch configuration change to apply upon turn completion
            pendingConfigTurns = contextTurns
            pendingConfigTtsMode = ttsMode
        } else {
            webSocket?.sendConfig(contextTurns, ttsMode)
        }
    }

    private fun checkPendingConfig() {
        val turns = pendingConfigTurns
        val ttsMode = pendingConfigTtsMode
        if (turns != null && ttsMode != null) {
            webSocket?.sendConfig(turns, ttsMode)
            pendingConfigTurns = null
            pendingConfigTtsMode = null
        }
    }

    fun connectWebSocket() {
        webSocket?.disconnect()
        notifyListener { it.onConnectionStatusChanged("Connessione...", false) }

        webSocket = AlveareLiveWebSocket(
            serverUrl = prefs.serverUrl,
            trustSelfSignedSsl = prefs.trustSelfSignedSsl,
            roomName = prefs.roomName,
            autoReconnect = true,
            listener = this
        ).apply {
            connect()
        }
    }

    fun reloadWakeWordModel() {
        val modelDir = VoskModelManager.getInstalledModelDir(this)
        wakeWordDetector?.release()
        wakeWordDetector = VoskWakeWordDetector(
            modelDir = modelDir,
            sampleRate = 16000.0f,
            wakePhrase = "ehi alveare",
            isConnectedProvider = { webSocket?.isConnected?.get() == true },
            onWakeWordDetected = { preRollAudio ->
                handleWakeWordActivated(preRollAudio)
            }
        ).apply {
            isEnabled.set(prefs.isWakeWordEnabled && stateMachine.mode == SatelliteMode.ASSISTANT)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // System restarted service in background without intent.
            // On Android 14+, starting microphone foreground service from background throws SecurityException.
            if (!isServiceRunning) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                togglePrivacyMute()
            }
            ACTION_INTERRUPT -> {
                interruptSession()
            }
            ACTION_START -> {
                val hasMicPermission = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                startForegroundWithNotification()

                if (hasMicPermission && !isPrivacyMuted) {
                    val started = captureManager?.start() ?: false
                    if (!started) {
                        notifyListener { it.onError("Impossibile avviare il microfono") }
                    }
                } else if (!hasMicPermission) {
                    notifyListener { it.onError("Permesso microfono non concesso") }
                }
                connectWebSocket()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alveare Smart Satellite",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifica satellite Alveare in esecuzione"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val muteIntent = Intent(this, AlveareSatelliteService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val pMute = PendingIntent.getService(
            this, 1, muteIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AlveareSatelliteService::class.java).apply {
            action = ACTION_STOP
        }
        val pStop = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val modeStr = if (stateMachine.mode == SatelliteMode.LIVE) "Live (Full Duplex)" else "Assistente"
        val stateStr = when (stateMachine.currentState) {
            SatelliteState.MUTED -> "🔇 Muto (Privacy)"
            SatelliteState.IDLE_WAITING_ACTIVATION -> if (prefs.isWakeWordEnabled) "👂 In attesa di 'Ehi Alveare'" else "Pronto (Tocca per parlare)"
            SatelliteState.LIVE_IDLE_STREAMING -> "🎙️ Sempre in ascolto"
            SatelliteState.LISTENING -> "🎙️ In ascolto..."
            SatelliteState.PROCESSING -> "⚡ Sto elaborando..."
            SatelliteState.SPEAKING -> "🔊 Alveare parla..."
        }

        val muteBtnTitle = if (isPrivacyMuted) "Riattiva Mic" else "Muto"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Alveare • ${prefs.roomName} ($modeStr)")
            .setContentText(stateStr)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pOpen)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_mic, muteBtnTitle, pMute)
            .addAction(R.drawable.ic_refresh, "Stop / Disconnetti", pStop)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    fun stopForegroundService() {
        if (!isStopping.compareAndSet(false, true)) return

        isServiceRunning = false
        listener = null
        try {
            webSocket?.disconnect()
        } catch (ignored: Exception) {}
        webSocket = null

        captureManager?.release()
        captureManager = null

        playbackManager?.release()
        playbackManager = null

        nativeTts?.shutdown()
        nativeTts = null

        wakeWordDetector?.release()
        wakeWordDetector = null

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ignored: Exception) {}
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopForegroundService()
    }

    // --- WebSocket Listener Callbacks ---

    override fun onConnecting() {
        notifyListener { it.onConnectionStatusChanged("Connessione...", false) }
    }

    override fun onConnected(sessionId: String?, inputSampleRate: Int, outputSampleRate: Int) {
        playbackManager?.setSampleRate(outputSampleRate)
        webSocket?.sendConfig(prefs.contextTurns, prefs.ttsMode)
        notifyListener { it.onConnectionStatusChanged("Connesso", true) }
        stateMachine.resetToIdle()
        if (!isPrivacyMuted) {
            val started = captureManager?.start() ?: false
            if (!started) {
                notifyListener { it.onError("Impossibile avviare il microfono") }
            } else if (stateMachine.mode == SatelliteMode.LIVE) {
                captureManager?.startStreaming()
            }
        }
        updateNotification()
    }

    override fun onDisconnected(reason: String) {
        notifyListener { it.onConnectionStatusChanged("Disconnesso: $reason", false) }
        captureManager?.stop() // Release capture on disconnect
        playbackManager?.stopAndFlush()
        nativeTts?.stop()
        stateMachine.resetToIdle()
        updateNotification()
    }

    override fun onVadSpeechStart() {
        stateMachine.onVadSpeechStart()
    }

    override fun onVadSpeechEnd() {
        stateMachine.onVadSpeechEnd()
    }

    override fun onTtft(ttftMs: Float) {}

    override fun onUserTranscript(text: String, latencyMs: Float) {
        notifyListener { it.onUserTranscript(text, latencyMs) }
    }

    override fun onAssistantDelta(delta: String) {
        notifyListener { it.onAssistantDelta(delta) }
    }

    override fun onAssistantSentence(sentence: String) {
        notifyListener { it.onAssistantSentence(sentence) }
        // If Device Native TTS is active, synthesize on client
        if (prefs.ttsMode == AppPreferences.MODE_DEVICE) {
            stateMachine.onAudioQueued()
            nativeTts?.speak(sentence)
        }
    }

    override fun onAudioChunkReceived(pcmBytes: ByteArray, sampleRate: Int, format: String?) {
        // If Server TTS is active, stream into playback manager
        if (prefs.ttsMode == AppPreferences.MODE_SERVER) {
            stateMachine.onAudioQueued()
            playbackManager?.enqueueAudio(pcmBytes, sampleRate)
        }
    }

    override fun onToolCall(toolName: String, args: String?, summary: String?, status: String?) {
        notifyListener { it.onToolCall(toolName, args, summary, status) }
    }

    override fun onTurnCompleted(turnId: Int, fullText: String?) {
        notifyListener { it.onTurnCompleted(turnId, fullText) }
        if (prefs.ttsMode == AppPreferences.MODE_DEVICE) {
            nativeTts?.markGenerationDone()
        }
        stateMachine.onServerTurnCompleted()
        checkPendingConfig()
    }

    override fun onMemoryCleared(message: String) {
        notifyListener { it.onMemoryCleared(message) }
    }

    override fun onInterrupted() {
        interruptSession(sendToServer = false)
    }

    override fun onError(error: String) {
        notifyListener { it.onError(error) }
        stateMachine.resetToIdle()
    }
}
