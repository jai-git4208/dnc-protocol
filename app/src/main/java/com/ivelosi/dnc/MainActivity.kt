package com.ivelosi.dnc

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 * Sample Activity File depicting the usage of SDK.
 * For example purpose only.
 */

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ivelosi.dnc.network.BluetoothDeviceInfo
import com.ivelosi.dnc.network.NetworkLogger
import com.ivelosi.dnc.network.NetworkManager
import com.ivelosi.dnc.network.WifiUtils
import com.ivelosi.dnc.signal.AdaptiveScanningManager
import com.ivelosi.dnc.signal.DNCPrefixValidator
import com.ivelosi.dnc.signal.EnergyEfficientSignalProcessor
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import com.ivelosi.dnc.ui.MessageHandlingUI
import com.ivelosi.dnc.notification.DNCNotificationManager
import com.ivelosi.dnc.service.DNCBackgroundService

class MainActivity : AppCompatActivity(), NetworkLogger {
    private lateinit var scanButton: Button
    private lateinit var setDeviceNameButton: Button
    private lateinit var devicesRecyclerView: RecyclerView
    private lateinit var logTextView: TextView
    private lateinit var logScrollView: ScrollView

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var wifiManager: WifiManager
    private lateinit var networkManager: NetworkManager
    private lateinit var messageHandlingUI: MessageHandlingUI
    
    // Add adaptive scanning manager
    private lateinit var adaptiveScanningManager: AdaptiveScanningManager
    
    // Add energy-efficient signal processor
    private lateinit var signalProcessor: EnergyEfficientSignalProcessor

    private lateinit var deviceAdapter: DeviceAdapter
    private val discoveredDevices = mutableListOf<BluetoothDeviceInfo>()
    private var selectedDevice: BluetoothDeviceInfo? = null
    
    // Track signal statistics
    private var totalRssiSum = 0
    private var deviceRssiCount = 0

    private val TAG = "DNCScannerApp"
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Service connection
    private var dncService: DNCBackgroundService? = null
    private var isServiceBound = false
    
    // Notification Manager
    private lateinit var notificationManager: DNCNotificationManager

