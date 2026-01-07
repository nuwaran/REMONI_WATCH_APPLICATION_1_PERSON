package pl.dalcop.tentnohttp

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var patientIdInput: EditText
    private lateinit var serverIpInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        patientIdInput = findViewById(R.id.patientIdInput)
        serverIpInput = findViewById(R.id.serverIpInput)
        connectButton = findViewById(R.id.connectButton)
        statusTextView = findViewById(R.id.statusTextView)

        // ✅ CRITICAL: Reset service state on app launch
        resetServiceState()

        // Load saved configuration
        val sharedPrefs = getSharedPreferences("health_monitor", Context.MODE_PRIVATE)
        val savedPatientId = sharedPrefs.getString("patient_id", "00001")
        val savedIp = sharedPrefs.getString("server_ip", "")

        patientIdInput.setText(savedPatientId)
        serverIpInput.setText(savedIp)

        requestPermissions()
        requestBatteryOptimization()

        connectButton.setOnClickListener {
            val actuallyRunning = isServiceActuallyRunning()

            if (actuallyRunning) {
                stopMonitoringService()
            } else {
                startMonitoringService()
            }
        }

        // Long press to force stop
        connectButton.setOnLongClickListener {
            forceStopService()
            true
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    // ✅ NEW: Reset service state completely
    private fun resetServiceState() {
        try {
            val actuallyRunning = isServiceActuallyRunning()

            Log.d(TAG, "Service state check:")
            Log.d(TAG, "  - Static flag: ${HealthMonitorServiceMQTT.isRunning}")
            Log.d(TAG, "  - Actually running: $actuallyRunning")

            // If flag says running but service is not actually running, reset the flag
            if (HealthMonitorServiceMQTT.isRunning && !actuallyRunning) {
                Log.w(TAG, "⚠️ Service flag was stuck! Resetting...")
                HealthMonitorServiceMQTT.isRunning = false
            }

            // If service is actually running but flag is false, update flag
            if (!HealthMonitorServiceMQTT.isRunning && actuallyRunning) {
                Log.w(TAG, "⚠️ Service was running but flag was wrong! Updating...")
                HealthMonitorServiceMQTT.isRunning = true
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error resetting service state: ${e.message}")
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.INTERNET,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.ACCESS_NETWORK_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_HEALTH)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 100)
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)

                    Toast.makeText(
                        this,
                        "Please allow battery optimization exemption",
                        Toast.LENGTH_LONG
                    ).show()

                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Please disable battery optimization in Settings",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // Check if service is ACTUALLY running
    private fun isServiceActuallyRunning(): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (HealthMonitorServiceMQTT::class.java.name == service.service.className) {
                    return true
                }
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking service: ${e.message}")
            false
        }
    }

    private fun startMonitoringService() {
        val patientId = patientIdInput.text.toString().trim()
        val serverIp = serverIpInput.text.toString().trim()

        // Validate patient ID
        if (patientId.isEmpty()) {
            Toast.makeText(this, "❌ Enter Patient ID", Toast.LENGTH_SHORT).show()
            return
        }

        if (!MQTTConfig.validatePatientId(patientId)) {
            Toast.makeText(this, "❌ Patient ID must be 5 digits (e.g., 00001)", Toast.LENGTH_SHORT).show()
            return
        }

        // Validate server IP
        if (serverIp.isEmpty()) {
            Toast.makeText(this, "❌ Enter Edge Device IP", Toast.LENGTH_SHORT).show()
            return
        }

        if (!isValidIP(serverIp)) {
            Toast.makeText(this, "❌ Invalid IP format", Toast.LENGTH_SHORT).show()
            return
        }

        // Save configuration
        val sharedPrefs = getSharedPreferences("health_monitor", Context.MODE_PRIVATE)
        sharedPrefs.edit().apply {
            putString("patient_id", patientId)
            putString("server_ip", serverIp)
            val serverUrl = "http://$serverIp:5000/sensors"
            putString("server_url", serverUrl)
            apply()
        }

        Log.w(TAG, "🚀 Starting service with:")
        Log.w(TAG, "   Patient ID: $patientId")
        Log.w(TAG, "   Server IP: $serverIp")
        Log.w(TAG, "   Full URL: http://$serverIp:5000/sensors")

        // Start service
        val intent = Intent(this, HealthMonitorServiceMQTT::class.java).apply {
            val serverUrl = "http://$serverIp:5000/sensors"
            putExtra(HealthMonitorServiceMQTT.EXTRA_SERVER_URL, serverUrl)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            Toast.makeText(this, "✅ Starting monitoring...", Toast.LENGTH_SHORT).show()

            // Update UI after delay
            connectButton.postDelayed({
                updateUI()
            }, 1000)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start service: ${e.message}")
            Toast.makeText(this, "❌ Failed to start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopMonitoringService() {
        Log.w(TAG, "⏹️ Stopping service...")

        val intent = Intent(this, HealthMonitorServiceMQTT::class.java)
        stopService(intent)

        Toast.makeText(this, "⏹️ Stopping...", Toast.LENGTH_SHORT).show()

        // Update UI after delay
        connectButton.postDelayed({
            updateUI()
        }, 1000)
    }

    // Force stop if stuck
    private fun forceStopService() {
        Log.w(TAG, "🛑 FORCE STOP")

        // Stop service
        val intent = Intent(this, HealthMonitorServiceMQTT::class.java)
        stopService(intent)

        // Reset flag
        HealthMonitorServiceMQTT.isRunning = false

        Toast.makeText(this, "🛑 FORCE STOPPED", Toast.LENGTH_SHORT).show()

        // Update UI
        connectButton.postDelayed({
            updateUI()
        }, 500)
    }

    // Validate IP address
    private fun isValidIP(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false

        return parts.all { part ->
            try {
                val num = part.toInt()
                num in 0..255
            } catch (e: NumberFormatException) {
                false
            }
        }
    }

    // ✅ FIXED: Always enable inputs + sync with actual service state
    private fun updateUI() {
        runOnUiThread {
            // Check ACTUAL service state
            val actuallyRunning = isServiceActuallyRunning()

            // Sync the static flag with reality
            HealthMonitorServiceMQTT.isRunning = actuallyRunning

            Log.d(TAG, "UI Update - Service running: $actuallyRunning")

            if (actuallyRunning) {
                // Service IS running
                connectButton.text = "Stop Monitoring"
                connectButton.setBackgroundResource(R.drawable.button_stop_bg)
                statusTextView.text = "Status: Running ✓"

            } else {
                // Service NOT running
                connectButton.text = "Start Monitoring"
                connectButton.setBackgroundResource(R.drawable.button_start_bg)
                statusTextView.text = "Status: Stopped"
            }

            // ✅ ALWAYS enable inputs (user can edit anytime)
            patientIdInput.isEnabled = true
            serverIpInput.isEnabled = true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100) {
            val denied = grantResults.indices.any { grantResults[it] != PackageManager.PERMISSION_GRANTED }

            if (denied) {
                Toast.makeText(
                    this,
                    "⚠️ Some permissions denied",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "✅ All permissions granted", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
