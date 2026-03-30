package com.guardiansoftech.callgateai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.guardiansoftech.callgateai.api.ServerService
import com.guardiansoftech.callgateai.call.CallManager
import com.guardiansoftech.callgateai.settings.SettingsManager
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 100
    }

    private lateinit var settingsManager: SettingsManager
    private var serverRunning = false

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var callStateText: TextView
    private lateinit var toggleServerBtn: Button
    private lateinit var openAiKeyInput: EditText
    private lateinit var apiPasswordInput: EditText
    private lateinit var saveSettingsBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settingsManager = SettingsManager(this)

        statusText = findViewById(R.id.statusText)
        ipText = findViewById(R.id.ipText)
        callStateText = findViewById(R.id.callStateText)
        toggleServerBtn = findViewById(R.id.toggleServerBtn)
        openAiKeyInput = findViewById(R.id.openAiKeyInput)
        apiPasswordInput = findViewById(R.id.apiPasswordInput)
        saveSettingsBtn = findViewById(R.id.saveSettingsBtn)

        toggleServerBtn.setOnClickListener {
            if (serverRunning) stopServer() else startServer()
        }

        saveSettingsBtn.setOnClickListener {
            val key = openAiKeyInput.text.toString().trim()
            val pass = apiPasswordInput.text.toString().trim()

            if (key.isNotEmpty()) settingsManager.setOpenAiKey(key)
            if (pass.isNotEmpty()) settingsManager.setApiPassword(pass)

            openAiKeyInput.setText("")
            apiPasswordInput.setText("")
            updateSettingsHints()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }

        // Listen for call state changes
        CallManager.addListener(object : CallManager.CallStateListener {
            override fun onCallStateChanged(state: CallManager.CallState, phoneNumber: String?) {
                runOnUiThread {
                    callStateText.text = when (state) {
                        CallManager.CallState.IDLE -> "No active call"
                        CallManager.CallState.DIALING -> "Dialing: $phoneNumber"
                        CallManager.CallState.RINGING -> "Ringing: $phoneNumber"
                        CallManager.CallState.ACTIVE -> "Active: $phoneNumber [AI auto-start]"
                        CallManager.CallState.DISCONNECTED -> "Call ended"
                    }
                }
            }
        })

        updateSettingsHints()
        updateIpAddress()
        requestPermissions()

        // Auto-start server if OpenAI key is already configured
        if (settingsManager.getOpenAiKey() != null && !serverRunning) {
            startServer()
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CALL_LOG,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val denied = permissions.zip(grantResults.toTypedArray())
                .filter { it.second != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty()) {
                Toast.makeText(this,
                    "Some permissions denied. App may not work correctly.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startServer() {
        val intent = Intent(this, ServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        serverRunning = true
        statusText.text = "Server: ONLINE"
        statusText.setTextColor(0xFF4CAF50.toInt())
        toggleServerBtn.text = "Stop Server"
        toggleServerBtn.setBackgroundColor(0xFFF44336.toInt())
    }

    private fun stopServer() {
        stopService(Intent(this, ServerService::class.java))
        serverRunning = false
        statusText.text = "Server: OFFLINE"
        statusText.setTextColor(0xFFF44336.toInt())
        toggleServerBtn.text = "Start Server"
        toggleServerBtn.setBackgroundColor(0xFF4CAF50.toInt())
    }

    private fun updateSettingsHints() {
        openAiKeyInput.hint = if (settingsManager.getOpenAiKey() != null)
            "Key saved - enter to change" else "sk-..."
        apiPasswordInput.hint = if (settingsManager.getApiPassword() != null)
            "Password saved - enter to change" else "API password"
    }

    private fun updateIpAddress() {
        val ip = getDeviceIp()
        ipText.text = if (ip != null) {
            "http://$ip:${ServerService.PORT}"
        } else {
            "Connect to WiFi to get IP"
        }
    }

    private fun getDeviceIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) { }
        return null
    }
}
