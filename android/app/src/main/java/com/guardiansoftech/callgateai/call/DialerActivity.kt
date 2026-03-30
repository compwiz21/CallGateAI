package com.guardiansoftech.callgateai.call

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Stub dialer activity required for default dialer registration.
 * Just finishes immediately — we don't need a dial pad UI.
 */
class DialerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If launched with a tel: URI, the call is handled by our InCallService
        finish()
    }
}
