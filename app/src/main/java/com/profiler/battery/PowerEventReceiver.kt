package com.profiler.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager

/**
 * BroadcastReceiver for power-related system events.
 * 
 * Design principles:
 * - Keep onReceive() under 10ms to avoid ANR
 * - NO file I/O in onReceive() - memory buffer only
 * - NO wake locks - let system suspend freely
 * - Battery reading via BatteryManager API is fast and non-blocking
 * 
 * Events captured:
 * - ACTION_SCREEN_OFF: Display turned off
 * - ACTION_SCREEN_ON: Display turned on
 * - ACTION_USER_PRESENT: User unlocked device (after lockscreen)
 */
class PowerEventReceiver(
    private val eventBuffer: EventRingBuffer,
    private val onScreenOn: (() -> Unit)? = null  // Callback for flush trigger
) : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        // Get battery manager for capacity reading
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        
        // Read remaining capacity in μAh, convert to mAh
        // This is a fast, non-blocking call
        val chargeCounter = batteryManager.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
        )
        val mah = if (chargeCounter != Int.MIN_VALUE && chargeCounter > 0) {
            chargeCounter / 1000
        } else {
            -1  // Unavailable on this device
        }
        
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                // Memory write only - do NOT flush to file here
                // System is about to suspend, we must not delay it
                eventBuffer.addScreenOff(mah)
            }
            
            Intent.ACTION_SCREEN_ON -> {
                eventBuffer.addScreenOn(mah)
                // Safe to flush - user is awake anyway, no additional power cost
                onScreenOn?.invoke()
            }
            
            Intent.ACTION_USER_PRESENT -> {
                eventBuffer.addUserPresent(mah)
            }
        }
    }
}
