package com.profiler.battery

import android.os.Handler
import android.os.HandlerThread

/**
 * Dedicated thread for handling Android broadcast callbacks.
 * 
 * Moves broadcast processing off the main thread to:
 * - Avoid ANR risk in onReceive()
 * - Allow heavier processing (battery reads, reason detection)
 * - Keep main thread responsive
 * 
 * Usage:
 * - Create in Service.onCreate()
 * - Call post() from BroadcastReceiver.onReceive()
 * - Call shutdown() in Service.onDestroy()
 */
class AndroidBroadcastHandler {
    
    companion object {
        const val THREAD_NAME = "AndroidBroadcastHandler"
    }
    
    private val handlerThread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(handlerThread.looper)
    
    /**
     * Post work to the broadcast handler thread.
     * Thread-safe - can be called from any thread.
     */
    fun post(runnable: Runnable) {
        handler.post(runnable)
    }
    
    /**
     * Post work with delay.
     */
    fun postDelayed(runnable: Runnable, delayMs: Long) {
        handler.postDelayed(runnable, delayMs)
    }
    
    /**
     * Remove pending callbacks.
     */
    fun removeCallbacks(runnable: Runnable) {
        handler.removeCallbacks(runnable)
    }
    
    /**
     * Shutdown the handler thread.
     * Call from Service.onDestroy().
     */
    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
    }
}
