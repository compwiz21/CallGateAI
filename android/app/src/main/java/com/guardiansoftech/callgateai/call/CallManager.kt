package com.guardiansoftech.callgateai.call

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Manages calls using standard Android telephony.
 * Places calls via Intent.ACTION_CALL, monitors state via TelephonyCallback.
 * When a call becomes active, enables speakerphone and signals the audio bridge to start.
 */
object CallManager {
    private const val TAG = "CallManager"

    var callState: CallState = CallState.IDLE
    var currentNumber: String? = null
    val debugLog = mutableListOf<String>()

    private val listeners = mutableListOf<CallStateListener>()

    enum class CallState {
        IDLE, DIALING, RINGING, ACTIVE, DISCONNECTED
    }

    interface CallStateListener {
        fun onCallStateChanged(state: CallState, phoneNumber: String?)
    }

    fun addListener(listener: CallStateListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: CallStateListener) {
        listeners.remove(listener)
    }

    private fun debug(msg: String) {
        Log.d(TAG, msg)
        debugLog.add("[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $msg")
        if (debugLog.size > 50) debugLog.removeAt(0)
    }

    private fun notifyStateChanged(state: CallState, phoneNumber: String? = null) {
        callState = state
        if (phoneNumber != null) currentNumber = phoneNumber
        debug("State -> $state, number: ${currentNumber}")
        listeners.forEach { it.onCallStateChanged(state, currentNumber) }
    }

    /**
     * Register for telephony state changes to detect when calls connect/disconnect.
     */
    fun registerCallStateListener(context: Context) {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+
                telephonyManager.registerTelephonyCallback(
                    context.mainExecutor,
                    object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            debug("TelephonyCallback state: $state")
                            handleCallState(context, state)
                        }
                    }
                )
                debug("TelephonyCallback registered (Android 12+)")
            } else {
                @Suppress("DEPRECATION")
                telephonyManager.listen(
                    object : PhoneStateListener() {
                        @Deprecated("Deprecated in API 31")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            debug("PhoneStateListener state: $state, number: $phoneNumber")
                            if (phoneNumber != null) currentNumber = phoneNumber
                            handleCallState(context, state)
                        }
                    },
                    PhoneStateListener.LISTEN_CALL_STATE
                )
                debug("PhoneStateListener registered (legacy)")
            }
        } catch (e: SecurityException) {
            debug("Permission denied for call state listener: ${e.message}")
        } catch (e: Exception) {
            debug("Failed to register call state listener: ${e.message}")
        }

        // Always run polling as a safety net alongside the callback
        startCallStatePolling(context, telephonyManager)
    }

    /**
     * Fallback: poll call state every second if TelephonyCallback fails.
     */
    private var pollingHandler: android.os.Handler? = null
    private fun startCallStatePolling(context: Context, telephonyManager: TelephonyManager) {
        debug("Starting call state polling fallback")
        pollingHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var lastState = TelephonyManager.CALL_STATE_IDLE

        val runnable = object : Runnable {
            override fun run() {
                try {
                    val currentState = telephonyManager.callState
                    if (currentState != lastState) {
                        debug("Poll detected state change: $lastState -> $currentState")
                        lastState = currentState
                        handleCallState(context, currentState)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error", e)
                }
                pollingHandler?.postDelayed(this, 1000)
            }
        }
        pollingHandler?.post(runnable)
    }

    private fun handleCallState(context: Context, state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                if (callState != CallState.IDLE) {
                    notifyStateChanged(CallState.DISCONNECTED, currentNumber)
                    // Reset after a moment
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        notifyStateChanged(CallState.IDLE)
                    }, 2000)
                }
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                notifyStateChanged(CallState.RINGING, currentNumber)
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                notifyStateChanged(CallState.ACTIVE, currentNumber)
            }
        }
    }

    /**
     * Place a call using standard Android Intent.
     */
    fun placeCall(context: Context, phoneNumber: String): Boolean {
        return try {
            currentNumber = phoneNumber
            notifyStateChanged(CallState.DIALING, phoneNumber)

            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.d(TAG, "Call intent sent to $phoneNumber")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "CALL_PHONE permission not granted", e)
            notifyStateChanged(CallState.IDLE)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place call", e)
            notifyStateChanged(CallState.IDLE)
            false
        }
    }

    /**
     * End call — uses TelecomManager on API 28+
     */
    fun endCall(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE)
                    as android.telecom.TelecomManager
                telecomManager.endCall()
            } else {
                // Older devices — use ITelephony reflection
                val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE)
                    as TelephonyManager
                val method = telephonyManager.javaClass.getDeclaredMethod("endCall")
                method.invoke(telephonyManager) as Boolean
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to end call", e)
            false
        }
    }

    private fun enableSpeakerphone(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_CALL
            audioManager.isSpeakerphoneOn = true
            Log.d(TAG, "Speakerphone enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable speakerphone", e)
        }
    }
}
