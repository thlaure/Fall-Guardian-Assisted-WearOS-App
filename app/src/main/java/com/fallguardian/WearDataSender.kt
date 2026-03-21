package com.fallguardian

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer

/**
 * Sends fall/cancel messages to the paired phone via the Wearable MessageClient API.
 * MessageClient is node-based and works across different package names.
 */
object WearDataSender {

    var alertActive by mutableStateOf(false)
        private set
    var remainingSeconds by mutableStateOf(30)
        private set
    var permissionDenied by mutableStateOf(false)
        internal set

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (remainingSeconds > 1) {
                remainingSeconds--
                handler.postDelayed(this, 1_000L)
            } else {
                remainingSeconds = 0
                alertActive = false
            }
        }
    }

    fun sendFallEvent(context: Context, timestamp: Long) {
        val payload = ByteBuffer.allocate(8).putLong(timestamp).array()
        sendToPhone(context, "/fall_event", payload, "fall event")

        handler.removeCallbacks(tickRunnable)
        alertActive = true
        remainingSeconds = 30
        handler.postDelayed(tickRunnable, 1_000L)
    }

    fun sendCancelAlert(context: Context) {
        handler.removeCallbacks(tickRunnable)
        alertActive = false
        remainingSeconds = 30
        sendToPhone(context, "/cancel_alert", ByteArray(0), "cancel alert")
    }

    private fun sendToPhone(context: Context, path: String, payload: ByteArray, label: String) {
        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.w("WearDataSender", "No connected nodes found")
                    return@addOnSuccessListener
                }
                nodes.forEach { node ->
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, path, payload)
                        .addOnSuccessListener { Log.d("WearDataSender", "Sent $label to ${node.displayName}") }
                        .addOnFailureListener { e -> Log.e("WearDataSender", "Failed to send $label", e) }
                }
            }
            .addOnFailureListener { e -> Log.e("WearDataSender", "Failed to get connected nodes", e) }
    }
}
