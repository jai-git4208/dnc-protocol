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
import com.ivelosi.dnc.bluetooth.BluetoothBroadcastManager
import com.ivelosi.dnc.network.BluetoothDeviceInfo
import com.ivelosi.dnc.network.DeviceNetworkInfo
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
    private lateinit var bluetoothBroadcastManager: BluetoothBroadcastManager
    
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
    private var onConnectionStatusListener: ((BluetoothDeviceInfo, String, Boolean) -> Unit)? = null
    private var onNetworkInfoUpdatedListener: ((String) -> Unit)? = null
    
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
        
        // Initialize the new BluetoothBroadcastManager
        bluetoothBroadcastManager = BluetoothBroadcastManager(this, bluetoothAdapter, this)
        bluetoothBroadcastManager.setOnDevicesUpdatedListener { devices ->
            // Update our device list with the new discovered devices
            discoveredDevices.clear()
            discoveredDevices.addAll(devices)
            
            // Notify listeners about updated device list
            onDevicesUpdatedListener?.invoke(discoveredDevices)
            
            // Update scanning status
            isScanning = bluetoothBroadcastManager.isActivelyScanning()
            updateServiceNotification()
        }
        
        // Setup notification manager
        notificationManager = DNCNotificationManager(this)
        notificationManager.createNotificationChannels()
        
        // Start as a foreground service with notification
        startForeground(
            DNCNotificationManager.NOTIFICATION_ID_SERVICE,
            notificationManager.createServiceNotification()
        )
        
        // Register service action receiver
        val serviceFilter = IntentFilter().apply {
            addAction(DNCNotificationManager.ACTION_STOP_SERVICE)
            addAction(DNCNotificationManager.ACTION_SCAN_DEVICES)
        }
        registerReceiver(serviceActionReceiver, serviceFilter)
        
        // Set up message listener
        networkManager.setOnMessageReceivedListener { device, type, payload ->
            handleReceivedMessage(device, type, payload)
        }
        
        // Start monitoring network conditions periodically
        startNetworkMonitoring()
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
     * Start Bluetooth scanning with improved discovery
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
        
        log("Starting Bluetooth scan through improved broadcast manager")
        notificationManager.showDiscoveryNotification("Starting enhanced device discovery")
        
        // Use the new Bluetooth Broadcast Manager
        bluetoothBroadcastManager.startDiscovery()
    }
    
    /**
     * Stop Bluetooth scanning
     */
    fun stopBluetoothScan() {
        isScanning = false
        updateServiceNotification()
        
        // Stop the adaptive scanning if running
        adaptiveScanningManager.stopAdaptiveScanning()
        
        // Stop the enhanced discovery
        bluetoothBroadcastManager.stopDiscovery()
    }
    
    /**
     * Handle a received message and generate notifications
     */
    private fun handleReceivedMessage(device: BluetoothDeviceInfo, type: String, payload: String) {
        // First update the device in our list
        updateDeviceInList(device)
        
        // Log the message
        log("Message from ${device.name}: [$type] $payload")
        
        // Forward message to any listeners
        onLogMessageListener?.invoke("Message from ${device.name}: [$type] $payload")
        
        // Handle specific message types
        when (type) {
            MessageProtocol.TYPE_HANDSHAKE -> {
                if (payload.startsWith("DNC-CONNECT:")) {
                    val deviceModel = payload.substring("DNC-CONNECT:".length)
                    notificationManager.showMessageNotification(
                        device.name, 
                        "Device connected: $deviceModel"
                    )
                }
            }
            MessageProtocol.TYPE_TEXT -> {
                // Show a notification for text messages
                notificationManager.showMessageNotification(device.name, payload)
            }
            MessageProtocol.TYPE_FILE_START -> {
                // Extract filename
                val parts = payload.split("|")
                if (parts.isNotEmpty()) {
                    val fileName = parts[0]
                    notificationManager.showDiscoveryNotification(
                        "Receiving file: $fileName from ${device.name}"
                    )
                }
            }
            MessageProtocol.TYPE_FILE_END -> {
                notificationManager.showDiscoveryNotification(
                    "File transfer complete from ${device.name}"
                )
            }
            MessageProtocol.TYPE_COMMAND -> {
                // For important commands, notify user
                val command = payload.split(":", limit = 2)[0]
                if (command == MessageProtocol.CMD_RESTART || command == MessageProtocol.CMD_SHUTDOWN) {
                    notificationManager.showDiscoveryNotification(
                        "Received command: $command from ${device.name}"
                    )
                }
            }
        }
    }
    
    /**
     * Connect to a device
     */
    fun connectToDevice(device: BluetoothDeviceInfo) {
        networkManager.connectToDevice(device) { updatedDevice, status, isConnected ->
            // Update our device list
            updateDeviceInList(updatedDevice.copy(connectionStatus = status, isConnected = isConnected))
            
            // Invoke listener if available
            onConnectionStatusListener?.invoke(updatedDevice, status, isConnected)
            
            if (isConnected) {
                connectedDeviceCount++
                updateServiceNotification()
            }
        }
    }
    
    /**
     * Disconnect from a device
     */
    fun disconnectFromDevice(device: BluetoothDeviceInfo) {
        networkManager.disconnectFromDevice(device) { updatedDevice, status, isConnected ->
            // Update our device list
            updateDeviceInList(updatedDevice.copy(connectionStatus = status, isConnected = isConnected))
            
            // Invoke listener if available
            onConnectionStatusListener?.invoke(updatedDevice, status, isConnected)
            
            if (!isConnected && connectedDeviceCount > 0) {
                connectedDeviceCount--
                updateServiceNotification()
            }
        }
    }
    
    /**
     * Update a device in our local list
     */
    private fun updateDeviceInList(device: BluetoothDeviceInfo) {
        val index = discoveredDevices.indexOfFirst { it.address == device.address }
        if (index != -1) {
            discoveredDevices[index] = device
        } else {
            discoveredDevices.add(device)
        }
        
        // Notify listeners
        onDevicesUpdatedListener?.invoke(discoveredDevices)
    }
    
    /**
     * Set a listener for device list updates
     */
    fun setOnDevicesUpdatedListener(listener: (List<BluetoothDeviceInfo>) -> Unit) {
        this.onDevicesUpdatedListener = listener
        // Also set the listener on the broadcast manager
        bluetoothBroadcastManager.setOnDevicesUpdatedListener(listener)
    }

    /**
     * Set a listener for log messages
     */
    fun setOnLogMessageListener(listener: (String) -> Unit) {
        this.onLogMessageListener = listener
    }
    
    /**
     * Set a listener for connection status changes
     */
    fun setOnConnectionStatusListener(listener: (BluetoothDeviceInfo, String, Boolean) -> Unit) {
        this.onConnectionStatusListener = listener
    }
    
    /**
     * Set a listener for network info updates
     */
    fun setOnNetworkInfoUpdatedListener(listener: (String) -> Unit) {
        this.onNetworkInfoUpdatedListener = listener
    }
    
    /**
     * Start periodic network monitoring
     */
    private fun startNetworkMonitoring() {
        serviceScope.launch {
            while (true) {
                try {
                    // Get network info
                    val networkInfo = DeviceNetworkInfo.getDeviceNetworkSummary(this@DNCBackgroundService)
                    
                    // Notify listeners
                    onNetworkInfoUpdatedListener?.invoke(networkInfo)
                    
                    // Update connected devices from NetworkManager
                    val connectedDevices = networkManager.getConnectedDevices()
                    if (connectedDevices.isNotEmpty()) {
                        for (device in connectedDevices) {
                            updateDeviceInList(device)
                        }
                        connectedDeviceCount = connectedDevices.size
                        updateServiceNotification()
                    }
                } catch (e: Exception) {
                    log("Error in network monitoring: ${e.message}")
                }
                
                // Check every 30 seconds
                delay(30_000)
            }
        }
    }
    
    /**
     * Update the service notification with current status
     */
    private fun updateServiceNotification() {
        val notification = notificationManager.createServiceNotification(
            isScanning = isScanning,
            connectedDevices = connectedDeviceCount
        )
        
        try {
            notificationManager.updateServiceNotification(notification)
        } catch (e: Exception) {
            log("Error updating service notification: ${e.message}")
        }
    }
    
    /**
     * Receiver for service actions from notifications
     */
    private val serviceActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                DNCNotificationManager.ACTION_STOP_SERVICE -> {
                    log("Stopping service from notification")
                    stopSelf()
                }
                DNCNotificationManager.ACTION_SCAN_DEVICES -> {
                    log("Starting scan from notification")
                    startBluetoothScan()
                }
            }
        }
    }
    
    override fun log(message: String) {
        Log.d(TAG, message)
        onLogMessageListener?.invoke(message)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up
        try {
            unregisterReceiver(serviceActionReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers: ${e.message}")
        }
        
        // Clean up network manager
        networkManager.cleanup()
        
        // Clean up broadcast manager
        bluetoothBroadcastManager.cleanup()
        
        // Cancel all coroutines
        serviceJob.cancel()
        
        log("DNC Background Service destroyed")
    }
} 