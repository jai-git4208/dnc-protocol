package com.ivelosi.dncprotocol.network

/**
 * (c) Ivelosi Technologies. All rights reserved.
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.net.wifi.aware.PeerHandle
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@RequiresApi(Build.VERSION_CODES.O)
class P2PChatService : Service() {
    private val TAG = "P2PChatService"

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var wifiAwareManager: WifiAwareManager
    private lateinit var bleManager: BleManager

    private val _messageReceivedFlow = MutableSharedFlow<ChatMessage>()
    val messageReceivedFlow: SharedFlow<ChatMessage> = _messageReceivedFlow

    private val _peerDiscoveredFlow = MutableSharedFlow<String>()
    val peerDiscoveredFlow: SharedFlow<String> = _peerDiscoveredFlow

    private val _connectionStateFlow = MutableSharedFlow<String>()
    val connectionStateFlow: SharedFlow<String> = _connectionStateFlow

    // Map peer IDs to handles for sending messages
    private val peerHandles = mutableMapOf<String, PeerHandle>()
    private val activePeers = mutableSetOf<String>()

    // Track initialization state
    private var isInitialized = false

    // Connection retry mechanism
    private var discoveryRetryCount = 0
    private val MAX_DISCOVERY_RETRIES = 10

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "P2PChatService onCreate")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wifiAwareManager = WifiAwareManager(this)
            bleManager = BleManager(this)

            // We'll initialize in onStartCommand after permissions are confirmed
        } else {
            Log.e(TAG, "WiFi Aware requires API 26+")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "P2PChatService onStartCommand")

        if (!isInitialized && hasRequiredPermissions()) {
            initialize()
        }

        return START_STICKY
    }

    private fun hasRequiredPermissions(): Boolean {
        // This should be checked by the activity before starting the service
        // Just a simple check to prevent crashes
        return true
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "P2PChatService onBind")
        return binder
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onDestroy() {
        Log.d(TAG, "P2PChatService onDestroy")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wifiAwareManager.cleanup()
            bleManager.cleanup()
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    fun initialize() {
        Log.d(TAG, "Initializing P2PChatService")

        if (isInitialized) {
            Log.d(TAG, "Already initialized, skipping")
            return
        }

        // Initialize connectivity managers
        wifiAwareManager.initialize()
        bleManager.initialize()

        // Start both advertising and discovery with a delay to ensure proper initialization
        scope.launch {
            delay(1000) // Give time for managers to initialize
            startAdvertising()
            delay(500)  // Stagger discovery to avoid resource contention
            startDiscovery()
        }

        // Collect WiFi Aware peer discovery events
        scope.launch {
            wifiAwareManager.peerDiscoveredFlow.collect { peerHandle ->
                val peerId = "aware-${peerHandle.hashCode()}"
                Log.d(TAG, "New WiFi Aware peer discovered: $peerId")
                peerHandles[peerId] = peerHandle
                activePeers.add(peerId)
                _peerDiscoveredFlow.emit(peerId)

                // Immediately send a handshake message to confirm bidirectional connection
                sendHandshakeMessage(peerId)
            }
        }

        // Collect WiFi Aware messages
        scope.launch {
            wifiAwareManager.messageReceivedFlow.collect { (peerHandle, message) ->
                // Try to find existing peer ID or create new one
                val peerId = peerHandles.entries.find { it.value == peerHandle }?.key
                    ?: "aware-${peerHandle.hashCode()}".also {
                        // This is a new peer we haven't seen before
                        peerHandles[it] = peerHandle
                        activePeers.add(it)
                        scope.launch {
                            _peerDiscoveredFlow.emit(it)
                        }
                    }

                // Handle handshake messages
                if (message.startsWith("HANDSHAKE_")) {
                    Log.d(TAG, "Received handshake from peer $peerId")
                    if (!activePeers.contains(peerId)) {
                        activePeers.add(peerId)
                        scope.launch {
                            _peerDiscoveredFlow.emit(peerId)
                        }
                    }
                    // Respond to handshake
                    if (!message.contains("RESPONSE")) {
                        sendHandshakeResponse(peerId)
                    }
                } else {
                    // Regular message
                    Log.d(TAG, "Message received from peer $peerId: $message")
                    _messageReceivedFlow.emit(ChatMessage(peerId, message, System.currentTimeMillis()))
                }
            }
        }

        // Collect WiFi Aware connection state changes
        scope.launch {
            wifiAwareManager.connectionStateFlow.collect { state ->
                Log.d(TAG, "WiFi Aware state changed: $state")
                _connectionStateFlow.emit(state)

                // Handle potential disconnections
                if (state.contains("terminated") || state.contains("failed")) {
                    handleConnectionFailure()
                }
            }
        }

        // Collect BLE events
        scope.launch {
            bleManager.deviceDiscoveredFlow.collect @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT) { scanResult ->
                val deviceId = "ble-${scanResult.device.address.replace(":", "")}"
                Log.d(TAG, "New BLE device discovered: $deviceId")
                bleManager.connectToDevice(scanResult)
                activePeers.add(deviceId)
                _peerDiscoveredFlow.emit(deviceId)
            }
        }

        scope.launch {
            bleManager.messageReceivedFlow.collect { message ->
                // BLE is mainly used for connection setup, but we can handle messages too
                val peerId = "ble-device" // In a real app, you'd maintain a mapping
                _messageReceivedFlow.emit(ChatMessage(peerId, message, System.currentTimeMillis()))
            }
        }

        isInitialized = true
        Log.d(TAG, "P2PChatService initialization complete")
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        Log.d(TAG, "Starting advertising on both WiFi Aware and BLE")
        try {
            wifiAwareManager.startAdvertising()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WiFi Aware advertising: ${e.message}")
        }

        try {
            bleManager.startAdvertising()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE advertising: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        Log.d(TAG, "Starting discovery on both WiFi Aware and BLE")
        try {
            wifiAwareManager.startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WiFi Aware discovery: ${e.message}")
        }

        try {
            bleManager.startScanning()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scanning: ${e.message}")
        }
    }

    private fun handleConnectionFailure() {
        if (discoveryRetryCount < MAX_DISCOVERY_RETRIES) {
            discoveryRetryCount++
            Log.d(TAG, "Connection failure detected, retrying discovery (attempt $discoveryRetryCount)")

            scope.launch {
                delay(3000)
                startAdvertising()
                delay(500)
                startDiscovery()
            }
        } else {
            Log.e(TAG, "Too many connection failures, giving up automatic retry")
        }
    }

    private fun sendHandshakeMessage(peerId: String) {
        val message = "HANDSHAKE_${System.currentTimeMillis()}"
        sendMessage(peerId, message)
    }

    private fun sendHandshakeResponse(peerId: String) {
        val message = "HANDSHAKE_RESPONSE_${System.currentTimeMillis()}"
        sendMessage(peerId, message)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendMessage(peerId: String, message: String) {
        Log.d(TAG, "Sending message to peer $peerId: $message")

        // Primarily use WiFi Aware for messaging due to better bandwidth
        val peerHandle = peerHandles[peerId]
        if (peerHandle != null) {
            try {
                Log.d(TAG, "Sending via WiFi Aware")
                wifiAwareManager.sendMessage(peerHandle, message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send WiFi Aware message: ${e.message}")
                // Mark peer as potentially disconnected
                activePeers.remove(peerId)
            }
        } else if (peerId.startsWith("ble-")) {
            // Fall back to BLE if needed
            try {
                Log.d(TAG, "Sending via BLE")
                bleManager.sendMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send BLE message: ${e.message}")
                activePeers.remove(peerId)
            }
        } else {
            Log.e(TAG, "Unknown peer ID format: $peerId")
        }
    }

    fun getActivePeers(): List<String> {
        return activePeers.toList()
    }

    inner class LocalBinder : Binder() {
        fun getService(): P2PChatService = this@P2PChatService
    }
}