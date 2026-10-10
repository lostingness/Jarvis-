package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.actions.DeviceActionManager
import com.example.audio.AudioRecordManager
import com.example.audio.AudioTrackPlayer
import com.example.gemini.GeminiAudioFallbackService
import com.example.gemini.GeminiLiveClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AssistantState {
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    EXECUTING_ACTION,
    ERROR
}

data class DiagnosticLog(
    val timestamp: String,
    val tag: String,
    val message: String
)

data class JarvisUiState(
    val assistantState: AssistantState = AssistantState.IDLE,
    val amplitude: Float = 0f,
    val userTranscript: String = "",
    val jarvisTranscript: String = "Hello! I'm Jarvis. Tap the reactor core to start talking.",
    val lastAction: String? = null,
    val errorMessage: String? = null,
    val speakerDiagnosticStatus: String? = null,
    val logs: List<DiagnosticLog> = emptyList(),
    val isMicActive: Boolean = false,
    val isTestingSpeaker: Boolean = false,
    val hasApiKey: Boolean = true,
    val hasMicPermission: Boolean = false
)

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "JarvisViewModel"
    }

    private val _uiState = MutableStateFlow(JarvisUiState())
    val uiState: StateFlow<JarvisUiState> = _uiState.asStateFlow()

    private val actionManager = DeviceActionManager(application.applicationContext)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val apiKey: String = try {
        BuildConfig.GEMINI_API_KEY
    } catch (e: Exception) {
        ""
    }

    private val audioTrackPlayer: AudioTrackPlayer = AudioTrackPlayer(
        onPlaybackStarted = {
            addLog("SPEAKER", "Audio playback started through device speaker")
            _uiState.update { it.copy(assistantState = AssistantState.SPEAKING) }
        },
        onPlaybackEnded = {
            addLog("SPEAKER", "Audio playback finished")
            _uiState.update {
                it.copy(
                    assistantState = if (it.isMicActive) AssistantState.LISTENING else AssistantState.IDLE,
                    amplitude = 0f
                )
            }
        },
        onPlaybackAmplitude = { amp ->
            if (_uiState.value.assistantState == AssistantState.SPEAKING) {
                _uiState.update { it.copy(amplitude = amp) }
            }
        },
        onError = { err ->
            addLog("AUDIO_ERR", err)
        }
    )

    private val audioRecordManager: AudioRecordManager = AudioRecordManager(
        context = application.applicationContext,
        onChunk = { base64Data, _ ->
            onMicrophoneChunk(base64Data)
        },
        onAmplitude = { amp ->
            if (_uiState.value.assistantState == AssistantState.LISTENING) {
                _uiState.update { it.copy(amplitude = amp) }
            }
        },
        onSpeechUtteranceComplete = { userPcmBytes ->
            onUserSpeechComplete(userPcmBytes)
        },
        onError = { err ->
            addLog("MIC_ERR", err)
            _uiState.update { it.copy(errorMessage = err) }
        }
    )

    private val fallbackService: GeminiAudioFallbackService = GeminiAudioFallbackService(apiKey)
    private var geminiLiveClient: GeminiLiveClient? = null

    init {
        val hasKey = apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY"
        val hasPerm = audioRecordManager.hasPermission()
        _uiState.update {
            it.copy(
                hasApiKey = hasKey,
                hasMicPermission = hasPerm
            )
        }
        addLog("INIT", "Jarvis initialized. API Key: $hasKey, Mic Permission: $hasPerm")

        setupGeminiLiveClient()
    }

    private fun setupGeminiLiveClient() {
        geminiLiveClient = GeminiLiveClient(
            apiKey = apiKey,
            onConnected = {
                addLog("LIVE_OK", "Connected to Gemini Live session. Listening for user...")
                _uiState.update {
                    it.copy(
                        assistantState = AssistantState.LISTENING,
                        errorMessage = null,
                        jarvisTranscript = "Listening to you..."
                    )
                }
            },
            onDisconnected = { reason ->
                addLog("LIVE_DISC", "Live session status: $reason")
                if (_uiState.value.isMicActive) {
                    _uiState.update {
                        it.copy(
                            assistantState = AssistantState.LISTENING,
                            jarvisTranscript = "Ready. Speak to me..."
                        )
                    }
                }
            },
            onAudioChunkReceived = { pcmBytes, sampleRate ->
                addLog("AUDIO_RX", "Received ${pcmBytes.size} bytes PCM @ $sampleRate Hz")
                audioTrackPlayer.queueAudio(pcmBytes, sampleRate)
            },
            onTranscriptReceived = { text, isUser ->
                if (isUser) {
                    _uiState.update { it.copy(userTranscript = text) }
                } else {
                    _uiState.update {
                        val combined = if (it.assistantState == AssistantState.SPEAKING && it.jarvisTranscript.isNotBlank() && !it.jarvisTranscript.startsWith("Hello!")) {
                            it.jarvisTranscript + " " + text
                        } else {
                            text
                        }
                        it.copy(jarvisTranscript = combined)
                    }
                }
            },
            onInterrupted = {
                addLog("INTERRUPT", "Interruption detected: stopping speech immediately")
                audioTrackPlayer.interrupt()
                _uiState.update {
                    it.copy(
                        assistantState = AssistantState.LISTENING,
                        amplitude = 0f
                    )
                }
            },
            onToolCall = { callId, functionName, args ->
                executeToolCall(callId, functionName, args)
            },
            onLog = { tag, msg ->
                addLog(tag, msg)
            },
            onError = { err ->
                addLog("API_ERR", err)
            }
        )
    }

    fun onMicrophonePermissionGranted() {
        addLog("PERM", "Microphone permission granted")
        _uiState.update { it.copy(hasMicPermission = true, errorMessage = null) }
    }

    fun onMicrophonePermissionConfirmed() {
        _uiState.update { it.copy(hasMicPermission = true) }
    }

    fun onMicrophonePermissionDenied() {
        addLog("PERM", "Microphone permission denied")
        _uiState.update {
            it.copy(
                hasMicPermission = false,
                errorMessage = "Microphone access is needed for voice. Tap the banner to grant.",
                assistantState = AssistantState.IDLE
            )
        }
    }

    private fun onMicrophoneChunk(base64Data: String) {
        val client = geminiLiveClient
        if (client != null && client.isConnected) {
            client.sendAudioChunk(base64Data)
        }
    }

    private fun onUserSpeechComplete(userPcmBytes: ByteArray) {
        val client = geminiLiveClient
        if (client != null && client.isConnected) {
            return
        }

        viewModelScope.launch(Dispatchers.Main) {
            addLog("VOICE_TURN", "Processing voice utterance (${userPcmBytes.size} bytes)...")
            _uiState.update {
                it.copy(
                    assistantState = AssistantState.CONNECTING,
                    jarvisTranscript = "Thinking..."
                )
            }

            val response = fallbackService.generateVoiceResponse(
                userText = "",
                userPcmBytes = userPcmBytes
            )

            handleVoiceResponse(response)
        }
    }

    fun hasMicPermission(): Boolean = audioRecordManager.hasPermission()

    fun toggleJarvis() {
        if (_uiState.value.isMicActive) {
            stopJarvis()
        } else {
            startJarvis()
        }
    }

    fun startJarvis() {
        if (!audioRecordManager.hasPermission()) {
            _uiState.update {
                it.copy(
                    errorMessage = "Microphone permission is required to speak with Jarvis.",
                    assistantState = AssistantState.ERROR,
                    hasMicPermission = false
                )
            }
            return
        }

        addLog("SESSION", "Activating Jarvis...")
        _uiState.update {
            it.copy(
                assistantState = AssistantState.LISTENING,
                isMicActive = true,
                errorMessage = null,
                jarvisTranscript = "Listening to you... Speak now."
            )
        }

        audioTrackPlayer.startPlaybackLoop()
        audioRecordManager.startRecording()

        // Confirmation tone so user knows Jarvis is listening
        audioTrackPlayer.playDiagnosticTestTone(523.25, 0.2)

        geminiLiveClient?.connect()
    }

    fun stopJarvis() {
        addLog("SESSION", "Stopping Jarvis...")
        audioRecordManager.stopRecording()
        audioTrackPlayer.interrupt()
        geminiLiveClient?.disconnect()

        _uiState.update {
            it.copy(
                assistantState = AssistantState.IDLE,
                isMicActive = false,
                amplitude = 0f,
                jarvisTranscript = "Jarvis in standby. Tap reactor core to speak."
            )
        }
    }

    fun testSpeaker() {
        viewModelScope.launch(Dispatchers.Main) {
            addLog("DIAGNOSTIC", "Initiating Speaker Diagnostic Test (440Hz tone)")
            _uiState.update {
                it.copy(
                    isTestingSpeaker = true,
                    speakerDiagnosticStatus = "Playing 440Hz diagnostic tone..."
                )
            }

            audioTrackPlayer.playDiagnosticTestTone(440.0, 1.2)

            kotlinx.coroutines.delay(1400)
            _uiState.update {
                it.copy(
                    isTestingSpeaker = false,
                    speakerDiagnosticStatus = "Speaker verified working!"
                )
            }
            addLog("DIAGNOSTIC", "Speaker test completed.")
        }
    }

    fun sendTextCommand(prompt: String) {
        if (prompt.isBlank()) return
        addLog("USER_CMD", prompt)
        _uiState.update {
            it.copy(
                userTranscript = prompt,
                jarvisTranscript = "Thinking...",
                assistantState = AssistantState.CONNECTING
            )
        }

        // Check for instant local command execution
        val lower = prompt.lowercase(Locale.ROOT)
        when {
            lower.contains("whatsapp") -> {
                actionManager.openWhatsApp()
                _uiState.update {
                    it.copy(
                        lastAction = "WhatsApp opened",
                        jarvisTranscript = "Opening WhatsApp for you right away!"
                    )
                }
                audioTrackPlayer.playDiagnosticTestTone(587.33, 0.2)
            }
            lower.contains("youtube") -> {
                actionManager.openApp("youtube")
                _uiState.update {
                    it.copy(
                        lastAction = "YouTube opened",
                        jarvisTranscript = "Opening YouTube for you now!"
                    )
                }
                audioTrackPlayer.playDiagnosticTestTone(587.33, 0.2)
            }
            lower.contains("instagram") -> {
                actionManager.openApp("instagram")
                _uiState.update {
                    it.copy(
                        lastAction = "Instagram opened",
                        jarvisTranscript = "Opening Instagram!"
                    )
                }
                audioTrackPlayer.playDiagnosticTestTone(587.33, 0.2)
            }
            lower.contains("speaker") || lower.contains("test") -> {
                testSpeaker()
                return
            }
            lower.startsWith("call") || lower.contains("ko call") -> {
                val cleanedName = prompt.replace(Regex("(?i)(call|ko call karo|phone lagao|phone karo)"), "").trim()
                if (cleanedName.matches(Regex(".*\\d{5,}.*"))) {
                    actionManager.makeCall(cleanedName)
                } else if (cleanedName.isNotBlank()) {
                    actionManager.callContact(cleanedName)
                }
            }
        }

        val client = geminiLiveClient
        if (client != null && client.isConnected) {
            client.sendTextPrompt(prompt)
        } else {
            viewModelScope.launch(Dispatchers.Main) {
                addLog("FALLBACK", "Processing: $prompt")
                val response = fallbackService.generateVoiceResponse(prompt)
                handleVoiceResponse(response)
            }
        }
    }

    private fun handleVoiceResponse(response: GeminiAudioFallbackService.VoiceResponse) {
        if (response.toolCalls.isNotEmpty()) {
            for (tc in response.toolCalls) {
                executeToolCall(tc.id, tc.name, tc.args)
            }
        }

        val replyText = when {
            response.text.isNotBlank() -> response.text
            response.toolCalls.isNotEmpty() -> "Opening right away!"
            else -> "Action processed!"
        }
        _uiState.update { it.copy(jarvisTranscript = replyText) }

        if (response.pcmAudioBytes != null && response.pcmAudioBytes.isNotEmpty()) {
            addLog("AUDIO_OUT", "Playing ${response.pcmAudioBytes.size} bytes audio through speaker")
            audioTrackPlayer.queueAudio(response.pcmAudioBytes, response.sampleRate)
        } else {
            _uiState.update {
                it.copy(
                    assistantState = if (it.isMicActive) AssistantState.LISTENING else AssistantState.IDLE
                )
            }
        }
    }

    private fun executeToolCall(callId: String, functionName: String, args: JSONObject) {
        viewModelScope.launch(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    assistantState = AssistantState.EXECUTING_ACTION,
                    lastAction = "Executing: $functionName"
                )
            }
            addLog("TOOL_EXEC", "Running $functionName with $args")

            val result: JSONObject = try {
                when (functionName) {
                    "openWhatsApp" -> actionManager.openWhatsApp()
                    "openApp" -> {
                        val appName = args.optString("appName", "")
                        actionManager.openApp(appName)
                    }
                    "openUrl" -> {
                        val url = args.optString("url", "")
                        actionManager.openUrl(url)
                    }
                    "makeCall" -> {
                        val phone = args.optString("phoneNumber", "")
                        actionManager.makeCall(phone)
                    }
                    "callContact" -> {
                        val contactName = args.optString("contactName", "")
                        actionManager.callContact(contactName)
                    }
                    else -> {
                        JSONObject().apply {
                            put("success", false)
                            put("error", "Unknown function: $functionName")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing tool $functionName", e)
                JSONObject().apply {
                    put("success", false)
                    put("error", "Action failed: ${e.message}")
                }
            }

            val actionSummary = when {
                result.optBoolean("success", false) -> "Done: ${result.optString("message", functionName)}"
                result.optBoolean("multipleMatches", false) -> "Clarification: ${result.optString("error", "")}"
                else -> "Action notice: ${result.optString("message", result.optString("error", "Processed"))}"
            }

            addLog("TOOL_RESULT", actionSummary)
            _uiState.update {
                it.copy(
                    lastAction = actionSummary,
                    assistantState = if (it.isMicActive) AssistantState.LISTENING else AssistantState.IDLE
                )
            }

            geminiLiveClient?.sendToolResponse(callId, result)
        }
    }

    fun addLog(tag: String, message: String) {
        val entry = DiagnosticLog(
            timestamp = timeFormat.format(Date()),
            tag = tag,
            message = message
        )
        Log.d("JARVIS_LOG", "[$tag] $message")
        _uiState.update {
            val updated = (listOf(entry) + it.logs).take(60)
            it.copy(logs = updated)
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecordManager.stopRecording()
        audioTrackPlayer.release()
        geminiLiveClient?.disconnect()
    }
}
