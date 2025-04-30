package com.ivelosi.dnc.network

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 */

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import android.os.Build
import android.content.Context
import android.net.wifi.WifiManager
import com.ivelosi.dnc.notification.DNCNotificationManager
import com.ivelosi.dnc.signal.MessageProtocol
import com.ivelosi.dnc.signal.SocketCommunicator
import java.net.ConnectException
import java.net.InetAddress
import java.net.NetworkInterface
import android.bluetooth.BluetoothDevice

class NetworkManager(private val context: Context, private val logger: NetworkLogger) {
    private val TAG = "DNCNetworkManager"
    private val DEFAULT_PORT = 8080 // Default socket port
    private val FALLBACK_PORTS = listOf(8081, 8082, 8090, 9000) // Fallback ports to try if default fails
    private val socketConnections = mutableMapOf<String, Socket>()
    private val communicators = mutableMapOf<String, SocketCommunicator>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Local device IPs to prevent self-connection
    private val localIPs = mutableListOf<String>()
    
    // Cache of device IP addresses from broadcasts
    private val deviceIPAddresses = mutableMapOf<String, String>()
    
    // Add notification manager
    private val notificationManager = DNCNotificationManager(context)
    
    // Add BluetoothHandshake for getting remote device IP addresses
    private val bluetoothHandshake = BluetoothHandshake(context, logger)

    // Callback for received messages
    private var messageReceivedCallback: ((BluetoothDeviceInfo, String, String) -> Unit)? = null
    
    // Add socket server for accepting incoming connections
    private val socketServer: SocketServer = SocketServer(
        context = context,
        logger = logger,
        onClientConnected = { device, socket -> handleIncomingConnection(device, socket) },
        onClientDisconnected = { device -> handleDisconnect(device) },
        onMessageReceived = { device, type, payload -> handleIncomingMessage(device, type, payload) }
    )
    
    // Singleton instance for the NetworkManager
    companion object {
        @Volatile
        private var instance: NetworkManager? = null
        
        fun getInstance(context: Context): NetworkManager? {
            return instance
        }
    }

    init {
        // Set the singleton instance
        instance = this
        
        // Get local IP addresses to prevent self-connection
        collectLocalIPs()
        
        // Start the socket server when NetworkManager is created
        coroutineScope.launch {
            startSocketServer()
        }
        
        // Start broadcasting our own IP address periodically
        startIPBroadcasting()
    }
    
