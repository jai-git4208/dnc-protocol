package com.ivelosi.dnc.signal

import android.content.Context
import android.util.Base64
import com.ivelosi.dnc.network.BluetoothDeviceInfo
import com.ivelosi.dnc.network.NetworkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import com.ivelosi.dnc.notification.DNCNotificationManager
import android.net.wifi.WifiManager

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 */

/**
 * Handles communication over an established socket connection
 */
class SocketCommunicator(
    public val deviceInfo: BluetoothDeviceInfo,
    private val socket: Socket,
    private val logger: NetworkLogger,
    private val scope: CoroutineScope,
    private val onMessageReceived: (BluetoothDeviceInfo, String, String) -> Unit,
    private val onDisconnect: (BluetoothDeviceInfo) -> Unit,
    private val context: Context
) {
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val messageQueue = ConcurrentLinkedQueue<String>()
    private var isRunning = true

    // Add notification manager
    private val notificationManager = DNCNotificationManager(context)

    init {
        try {
            writer = PrintWriter(socket.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            startReceiving()
            startSending()
            
            // Send notification when connection is initialized
            notificationManager.showDiscoveryNotification("Connected to ${deviceInfo.name}")
        } catch (e: Exception) {
            logger.log("Error initializing communicator for ${deviceInfo.name}: ${e.message}")
            notificationManager.showDiscoveryNotification("Failed to connect to ${deviceInfo.name}: ${e.message}")
            close()
        }
    }

    /**
     * Queues a message to be sent to the device
     */
    fun sendMessage(type: String, payload: String) {
        val message = MessageProtocol.createMessage(type, payload)
        messageQueue.add(message)
        logger.log("Queued message to ${deviceInfo.name}: $type")
        
        // Only notify for significant message types
        when (type) {
            MessageProtocol.TYPE_FILE_START -> {
                val parts = payload.split("|", limit = 2)
                if (parts.size == 2) {
                    val fileName = parts[0]
                    notificationManager.showDiscoveryNotification(
                        "Started sending file: $fileName to ${deviceInfo.name}"
                    )
                }
            }
            MessageProtocol.TYPE_FILE_END -> {
                notificationManager.showDiscoveryNotification(
                    "Completed sending file to ${deviceInfo.name}"
                )
            }
        }
    }

    /**
     * Sends a text message to the device
     */
    fun sendTextMessage(text: String) {
        sendMessage(MessageProtocol.TYPE_TEXT, text)
    }

    /**
     * Sends a command to the device
     */
    fun sendCommand(command: String, args: String = "") {
        val payload = if (args.isEmpty()) command else "$command:$args"
        sendMessage(MessageProtocol.TYPE_COMMAND, payload)
        
        // Notify for important commands
        when (command) {
            MessageProtocol.CMD_RESTART, MessageProtocol.CMD_SHUTDOWN -> {
                notificationManager.showDiscoveryNotification(
                    "Sent $command command to ${deviceInfo.name}"
                )
            }
        }
    }

    /**
     * Starts a coroutine to handle receiving messages
     */
    private fun startReceiving() {
        scope.launch(Dispatchers.IO) {
            try {
                while (isRunning && !socket.isClosed && reader != null) {
                    val line = reader?.readLine()
                    if (line != null) {
                        processReceivedMessage(line)
                    } else {
                        // End of stream
                        logger.log("Connection closed by ${deviceInfo.name}")
                        break
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    logger.log("Error receiving from ${deviceInfo.name}: ${e.message}")
                }
            } finally {
                close()
            }
        }
    }

    /**
     * Starts a coroutine to handle sending queued messages
     */
    private fun startSending() {
        scope.launch(Dispatchers.IO) {
            try {
                while (isRunning && !socket.isClosed && writer != null) {
                    val message = messageQueue.poll()
                    if (message != null) {
                        writer?.println(message)
                        logger.log("Sent to ${deviceInfo.name}: $message")
                    } else {
                        // Nothing to send, wait a bit
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    logger.log("Error sending to ${deviceInfo.name}: ${e.message}")
                }
            } finally {
                close()
            }
        }
    }

    /**
     * Processes a received message according to the protocol
     */
    private fun processReceivedMessage(rawMessage: String) {
        val parsedMessage = MessageProtocol.parseMessage(rawMessage)
        if (parsedMessage != null) {
            val (type, timestamp, payload) = parsedMessage
            logger.log("Received from ${deviceInfo.name}: [$type] $payload")

            // Handle system messages internally
            when (type) {
                MessageProtocol.TYPE_HEARTBEAT -> {
                    // Just log it, no need to notify
                    return
                }
                MessageProtocol.TYPE_HANDSHAKE -> {
                    // Send handshake response if needed
                    sendMessage(MessageProtocol.TYPE_HANDSHAKE, "ACCEPTED")
                    
                    // After handshake, broadcast our IP address
                    try {
                        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val wifiInfo = com.ivelosi.dnc.network.WifiUtils.getWifiInfo(context, wifiManager)
                        val localIP = com.ivelosi.dnc.network.WifiUtils.extractIpAddress(context, wifiInfo, wifiManager, null)
                        
                        if (localIP.isNotEmpty()) {
                            // Send our IP address to the newly connected device
                            sendMessage(MessageProtocol.TYPE_IP_BROADCAST, localIP)
                            logger.log("Sent our IP address ($localIP) to ${deviceInfo.name}")
                        }
                    } catch (e: Exception) {
                        logger.log("Error broadcasting IP during handshake: ${e.message}")
                    }
                    
                    notificationManager.showDiscoveryNotification(
                        "Connection handshake completed with ${deviceInfo.name}"
                    )
                }
                MessageProtocol.TYPE_TEXT -> {
                    // Show notification for text messages
                    notificationManager.showMessageNotification(deviceInfo.name, payload)
                }
                MessageProtocol.TYPE_FILE_START -> {
                    // Show notification for file transfer beginning
                    val parts = payload.split("|", limit = 2)
                    if (parts.size == 2) {
                        val fileName = parts[0]
                        notificationManager.showDiscoveryNotification(
                            "Receiving file: $fileName from ${deviceInfo.name}"
                        )
                    }
                }
                MessageProtocol.TYPE_FILE_END -> {
                    // Show notification for file transfer completion
                    notificationManager.showDiscoveryNotification(
                        "File transfer complete from ${deviceInfo.name}"
                    )
                }
                MessageProtocol.TYPE_IP_BROADCAST -> {
                    // Handle IP address broadcast - this will be processed by NetworkManager
                    logger.log("Received IP broadcast from ${deviceInfo.name}: $payload")
                }
            }

            // Notify handlers about the message
            scope.launch(Dispatchers.Main) {
                onMessageReceived(deviceInfo, type, payload)
            }
        } else {
            // Not conforming to our protocol, but still log it
            logger.log("Received non-protocol message from ${deviceInfo.name}: $rawMessage")
        }
    }

    /**
     * Closes the connection and resources
     */
    fun close() {
        if (!isRunning) return

        isRunning = false
        try {
            writer?.close()
            reader?.close()
            if (!socket.isClosed) {
                socket.close()
            }
            
            // Notify about disconnection
            notificationManager.showDiscoveryNotification("Disconnected from ${deviceInfo.name}")
        } catch (e: Exception) {
            logger.log("Error closing communication with ${deviceInfo.name}: ${e.message}")
        }

        scope.launch(Dispatchers.Main) {
            onDisconnect(deviceInfo)
        }
    }

    /**
     * Helper method to send a file in chunks
     * @param fileName The name of the file
     * @param fileContent The byte array of the file content
     * @param chunkSize The size of each chunk in bytes
     */
    fun sendFile(fileName: String, fileContent: ByteArray, chunkSize: Int = 8192) {
        scope.launch(Dispatchers.IO) {
            try {
                // Send file start message
                val fileSize = fileContent.size
                val fileInfo = "$fileName|$fileSize"
                sendMessage(MessageProtocol.TYPE_FILE_START, fileInfo)

                // Send file in chunks
                var offset = 0
                while (offset < fileSize) {
                    val currentChunkSize = minOf(chunkSize, fileSize - offset)
                    val chunk = fileContent.copyOfRange(offset, offset + currentChunkSize)
                    val base64Chunk = Base64.encodeToString(chunk, Base64.DEFAULT)

                    sendMessage(MessageProtocol.TYPE_FILE_CHUNK, "$offset|$currentChunkSize|$base64Chunk")
                    offset += currentChunkSize

                    // Small delay to prevent flooding
                    delay(50)
                }

                // Send file end message
                sendMessage(MessageProtocol.TYPE_FILE_END, fileName)
                logger.log("File '$fileName' sent successfully to ${deviceInfo.name}")
            } catch (e: Exception) {
                logger.log("Error sending file to ${deviceInfo.name}: ${e.message}")
                sendMessage(MessageProtocol.TYPE_ERROR, "File transfer failed: ${e.message}")
            }
        }
    }

    /**
     * Get current network information for diagnostics
     */
    fun getNetworkInfo(): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = com.ivelosi.dnc.network.WifiUtils.getWifiInfo(context, wifiManager)
        val localIP = com.ivelosi.dnc.network.WifiUtils.extractIpAddress(context, wifiInfo, wifiManager, null)
        
        return "Socket connection to ${deviceInfo.name}\n" +
                "Local IP: $localIP\n" +
                "Remote IP: ${deviceInfo.ipAddress}\n" +
                "Connected: ${socket?.isConnected}\n" +
                "Socket closed: ${socket?.isClosed}\n"
    }
}