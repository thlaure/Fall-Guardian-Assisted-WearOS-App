package com.fallguardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
/**
 * Persistent foreground service that runs the PSP fall detection algorithm.
 *
 * --- What is a Foreground Service? ---
 * Android can kill background processes at any time to reclaim memory. A
 * "foreground service" is special: Android promises to keep it alive for as long
 * as it displays a visible notification in the status bar. That's the persistent
 * "Fall Guardian Active" notification the user sees on the watch. Without it,
 * the OS could silently stop fall detection at any point during the day.
 *
 * --- What is SensorEventListener? ---
 * The ": Service(), SensorEventListener" syntax after the class name means
 * FallDetectionService *implements* the SensorEventListener interface. Declaring
 * this "contract" is what allows us to pass `this` to sensorManager.registerListener()
 * and have Android call our onSensorChanged() method directly every ~20 ms.
 *
 * --- How the four files connect (data flow) ---
 * 1. MainActivity creates this service on launch and requests the BODY_SENSORS
 *    permission needed to read the accelerometer.
 * 2. This service subscribes to the accelerometer and feeds every reading into
 *    FallAlgorithm.processSample().
 * 3. When FallAlgorithm confirms a fall, this service calls
 *    WearDataSender.sendFallEvent(), which starts the 30-second countdown UI
 *    AND sends a Wearable message to the phone.
 * 4. PhoneMessageListenerService receives threshold updates sent from the phone
 *    and writes them to SharedPreferences. This service's prefChangeListener
 *    detects those writes and rebuilds FallAlgorithm on the fly — no restart needed.
 *
 * Uses SensorManager with TYPE_ACCELEROMETER at 50 Hz.
 * On fall detection: sends Data Layer event to the phone and enforces
 * a 5-second cooldown before the next detection can fire.
 */
class FallDetectionService : Service(), SensorEventListener {

    // SensorManager is the Android system service that grants access to hardware
    // sensors (accelerometer, gyroscope, heart rate, etc.). We request it from
    // the OS in onCreate() via getSystemService().
    private lateinit var sensorManager: SensorManager

    // A handle to the specific accelerometer sensor object on this hardware.
    // Nullable (?) because getDefaultSensor() returns null if the device has no
    // accelerometer — unlikely on a Galaxy Watch, but safe to handle.
    private var accelerometer: Sensor? = null

    // The fall-detection algorithm (see FallAlgorithm.kt). Receives raw X/Y/Z
    // accelerometer values on every tick and returns true when it identifies the
    // full three-phase fall pattern (freefall → impact → tilt).
    private lateinit var algorithm: FallAlgorithm

    // A WakeLock prevents the watch CPU from entering deep sleep while the
    // screen is off. Without it, Android would stop delivering sensor events
    // once the screen turns off — meaning falls would go undetected at night
    // or when the watch is idle. PARTIAL_WAKE_LOCK keeps only the CPU on,
    // letting the screen turn off to save battery.
    private lateinit var wakeLock: PowerManager.WakeLock

    // SharedPreferences is Android's built-in key-value persistent storage —
    // think of it as a small on-device settings file. We use it to store the
    // four detection thresholds so they survive app restarts and can be updated
    // remotely by the phone app.
    private lateinit var prefs: SharedPreferences

    // Timestamp (in elapsed-milliseconds) of the most recent confirmed fall.
    // Compared against the current time to enforce the cooldown window.
    private var lastFallMs: Long = 0L

    // Minimum gap between two successive fall alerts. 5 seconds prevents a
    // single tumble from firing the alarm multiple times (e.g. if the user
    // rolls after hitting the ground and triggers the sensor again).
    private val cooldownMs: Long = 5_000L

