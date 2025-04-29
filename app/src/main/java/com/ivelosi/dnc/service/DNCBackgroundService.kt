package com.ivelosi.dnc.service

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ivelosi.dnc.network.BluetoothDeviceInfo
import com.ivelosi.dnc.network.NetworkLogger
import com.ivelosi.dnc.network.NetworkManager
import com.ivelosi.dnc.notification.DNCNotificationManager
import com.ivelosi.dnc.signal.AdaptiveScanningManager
import com.ivelosi.dnc.signal.DNCPrefixValidator
import com.ivelosi.dnc.signal.EnergyEfficientSignalProcessor
import com.ivelosi.dnc.signal.MessageProtocol
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 *
 * Background service for DNC operations.
 * Handles Bluetooth scanning, device monitoring, and maintaining connections
 * even when the app is in the background.
 */
class DNCBackgroundService : Service(), NetworkLogger {
    companion object {
        private const val TAG = "DNCBackgroundService"
    }

    // Binder for activity connection
    private val binder = LocalBinder()
    
    // Core components
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var wifiManager: WifiManager
    private lateinit var networkManager: NetworkManager
    private lateinit var adaptiveScanningManager: AdaptiveScanningManager
    private lateinit var signalProcessor: EnergyEfficientSignalProcessor
    private lateinit var notificationManager: DNCNotificationManager
    
    // Status tracking
    private val discoveredDevices = mutableListOf<BluetoothDeviceInfo>()
    private var isScanning = false
    private var connectedDeviceCount = 0
    
    // Signal statistics
    private var totalRssiSum = 0
    private var deviceRssiCount = 0
    
    // Coroutine context
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    
    // Callback interfaces
    private var onDevicesUpdatedListener: ((List<BluetoothDeviceInfo>) -> Unit)? = null
    private var onLogMessageListener: ((String) -> Unit)? = null
    
    /**
     * Local binder class
     */
    inner class LocalBinder : Binder() {
        fun getService(): DNCBackgroundService = this@DNCBackgroundService
    }
    
