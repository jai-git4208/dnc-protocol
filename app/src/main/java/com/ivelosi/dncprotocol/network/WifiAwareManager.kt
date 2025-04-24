package com.ivelosi.dncprotocol.network

/**
 * (c) Ivelosi Technologies. All rights reserved.
 */

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.NetworkSpecifier
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

@RequiresApi(Build.VERSION_CODES.O)
class WifiAwareManager(private val context: Context) {
    private val TAG = "WifiAwareManager"
    private val SERVICE_NAME = "P2P_CHAT"
    private val SERVICE_ID = "com.ivelosi.dncprotocol"
    private val MESSAGE_ID = 1337

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager

    private var wifiAwareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null

    private var isAttaching = AtomicBoolean(false)
    private var attachRetryCount = 0
    private val MAX_ATTACH_RETRIES = 5

    private val discoveredPeers = mutableMapOf<PeerHandle, NetworkSpecifier?>()

    private val _peerDiscoveredFlow = MutableSharedFlow<PeerHandle>()
    val peerDiscoveredFlow: SharedFlow<PeerHandle> = _peerDiscoveredFlow

    private val _messageReceivedFlow = MutableSharedFlow<Pair<PeerHandle, String>>()
    val messageReceivedFlow: SharedFlow<Pair<PeerHandle, String>> = _messageReceivedFlow

