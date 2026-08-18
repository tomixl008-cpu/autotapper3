package com.example.autotexttapper

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Simple control screen with three buttons:
 *  - Open Accessibility Settings
 *  - Start (5-second delay)
 *  - Stop
 *
 * All automation runs inside [TextAutomationAccessibilityService]; this activity
 * only forwards the user's commands and mirrors the service status.
 */
class MainActivity : AppCompatActivity(), StatusHolder.Listener {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.textStatus)

        findViewById<Button>(R.id.buttonOpenAccessibility).setOnClickListener {
            openAccessibilitySettings()
        }
        findViewById<Button>(R.id.buttonStart).setOnClickListener { onStartPressed() }
        findViewById<Button>(R.id.buttonStop).setOnClickListener { onStopPressed() }
    }

    override fun onResume() {
        super.onResume()
        StatusHolder.addListener(this)
        refreshStatus()
    }

    override fun onPause() {
        StatusHolder.removeListener(this)
        super.onPause()
    }

    // ---------------------------------------------------------------
    // StatusHolder.Listener
    // ---------------------------------------------------------------

    override fun onStatusChanged(status: String) {
        if (::statusText.isInitialized) {
            statusText.text = status
        }
    }

    // ---------------------------------------------------------------
    // Button handlers
    // ---------------------------------------------------------------

    private fun onStartPressed() {
        val service = TextAutomationAccessibilityService.instance
        if (service == null || !isAccessibilityServiceEnabled()) {
            // Service is not enabled: say so clearly and send the user to Settings.
            statusText.text = getString(R.string.status_service_disabled)
            openAccessibilitySettings()
            return
        }
        service.startAutomation()
    }

    private fun onStopPressed() {
        val service = TextAutomationAccessibilityService.instance
        if (service != null) {
            service.stopAutomation()
        } else {
            statusText.text = getString(R.string.status_stopped)
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // ---------------------------------------------------------------
    // Status helpers
    // ---------------------------------------------------------------

    private fun refreshStatus() {
        if (!isAccessibilityServiceEnabled()) {
            statusText.text = getString(R.string.status_service_disabled)
            return
        }
        val stored = StatusHolder.read(this)
        statusText.text =
            if (stored.isEmpty()) getString(R.string.status_enabled_ready) else stored
    }

    /**
     * True when the user has enabled this app's accessibility service in Android
     * Settings (and it is currently bound).
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        // Fast path: the service is bound to this process right now.
        if (TextAutomationAccessibilityService.instance != null) return true

        val manager =
            getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(this, TextAutomationAccessibilityService::class.java)
        val enabledServices =
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        return enabledServices.any { info ->
            val si = info.resolveInfo.serviceInfo
            si != null && si.packageName == expected.packageName && si.name == expected.className
        }
    }
}
