package com.profiler.battery

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.opengl.GLES10
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import java.io.File
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext

/**
 * Comprehensive system information collector.
 * 
 * Collects hardware, software, and device info useful for:
 * - Power behavior correlation across devices
 * - Debugging and system analysis
 * - Device identification and comparison
 */
object SystemInfo {
    
    // ===== Device Identity =====
    
    /** Hardware model name (e.g., "Pixel 6", "SM-G998B") */
    val hardwareModel: String get() = Build.MODEL
    
    /** Device manufacturer (e.g., "Google", "Samsung") */
    val manufacturer: String get() = Build.MANUFACTURER
    
    /** Full device name (manufacturer + model) */
    val deviceName: String get() = "$manufacturer $hardwareModel"
    
    /** Hardware board name */
    val board: String get() = Build.BOARD
    
    /** Device codename (e.g., "oriole" for Pixel 6) */
    val device: String get() = Build.DEVICE
    
    /** Product name */
    val product: String get() = Build.PRODUCT
    
    /** Hardware name from Build.HARDWARE */
    val hardware: String get() = Build.HARDWARE
    
    /** Brand name */
    val brand: String get() = Build.BRAND
    
    // ===== SoC (System on Chip) Info =====
    
    /** SoC manufacturer (API 31+) */
    val socManufacturer: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER
        } else {
            readSysFile("/sys/devices/soc0/vendor") ?: "unknown"
        }
    
    /** SoC model (API 31+) */
    val socModel: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL
        } else {
            readSysFile("/sys/devices/soc0/soc_id") ?: hardware
        }
    
    // ===== OS Info =====
    
    /** Android OS version string (e.g., "14", "13") */
    val osVersion: String get() = Build.VERSION.RELEASE
    
    /** Android SDK/API level (e.g., 34) */
    val sdkVersion: Int get() = Build.VERSION.SDK_INT
    
    /** Full OS version string with API level */
    val osVersionFull: String get() = "Android $osVersion (API $sdkVersion)"
    
    /** Build fingerprint (unique build identifier) */
    val buildFingerprint: String get() = Build.FINGERPRINT
    
    /** Security patch level */
    val securityPatch: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Build.VERSION.SECURITY_PATCH
        } else {
            "unknown"
        }
    
    /** Build ID */
    val buildId: String get() = Build.ID
    
    /** Build type (e.g., "user", "userdebug") */
    val buildType: String get() = Build.TYPE
    
    /** Kernel version from /proc/version */
    val kernelVersion: String
        get() = try {
            File("/proc/version").readText().trim().let { version ->
                version.substringAfter("version ").substringBefore(" ")
            }
        } catch (e: Exception) {
            "unknown"
        }
    
    /** Baseband/radio version */
    val basebandVersion: String
        get() = Build.getRadioVersion() ?: "unknown"
    
    /** Bootloader version */
    val bootloader: String get() = Build.BOOTLOADER
    
    // ===== CPU Info =====
    
    /** CPU architecture (e.g., "arm64-v8a", "x86_64") */
    val cpuArchitecture: String get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
    
    /** All supported ABIs */
    val supportedAbis: String get() = Build.SUPPORTED_ABIS.joinToString(",")
    
    /** Number of CPU cores */
    val cpuCores: Int get() = Runtime.getRuntime().availableProcessors()
    
    /** CPU hardware info from /proc/cpuinfo */
    val cpuHardware: String
        get() = try {
            File("/proc/cpuinfo").readLines()
                .find { it.startsWith("Hardware") }
                ?.substringAfter(":")?.trim() ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    
    /** CPU features from /proc/cpuinfo */
    val cpuFeatures: String
        get() = try {
            File("/proc/cpuinfo").readLines()
                .find { it.startsWith("Features") || it.startsWith("flags") }
                ?.substringAfter(":")?.trim() ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    
    /** Maximum CPU frequency in MHz (first core) */
    val cpuMaxFreqMHz: Int
        get() = try {
            val freq = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toInt()
            freq / 1000  // Convert kHz to MHz
        } catch (e: Exception) {
            -1
        }
    
    // ===== Memory Info =====
    
    /** Total RAM in bytes (requires context) */
    fun getTotalRam(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem
    }
    
    /** Total RAM formatted as string */
    fun getTotalRamFormatted(context: Context): String {
        val bytes = getTotalRam(context)
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return "%.1f GB".format(gb)
    }
    
    /** Available RAM in bytes (requires context) */
    fun getAvailableRam(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem
    }
    
    /** Low memory threshold in bytes */
    fun getLowMemoryThreshold(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.threshold
    }
    
    // ===== Storage Info =====
    
    /** Total internal storage in bytes */
    fun getTotalInternalStorage(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.blockCountLong * stat.blockSizeLong
        } catch (e: Exception) {
            -1
        }
    }
    
    /** Available internal storage in bytes */
    fun getAvailableInternalStorage(): Long {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            -1
        }
    }
    
    /** Total internal storage formatted */
    fun getTotalInternalStorageFormatted(): String {
        val bytes = getTotalInternalStorage()
        if (bytes < 0) return "unknown"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return "%.1f GB".format(gb)
    }
    
    // ===== GPU Info =====
    
    /** GPU renderer name (requires EGL context) */
    fun getGpuRenderer(): String {
        return try {
            val egl = EGLContext.getEGL() as EGL10
            val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            egl.eglInitialize(display, intArrayOf(0, 0))
            
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val attribs = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL10.EGL_NONE
            )
            egl.eglChooseConfig(display, attribs, configs, 1, numConfigs)
            
            val context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, intArrayOf(0x3098, 2, EGL10.EGL_NONE))
            val surface = egl.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE))
            egl.eglMakeCurrent(display, surface, surface, context)
            
            val renderer = GLES10.glGetString(GLES10.GL_RENDERER) ?: "unknown"
            
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl.eglDestroyContext(display, context)
            egl.eglDestroySurface(display, surface)
            egl.eglTerminate(display)
            
            renderer
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /** GPU vendor */
    fun getGpuVendor(): String {
        return try {
            val egl = EGLContext.getEGL() as EGL10
            val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            egl.eglInitialize(display, intArrayOf(0, 0))
            
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val attribs = intArrayOf(EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE)
            egl.eglChooseConfig(display, attribs, configs, 1, numConfigs)
            
            val context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, intArrayOf(0x3098, 2, EGL10.EGL_NONE))
            val surface = egl.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE))
            egl.eglMakeCurrent(display, surface, surface, context)
            
            val vendor = GLES10.glGetString(GLES10.GL_VENDOR) ?: "unknown"
            
            egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl.eglDestroyContext(display, context)
            egl.eglDestroySurface(display, surface)
            egl.eglTerminate(display)
            
            vendor
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    // ===== Display Info =====
    
    /** Screen resolution as "WIDTHxHEIGHT" */
    fun getScreenResolution(context: Context): String {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            "${metrics.widthPixels}x${metrics.heightPixels}"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    /** Screen density in DPI */
    fun getScreenDensity(context: Context): Int {
        return context.resources.displayMetrics.densityDpi
    }
    
    /** Screen refresh rate in Hz */
    fun getScreenRefreshRate(context: Context): Float {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate
        } catch (e: Exception) {
            -1f
        }
    }
    
    // ===== Battery Info =====
    
    /** Battery design capacity in mAh (via PowerProfile reflection) */
    fun getBatteryCapacity(context: Context): Int {
        return try {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val constructor = powerProfileClass.getConstructor(Context::class.java)
            val powerProfile = constructor.newInstance(context)
            val getMethod = powerProfileClass.getMethod("getBatteryCapacity")
            val capacity = getMethod.invoke(powerProfile) as Double
            capacity.toInt()
        } catch (e: Exception) {
            try {
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                if (chargeCounter > 0 && capacity > 0) {
                    ((chargeCounter / 1000.0) / (capacity / 100.0)).toInt()
                } else -1
            } catch (e2: Exception) {
                -1
            }
        }
    }
    
    // ===== Sensor Info =====
    
    /** List of available sensors with details */
    fun getSensorList(context: Context): List<SensorInfo> {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sensorManager.getSensorList(Sensor.TYPE_ALL).map { sensor ->
            SensorInfo(
                name = sensor.name,
                vendor = sensor.vendor,
                type = sensor.type,
                typeName = getSensorTypeName(sensor.type),
                power = sensor.power,
                resolution = sensor.resolution,
                maxRange = sensor.maximumRange
            )
        }
    }
    
    /** Get sensor count by type */
    fun getSensorSummary(context: Context): String {
        val sensors = getSensorList(context)
        return "${sensors.size} sensors"
    }
    
    /** Check if a specific sensor type is available */
    fun hasSensor(context: Context, sensorType: Int): Boolean {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sensorManager.getDefaultSensor(sensorType) != null
    }
    
    private fun getSensorTypeName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
        Sensor.TYPE_GYROSCOPE -> "Gyroscope"
        Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
        Sensor.TYPE_LIGHT -> "Light"
        Sensor.TYPE_PROXIMITY -> "Proximity"
        Sensor.TYPE_PRESSURE -> "Barometer"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "Temperature"
        Sensor.TYPE_GRAVITY -> "Gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "Linear Acceleration"
        Sensor.TYPE_ROTATION_VECTOR -> "Rotation Vector"
        Sensor.TYPE_STEP_COUNTER -> "Step Counter"
        Sensor.TYPE_STEP_DETECTOR -> "Step Detector"
        Sensor.TYPE_HEART_RATE -> "Heart Rate"
        else -> "Type $type"
    }
    
    // ===== Thermal Info =====
    
    /** Current thermal status (API 29+) */
    fun getThermalStatus(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                when (powerManager.currentThermalStatus) {
                    0 -> "NONE"
                    1 -> "LIGHT"
                    2 -> "MODERATE"
                    3 -> "SEVERE"
                    4 -> "CRITICAL"
                    5 -> "EMERGENCY"
                    6 -> "SHUTDOWN"
                    else -> "UNKNOWN"
                }
            } catch (e: Exception) {
                "unknown"
            }
        } else {
            "unsupported"
        }
    }
    
    // ===== Utility Functions =====
    
    private fun readSysFile(path: String): String? {
        return try {
            File(path).readText().trim()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
    
    // ===== Export Functions =====
    
    /** Get formatted system info for log header */
    fun getLogHeader(): String = buildString {
        appendLine("# Device: $deviceName ($device)")
        appendLine("# SoC: $socManufacturer $socModel")
        appendLine("# OS: $osVersionFull")
        appendLine("# Kernel: $kernelVersion")
        appendLine("# CPU: $cpuArchitecture ($cpuCores cores)")
        appendLine("# Build: $buildFingerprint")
    }
    
    /** Get basic system info map (no context needed) */
    fun asMap(): Map<String, String> = mapOf(
        // Device identity
        "manufacturer" to manufacturer,
        "brand" to brand,
        "model" to hardwareModel,
        "device" to device,
        "product" to product,
        "board" to board,
        "hardware" to hardware,
        // SoC
        "soc_manufacturer" to socManufacturer,
        "soc_model" to socModel,
        // OS
        "os_version" to osVersion,
        "sdk_version" to sdkVersion.toString(),
        "security_patch" to securityPatch,
        "build_id" to buildId,
        "build_type" to buildType,
        "kernel_version" to kernelVersion,
        "baseband_version" to basebandVersion,
        "bootloader" to bootloader,
        "fingerprint" to buildFingerprint,
        // CPU
        "cpu_architecture" to cpuArchitecture,
        "cpu_cores" to cpuCores.toString(),
        "cpu_max_freq_mhz" to cpuMaxFreqMHz.toString(),
        "cpu_hardware" to cpuHardware,
        "supported_abis" to supportedAbis
    )
    
    /** Get full system info including context-dependent fields */
    fun asMap(context: Context): Map<String, String> = asMap() + mapOf(
        // Memory
        "total_ram_bytes" to getTotalRam(context).toString(),
        "total_ram_formatted" to getTotalRamFormatted(context),
        // Storage
        "total_storage_bytes" to getTotalInternalStorage().toString(),
        "total_storage_formatted" to getTotalInternalStorageFormatted(),
        // Display
        "screen_resolution" to getScreenResolution(context),
        "screen_density_dpi" to getScreenDensity(context).toString(),
        "screen_refresh_rate" to getScreenRefreshRate(context).toString(),
        // Battery
        "battery_capacity_mah" to getBatteryCapacity(context).toString(),
        // Sensors
        "sensor_count" to getSensorList(context).size.toString(),
        // GPU (expensive, can be disabled if needed)
        "gpu_renderer" to getGpuRenderer(),
        "gpu_vendor" to getGpuVendor(),
        // Thermal
        "thermal_status" to getThermalStatus(context)
    )
}

/** Data class for sensor information */
data class SensorInfo(
    val name: String,
    val vendor: String,
    val type: Int,
    val typeName: String,
    val power: Float,  // mA
    val resolution: Float,
    val maxRange: Float
)