    private val requestMultiplePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            log("All permissions granted")
        } else {
            log("Some permissions were denied")
            Toast.makeText(this, "App needs permissions to function properly", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        scanButton = findViewById(R.id.scanButton)
        setDeviceNameButton = findViewById(R.id.setDeviceNameButton)
        devicesRecyclerView = findViewById(R.id.devicesRecyclerView)
        logTextView = findViewById(R.id.logTextView)
        logScrollView = findViewById<ScrollView>(R.id.logScrollView)

        // Initialize Bluetooth adapter
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Initialize WiFi manager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        
        // Initialize Adaptive Scanning Manager
        adaptiveScanningManager = AdaptiveScanningManager(this, bluetoothAdapter, this)
        
        // Initialize Energy-Efficient Signal Processor
        signalProcessor = EnergyEfficientSignalProcessor(this, wifiManager, this)

        // Initialize Network Manager
        networkManager = NetworkManager(this, this)

        // Initialize Message Handling UI first
        messageHandlingUI = MessageHandlingUI(this, networkManager, this, coroutineScope)
        messageHandlingUI.initialize()

        // Initialize the DeviceAdapter with the callback for MessageHandlingUI
        deviceAdapter = DeviceAdapter(
            onConnectClick = { device -> connectToDevice(device) },
            onSetSelectedDevice = { device -> messageHandlingUI.setSelectedDevice(device) }
        )

        // Setup RecyclerView
        devicesRecyclerView.layoutManager = LinearLayoutManager(this)
        devicesRecyclerView.adapter = deviceAdapter

        // Initialize Notification Manager
        notificationManager = DNCNotificationManager(this)
        notificationManager.createNotificationChannels()

        // Start and bind to the background service
        val serviceIntent = Intent(this, DNCBackgroundService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Request permissions
        requestPermissions()

        // Register Bluetooth discovery receiver
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothReceiver, filter)

        // Set up button click listeners
        scanButton.setOnClickListener {
            dncService?.startBluetoothScan() ?: startBluetoothScan()
        }

        setDeviceNameButton.setOnClickListener {
            setDeviceName()
        }

        log("App initialized")
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Location permissions are required for Bluetooth scanning on Android 6.0+
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Bluetooth permissions for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        // WiFi and Internet permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_WIFI_STATE)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CHANGE_WIFI_STATE)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.INTERNET)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_NETWORK_STATE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
        }
    }

    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val localBinder = binder as DNCBackgroundService.LocalBinder
            dncService = localBinder.getService()
            isServiceBound = true
            
            // Setup listeners
            dncService?.setOnDevicesUpdatedListener { devices ->
                updateDeviceList(devices)
            }
            
            dncService?.setOnLogMessageListener { message ->
                updateLog(message)
            }
            
            log("Connected to DNC background service")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            dncService = null
            isServiceBound = false
            log("Disconnected from DNC background service")
        }
    }
    
    private fun updateDeviceList(devices: List<BluetoothDeviceInfo>) {
        runOnUiThread {
            discoveredDevices.clear()
            discoveredDevices.addAll(devices)
            deviceAdapter.updateDevices(discoveredDevices)
        }
    }
    
    private fun updateLog(message: String) {
        runOnUiThread {
            logTextView.append("$message\n")
            // Auto-scroll to bottom
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startBluetoothScan() {
        // This is a fallback if the service isn't bound yet
        if (!bluetoothAdapter.isEnabled) {
            log("Bluetooth is not enabled")
            notificationManager.showDiscoveryNotification("Bluetooth is not enabled")
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                startActivity(enableBtIntent)
            } else {
                log("Missing BLUETOOTH_CONNECT permission")
                return
            }
        } else {
            // Clear previous results
            discoveredDevices.clear()
            deviceAdapter.updateDevices(discoveredDevices)
            
            log("Starting Bluetooth scan through service...")
            notificationManager.showDiscoveryNotification("Starting device discovery")
            
            // Try to use the service if bound
            if (isServiceBound && dncService != null) {
                dncService?.startBluetoothScan()
            } else {
                // Fallback to traditional scanning if service isn't available
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    if (bluetoothAdapter.isDiscovering) {
                        bluetoothAdapter.cancelDiscovery()
                    }
                    bluetoothAdapter.startDiscovery()
                } else {
                    log("Missing BLUETOOTH_SCAN permission")
                }
            }
        }
    }

    private fun setDeviceName() {
        try {
            // Get the default prefix from validator
            val defaultPrefix = DNCPrefixValidator.getValidPrefixes().first() // Gets "DNC-" by default
            val deviceName = "${defaultPrefix}User"
            log("Attempting to set device name to: $deviceName")

            // For modern Android versions, this is typically not allowed programmatically
            // without system permissions, but we're including the code for completeness
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    log("Cannot set Bluetooth name: missing BLUETOOTH_CONNECT permission")
                    return
                }
            }

            bluetoothAdapter.name = deviceName
            log("Successfully set device name to $deviceName")
            Toast.makeText(this, "Device name set to $deviceName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            log("Error setting device name: ${e.message}")

            // For most devices, we'll need to guide users to do this manually
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Please set your device name to DNC-User manually", Toast.LENGTH_LONG).show()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    log("Discovery started")
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    log("Discovery finished. Found ${discoveredDevices.size} compatible devices")
                    
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
                    deviceAdapter.updateDevices(discoveredDevices)
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
                            
                            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                                val deviceName = it.name ?: "Unknown"
                                val deviceAddress = it.address

                                log("Found device: $deviceName ($deviceAddress) with signal strength: ${rssi} dBm")

                                // Filter using DNCPrefixValidator
                                if (DNCPrefixValidator.hasValidPrefix(deviceName)) {
                                    val matchingPrefix = DNCPrefixValidator.getMatchingPrefix(deviceName)
                                    log("Compatible device found with prefix '$matchingPrefix': $deviceName")

                                    // Get WiFi information
                                    val wifiInfo = WifiUtils.getWifiInfo(wifiManager)

                                    // Extract IP from WiFi info for socket connection
                                    val ipAddress = WifiUtils.extractIpAddress(wifiInfo, wifiManager)

                                    // Add to our list
                                    val deviceInfo = BluetoothDeviceInfo(
                                        name = deviceName,
                                        address = deviceAddress,
                                        wifiInfo = wifiInfo,
                                        ipAddress = ipAddress
                                    )

                                    // Add only if not already in the list
                                    if (!discoveredDevices.any { it.address == deviceAddress }) {
                                        discoveredDevices.add(deviceInfo)
                                        deviceAdapter.updateDevices(discoveredDevices)
                                    }
                                }
                            } else {
                                log("Missing BLUETOOTH_CONNECT permission")
                            }
                        } catch (e: Exception) {
                            log("Error processing device: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    private fun connectToDevice(device: BluetoothDeviceInfo) {
        // Use the service for device connections if available
        if (isServiceBound && dncService != null) {
            dncService?.connectToDevice(device)
        } else {
            // Fallback to direct connection
            if (device.isConnected) {
                // If already connected, disconnect
                networkManager.disconnectFromDevice(device) { updatedDevice, status, isConnected ->
                    updateConnectionStatus(updatedDevice, status, isConnected)

                    // Clear selected device when disconnecting
                    if (selectedDevice?.address == device.address) {
                        selectedDevice = null
                        messageHandlingUI.setSelectedDevice(null)
                    }
                }
            } else {
                // Connect to the device
                networkManager.connectToDevice(device) { updatedDevice, status, isConnected ->
                    updateConnectionStatus(updatedDevice, status, isConnected)

                    // Set as selected device when connection is successful
                    if (isConnected) {
                        selectedDevice = updatedDevice
                        messageHandlingUI.setSelectedDevice(updatedDevice)
                    }
                }
            }
        }
    }

    private suspend fun updateConnectionStatus(device: BluetoothDeviceInfo, status: String, isConnected: Boolean) {
        withContext(Dispatchers.Main) {
            val index = discoveredDevices.indexOfFirst { it.address == device.address }
            if (index != -1) {
                discoveredDevices[index] = device.copy(connectionStatus = status, isConnected = isConnected)
                deviceAdapter.updateDevices(discoveredDevices)

                // Update selected device for messaging if it's connected
                if (isConnected) {
                    messageHandlingUI.setSelectedDevice(discoveredDevices[index])
                } else if (device.address == selectedDevice?.address) {
                    messageHandlingUI.setSelectedDevice(null)
                }
            }
        }
    }

    override fun log(message: String) {
        Log.d(TAG, message)
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logMessage = "[$timestamp] $message\n"

        runOnUiThread {
            logTextView.append(logMessage)
            // Auto-scroll to bottom
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Unbind from service
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }

        // Clean up resources
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
        }

        // Cancel coroutine scope
        coroutineScope.cancel()

        log("App destroyed")
    }
}