    // --- Step 4: Live threshold reload ---
    // This callback is registered on SharedPreferences below. Whenever
    // PhoneMessageListenerService saves new threshold values from the phone,
    // SharedPreferences fires this listener. We rebuild FallAlgorithm immediately
    // so the new sensitivity takes effect on the very next sensor sample — no
    // service restart, no detection gap.
    private val prefChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key.startsWith("thresh_")) {
            algorithm = loadAlgorithmFromPrefs()
        }
    }

    companion object {
        // Identifies the notification channel to the Android OS. Channels (added
        // in Android 8.0) let users independently control notification behaviour
        // per category — e.g. they can silence "Fall Detection" without affecting
        // other app notifications.
        const val CHANNEL_ID = "fall_detection"

        // Every notification needs a unique integer ID so the OS knows which
        // notification to update or remove. 1001 is arbitrary — it just must be
        // unique within this app.
        const val NOTIF_ID = 1001
    }

    // --- Step 1: Service startup ---
    // onCreate() runs once when the service is first created. Think of it as the
    // service's constructor. Everything initialised here persists for the entire
    // lifetime of the service.
    override fun onCreate() {
        super.onCreate()

        // Load the four threshold values from local storage. If the phone has
        // never sent settings, the default values inside loadAlgorithmFromPrefs()
        // are used.
        prefs = getSharedPreferences("fall_guardian", Context.MODE_PRIVATE)
        algorithm = loadAlgorithmFromPrefs()

        // Ask the OS for the SensorManager, then request the accelerometer sensor.
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Acquire the CPU wake lock. "apply { acquire() }" runs acquire() on the
        // newly created WakeLock object immediately. Released in onDestroy().
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "FallGuardian::FallDetection" // Tag shown in battery usage reports.
        ).apply { acquire() } // released in onDestroy — no timeout to avoid detection gaps

        // Begin listening for remote threshold changes from the phone.
        prefs.registerOnSharedPreferenceChangeListener(prefChangeListener)

        // A foreground service must call startForeground() promptly after creation —
        // Android enforces this. The call attaches the persistent notification and
        // signals to the OS that this service must not be killed under memory pressure.
        createNotificationChannel() // Must exist before posting any notification.
        startForeground(NOTIF_ID, buildNotification())

        // --- Step 2: Begin reading the accelerometer ---
        registerSensors()
    }

    /**
     * Reads the four threshold values from SharedPreferences and constructs a
     * new FallAlgorithm. Called at startup and whenever the phone pushes updated
     * settings (via prefChangeListener).
     *
     * Default values represent a balanced sensitivity for most users:
     *   - 0.5 g freefall threshold  (brief weightlessness during a fall)
     *   - 2.5 g impact threshold    (sudden deceleration when hitting the ground)
     *   - 45° tilt threshold        (post-impact body orientation)
     *   - 80 ms freefall window     (minimum duration of weightlessness)
     */
    private fun loadAlgorithmFromPrefs() = FallAlgorithm(
        freeFallThresholdG = prefs.getFloat("thresh_freefall", 0.5f).coerceIn(0.1f, 1.0f),
        impactThresholdG = prefs.getFloat("thresh_impact", 2.5f).coerceIn(1.5f, 5.0f),
        tiltThresholdDeg = prefs.getFloat("thresh_tilt", 45f).coerceIn(20f, 90f),
        freeFallMinMs = prefs.getInt("thresh_freefall_ms", 80).coerceIn(40, 200).toLong()
    )

    /**
     * Subscribes to the accelerometer at SENSOR_DELAY_GAME rate (~50 Hz / one
     * sample every ~20 ms). SENSOR_DELAY_GAME is the fastest preset that still
     * batches efficiently — fast enough to capture the brief freefall and impact
     * phases of a fall, without the battery drain of SENSOR_DELAY_FASTEST.
     *
     * If the BODY_SENSORS permission was revoked after launch (the user went to
     * system settings and turned it off), we flag the error in WearDataSender
     * so MainActivity can show the PermissionDeniedScreen, then stop the service.
     */
    private fun registerSensors() {
        val sensor = accelerometer ?: return // No accelerometer hardware found — nothing to do.
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) // ~50 Hz
    }

    // --- Step 3: Process each accelerometer reading ---
    // Android calls this method on every sensor tick (~50 times per second).
    // This is the hot path: avoid memory allocations here to prevent GC pauses.
    override fun onSensorChanged(event: SensorEvent) {
        // Safety guard: this listener could theoretically receive events from
        // other sensors if we register more in the future. Ignore non-accelerometer data.
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // elapsedRealtime() is a monotonic clock (never jumps backward, keeps
        // ticking during device sleep). It's the right clock for measuring time
        // intervals inside the algorithm.
        val nowElapsed = SystemClock.elapsedRealtime()

        // currentTimeMillis() is wall-clock time (milliseconds since Unix epoch,
        // 1 January 1970). We use this as the shared fall timestamp so both the
        // watch and the phone can anchor their 30-second countdown to the same moment.
        val nowWall = System.currentTimeMillis()

        // event.values holds [ax, ay, az] in m/s² — the raw accelerations along
        // the watch's three physical axes.
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        // Feed this sample into the PSP fall-detection state machine.
        // processSample() returns true only after the full pattern completes:
        // freefall phase (< thresh_freefall g for >= thresh_freefall_ms ms) →
        // impact phase  (> thresh_impact g) →
        // tilt phase    (body angle > thresh_tilt degrees).
        val detected = algorithm.processSample(ax, ay, az, nowElapsed)

        // Only act on a positive detection if enough time has passed since the
        // last alert — this is the cooldown check.
        if (detected && (nowElapsed - lastFallMs > cooldownMs)) {
            lastFallMs = nowElapsed  // Record when this fall fired.
            algorithm.reset()        // Clear the state machine for the next event.

            // --- Step 3a: Notify the phone and launch the countdown UI ---
            // WearDataSender packages the wall-clock timestamp, sends it to the
            // phone via Wearable MessageClient, and flips alertActive = true so
            // MainActivity automatically switches to AlertScreen.
            WearDataSender.sendFallEvent(applicationContext, nowWall)
        }
    }

    // Required by the SensorEventListener interface but unused here — we do not
    // change detection behaviour based on the sensor's calibration accuracy.
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // onStartCommand() fires every time a caller invokes startForegroundService()
    // on this service (e.g. if the OS restarted it after a kill). START_STICKY
    // tells Android to automatically restart the service if it is killed — this
    // is essential for continuous fall monitoring. We reload thresholds in case
    // they changed while the service was down.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reload thresholds if settings changed while the service was stopped.
        algorithm = loadAlgorithmFromPrefs()
        return START_STICKY // "Restart me automatically if you kill me."
    }

    // Called when the service is finally shutting down (e.g. user force-quits).
    // Every resource acquired in onCreate() must be released here to avoid leaks.
    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefChangeListener) // Stop threshold watching.
        sensorManager.unregisterListener(this)    // Stop receiving sensor events.
        if (wakeLock.isHeld) wakeLock.release()   // Release the CPU wake lock.
        super.onDestroy()
    }

    // This service is not designed to be "bound" (called like an RPC by another
    // component). Returning null tells Android it is a "started service" only.
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Registers the notification channel with the OS. Channels must be created
     * before any notification is posted. IMPORTANCE_LOW means the notification
     * appears silently — no sound, no pop-up — which is appropriate for a
     * persistent background-monitoring status indicator.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Fall Detection",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Fall monitoring is active" }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /**
     * Builds the persistent "Fall Guardian Active" notification that appears in
     * the watch's status bar. setOngoing(true) "pins" the notification — the user
     * cannot dismiss it by swiping. Removing it requires the service to be stopped
     * intentionally through the app, which is the correct safety behaviour.
     */
    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fall Guardian Active")
            .setContentText("Fall detection is running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true) // Pinned — cannot be dismissed by swipe.
            .build()
}
