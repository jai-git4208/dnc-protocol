package com.ivelosi.dnc.signal

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.ivelosi.dnc.network.BluetoothDeviceInfo
import com.ivelosi.dnc.network.NetworkLogger
import com.ivelosi.dnc.network.WifiUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import com.ivelosi.dnc.notification.DNCNotificationManager

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 *
 * Implements energy-efficient signal processing as described in DNC whitepaper.
 * This class optimizes the signal processing by:
 * - Batch processing signals to reduce CPU wakeups
 * - Dynamic signal strength thresholds based on environment
 * - Caching previously seen devices to reduce processing overhead
 * - Applying device proximity filtering
 */
class EnergyEfficientSignalProcessor(
    private val context: Context,
    private val wifiManager: WifiManager,
    private val logger: NetworkLogger
) {
    companion object {
        // Signal strength thresholds (in dBm)
        private const val RSSI_EXCELLENT = -60
        private const val RSSI_GOOD = -70
        private const val RSSI_FAIR = -80
        private const val RSSI_POOR = -90
        
        // Processing constants
        private const val MAX_CACHE_SIZE = 100
        private const val CACHE_EXPIRY_MS = 30 * 60 * 1000L // 30 minutes
        
        // Energy optimization
        private const val BATCH_PROCESSING_SIZE = 5
        private const val MINIMUM_PROCESSING_DELAY_MS = 250L
    }
    
    // Keep track of previously seen devices with timestamp and cached signal strength
    private data class CachedDeviceInfo(
        val deviceInfo: BluetoothDeviceInfo,
        val lastSeen: Long = System.currentTimeMillis(),
        var signalStrength: Int = 0,
        var signalCount: Int = 1
    )
    
    // Device cache to avoid reprocessing the same device multiple times
    private val deviceCache = HashMap<String, CachedDeviceInfo>()
    
    // Batch processing queue
    private val processingQueue = ArrayList<BluetoothDevice>()
    
    // Background processing scope
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Handler for delayed processing
    private val processingHandler = Handler(Looper.getMainLooper())
    
    // Dynamic signal strength threshold adjusted based on environment
    private var currentRssiThreshold = RSSI_FAIR
    
    // Queue processing flag
    private var isProcessingQueue = false
    
    // Wake lock for batch processing
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Add notification manager
    private val notificationManager = DNCNotificationManager(context)
    
    /**
     * Process a discovered device with energy optimization
     *
     * @param device The Bluetooth device discovered
     * @param rssi Signal strength
     * @return True if device was processed immediately, false if batched
     */
    fun processDevice(device: BluetoothDevice, rssi: Int): Boolean {
        // Add to queue for batch processing
        synchronized(processingQueue) {
            processingQueue.add(device)
            
            // If we've reached the batch size, process the queue
            if (processingQueue.size >= BATCH_PROCESSING_SIZE) {
                // Schedule processing with a slight delay to catch more devices
                scheduleQueueProcessing(0)
                return true
            } else if (processingQueue.size == 1) {
                // First item in the queue, schedule processing with delay
                scheduleQueueProcessing(MINIMUM_PROCESSING_DELAY_MS)
                return false
            }
        }
        return false
    }
    
    /**
     * Schedule the processing of the current queue
     */
    private fun scheduleQueueProcessing(delayMs: Long) {
        if (!isProcessingQueue) {
            isProcessingQueue = true
            processingHandler.removeCallbacksAndMessages(null) // Remove any pending callbacks
            
            processingHandler.postDelayed({
                processQueue()
                isProcessingQueue = false
            }, delayMs)
        }
    }
    
    /**
     * Process all devices in the queue
     */
    private fun processQueue() {
        // Get a wake lock for processing
        acquireWakeLock()
        
        val devicesToProcess = ArrayList<BluetoothDevice>()
        
        // Get current queue items
        synchronized(processingQueue) {
            devicesToProcess.addAll(processingQueue)
            processingQueue.clear()
        }
        
        // Only notify if processing a significant batch
        if (devicesToProcess.size >= 3) {
            notificationManager.showDiscoveryNotification("Processing batch of ${devicesToProcess.size} devices")
        }
        
        // Process devices in background thread
        processingScope.launch {
            try {
                val wifiInfo = WifiUtils.getWifiInfo(context, wifiManager)
                
                for (device in devicesToProcess) {
                    processDeviceInternal(device, wifiInfo)
                }
                
                // Clean up the cache periodically
                if (deviceCache.size > MAX_CACHE_SIZE) {
                    cleanupCache()
                }
            } finally {
                // Release wake lock
                releaseWakeLock()
            }
        }
    }
    
    /**
     * Internal device processing implementation
     */
    private fun processDeviceInternal(device: BluetoothDevice, wifiInfo: String) {
        try {
            // Skip devices with null names or that don't match our filter
            val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+, we need to check for BLUETOOTH_CONNECT permission
                if (PackageManager.PERMISSION_GRANTED != 
                    ContextCompat.checkSelfPermission(
                        context, 
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                ) {
                    logger.log("Missing BLUETOOTH_CONNECT permission, cannot process device")
                    return
                }
                device.name ?: return
            } else {
                // For older Android versions
                @Suppress("DEPRECATION")
                device.name ?: return
            }
            
            if (!DNCPrefixValidator.hasValidPrefix(deviceName)) {
                return
            }
            
            val deviceAddress = device.address
            val matchingPrefix = DNCPrefixValidator.getMatchingPrefix(deviceName) ?: return
            
            logger.log("Efficiently processing device: $deviceName (${device.address})")
            
            // Extract IP from WiFi info for socket connection
            val ipAddress = WifiUtils.extractIpAddress(context, wifiInfo, wifiManager, device)
            
            // Create or update cached device info
            val cachedInfo = deviceCache[deviceAddress]
            if (cachedInfo != null) {
                // Update existing cache entry
                cachedInfo.deviceInfo.connectionStatus = "Signal processed"
                deviceCache[deviceAddress] = cachedInfo.copy(
                    lastSeen = System.currentTimeMillis()
                )
            } else {
                // Create new device info and add to cache
                val deviceInfo = BluetoothDeviceInfo(
                    name = deviceName,
                    address = deviceAddress,
                    wifiInfo = wifiInfo,
                    ipAddress = ipAddress,
                    connectionStatus = "Signal processed",
                    rssi = -65  // Add a default RSSI value
                )
                
                deviceCache[deviceAddress] = CachedDeviceInfo(deviceInfo)
                
                // Notify about new compatible device found
                notificationManager.showDiscoveryNotification("New device found: $deviceName")
            }
            
            logger.log("Processed signal from compatible device: '$matchingPrefix': $deviceName")
        } catch (e: Exception) {
            logger.log("Error processing device: ${e.message}")
        }
    }
    
    /**
     * Clean up expired entries from the device cache
     */
    private fun cleanupCache() {
        val now = System.currentTimeMillis()
        val expiredAddresses = deviceCache.entries
            .filter { now - it.value.lastSeen > CACHE_EXPIRY_MS }
            .map { it.key }
        
        expiredAddresses.forEach { deviceCache.remove(it) }
        
        // Only notify if significant cleanup
        if (expiredAddresses.size > 5) {
            notificationManager.showDiscoveryNotification("Removed ${expiredAddresses.size} expired devices from cache")
        }
        
        logger.log("Cleaned up ${expiredAddresses.size} expired devices from cache")
    }
    
    /**
     * Adjust the RSSI threshold based on environment
     */
    fun adjustRssiThreshold(averageRssi: Int, deviceCount: Int) {
        // Dynamically adjust threshold based on:
        // 1. Average signal strength of discovered devices
        // 2. Number of devices in the area (network density)
        
        // Start with a base threshold
        var newThreshold = RSSI_FAIR
        
        // Adjust for average signal strength
        if (averageRssi > RSSI_EXCELLENT) {
            // Excellent signals, be more selective
            newThreshold = RSSI_EXCELLENT
        } else if (averageRssi > RSSI_GOOD) {
            // Good signals
            newThreshold = RSSI_GOOD
        } else if (averageRssi < RSSI_POOR) {
            // Poor signals, be less selective
            newThreshold = RSSI_POOR
        }
        
        // Adjust for device density
        if (deviceCount > 10) {
            // Many devices, be more selective
            newThreshold = max(newThreshold, RSSI_GOOD)
        } else if (deviceCount < 2) {
            // Few devices, be less selective
            newThreshold = min(newThreshold, RSSI_POOR)
        }
        
        // Update the threshold
        if (newThreshold != currentRssiThreshold) {
            logger.log("Adjusted RSSI threshold: $currentRssiThreshold -> $newThreshold")
            
            // Only notify of significant threshold changes
            if (Math.abs(newThreshold - currentRssiThreshold) > 10) {
                notificationManager.showDiscoveryNotification(
                    "Signal quality threshold adjusted for better reception"
                )
            }
            
            currentRssiThreshold = newThreshold
        }
    }
    
    /**
     * Get all processed devices from cache
     */
    fun getProcessedDevices(): List<BluetoothDeviceInfo> {
        return deviceCache.values.map { it.deviceInfo }
    }
    
    /**
     * Check if a device signal is strong enough to process
     */
    fun isSignalStrengthAcceptable(rssi: Int): Boolean {
        return rssi >= currentRssiThreshold
    }
    
    /**
     * Acquire wake lock for processing
     */
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "DNC:SignalProcessingWakeLock"
                )
                wakeLock?.setReferenceCounted(false)
            }
            
            wakeLock?.acquire(10000) // 10 seconds timeout
        } catch (e: Exception) {
            logger.log("Error acquiring wake lock: ${e.message}")
        }
    }
    
    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            logger.log("Error releasing wake lock: ${e.message}")
        }
    }
    
    /**
     * Reset the processor state
     */
    fun reset() {
        deviceCache.clear()
        processingQueue.clear()
        currentRssiThreshold = RSSI_FAIR
        processingHandler.removeCallbacksAndMessages(null)
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        releaseWakeLock()
        processingHandler.removeCallbacksAndMessages(null)
        processingQueue.clear()
    }
} 