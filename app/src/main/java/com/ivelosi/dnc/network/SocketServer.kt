package com.ivelosi.dnc.network

import android.content.Context
import android.util.Log
import com.ivelosi.dnc.notification.DNCNotificationManager
import com.ivelosi.dnc.signal.MessageProtocol
import com.ivelosi.dnc.signal.SocketCommunicator
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.net.NetworkInterface
import java.net.InetAddress
import android.net.wifi.WifiManager

/**
 * Socket server that listens for incoming connections from other devices.
 */
class SocketServer(
    private val context: Context,
    private val logger: NetworkLogger,
    private val onClientConnected: (BluetoothDeviceInfo, Socket) -> Unit,
    private val onClientDisconnected: (BluetoothDeviceInfo) -> Unit,
    private val onMessageReceived: (BluetoothDeviceInfo, String, String) -> Unit
) {
    private val TAG = "DNCSocketServer"
    private val SERVER_PORT = 8080 // Default port to listen on
    private val FALLBACK_PORTS = listOf(8081, 8082, 8090, 9000) // Fallback ports to try if default fails

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connectedClients = ConcurrentHashMap<String, SocketCommunicator>()
    private val notificationManager = DNCNotificationManager(context)
    
    // Local device IPs to prevent self-connection
    private val localIPs = mutableListOf<String>()

    private var serverPort = SERVER_PORT
    
    init {
        // Collect local IP addresses to prevent self-connections
        collectLocalIPs()
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
            logger.log("Server local device IPs: $localIPs")
        } catch (e: Exception) {
            logger.log("Error collecting local IPs: ${e.message}")
            // Add localhost as fallback
            localIPs.add("127.0.0.1")
            localIPs.add("localhost")
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
     * Start the server on the default port or try fallback ports
     * @return true if server started successfully
     */
    fun start(): Boolean {
        if (isRunning) {
            logger.log("Server is already running on port $serverPort")
            return true
        }

        // Try to start server on default port
        if (tryStartServer(SERVER_PORT)) {
            return true
        }

        // Try fallback ports
        for (port in FALLBACK_PORTS) {
            if (tryStartServer(port)) {
                return true
            }
        }

        logger.log("Failed to start server on any port")
        notificationManager.showDiscoveryNotification("Failed to start DNC connection server")
        return false
    }

    /**
     * Try to start server on specified port
     * @return true if server started successfully
     */
    private fun tryStartServer(port: Int): Boolean {
        try {
            serverSocket = ServerSocket(port)
            serverPort = port
            isRunning = true
            
            logger.log("Server started on port $port")
            notificationManager.showDiscoveryNotification("DNC connection server running on port $port")
            
            // Start accepting connections
            scope.launch {
                acceptConnections()
            }
            
            return true
        } catch (e: Exception) {
            logger.log("Failed to start server on port $port: ${e.message}")
            return false
        }
    }

    /**
     * Accept incoming connections in a coroutine
     */
    private suspend fun acceptConnections() {
        try {
            while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                try {
                    val clientSocket = serverSocket!!.accept()
                    
                    // Handle the connection in a new coroutine
                    scope.launch {
                        handleClientConnection(clientSocket)
                    }
                } catch (e: SocketException) {
                    if (isRunning) {
                        logger.log("Error accepting connection: ${e.message}")
                    }
                    // If not running, this is normal during shutdown
                } catch (e: Exception) {
                    logger.log("Unexpected error accepting connection: ${e.message}")
                }
            }
        } finally {
            stopServer()
        }
    }

    /**
     * Handle a new client connection
     */
    private fun handleClientConnection(clientSocket: Socket) {
        try {
            val clientAddress = clientSocket.inetAddress.hostAddress ?: "unknown"
            logger.log("New connection from $clientAddress")
            
            // Check if this is a self-connection (local device connecting to itself)
            if (isLocalIP(clientAddress)) {
                logger.log("Rejecting self-connection from $clientAddress")
                clientSocket.close()
                return
            }
            
            // Create a temporary device info until we get the handshake
            val tempDevice = BluetoothDeviceInfo(
                name = "Unknown Device ($clientAddress)",
                address = clientAddress,
                wifiInfo = "Connected via: ${clientSocket.localPort}",
                ipAddress = clientAddress,
                isConnected = true,
                rssi = -100  // Add a default RSSI value for socket connections
            )
            
            // Create a communicator for this client
            val communicator = SocketCommunicator(
                deviceInfo = tempDevice,
                socket = clientSocket,
                logger = logger,
                scope = scope,
                onMessageReceived = { device, type, payload ->
                    handleClientMessage(device, type, payload)
                },
                onDisconnect = { device ->
                    connectedClients.remove(device.address)
                    onClientDisconnected(device)
                    logger.log("Client disconnected: ${device.name}")
                },
                context = context
            )
            
            // Store in connected clients map
            connectedClients[tempDevice.address] = communicator
            
            // Notify listener
            onClientConnected(tempDevice, clientSocket)
            
            // Wait for handshake message (this is passive, communicator handles it)
            
        } catch (e: Exception) {
            logger.log("Error handling client connection: ${e.message}")
        }
    }

    /**
     * Handle messages from clients
     */
    private fun handleClientMessage(device: BluetoothDeviceInfo, type: String, payload: String) {
        // Process handshake to get device info
        if (type == MessageProtocol.TYPE_HANDSHAKE) {
            processHandshake(device, payload)
        }
        
        // Forward message to listener
        onMessageReceived(device, type, payload)
    }

    /**
     * Process handshake message to update device info
     */
    private fun processHandshake(device: BluetoothDeviceInfo, payload: String) {
        try {
            if (payload.startsWith("DNC-CONNECT:")) {
                val deviceName = payload.substring("DNC-CONNECT:".length)
                
                // Update device info with the real name
                val updatedDevice = device.copy(
                    name = deviceName,
                    connectionStatus = "Connected"
                )
                
                // Update in our map
                connectedClients[device.address]?.let { communicator ->
                    // Get the old communicator
                    connectedClients.remove(device.address)
                    
                    // Store with updated device info
                    connectedClients[updatedDevice.address] = communicator
                    
                    // Send a response back with our device model
                    communicator.sendMessage(
                        MessageProtocol.TYPE_HANDSHAKE,
                        "ACCEPTED:${android.os.Build.MODEL}"
                    )
                    
                    // Also send our IP address to the client
                    sendIPAddressToPeer(communicator)
                    
                    logger.log("Handshake completed with ${updatedDevice.name}")
                    notificationManager.showDiscoveryNotification("Device connected: ${updatedDevice.name}")
                }
            }
        } catch (e: Exception) {
            logger.log("Error processing handshake: ${e.message}")
        }
    }
    
    /**
     * Send our IP address to a peer
     */
    private fun sendIPAddressToPeer(communicator: SocketCommunicator) {
        try {
            // Get our current IP address
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = WifiUtils.getWifiInfo(wifiManager)
            val localIP = WifiUtils.extractIpAddress(wifiInfo, wifiManager)
            
            if (localIP.isNotEmpty() && !isLocalIP(localIP)) {
                // Send our IP address to the peer
                communicator.sendMessage(
                    MessageProtocol.TYPE_IP_BROADCAST,
                    localIP
                )
                logger.log("Sent our IP address ($localIP) to ${communicator.deviceInfo.name}")
            }
        } catch (e: Exception) {
            logger.log("Error sending IP address to peer: ${e.message}")
        }
    }

    /**
     * Send a message to a specific client
     */
    fun sendMessage(deviceAddress: String, type: String, payload: String): Boolean {
        val communicator = connectedClients[deviceAddress]
        return if (communicator != null) {
            communicator.sendMessage(type, payload)
            true
        } else {
            logger.log("Cannot send message: Client not connected")
            false
        }
    }

    /**
     * Stop the server and disconnect all clients
     */
    fun stop() {
        if (!isRunning) return
        
        stopServer()
        logger.log("Server stopped")
        notificationManager.showDiscoveryNotification("DNC connection server stopped")
    }

    /**
     * Clean up resources
     */
    private fun stopServer() {
        isRunning = false
        
        // Disconnect all clients
        connectedClients.values.forEach { it.close() }
        connectedClients.clear()
        
        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket: ${e.message}")
        }
        
        serverSocket = null
    }

    /**
     * Get the port the server is running on
     */
    fun getServerPort(): Int {
        return serverPort
    }

    /**
     * Check if the server is running
     */
    fun isRunning(): Boolean {
        return isRunning
    }

    /**
     * Get all connected clients
     */
    fun getConnectedClients(): List<BluetoothDeviceInfo> {
        return connectedClients.keys.mapNotNull { address ->
            connectedClients[address]?.deviceInfo
        }
    }
} 