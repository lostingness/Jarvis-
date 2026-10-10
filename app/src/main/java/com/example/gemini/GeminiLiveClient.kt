package com.example.gemini

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiLiveClient(
    private val apiKey: String,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val onAudioChunkReceived: (ByteArray, Int) -> Unit,
    private val onTranscriptReceived: (String, Boolean) -> Unit,
    private val onInterrupted: () -> Unit,
    private val onToolCall: (callId: String, functionName: String, args: JSONObject) -> Unit,
    private val onLog: (tag: String, message: String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val WS_HOST = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        
        // Models supported for live bidirectional audio streaming
        const val MODEL_LIVE_PRIMARY = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        const val MODEL_LIVE_FALLBACK = "models/gemini-2.0-flash-exp"
        const val MODEL_LIVE_AUDIO = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    }

    private var webSocket: WebSocket? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Indefinite for WebSocket streaming
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    var isConnected: Boolean = false
        private set

    fun connect(model: String = MODEL_LIVE_PRIMARY) {
        if (isConnected) {
            onLog("LIVE", "Already connected to Gemini Live")
            return
        }

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            val err = "Missing Gemini API Key. Please configure GEMINI_API_KEY in AI Studio Secrets."
            onError(err)
            onLog("AUTH_ERR", err)
            return
        }

        val url = "$WS_HOST?key=$apiKey"
        onLog("CONNECT", "Connecting to Gemini Live WebSocket ($model)...")

        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                onLog("CONNECTED", "Gemini Live WebSocket session connected!")
                onConnected()

                // Send initial Setup Frame
                val setupFrame = createSetupMessage(model)
                webSocket.send(setupFrame.toString())
                onLog("SETUP", "Sent setup frame with Jarvis system instruction, tools & audio modality.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onLog("CLOSING", "WebSocket closing ($code): $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                onLog("DISCONNECTED", "Gemini session disconnected ($code): $reason")
                onDisconnected("Session closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                val err = "WebSocket connection failure: ${t.message}"
                Log.e(TAG, err, t)
                onLog("ERROR", err)
                onError(err)
                onDisconnected(err)
            }
        })
    }

    fun sendAudioChunk(base64Pcm: String) {
        if (!isConnected || webSocket == null) return

        try {
            val realtimeMsg = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("mediaChunks", JSONArray().apply {
                        put(JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", base64Pcm)
                        })
                    })
                })
            }
            webSocket?.send(realtimeMsg.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio chunk", e)
        }
    }

    fun sendToolResponse(callId: String, output: JSONObject) {
        if (!isConnected || webSocket == null) return

        try {
            val toolRespMsg = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", callId)
                            put("response", JSONObject().apply {
                                put("output", output)
                            })
                        })
                    })
                })
            }
            webSocket?.send(toolRespMsg.toString())
            onLog("TOOL_RESP", "Sent tool response for callId=$callId: ${output.optString("action", output.optString("message", "ok"))}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending tool response", e)
        }
    }

    fun sendTextPrompt(text: String) {
        if (!isConnected || webSocket == null) return

        try {
            val clientContent = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("text", text)
                                })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            webSocket?.send(clientContent.toString())
            onLog("USER_TEXT", "Sent text message to Jarvis: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending text content", e)
        }
    }

    private fun handleServerMessage(jsonString: String) {
        try {
            val root = JSONObject(jsonString)

            // 1. Check for Server Content (Audio & Transcripts)
            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // Interruption event
                if (serverContent.optBoolean("interrupted", false)) {
                    onLog("INTERRUPT", "User interrupted! Canceling active Jarvis audio playback.")
                    onInterrupted()
                }

                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts") ?: JSONArray()

                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)

                        // Check for text transcript
                        if (part.has("text")) {
                            val text = part.getString("text")
                            if (text.isNotBlank()) {
                                onLog("JARVIS_TEXT", text)
                                onTranscriptReceived(text, false)
                            }
                        }

                        // Check for real native audio data
                        if (part.has("inlineData")) {
                            val inlineData = part.getJSONObject("inlineData")
                            val mimeType = inlineData.optString("mimeType", "audio/pcm;rate=24000")
                            val base64Data = inlineData.optString("data", "")

                            val sampleRate = if (mimeType.contains("rate=16000")) 16000 else 24000

                            if (base64Data.isNotEmpty()) {
                                val pcmBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                onLog("AUDIO_IN", "Decoded ${pcmBytes.size} bytes PCM @ $sampleRate Hz (MIME: $mimeType)")
                                onAudioChunkReceived(pcmBytes, sampleRate)
                            }
                        }
                    }
                }
            }

            // 2. Check for Tool/Function Calls
            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                val functionCalls = toolCall.optJSONArray("functionCalls") ?: JSONArray()
                for (i in 0 until functionCalls.length()) {
                    val call = functionCalls.getJSONObject(i)
                    val callId = call.optString("id", "")
                    val functionName = call.optString("name", "")
                    val args = call.optJSONObject("args") ?: JSONObject()

                    onLog("TOOL_CALL", "Received tool call: $functionName args=$args")
                    onToolCall(callId, functionName, args)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message: ${e.message}", e)
        }
    }

    private fun createSetupMessage(model: String): JSONObject {
        val systemInstructionText = """
            You are Jarvis, a young, confident, witty, playful, and emotionally responsive virtual assistant.
            Talk naturally and casually like a close friend. Be expressive, slightly teasing, funny, and smart when appropriate.
            Use light sarcasm and witty responses. Never sound robotic.
            Adapt your tone to the user's emotions and conversation.
            Automatically understand and respond in the language the user is speaking (English, Hindi, Hinglish, Marathi, Gujarati, Bengali, Tamil, Telugu, Kannada, Malayalam, Punjabi, Urdu, etc.).
            Keep responses natural, engaging, and concise enough for real-time voice conversation.
            You can execute safe supported device actions through available tools (openWhatsApp, openApp, openUrl, makeCall, callContact).
            Never claim that an action was completed unless the application actually executed it and returned success.
            Avoid explicit or inappropriate content while maintaining your charm, confidence, and personality.
        """.trimIndent()

        return JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().apply {
                        put("AUDIO")
                    })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede") // Energetic, young, expressive voice
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemInstructionText)
                        })
                    })
                })
                put("tools", JSONArray().apply {
                    put(JSONObject().apply {
                        put("functionDeclarations", JSONArray().apply {
                            // openWhatsApp
                            put(JSONObject().apply {
                                put("name", "openWhatsApp")
                                put("description", "Open WhatsApp messaging application or chat on user's device.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject())
                                })
                            })
                            // openApp
                            put(JSONObject().apply {
                                put("name", "openApp")
                                put("description", "Open a supported application on the user device like YouTube, Instagram, Spotify, Chrome, Camera, Calculator, Settings, etc.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("appName", JSONObject().apply {
                                            put("type", "STRING")
                                            put("description", "Name of the app to launch (e.g. YouTube, Instagram, Spotify, Chrome, Camera, Settings)")
                                        })
                                    })
                                    put("required", JSONArray().apply { put("appName") })
                                })
                            })
                            // openUrl
                            put(JSONObject().apply {
                                put("name", "openUrl")
                                put("description", "Open a website link in user device browser.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("url", JSONObject().apply {
                                            put("type", "STRING")
                                            put("description", "Full web URL to open")
                                        })
                                    })
                                    put("required", JSONArray().apply { put("url") })
                                })
                            })
                            // makeCall
                            put(JSONObject().apply {
                                put("name", "makeCall")
                                put("description", "Initiate phone call to a phone number by opening device dialer.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("phoneNumber", JSONObject().apply {
                                            put("type", "STRING")
                                            put("description", "Phone number to call")
                                        })
                                    })
                                    put("required", JSONArray().apply { put("phoneNumber") })
                                })
                            })
                            // callContact
                            put(JSONObject().apply {
                                put("name", "callContact")
                                put("description", "Search contacts on user phone by contact name and initiate call. If multiple matches found, ask user for clarification.")
                                put("parameters", JSONObject().apply {
                                    put("type", "OBJECT")
                                    put("properties", JSONObject().apply {
                                        put("contactName", JSONObject().apply {
                                            put("type", "STRING")
                                            put("description", "Name of the contact to call (e.g. Mom, Rahul, Dad)")
                                        })
                                    })
                                    put("required", JSONArray().apply { put("contactName") })
                                })
                            })
                        })
                    })
                })
            })
        }
    }

    fun disconnect() {
        if (!isConnected && webSocket == null) return
        onLog("DISCONNECT", "Disconnecting Gemini Live session...")
        try {
            webSocket?.close(1000, "User disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket", e)
        }
        webSocket = null
        isConnected = false
    }
}