// Adapter for RecyclerView
class DeviceAdapter(
    private val onConnectClick: (BluetoothDeviceInfo) -> Unit,
    private val onSetSelectedDevice: (BluetoothDeviceInfo?) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {
    private val devices = mutableListOf<BluetoothDeviceInfo>()

    fun updateDevices(newDevices: List<BluetoothDeviceInfo>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view, onConnectClick, onSetSelectedDevice)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device)
    }

    override fun getItemCount(): Int = devices.size

    class DeviceViewHolder(
        itemView: View,
        private val onConnectClick: (BluetoothDeviceInfo) -> Unit,
        private val onSetSelectedDevice: (BluetoothDeviceInfo?) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val deviceNameTextView: TextView = itemView.findViewById(R.id.deviceNameTextView)
        private val deviceAddressTextView: TextView = itemView.findViewById(R.id.deviceAddressTextView)
        private val wifiInfoTextView: TextView = itemView.findViewById(R.id.wifiInfoTextView)
        private val connectButton: Button = itemView.findViewById(R.id.connectButton)
        private val connectionStatusTextView: TextView = itemView.findViewById(R.id.connectionStatusTextView)

        private var currentDevice: BluetoothDeviceInfo? = null

        init {
            connectButton.setOnClickListener {
                currentDevice?.let { device ->
                    if (device.isConnected) {
                        // Disconnect if already connected
                        onConnectClick(device)
                        onSetSelectedDevice(null)
                    } else {
                        // Connect if not connected
                        onConnectClick(device)
                        // We'll set the selected device after connection is confirmed
                    }
                }
            }
        }

        fun bind(device: BluetoothDeviceInfo) {
            currentDevice = device
            deviceNameTextView.text = device.name
            deviceAddressTextView.text = "MAC: ${device.address}"
            wifiInfoTextView.text = device.wifiInfo

            // Set connect button text based on connection status
            connectButton.text = if (device.isConnected) "Disconnect" else "Connect"

            // Show connection status if available
            if (device.connectionStatus.isNotEmpty() && device.connectionStatus != "Not connected") {
                connectionStatusTextView.text = "Status: ${device.connectionStatus}"
                connectionStatusTextView.visibility = View.VISIBLE
            } else {
                connectionStatusTextView.visibility = View.GONE
            }
        }
    }
}