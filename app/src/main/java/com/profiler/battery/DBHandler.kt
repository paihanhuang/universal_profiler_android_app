package com.profiler.battery

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Centralized database handler with message queue.
 * 
 * ALL database operations MUST go through DBHandler via DBMessage.
 * Operations are processed in FIFO order on a dedicated "DBHandler" thread.
 * 
 * Features:
 * - Thread confinement: Only DBHandler thread touches the database
 * - Message queue: Lock-free ConcurrentLinkedQueue for submissions
 * - Batching: Screen state events are batched (500 ops or 5 min)
 * - FIFO ordering: Messages processed in order of submission
 */
class DBHandler private constructor(private val context: Context) {
    
    companion object {
        // Batch thresholds for screen state events
        const val MAX_BATCH_SIZE = 500
        const val FLUSH_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes
        
        // Thread name for identification
        const val THREAD_NAME = "DBHandler"
        
        // Singleton instance
        @Volatile
        private var INSTANCE: DBHandler? = null
        
        fun getInstance(context: Context): DBHandler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DBHandler(context.applicationContext).also { INSTANCE = it }
            }
        }
        
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.shutdown()
                INSTANCE = null
            }
        }
    }
    
    // Message queue (lock-free, thread-safe)
    private val messageQueue = ConcurrentLinkedQueue<DBMessage>()
    
    // Dedicated handler thread
    private val handlerThread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(handlerThread.looper)
    
    // Database instance (only accessed on handler thread)
    private var database: ProfilerDatabase? = null
    
    // Screen state batch buffer (only accessed on handler thread)
    private val screenStateBatch = ArrayList<ScreenStateEvent>(MAX_BATCH_SIZE)
    private var firstBatchEventTimeMs = 0L
    private var batchFlushScheduled = false
    
    // State
    private val isShuttingDown = AtomicBoolean(false)
    
    // Statistics
    private val _messagesProcessed = AtomicLong(0)
    private val _batchFlushes = AtomicLong(0)
    val messagesProcessed: Long get() = _messagesProcessed.get()
    val batchFlushes: Long get() = _batchFlushes.get()
    
    // Runnable for processing messages
    private val processMessagesRunnable = Runnable { processMessages() }
    
    // Runnable for scheduled batch flush
    private val scheduledBatchFlushRunnable = Runnable {
        batchFlushScheduled = false
        flushScreenStateBatch()
    }
    
    init {
        // Initialize database on handler thread
        handler.post {
            database = ProfilerDatabase.getInstance(context)
        }
    }
    
    /**
     * Send a message to the handler.
     * Thread-safe - can be called from any thread.
     * Messages are processed in FIFO order.
     */
    fun send(message: DBMessage) {
        if (isShuttingDown.get()) {
            // Ignore messages after shutdown initiated
            return
        }
        
        messageQueue.offer(message)
        handler.post(processMessagesRunnable)
    }
    
    /**
     * Send a message and wait for completion.
     * Blocks until the message is processed.
     */
    fun sendSync(message: DBMessage, timeoutMs: Long = 5000) {
        val latch = CountDownLatch(1)
        
        // Wrap message with completion callback
        val wrappedMessage = when (message) {
            is DBMessage.FlushPending -> message.copy(onComplete = { latch.countDown() })
            else -> {
                // For other messages, send original and post countdown after
                messageQueue.offer(message)
                handler.post {
                    processMessages()
                    latch.countDown()
                }
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                return
            }
        }
        
        send(wrappedMessage)
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }
    
    /**
     * Get current message queue size.
     */
    fun getQueueSize(): Int = messageQueue.size
    
    /**
     * Get current batch buffer size.
     */
    fun getBatchSize(): Int = screenStateBatch.size
    
    // ===== Methods below run ONLY on handler thread =====
    
    /**
     * Process all pending messages in FIFO order.
     * MUST be called on handler thread.
     */
    private fun processMessages() {
        val db = database ?: return
        
        var message = messageQueue.poll()
        while (message != null) {
            processMessage(db, message)
            _messagesProcessed.incrementAndGet()
            message = messageQueue.poll()
        }
    }
    
    /**
     * Process a single message.
     * MUST be called on handler thread.
     */
    private fun processMessage(db: ProfilerDatabase, message: DBMessage) {
        when (message) {
            is DBMessage.InsertSystemInfo -> {
                db.insertSystemInfo(message.context, message.sessionId)
            }
            
            is DBMessage.InsertScreenState -> {
                addToScreenStateBatch(message.event)
            }
            
            is DBMessage.InsertScreenStateBatch -> {
                message.events.forEach { addToScreenStateBatch(it) }
            }
            
            is DBMessage.InsertMetadata -> {
                db.insertMetadata(message.key, message.value, message.sessionId)
            }
            
            is DBMessage.GetEventCount -> {
                val count = db.getEventCount()
                message.callback(count)
            }
            
            is DBMessage.GetDatabaseSize -> {
                val size = db.getDatabaseSize(message.context)
                message.callback(size)
            }
            
            is DBMessage.ExportToCsv -> {
                // Flush pending batch first
                flushScreenStateBatch()
                val csv = db.exportToCsv()
                message.callback(csv)
            }
            
            is DBMessage.FlushPending -> {
                flushScreenStateBatch()
                message.onComplete?.invoke()
            }
            
            is DBMessage.Shutdown -> {
                // Flush and stop
                flushScreenStateBatch()
                isShuttingDown.set(true)
            }
        }
    }
    
    /**
     * Add event to screen state batch.
     * Flushes when threshold reached.
     * MUST be called on handler thread.
     */
    private fun addToScreenStateBatch(event: ScreenStateEvent) {
        if (screenStateBatch.isEmpty()) {
            firstBatchEventTimeMs = SystemClock.elapsedRealtime()
            scheduleBatchFlush()
        }
        
        screenStateBatch.add(event)
        
        if (screenStateBatch.size >= MAX_BATCH_SIZE) {
            flushScreenStateBatch()
        }
    }
    
    /**
     * Flush screen state batch to database.
     * MUST be called on handler thread.
     */
    private fun flushScreenStateBatch() {
        if (screenStateBatch.isEmpty()) return
        
        // Cancel scheduled flush
        handler.removeCallbacks(scheduledBatchFlushRunnable)
        batchFlushScheduled = false
        
        // Copy and clear
        val eventsToFlush = ArrayList(screenStateBatch)
        screenStateBatch.clear()
        
        // Write to database
        database?.insertScreenStateBatch(eventsToFlush)
        _batchFlushes.incrementAndGet()
    }
    
    /**
     * Schedule batch flush after interval.
     * MUST be called on handler thread.
     */
    private fun scheduleBatchFlush() {
        if (!batchFlushScheduled) {
            handler.postDelayed(scheduledBatchFlushRunnable, FLUSH_INTERVAL_MS)
            batchFlushScheduled = true
        }
    }
    
    /**
     * Shutdown the handler.
     * Flushes pending data and stops the thread.
     */
    private fun shutdown() {
        // Send shutdown message and wait
        val latch = CountDownLatch(1)
        send(DBMessage.FlushPending { latch.countDown() })
        latch.await(5, TimeUnit.SECONDS)
        
        // Stop thread
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
    }
}
