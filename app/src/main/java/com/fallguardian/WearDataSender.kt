package com.fallguardian

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer

/**
 * Central hub for all watch ↔ phone communication and UI state during an alert.
 *
 * --- What is the Wearable MessageClient API? ---
 * Google provides a "Wearable Data Layer" API as part of Google Play Services.
 * It gives paired Wear OS / Android devices a reliable channel to exchange small
 * messages over Bluetooth (or Wi-Fi when Bluetooth is out of range). The
 * MessageClient works node-to-node: you look up the connected phone's node ID,
 * then fire a message at a named "path" (like "/fall_event") that the receiving
 * side listens for. This is why it works across different package names — the
 * routing is by node ID + path, not by app identity.
 *
 * --- Why a Kotlin `object`? ---
 * `object` declares a singleton — there is exactly one instance of WearDataSender
 * for the whole process. That single instance holds the shared UI state
 * (alertActive, remainingSeconds) that both FallDetectionService (background) and
 * MainActivity (UI) read. Using a singleton avoids passing references around and
 * guarantees that state changes from any caller are immediately visible everywhere.
 *
 * --- How this file connects to the others ---
 * - FallDetectionService calls sendFallEvent() when the algorithm fires.
 * - PhoneMessageListenerService calls cancelAlertFromPhone() when the phone
 *   user taps "cancel".
 * - MainActivity's AlertScreen reads alertActive and remainingSeconds to render
 *   the countdown, and calls sendCancelAlert() when the watch user taps.
 *
 * Sends fall/cancel messages to the paired phone via the Wearable MessageClient API.
 * MessageClient is node-based and works across different package names.
 */
object WearDataSender {

    // --- Compose-observable UI state ---
    // `mutableStateOf` creates a Compose state holder. Any @Composable function
    // that reads these variables will automatically recompose (re-render) when
    // their value changes — similar to React's useState hook.
    // `by` is Kotlin's property delegation syntax; it unwraps the state object
    // so we can read/write alertActive directly instead of alertActive.value.
    // `private set` means only code inside this object can change the value;
    // external callers can only read it.
    var alertActive by mutableStateOf(false)
        private set

    // Counts down from 30 to 0 during an active alert. Drives the large number
    // shown on AlertScreen. Updated by tickRunnable every second.
    var remainingSeconds by mutableStateOf(30)
        private set

    // Shared countdown origin used to keep the watch UI aligned with the phone.
    private var fallTimestampMs: Long = 0L

    // --- Countdown timer ---
    // Handler + Looper.getMainLooper() creates a timer that runs on the main
    // (UI) thread. Composable state must only be written from the main thread,
    // so this is the correct choice over a background Thread or coroutine here.
    private val handler = Handler(Looper.getMainLooper())

    // tickRunnable re-computes the remaining time from the original fall timestamp.
    // This avoids drift and keeps the watch aligned with the phone countdown even
    // if there was a delivery delay before either screen became visible.
    // Using `object : Runnable` creates an anonymous class implementing Runnable
    // so the Runnable can reference itself via `this` to reschedule.
    private val tickRunnable = object : Runnable {
        override fun run() {
            val elapsedSeconds = ((System.currentTimeMillis() - fallTimestampMs) / 1000L).toInt()
            val remaining = (30 - elapsedSeconds).coerceIn(0, 30)
            remainingSeconds = remaining

            if (remaining > 0) {
                handler.postDelayed(this, 500L)
            } else {
                // Countdown expired without cancellation — time is up.
                alertActive = false                     // Dismiss AlertScreen; return to IdleScreen.
                // Note: the phone is responsible for sending the SMS to emergency
                // contacts when its own 30-second countdown expires.
            }
        }
    }

    /**
     * Called by FallDetectionService when a fall is confirmed.
     *
     * Step 1 — Encode the timestamp into 8 raw bytes so the phone can parse it
     *           as a Long (Int64). ByteBuffer is a Java utility that writes
     *           primitive types into a byte array in big-endian order.
     * Step 2 — Send the "/fall_event" message with the timestamp payload to
     *           every connected phone node via sendToPhone().
     * Step 3 — Reset and start the local 30-second countdown UI so the watch
     *           shows AlertScreen in sync with the phone's countdown.
     *
     * @param context  Android context needed to reach Wearable APIs.
     * @param timestamp  Wall-clock time in ms (System.currentTimeMillis()) when
     *                   the fall was detected. Both watch and phone use this same
     *                   origin to keep their countdowns in sync.
     */
    fun sendFallEvent(context: Context, timestamp: Long) {
        // Encode the 8-byte Long timestamp into a raw byte array for the message payload.
        val payload = ByteBuffer.allocate(8).putLong(timestamp).array()
        sendToPhone(context, "/fall_event", payload, "fall event")
        queueForPhone(context, "/fall_event", timestamp, "fall event")

        // Reset any previous countdown before starting a new one.
        handler.removeCallbacks(tickRunnable)
        fallTimestampMs = timestamp
        alertActive = true        // Tell Compose to switch to AlertScreen.
        remainingSeconds = (30 - ((System.currentTimeMillis() - timestamp) / 1000L).toInt()).coerceIn(0, 30)
        handler.post(tickRunnable)
    }

