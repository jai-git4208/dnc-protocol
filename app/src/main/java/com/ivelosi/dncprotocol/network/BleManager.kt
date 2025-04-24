package com.ivelosi.dncprotocol.network

/**
 * (c) Ivelosi Technologies. All rights reserved.
 */

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.UUID

@RequiresApi(Build.VERSION_CODES.O)
class BleManager(private val context: Context) {
    private val TAG = "BleManager"

    // Define the device name prefix for discovery
    private val DEVICE_NAME_PREFIX = "DNC-"

    // UUIDs for the service and characteristic
    private val SERVICE_UUID = UUID.fromString("00001234-0000-1000-8000-00805f9b34fb")
    private val CHARACTERISTIC_UUID = UUID.fromString("00005678-0000-1000-8000-00805f9b34fb")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val bluetoothLeAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter.bluetoothLeAdvertiser
    private val bluetoothLeScanner: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner

    private var gattServer: BluetoothGattServer? = null
    private var gattClient: BluetoothGatt? = null

    // Map to track connected devices and their addresses
    private val connectedDevices = mutableMapOf<String, BluetoothGatt>()

    private val _deviceDiscoveredFlow = MutableSharedFlow<ScanResult>()
    val deviceDiscoveredFlow: SharedFlow<ScanResult> = _deviceDiscoveredFlow

    private val _messageReceivedFlow = MutableSharedFlow<String>()
    val messageReceivedFlow: SharedFlow<String> = _messageReceivedFlow

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(TAG, "BLE Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            val errorMessage = when(errorCode) {
                AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
                AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
                AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                else -> "Unknown error: $errorCode"
            }
            Log.e(TAG, "BLE Advertising failed: $errorMessage")

