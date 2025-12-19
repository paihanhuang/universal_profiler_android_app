package com.profiler.battery

import android.content.Context

/**
 * Message types for DBHandler message queue.
 * 
 * All database operations are represented as messages that are
 * processed by DBHandler in FIFO order on a dedicated thread.
 */
sealed class DBMessage {
    
    /**
     * Insert system metadata for a session.
     * Called once when profiler starts.
     */
    data class InsertSystemInfo(
        val context: Context,
        val sessionId: String
    ) : DBMessage()
    
    /**
     * Insert a single screen state event.
     */
    data class InsertScreenState(
        val event: ScreenStateEvent
    ) : DBMessage()
    
    /**
     * Insert multiple screen state events in a batch.
     * All events are wrapped in a single transaction.
     */
    data class InsertScreenStateBatch(
        val events: List<ScreenStateEvent>
    ) : DBMessage()
    
    /**
     * Insert metadata key-value pair.
     */
    data class InsertMetadata(
        val key: String,
        val value: String?,
        val sessionId: String
    ) : DBMessage()
    
    /**
     * Get event count (async result via callback).
     */
    data class GetEventCount(
        val callback: (Int) -> Unit
    ) : DBMessage()
    
    /**
     * Get database size (async result via callback).
     */
    data class GetDatabaseSize(
        val context: Context,
        val callback: (Long) -> Unit
    ) : DBMessage()
    
    /**
     * Export data to CSV (async result via callback).
     */
    data class ExportToCsv(
        val callback: (String) -> Unit
    ) : DBMessage()
    
    /**
     * Flush any pending batched events.
     * Used for graceful shutdown.
     */
    data class FlushPending(
        val onComplete: (() -> Unit)? = null
    ) : DBMessage()
    
    /**
     * Shutdown the handler.
     * Processes remaining messages then stops.
     */
    object Shutdown : DBMessage()
}
