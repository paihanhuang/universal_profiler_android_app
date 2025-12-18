package com.profiler.battery

import android.os.SystemClock
import java.io.File

/**
 * Boot session tracking for correlating events across reboots.
 * 
 * Since SystemClock.elapsedRealtime() resets on reboot, we need a unique
 * boot identifier to correlate monotonic timestamps with wall clock time.
 */
object BootSession {
    
    /**
     * Unique identifier for current boot session.
     * Read from /proc/sys/kernel/random/boot_id on Linux/Android.
     * This value is generated fresh on each boot.
     */
    val bootId: String by lazy {
        try {
            File("/proc/sys/kernel/random/boot_id").readText().trim()
        } catch (e: Exception) {
            // Fallback: generate based on boot time (less reliable but works)
            "boot_${SystemClock.elapsedRealtime()}_${System.currentTimeMillis()}"
        }
    }
    
    /**
     * Wall clock time at boot.
     * Calculated as: current wall clock - elapsed time since boot.
     * This allows converting monotonic timestamps to approximate wall clock.
     */
    val bootWallClockMs: Long by lazy {
        System.currentTimeMillis() - SystemClock.elapsedRealtime()
    }
    
    /**
     * App start time in monotonic milliseconds.
     * Useful for calculating session duration.
     */
    val appStartMonotonicMs: Long = SystemClock.elapsedRealtime()
    
    /**
     * App start time in wall clock milliseconds.
     */
    val appStartWallClockMs: Long = System.currentTimeMillis()
}
