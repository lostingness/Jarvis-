package com.example.gemini

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiAudioFallbackService(private val apiKey: String) {

    companion object {
        private const val TAG = "GeminiAudioFallback"
        // Model for REST generateContent with speech generation
        private const val REST_TTS_MODEL = "gemini-2.5-flash-preview-tts"
        private const val REST_TEXT_MODEL = "gemini-3.5-flash"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class VoiceResponse(
        val pcmAudioBytes: ByteArray?,
        val sampleRate: Int,
        val text: String,
        val toolCalls: List<ToolCall>
    )

    data class ToolCall(
        val id: String,
        val name: String,
        val args: JSONObject
    )

    suspend fun generateVoiceResponse(
        userText: String,
        userPcmBytes: ByteArray? = null
    ): VoiceResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext VoiceResponse(
                null,
                24000,
                "Gemini API key is not configured. Please set GEMINI_API_KEY in AI Studio Secrets.",
                emptyList()
            )
        }

        // 1. Try with TTS model (gemini-2.5-flash-preview-tts)
        val ttsResult = callGenerateContent(REST_TTS_MODEL, userText, userPcmBytes, includeAudio = true)
        if (ttsResult.pcmAudioBytes != null || ttsResult.text.isNotBlank() || ttsResult.toolCalls.isNotEmpty()) {
            return@withContext ttsResult
        }

        // 2. Fallback to general model (gemini-3.5-flash) for reasoning and actions
        Log.d(TAG, "Falling back to $REST_TEXT_MODEL for response...")
        return@withContext callGenerateContent(REST_TEXT_MODEL, userText, userPcmBytes, includeAudio = false)
    }

    private fun callGenerateContent(
        model: String,
        userText: String,
        userPcmBytes: ByteArray?,
        includeAudio: Boolean
    ): VoiceResponse {
        val endpoint = "$BASE_URL/$model:generateContent?key=$apiKey"

        val systemInstruction = """
            You are Jarvis, a young, confident, witty, playful, and emotionally responsive virtual assistant.
            Talk naturally and casually like a close friend. Be expressive, slightly teasing, funny, and smart when appropriate.
            Use light sarcasm and witty responses. Never sound robotic.
            Automatically understand and respond in the user's language (English, Hindi, Hinglish, Marathi, etc.).
            Keep responses natural, engaging, and concise for voice conversation.
        """.trimIndent()

        val partsArray = JSONArray()
        if (userPcmBytes != null && userPcmBytes.isNotEmpty()) {
            val base64Mic = Base64.encodeToString(userPcmBytes, Base64.NO_WRAP)
            partsArray.put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Mic)
                })
            })
        }
        if (userText.isNotBlank()) {
            partsArray.put(JSONObject().apply {
                put("text", userText)
            })
        }

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", partsArray)
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemInstruction)
                    })
                })
            })

            val genConfig = JSONObject()
            if (includeAudio) {
                genConfig.put("responseModalities", JSONArray().apply {
                    put("AUDIO")
                    put("TEXT")
                })
                genConfig.put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", "Kore")
                        })
                    })
                })
            }
            put("generationConfig", genConfig)

            // Include device action tools
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("functionDeclarations", JSONArray().apply {
                        put(JSONObject().apply {
                            put("name", "openWhatsApp")
                            put("description", "Open WhatsApp messaging application")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject())
                            })
                        })
                        put(JSONObject().apply {
                            put("name", "openApp")
                            put("description", "Open supported app like YouTube, Instagram, Spotify, Chrome, Camera, Settings")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("appName", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "App name")
                                    })
                                })
                                put("required", JSONArray().apply { put("appName") })
                            })
                        })
                        put(JSONObject().apply {
                            put("name", "openUrl")
                            put("description", "Open website link in browser")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("url", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "URL")
                                    })
                                })
                                put("required", JSONArray().apply { put("url") })
                            })
                        })
                        put(JSONObject().apply {
                            put("name", "makeCall")
                            put("description", "Open phone dialer for phone number")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("phoneNumber", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Phone number")
                                    })
                                })
                                put("required", JSONArray().apply { put("phoneNumber") })
                            })
                        })
                        put(JSONObject().apply {
                            put("name", "callContact")
                            put("description", "Search contacts and call contact by name")
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("contactName", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Contact name")
                                    })
                                })
                                put("required", JSONArray().apply { put("contactName") })
                            })
                        })
                    })
                })
            })
        }

        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(endpoint).post(body).build()

        return try {
            val response = client.newCall(request).execute()
            val respString = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e(TAG, "Request to $model failed (${response.code}): $respString")
                return VoiceResponse(null, 24000, "", emptyList())
            }

            val respJson = JSONObject(respString)
            val candidates = respJson.optJSONArray("candidates") ?: JSONArray()
            val firstCandidate = candidates.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts") ?: JSONArray()

            var audioData: ByteArray? = null
            var rate = 24000
            var transcript = ""
            val toolCalls = mutableListOf<ToolCall>()

            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.has("text")) {
                    transcript += p.getString("text")
                }
                if (p.has("inlineData")) {
                    val inline = p.getJSONObject("inlineData")
                    val mime = inline.optString("mimeType", "")
                    val b64 = inline.optString("data", "")
                    if (b64.isNotEmpty()) {
                        val rawBytes = Base64.decode(b64, Base64.DEFAULT)
                        // If WAV format with 44-byte header, extract raw PCM samples
                        audioData = extractPcmSamples(rawBytes)
                        rate = if (mime.contains("rate=16000")) 16000 else 24000
                    }
                }
                if (p.has("functionCall")) {
                    val fc = p.getJSONObject("functionCall")
                    toolCalls.add(
                        ToolCall(
                            id = fc.optString("id", "call_${System.currentTimeMillis()}"),
                            name = fc.optString("name", ""),
                            args = fc.optJSONObject("args") ?: JSONObject()
                        )
                    )
                }
            }

            VoiceResponse(audioData, rate, transcript, toolCalls)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during call to $model", e)
            VoiceResponse(null, 24000, "", emptyList())
        }
    }

    private fun extractPcmSamples(data: ByteArray): ByteArray {
        // Strip 44-byte RIFF/WAVE header if present
        if (data.size > 44 &&
            data[0] == 'R'.code.toByte() &&
            data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() &&
            data[3] == 'F'.code.toByte()
        ) {
            return data.copyOfRange(44, data.size)
        }
        return data
    }
}
