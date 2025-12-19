package com.profiler.battery

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidBroadcastHandlerTest {

    private lateinit var handler: AndroidBroadcastHandler

    @Before
    fun setUp() {
        handler = AndroidBroadcastHandler()
    }

    @After
    fun tearDown() {
        handler.shutdown()
    }

    @Test
    fun testPostRunsOnBackgroundThread() {
        val latch = CountDownLatch(1)
        var threadName = ""
        var isMainThread = true

        handler.post {
            threadName = Thread.currentThread().name
            isMainThread = Looper.myLooper() == Looper.getMainLooper()
            latch.countDown()
        }

        val completed = latch.await(2, TimeUnit.SECONDS)
        assertTrue("Task should have completed", completed)
        
        assertTrue("Thread name should be AndroidBroadcastHandler, was $threadName", 
            threadName == AndroidBroadcastHandler.THREAD_NAME)
        
        assertTrue("Should not run on main thread", !isMainThread)
    }


}
