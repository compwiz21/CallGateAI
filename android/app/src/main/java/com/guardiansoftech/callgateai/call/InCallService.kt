package com.guardiansoftech.callgateai.call

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.content.Intent
import android.util.Log

/**
 * InCallService that takes over as the default dialer's call handler.
 * This gives us setAudioRoute() to control speakerphone programmatically.
 */
class CallInCallService : InCallService() {
    companion object {
        private const val TAG = "InCallService"
        var instance: CallInCallService? = null
            private set
        var currentCall: Call? = null
            private set
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        instance = this
        currentCall = call
        Log.d(TAG, "Call added: state=${call.state}")
        CallManager.debugLog.add("[${ts()}] InCall: call added state=${call.state}")

        call.registerCallback(callCallback)

        // Launch our in-call UI
        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)

        // Auto-enable speaker when call becomes active
        if (call.state == Call.STATE_ACTIVE) {
            enableSpeaker()
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        currentCall = null
        if (calls.isEmpty()) {
            instance = null
        }
        Log.d(TAG, "Call removed")
        CallManager.debugLog.add("[${ts()}] InCall: call removed")
    }

    fun enableSpeaker() {
        setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        CallManager.debugLog.add("[${ts()}] InCall: setAudioRoute(SPEAKER)")
    }

    fun disableSpeaker() {
        setAudioRoute(CallAudioState.ROUTE_EARPIECE)
    }

    fun answerCall() {
        currentCall?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
    }

    fun rejectCall() {
        currentCall?.reject(false, null)
    }

    fun endCall() {
        currentCall?.disconnect()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        val route = audioState?.route
        val isSpeaker = route == CallAudioState.ROUTE_SPEAKER
        CallManager.debugLog.add("[${ts()}] InCall: audioState route=$route isSpeaker=$isSpeaker")
        Log.d(TAG, "Audio state changed: route=$route isSpeaker=$isSpeaker")
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val stateName = when (state) {
                Call.STATE_DIALING -> "DIALING"
                Call.STATE_RINGING -> "RINGING"
                Call.STATE_ACTIVE -> "ACTIVE"
                Call.STATE_HOLDING -> "HOLDING"
                Call.STATE_DISCONNECTED -> "DISCONNECTED"
                else -> "UNKNOWN($state)"
            }
            Log.d(TAG, "Call state: $stateName")
            CallManager.debugLog.add("[${ts()}] InCall: callState=$stateName")

            // Map to CallManager states
            when (state) {
                Call.STATE_DIALING -> CallManager.notifyFromInCall(CallManager.CallState.DIALING)
                Call.STATE_RINGING -> CallManager.notifyFromInCall(CallManager.CallState.RINGING)
                Call.STATE_ACTIVE -> {
                    CallManager.notifyFromInCall(CallManager.CallState.ACTIVE)
                    // Enable speaker immediately when call goes active
                    enableSpeaker()
                }
                Call.STATE_DISCONNECTED -> {
                    CallManager.notifyFromInCall(CallManager.CallState.DISCONNECTED)
                }
            }

            // Update InCallActivity if it's showing
            InCallActivity.instance?.updateCallState(stateName)
        }
    }

    private fun ts(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
}
