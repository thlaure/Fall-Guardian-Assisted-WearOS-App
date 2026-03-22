package com.fallguardian

import android.content.Context
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/**
 * Receives /thresholds messages from the paired phone and persists them to
 * SharedPreferences. FallDetectionService.prefChangeListener picks up the
 * changes and reloads the algorithm without a restart.
 */
class PhoneMessageListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            "/thresholds" -> handleThresholds(messageEvent.data)
            "/cancel_alert" -> WearDataSender.cancelAlertFromPhone()
        }
    }

    private fun handleThresholds(data: ByteArray) {
        try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            val prefs = getSharedPreferences("fall_guardian", Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (json.has("thresh_freefall"))
                    putFloat("thresh_freefall", json.getDouble("thresh_freefall").toFloat())
                if (json.has("thresh_impact"))
                    putFloat("thresh_impact", json.getDouble("thresh_impact").toFloat())
                if (json.has("thresh_tilt"))
                    putFloat("thresh_tilt", json.getDouble("thresh_tilt").toFloat())
                if (json.has("thresh_freefall_ms"))
                    putInt("thresh_freefall_ms", json.getInt("thresh_freefall_ms"))
            }.apply()
        } catch (_: Exception) {
            // Malformed payload — ignore
        }
    }
}
