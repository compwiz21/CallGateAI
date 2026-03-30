package com.guardiansoftech.callgateai.ai

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for OpenAI Realtime API.
 *
 * Sends caller's audio (PCM16 base64) to the AI and receives AI audio responses.
 * Uses the WebSocket transport (not WebRTC) for maximum audio control.
 */
class RealtimeClient {
    companion object {
        private const val TAG = "RealtimeClient"
        private const val REALTIME_URL = "wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview"
    }

    private var webSocket: WebSocket? = null
    private var isConnected = false

    // Callbacks
    var onAudioDelta: ((String) -> Unit)? = null     // base64 PCM audio from AI
    var onTranscript: ((String, String) -> Unit)? = null // (role, text)
    var onStateChanged: ((String) -> Unit)? = null    // "connected", "disconnected", "error"
    var onAudioDone: (() -> Unit)? = null             // AI finished speaking

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(apiKey: String, systemInstructions: String = DEFAULT_INSTRUCTIONS) {
        if (isConnected) {
            Log.w(TAG, "Already connected")
            return
        }

        val request = Request.Builder()
            .url(REALTIME_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isConnected = true
                onStateChanged?.invoke("connected")

                // Configure the session
                val sessionConfig = JSONObject().apply {
                    put("type", "session.update")
                    put("session", JSONObject().apply {
                        put("modalities", org.json.JSONArray().apply {
                            put("audio")
                            put("text")
                        })
                        put("instructions", systemInstructions)
                        put("voice", "alloy")
                        put("input_audio_format", "pcm16")
                        put("output_audio_format", "pcm16")
                        put("input_audio_transcription", JSONObject().apply {
                            put("model", "whisper-1")
                            put("language", "en")
                        })
                        put("turn_detection", JSONObject().apply {
                            put("type", "server_vad")
                            put("threshold", 0.5)
                            put("prefix_padding_ms", 300)
                            put("silence_duration_ms", 500)
                        })
                    })
                }
                webSocket.send(sessionConfig.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleEvent(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                isConnected = false
                onStateChanged?.invoke("disconnected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
                isConnected = false
                onStateChanged?.invoke("error: ${t.message}")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected = false
    }

    /**
     * Send captured audio from the caller to the AI.
     * @param base64Audio Base64-encoded PCM16 24kHz mono audio
     */
    fun sendAudio(base64Audio: String) {
        if (!isConnected) return

        val event = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64Audio)
        }
        webSocket?.send(event.toString())
    }

    /**
     * Trigger the AI to respond (used for initial greeting).
     */
    fun triggerResponse(instructions: String? = null) {
        if (!isConnected) return

        val event = JSONObject().apply {
            put("type", "response.create")
            if (instructions != null) {
                put("response", JSONObject().apply {
                    put("instructions", instructions)
                })
            }
        }
        webSocket?.send(event.toString())
    }

    /**
     * Cancel the current AI response (e.g., when caller interrupts).
     */
    fun cancelResponse() {
        if (!isConnected) return

        val event = JSONObject().apply {
            put("type", "response.cancel")
        }
        webSocket?.send(event.toString())
    }

    private fun handleEvent(text: String) {
        try {
            val event = JSONObject(text)
            when (event.getString("type")) {
                // AI audio chunk
                "response.audio.delta" -> {
                    val delta = event.getString("delta")
                    onAudioDelta?.invoke(delta)
                }

                // AI finished speaking
                "response.audio.done" -> {
                    onAudioDone?.invoke()
                }

                // AI transcript
                "response.audio_transcript.delta" -> {
                    val delta = event.getString("delta")
                    onTranscript?.invoke("ai", delta)
                }

                // User transcript
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = event.getString("transcript")
                    onTranscript?.invoke("user", transcript)
                }

                // VAD detected speech start — interrupt AI
                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "User started speaking — interrupting AI")
                }

                // Session created
                "session.created" -> {
                    Log.d(TAG, "Session created")
                }

                // Session updated
                "session.updated" -> {
                    Log.d(TAG, "Session configured")
                }

                // Error
                "error" -> {
                    val error = event.getJSONObject("error")
                    Log.e(TAG, "API error: ${error.getString("message")}")
                    onStateChanged?.invoke("error: ${error.getString("message")}")
                }

                else -> {
                    Log.v(TAG, "Event: ${event.getString("type")}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling event", e)
        }
    }

    fun isConnected(): Boolean = isConnected
}

private const val DEFAULT_INSTRUCTIONS = """
You are a friendly AI phone assistant. You MUST ONLY speak English. Never switch languages.
Even if you hear unclear audio, static, or what sounds like another language, ALWAYS respond in English.
Keep responses short and conversational. Greet callers warmly. No markdown or formatting.
"""
