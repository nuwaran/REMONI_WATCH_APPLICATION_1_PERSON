package pl.dalcop.tentnohttp

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random
import kotlin.math.sqrt
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.net.URISyntaxException

class HealthMonitorServiceMQTT : Service(), SensorEventListener {

    companion object {
        const val TAG = "HealthMonitorServiceMQTT"
        const val EXTRA_SERVER_URL = "server_url"
        const val ACTION_RESTART_SERVICE = "pl.dalcop.tentnohttp.ACTION_RESTART_SERVICE"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "HealthMonitorChannel"

        @Volatile
        var isRunning = false
    }

    private lateinit var sensorManager: SensorManager
    private var heartRateSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null

    private var serverUrl: String = ""
    private var baseServerUrl: String = ""
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: Socket? = null
    private var socketConnected = false

    private var latestHeartRate: Int = 0
    private var latestAccelX: Float = 0f
    private var latestAccelY: Float = 0f
    private var latestAccelZ: Float = 0f
    private var latestGyroX: Float = 0f
    private var latestGyroY: Float = 0f
    private var latestGyroZ: Float = 0f

    private var sendJob: Job? = null

    private val BUFFER_SIZE = 680
    private val POST_THRESHOLD_SIZE = 340

    private val circularBuffer = ArrayDeque<MotionDataPoint>(BUFFER_SIZE)
    private val bufferLock = ReentrantLock()

    private var isCollectingPostThreshold = false
    private var postThresholdSamples = mutableListOf<MotionDataPoint>()

    private val ACCEL_MAGNITUDE_THRESHOLD = 50.0f
    private val SUSTAINED_HIGH_ACCEL_COUNT = 5
    private var consecutiveHighAccelCount = 0

    private var totalSamplesCollected = 0
    private var totalThresholdsDetected = 0

    // ✅ NEW: Track watch wearing status
    private var isWatchWorn = false
    private var lastWornStatusLog = 0L

