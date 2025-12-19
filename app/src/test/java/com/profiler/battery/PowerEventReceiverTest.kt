package com.profiler.battery

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowBatteryManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.robolectric.annotation.Config
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PowerEventReceiverTest {

    private lateinit var context: Context
    private lateinit var eventBuffer: EventRingBuffer
    private var broadcastHandler: AndroidBroadcastHandler? = null
    private lateinit var receiver: PowerEventReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        // Setup BatteryManager via Shadow
        // With SDK 33, getting the service should work reliably
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val shadowBattery = Shadows.shadowOf(batteryManager)
        shadowBattery.setIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER, 5000000)
        
        // KeyguardManager (unlocked by default in Robolectric, which is fine for this test)
        
        eventBuffer = Mockito.mock(EventRingBuffer::class.java)
        
        // Use a real handler for testing dispatch
        broadcastHandler = AndroidBroadcastHandler()
        
        receiver = PowerEventReceiver(eventBuffer, broadcastHandler!!)
    }

    @Test
    fun testOnReceiveDispatchesToHandlerThread() {
        val intent = Intent(Intent.ACTION_SCREEN_ON)
        val latch = CountDownLatch(1)
        
        // Verify buffer interaction on background thread
        Mockito.doAnswer {
            val isMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            assertTrue("Should perform work on background thread", !isMainThread)
            
            // Check thread name
            val threadName = Thread.currentThread().name
            assertTrue("Should run on AndroidBroadcastHandler thread", 
                threadName == AndroidBroadcastHandler.THREAD_NAME)
                
            latch.countDown()
            null
        }.`when`(eventBuffer).addScreenOn(Mockito.anyInt(), Mockito.anyInt())

        // Execute onReceive with real context
        receiver.onReceive(context, intent)
        
        // Wait for background execution
        val completed = latch.await(2, TimeUnit.SECONDS)
        assertTrue("Background processing should complete", completed)
        
        // Verify method called
        Mockito.verify(eventBuffer).addScreenOn(Mockito.anyInt(), Mockito.anyInt())
    }
    
    @org.junit.After
    fun tearDown() {
        broadcastHandler?.shutdown()
    }
}
