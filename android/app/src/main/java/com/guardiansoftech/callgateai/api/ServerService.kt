package com.guardiansoftech.callgateai.api

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.guardiansoftech.callgateai.MainActivity
import com.guardiansoftech.callgateai.ai.RealtimeClient
import com.guardiansoftech.callgateai.audio.AudioBridge
import com.guardiansoftech.callgateai.call.CallManager
import com.guardiansoftech.callgateai.settings.SettingsManager

/**
 * Foreground service that runs the API server and manages the audio/AI bridge.
 * When a call becomes active, it automatically starts the AI voice bridge.
 */
class ServerService : Service() {
    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "callgateai_server"
        private const val NOTIFICATION_ID = 1
        const val PORT = 8084
    }

    private var apiServer: ApiServer? = null
    private var audioBridge: AudioBridge? = null
    private var realtimeClient: RealtimeClient? = null
    private lateinit var settingsManager: SettingsManager
    private var aiActive = false

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))

        // Register for call state changes
        try {
            CallManager.registerCallStateListener(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register call listener (permission?)", e)
        }

        // Listen for call state changes
        CallManager.addListener(object : CallManager.CallStateListener {
            override fun onCallStateChanged(state: CallManager.CallState, phoneNumber: String?) {
                val text = when (state) {
                    CallManager.CallState.IDLE -> "Server running on port $PORT"
                    CallManager.CallState.DIALING -> "Dialing $phoneNumber..."
                    CallManager.CallState.RINGING -> "Ringing: $phoneNumber"
                    CallManager.CallState.ACTIVE -> {
                        // Auto-start AI when call connects
                        val settings = settingsManager.load()
                        if (settings.optBoolean("auto_ai", true) && !aiActive) {
                            Log.d(TAG, "Call active — auto-starting AI")
                            startAI()
                        }
                        "Active call: $phoneNumber" + if (aiActive) " [AI]" else ""
                    }
                    CallManager.CallState.DISCONNECTED -> {
                        stopAI()
                        "Call ended"
                    }
                }
                updateNotification(text)
                sendWebhook(state, phoneNumber)
            }
        })

        // Start API server
        startApiServer()
    }

    private fun startApiServer() {
        try {
            apiServer = ApiServer(this, PORT).apply {
                onStartAI = { startAI() }
                onStopAI = { stopAI() }
                start()
            }
            Log.d(TAG, "API server started on port $PORT")
            updateNotification("Server running on port $PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            updateNotification("Server failed: ${e.message}")
        }
    }

    fun startAI() {
        if (aiActive) return

        val apiKey = settingsManager.getOpenAiKey()
        if (apiKey == null) {
            Log.e(TAG, "Cannot start AI — no OpenAI API key configured")
            return
        }

        Log.d(TAG, "Starting AI voice bridge")

        // Create audio bridge
        audioBridge = AudioBridge(this).apply {
            onAudioCaptured = { base64Audio ->
                realtimeClient?.sendAudio(base64Audio)
            }
        }

        // Create realtime client
        realtimeClient = RealtimeClient().apply {
            onAudioDelta = { base64Audio ->
                audioBridge?.enqueueAudio(base64Audio)
            }
            onAudioDone = {
                Log.d(TAG, "AI finished speaking")
            }
            onTranscript = { role, text ->
                Log.d(TAG, "[$role] $text")
            }
            onStateChanged = { state ->
                Log.d(TAG, "AI state: $state")
                if (state == "connected") {
                    // Start audio capture/playback
                    audioBridge?.start()
                    // Trigger AI to greet the caller in English
                    realtimeClient?.triggerResponse(
                        "Greet the caller warmly in English. Say hello and ask how you can help them today."
                    )
                }
            }
        }

        val instructions = settingsManager.getAiInstructions()
        realtimeClient?.connect(apiKey, instructions)
        aiActive = true
        Log.d(TAG, "AI bridge started")
    }

    fun stopAI() {
        if (!aiActive) return
        Log.d(TAG, "Stopping AI voice bridge")

        audioBridge?.stop()
        audioBridge = null
        realtimeClient?.disconnect()
        realtimeClient = null
        aiActive = false
    }

    private fun sendWebhook(state: CallManager.CallState, phoneNumber: String?) {
        val url = settingsManager.getWebhookUrl() ?: return

        val event = when (state) {
            CallManager.CallState.RINGING -> "call:ringing"
            CallManager.CallState.ACTIVE -> "call:started"
            CallManager.CallState.DISCONNECTED -> "call:ended"
            else -> return
        }

        Thread {
            try {
                val payload = org.json.JSONObject().apply {
                    put("event", event)
                    put("payload", org.json.JSONObject().apply {
                        put("phoneNumber", phoneNumber ?: "")
                    })
                }
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.outputStream.write(payload.toString().toByteArray())
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Webhook failed", e)
            }
        }.start()
    }

    // --- Notification ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "CallGate AI Server", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows when the CallGate AI server is running" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CallGate AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAI()
        apiServer?.stop()
        super.onDestroy()
    }
}
