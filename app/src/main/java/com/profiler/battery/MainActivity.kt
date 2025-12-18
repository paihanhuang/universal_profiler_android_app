package com.profiler.battery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Main activity for battery profiler app.
 * Provides UI for starting/stopping profiler and viewing status.
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val STATUS_UPDATE_INTERVAL_MS = 1000L
    }
    
    private lateinit var statusText: TextView
    private lateinit var eventCountText: TextView
    private lateinit var logSizeText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var viewLogButton: Button
    private lateinit var clearLogButton: Button
    
    private val handler = Handler(Looper.getMainLooper())
    private val statusUpdater = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, STATUS_UPDATE_INTERVAL_MS)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Initialize views
        statusText = findViewById(R.id.statusText)
        eventCountText = findViewById(R.id.eventCountText)
        logSizeText = findViewById(R.id.logSizeText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        viewLogButton = findViewById(R.id.viewLogButton)
        clearLogButton = findViewById(R.id.clearLogButton)
        
        // Set up button listeners
        startButton.setOnClickListener { startProfiling() }
        stopButton.setOnClickListener { stopProfiling() }
        viewLogButton.setOnClickListener { viewLog() }
        clearLogButton.setOnClickListener { clearLog() }
        
        // Request notification permission for Android 13+
        requestNotificationPermission()
        
        // Initial status update
        updateStatus()
    }
    
    override fun onResume() {
        super.onResume()
        handler.post(statusUpdater)
    }
    
    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusUpdater)
    }
    
    private fun startProfiling() {
        ProfilerService.start(this)
        Toast.makeText(this, "Profiler started", Toast.LENGTH_SHORT).show()
        updateStatus()
    }
    
    private fun stopProfiling() {
        ProfilerService.stop(this)
        Toast.makeText(this, "Profiler stopped", Toast.LENGTH_SHORT).show()
        updateStatus()
    }
    
    private fun viewLog() {
        val logger = EventLogger(this)
        val log = logger.readLog()
        
        if (log.isEmpty()) {
            Toast.makeText(this, "No log data yet", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Show log in a simple dialog or share
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_TEXT, log)
            putExtra(Intent.EXTRA_SUBJECT, "Battery Profiler Log")
        }
        startActivity(Intent.createChooser(intent, "Share Log"))
    }
    
    private fun clearLog() {
        val logger = EventLogger(this)
        logger.clearLog()
        Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
        updateStatus()
    }
    
    private fun updateStatus() {
        val isRunning = ProfilerService.isRunning
        val eventCount = ProfilerService.eventBuffer.totalCount()
        val logger = EventLogger(this)
        val logSize = logger.getLogFileSize()
        
        statusText.text = if (isRunning) "Status: RUNNING" else "Status: STOPPED"
        statusText.setTextColor(
            if (isRunning) 
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            else 
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
        
        eventCountText.text = "Events captured: $eventCount"
        logSizeText.text = "Log size: ${formatBytes(logSize)}"
        
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
    
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, 
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }
}
