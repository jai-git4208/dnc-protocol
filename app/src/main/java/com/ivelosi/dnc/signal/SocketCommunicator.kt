package com.ivelosi.dnc.signal

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

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 */

/**
 * Handles communication over an established socket connection
 */
class SocketCommunicator(
    private val deviceInfo: BluetoothDeviceInfo,
    private val socket: Socket,
    private val logger: NetworkLogger,
    private val scope: CoroutineScope,
    private val onMessageReceived: (BluetoothDeviceInfo, String, String) -> Unit,
    private val onDisconnect: (BluetoothDeviceInfo) -> Unit
) {
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val messageQueue = ConcurrentLinkedQueue<String>()
    private var isRunning = true

    init {
        try {
            writer = PrintWriter(socket.getOutputStream(), true)
            reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            startReceiving()
            startSending()
        } catch (e: Exception) {
            logger.log("Error initializing communicator for ${deviceInfo.name}: ${e.message}")
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
}