            // Retry advertising after a delay
            handler.postDelayed({
                if (bluetoothAdapter.isEnabled) {
                    try {
                        startAdvertising()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart advertising: ${e.message}")
                    }
                }
            }, 5000)
        }
    }

    private val scanCallback = object : ScanCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name

            // Log every discovered device for debugging
            Log.d(TAG, "BLE scan found device: ${deviceName ?: "Unknown"} (${result.device.address})")

            // Check if the device name starts with our prefix
            if (deviceName != null && deviceName.startsWith(DEVICE_NAME_PREFIX)) {
                Log.d(TAG, "BLE device with matching prefix found: $deviceName (${result.device.address})")
                scope.launch {
                    _deviceDiscoveredFlow.emit(result)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMessage = when(errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scanning not supported"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error code: $errorCode"
            }
            Log.e(TAG, "BLE scan failed: $errorMessage")

            // Retry scanning after a delay
            handler.postDelayed({
                if (bluetoothAdapter.isEnabled) {
                    try {
                        startScanning()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restart scanning: ${e.message}")
                    }
                }
            }, 5000)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: android.bluetooth.BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Device connected to GATT server: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Device disconnected from GATT server: ${device.address}")
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val message = String(value)
                Log.d(TAG, "Message received via BLE: $message")
                scope.launch {
                    _messageReceivedFlow.emit(message)
                }
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private val gattClientCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server: $deviceAddress")
                connectedDevices[deviceAddress] = gatt
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from GATT server: $deviceAddress")
                connectedDevices.remove(deviceAddress)

                // Try to reconnect after a delay
                handler.postDelayed({
                    if (bluetoothAdapter.isEnabled) {
                        try {
                            val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
                            device.connectGatt(context, false, gattClientCallback)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to reconnect to device: ${e.message}")
                        }
                    }
                }, 5000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered for ${gatt.device.address}")
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    Log.d(TAG, "Found our service")

                    // Set up notifications for the characteristic
                    val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true)
                    }
                } else {
                    Log.e(TAG, "Service not found")
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val message = String(value)
                Log.d(TAG, "Read characteristic value: $message")
                scope.launch {
                    _messageReceivedFlow.emit(message)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val message = String(value)
            Log.d(TAG, "Characteristic changed: $message")
            scope.launch {
                _messageReceivedFlow.emit(message)
            }
        }
    }

    // Helper function to check Bluetooth and permission readiness
    private fun isBluetoothReady(): Boolean {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth not supported on this device")
            return false
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled")
            return false
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun initialize() {
        if (!isBluetoothReady()) {
            Log.e(TAG, "Cannot initialize BLE - Bluetooth not ready")
            return
        }

        // Set device name with prefix for easy discovery
        try {
            // Take current name or use default if null
            val baseName = bluetoothAdapter.name ?: "Device"
            val newName = DEVICE_NAME_PREFIX + baseName.take(10)
            bluetoothAdapter.setName(newName)
            Log.d(TAG, "Set device name to: $newName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set device name: ${e.message}")
        }

        setupGattServer()
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun cleanup() {
        stopAdvertising()
        stopScanning()

        // Close all connected GATT clients
        connectedDevices.values.forEach { it.close() }
        connectedDevices.clear()

        gattServer?.close()
        gattServer = null

        gattClient?.close()
        gattClient = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun setupGattServer() {
        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)
            Log.d(TAG, "GATT server setup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup GATT server: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (!isBluetoothReady()) {
            Log.e(TAG, "Cannot start advertising - Bluetooth not ready")
            return
        }

        bluetoothLeAdvertiser?.let { advertiser ->
            try {
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .build()

                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)  // Critical for prefix-based discovery
                    .addServiceUuid(ParcelUuid(SERVICE_UUID))  // Include service UUID for connection
                    .build()

                advertiser.startAdvertising(settings, data, advertiseCallback)
                Log.d(TAG, "Started BLE advertising with device name: ${bluetoothAdapter.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start advertising: ${e.message}")
            }
        } ?: Log.e(TAG, "BLE advertising not supported on this device")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    fun stopAdvertising() {
        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            Log.d(TAG, "BLE advertising stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE advertising: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!isBluetoothReady()) {
            Log.e(TAG, "Cannot start scanning - Bluetooth not ready")
            return
        }

        bluetoothLeScanner?.let { scanner ->
            try {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()

                // Start scanning without filters to catch all devices
                scanner.startScan(null, settings, scanCallback)
                Log.d(TAG, "Started BLE scanning for devices with prefix: $DEVICE_NAME_PREFIX")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start scanning: ${e.message}")
            }
        } ?: Log.e(TAG, "BLE scanning not supported on this device")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScanning() {
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            Log.d(TAG, "BLE scanning stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scanning: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectToDevice(scanResult: ScanResult) {
        val device = scanResult.device
        val deviceAddress = device.address

        Log.d(TAG, "Connecting to device: ${device.name ?: "Unknown"} ($deviceAddress)")

        try {
            // Check if already connected
            if (connectedDevices.containsKey(deviceAddress)) {
                Log.d(TAG, "Already connected to device $deviceAddress")
                return
            }

            // Connect to the device
            gattClient = device.connectGatt(context, false, gattClientCallback)
            Log.d(TAG, "Connection request sent to $deviceAddress")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to device: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendMessage(message: String, deviceAddress: String? = null) {
        // If specific device address provided, send only to that device
        if (deviceAddress != null) {
            val gatt = connectedDevices[deviceAddress]
            if (gatt != null) {
                sendMessageToGatt(gatt, message)
            } else {
                Log.e(TAG, "Device $deviceAddress not connected")
            }
            return
        }

        // If no specific device, try the main gattClient first
        if (gattClient != null) {
            sendMessageToGatt(gattClient!!, message)
            return
        }

        // If main gattClient not available, try all connected devices
        if (connectedDevices.isNotEmpty()) {
            Log.d(TAG, "Sending message to all connected devices (${connectedDevices.size})")
            connectedDevices.values.forEach { gatt ->
                sendMessageToGatt(gatt, message)
            }
        } else {
            Log.e(TAG, "No connected devices to send message to")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendMessageToGatt(gatt: BluetoothGatt, message: String) {
        val service = gatt.getService(SERVICE_UUID) ?: run {
            Log.e(TAG, "Cannot send message - Service not found")
            return
        }

        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: run {
            Log.e(TAG, "Cannot send message - Characteristic not found")
            return
        }

        try {
            characteristic.value = message.toByteArray()
            val success = gatt.writeCharacteristic(characteristic)
            if (success) {
                Log.d(TAG, "Message sent successfully: $message")
            } else {
                Log.e(TAG, "Failed to write characteristic")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending message: ${e.message}")
        }
    }

    fun getConnectedDevices(): List<String> {
        return connectedDevices.keys.toList()
    }
}