    /**
     * Called when the watch user taps "cancel" on AlertScreen.
     *
     * Stops the local countdown, dismisses AlertScreen, and sends "/cancel_alert"
     * to the phone so the phone also dismisses its FallAlertScreen and push
     * notification. This is the watch-originates-cancel path; the reverse path
     * (phone originates cancel → watch) is handled by cancelAlertFromPhone().
     *
     * @param context  Android context needed to reach Wearable APIs.
     */
    fun sendCancelAlert(context: Context) {
        handler.removeCallbacks(tickRunnable)  // Stop the countdown timer.
        alertActive = false                    // Return to IdleScreen immediately.
        fallTimestampMs = 0L
        remainingSeconds = 30                  // Reset for the next alert.
        // Empty payload — the phone only needs to know that cancel happened, not any data.
        sendToPhone(context, "/cancel_alert", ByteArray(0), "cancel alert")
        queueForPhone(context, "/cancel_alert", System.currentTimeMillis(), "cancel alert")
    }

    /**
     * Called by PhoneMessageListenerService (or MainActivity's cancelAlertListener)
     * when the phone sends "/cancel_alert" to the watch.
     *
     * We do NOT call sendToPhone() here — that would echo the cancel back to the
     * phone, causing an infinite ping-pong loop. We only update local state.
     *
     * handler.post() ensures the state write happens on the main thread, even
     * though PhoneMessageListenerService may call this from a background thread.
     */
    fun cancelAlertFromPhone() {
        Log.d("WearDataSender", "cancelAlertFromPhone: alertActive=$alertActive")
        handler.post {
            handler.removeCallbacks(tickRunnable) // Stop the countdown timer.
            alertActive = false                   // Return to IdleScreen.
            fallTimestampMs = 0L
            remainingSeconds = 30                 // Reset for the next alert.
        }
    }

    /**
     * Low-level helper that sends a Wearable message to all connected phone nodes.
     *
     * --- NodeClient and MessageClient ---
     * The Wearable Data Layer has two relevant clients:
     *   • NodeClient  — lists the Bluetooth-paired devices ("nodes"). A node is
     *                   any device running Google Play Services with a paired Wear
     *                   OS watch. In practice there is usually exactly one node
     *                   (the user's phone).
     *   • MessageClient — sends a fire-and-forget message to a specific node ID
     *                     at a named path. Messages are delivered reliably over
     *                     the Bluetooth link (or Wi-Fi Direct if BT is unavailable).
     *
     * --- Asynchronous callbacks ---
     * Both getNodeClient and sendMessage are asynchronous operations — they return
     * a Task<T> (Google's async primitive). addOnSuccessListener and
     * addOnFailureListener register callbacks that run when the operation completes.
     * This avoids blocking the calling thread (which could be the UI thread).
     *
     * @param context  Android context for Play Services.
     * @param path     Message path (e.g. "/fall_event", "/cancel_alert").
     * @param payload  Raw bytes to send. Max ~100 KB, but we send ≤ 8 bytes.
     * @param label    Human-readable name used in log messages for debugging.
     */
    private fun sendToPhone(context: Context, path: String, payload: ByteArray, label: String) {
        // Step A: discover which phone nodes are currently reachable.
        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    // Phone is out of range or Bluetooth is off. The message is
                    // lost — there is no queuing in MessageClient (use DataClient
                    // for guaranteed delivery). Log a warning for debugging.
                    Log.w("WearDataSender", "No connected nodes found")
                    return@addOnSuccessListener
                }
                // Step B: send the message to every connected node. Normally there
                // is only one (the paired phone), but the API supports multi-node
                // setups, so we iterate to be correct.
                nodes.forEach { node ->
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, path, payload)
                        .addOnSuccessListener { Log.d("WearDataSender", "Sent $label to ${node.displayName}") }
                        .addOnFailureListener { e -> Log.e("WearDataSender", "Failed to send $label", e) }
                }
            }
            .addOnFailureListener { e -> Log.e("WearDataSender", "Failed to get connected nodes", e) }
    }

    private fun queueForPhone(context: Context, path: String, timestamp: Long, label: String) {
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putLong("timestamp", timestamp)
            // DataClient coalesces items by path; this marker makes every alert
            // and cancel a distinct update even when the path is unchanged.
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context)
            .putDataItem(request)
            .addOnSuccessListener { Log.d("WearDataSender", "Queued $label for phone") }
            .addOnFailureListener { e -> Log.e("WearDataSender", "Failed to queue $label", e) }
    }
}
