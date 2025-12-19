package com.profiler.battery

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Batched writer for database operations with configurable thresholds.
 * 
 * Flushes queued events when either:
 * - 500 operations are queued (count threshold)
 * - 5 minutes have passed since first queued event (time threshold)
 * 
 * Thread-safe and designed for minimal overhead.
 */
class BatchWriter(private val context: Context) {
    
    companion object {
        // Batch thresholds
        const val MAX_BATCH_SIZE = 500
        const val FLUSH_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes
        
        // Singleton instance
        @Volatile
        private var INSTANCE: BatchWriter? = null
        
        fun getInstance(context: Context): BatchWriter {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BatchWriter(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.shutdown()
                INSTANCE = null
            }
        }
    }
    
    // Event queue
    private val eventQueue = ArrayList<ScreenStateEvent>(MAX_BATCH_SIZE)
    private val queueLock = ReentrantLock()
    
    // Timer for time-based flush
    private val handlerThread = HandlerThread("BatchWriter").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private var flushScheduled = false
    private var firstEventTimeMs = 0L
    
    // Statistics
    @Volatile var totalEventsQueued = 0L
        private set
    @Volatile var totalFlushes = 0L
        private set
    
    private val flushRunnable = Runnable {
        flushIfNeeded(forceFlush = true)
    }
    
    /**
     * Queue a screen state event for batched writing.
     * Will trigger flush if batch size threshold is reached.
     */
    fun queueEvent(event: ScreenStateEvent) {
        queueLock.withLock {
            // Record first event time for time-based flush
            if (eventQueue.isEmpty()) {
                firstEventTimeMs = SystemClock.elapsedRealtime()
                scheduleFlush()
            }
            
            eventQueue.add(event)
            totalEventsQueued++
            
            // Check if we hit the batch size threshold
            if (eventQueue.size >= MAX_BATCH_SIZE) {
                flushNow()
            }
        }
    }
    
    /**
     * Queue multiple events at once.
     */
    fun queueEvents(events: List<ScreenStateEvent>) {
        if (events.isEmpty()) return
        
        queueLock.withLock {
            if (eventQueue.isEmpty()) {
                firstEventTimeMs = SystemClock.elapsedRealtime()
                scheduleFlush()
            }
            
            eventQueue.addAll(events)
            totalEventsQueued += events.size
            
            // Flush if we exceed threshold
            while (eventQueue.size >= MAX_BATCH_SIZE) {
                flushNow()
            }
        }
    }
    
    /**
     * Force flush all queued events immediately.
     * Call this when profiler stops or app is closing.
     */
    fun forceFlush() {
        flushIfNeeded(forceFlush = true)
    }
    
    /**
     * Get current queue size.
     */
    fun getQueueSize(): Int = queueLock.withLock { eventQueue.size }
    
    /**
     * Check if flush is needed and perform it.
     */
    private fun flushIfNeeded(forceFlush: Boolean = false) {
        val eventsToFlush: List<ScreenStateEvent>
        
        queueLock.withLock {
            // Cancel scheduled flush
            handler.removeCallbacks(flushRunnable)
            flushScheduled = false
            
            if (eventQueue.isEmpty()) return
            
            val timeSinceFirst = SystemClock.elapsedRealtime() - firstEventTimeMs
            val shouldFlush = forceFlush || 
                              eventQueue.size >= MAX_BATCH_SIZE || 
                              timeSinceFirst >= FLUSH_INTERVAL_MS
            
            if (!shouldFlush) {
                // Re-schedule if we still have events
                if (eventQueue.isNotEmpty()) {
                    scheduleFlush()
                }
                return
            }
            
            // Take all events for flushing
            eventsToFlush = ArrayList(eventQueue)
            eventQueue.clear()
        }
        
        // Flush outside lock to minimize lock time
        if (eventsToFlush.isNotEmpty()) {
            val db = ProfilerDatabase.getInstance(context)
            db.insertScreenStateBatch(eventsToFlush)
            totalFlushes++
        }
    }
    
    /**
     * Flush current batch immediately (called while holding lock).
     */
    private fun flushNow() {
        // Cancel scheduled flush
        handler.removeCallbacks(flushRunnable)
        flushScheduled = false
        
        if (eventQueue.isEmpty()) return
        
        // Take up to MAX_BATCH_SIZE events
        val batchSize = minOf(eventQueue.size, MAX_BATCH_SIZE)
        val eventsToFlush = ArrayList(eventQueue.subList(0, batchSize))
        
        // Remove flushed events from queue
        repeat(batchSize) { eventQueue.removeAt(0) }
        
        // Flush outside lock would be better, but we're called from within lock
        // So we do it here - the DB operations are still efficient
        val db = ProfilerDatabase.getInstance(context)
        db.insertScreenStateBatch(eventsToFlush)
        totalFlushes++
        
        // Reset first event time if queue still has events
        if (eventQueue.isNotEmpty()) {
            firstEventTimeMs = SystemClock.elapsedRealtime()
            scheduleFlush()
        }
    }
    
    /**
     * Schedule a flush after FLUSH_INTERVAL_MS.
     */
    private fun scheduleFlush() {
        if (!flushScheduled) {
            handler.postDelayed(flushRunnable, FLUSH_INTERVAL_MS)
            flushScheduled = true
        }
    }
    
    /**
     * Shutdown the batch writer.
     * Flushes remaining events and stops the handler thread.
     */
    fun shutdown() {
        // Final flush
        forceFlush()
        
        // Stop handler thread
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
    }
}
