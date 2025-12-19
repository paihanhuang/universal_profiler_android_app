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
        CoreService.start(this)
        Toast.makeText(this, "Core Service started", Toast.LENGTH_SHORT).show()
        updateStatus()
    }
    
    private fun stopProfiling() {
        // 1. Stop the service
        CoreService.stop(this)
        
        // 2. Flush any pending data to disk synchronously
        val dbHandler = DBHandler.getInstance(this)
        // Use a background thread to avoid blocking UI during flush/share prep
        Thread {
            dbHandler.sendSync(DBMessage.FlushPending())
            
            // 3. Prepare and share the file
            shareDatabaseFile()
        }.start()
        
        Toast.makeText(this, "Stopping service and preparing email...", Toast.LENGTH_LONG).show()
        updateStatus()
    }
    
    private fun shareDatabaseFile() {
        try {
            val dbPath = getDatabasePath(ProfilerDatabase.DATABASE_NAME)
            if (!dbPath.exists()) {
                runOnUiThread { Toast.makeText(this, "Database not found", Toast.LENGTH_SHORT).show() }
                return
            }

            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                dbPath
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.sqlite3" // Or application/octet-stream
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Battery Profiler Database Export")
                putExtra(Intent.EXTRA_TEXT, "Attached is the profiler.db database file.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooser = Intent.createChooser(intent, "Email Database")
            // Grant permissions to the choosing app
            val resInfoList = packageManager.queryIntentActivities(chooser, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo.packageName
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            runOnUiThread {
                startActivity(chooser)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "Failed to share DB: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun viewLog() {
        val dbHandler = DBHandler.getInstance(this)
        
        // Request CSV export via message queue with callback
        dbHandler.send(DBMessage.ExportToCsv { csv ->
            runOnUiThread {
                if (csv.lines().size <= 1) {
                    Toast.makeText(this, "No data yet", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                
                // Share CSV data
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_TEXT, csv)
                    putExtra(Intent.EXTRA_SUBJECT, "Battery Profiler Data")
                }
                startActivity(Intent.createChooser(intent, "Share Data"))
            }
        })
    }
    
    private fun clearLog() {
        // Close handlers and delete database
        DBHandler.closeInstance()
        ProfilerDatabase.closeInstance()
        deleteDatabase(ProfilerDatabase.DATABASE_NAME)
        Toast.makeText(this, "Database cleared", Toast.LENGTH_SHORT).show()
        updateStatus()
    }
    
    private fun updateStatus() {
        val isRunning = CoreService.isRunning
        
        // Update UI immediately for status
        statusText.text = if (isRunning) "Status: RUNNING" else "Status: STOPPED"
        statusText.setTextColor(
            if (isRunning) 
                ContextCompat.getColor(this, android.R.color.holo_green_dark)
            else 
                ContextCompat.getColor(this, android.R.color.holo_red_dark)
        )
        
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
        
        // Get database stats via message queue
        val dbHandler = DBHandler.getInstance(this)
        
        dbHandler.send(DBMessage.GetEventCount { count ->
            runOnUiThread {
                eventCountText.text = "Events in DB: $count"
            }
        })
        
        dbHandler.send(DBMessage.GetDatabaseSize(this) { size ->
            runOnUiThread {
                logSizeText.text = "DB size: ${formatBytes(size)}"
            }
        })
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
