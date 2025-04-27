package com.ivelosi.dnc.network

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 */

import android.util.Log
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import android.os.Build
import com.ivelosi.dnc.signal.MessageProtocol
import com.ivelosi.dnc.signal.SocketCommunicator

class NetworkManager(private val logger: NetworkLogger) {
    private val TAG = "DNCNetworkManager"
    private val DEFAULT_PORT = 8080 // Default socket port
    private val socketConnections = mutableMapOf<String, Socket>()
    private val communicators = mutableMapOf<String, SocketCommunicator>()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callback for received messages
    private var messageReceivedCallback: ((BluetoothDeviceInfo, String, String) -> Unit)? = null

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

        // No IP address available
        if (device.ipAddress.isEmpty()) {
            logger.log("Cannot connect to ${device.name}: No IP address available")
            coroutineScope.launch {
                onStatusUpdate(device, "Failed: No IP address", false)
            }
            return
        }

        coroutineScope.launch {
            try {
                logger.log("Connecting to ${device.name} at ${device.ipAddress}:$DEFAULT_PORT...")
                onStatusUpdate(device, "Connecting...", false)

                val socket = Socket()
                // Set connection timeout to 5 seconds
                socket.connect(InetSocketAddress(device.ipAddress, DEFAULT_PORT), 5000)

                if (socket.isConnected) {
                    socketConnections[device.address] = socket
                    logger.log("Connected successfully to ${device.name}")
                    onStatusUpdate(device, "Connected", true)

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
                        }
                    )
                    communicators[device.address] = communicator

                    // Send initial handshake
                    communicator.sendMessage(
                        MessageProtocol.TYPE_HANDSHAKE,
                        "DNC-CONNECT:${Build.MODEL}"
                    )
                } else {
                    logger.log("Failed to connect to ${device.name}")
                    onStatusUpdate(device, "Connection failed", false)
                }
            } catch (e: Exception) {
                logger.log("Connection error with ${device.name}: ${e.message}")
                onStatusUpdate(device, "Error: ${e.message}", false)
            }
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

                coroutineScope.launch {
                    onStatusUpdate(device, "Disconnected", false)
                }
            } catch (e: Exception) {
                logger.log("Error closing connection to ${device.name}: ${e.message}")
            }
        }
    }

    /**
     * Handle device disconnection
     */
    private fun handleDisconnect(device: BluetoothDeviceInfo) {
        communicators.remove(device.address)
        socketConnections.remove(device.address)
        logger.log("Device ${device.name} disconnected")
    }

    /**
     * Send a text message to a connected device
     */
    fun sendTextMessage(device: BluetoothDeviceInfo, message: String): Boolean {
        val communicator = communicators[device.address]
        if (communicator != null) {
            communicator.sendTextMessage(message)
            return true
        }
        logger.log("Cannot send message: Not connected to ${device.name}")
        return false
    }

    /**
     * Send a command to a connected device
     */
    fun sendCommand(device: BluetoothDeviceInfo, command: String, args: String = ""): Boolean {
        val communicator = communicators[device.address]
        if (communicator != null) {
            communicator.sendCommand(command, args)
            return true
        }
        logger.log("Cannot send command: Not connected to ${device.name}")
        return false
    }

    /**
     * Send a file to a connected device
     */
    fun sendFile(device: BluetoothDeviceInfo, fileName: String, fileContent: ByteArray): Boolean {
        val communicator = communicators[device.address]
        if (communicator != null) {
            communicator.sendFile(fileName, fileContent)
            return true
        }
        logger.log("Cannot send file: Not connected to ${device.name}")
        return false
    }

    fun cleanup() {
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
    }
}