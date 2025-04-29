package com.ivelosi.dnc.signal

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.ivelosi.dnc.network.NetworkLogger
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import com.ivelosi.dnc.notification.DNCNotificationManager

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 *
 * Implements the adaptive scanning frequency algorithm as described in DNC Whitepaper section 2.1.2
 * This class adapts scanning frequency based on:
 * - Device battery level and charging state
 * - Previous discovery success rate
 * - Time of day (passive mode during typical inactive hours)
 * - Network density
 */
class AdaptiveScanningManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val logger: NetworkLogger
) {
    companion object {
        // Scanning interval bounds (in milliseconds)
        private const val MIN_SCAN_INTERVAL = 30_000L // 30 seconds
        private const val MAX_SCAN_INTERVAL = 900_000L // 15 minutes
        private const val DEFAULT_SCAN_INTERVAL = 120_000L // 2 minutes
        
        // Energy conservation thresholds
        private const val LOW_BATTERY_THRESHOLD = 20 // 20%
        private const val MEDIUM_BATTERY_THRESHOLD = 50 // 50%
        
        // Success rate thresholds for adjusting scan frequency
        private const val HIGH_SUCCESS_THRESHOLD = 0.7 // 70%
        private const val LOW_SUCCESS_THRESHOLD = 0.2 // 20%
        
        // Adjustment factors
        private const val INTERVAL_ADJUSTMENT_FACTOR = 0.15 // 15% adjustment per cycle
    }
    
    // Current scan interval (adaptive)
    private var currentScanInterval = DEFAULT_SCAN_INTERVAL
    
    // Track discovery statistics
    private var totalScans = 0
    private var successfulScans = 0
    private var lastScanDeviceCount = 0
    
    // Network density factor (increases with more discovered devices)
    private var networkDensityFactor = 1.0
    
    // Track whether scanning is active
    private val isScanning = AtomicBoolean(false)
    
    // Coroutine for scheduled scanning
    private var scanningJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Add notification manager
    private val notificationManager = DNCNotificationManager(context)
    
    /**
     * Start adaptive scanning process
     */
    fun startAdaptiveScanning() {
        if (scanningJob?.isActive == true) {
            logger.log("Adaptive scanning already running")
            notificationManager.showDiscoveryNotification("Adaptive scanning already running")
            return
        }
        
        logger.log("Starting adaptive scanning with interval: ${currentScanInterval / 1000} seconds")
        notificationManager.showDiscoveryNotification("Started adaptive scanning (${currentScanInterval / 1000}s interval)")
        
        isScanning.set(true)
        
        scanningJob = coroutineScope.launch {
            while (isScanning.get()) {
                performScan()
                delay(calculateNextInterval())
            }
        }
    }
    
    /**
     * Stop adaptive scanning
     */
    fun stopAdaptiveScanning() {
        logger.log("Stopping adaptive scanning")
        notificationManager.showDiscoveryNotification("Stopped adaptive scanning")
        isScanning.set(false)
        scanningJob?.cancel()
        scanningJob = null
    }
    
    /**
     * Execute a Bluetooth scan
     */
    @SuppressLint("MissingPermission")
    private fun performScan() {
        if (!bluetoothAdapter.isEnabled) {
            logger.log("Bluetooth is disabled, skipping scan")
            return
        }
        
        totalScans++
        
        try {
            // Cancel any ongoing discovery
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            
            // Start new discovery
            val started = bluetoothAdapter.startDiscovery()
            if (started) {
                logger.log("Adaptive scan #$totalScans started")
                // No notification for each scan to avoid spamming
            } else {
                logger.log("Failed to start adaptive scan #$totalScans")
                notificationManager.showDiscoveryNotification("Failed to start scan")
            }
        } catch (e: Exception) {
            logger.log("Error during adaptive scan: ${e.message}")
            notificationManager.showDiscoveryNotification("Scan error: ${e.message}")
        }
    }
    
    /**
     * Record scan results to adjust future scanning frequency
     */
    fun recordScanResults(deviceCount: Int) {
        lastScanDeviceCount = deviceCount
        
        // Consider a scan successful if at least one device was found
        if (deviceCount > 0) {
            successfulScans++
            // Update network density based on discovered devices
            networkDensityFactor = min(3.0, 1.0 + (deviceCount * 0.1))
            
            // Show notification for successful discovery
            if (deviceCount > 2) {
                notificationManager.showDiscoveryNotification("Found $deviceCount devices")
            }
        } else {
            // Slightly reduce network density on unsuccessful scans
            networkDensityFactor = max(1.0, networkDensityFactor * 0.95)
        }
        
        logger.log("Scan results: Found $deviceCount devices. Success rate: ${getSuccessRate()}")
    }
    
    /**
     * Calculate the next scanning interval based on adaptive factors
     */
    private fun calculateNextInterval(): Long {
        // 1. Start with current interval
        var newInterval = currentScanInterval
        
        // 2. Adjust based on success rate
        val successRate = getSuccessRate()
        when {
            successRate > HIGH_SUCCESS_THRESHOLD -> {
                // High success rate, scan less frequently (save energy)
                newInterval = (newInterval * (1 + INTERVAL_ADJUSTMENT_FACTOR)).toLong()
            }
            successRate < LOW_SUCCESS_THRESHOLD -> {
                // Low success rate, scan more frequently
                newInterval = (newInterval * (1 - INTERVAL_ADJUSTMENT_FACTOR)).toLong()
            }
        }
        
        // 3. Adjust based on battery level and charging status
        val batteryFactor = getBatteryAdjustmentFactor()
        newInterval = (newInterval * batteryFactor).toLong()
        
        // 4. Consider network density
        newInterval = (newInterval / networkDensityFactor).toLong()
        
        // 5. Apply time-of-day adjustment
        val timeOfDayFactor = getTimeOfDayFactor()
        newInterval = (newInterval * timeOfDayFactor).toLong()
        
        // 6. Ensure within bounds
        newInterval = newInterval.coerceIn(MIN_SCAN_INTERVAL, MAX_SCAN_INTERVAL)
        
        // 7. Update the current interval
        if (newInterval != currentScanInterval) {
            logger.log("Adaptive scan interval adjusted: ${currentScanInterval / 1000}s -> ${newInterval / 1000}s")
            
            // Only notify of significant changes (>30 seconds difference)
            if (Math.abs(newInterval - currentScanInterval) > 30_000) {
                notificationManager.showDiscoveryNotification(
                    "Scan interval adjusted to ${newInterval / 1000}s"
                )
            }
            
            currentScanInterval = newInterval
        }
        
        return newInterval
    }
    
    /**
     * Calculate success rate of device discovery
     */
    private fun getSuccessRate(): Double {
        return if (totalScans > 0) {
            successfulScans.toDouble() / totalScans
        } else {
            0.0
        }
    }
    
    /**
     * Get adjustment factor based on battery level and charging state
     */
    private fun getBatteryAdjustmentFactor(): Double {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        
        // Check if device is charging
        val isCharging = batteryManager.isCharging
        
        // Get battery percentage
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        return when {
            isCharging -> 0.8  // Scan more frequently when charging
            batteryLevel <= LOW_BATTERY_THRESHOLD -> 2.0  // Scan less frequently at low battery
            batteryLevel <= MEDIUM_BATTERY_THRESHOLD -> 1.4  // Slightly reduce frequency at medium battery
            else -> 1.0  // Normal frequency at high battery
        }
    }
    
    /**
     * Get adjustment factor based on time of day
     * Reduces scan frequency during typical night hours (11 PM - 6 AM)
     */
    private fun getTimeOfDayFactor(): Double {
        val hour = java.time.LocalTime.now().hour
        return if (hour in 23..24 || hour in 0..5) {
            2.0  // Night time - scan half as frequently
        } else {
            1.0  // Day time - normal frequency
        }
    }
    
    /**
     * Determine if the device is in power save mode
     */
    private fun isInPowerSaveMode(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isPowerSaveMode
    }
    
    /**
     * Check if we're in optimal conditions for scanning
     * (charging, good battery level, etc.)
     */
    fun isInOptimalScanningState(): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val isCharging = batteryManager.isCharging
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        
        return isCharging && batteryLevel > MEDIUM_BATTERY_THRESHOLD && !isInPowerSaveMode()
    }
    
    /**
     * Reset statistics and adaptive parameters
     */
    fun reset() {
        totalScans = 0
        successfulScans = 0
        lastScanDeviceCount = 0
        networkDensityFactor = 1.0
        currentScanInterval = DEFAULT_SCAN_INTERVAL
    }
    
    /**
     * Check if the manager is actively scanning
     */
    fun isActivelyScanning(): Boolean {
        return isScanning.get() && scanningJob?.isActive == true
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        stopAdaptiveScanning()
        coroutineScope.cancel()
    }
} 