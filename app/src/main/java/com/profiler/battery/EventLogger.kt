package com.profiler.battery

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Event logger for writing power events to CSV file.
 * 
 * Design principles:
 * - Called ONLY when screen is ON (suspend-safe)
 * - Writes boot session header for correlation across reboots
 * - Uses simple CSV format for minimal overhead and easy parsing
 * 
 * Log format:
 * - Header with boot session info (written once per session)
 * - Data: monotonic_ms,wall_clock_ms,event_type,battery_mah
 */
class EventLogger(private val context: Context) {
    
    companion object {
        private const val LOG_FILE_NAME = "power_events.csv"
        private const val HEADER_MARKER = "# Boot Session:"
    }
    
    private val logFile: File
        get() = File(context.filesDir, LOG_FILE_NAME)
    
    private var sessionInitialized = false
    
    /**
     * Initialize log file with boot session header.
     * Called once when profiler starts.
     */
    fun initSession() {
        if (sessionInitialized) return
        
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        val header = buildString {
            appendLine()
            appendLine("$HEADER_MARKER ${BootSession.bootId}")
            appendLine("# Boot Wall Clock: ${BootSession.bootWallClockMs} (${dateFormat.format(Date(BootSession.bootWallClockMs))})")
            appendLine("# Session Start: ${BootSession.appStartWallClockMs} (${dateFormat.format(Date(BootSession.appStartWallClockMs))})")
            // System information for debugging
            append(SystemInfo.getLogHeader())
            appendLine("# Format: monotonic_ms,wall_clock_ms,event_type,battery_mah")
            appendLine("# Event Types: 0=SCREEN_OFF, 1=SCREEN_ON, 2=USER_UNLOCK")
            appendLine("# Monotonic: SystemClock.elapsedRealtime() - immune to user time changes")
            appendLine("# Wall Clock: System.currentTimeMillis() - user-visible time")
        }
        
        logFile.appendText(header)
        sessionInitialized = true
    }
    
    /**
     * Append events to log file.
     * Should only be called when screen is ON.
     * 
     * @param events CSV-formatted event data
     */
    fun appendEvents(events: String) {
        if (events.isNotEmpty()) {
            logFile.appendText(events)
        }
    }
    
    /**
     * Get the log file path for display/export.
     */
    fun getLogFilePath(): String = logFile.absolutePath
    
    /**
     * Get log file size in bytes.
     */
    fun getLogFileSize(): Long = if (logFile.exists()) logFile.length() else 0
    
    /**
     * Read log file contents.
     */
    fun readLog(): String = if (logFile.exists()) logFile.readText() else ""
    
    /**
     * Clear log file (for testing/reset).
     */
    fun clearLog() {
        if (logFile.exists()) {
            logFile.delete()
        }
        sessionInitialized = false
    }
    
    /**
     * Export log to external storage or share location.
     * Returns the exported file path.
     */
    fun exportLog(exportDir: File): File? {
        if (!logFile.exists()) return null
        
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val exportFile = File(exportDir, "power_events_$timestamp.csv")
        
        return try {
            logFile.copyTo(exportFile, overwrite = true)
            exportFile
        } catch (e: Exception) {
            null
        }
    }
}