    /**
     * Collect all local IP addresses to prevent connecting to self
     */
    private fun collectLocalIPs() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress) {
                        localIPs.add(address.hostAddress ?: "")
                    }
                }
            }
            logger.log("Local device IPs: $localIPs")
        } catch (e: Exception) {
            logger.log("Error collecting local IPs: ${e.message}")
            // Add localhost as fallback
            localIPs.add("127.0.0.1")
            localIPs.add("localhost")
        }
    }
    
    /**
     * Start the socket server to accept incoming connections
     */
    private fun startSocketServer() {
        if (socketServer.start()) {
            val port = socketServer.getServerPort()
            logger.log("Socket server started on port $port")
            notificationManager.showSocketServerNotification(port)
            
            // Start a heartbeat to keep the server alive and monitor connections
            startServerHeartbeat()
        } else {
            logger.log("Failed to start socket server")
            notificationManager.showConnectionNotification(
                "Server Error", 
                "DNC service unavailable - port binding failed"
            )
        }
    }
    
    /**
     * Start a periodic heartbeat to monitor server status
     */
    private fun startServerHeartbeat() {
        coroutineScope.launch {
            while (true) {
                try {
                    // Check if server is still running
                    if (!socketServer.isRunning()) {
                        // Try to restart server if it stopped
                        logger.log("Server not running, attempting restart...")
                        if (socketServer.start()) {
                            val port = socketServer.getServerPort()
                            logger.log("Socket server restarted on port $port")
                            notificationManager.showSocketServerNotification(port)
                        } else {
                            logger.log("Failed to restart socket server")
                        }
                    }
                    
                    // Get connected clients
                    val connectedClients = socketServer.getConnectedClients()
                    if (connectedClients.isNotEmpty()) {
                        logger.log("Server has ${connectedClients.size} connected clients")
                    }
                } catch (e: Exception) {
                    logger.log("Error in server heartbeat: ${e.message}")
                }
                
                // Check every 60 seconds
                delay(60_000)
            }
        }
    }
    
    /**
     * Use the handshake mechanism to get the remote device's IP address
     * This is called from the WifiUtils.extractIpAddress method
     */
    suspend fun getRemoteDeviceIpViaHandshake(device: BluetoothDevice): String? {
        return try {
            logger.log("Attempting handshake with ${device.name ?: "Unknown Device"} to get IP address")
            bluetoothHandshake.getRemoteDeviceIpAddress(device)
        } catch (e: Exception) {
            logger.log("Handshake failed: ${e.message}")
            null
        }
    }
    
    /**
     * Start periodic broadcasting of our IP address so other devices can discover us
     */
    private fun startIPBroadcasting() {
        coroutineScope.launch {
            while (true) {
                try {
                    // Broadcast our IP to all connected devices
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val wifiInfo = WifiUtils.getWifiInfo(context, wifiManager)
                    val localIP = WifiUtils.extractIpAddress(context, wifiInfo, wifiManager, null)
                    
                    if (localIP.isNotEmpty()) {
                        broadcastIPAddress(localIP)
                    }
                } catch (e: Exception) {
                    logger.log("Error broadcasting IP: ${e.message}")
                }
                
                // Broadcast every 30 seconds
                delay(30_000)
            }
        }
    }
    
    /**
     * Broadcast our IP address to all connected devices
     */
    private fun broadcastIPAddress(ipAddress: String) {
        // Only broadcast if we have a valid IP
        if (ipAddress.isEmpty() || isLocalIP(ipAddress)) {
            return
        }
        
        logger.log("Broadcasting our IP address: $ipAddress")
        
        // Send to all connected devices
        val connectedDevices = getConnectedDevices()
        for (device in connectedDevices) {
            // Use the communicator if available
            communicators[device.address]?.let { communicator ->
                communicator.sendMessage(
                    MessageProtocol.TYPE_IP_BROADCAST,
                    ipAddress
                )
            }
        }
    }
    
    /**
     * Update a device's IP address from a broadcast
     */
    private fun updateDeviceIPAddress(device: BluetoothDeviceInfo, ipAddress: String) {
        // Validate the IP address
        if (ipAddress.isEmpty() || isLocalIP(ipAddress)) {
            logger.log("Ignoring invalid IP broadcast from ${device.name}: $ipAddress")
            return
        }
        
        logger.log("Received IP broadcast from ${device.name}: $ipAddress")
        
        // Store in our cache
        deviceIPAddresses[device.address] = ipAddress
        
        // Update the device object
        val updatedDevice = device.copy(ipAddress = ipAddress)
        
        // Update in communicators if necessary
        communicators[device.address]?.let { communicator ->
            // Update the device info in the communicator
            val field = communicator.javaClass.getDeclaredField("deviceInfo")
            field.isAccessible = true
            field.set(communicator, updatedDevice)
        }
    }
    
    /**
     * Handle an incoming connection from the socket server
     */
    private fun handleIncomingConnection(device: BluetoothDeviceInfo, socket: Socket) {
        // Store the connection
        socketConnections[device.address] = socket
        
        // Update our device list if we have a callback
        messageReceivedCallback?.let { callback ->
            // Just notify with a dummy handshake message
            callback(device, MessageProtocol.TYPE_HANDSHAKE, "INCOMING_CONNECTION")
        }
        
        logger.log("Incoming connection accepted from ${device.name}")
        notificationManager.showConnectionNotification(
            "New Connection", 
            "Device connected: ${device.name}"
        )
    }
    
    /**
     * Handle messages from the socket server
     */
    private fun handleIncomingMessage(device: BluetoothDeviceInfo, type: String, payload: String) {
        // Handle IP broadcasts specially
        if (type == MessageProtocol.TYPE_IP_BROADCAST) {
            updateDeviceIPAddress(device, payload)
            return // No need to forward to callback
        }
        
        // Forward to the callback if set
        messageReceivedCallback?.invoke(device, type, payload)
    }

    /**
     * Set a callback to be notified when a message is received
     */
    fun setOnMessageReceivedListener(callback: (BluetoothDeviceInfo, String, String) -> Unit) {
        messageReceivedCallback = callback
    }

    fun connectToDevice(device: BluetoothDeviceInfo, onStatusUpdate: suspend (BluetoothDeviceInfo, String, Boolean) -> Unit) {
        // Already have an active connection to this device
        if (socketConnections.containsKey(device.address) && socketConnections[device.address]?.isConnected == true) {
            logger.log("Already connected to ${device.name}")
            return
        }

        // Check if we have a cached IP from broadcasts
        var targetIP = deviceIPAddresses[device.address]
        
        // If no cached IP, fall back to the one in the device object
        if (targetIP.isNullOrEmpty()) {
            targetIP = device.ipAddress
        }
        
        // No IP address available
        if (targetIP.isEmpty()) {
            logger.log("Cannot connect to ${device.name}: No IP address available")
            notificationManager.showDiscoveryNotification("Cannot connect to ${device.name}: No IP address available")
            coroutineScope.launch {
                onStatusUpdate(device, "Failed: No IP address", false)
            }
            return
        }
        
        // Check if trying to connect to self
        if (isLocalIP(targetIP)) {
            logger.log("Cannot connect to self: $targetIP is a local IP address")
            notificationManager.showDiscoveryNotification("Cannot connect to self: ${device.name} is your own device")
            coroutineScope.launch {
                onStatusUpdate(device, "Failed: Cannot connect to self", false)
            }
            return
        }

        coroutineScope.launch {
            try {
                // Try connecting with the default port first
                logger.log("Connecting to ${device.name} at $targetIP:$DEFAULT_PORT...")
                notificationManager.showDiscoveryNotification("Connecting to ${device.name}...")
                onStatusUpdate(device, "Connecting...", false)

                if (tryConnect(device, targetIP, DEFAULT_PORT, onStatusUpdate)) {
                    return@launch // Connection successful
                }
                
                // If default port failed, try the fallback ports
                for (port in FALLBACK_PORTS) {
                    logger.log("Trying alternate port for ${device.name}: $port")
                    if (tryConnect(device, targetIP, port, onStatusUpdate)) {
                        return@launch // Connection successful with fallback port
                    }
                }
                
                // All connection attempts failed
                logger.log("Failed to connect to ${device.name} on any port")
                notificationManager.showDiscoveryNotification("Failed to connect to ${device.name} on any port")
                onStatusUpdate(device, "All connection attempts failed", false)
                
            } catch (e: Exception) {
                logger.log("Unexpected error during connection attempts: ${e.message}")
                notificationManager.showDiscoveryNotification("Error connecting to ${device.name}: ${e.message}")
                onStatusUpdate(device, "Error: ${e.message}", false)
            }
        }
    }
    
    /**
     * Check if an IP address is one of our local addresses
     */
    private fun isLocalIP(ipAddress: String): Boolean {
        // Check against our collected local IPs
        if (localIPs.contains(ipAddress)) {
            return true
        }
        
        // Also check if it resolves to a loopback
        try {
            val addr = InetAddress.getByName(ipAddress)
            return addr.isLoopbackAddress || addr.isAnyLocalAddress
        } catch (e: Exception) {
            // In case of error, be cautious
            logger.log("Error checking if IP is local: ${e.message}")
        }
        
        return false
    }
    
    /**
     * Try to connect to a specific IP and port
     * @return true if connection successful, false otherwise
     */
    private suspend fun tryConnect(
        device: BluetoothDeviceInfo, 
        ipAddress: String, 
        port: Int,
        onStatusUpdate: suspend (BluetoothDeviceInfo, String, Boolean) -> Unit
    ): Boolean {
        return try {
            val socket = Socket()
            // Set connection timeout
            socket.connect(InetSocketAddress(ipAddress, port), 10000)
            
            if (socket.isConnected) {
                // Configure socket for better performance and reliability
                socket.keepAlive = true
                socket.tcpNoDelay = true
                socket.sendBufferSize = 65536
                socket.receiveBufferSize = 65536
                
                socketConnections[device.address] = socket
                logger.log("Connected successfully to ${device.name} on port $port")
                notificationManager.showDiscoveryNotification("Connected successfully to ${device.name}")
                onStatusUpdate(device, "Connected", true)

                // Update device connection status
                device.isConnected = true

                // Create communicator for this connection
                val communicator = SocketCommunicator(
                    deviceInfo = device,
                    socket = socket,
                    logger = logger,
                    scope = coroutineScope,
                    onMessageReceived = { d, type, payload ->
                        messageReceivedCallback?.invoke(d, type, payload)
                    },
                    onDisconnect = { d ->
                        handleDisconnect(d)
                        coroutineScope.launch {
                            onStatusUpdate(d, "Disconnected", false)
                        }
                    },
                    context = context
                )
                communicators[device.address] = communicator

                // Send initial handshake
                communicator.sendMessage(
                    MessageProtocol.TYPE_HANDSHAKE,
                    "DNC-CONNECT:${Build.MODEL}"
                )
                
                true // Connection successful
            } else {
                socket.close()
                false // Connection failed
            }
        } catch (e: ConnectException) {
            logger.log("Connection refused on port $port: ${e.message}")
            false // Connection refused
        } catch (e: Exception) {
            logger.log("Error connecting on port $port: ${e.message}")
            false // Other connection error
        }
    }

    fun disconnectFromDevice(device: BluetoothDeviceInfo, onStatusUpdate: suspend (BluetoothDeviceInfo, String, Boolean) -> Unit) {
        communicators[device.address]?.close()
        communicators.remove(device.address)

        socketConnections[device.address]?.let { socket ->
            try {
                if (!socket.isClosed) {
                    socket.close()
                }
                socketConnections.remove(device.address)
                logger.log("Disconnected from ${device.name}")
                notificationManager.showDiscoveryNotification("Disconnected from ${device.name}")
                
                // Update device connection status
                device.isConnected = false

                coroutineScope.launch {
                    onStatusUpdate(device, "Disconnected", false)
                }
            } catch (e: Exception) {
                logger.log("Error closing connection to ${device.name}: ${e.message}")
                notificationManager.showDiscoveryNotification("Error disconnecting from ${device.name}: ${e.message}")
            }
        }
    }

    /**
     * Handle device disconnection
     */
    private fun handleDisconnect(device: BluetoothDeviceInfo) {
        communicators.remove(device.address)
        socketConnections.remove(device.address)
        // Update device connection status
        device.isConnected = false
        logger.log("Device ${device.name} disconnected")
        notificationManager.showDiscoveryNotification("Device ${device.name} disconnected")
    }

    /**
     * Send a text message to a connected device
     */
    fun sendTextMessage(device: BluetoothDeviceInfo, message: String): Boolean {
        // First try to use communicator if available
        val communicator = communicators[device.address]
        if (communicator != null) {
            communicator.sendTextMessage(message)
            return true
        }
        
        // Otherwise check if the device is connected via the server
        if (socketServer.sendMessage(device.address, MessageProtocol.TYPE_TEXT, message)) {
            return true
        }
        
        logger.log("Cannot send message: Not connected to ${device.name}")
        notificationManager.showDiscoveryNotification("Cannot send message: Not connected to ${device.name}")
        return false
    }

    /**
     * Send a command to a connected device
     */
    fun sendCommand(device: BluetoothDeviceInfo, command: String, args: String = ""): Boolean {
        val payload = if (args.isEmpty()) command else "$command:$args"
        
        // First try to use communicator if available  
        val communicator = communicators[device.address]
        if (communicator != null) {
            communicator.sendCommand(command, args)
            return true
        }
        
        // Otherwise check if the device is connected via the server
        if (socketServer.sendMessage(device.address, MessageProtocol.TYPE_COMMAND, payload)) {
            return true
        }
        
        logger.log("Cannot send command: Not connected to ${device.name}")
        notificationManager.showDiscoveryNotification("Cannot send command: Not connected to ${device.name}")
        return false
    }

    /**
     * Send a file to a connected device
     */
    fun sendFile(device: BluetoothDeviceInfo, fileName: String, fileContent: ByteArray): Boolean {
        val communicator = communicators[device.address]
        if (communicator != null) {
            communicator.sendFile(fileName, fileContent)
            notificationManager.showDiscoveryNotification("Started sending file: $fileName to ${device.name}")
            return true
        }
        logger.log("Cannot send file: Not connected to ${device.name}")
        notificationManager.showDiscoveryNotification("Cannot send file: Not connected to ${device.name}")
        return false
    }
    
    /**
     * Get all connected devices (both outgoing and incoming connections)
     */
    fun getConnectedDevices(): List<BluetoothDeviceInfo> {
        val devices = mutableListOf<BluetoothDeviceInfo>()
        
        // Add devices from outgoing connections
        devices.addAll(
            communicators.keys.mapNotNull { address ->
                val socket = socketConnections[address]
                if (socket != null && socket.isConnected) {
                    val device = communicators[address]?.deviceInfo
                    device?.copy(isConnected = true)
                } else null
            }
        )
        
        // Add devices from server connections
        devices.addAll(socketServer.getConnectedClients())
        
        return devices
    }

    fun cleanup() {
        // Stop the socket server
        socketServer.stop()
        
        // Close all communicators
        communicators.values.forEach { communicator ->
            communicator.close()
        }
        communicators.clear()

        // Close all socket connections
        socketConnections.values.forEach { socket ->
            try {
                if (!socket.isClosed) {
                    socket.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket: ${e.message}")
            }
        }
        socketConnections.clear()

        // Cancel all coroutines
        coroutineScope.cancel()

        logger.log("Network manager cleaned up")
        notificationManager.showDiscoveryNotification("Network connections closed")
    }
}