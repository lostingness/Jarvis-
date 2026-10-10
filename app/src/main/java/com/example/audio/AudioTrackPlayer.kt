package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AudioTrackPlayer(
    private val onPlaybackStarted: () -> Unit = {},
    private val onPlaybackEnded: () -> Unit = {},
    private val onPlaybackAmplitude: (Float) -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioTrackPlayer"
        const val DEFAULT_SAMPLE_RATE = 24000 // Standard Gemini Live output sample rate
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private var currentSampleRate: Int = DEFAULT_SAMPLE_RATE
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var playbackJob: Job? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Synchronized
    private fun ensureAudioTrack(sampleRate: Int) {
        if (audioTrack != null && currentSampleRate == sampleRate && audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
            return
        }

        releaseAudioTrack()
        currentSampleRate = sampleRate

        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            val bufferSize = maxOf(minBufferSize * 2, 8192)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormatObj = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()

            audioTrack = AudioTrack(
                audioAttributes,
                audioFormatObj,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.play()
                Log.d(TAG, "AudioTrack initialized successfully at $sampleRate Hz, bufferSize=$bufferSize")
            } else {
                Log.e(TAG, "Failed to initialize AudioTrack at $sampleRate Hz")
                onError("Failed to initialize AudioTrack output")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing AudioTrack", e)
            onError("AudioTrack error: ${e.message}")
        }
    }

    fun startPlaybackLoop() {
        if (playbackJob?.isActive == true) return

        playbackJob = scope.launch {
            while (isActive) {
                try {
                    val chunk = audioQueue.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk != null) {
                        if (!isPlaying) {
                            isPlaying = true
                            onPlaybackStarted()
                        }

                        ensureAudioTrack(currentSampleRate)

                        // Calculate RMS amplitude for speaking visualizer
                        val amp = calculateRmsAmplitude(chunk, chunk.size)
                        onPlaybackAmplitude(amp)

                        // Write PCM data to AudioTrack
                        val track = audioTrack
                        if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            var offset = 0
                            while (offset < chunk.size && isActive) {
                                val written = track.write(chunk, offset, chunk.size - offset)
                                if (written > 0) {
                                    offset += written
                                } else {
                                    Log.e(TAG, "AudioTrack.write returned $written")
                                    break
                                }
                            }
                        }
                    } else {
                        // Queue empty for 150ms
                        if (isPlaying && audioQueue.isEmpty()) {
                            isPlaying = false
                            onPlaybackAmplitude(0f)
                            onPlaybackEnded()
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in playback loop", e)
                }
            }
        }
    }

    fun queueAudio(pcmData: ByteArray, sampleRate: Int = DEFAULT_SAMPLE_RATE) {
        if (pcmData.isEmpty()) return
        if (currentSampleRate != sampleRate) {
            currentSampleRate = sampleRate
        }
        audioQueue.offer(pcmData)
        startPlaybackLoop()
    }

    fun interrupt() {
        Log.d(TAG, "Interrupting audio playback: clearing queue & flushing AudioTrack")
        audioQueue.clear()
        try {
            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    pause()
                    flush()
                    play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack", e)
        }
        isPlaying = false
        onPlaybackAmplitude(0f)
        onPlaybackEnded()
    }

    fun playDiagnosticTestTone(frequencyHz: Double = 440.0, durationSec: Double = 1.2) {
        Log.d(TAG, "Generating speaker test tone: $frequencyHz Hz for $durationSec s")
        interrupt()
        val sampleRate = DEFAULT_SAMPLE_RATE
        val numSamples = (sampleRate * durationSec).toInt()
        val buffer = ByteArray(numSamples * 2) // 16-bit = 2 bytes per sample

        val byteBuf = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            // Sine wave with smooth fade in / fade out to avoid clicks
            val envelope = when {
                i < 480 -> i / 480.0
                i > numSamples - 480 -> (numSamples - i) / 480.0
                else -> 1.0
            }
            val sampleVal = (sin(2.0 * PI * frequencyHz * t) * 32767.0 * 0.75 * envelope).toInt().toShort()
            byteBuf.putShort(sampleVal)
        }

        queueAudio(buffer, sampleRate)
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
        return (rms / 12000.0).toFloat().coerceIn(0f, 1f)
    }

    @Synchronized
    fun release() {
        Log.d(TAG, "Releasing AudioTrackPlayer")
        playbackJob?.cancel()
        playbackJob = null
        audioQueue.clear()
        releaseAudioTrack()
        isPlaying = false
    }

    private fun releaseAudioTrack() {
        try {
            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping/releasing AudioTrack", e)
        }
        audioTrack = null
    }
}
