package com.profiler.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receiver to start CoreService on device boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CoreService.start(context)
        }
    }
}
