package com.profiler.battery

/**
 * Data class for screen state event (used for batch inserts).
 */
data class ScreenStateEvent(
    val monotonicMs: Long,
    val wallClockMs: Long,
    val eventType: Int,
    val batteryMah: Int?,
    val reason: Int = 0,
    val sessionId: String
)

/**
 * Data class for screen state records (read from DB).
 */
data class ScreenStateRecord(
    val id: Long,
    val monotonicMs: Long,
    val wallClockMs: Long,
    val eventType: Int,
    val batteryMah: Int,
    val reason: Int,
    val sessionId: String
) {
    fun getEventTypeName(): String = when (eventType) {
        ProfilerDatabase.EVENT_SCREEN_OFF -> "SCREEN_OFF"
        ProfilerDatabase.EVENT_SCREEN_ON -> "SCREEN_ON"
        ProfilerDatabase.EVENT_USER_PRESENT -> "USER_UNLOCK"
        else -> "UNKNOWN"
    }
    
    fun getReasonName(): String = PowerEventReceiver.getReasonName(reason)
}
