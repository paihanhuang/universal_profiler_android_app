package com.profiler.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service for continuous power event profiling.
 * 
 * Design principles:
 * - Minimal foreground notification (required for Android 8+)
 * - Registers BroadcastReceiver for power events
 * - Flushes buffer to file only when screen turns ON (suspend-safe)
 * - NO wake locks - does not interfere with system suspend
 */
class ProfilerService : Service() {
    
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "profiler_channel"
        
        // Shared event buffer - accessible from receiver and for status display
        val eventBuffer = EventRingBuffer(1024)
        
        // Current session ID
        var sessionId: String = ""
            private set
        
        // Service state
        var isRunning = false
            private set
        
        fun start(context: Context) {
            val intent = Intent(context, ProfilerService::class.java)
            context.startForegroundService(intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, ProfilerService::class.java)
            context.stopService(intent)
        }
    }
    
    private lateinit var receiver: PowerEventReceiver
    private lateinit var dbHandler: DBHandler
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        
        // Generate session ID based on boot ID + app start time
        sessionId = "${BootSession.bootId}_${BootSession.appStartWallClockMs}"
        
        // Initialize DB handler (centralized database access)
        dbHandler = DBHandler.getInstance(this)
        
        // Insert system metadata for this session via message queue
        dbHandler.send(DBMessage.InsertSystemInfo(this, sessionId))
        
        // Create receiver with callback to flush buffer
        receiver = PowerEventReceiver(eventBuffer) {
            // Called when screen turns ON - parse buffer and send to DBHandler
            flushBufferAsync()
        }
        
        // Register for power events
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(receiver, filter)
        
        // Start foreground with minimal notification
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        isRunning = true
    }
    
    override fun onDestroy() {
        isRunning = false
        
        // Unregister receiver
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        
        // Final flush - parse remaining buffer and flush via DBHandler
        flushBufferSync()
        dbHandler.sendSync(DBMessage.FlushPending())
        
        // Cancel coroutine scope
        serviceScope.cancel()
        
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    /**
     * Flush buffer asynchronously (called on SCREEN_ON).
     * Uses IO dispatcher to avoid blocking main thread.
     */
    private fun flushBufferAsync() {
        serviceScope.launch {
            flushBufferSync()
        }
    }
    
    /**
     * Flush ring buffer to DBHandler via message queue.
     * Events are batched by DBHandler (500 ops or 5 min threshold).
     */
    private fun flushBufferSync() {
        // Drain buffer
        val sb = StringBuilder()
        val count = eventBuffer.drain(sb)
        if (count == 0) return
        
        // Parse CSV into event list
        val events = mutableListOf<ScreenStateEvent>()
        
        sb.lines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split(",")
            if (parts.size >= 5) {
                try {
                    events.add(
                        ScreenStateEvent(
                            monotonicMs = parts[0].toLong(),
                            wallClockMs = parts[1].toLong(),
                            eventType = parts[2].toInt(),
                            batteryMah = parts[3].toIntOrNull(),
                            reason = parts[4].toIntOrNull() ?: 0,
                            sessionId = sessionId
                        )
                    )
                } catch (e: Exception) {
                    // Skip malformed entries
                }
            }
        }
        
        // Send to DBHandler via message queue (batched, FIFO)
        if (events.isNotEmpty()) {
            dbHandler.send(DBMessage.InsertScreenStateBatch(events))
        }
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Battery Profiler",
            NotificationManager.IMPORTANCE_LOW  // Minimal intrusion
        ).apply {
            description = "Battery profiling is active"
            setShowBadge(false)
        }
        
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
    
    private fun createNotification(): Notification {
        // Intent to open main activity when notification tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Battery Profiler Active")
            .setContentText("Monitoring display events and battery")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
