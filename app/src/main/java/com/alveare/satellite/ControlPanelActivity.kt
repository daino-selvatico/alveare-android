package com.alveare.satellite

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.alveare.satellite.data.AppPreferences
import com.alveare.satellite.databinding.ActivityControlPanelBinding
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ControlPanelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControlPanelBinding
    private lateinit var prefs: AppPreferences
    private lateinit var httpClient: OkHttpClient
    private val gson = Gson()

    private val llmModels = listOf("gemma4-e4b", "gemma4", "qwen3.8-27b", "llama")
    private val sttModels = listOf("openai/whisper-large-v3-turbo", "openai/whisper-base")
    private val ttsModels = listOf("hexgrad/Kokoro-82M", "Audio8/Audio8-TTS-Preview-0.1b", "Audio8/Audio8-TTS-Preview-0.6b")
    private val devices = listOf("gpu", "npu", "cpu")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        httpClient = buildHttpClient(prefs.trustSelfSignedSsl)

        setupSpinners()
        setupListeners()
        binding.tvLogs.movementMethod = ScrollingMovementMethod()

        fetchStatusAndTelemetry()
        fetchConfig()
        fetchLogs()
    }

    private fun setupSpinners() {
        binding.spModelLlm.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, llmModels)
        binding.spDeviceLlm.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, devices)

        binding.spModelStt.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sttModels)
        binding.spDeviceStt.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, devices)

        binding.spModelTts.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ttsModels)
        binding.spDeviceTts.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, devices)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener {
            fetchStatusAndTelemetry()
            fetchLogs()
        }

        binding.btnStartAll.setOnClickListener {
            controlSlot("all", "start")
        }

        binding.btnStopAll.setOnClickListener {
            controlSlot("all", "stop")
        }

        // LLM Slot
        binding.btnStartLlm.setOnClickListener {
            val model = binding.spModelLlm.selectedItem?.toString()
            val device = binding.spDeviceLlm.selectedItem?.toString()
            controlSlot("llm", "start", model, device)
        }
        binding.btnStopLlm.setOnClickListener {
            controlSlot("llm", "stop")
        }
        binding.btnRestartLlm.setOnClickListener {
            controlSlot("llm", "stop") {
                val model = binding.spModelLlm.selectedItem?.toString()
                val device = binding.spDeviceLlm.selectedItem?.toString()
                controlSlot("llm", "start", model, device)
            }
        }

        // STT Slot
        binding.btnStartStt.setOnClickListener {
            val model = binding.spModelStt.selectedItem?.toString()
            val device = binding.spDeviceStt.selectedItem?.toString()
            controlSlot("stt", "start", model, device)
        }
        binding.btnStopStt.setOnClickListener {
            controlSlot("stt", "stop")
        }
        binding.btnRestartStt.setOnClickListener {
            controlSlot("stt", "stop") {
                val model = binding.spModelStt.selectedItem?.toString()
                val device = binding.spDeviceStt.selectedItem?.toString()
                controlSlot("stt", "start", model, device)
            }
        }

        // TTS Slot
        binding.btnStartTts.setOnClickListener {
            val model = binding.spModelTts.selectedItem?.toString()
            val device = binding.spDeviceTts.selectedItem?.toString()
            controlSlot("tts", "start", model, device)
        }
        binding.btnStopTts.setOnClickListener {
            controlSlot("tts", "stop")
        }
        binding.btnRestartTts.setOnClickListener {
            controlSlot("tts", "stop") {
                val model = binding.spModelTts.selectedItem?.toString()
                val device = binding.spDeviceTts.selectedItem?.toString()
                controlSlot("tts", "start", model, device)
            }
        }

        // Tuning SeekBars
        binding.sbTemperature.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val temp = progress / 100.0f
                binding.tvTempLabel.text = String.format("Temperature: %.2f", temp)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbTopP.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val topP = progress / 100.0f
                binding.tvTopPLabel.text = String.format("Top P: %.2f", topP)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.sbMaxTokens.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvMaxTokensLabel.text = "Max Tokens: $progress"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnSaveTuning.setOnClickListener {
            saveConfig()
        }
    }

    private fun getBaseUrl(): String {
        return AppPreferences.getBaseHttpUrl(prefs.serverUrl)
    }

    private fun controlSlot(slot: String, action: String, model: String? = null, device: String? = null, onComplete: (() -> Unit)? = null) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "${getBaseUrl()}/api/control/slot/$action"
                val bodyJson = JsonObject().apply {
                    addProperty("slot", slot)
                    if (model != null) addProperty("model", model)
                    if (device != null) addProperty("device", device)
                }
                val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url).post(body).build()
                val resp = httpClient.newCall(req).execute()
                val isSuccess = resp.isSuccessful
                resp.close()

                withContext(Dispatchers.Main) {
                    if (isSuccess) {
                        Toast.makeText(this@ControlPanelActivity, "Slot $slot -> $action inviato", Toast.LENGTH_SHORT).show()
                        fetchStatusAndTelemetry()
                        onComplete?.invoke()
                    } else {
                        Toast.makeText(this@ControlPanelActivity, "Errore $action slot $slot", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ControlPanelActivity, "Richiesta fallita: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchStatusAndTelemetry() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "${getBaseUrl()}/api/status"
                val req = Request.Builder().url(url).get().build()
                val resp = httpClient.newCall(req).execute()
                val bodyStr = resp.body?.string()
                resp.close()

                if (bodyStr != null) {
                    val json = gson.fromJson(bodyStr, JsonObject::class.java)
                    withContext(Dispatchers.Main) {
                        updateUiFromStatus(json)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvTelemetryGpu.text = "GPU: Server offline o non raggiungibile"
                }
            }
        }
    }

    private fun updateUiFromStatus(json: JsonObject) {
        // Telemetry
        val gpu = json.getAsJsonObject("gpu_usage")
        if (gpu != null) {
            val name = gpu.get("device_name")?.asString ?: "GPU"
            val vramUsed = gpu.get("vram_used_mb")?.asInt ?: 0
            val vramTotal = gpu.get("vram_total_mb")?.asInt ?: 0
            binding.tvTelemetryGpu.text = "GPU ($name): $vramUsed / $vramTotal MB VRAM"
        }

        val npu = json.getAsJsonObject("npu_usage")
        if (npu != null) {
            val present = npu.get("present")?.asBoolean ?: false
            val activeCtx = npu.get("active_contexts")?.asInt ?: 0
            val npuName = npu.get("device_name")?.asString ?: "AMD Ryzen AI XDNA2"
            binding.tvTelemetryNpu.text = "NPU ($npuName): ${if (present) "Attiva ($activeCtx contesti)" else "Non rilevata"}"
        }

        val cpu = json.getAsJsonObject("cpu_usage")
        if (cpu != null) {
            val pct = cpu.get("percent")?.asFloat ?: 0.0f
            binding.tvTelemetryCpu.text = String.format("CPU: %.1f%% di utilizzo", pct)
        }

        // Slots
        val slots = json.getAsJsonObject("slots")
        if (slots != null) {
            slots.getAsJsonObject("llm")?.let {
                val st = it.get("status")?.asString ?: "stopped"
                val m = it.get("model")?.asString ?: ""
                val d = it.get("device")?.asString ?: ""
                binding.tvStatusLlm.text = "$st ($m @ $d)"
            }
            slots.getAsJsonObject("stt")?.let {
                val st = it.get("status")?.asString ?: "stopped"
                val m = it.get("model")?.asString ?: ""
                val d = it.get("device")?.asString ?: ""
                binding.tvStatusStt.text = "$st ($m @ $d)"
            }
            slots.getAsJsonObject("tts")?.let {
                val st = it.get("status")?.asString ?: "stopped"
                val m = it.get("model")?.asString ?: ""
                val d = it.get("device")?.asString ?: ""
                binding.tvStatusTts.text = "$st ($m @ $d)"
            }
        }
    }

    private fun fetchConfig() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "${getBaseUrl()}/api/config"
                val req = Request.Builder().url(url).get().build()
                val resp = httpClient.newCall(req).execute()
                val bodyStr = resp.body?.string()
                resp.close()

                if (bodyStr != null) {
                    val json = gson.fromJson(bodyStr, JsonObject::class.java)
                    withContext(Dispatchers.Main) {
                        val temp = json.get("temperature")?.asFloat ?: 0.5f
                        val topP = json.get("top_p")?.asFloat ?: 0.9f
                        val maxTokens = json.get("max_tokens")?.asInt ?: 120
                        val spec = json.get("speculative_decoding")?.asBoolean ?: true

                        binding.sbTemperature.progress = (temp * 100).toInt()
                        binding.sbTopP.progress = (topP * 100).toInt()
                        binding.sbMaxTokens.progress = maxTokens
                        binding.swSpeculative.isChecked = spec
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun saveConfig() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "${getBaseUrl()}/api/config"
                val bodyJson = JsonObject().apply {
                    addProperty("temperature", binding.sbTemperature.progress / 100.0f)
                    addProperty("top_p", binding.sbTopP.progress / 100.0f)
                    addProperty("max_tokens", binding.sbMaxTokens.progress)
                    addProperty("speculative_decoding", binding.swSpeculative.isChecked)
                }
                val body = bodyJson.toString().toRequestBody("application/json".toMediaType())
                val req = Request.Builder().url(url).post(body).build()
                val resp = httpClient.newCall(req).execute()
                val isSuccess = resp.isSuccessful
                resp.close()

                withContext(Dispatchers.Main) {
                    if (isSuccess) {
                        Toast.makeText(this@ControlPanelActivity, "Parametri salvati su Alveare", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ControlPanelActivity, "Errore nel salvataggio config", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ControlPanelActivity, "Errore: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "${getBaseUrl()}/api/logs"
                val req = Request.Builder().url(url).get().build()
                val resp = httpClient.newCall(req).execute()
                val bodyStr = resp.body?.string()
                resp.close()

                if (bodyStr != null) {
                    val json = gson.fromJson(bodyStr, JsonObject::class.java)
                    val logsArr = json.getAsJsonArray("logs")
                    val logText = StringBuilder()
                    logsArr?.forEach {
                        logText.append(it.asString).append("\n")
                    }
                    withContext(Dispatchers.Main) {
                        if (logText.isNotEmpty()) {
                            binding.tvLogs.text = logText.toString()
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun buildHttpClient(trustSelfSigned: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)

        if (trustSelfSigned) {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAllCerts, SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder.build()
    }
}
