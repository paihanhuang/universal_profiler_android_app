package com.profiler.battery

import android.os.Build

/**
 * System information collector for debugging metadata.
 * 
 * Collects static device info that's useful for correlating
 * power behavior across different hardware and software versions.
 */
object SystemInfo {
    
    /**
     * Hardware model name (e.g., "Pixel 6", "SM-G998B")
     */
    val hardwareModel: String
        get() = Build.MODEL
    
    /**
     * Device manufacturer (e.g., "Google", "Samsung")
     */
    val manufacturer: String
        get() = Build.MANUFACTURER
    
    /**
     * Full device name (manufacturer + model)
     */
    val deviceName: String
        get() = "$manufacturer $hardwareModel"
    
    /**
     * Android OS version string (e.g., "14", "13")
     */
    val osVersion: String
        get() = Build.VERSION.RELEASE
    
    /**
     * Android SDK/API level (e.g., 34)
     */
    val sdkVersion: Int
        get() = Build.VERSION.SDK_INT
    
    /**
     * Full OS version string with API level
     */
    val osVersionFull: String
        get() = "Android $osVersion (API $sdkVersion)"
    
    /**
     * Build fingerprint (unique build identifier)
     */
    val buildFingerprint: String
        get() = Build.FINGERPRINT
    
    /**
     * Hardware board name
     */
    val board: String
        get() = Build.BOARD
    
    /**
     * Device codename (e.g., "oriole" for Pixel 6)
     */
    val device: String
        get() = Build.DEVICE
    
    /**
     * Product name
     */
    val product: String
        get() = Build.PRODUCT
    
    /**
     * Get formatted system info for log header
     */
    fun getLogHeader(): String = buildString {
        appendLine("# Device: $deviceName")
        appendLine("# OS: $osVersionFull")
        appendLine("# Board: $board")
        appendLine("# Device Code: $device")
        appendLine("# Build: $buildFingerprint")
    }
    
    /**
     * Get system info as a map for structured logging
     */
    fun asMap(): Map<String, String> = mapOf(
        "manufacturer" to manufacturer,
        "model" to hardwareModel,
        "os_version" to osVersion,
        "sdk_version" to sdkVersion.toString(),
        "board" to board,
        "device" to device,
        "product" to product,
        "fingerprint" to buildFingerprint
    )
}
