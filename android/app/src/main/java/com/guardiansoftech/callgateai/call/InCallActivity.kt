package com.guardiansoftech.callgateai.call

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.guardiansoftech.callgateai.R

/**
 * Minimal in-call UI shown during active calls.
 * Required because our app is the default dialer.
 */
class InCallActivity : AppCompatActivity() {
    companion object {
        var instance: InCallActivity? = null
            private set
    }

    private lateinit var statusText: TextView
    private lateinit var callerText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        setContentView(R.layout.activity_in_call)

        statusText = findViewById(R.id.inCallStatus)
        callerText = findViewById(R.id.inCallCaller)

        val number = CallManager.currentNumber ?: "Unknown"
        callerText.text = number

        // Answer button (for incoming calls)
        findViewById<Button>(R.id.btnAnswer).setOnClickListener {
            CallInCallService.instance?.answerCall()
        }

        // Reject button
        findViewById<Button>(R.id.btnReject).setOnClickListener {
            CallInCallService.instance?.rejectCall()
            finish()
        }

        // End call button
        findViewById<Button>(R.id.btnEndCall).setOnClickListener {
            CallInCallService.instance?.endCall()
            finish()
        }

        // Speaker toggle
        findViewById<Button>(R.id.btnSpeaker).setOnClickListener {
            CallInCallService.instance?.enableSpeaker()
        }

        updateCallState(CallInCallService.currentCall?.state?.let {
            when (it) {
                android.telecom.Call.STATE_DIALING -> "DIALING"
                android.telecom.Call.STATE_RINGING -> "RINGING"
                android.telecom.Call.STATE_ACTIVE -> "ACTIVE"
                else -> "CONNECTING"
            }
        } ?: "CONNECTING")
    }

    fun updateCallState(state: String) {
        runOnUiThread {
            statusText.text = state
            // Hide answer/reject for outgoing calls
            if (state == "ACTIVE" || state == "DIALING") {
                findViewById<Button>(R.id.btnAnswer).visibility = android.view.View.GONE
                findViewById<Button>(R.id.btnReject).visibility = android.view.View.GONE
            }
            if (state == "DISCONNECTED") {
                finish()
            }
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