    override fun onCreate() {
        super.onCreate()
        log("DNC Background Service created")
        
        // Initialize core components
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        networkManager = NetworkManager(this, this)
        adaptiveScanningManager = AdaptiveScanningManager(this, bluetoothAdapter, this)
        signalProcessor = EnergyEfficientSignalProcessor(this, wifiManager, this)
        
        // Setup notification manager
        notificationManager = DNCNotificationManager(this)
        notificationManager.createNotificationChannels()
        
        // Start as a foreground service with notification
        startForeground(
            DNCNotificationManager.NOTIFICATION_ID_SERVICE,
            notificationManager.createServiceNotification()
        )
        
        // Register broadcast receivers
        registerReceivers()
        
        // Set up message listener
        networkManager.setOnMessageReceivedListener { device, type, payload ->
            handleReceivedMessage(device, type, payload)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log("DNC Background Service started")
        
        // Handle intent actions
        when (intent?.action) {
            DNCNotificationManager.ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            DNCNotificationManager.ACTION_SCAN_DEVICES -> {
                startBluetoothScan()
            }
        }
        
        // If service is killed, restart it
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    /**
     * Register broadcast receivers for Bluetooth events and notification actions
     */
    private fun registerReceivers() {
        // Bluetooth discovery receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothReceiver, filter)
        
        // Service action receiver
        val serviceFilter = IntentFilter().apply {
            addAction(DNCNotificationManager.ACTION_STOP_SERVICE)
            addAction(DNCNotificationManager.ACTION_SCAN_DEVICES)
        }
        registerReceiver(serviceActionReceiver, serviceFilter)
    }
    
    /**
     * Start Bluetooth scanning
     */
    fun startBluetoothScan() {
        // Clear old results
        discoveredDevices.clear()
        
        // Reset signal statistics
        totalRssiSum = 0
        deviceRssiCount = 0
        
        // Update UI and notification
        isScanning = true
        updateServiceNotification()
        onDevicesUpdatedListener?.invoke(discoveredDevices)
        
        // Use adaptive scanning if in optimal conditions
        if (adaptiveScanningManager.isInOptimalScanningState()) {
            log("Starting adaptive scanning sequence")
            notificationManager.showDiscoveryNotification("Starting adaptive scanning sequence")
            adaptiveScanningManager.startAdaptiveScanning()
        } else {
            // Use traditional scanning
            log("Starting traditional Bluetooth scan")
            notificationManager.showDiscoveryNotification("Starting Bluetooth scan")
            
            try {
                if (bluetoothAdapter.isDiscovering) {
                    bluetoothAdapter.cancelDiscovery()
                }
                bluetoothAdapter.startDiscovery()
            } catch (e: Exception) {
                log("Error starting scan: ${e.message}")
            }
        }
    }
    
    /**
     * Stop Bluetooth scanning
     */
    fun stopBluetoothScan() {
        isScanning = false
        updateServiceNotification()
        
        adaptiveScanningManager.stopAdaptiveScanning()
        
        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: Exception) {
            log("Error stopping scan: ${e.message}")
        }
    }
    
    /**
     * Connect to a discovered device
     */
    fun connectToDevice(device: BluetoothDeviceInfo) {
        if (device.isConnected) {
            // Disconnect if already connected
            networkManager.disconnectFromDevice(device) { updatedDevice, status, isConnected ->
                updateConnectionStatus(updatedDevice, status, isConnected)
            }
        } else {
            // Connect to the device
            log("Connecting to ${device.name}")
            notificationManager.showDiscoveryNotification("Connecting to ${device.name}")
            
            networkManager.connectToDevice(device) { updatedDevice, status, isConnected ->
                updateConnectionStatus(updatedDevice, status, isConnected)
            }
        }
    }
    
    /**
     * Send a text message to a device
     */
    fun sendMessage(device: BluetoothDeviceInfo, message: String): Boolean {
        return networkManager.sendTextMessage(device, message)
    }
    
    /**
     * Send a command to a device
     */
    fun sendCommand(device: BluetoothDeviceInfo, command: String, args: String = ""): Boolean {
        return networkManager.sendCommand(device, command, args)
    }
    
    /**
     * Get all discovered devices
     */
    fun getDiscoveredDevices(): List<BluetoothDeviceInfo> {
        return discoveredDevices.toList()
    }
    
    /**
     * Set listener for device list updates
     */
    fun setOnDevicesUpdatedListener(listener: (List<BluetoothDeviceInfo>) -> Unit) {
        onDevicesUpdatedListener = listener
    }
    
    /**
     * Set listener for log messages
     */
    fun setOnLogMessageListener(listener: (String) -> Unit) {
        onLogMessageListener = listener
    }
    
    /**
     * Update the connection status for a device
     */
    private suspend fun updateConnectionStatus(device: BluetoothDeviceInfo, status: String, isConnected: Boolean) {
        withContext(Dispatchers.Main) {
            val index = discoveredDevices.indexOfFirst { it.address == device.address }
            if (index != -1) {
                discoveredDevices[index] = device.copy(connectionStatus = status, isConnected = isConnected)
                
                // Update connected device count
                connectedDeviceCount = discoveredDevices.count { it.isConnected }
                updateServiceNotification()
                
                // Notify listener
                onDevicesUpdatedListener?.invoke(discoveredDevices)
                
                // Show connection notification
                val action = if (isConnected) "Connected to" else "Disconnected from"
                notificationManager.showDiscoveryNotification("$action ${device.name}")
            }
        }
    }
    
    /**
     * Handle a received message
     */
    private fun handleReceivedMessage(device: BluetoothDeviceInfo, type: String, payload: String) {
        log("Message from ${device.name}: [$type] $payload")
        
        when (type) {
            MessageProtocol.TYPE_TEXT -> {
                // Show notification for text message
                notificationManager.showMessageNotification(device.name, payload)
            }
            MessageProtocol.TYPE_FILE_START -> {
                // Show notification for file transfer start
                val parts = payload.split("|", limit = 2)
                if (parts.size == 2) {
                    val fileName = parts[0]
                    notificationManager.showDiscoveryNotification(
                        "Starting file transfer: $fileName from ${device.name}"
                    )
                }
            }
            MessageProtocol.TYPE_FILE_END -> {
                // Show notification for file transfer completion
                notificationManager.showDiscoveryNotification(
                    "File transfer complete: $payload from ${device.name}"
                )
            }
        }
    }
    
    /**
     * Update the service notification with current status
     */
    private fun updateServiceNotification() {
        notificationManager.updateServiceNotification(connectedDeviceCount, isScanning)
    }
    
    /**
     * Bluetooth discovery receiver
     */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    log("Discovery started")
                }
                
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    log("Discovery finished. Found ${discoveredDevices.size} compatible devices")
                    isScanning = false
                    updateServiceNotification()
                    
                    // Update adaptive scanning with results
                    adaptiveScanningManager.recordScanResults(discoveredDevices.size)
                    
