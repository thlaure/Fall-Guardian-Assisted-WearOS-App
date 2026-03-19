package com.fallguardian

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import java.nio.ByteBuffer

/**
 * Sends fall event to the paired phone via the Wearable MessageClient API.
 * MessageClient is node-based and works across different package names.
 */
object WearDataSender {

    fun sendFallEvent(context: Context, timestamp: Long) {
        val payload = ByteBuffer.allocate(8).putLong(timestamp).array()

        Wearable.getNodeClient(context).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    Log.w("WearDataSender", "No connected nodes found")
                    return@addOnSuccessListener
                }
                nodes.forEach { node ->
                    Log.d("WearDataSender", "Sending fall event to node ${node.displayName} timestamp=$timestamp")
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, "/fall_event", payload)
                        .addOnSuccessListener { Log.d("WearDataSender", "Fall event sent successfully") }
                        .addOnFailureListener { e -> Log.e("WearDataSender", "Failed to send fall event", e) }
                }
            }
            .addOnFailureListener { e -> Log.e("WearDataSender", "Failed to get connected nodes", e) }
    }
}
