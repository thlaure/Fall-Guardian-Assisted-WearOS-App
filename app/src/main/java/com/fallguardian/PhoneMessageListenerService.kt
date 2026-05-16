package com.fallguardian

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/**
 * Background service that receives Wearable messages sent FROM the phone TO the watch.
 *
 * --- What is WearableListenerService? ---
 * WearableListenerService is a special Android service provided by Google Play
 * Services. You do not start it manually — the Wearable Data Layer infrastructure
 * starts it automatically whenever a message arrives from the paired phone,
 * even if the app is not running. Once the message is delivered, the system
 * can stop the service again. This "wake on message" behaviour is what allows
 * the watch to respond to phone commands even in the background.
 *
 * The service must be declared in AndroidManifest.xml with an intent-filter for
 * "com.google.android.gms.wearable.MESSAGE_RECEIVED" — that's how Play Services
 * knows to wake this class when a message arrives.
 *
 * --- What messages arrive here? ---
 * Two paths are handled:
 *   "/thresholds"   — The phone user changed sensitivity settings. The payload
 *                     is a UTF-8 JSON string with updated threshold values.
 *   "/cancel_alert" — The phone user (or the phone's auto-timeout) cancelled
 *                     the alert. No payload needed.
 *
 * --- How this connects to the other files ---
 * • Threshold changes: written to SharedPreferences here →
 *   FallDetectionService.prefChangeListener hears the change →
 *   FallAlgorithm is rebuilt with the new values, in real-time, no restart.
 * • Cancel: calls WearDataSender.cancelAlertFromPhone() →
 *   alertActive becomes false → Compose recomposes → AlertScreen disappears.
 *
 * --- Why is cancel ALSO handled in MainActivity? ---
 * WearableListenerService is unreliable in the Android/Wear OS emulator for the
 * phone→watch direction. MainActivity registers a second MessageClient listener
 * that is active while the Activity is in the foreground, as a belt-and-suspenders
 * workaround during development. On a real device, either listener would suffice.
 *
 * Receives /thresholds messages from the paired phone and persists them to
 * SharedPreferences. FallDetectionService.prefChangeListener picks up the
 * changes and reloads the algorithm without a restart.
 */
class PhoneMessageListenerService : WearableListenerService() {

    // onMessageReceived() is called by the Wearable runtime on a background thread
    // every time the phone sends a message to this watch. messageEvent carries
    // the path string and the raw byte payload.
    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d("PhoneMessageListener", "onMessageReceived: path=${messageEvent.path}")
        // Route the message to the appropriate handler based on the path string.
        when (messageEvent.path) {
            "/thresholds"  -> handleThresholds(messageEvent.data)  // Phone pushed new settings.
            "/cancel_alert" -> WearDataSender.cancelAlertFromPhone() // Phone user cancelled the alert.
        }
    }

    /**
     * Parses the threshold payload and persists each value to SharedPreferences.
     *
     * --- Message format ---
     * The phone sends a UTF-8-encoded JSON object, for example:
     *   {"thresh_freefall":0.4,"thresh_impact":2.8,"thresh_tilt":40,"thresh_freefall_ms":100}
     *
     * --- Why JSON? ---
     * JSON is human-readable and easy to produce from Flutter (which uses Dart maps).
     * The alternative — fixed binary layout — would be faster but fragile if either
     * side ever adds or reorders fields.
     *
     * --- SharedPreferences.edit() / apply() pattern ---
     * edit() opens a transaction-like "editor" object. Changes are batched inside
     * the apply { ... } block. The final .apply() (different from Kotlin's apply!)
     * commits all changes asynchronously to disk. Using apply() instead of commit()
     * means the write does not block this background thread.
     *
     * --- Why check json.has() before reading? ---
     * The phone might send a partial update (only some keys changed). Checking
     * json.has() before reading ensures we only overwrite values that were
     * explicitly included in the message.
     *
     * --- Connection to FallDetectionService ---
     * After .apply() completes, SharedPreferences notifies all registered
     * OnSharedPreferenceChangeListeners. FallDetectionService.prefChangeListener
     * is one such listener — it catches the "thresh_*" key changes and immediately
     * rebuilds FallAlgorithm with the new values.
     *
     * @param data  Raw bytes from the Wearable message payload.
     */
    private fun handleThresholds(data: ByteArray) {
        try {
            // Decode the bytes to a UTF-8 String, then parse it as a JSON object.
            val json = JSONObject(String(data, Charsets.UTF_8))

            // Open SharedPreferences in MODE_PRIVATE — only this app can read it.
            // "fall_guardian" is the file name; it must match the name used in
            // FallDetectionService to ensure both read from the same store.
            val prefs = getSharedPreferences("fall_guardian", Context.MODE_PRIVATE)

            // Kotlin's apply { } here is the standard library extension — it runs
            // the lambda on the editor object and returns the editor, enabling chaining.
            prefs.edit().apply {
                // Each threshold is optional in the payload — only write it if present.
                json.validDouble("thresh_freefall", 0.1, 1.0)?.let {
                    putFloat("thresh_freefall", it.toFloat())
                }
                json.validDouble("thresh_impact", 1.5, 5.0)?.let {
                    putFloat("thresh_impact", it.toFloat())
                }
                json.validDouble("thresh_tilt", 20.0, 90.0)?.let {
                    putFloat("thresh_tilt", it.toFloat())
                }
                json.validInt("thresh_freefall_ms", 40, 200)?.let {
                    putInt("thresh_freefall_ms", it)
                }
            }.apply() // Commit the batch to disk asynchronously.
        } catch (_: Exception) {
            // Malformed payload — ignore silently. A bad threshold message should
            // not crash the app; the old values remain in effect.
        }
    }

    private fun JSONObject.validDouble(key: String, min: Double, max: Double): Double? {
        if (!has(key)) return null
        val value = getDouble(key)
        return value.takeIf { it.isFinite() && it in min..max }
    }

    private fun JSONObject.validInt(key: String, min: Int, max: Int): Int? {
        if (!has(key)) return null
        val value = getInt(key)
        return value.takeIf { it in min..max }
    }
}
