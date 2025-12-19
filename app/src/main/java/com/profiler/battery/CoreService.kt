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
 * Core persistent service for continuous power event profiling.
 * 
 * Responsibilities:
 * - Starts at boot (via BootReceiver)
 * - Always runs in foreground (with minimal notification)
 * - Owns the database handler and event buffer
 * - Registers PowerEventReceiver to track screen state
 * - Does NOT prevent system suspend (no WakeLocks)
 */
class CoreService : Service() {
    
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "core_profiler_channel"
        
        // Shared event buffer
        val eventBuffer = EventRingBuffer(1024)
        
        // Current session ID
        var sessionId: String = ""
            private set
        
        // Service state for UI
        var isRunning = false
            private set
        
        /**
         * Start the service (idempotent).
         */
        fun start(context: Context) {
            val intent = Intent(context, CoreService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stop the service (explicit user action).
         */
        fun stop(context: Context) {
            val intent = Intent(context, CoreService::class.java)
            context.stopService(intent)
        }
    }
    
    private lateinit var receiver: PowerEventReceiver
    private lateinit var dbHandler: DBHandler
    private lateinit var broadcastHandler: AndroidBroadcastHandler
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()
        
        // Generate session ID based on boot ID + app start time
        // If restarting after crash, this preserves the session continuity relative to boot
        sessionId = "${BootSession.bootId}_${BootSession.appStartWallClockMs}"
        
        // Initialize DB handler (centralized database access)
        dbHandler = DBHandler.getInstance(this)
        
        // Insert system metadata for this session (idempotent-ish)
        dbHandler.send(DBMessage.InsertSystemInfo(this, sessionId))
        
        // Create broadcast handler thread
        broadcastHandler = AndroidBroadcastHandler()
        
        // Create receiver with callback to flush buffer
        receiver = PowerEventReceiver(eventBuffer, broadcastHandler) {
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
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY ensures service restarts if killed by system
        return START_STICKY
    }
    
    override fun onDestroy() {
        isRunning = false
        
        // Unregister receiver
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        
        // Final flush
        flushBufferSync()
        dbHandler.sendSync(DBMessage.FlushPending())
        
        // Shutdown broadcast handler
        broadcastHandler.shutdown()
        
        // Cancel coroutine scope
        serviceScope.cancel()
        
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * Flush buffer asynchronously.
     */
    private fun flushBufferAsync() {
        serviceScope.launch {
            flushBufferSync()
        }
    }
    
    /**
     * Flush ring buffer to DBHandler.
     */
    private fun flushBufferSync() {
        val sb = StringBuilder()
        val count = eventBuffer.drain(sb)
        if (count == 0) return
        
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
                    // Skip malformed
                }
            }
        }
        
        if (events.isNotEmpty()) {
            dbHandler.send(DBMessage.InsertScreenStateBatch(events))
        }
    }
    
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Core Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Continuous system profiling"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Profiler Active")
            .setContentText("Monitoring system events")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
