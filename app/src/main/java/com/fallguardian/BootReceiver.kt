package com.fallguardian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the fall detection service after device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(
                Intent(context, FallDetectionService::class.java)
            )
        }
    }
}
