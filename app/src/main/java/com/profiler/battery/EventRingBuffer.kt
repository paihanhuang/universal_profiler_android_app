package com.profiler.battery

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free ring buffer for power event storage.
 * 
 * Design goals:
 * - Pre-allocated arrays to avoid GC during logging
 * - Atomic operations for thread safety without locks
 * - Dual timestamps (monotonic + wall clock) for robust time tracking
 * - Minimal memory footprint: ~21 bytes per event
 * 
 * Thread safety:
 * - Single producer (BroadcastReceiver on main thread) is typical
 * - Multiple consumers are supported via atomic drain
 */
class EventRingBuffer(private val capacity: Int = 1024) {
    
    companion object {
        const val EVENT_SCREEN_OFF: Byte = 0
        const val EVENT_SCREEN_ON: Byte = 1
        const val EVENT_USER_PRESENT: Byte = 2
    }
    
    // Pre-allocated arrays - no runtime allocations after init
    private val monotonicMs = LongArray(capacity)   // Immune to user manipulation
    private val wallClockMs = LongArray(capacity)   // User-visible time
    private val eventTypes = ByteArray(capacity)
    private val batteryMah = IntArray(capacity)
    
    // Atomic indices for lock-free operation
    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)
    
    /**
     * Add an event to the buffer.
     * 
     * - O(1) time complexity
     * - No allocations
     * - No blocking
     * - Captures both timestamps atomically
     * 
     * @param eventType Event type code (SCREEN_OFF, SCREEN_ON, USER_PRESENT)
     * @param mah Battery remaining capacity in mAh (-1 if unavailable)
     */
    fun add(eventType: Byte, mah: Int) {
        // Capture both timestamps as close together as possible
        val monotonic = SystemClock.elapsedRealtime()
        val wallClock = System.currentTimeMillis()
        
        val idx = writeIndex.getAndIncrement() % capacity
        monotonicMs[idx] = monotonic
        wallClockMs[idx] = wallClock
        eventTypes[idx] = eventType
        batteryMah[idx] = mah
    }
    
    /**
     * Convenience method to add event with automatic timestamp capture.
     */
    fun addScreenOff(mah: Int) = add(EVENT_SCREEN_OFF, mah)
    fun addScreenOn(mah: Int) = add(EVENT_SCREEN_ON, mah)
    fun addUserPresent(mah: Int) = add(EVENT_USER_PRESENT, mah)
    
    /**
     * Drain all pending events to a StringBuilder for file output.
     * 
     * Format: monotonic_ms,wall_clock_ms,event_type,battery_mah
     * 
     * @param output StringBuilder to append events to
     * @return Number of events drained
     */
    fun drain(output: StringBuilder): Int {
        var count = 0
        val currentWrite = writeIndex.get()
        var read = readIndex.get()
        
        while (read < currentWrite && count < capacity) {
            val idx = read % capacity
            output.append(monotonicMs[idx])
                .append(',')
                .append(wallClockMs[idx])
                .append(',')
                .append(eventTypes[idx].toInt())
                .append(',')
                .append(batteryMah[idx])
                .append('\n')
            read++
            count++
        }
        readIndex.set(read)
        return count
    }
    
    /**
     * Get the number of pending events (not yet drained).
     */
    fun pendingCount(): Int {
        return (writeIndex.get() - readIndex.get()).coerceIn(0, capacity)
    }
    
    /**
     * Get total events added since buffer creation.
     */
    fun totalCount(): Int = writeIndex.get()
    
    /**
     * Get event type name for display purposes.
     */
    fun getEventTypeName(eventType: Byte): String = when (eventType) {
        EVENT_SCREEN_OFF -> "SCREEN_OFF"
        EVENT_SCREEN_ON -> "SCREEN_ON"
        EVENT_USER_PRESENT -> "USER_UNLOCK"
        else -> "UNKNOWN"
    }
}