                    // Adjust signal thresholds based on discovered devices
                    if (deviceRssiCount > 0) {
                        val averageRssi = totalRssiSum / deviceRssiCount
                        signalProcessor.adjustRssiThreshold(averageRssi, discoveredDevices.size)
                    }
                    
                    // Update the device list with any energy-efficient processed devices
                    val processedDevices = signalProcessor.getProcessedDevices()
                    for (processedDevice in processedDevices) {
                        if (!discoveredDevices.any { it.address == processedDevice.address }) {
                            discoveredDevices.add(processedDevice)
                        }
                    }
                    
                    // Notify listener
                    onDevicesUpdatedListener?.invoke(discoveredDevices)
                    
                    // Show discovery completion notification
                    notificationManager.showDiscoveryNotification(
                        "Found ${discoveredDevices.size} compatible devices"
                    )
                }
                
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    
                    // Get signal strength (RSSI)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    
                    // Update signal statistics
                    if (rssi != Short.MIN_VALUE.toInt()) {
                        totalRssiSum += rssi
                        deviceRssiCount++
                    }
                    
                    device?.let {
                        try {
                            // Check if the signal strength is acceptable
                            if (rssi != Short.MIN_VALUE.toInt() && !signalProcessor.isSignalStrengthAcceptable(rssi)) {
                                log("Device signal too weak (${rssi} dBm), skipping")
                                return
                            }
                            
                            // Process the device with energy efficiency
                            val processed = signalProcessor.processDevice(it, rssi)
                            
                            // If processed immediately, skip the traditional processing
                            if (processed) {
                                return
                            }
                            
                            try {
                                val deviceName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                    if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        log("Missing BLUETOOTH_CONNECT permission")
                                        return
                                    }
                                    it.name ?: "Unknown"
                                } else {
                                    @Suppress("DEPRECATION")
                                    it.name ?: "Unknown"
                                }
                                
                                val deviceAddress = it.address
                                
                                log("Found device: $deviceName ($deviceAddress) with signal strength: ${rssi} dBm")
                                
                                // Filter using DNCPrefixValidator
                                if (DNCPrefixValidator.hasValidPrefix(deviceName)) {
                                    val matchingPrefix = DNCPrefixValidator.getMatchingPrefix(deviceName)
                                    log("Compatible device found with prefix '$matchingPrefix': $deviceName")
                                    
                                    // Get WiFi information and IP address
                                    val wifiInfo = com.ivelosi.dnc.network.WifiUtils.getWifiInfo(wifiManager)
                                    val ipAddress = com.ivelosi.dnc.network.WifiUtils.extractIpAddress(wifiInfo, wifiManager)
                                    
                                    // Create device info object
                                    val deviceInfo = BluetoothDeviceInfo(
                                        name = deviceName,
                                        address = deviceAddress,
                                        wifiInfo = wifiInfo,
                                        ipAddress = ipAddress
                                    )
                                    
                                    // Add only if not already in the list
                                    if (!discoveredDevices.any { it.address == deviceAddress }) {
                                        discoveredDevices.add(deviceInfo)
                                        
                                        // Notify listener about updated device list
                                        onDevicesUpdatedListener?.invoke(discoveredDevices)
                                    }
                                }
                            } catch (e: Exception) {
                                log("Error processing device details: ${e.message}")
                            }
                        } catch (e: Exception) {
                            log("Error processing device: ${e.message}")
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Service action receiver
     */
    private val serviceActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                DNCNotificationManager.ACTION_STOP_SERVICE -> {
                    log("Stop service action received")
                    stopSelf()
                }
                DNCNotificationManager.ACTION_SCAN_DEVICES -> {
                    log("Scan devices action received")
                    startBluetoothScan()
                }
            }
        }
    }
    
    override fun log(message: String) {
        Log.d(TAG, message)
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] $message"
        
        // Send log to activity if it's listening
        onLogMessageListener?.invoke(logMessage)
    }
    
    override fun onDestroy() {
        log("DNC Background Service destroyed")
        
        // Clean up resources
        networkManager.cleanup()
        adaptiveScanningManager.cleanup()
        signalProcessor.cleanup()
        
        // Unregister receivers
        try {
            unregisterReceiver(bluetoothReceiver)
            unregisterReceiver(serviceActionReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers: ${e.message}")
        }
        
        // Cancel all jobs
        serviceJob.cancel()
        
        super.onDestroy()
    }
} 