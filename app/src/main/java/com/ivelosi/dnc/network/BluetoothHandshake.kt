package com.ivelosi.dnc.network

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.ivelosi.dnc.signal.DNCPrefixValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Handles the handshake process with Bluetooth devices to retrieve their network information
 * This allows directly getting the IP address from the device rather than guessing
 */
class BluetoothHandshake(
    private val context: Context,
    private val logger: NetworkLogger
) {
    companion object {
        private const val TAG = "BluetoothHandshake"
        
        // Standard Serial Port Profile UUID
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        
        // Handshake protocol commands
        const val CMD_REQUEST_NETWORK_INFO = "DNC:REQ_NET_INFO"
        const val CMD_RESPONSE_PREFIX = "DNC:NET_INFO:"
        
        // Timeout for handshake operations in milliseconds
        private const val HANDSHAKE_TIMEOUT = 3000L
    }
    
    /**
     * Perform a handshake with the device to get its network information
     * Returns the IP address of the remote device or null if unavailable
     */
    suspend fun getRemoteDeviceIpAddress(device: BluetoothDevice): String? = withContext(Dispatchers.IO) {
        var socket: BluetoothSocket? = null
        
        try {
            logger.log("Initiating handshake with device: ${device.name ?: "Unknown"}")
            
            // Create socket and connect
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            
            // Get streams
            val inputStream = socket.inputStream
            val outputStream = socket.outputStream
            
            // Send network info request
            sendCommand(outputStream, CMD_REQUEST_NETWORK_INFO)
            
            // Read response with timeout
            val response = readResponseWithTimeout(inputStream)
            
            if (response.startsWith(CMD_RESPONSE_PREFIX)) {
                // Parse the response which should be in format: DNC:NET_INFO:192.168.1.100
                val ipAddress = response.substring(CMD_RESPONSE_PREFIX.length).trim()
                logger.log("Received IP address from device: $ipAddress")
                return@withContext ipAddress
            }
            
            return@withContext null
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed: ${e.message}")
            logger.log("Handshake failed: ${e.message}")
            return@withContext null
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    /**
     * Process incoming handshake requests when we are the server
     * This should be called by the socket server when a connection is established
     */
    fun handleIncomingHandshake(inputStream: InputStream, outputStream: OutputStream) {
        try {
            val buffer = ByteArray(1024)
            val bytesRead = inputStream.read(buffer)
            
            if (bytesRead > 0) {
                val message = String(buffer, 0, bytesRead)
                
                if (message.trim() == CMD_REQUEST_NETWORK_INFO) {
                    // Get our own IP address to send back
                    val deviceNetworkInfo = DeviceNetworkInfo.getAllIPAddresses().firstOrNull() ?: "0.0.0.0"
                    val response = "$CMD_RESPONSE_PREFIX$deviceNetworkInfo"
                    
                    // Send our IP address back
                    sendCommand(outputStream, response)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming handshake: ${e.message}")
        }
    }
    
    /**
     * Send a command string to the output stream
     */
    private fun sendCommand(outputStream: OutputStream, command: String) {
        outputStream.write(command.toByteArray())
        outputStream.flush()
    }
    
    /**
     * Read a response with a timeout
     */
    private fun readResponseWithTimeout(inputStream: InputStream): String {
        val buffer = ByteArray(1024)
        val startTime = System.currentTimeMillis()
        
        while (System.currentTimeMillis() - startTime < HANDSHAKE_TIMEOUT) {
            if (inputStream.available() > 0) {
                val bytesRead = inputStream.read(buffer)
                return String(buffer, 0, bytesRead)
            }
            
            Thread.sleep(100) // Small delay to avoid tight loop
        }
        
        return "" // Return empty string if timeout
    }
} 