package com.ivelosi.dnc.signal

import android.content.Context
import android.os.Environment
import android.util.Base64
import com.ivelosi.dnc.network.BluetoothDeviceInfo
import com.ivelosi.dnc.network.NetworkLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.ivelosi.dnc.notification.DNCNotificationManager

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 */

/**
 * Handles receiving and assembling files sent through the network
 */
class FileReceiver(
    private val context: Context,
    private val logger: NetworkLogger,
    private val coroutineScope: CoroutineScope
) {
    // Store file transfer state
    private val fileTransfers = mutableMapOf<String, FileTransferState>()
    
    // Add notification manager
    private val notificationManager = DNCNotificationManager(context)

    /**
     * Process a file-related message
     */
    fun processMessage(from: BluetoothDeviceInfo, type: String, payload: String): Boolean {
        return when (type) {
            MessageProtocol.TYPE_FILE_START -> {
                handleFileStart(from, payload)
                true
            }
            MessageProtocol.TYPE_FILE_CHUNK -> {
                handleFileChunk(from, payload)
                true
            }
            MessageProtocol.TYPE_FILE_END -> {
                handleFileEnd(from, payload)
                true
            }
            else -> false // Not a file-related message
        }
    }

    /**
     * Handle file start message
     */
    private fun handleFileStart(from: BluetoothDeviceInfo, payload: String) {
        try {
            val parts = payload.split("|", limit = 2)
            if (parts.size != 2) {
                logger.log("Invalid file start message format")
                return
            }

            val fileName = parts[0]
            val fileSize = parts[1].toLongOrNull() ?: 0L

            // Create a unique transfer ID combining device address and filename
            val transferId = "${from.address}_$fileName"

            // Create new file transfer state
            val transferState = FileTransferState(fileName, fileSize)
            fileTransfers[transferId] = transferState

            logger.log("Starting file transfer: $fileName (${formatFileSize(fileSize)}) from ${from.name}")
            
            // Send notification about file transfer starting
            notificationManager.showDiscoveryNotification(
                "Starting file transfer: $fileName (${formatFileSize(fileSize)}) from ${from.name}"
            )
        } catch (e: Exception) {
            logger.log("Error processing file start: ${e.message}")
        }
    }

    /**
     * Handle file chunk message
     */
    private fun handleFileChunk(from: BluetoothDeviceInfo, payload: String) {
        try {
            val parts = payload.split("|", limit = 3)
            if (parts.size != 3) {
                logger.log("Invalid file chunk message format")
                return
            }

            val offset = parts[0].toLongOrNull() ?: 0L
            val chunkSize = parts[1].toIntOrNull() ?: 0
            val base64Data = parts[2]

            // Get all active transfers for this device
            val transferId = fileTransfers.keys.find { it.startsWith(from.address) }
            if (transferId == null) {
                logger.log("No active file transfer from ${from.name}")
                return
            }

            val transferState = fileTransfers[transferId] ?: return

            // Decode and store the chunk
            val data = Base64.decode(base64Data, Base64.DEFAULT)
            transferState.addChunk(offset, data)

            // Log progress periodically
            val progress = (transferState.receivedBytes.toFloat() / transferState.fileSize) * 100
            if (progress % 25 < 1 || transferState.receivedBytes == transferState.fileSize) {
                logger.log("File transfer progress: ${progress.toInt()}% (${formatFileSize(transferState.receivedBytes)}/${formatFileSize(transferState.fileSize)})")
                
                // Only update notification at significant progress points (25%, 50%, 75%, 100%)
                if (progress % 25 < 1) {
                    notificationManager.showDiscoveryNotification(
                        "File transfer from ${from.name}: ${progress.toInt()}% complete"
                    )
                }
            }
        } catch (e: Exception) {
            logger.log("Error processing file chunk: ${e.message}")
        }
    }

    /**
     * Handle file end message
     */
    private fun handleFileEnd(from: BluetoothDeviceInfo, fileName: String) {
        try {
            // Get the transfer state
            val transferId = "${from.address}_$fileName"
            val transferState = fileTransfers[transferId] ?: run {
                logger.log("No active transfer found for $fileName")
                return
            }

            // Save the complete file
            coroutineScope.launch {
                try {
                    val savedFilePath = saveReceivedFile(from, transferState)
                    withContext(Dispatchers.Main) {
                        logger.log("File transfer complete: ${transferState.fileName} from ${from.name}")
                        
                        // Send notification about completed file with path
                        notificationManager.showDiscoveryNotification(
                            "File received: ${transferState.fileName} from ${from.name}"
                        )
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        logger.log("Error saving file: ${e.message}")
                        
                        // Notify about error
                        notificationManager.showDiscoveryNotification(
                            "Error saving file from ${from.name}: ${e.message}"
                        )
                    }
                } finally {
                    // Clean up the transfer state
                    fileTransfers.remove(transferId)
                }
            }
        } catch (e: Exception) {
            logger.log("Error processing file end: ${e.message}")
        }
    }

    /**
     * Save the received file to storage
     */
    private suspend fun saveReceivedFile(from: BluetoothDeviceInfo, transferState: FileTransferState): String {
        return withContext(Dispatchers.IO) {
            try {
                // Create downloads directory if it doesn't exist
                val downloadsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "DNCTransfers"
                ).apply {
                    if (!exists()) mkdirs()
                }

                // Create a file with unique name
                val timeStamp = System.currentTimeMillis()
                val fileName = transferState.fileName
                val extension = fileName.substringAfterLast('.', "")
                val baseName = fileName.substringBeforeLast('.', fileName)
                val uniqueFileName = "${baseName}_${timeStamp}.$extension"

                val outputFile = File(downloadsDir, uniqueFileName)

                // Write the combined chunks to the file
                FileOutputStream(outputFile).use { outputStream ->
                    transferState.chunks.toSortedMap().forEach { (_, chunk) ->
                        outputStream.write(chunk)
                    }
                }

                logger.log("File saved to: ${outputFile.absolutePath}")
                outputFile.absolutePath
            } catch (e: Exception) {
                logger.log("Error saving file: ${e.message}")
                throw e
            }
        }
    }

    /**
     * Format file size in human-readable form
     */
    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }

    /**
     * State for tracking file transfer progress
     */
    private inner class FileTransferState(
        val fileName: String,
        val fileSize: Long
    ) {
        val chunks = mutableMapOf<Long, ByteArray>()
        var receivedBytes: Long = 0

        fun addChunk(offset: Long, data: ByteArray) {
            chunks[offset] = data
            receivedBytes += data.size
        }
    }
}