    private val _connectionStateFlow = MutableSharedFlow<String>()
    val connectionStateFlow: SharedFlow<String> = _connectionStateFlow

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available")
            if (wifiAwareSession == null && !isAttaching.get()) {
                attachToSession()
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
            cleanup()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) {
                Log.d(TAG, "WiFi Aware network capabilities changed")
                scope.launch {
                    _connectionStateFlow.emit("WiFi Aware connection active")
                }
            }
        }
    }

    private val peerNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Peer network connection established!")
            scope.launch {
                _connectionStateFlow.emit("Peer connected")
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Peer network connection lost")
            scope.launch {
                _connectionStateFlow.emit("Peer disconnected")
            }
        }
    }

    private val isWifiAwareSupported: Boolean
        get() {
            val packageManager = context.packageManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
            } else {
                false
            }
        }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    @SuppressLint("MissingPermission")
    fun initialize() {
        Log.d(TAG, "Initializing WiFi Aware manager")

        if (!isWifiAwareSupported) {
            Log.e(TAG, "WiFi Aware is not supported on this device")
            return
        }

        if (wifiAwareManager == null) {
            Log.e(TAG, "WiFi Aware service is not available")
            return
        }

        // Verify required permissions
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing required permission: ACCESS_FINE_LOCATION")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing required permission: NEARBY_WIFI_DEVICES")
            return
        }

        if (!wifiAwareManager.isAvailable) {
            Log.e(TAG, "WiFi Aware is not available on this device currently")

            // Register for network callbacks to try again when WiFi is available
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            try {
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
                Log.d(TAG, "Registered for network callbacks")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register network callback: ${e.message}")
            }
            return
        }

        // WiFi Aware is available now, attach immediately
        attachToSession()
    }

    fun cleanup() {
        Log.d(TAG, "Cleaning up WiFi Aware manager")

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            // Network callback wasn't registered
            Log.d(TAG, "Network callback wasn't registered")
        }

        try {
            connectivityManager.unregisterNetworkCallback(peerNetworkCallback)
        } catch (e: IllegalArgumentException) {
            // Peer network callback wasn't registered
        }

        publishSession?.close()
        publishSession = null

        subscribeSession?.close()
        subscribeSession = null

        wifiAwareSession = null
        isAttaching.set(false)
        attachRetryCount = 0
        discoveredPeers.clear()
    }

    private fun attachToSession() {
        if (wifiAwareManager == null || !wifiAwareManager.isAvailable) {
            Log.e(TAG, "WiFi Aware is not available for attach")
            return
        }

        if (isAttaching.getAndSet(true)) {
            Log.d(TAG, "Attach already in progress, ignoring")
            return
        }

        if (attachRetryCount >= MAX_ATTACH_RETRIES) {
            Log.e(TAG, "Exceeded maximum attach retries, giving up")
            isAttaching.set(false)
            return
        }

        attachRetryCount++

        Log.d(TAG, "Attaching to WiFi Aware session (attempt $attachRetryCount)")

        try {
            wifiAwareManager.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    Log.d(TAG, "Successfully attached to WiFi Aware session")
                    wifiAwareSession = session
                    isAttaching.set(false)
                    attachRetryCount = 0

                    scope.launch {
                        _connectionStateFlow.emit("WiFi Aware attached")
                    }

                    // Start discovery and publish after successful attachment
                    scope.launch {
                        startAdvertising()
                        delay(500)
                        startDiscovery()
                    }
                }

                override fun onAttachFailed() {
                    Log.e(TAG, "Failed to attach to WiFi Aware session")
                    isAttaching.set(false)

                    scope.launch {
                        _connectionStateFlow.emit("WiFi Aware attach failed")
                    }

                    // Retry with exponential backoff
                    scope.launch {
                        val delayTime = (1000L * (1 shl minOf(attachRetryCount, 4)))
                        delay(delayTime)
                        attachToSession()
                    }
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during WiFi Aware attach: ${e.message}")
            isAttaching.set(false)

            // Try to recover by registering for network callbacks
            try {
                val networkRequest = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            } catch (e: Exception) {
                // Already registered, ignore
            }
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES])
    fun startAdvertising() {
        val session = wifiAwareSession ?: run {
            Log.e(TAG, "No active WiFi Aware session for advertising")
            return
        }

        Log.d(TAG, "Starting WiFi Aware advertising")

        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(SERVICE_ID.toByteArray(StandardCharsets.UTF_8))
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .setTerminateNotificationEnabled(true)
            .build()

        try {
            session.publish(config, object : DiscoverySessionCallback() {
                override fun onPublishStarted(session: PublishDiscoverySession) {
                    Log.d(TAG, "WiFi Aware advertising started successfully")
                    publishSession = session

                    scope.launch {
                        _connectionStateFlow.emit("Publishing started")
                    }
                }

                override fun onSessionConfigFailed() {
                    Log.e(TAG, "Failed to configure publish session")
                    publishSession = null

                    scope.launch {
                        _connectionStateFlow.emit("Publishing failed")
                    }

                    // Try to restart advertising after delay
                    scope.launch {
                        delay(3000)
                        startAdvertising()
                    }
                }

                override fun onSessionTerminated() {
                    Log.d(TAG, "Publish session terminated")
                    publishSession = null

                    scope.launch {
                        _connectionStateFlow.emit("Publishing terminated")
                    }

                    // Try to restart advertising
                    scope.launch {
                        delay(5000)
                        startAdvertising()
                    }
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val messageString = String(message, StandardCharsets.UTF_8)
                    Log.d(TAG, "Message received via publish channel: $messageString")
                    scope.launch {
                        _messageReceivedFlow.emit(Pair(peerHandle, messageString))
                    }
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting WiFi Aware advertising: ${e.message}")

            // Try to reattach
            scope.launch {
                delay(5000)
                if (wifiAwareSession == null && !isAttaching.get()) {
                    attachToSession()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val session = wifiAwareSession ?: run {
            Log.e(TAG, "No active WiFi Aware session for discovery")
            return
        }

        Log.d(TAG, "Starting WiFi Aware discovery")

        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setMatchFilter(null) // Match any instance of this service
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .setTerminateNotificationEnabled(true)
            .build()

        try {
            session.subscribe(config, object : DiscoverySessionCallback() {
                override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                    Log.d(TAG, "WiFi Aware discovery started successfully")
                    subscribeSession = session

                    scope.launch {
                        _connectionStateFlow.emit("Discovery started")
                    }
                }

                override fun onSessionConfigFailed() {
                    Log.e(TAG, "Failed to configure subscribe session")
                    subscribeSession = null

                    scope.launch {
                        _connectionStateFlow.emit("Discovery failed")
                    }

                    // Try to restart discovery after delay
                    scope.launch {
                        delay(3000)
                        startDiscovery()
                    }
                }

                override fun onSessionTerminated() {
                    Log.d(TAG, "Subscribe session terminated")
                    subscribeSession = null

                    scope.launch {
                        _connectionStateFlow.emit("Discovery terminated")
                    }

                    // Try to restart discovery
                    scope.launch {
                        delay(5000)
                        startDiscovery()
                    }
                }

                override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?, matchFilter: List<ByteArray>?) {
                    val serviceId = serviceSpecificInfo?.let { String(it, StandardCharsets.UTF_8) }
                    Log.d(TAG, "WiFi Aware peer discovered: $peerHandle, serviceId: $serviceId")

                    if (serviceId == SERVICE_ID || serviceSpecificInfo?.contentEquals(SERVICE_ID.toByteArray(StandardCharsets.UTF_8)) == true) {
                        discoveredPeers[peerHandle] = null

                        // Create a network specifier to connect to this peer
                        createNetworkSpecifier(peerHandle)

                        scope.launch {
                            _peerDiscoveredFlow.emit(peerHandle)
                            _connectionStateFlow.emit("Peer discovered")
                        }

                        // After discovery, send a ping message to establish bidirectional connection
                        sendPingMessage(peerHandle)
                    } else {
                        Log.d(TAG, "Discovered service doesn't match our service ID")
                    }
                }

                override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                    val messageString = String(message, StandardCharsets.UTF_8)
                    Log.d(TAG, "Message received via subscribe channel: $messageString")

                    // If this is our first message from this peer, also emit a discovery event
                    if (!discoveredPeers.containsKey(peerHandle)) {
                        discoveredPeers[peerHandle] = null
                        scope.launch {
                            _peerDiscoveredFlow.emit(peerHandle)
                        }
                    }

                    scope.launch {
                        _messageReceivedFlow.emit(Pair(peerHandle, messageString))
                    }
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting WiFi Aware discovery: ${e.message}")

            // Try to reattach
            scope.launch {
                delay(5000)
                if (wifiAwareSession == null && !isAttaching.get()) {
                    attachToSession()
                }
            }
        }
    }

    private fun sendPingMessage(peerHandle: PeerHandle) {
        try {
            Log.d(TAG, "Sending ping message to establish connection with peer: $peerHandle")
            val pingMessage = "PING_${System.currentTimeMillis()}"
            sendMessage(peerHandle, pingMessage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ping message: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("MissingPermission")
    private fun createNetworkSpecifier(peerHandle: PeerHandle) {
        val session = publishSession ?: subscribeSession ?: return

        try {
            // Create network specifier for this peer
            val networkSpecifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiAwareNetworkSpecifier.Builder(session, peerHandle)
                    .setPskPassphrase("P2PChatSecurePassword")
                    .build()
            } else {
                // For API 26-28
                // Use deprecated API if needed (specifics dependent on exact implementation)
                null
            }

            // Store network specifier
            discoveredPeers[peerHandle] = networkSpecifier

            // Request network using the specifier
            if (networkSpecifier != null) {
                requestNetwork(networkSpecifier)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create network specifier: ${e.message}")
        }
    }

    private fun requestNetwork(networkSpecifier: NetworkSpecifier) {
        try {
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(networkSpecifier)
                .build()

            Log.d(TAG, "Requesting WiFi Aware network connection")
            connectivityManager.requestNetwork(networkRequest, peerNetworkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request network: ${e.message}")
        }
    }

    fun sendMessage(peerHandle: PeerHandle, message: String) {
        Log.d(TAG, "Sending message via WiFi Aware to peer: $peerHandle")

        val session = publishSession ?: subscribeSession
        if (session == null) {
            Log.e(TAG, "No active WiFi Aware discovery session for sending message")
            return
        }

        try {
            val messageBytes = message.toByteArray(StandardCharsets.UTF_8)
            session.sendMessage(peerHandle, MESSAGE_ID, messageBytes)
            Log.d(TAG, "Message sent successfully to peer: $peerHandle")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message: ${e.message}")

            // Peer might be lost, try to rediscover
            discoveredPeers.remove(peerHandle)
        }
    }
}