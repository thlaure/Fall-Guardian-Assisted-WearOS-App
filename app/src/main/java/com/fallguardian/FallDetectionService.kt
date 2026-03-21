package com.fallguardian

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Persistent foreground service that runs the PSP fall detection algorithm.
 *
 * Uses SensorManager with TYPE_ACCELEROMETER at 50 Hz.
 * On fall detection: sends Data Layer event to the phone and enforces
 * a 5-second cooldown before the next detection can fire.
 */
class FallDetectionService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private lateinit var algorithm: FallAlgorithm
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var prefs: SharedPreferences

    private var lastFallMs: Long = 0L
    private val cooldownMs: Long = 5_000L

    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith("thresh_")) {
            algorithm = loadAlgorithmFromPrefs()
        }
    }

    companion object {
        const val CHANNEL_ID = "fall_detection"
        const val NOTIF_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("fall_guardian", Context.MODE_PRIVATE)
        algorithm = loadAlgorithmFromPrefs()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FallGuardian::FallDetection"
        ).apply { acquire() } // released in onDestroy — no timeout to avoid detection gaps

        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        registerSensors()
    }

    private fun loadAlgorithmFromPrefs() = FallAlgorithm(
        freeFallThresholdG = prefs.getFloat("thresh_freefall", 0.5f),
        impactThresholdG = prefs.getFloat("thresh_impact", 2.5f),
        tiltThresholdDeg = prefs.getFloat("thresh_tilt", 45f),
        freeFallMinMs = prefs.getInt("thresh_freefall_ms", 80).toLong()
    )

    private fun registerSensors() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BODY_SENSORS)
                != PackageManager.PERMISSION_GRANTED) {
            WearDataSender.permissionDenied = true
            stopSelf()
            return
        }
        val sensor = accelerometer ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) // ~50 Hz
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        val detected = algorithm.processSample(ax, ay, az, nowElapsed)

        if (detected && (nowElapsed - lastFallMs > cooldownMs)) {
            lastFallMs = nowElapsed
            algorithm.reset()
            WearDataSender.sendFallEvent(applicationContext, nowWall)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reload thresholds if settings changed
        algorithm = loadAlgorithmFromPrefs()
        return START_STICKY
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener)
        sensorManager.unregisterListener(this)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fall Detection",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Fall monitoring is active" }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fall Guardian Active")
            .setContentText("Fall detection is running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
}
