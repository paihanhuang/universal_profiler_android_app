package com.profiler.battery

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.telecom.TelecomManager

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
 * 
 * Wake reasons tracked (inferred from context):
 * - POWER_BUTTON: Manual power button press (most common)
 * - NOTIFICATION: Likely woken by notification
 * - INCOMING_CALL: Phone call waking device
 * - UNLOCK: User present event
 * - TIMEOUT: Screen off due to timeout
 * - PROXIMITY: Proximity sensor (during calls)
 * - UNKNOWN: Could not determine
 */
class PowerEventReceiver(
    private val eventBuffer: EventRingBuffer,
    private val broadcastHandler: AndroidBroadcastHandler,
    private val onScreenOn: (() -> Unit)? = null  // Callback for flush trigger
) : BroadcastReceiver() {
    
    companion object {
        // Wake reason constants
        const val REASON_UNKNOWN = 0
        const val REASON_POWER_BUTTON = 1
        const val REASON_NOTIFICATION = 2
        const val REASON_INCOMING_CALL = 3
        const val REASON_UNLOCK = 4
        const val REASON_TIMEOUT = 5
        const val REASON_PROXIMITY = 6
        const val REASON_LIFT = 7
        const val REASON_DOUBLE_TAP = 8
        
        fun getReasonName(reason: Int): String = when (reason) {
            REASON_POWER_BUTTON -> "POWER_BUTTON"
            REASON_NOTIFICATION -> "NOTIFICATION"
            REASON_INCOMING_CALL -> "INCOMING_CALL"
            REASON_UNLOCK -> "UNLOCK"
            REASON_TIMEOUT -> "TIMEOUT"
            REASON_PROXIMITY -> "PROXIMITY"
            REASON_LIFT -> "LIFT"
            REASON_DOUBLE_TAP -> "DOUBLE_TAP"
            else -> "UNKNOWN"
        }
    }
    
    // Track last screen off time to detect timeout
    private var lastScreenOnTime = 0L
    private var lastScreenOffTime = 0L
    
    override fun onReceive(context: Context, intent: Intent) {
        // Capture action on main thread (fast)
        val action = intent.action ?: return
        
        // Capture timestamps IMMEDIATELY upon receipt for precision
        val nowMonotonic = android.os.SystemClock.elapsedRealtime()
        val nowWallClock = System.currentTimeMillis()
        
        // Dispatch all processing to dedicated broadcast handler thread
        broadcastHandler.post {
            processBroadcast(context, action, nowMonotonic, nowWallClock)
        }
    }
    
    /**
     * Process broadcast on dedicated handler thread.
     * Called from AndroidBroadcastHandler thread, NOT main thread.
     */
    private fun processBroadcast(
        context: Context, 
        action: String, 
        timestampMonotonic: Long, 
        timestampWallClock: Long
    ) {
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
        
        when (action) {
            Intent.ACTION_SCREEN_OFF -> {
                val reason = detectScreenOffReason(context)
                lastScreenOffTime = timestampWallClock
                // Memory write only - do NOT flush to file here
                // System is about to suspend, we must not delay it
                eventBuffer.addScreenOff(mah, reason, timestampMonotonic, timestampWallClock)
            }
            
            Intent.ACTION_SCREEN_ON -> {
                val reason = detectScreenOnReason(context)
                lastScreenOnTime = timestampWallClock
                eventBuffer.addScreenOn(mah, reason, timestampMonotonic, timestampWallClock)
                // Safe to flush - user is awake anyway, no additional power cost
                onScreenOn?.invoke()
            }
            
            Intent.ACTION_USER_PRESENT -> {
                eventBuffer.addUserPresent(mah, REASON_UNLOCK, timestampMonotonic, timestampWallClock)
            }
        }
    }
    
    /**
     * Detect likely reason for screen turning ON.
     * Uses available context to infer the cause.
     */
    private fun detectScreenOnReason(context: Context): Int {
        // Check if there's an incoming call
        if (isIncomingCall(context)) {
            return REASON_INCOMING_CALL
        }
        
        // Check keyguard state - if not locked, might be notification wake
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        
        // If keyguard is locked and showing, likely power button
        if (keyguardManager.isKeyguardLocked) {
            return REASON_POWER_BUTTON
        }
        
        // Default to power button as most common cause
        return REASON_POWER_BUTTON
    }
    
    /**
     * Detect likely reason for screen turning OFF.
     */
    private fun detectScreenOffReason(context: Context): Int {
        // Check if in a call - might be proximity sensor
        if (isInCall(context)) {
            return REASON_PROXIMITY
        }
        
        // If screen was on for typical timeout duration (15-60 seconds typical)
        // this might be timeout
        val onDuration = System.currentTimeMillis() - lastScreenOnTime
        if (lastScreenOnTime > 0 && onDuration >= 10_000 && onDuration <= 600_000) {
            // Might be timeout, but hard to distinguish from power button
            // Default to power button as we can't be certain
            return REASON_POWER_BUTTON
        }
        
        return REASON_POWER_BUTTON
    }
    
    /**
     * Check if there's an incoming or active call.
     */
    private fun isInCall(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                telecomManager?.isInCall == true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Check if there's an incoming call (ringing).
     */
    private fun isIncomingCall(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                // isInCall includes ringing
                telecomManager?.isInCall == true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
