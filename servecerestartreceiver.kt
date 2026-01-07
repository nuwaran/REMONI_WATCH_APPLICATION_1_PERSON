package pl.dalcop.tentnohttp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Broadcast receiver to restart the health monitoring service
 * Handles boot completion and service crashes
 */
class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("ServiceRestart", "Received broadcast: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "pl.dalcop.tentnohttp.ACTION_RESTART_SERVICE" -> {
                restartService(context)
            }
        }
    }

    private fun restartService(context: Context) {
        try {
            // Check if service was previously running
            val sharedPrefs = context.getSharedPreferences("health_monitor", Context.MODE_PRIVATE)
            val serverUrl = sharedPrefs.getString("server_url", null)

            if (serverUrl != null) {
                Log.i("ServiceRestart", "Restarting HealthMonitorServiceMQTT")

                val serviceIntent = Intent(context, HealthMonitorServiceMQTT::class.java).apply {
                    putExtra(HealthMonitorServiceMQTT.EXTRA_SERVER_URL, serverUrl)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                Log.i("ServiceRestart", "Service restart initiated")
            } else {
                Log.i("ServiceRestart", "No saved configuration - service not restarted")
            }
        } catch (e: Exception) {
            Log.e("ServiceRestart", "Error restarting service: ${e.message}", e)
        }
    }
}
