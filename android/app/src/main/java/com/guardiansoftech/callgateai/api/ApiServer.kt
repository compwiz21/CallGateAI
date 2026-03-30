package com.guardiansoftech.callgateai.api

import android.content.Context
import android.util.Base64
import android.util.Log
import com.guardiansoftech.callgateai.call.CallManager
import com.guardiansoftech.callgateai.settings.SettingsManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Embedded HTTP server exposing REST API for call control and settings.
 * Drop-in replacement for CallGate with added AI control.
 */
class ApiServer(
    private val context: Context,
    port: Int = 8084
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ApiServer"
    }

    private val settingsManager = SettingsManager(context)

    var onStartAI: (() -> Unit)? = null
    var onStopAI: (() -> Unit)? = null

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "$method $uri")

        // CORS preflight
        if (method == Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, ""))
        }

        val response = try {
            route(method, uri, session)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method $uri", e)
            json(Response.Status.INTERNAL_ERROR, """{"error":"${e.message}"}""")
        }

        return cors(response)
    }

    private fun route(method: Method, uri: String, session: IHTTPSession): Response {
        return when {
            // Status
            uri == "/api/v1/status" && method == Method.GET -> {
                json(Response.Status.OK, JSONObject().apply {
                    put("app", "CallGateAI")
                    put("version", "1.0.0")
                    put("callState", CallManager.callState.name)
                    put("hasOpenAiKey", settingsManager.getOpenAiKey() != null)
                    put("aiActive", false) // TODO: track this
                }.toString())
            }

            // Place call (CallGate-compatible)
            uri == "/api/v1/calls" && method == Method.POST -> {
                val body = readBody(session)
                val phoneNumber = body.optJSONObject("call")?.optString("phoneNumber")
                    ?: body.optString("phoneNumber", "")

                if (phoneNumber.isBlank()) {
                    json(Response.Status.BAD_REQUEST, """{"error":"Phone number required"}""")
                } else {
                    val success = CallManager.placeCall(context, phoneNumber)
                    if (success) {
                        json(Response.Status.OK, """{"message":"Call initiated"}""")
                    } else {
                        json(Response.Status.INTERNAL_ERROR, """{"error":"Failed to place call"}""")
                    }
                }
            }

            // End call
            uri == "/api/v1/calls" && method == Method.DELETE -> {
                val success = CallManager.endCall(context)
                if (success) {
                    newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
                } else {
                    json(Response.Status.NOT_FOUND, """{"error":"No active call"}""")
                }
            }

            // Get settings (masked)
            uri == "/api/v1/settings" && method == Method.GET -> {
                val settings = settingsManager.load()
                json(Response.Status.OK, JSONObject().apply {
                    put("api_username", settings.optString("api_username", "admin"))
                    put("has_api_password", settings.has("api_password"))
                    put("has_openai_key", settings.has("openai_api_key"))
                    put("auto_ai", settings.optBoolean("auto_ai", true))
                    put("ai_instructions", settings.optString("ai_instructions", ""))
                    put("webhook_url", settings.optString("webhook_url", ""))
                }.toString())
            }

            // Update settings
            uri == "/api/v1/settings" && method == Method.POST -> {
                val body = readBody(session)
                Log.d(TAG, "Settings update received: ${body.keys().asSequence().toList()}")

                val current = settingsManager.load()

                // Update each field if present
                if (body.has("api_username")) current.put("api_username", body.getString("api_username"))
                if (body.has("api_password")) current.put("api_password", body.getString("api_password"))
                if (body.has("openai_api_key")) current.put("openai_api_key", body.getString("openai_api_key"))
                if (body.has("ai_instructions")) current.put("ai_instructions", body.getString("ai_instructions"))
                if (body.has("webhook_url")) current.put("webhook_url", body.getString("webhook_url"))
                if (body.has("auto_ai")) current.put("auto_ai", body.getBoolean("auto_ai"))

                settingsManager.save(current)
                Log.d(TAG, "Settings saved. Has OpenAI key: ${current.has("openai_api_key")}")

                json(Response.Status.OK, """{"ok":true,"message":"Settings saved"}""")
            }

            // Start AI
            uri == "/api/v1/ai/start" && method == Method.POST -> {
                if (settingsManager.getOpenAiKey() == null) {
                    json(Response.Status.BAD_REQUEST, """{"error":"OpenAI API key not configured"}""")
                } else {
                    onStartAI?.invoke()
                    json(Response.Status.OK, """{"message":"AI started"}""")
                }
            }

            // Stop AI
            uri == "/api/v1/ai/stop" && method == Method.POST -> {
                onStopAI?.invoke()
                json(Response.Status.OK, """{"message":"AI stopped"}""")
            }

            // Debug log
            uri == "/api/v1/debug" && method == Method.GET -> {
                val logs = CallManager.debugLog.joinToString("\n")
                json(Response.Status.OK, JSONObject().apply {
                    put("callState", CallManager.callState.name)
                    put("currentNumber", CallManager.currentNumber ?: "")
                    put("logCount", CallManager.debugLog.size)
                    put("log", logs)
                }.toString())
            }

            else -> {
                json(Response.Status.NOT_FOUND, """{"error":"Not found: $method $uri"}""")
            }
        }
    }

    private fun readBody(session: IHTTPSession): JSONObject {
        return try {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength == 0) return JSONObject()

            val buffer = ByteArray(contentLength)
            session.inputStream.read(buffer, 0, contentLength)
            val bodyStr = String(buffer)
            Log.d(TAG, "Request body: $bodyStr")
            JSONObject(bodyStr)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading body", e)
            JSONObject()
        }
    }

    private fun json(status: Response.Status, body: String): Response {
        return newFixedLengthResponse(status, "application/json", body)
    }

    private fun cors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return response
    }
}
