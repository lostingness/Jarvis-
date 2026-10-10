package com.example.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class AudioRecordManager(
    private val context: Context,
    private val onChunk: (base64Data: String, rawBytes: ByteArray) -> Unit,
    private val onAmplitude: (Float) -> Unit,
    private val onSpeechUtteranceComplete: (ByteArray) -> Unit = {},
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "AudioRecordManager"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE_BYTES = 2048 // 1024 16-bit samples (~64ms @ 16kHz)
        private const val SPEECH_THRESHOLD = 0.05f
        private const val SILENCE_DURATION_MS = 1200L
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    var isRecording: Boolean = false
        private set

    // Buffer to accumulate user speech turn for voice recognition & fallback
    private val speechTurnBuffer = ByteArrayOutputStream()
    private var hasSpokenInCurrentTurn = false
    private var lastSpeechTimeMs = 0L

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @Synchronized
    fun startRecording() {
        if (isRecording) {
            Log.d(TAG, "Already recording")
            return
        }

        if (!hasPermission()) {
            onError("Microphone permission not granted")
            return
        }

        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            val bufferSize = maxOf(minBufferSize * 2, CHUNK_SIZE_BYTES * 4)

            // Try MIC first as it is standard and universally supported
            var record: AudioRecord? = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (record?.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (record?.state != AudioRecord.STATE_INITIALIZED) {
                record?.release()
                record = AudioRecord(
                    MediaRecorder.AudioSource.DEFAULT,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onError("Failed to initialize AudioRecord device")
                return
            }

            audioRecord = record
            record.startRecording()
            isRecording = true
            speechTurnBuffer.reset()
            hasSpokenInCurrentTurn = false
            lastSpeechTimeMs = System.currentTimeMillis()
            Log.d(TAG, "AudioRecord started at $SAMPLE_RATE Hz, bufferSize=$bufferSize")

            recordingJob = scope.launch {
                val buffer = ByteArray(CHUNK_SIZE_BYTES)
                while (isActive && isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (bytesRead > 0) {
                        val validBytes = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)

                        // Calculate RMS amplitude for UI visualization & VAD
                        val amp = calculateRmsAmplitude(validBytes, bytesRead)
                        onAmplitude(amp)

                        // Encode chunk to base64 for live streaming
                        val base64 = Base64.encodeToString(validBytes, Base64.NO_WRAP)
                        onChunk(base64, validBytes)

                        // Voice Activity Accumulation
                        val now = System.currentTimeMillis()
                        if (amp >= SPEECH_THRESHOLD) {
                            hasSpokenInCurrentTurn = true
                            lastSpeechTimeMs = now
                            speechTurnBuffer.write(validBytes)
                        } else if (hasSpokenInCurrentTurn) {
                            speechTurnBuffer.write(validBytes)
                            // Check if user paused/stopped speaking
                            if (now - lastSpeechTimeMs > SILENCE_DURATION_MS) {
                                val completedPcm = speechTurnBuffer.toByteArray()
                                speechTurnBuffer.reset()
                                hasSpokenInCurrentTurn = false
                                if (completedPcm.size > 8000) { // At least ~0.25s of audio
                                    Log.d(TAG, "Speech utterance detected: ${completedPcm.size} bytes PCM")
                                    onSpeechUtteranceComplete(completedPcm)
                                }
                            }
                        }
                    } else if (bytesRead < 0) {
                        Log.e(TAG, "AudioRecord read error code: $bytesRead")
                        break
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting recording", e)
            onError("Permission denied: ${e.message}")
            stopRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting recording", e)
            onError("Error starting recording: ${e.message}")
            stopRecording()
        }
    }

    @Synchronized
    fun stopRecording() {
        if (!isRecording && audioRecord == null) return
        Log.d(TAG, "Stopping AudioRecord")
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
        speechTurnBuffer.reset()
        hasSpokenInCurrentTurn = false
        onAmplitude(0f)
    }

    private fun calculateRmsAmplitude(bytes: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        val shortBuffer = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        val sampleCount = length / 2
        while (shortBuffer.hasRemaining()) {
            val sample = shortBuffer.get()
            sum += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(sum / sampleCount)
        val normalized = (rms / 9000.0).toFloat().coerceIn(0f, 1f)
        return normalized
    }
}
