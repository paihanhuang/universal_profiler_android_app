package com.profiler.battery

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite database helper with object pooling for minimal overhead.
 * 
 * Optimizations:
 * - Singleton instance to avoid repeated DB open/close
 * - Compiled SQLiteStatement for fast inserts (no ContentValues allocation)
 * - Reusable objects to minimize GC pressure
 * - Thread-safe via locks
 * 
 * Tables:
 * - MetaData: System information and session metadata (collected once)
 * - ScreenState: Display on/off events with battery data (continuous)
 */
class ProfilerDatabase private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext,  // Use application context to prevent leaks
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    
    companion object {
        const val DATABASE_NAME = "profiler.db"
        const val DATABASE_VERSION = 2
        
        // MetaData table
        const val TABLE_METADATA = "MetaData"
        const val COL_META_ID = "id"
        const val COL_META_KEY = "key"
        const val COL_META_VALUE = "value"
        const val COL_META_SESSION_ID = "session_id"
        
        // ScreenState table
        const val TABLE_SCREEN_STATE = "ScreenState"
        const val COL_SCREEN_ID = "id"
        const val COL_SCREEN_MONOTONIC_MS = "monotonic_ms"
        const val COL_SCREEN_WALL_CLOCK_MS = "wall_clock_ms"
        const val COL_SCREEN_EVENT_TYPE = "event_type"
        const val COL_SCREEN_BATTERY_MAH = "battery_mah"
        const val COL_SCREEN_SESSION_ID = "session_id"
        
        // Event type constants
        const val EVENT_SCREEN_OFF = 0
        const val EVENT_SCREEN_ON = 1
        const val EVENT_USER_PRESENT = 2
        
        // Singleton instance
        @Volatile
        private var INSTANCE: ProfilerDatabase? = null
        
        /**
         * Get the singleton database instance.
         * Thread-safe double-checked locking.
         */
        fun getInstance(context: Context): ProfilerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProfilerDatabase(context).also { INSTANCE = it }
            }
        }
        
        /**
         * Close the singleton instance (for testing/cleanup).
         */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
    
    // Lock for thread-safe statement access
    private val statementLock = ReentrantLock()
    
    // Pre-compiled statements (created lazily, reused)
    private var insertScreenStateStmt: SQLiteStatement? = null
    private var insertMetadataStmt: SQLiteStatement? = null
    
    // Reusable ContentValues for complex operations (pooled)
    private val reusableContentValues = ContentValues(6)  // Pre-sized for max columns
    
    /**
     * Enable WAL mode for better concurrent read/write performance.
     * WAL (Write-Ahead Logging) allows readers and writers to operate concurrently
     * and provides better performance for most workloads.
     */
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }
    
    override fun onCreate(db: SQLiteDatabase) {
        // Create MetaData table
        db.execSQL("""
            CREATE TABLE $TABLE_METADATA (
                $COL_META_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_META_KEY TEXT NOT NULL,
                $COL_META_VALUE TEXT,
                $COL_META_SESSION_ID TEXT NOT NULL
            )
        """.trimIndent())
        
        db.execSQL("""
            CREATE INDEX idx_metadata_session 
            ON $TABLE_METADATA ($COL_META_SESSION_ID)
        """.trimIndent())
        
        // Create ScreenState table
        db.execSQL("""
            CREATE TABLE $TABLE_SCREEN_STATE (
                $COL_SCREEN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SCREEN_MONOTONIC_MS INTEGER NOT NULL,
                $COL_SCREEN_WALL_CLOCK_MS INTEGER NOT NULL,
                $COL_SCREEN_EVENT_TYPE INTEGER NOT NULL,
                $COL_SCREEN_BATTERY_MAH INTEGER,
                $COL_SCREEN_SESSION_ID TEXT NOT NULL
            )
        """.trimIndent())
        
        db.execSQL("""
            CREATE INDEX idx_screen_session 
            ON $TABLE_SCREEN_STATE ($COL_SCREEN_SESSION_ID)
        """.trimIndent())
        
        db.execSQL("""
            CREATE INDEX idx_screen_time 
            ON $TABLE_SCREEN_STATE ($COL_SCREEN_WALL_CLOCK_MS)
        """.trimIndent())
    }
    
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_METADATA")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SCREEN_STATE")
        onCreate(db)
    }
    
    /**
     * Get or create the compiled statement for screen state inserts.
     * Uses lazy initialization and caches the statement.
     */
    private fun getInsertScreenStateStatement(): SQLiteStatement {
        return insertScreenStateStmt ?: run {
            val stmt = writableDatabase.compileStatement("""
                INSERT INTO $TABLE_SCREEN_STATE 
                ($COL_SCREEN_MONOTONIC_MS, $COL_SCREEN_WALL_CLOCK_MS, $COL_SCREEN_EVENT_TYPE, $COL_SCREEN_BATTERY_MAH, $COL_SCREEN_SESSION_ID)
                VALUES (?, ?, ?, ?, ?)
            """.trimIndent())
            insertScreenStateStmt = stmt
            stmt
        }
    }
    
    /**
     * Get or create the compiled statement for metadata inserts.
     */
    private fun getInsertMetadataStatement(): SQLiteStatement {
        return insertMetadataStmt ?: run {
            val stmt = writableDatabase.compileStatement("""
                INSERT INTO $TABLE_METADATA 
                ($COL_META_KEY, $COL_META_VALUE, $COL_META_SESSION_ID)
                VALUES (?, ?, ?)
            """.trimIndent())
            insertMetadataStmt = stmt
            stmt
        }
    }
    
    /**
     * Insert a screen state event using compiled statement (FAST).
     * No object allocation - reuses compiled statement.
     * Thread-safe via lock.
     */
    fun insertScreenState(
        monotonicMs: Long,
        wallClockMs: Long,
        eventType: Int,
        batteryMah: Int?,
        sessionId: String
    ): Long = statementLock.withLock {
        val stmt = getInsertScreenStateStatement()
        stmt.clearBindings()
        stmt.bindLong(1, monotonicMs)
        stmt.bindLong(2, wallClockMs)
        stmt.bindLong(3, eventType.toLong())
        if (batteryMah != null) {
            stmt.bindLong(4, batteryMah.toLong())
        } else {
            stmt.bindNull(4)
        }
        stmt.bindString(5, sessionId)
        stmt.executeInsert()
    }
    
    /**
     * Insert metadata key-value pair using compiled statement.
     * Thread-safe via lock.
     */
    fun insertMetadata(key: String, value: String?, sessionId: String): Long = statementLock.withLock {
        val stmt = getInsertMetadataStatement()
        stmt.clearBindings()
        stmt.bindString(1, key)
        if (value != null) {
            stmt.bindString(2, value)
        } else {
            stmt.bindNull(2)
        }
        stmt.bindString(3, sessionId)
        stmt.executeInsert()
    }
    
    /**
     * Insert all system info as metadata for a session.
     * Uses batch transaction for efficiency.
     */
    fun insertSystemInfo(context: Context, sessionId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Get full system info including context-dependent fields
            val systemInfo = SystemInfo.asMap(context)
            systemInfo.forEach { (key, value) ->
                insertMetadata(key, value, sessionId)
            }
            // Also insert boot session info
            insertMetadata("boot_id", BootSession.bootId, sessionId)
            insertMetadata("boot_wall_clock_ms", BootSession.bootWallClockMs.toString(), sessionId)
            insertMetadata("app_start_monotonic_ms", BootSession.appStartMonotonicMs.toString(), sessionId)
            insertMetadata("app_start_wall_clock_ms", BootSession.appStartWallClockMs.toString(), sessionId)
            
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    
    /**
     * Batch insert multiple screen state events.
     * Uses transaction for better performance.
     */
    fun insertScreenStateBatch(events: List<ScreenStateEvent>) {
        if (events.isEmpty()) return
        
        val db = writableDatabase
        db.beginTransaction()
        try {
            events.forEach { event ->
                insertScreenState(
                    event.monotonicMs,
                    event.wallClockMs,
                    event.eventType,
                    event.batteryMah,
                    event.sessionId
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
    
    /**
     * Get screen state events for a session.
     */
    fun getScreenStates(sessionId: String): List<ScreenStateRecord> {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SCREEN_STATE,
            null,
            "$COL_SCREEN_SESSION_ID = ?",
            arrayOf(sessionId),
            null,
            null,
            "$COL_SCREEN_MONOTONIC_MS ASC"
        )
        
        val records = mutableListOf<ScreenStateRecord>()
        cursor.use {
            // Cache column indexes to avoid repeated lookups
            val idIdx = it.getColumnIndexOrThrow(COL_SCREEN_ID)
            val monoIdx = it.getColumnIndexOrThrow(COL_SCREEN_MONOTONIC_MS)
            val wallIdx = it.getColumnIndexOrThrow(COL_SCREEN_WALL_CLOCK_MS)
            val typeIdx = it.getColumnIndexOrThrow(COL_SCREEN_EVENT_TYPE)
            val batteryIdx = it.getColumnIndexOrThrow(COL_SCREEN_BATTERY_MAH)
            val sessionIdx = it.getColumnIndexOrThrow(COL_SCREEN_SESSION_ID)
            
            while (it.moveToNext()) {
                records.add(
                    ScreenStateRecord(
                        id = it.getLong(idIdx),
                        monotonicMs = it.getLong(monoIdx),
                        wallClockMs = it.getLong(wallIdx),
                        eventType = it.getInt(typeIdx),
                        batteryMah = it.getInt(batteryIdx),
                        sessionId = it.getString(sessionIdx)
                    )
                )
            }
        }
        return records
    }
    
    /**
     * Get total event count.
     */
    fun getEventCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_SCREEN_STATE", null)
        cursor.use {
            if (it.moveToFirst()) {
                return it.getInt(0)
            }
        }
        return 0
    }
    
    /**
     * Get database file size in bytes.
     */
    fun getDatabaseSize(context: Context): Long {
        val dbFile = context.getDatabasePath(DATABASE_NAME)
        return if (dbFile.exists()) dbFile.length() else 0
    }
    
    /**
     * Export all screen states to CSV format.
     */
    fun exportToCsv(): String {
        val db = readableDatabase
        val cursor = db.query(
            TABLE_SCREEN_STATE,
            null,
            null,
            null,
            null,
            null,
            "$COL_SCREEN_WALL_CLOCK_MS ASC"
        )
        
        val sb = StringBuilder(4096)  // Pre-sized to reduce reallocation
        sb.appendLine("id,monotonic_ms,wall_clock_ms,event_type,battery_mah,session_id")
        
        cursor.use {
            // Cache column indexes
            val idIdx = it.getColumnIndexOrThrow(COL_SCREEN_ID)
            val monoIdx = it.getColumnIndexOrThrow(COL_SCREEN_MONOTONIC_MS)
            val wallIdx = it.getColumnIndexOrThrow(COL_SCREEN_WALL_CLOCK_MS)
            val typeIdx = it.getColumnIndexOrThrow(COL_SCREEN_EVENT_TYPE)
            val batteryIdx = it.getColumnIndexOrThrow(COL_SCREEN_BATTERY_MAH)
            val sessionIdx = it.getColumnIndexOrThrow(COL_SCREEN_SESSION_ID)
            
            while (it.moveToNext()) {
                sb.append(it.getLong(idIdx))
                sb.append(',')
                sb.append(it.getLong(monoIdx))
                sb.append(',')
                sb.append(it.getLong(wallIdx))
                sb.append(',')
                sb.append(it.getInt(typeIdx))
                sb.append(',')
                sb.append(it.getInt(batteryIdx))
                sb.append(',')
                sb.appendLine(it.getString(sessionIdx))
            }
        }
        return sb.toString()
    }
    
    /**
     * Close and release compiled statements.
     */
    override fun close() {
        statementLock.withLock {
            insertScreenStateStmt?.close()
            insertMetadataStmt?.close()
            insertScreenStateStmt = null
            insertMetadataStmt = null
        }
        super.close()
    }
}

/**
 * Data class for screen state event (used for batch inserts).
 */
data class ScreenStateEvent(
    val monotonicMs: Long,
    val wallClockMs: Long,
    val eventType: Int,
    val batteryMah: Int?,
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
    val sessionId: String
) {
    fun getEventTypeName(): String = when (eventType) {
        ProfilerDatabase.EVENT_SCREEN_OFF -> "SCREEN_OFF"
        ProfilerDatabase.EVENT_SCREEN_ON -> "SCREEN_ON"
        ProfilerDatabase.EVENT_USER_PRESENT -> "USER_UNLOCK"
        else -> "UNKNOWN"
    }
}