    data class MotionDataPoint(
        val accelX: Float,
        val accelY: Float,
        val accelZ: Float,
        val gyroX: Float,
        val gyroY: Float,
        val gyroZ: Float,
        val timestamp: Long
    )

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "==========================================")
        Log.w(TAG, "🚀 REMONI WATCH SERVICE STARTING")
        Log.w(TAG, "==========================================")
        Log.w(TAG, "   Device: Galaxy Watch 6 Classic")
        Log.w(TAG, "   Detection: PPG-based (heart rate sensor)")
        Log.w(TAG, "   Fall Detection: ONLY when watch is worn")
        Log.w(TAG, "==========================================")

        isRunning = true
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        heartRateSensor?.let {
            Log.w(TAG, "✅ Heart Rate (PPG) sensor: ${it.name}")
            Log.w(TAG, "   Controls fall detection activation")
        } ?: run {
            Log.e(TAG, "❌ Heart Rate sensor NOT available!")
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::WakeLock")
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: ""
        if (serverUrl.isEmpty()) {
            val sharedPrefs = getSharedPreferences("health_monitor", Context.MODE_PRIVATE)
            serverUrl = sharedPrefs.getString("server_url", "") ?: ""
        } else {
            getSharedPreferences("health_monitor", Context.MODE_PRIVATE)
                .edit().putString("server_url", serverUrl).apply()
        }

        if (serverUrl.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        baseServerUrl = if (serverUrl.contains("/sensors")) {
            serverUrl.substringBeforeLast("/sensors")
        } else {
            serverUrl.substringBeforeLast("/")
        }

        startForeground(NOTIFICATION_ID, createNotification())

        if (totalSamplesCollected == 0) {
            heartRateSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
                Log.w(TAG, "✅ Heart Rate sensor registered")
            }
            accelerometerSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
                Log.w(TAG, "✅ Accelerometer registered")
            }
            gyroscopeSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
                Log.w(TAG, "✅ Gyroscope registered")
            }
        }

        if (socket == null || !socketConnected) setupSocketIO()
        if (sendJob == null || sendJob?.isActive == false) startPeriodicSending()

        return START_STICKY
    }

    private fun setupSocketIO() {
        try {
            val options = IO.Options().apply {
                reconnection = true
                reconnectionAttempts = Integer.MAX_VALUE
                reconnectionDelay = 5000
                transports = arrayOf("websocket", "polling")
            }

            socket = IO.socket(baseServerUrl, options)
            socket?.on(Socket.EVENT_CONNECT, onConnect)
            socket?.on(Socket.EVENT_DISCONNECT, onDisconnect)
            socket?.on(Socket.EVENT_CONNECT_ERROR, onConnectError)
            socket?.on("request_fresh_vitals", onFreshVitalsRequest)
            socket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "SocketIO error: ${e.message}")
        }
    }

    private val onConnect = Emitter.Listener {
        socketConnected = true
        Log.w(TAG, "✅ SocketIO CONNECTED")
        updateNotification("Connected")
    }

    private val onDisconnect = Emitter.Listener {
        socketConnected = false
        Log.w(TAG, "⚠️ SocketIO disconnected")
        updateNotification("Disconnected")
    }

    private val onConnectError = Emitter.Listener { }

    private val onFreshVitalsRequest = Emitter.Listener {
        Log.w(TAG, "📨 Fresh vitals requested!")
        serviceScope.launch { sendAllSensorData() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val hr = event.values[0].toInt()
                val previousWornStatus = isWatchWorn

                // ✅ UPDATE WORN STATUS BASED ON PPG
                isWatchWorn = hr > 0

                // ✅ LOG STATUS CHANGES (but not every single reading)
                val now = System.currentTimeMillis()
                if (previousWornStatus != isWatchWorn || (now - lastWornStatusLog) > 10000) {
                    if (isWatchWorn) {
                        Log.w(TAG, "💓 HR: $hr bpm ✅ WATCH WORN - Fall detection ACTIVE")
                    } else {
                        Log.w(TAG, "💓 HR: 0 bpm ❌ WATCH NOT WORN - Fall detection DISABLED")

                        // ✅ CLEAR FALL DETECTION BUFFERS when watch is removed
                        bufferLock.withLock {
                            if (isCollectingPostThreshold) {
                                Log.w(TAG, "🛑 Clearing fall detection buffers (watch removed)")
                                isCollectingPostThreshold = false
                                postThresholdSamples.clear()
                                consecutiveHighAccelCount = 0
                            }
                        }
                    }
                    lastWornStatusLog = now
                }

                latestHeartRate = hr
            }
            Sensor.TYPE_ACCELEROMETER -> {
                latestAccelX = event.values[0]
                latestAccelY = event.values[1]
                latestAccelZ = event.values[2]

                // ✅ ONLY PROCESS MOTION DATA IF WATCH IS WORN
                if (isWatchWorn) {
                    processMotionData(MotionDataPoint(
                        latestAccelX, latestAccelY, latestAccelZ,
                        latestGyroX, latestGyroY, latestGyroZ,
                        System.currentTimeMillis()
                    ))
                    totalSamplesCollected++
                }
                // ✅ If watch not worn, motion data is IGNORED (no fall detection)
            }
            Sensor.TYPE_GYROSCOPE -> {
                latestGyroX = event.values[0]
                latestGyroY = event.values[1]
                latestGyroZ = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun processMotionData(dataPoint: MotionDataPoint) {
        // ✅ DOUBLE-CHECK: This should never be called when watch isn't worn
        if (!isWatchWorn) {
            return
        }

        bufferLock.withLock {
            val magnitude = sqrt(
                dataPoint.accelX * dataPoint.accelX +
                        dataPoint.accelY * dataPoint.accelY +
                        dataPoint.accelZ * dataPoint.accelZ
            )

            if (magnitude > ACCEL_MAGNITUDE_THRESHOLD) {
                consecutiveHighAccelCount++
            } else {
                consecutiveHighAccelCount = 0
            }

            val thresholdExceeded = consecutiveHighAccelCount >= SUSTAINED_HIGH_ACCEL_COUNT

            if (isCollectingPostThreshold) {
                postThresholdSamples.add(dataPoint)
                if (postThresholdSamples.size >= POST_THRESHOLD_SIZE) {
                    val preThresholdCount = minOf(BUFFER_SIZE - POST_THRESHOLD_SIZE, circularBuffer.size)
                    val preThresholdSamples = circularBuffer.takeLast(preThresholdCount).toList()
                    val completeWindow = preThresholdSamples + postThresholdSamples.take(POST_THRESHOLD_SIZE)

                    serviceScope.launch { sendMotionBatchForAnalysis(completeWindow, magnitude) }

                    isCollectingPostThreshold = false
                    postThresholdSamples.clear()
                    consecutiveHighAccelCount = 0
                }
                addToCircularBuffer(dataPoint)
            } else if (thresholdExceeded) {
                totalThresholdsDetected++
                Log.w(TAG, "🎯 Potential fall motion #$totalThresholdsDetected (watch worn ✅)")
                isCollectingPostThreshold = true
                postThresholdSamples.clear()
                postThresholdSamples.add(dataPoint)
                addToCircularBuffer(dataPoint)
            } else {
                addToCircularBuffer(dataPoint)
            }
        }
    }

    private fun addToCircularBuffer(dataPoint: MotionDataPoint) {
        circularBuffer.addLast(dataPoint)
        if (circularBuffer.size > BUFFER_SIZE) circularBuffer.removeFirst()
    }

    private suspend fun sendMotionBatchForAnalysis(motionData: List<MotionDataPoint>, peakMagnitude: Float) {
        // ✅ FINAL CHECK: Don't send fall analysis if watch was removed during collection
        if (!isWatchWorn) {
            Log.w(TAG, "⚠️ Skipping fall analysis - watch no longer worn")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                val motionArray = JSONArray()
                motionData.forEach { point ->
                    motionArray.put(JSONObject().apply {
                        put("accelerometer", JSONObject().apply {
                            put("x", point.accelX)
                            put("y", point.accelY)
                            put("z", point.accelZ)
                        })
                        put("gyroscope", JSONObject().apply {
                            put("x", point.gyroX)
                            put("y", point.gyroY)
                            put("z", point.gyroZ)
                        })
                        put("timestamp", point.timestamp)
                    })
                }

                val payload = JSONObject().apply {
                    put("motion_data", motionArray)
                    put("patient_id", "00001")
                }

                val connection = (URL("$baseServerUrl/analyze_motion_batch").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 10000
                }

                OutputStreamWriter(connection.outputStream).use {
                    it.write(payload.toString())
                    it.flush()
                }

                if (connection.responseCode == 200) {
                    val responseJson = JSONObject(connection.inputStream.bufferedReader().readText())
                    if (responseJson.optBoolean("fall_detected", false)) {
                        Log.w(TAG, "🚨 FALL DETECTED! (confirmed while wearing watch)")
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Fall analysis error: ${e.message}")
            }
        }
    }

    private fun startPeriodicSending() {
        sendJob?.cancel()
        sendJob = serviceScope.launch {
            val calendar = java.util.Calendar.getInstance()
            val currentMinute = calendar.get(java.util.Calendar.MINUTE)
            val nextMinuteMark = ((currentMinute / 5) + 1) * 5
            val minutesUntilNext = if (nextMinuteMark >= 60) 60 - currentMinute else nextMinuteMark - currentMinute

            Log.w(TAG, "⏰ Next vitals send in ${minutesUntilNext} minutes")
            delay(minutesUntilNext * 60 * 1000L)
            sendAllSensorData()

            while (isActive) {
                delay(300000L)
                sendAllSensorData()
            }
        }
    }

    private fun generateSimulatedData(): Map<String, Any> {
        if (latestHeartRate == 0) {
            return mapOf(
                "oxygen_saturation" to 0,
                "systolic_pressure" to 0,
                "diastolic_pressure" to 0,
                "body_temperature" to 0.0,
                "respiratory_rate" to 0
            )
        }
        return mapOf(
            "oxygen_saturation" to Random.nextInt(95, 101),
            "systolic_pressure" to Random.nextInt(110, 140),
            "diastolic_pressure" to Random.nextInt(70, 90),
            "body_temperature" to 36.0 + Random.nextDouble(0.0, 2.0),
            "respiratory_rate" to when (Random.nextInt(200)) {
                in 0..0 -> Random.nextInt(2) + 7
                in 1..4 -> 11
                in 197..197 -> Random.nextInt(3) + 28
                in 195..196 -> 21
                else -> Random.nextInt(9) + 12
            }
        )
    }

    private suspend fun sendAllSensorData() {
        withContext(Dispatchers.IO) {
            try {
                val simulated = generateSimulatedData()
                val currentTimestamp = getRoundedTimestamp()

                val vitalsData = mapOf(
                    "patient_id" to "00001",
                    "time_stamp" to currentTimestamp,
                    "heart_rate" to latestHeartRate,
                    "oxygen_saturation" to simulated["oxygen_saturation"],
                    "systolic_pressure" to simulated["systolic_pressure"],
                    "diastolic_pressure" to simulated["diastolic_pressure"],
                    "body_temperature" to simulated["body_temperature"],
                    "respiratory_rate" to simulated["respiratory_rate"],
                    "glucose" to null,
                    "datetime" to formatRoundedDateTime(currentTimestamp)
                )

                // ✅ IMPROVED LOGGING
                Log.w(TAG, "==========================================")
                Log.w(TAG, "📤 SENDING VITALS")
                Log.w(TAG, "==========================================")
                Log.w(TAG, "   HR: $latestHeartRate bpm ${if (isWatchWorn) "✅ WORN" else "❌ NOT WORN"}")
                Log.w(TAG, "   SpO2: ${simulated["oxygen_saturation"]}%")
                Log.w(TAG, "   BP: ${simulated["systolic_pressure"]}/${simulated["diastolic_pressure"]}")
                Log.w(TAG, "   Fall Detection: ${if (isWatchWorn) "ACTIVE ✅" else "DISABLED ❌"}")

                val jsonData = JSONObject(vitalsData).toString()

                // Send via SocketIO
                if (socketConnected) {
                    try {
                        socket?.emit("sensors", JSONObject(vitalsData))
                        Log.w(TAG, "✅ Sent via SocketIO")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ SocketIO failed: ${e.message}")
                    }
                }

                // Send via HTTP
                try {
                    val connection = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        doOutput = true
                        connectTimeout = 5000
                    }

                    OutputStreamWriter(connection.outputStream).use {
                        it.write(jsonData)
                        it.flush()
                    }

                    val responseCode = connection.responseCode
                    if (responseCode == 200) {
                        Log.w(TAG, "✅ Sent via HTTP - Server accepted")
                    } else {
                        Log.e(TAG, "⚠️ HTTP response: $responseCode")
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ HTTP failed: ${e.message}")
                }

                Log.w(TAG, "==========================================")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Send error: ${e.message}")
            }
        }
    }

    private fun getRoundedTimestamp(): Long {
        val calendar = java.util.Calendar.getInstance()
        val currentMinute = calendar.get(java.util.Calendar.MINUTE)
        val roundedMinute = ((currentMinute + 2) / 5) * 5

        calendar.set(java.util.Calendar.MINUTE, roundedMinute % 60)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)

        if (roundedMinute >= 60) calendar.add(java.util.Calendar.HOUR_OF_DAY, 1)
        return calendar.timeInMillis
    }

    private fun formatRoundedDateTime(timestamp: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    }

    private fun createNotification(): Notification {
        createNotificationChannel()
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("REMONI Watch Active")
            .setContentText("PPG-based fall detection")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("REMONI Watch")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                ))
                .build()

            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "REMONI Health Monitor", NotificationManager.IMPORTANCE_HIGH
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.w(TAG, "⚠️ Service destroyed")
        socket?.off()
        socket?.disconnect()
        sensorManager.unregisterListener(this)
        sendJob?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        serviceScope.cancel()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.w(TAG, "⚠️ Task removed - restarting")
        val restartIntent = Intent(applicationContext, HealthMonitorServiceMQTT::class.java)
            .putExtra(EXTRA_SERVER_URL, serverUrl)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            applicationContext.startForegroundService(restartIntent)
        else
            applicationContext.startService(restartIntent)
    }
}
