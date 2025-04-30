package com.ivelosi.dnc.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.ivelosi.dnc.network.BluetoothDeviceInfo
import com.ivelosi.dnc.network.NetworkLogger
import com.ivelosi.dnc.network.WifiUtils
import com.ivelosi.dnc.notification.DNCNotificationManager
import com.ivelosi.dnc.signal.DNCPrefixValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated manager for Bluetooth device discovery with improved reliability and performance.
 * Handles both classic Bluetooth discovery and BLE scanning for maximum device detection.
 */
class BluetoothBroadcastManager(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter,
    private val logger: NetworkLogger
) {
    companion object {
        private const val SCAN_PERIOD = 20000L // 20 seconds scan period
        private const val BLE_SCAN_PERIOD = 15000L // 15 seconds BLE scan period

        // Extended scan if no devices found
        private const val EXTENDED_SCAN_PERIOD = 30000L
        private const val MAX_RETRY_COUNT = 3
    }

    // Device discovery tracking
    private val discoveredDevices = mutableListOf<BluetoothDeviceInfo>()
    private val isScanning = AtomicBoolean(false)
    private var retryCount = 0
    
    // Notification manager
    private val notificationManager = DNCNotificationManager(context)
    
    // BLE scanning components
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Add WiFi manager for getting IPs
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    
    // Callback interface
    private var onDevicesUpdatedListener: ((List<BluetoothDeviceInfo>) -> Unit)? = null

    /**
     * Start Bluetooth device discovery with improved techniques for better device detection
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (isScanning.getAndSet(true)) {
            logger.log("Discovery already in progress")
            return
        }

        // Clear previous results
        discoveredDevices.clear()
        
        // Notify listener of empty list
        onDevicesUpdatedListener?.invoke(discoveredDevices)
        
        logger.log("Starting enhanced Bluetooth discovery")
        notificationManager.showDiscoveryNotification("Starting enhanced Bluetooth discovery")
        
        // Register broadcast receiver for classic Bluetooth
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(bluetoothReceiver, filter)
        
        // Start classic Bluetooth discovery
        startClassicDiscovery()
        
        // Schedule BLE scan to follow immediately after
        handler.postDelayed({
            // Start BLE scanning if supported
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                startBluetoothLeDiscovery()
            }
        }, 1000) // short delay to avoid interference
    }
    
    /**
     * Start standard Bluetooth discovery process
     */
    @SuppressLint("MissingPermission")
    private fun startClassicDiscovery() {
        try {
            // Cancel existing discovery
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            
            // Set discoverable if possible - helps other devices find us
            if (bluetoothAdapter.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                discoverableIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Only attempt this, don't require it to succeed
                try {
                    context.startActivity(discoverableIntent)
                } catch (e: Exception) {
                    logger.log("Could not make device discoverable: ${e.message}")
                }
            }
            
            // Start discovery with timeout
            val started = bluetoothAdapter.startDiscovery()
            if (started) {
                logger.log("Classic Bluetooth discovery started")
                
                // Set a timeout to stop discovery
                handler.postDelayed({
                    stopClassicDiscovery()
                }, SCAN_PERIOD)
            } else {
                logger.log("Failed to start classic Bluetooth discovery")
                notificationManager.showDiscoveryNotification("Failed to start discovery")
                
                // Try BLE scan immediately if classic fails
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    startBluetoothLeDiscovery()
                } else {
                    isScanning.set(false)
                }
            }
        } catch (e: Exception) {
            logger.log("Error in classic Bluetooth discovery: ${e.message}")
            isScanning.set(false)
        }
    }
    
    /**
     * Stop classic Bluetooth discovery
     */
    @SuppressLint("MissingPermission")
    private fun stopClassicDiscovery() {
        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
                logger.log("Classic Bluetooth discovery stopped")
            }
        } catch (e: Exception) {
            logger.log("Error stopping classic discovery: ${e.message}")
        }
    }
    
    /**
     * Start Bluetooth Low Energy scanning for improved device discovery
     * This can detect devices that may not appear in classic scanning
     */
    @SuppressLint("MissingPermission")
    private fun startBluetoothLeDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        
        try {
            logger.log("Starting BLE discovery")
            
            bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
            
            if (bluetoothLeScanner == null) {
                logger.log("BLE scanner not available")
                checkAndFinishScanning()
                return
            }
            
            // Configure scan settings for maximum discovery
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // Highest power, best discovery
                .build()
            
            // Start scanning
            bluetoothLeScanner?.startScan(null, settings, bleScanCallback)
            
            // Set timeout
            handler.postDelayed({
                stopBluetoothLeDiscovery()
                checkAndFinishScanning()
            }, BLE_SCAN_PERIOD)
        } catch (e: Exception) {
            logger.log("Error starting BLE discovery: ${e.message}")
            checkAndFinishScanning()
        }
    }
    
    /**
     * Stop BLE scanning
     */
    @SuppressLint("MissingPermission")
    private fun stopBluetoothLeDiscovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return
        }
        
        try {
            bluetoothLeScanner?.stopScan(bleScanCallback)
            logger.log("BLE discovery stopped")
        } catch (e: Exception) {
            logger.log("Error stopping BLE discovery: ${e.message}")
        }
    }
    
    /**
     * BLE scan callback to process discovered devices
     */
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            try {
                val device = result.device
                val rssi = result.rssi
                
                // Process the BLE device similarly to classic device
                processNewDevice(device, rssi)
            } catch (e: Exception) {
                logger.log("Error in BLE scan result: ${e.message}")
            }
        }
    }
    
    /**
     * Process newly discovered Bluetooth device
     */
    @SuppressLint("MissingPermission")
    private fun processNewDevice(device: BluetoothDevice, rssi: Int) {
        scope.launch {
            try {
                // Get device name safely
                val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != 
                            android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        logger.log("Missing BLUETOOTH_CONNECT permission")
                        return@launch
                    }
                    device.name ?: "Unknown"
                } else {
                    @Suppress("DEPRECATION")
                    device.name ?: "Unknown"
                }
                
                val deviceAddress = device.address
                
                // Log discovery
                logger.log("Found device: $deviceName ($deviceAddress) with signal strength: ${rssi} dBm")
                
                // Only consider DNC devices
                if (DNCPrefixValidator.hasValidPrefix(deviceName)) {
                    val matchingPrefix = DNCPrefixValidator.getMatchingPrefix(deviceName)
                    logger.log("Compatible device found with prefix '$matchingPrefix': $deviceName")
                    
                    // Get WiFi information
                    val wifiInfo = WifiUtils.getWifiInfo(context, wifiManager)
                    val ipAddress = WifiUtils.extractIpAddress(context, wifiInfo, wifiManager, device)
                    
                    // Create device info
                    val deviceInfo = BluetoothDeviceInfo(
                        name = deviceName,
                        address = deviceAddress,
                        rssi = rssi,
                        wifiInfo = wifiInfo,
                        ipAddress = ipAddress
                    )
                    
                    // Add to list if not already present
                    if (!discoveredDevices.any { it.address == deviceAddress }) {
                        discoveredDevices.add(deviceInfo)
                        
                        // Notify about updated device list
                        onDevicesUpdatedListener?.invoke(discoveredDevices)
                    }
                }
            } catch (e: Exception) {
                logger.log("Error processing device: ${e.message}")
            }
        }
    }
    
    /**
     * Receiver for classic Bluetooth discovery results
     */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    logger.log("Classic Bluetooth discovery started")
                }
                
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    logger.log("Classic Bluetooth discovery finished")
                }
                
                BluetoothDevice.ACTION_FOUND -> {
                    // Get the BluetoothDevice from the Intent
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    
                    // Get RSSI if available
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    
                    // Process the discovered device
                    device?.let {
                        processNewDevice(it, rssi)
                    }
                }
            }
        }
    }
    
    /**
     * Check if we should continue scanning or finish
     */
    private fun checkAndFinishScanning() {
        // If no devices found and we haven't exceeded retry limit, try once more
        if (discoveredDevices.isEmpty() && retryCount < MAX_RETRY_COUNT) {
            retryCount++
            logger.log("No devices found. Retrying scan (attempt $retryCount)")
            notificationManager.showDiscoveryNotification("Extended scan in progress...")
            
            // Try extended scan with higher power
            startExtendedScan()
        } else {
            // Finish scanning
            completeScanning()
        }
    }
    
    /**
     * Start an extended scan with maximum power settings
     */
    @SuppressLint("MissingPermission")
    private fun startExtendedScan() {
        // Stop any ongoing scanning first
        stopClassicDiscovery()
        stopBluetoothLeDiscovery()
        
        // Start with max power for classic Bluetooth
        try {
            bluetoothAdapter.startDiscovery()
            
            // Schedule to stop after extended period
            handler.postDelayed({
                stopClassicDiscovery()
                completeScanning()
            }, EXTENDED_SCAN_PERIOD)
        } catch (e: Exception) {
            logger.log("Error in extended scan: ${e.message}")
            completeScanning()
        }
    }
    
    /**
     * Complete the scanning process and clean up
     */
    private fun completeScanning() {
        try {
            // Stop all scans
            stopClassicDiscovery()
            stopBluetoothLeDiscovery()
            
            // Unregister receiver
            try {
                context.unregisterReceiver(bluetoothReceiver)
            } catch (e: Exception) {
                // Ignore if not registered
            }
            
            // Reset retry count
            retryCount = 0
            
            // Update status
            isScanning.set(false)
            
            // Log results
            val deviceCount = discoveredDevices.size
            logger.log("Discovery completed. Found $deviceCount devices")
            
            if (deviceCount > 0) {
                notificationManager.showDiscoveryNotification("Found $deviceCount devices")
            } else {
                notificationManager.showDiscoveryNotification("No compatible devices found")
            }
        } catch (e: Exception) {
            logger.log("Error completing scan: ${e.message}")
        }
    }
    
    /**
     * Stop all scanning
     */
    fun stopDiscovery() {
        if (!isScanning.get()) {
            return
        }
        
        // Remove any pending callbacks
        handler.removeCallbacksAndMessages(null)
        
        // Complete the scan
        completeScanning()
    }
    
    /**
     * Set a listener for device list updates
     */
    fun setOnDevicesUpdatedListener(listener: (List<BluetoothDeviceInfo>) -> Unit) {
        this.onDevicesUpdatedListener = listener
    }
    
    /**
     * Check if scanning is currently active
     */
    fun isActivelyScanning(): Boolean {
        return isScanning.get()
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        stopDiscovery()
    }
} 