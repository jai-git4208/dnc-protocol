package com.ivelosi.dnc.ui

/**
 * (c) Ivelosi Technologies. All Rights Reserved.
 */

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.ivelosi.dnc.R
import com.ivelosi.dnc.network.BluetoothDeviceInfo
import com.ivelosi.dnc.signal.FileReceiver
import com.ivelosi.dnc.signal.MessageProtocol
import com.ivelosi.dnc.network.NetworkLogger
import com.ivelosi.dnc.network.NetworkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Handles UI for sending messages and files to connected devices
 */
class MessageHandlingUI(
    private val activity: Activity,
    private val networkManager: NetworkManager,
    private val logger: NetworkLogger,
    private val coroutineScope: CoroutineScope

) {
    private lateinit var messageEditText: EditText
    private lateinit var sendMessageButton: Button
    private lateinit var sendFileButton: Button
    private lateinit var sendCommandButton: Button
    private lateinit var messageContainer: LinearLayout
    private lateinit var fileReceiver: FileReceiver

    private var selectedDevice: BluetoothDeviceInfo? = null
    private val FILE_PICK_REQUEST_CODE = 123

    /**
     * Initialize UI elements
     */
    fun initialize() {
        try {
            // First get the included layout from the activity
            val messageLayout = activity.findViewById<View>(R.id.messageLayout)
            if (messageLayout == null) {
                logger.log("ERROR: messageLayout view not found")
                Toast.makeText(activity, "Error initializing message UI", Toast.LENGTH_SHORT).show()
                
                // Create an emergency fallback container
                val fallbackContainer = LinearLayout(activity)
                fallbackContainer.orientation = LinearLayout.VERTICAL
                fallbackContainer.visibility = View.GONE
                messageContainer = fallbackContainer
                
                // Add the fallback container to activity's root view
                val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
                rootView?.addView(fallbackContainer)
                
                logger.log("Created fallback message container")
                return
            }

            // Try to find messageContainer within the included layout first
            val containerInLayout = messageLayout.findViewById<LinearLayout>(R.id.messageContainer)
            
            // Next, try to find it directly in the activity
            val containerInActivity = activity.findViewById<LinearLayout>(R.id.messageContainer)
            
            // Finally, check if the included layout itself is the messageContainer
            // (since the message_layout.xml has id="messageContainer" on its root element)
            val layoutAsContainer = if (messageLayout is LinearLayout) messageLayout else null
            
            // Assign the first non-null container we find
            when {
                containerInLayout != null -> {
                    logger.log("Found messageContainer in messageLayout")
                    messageContainer = containerInLayout
                }
                containerInActivity != null -> {
                    logger.log("Found messageContainer directly in activity")
                    messageContainer = containerInActivity
                }
                layoutAsContainer != null -> {
                    logger.log("Using messageLayout itself as container")
                    messageContainer = layoutAsContainer
                }
                else -> {
                    logger.log("ERROR: messageContainer view not found in any layout")
                    Toast.makeText(activity, "Error initializing message UI components", Toast.LENGTH_SHORT).show()
                    
                    // Create an emergency fallback container
                    val fallbackContainer = LinearLayout(activity)
                    fallbackContainer.orientation = LinearLayout.VERTICAL
                    fallbackContainer.visibility = View.GONE
                    messageContainer = fallbackContainer
                    
                    // Add the fallback container to activity's root view
                    val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
                    rootView?.addView(fallbackContainer)
                    
                    logger.log("Created fallback message container")
                }
            }

            // Now find all other views within the messageContainer or activity layout
            messageEditText = activity.findViewById(R.id.messageEditText) 
                ?: messageLayout.findViewById(R.id.messageEditText)
                ?: throw Exception("messageEditText not found")
                
            sendMessageButton = activity.findViewById(R.id.sendMessageButton)
                ?: messageLayout.findViewById(R.id.sendMessageButton) 
                ?: throw Exception("sendMessageButton not found")
                
            sendFileButton = activity.findViewById(R.id.sendFileButton)
                ?: messageLayout.findViewById(R.id.sendFileButton)
                ?: throw Exception("sendFileButton not found")
                
            sendCommandButton = activity.findViewById(R.id.sendCommandButton)
                ?: messageLayout.findViewById(R.id.sendCommandButton)
                ?: throw Exception("sendCommandButton not found")

            // Set click listeners
            sendMessageButton.setOnClickListener {
                sendTextMessage()
            }

            sendFileButton.setOnClickListener {
                openFilePicker()
            }

            sendCommandButton.setOnClickListener {
                showCommandDialog()
            }

            // Setup message receiver
            networkManager.setOnMessageReceivedListener { device, type, payload ->
                handleReceivedMessage(device, type, payload)
            }

            // Initialize the File Receiver
            fileReceiver = FileReceiver(activity, logger, coroutineScope)

            // Initially hide the message container
            messageContainer.visibility = View.GONE

            logger.log("MessageHandlingUI initialized successfully")
        } catch (e: Exception) {
            logger.log("Error initializing MessageHandlingUI: ${e.message}")
            e.printStackTrace()
            
            // Create a fallback UI as last resort
            try {
                val fallbackContainer = LinearLayout(activity)
                fallbackContainer.orientation = LinearLayout.VERTICAL
                fallbackContainer.visibility = View.GONE
                messageContainer = fallbackContainer
                
                // Add the fallback container to activity's root view
                val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
                rootView?.addView(fallbackContainer)
                
                logger.log("Created emergency fallback message container after exception")
            } catch (e2: Exception) {
                // We've done all we can
                logger.log("CRITICAL: Even fallback container creation failed: ${e2.message}")
            }
            
            // Create a fallback UI or notify the user
            Toast.makeText(activity, "Error initializing UI: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Set the currently selected device
     */
    fun setSelectedDevice(device: BluetoothDeviceInfo?) {
        selectedDevice = device

        // Show/hide message container based on selection
        messageContainer.visibility = if (device != null && device.isConnected) View.VISIBLE else View.GONE
    }

    /**
     * Send a text message to the selected device
     */
    private fun sendTextMessage() {
        val device = selectedDevice ?: return
        val message = messageEditText.text.toString().trim()

        if (message.isEmpty()) {
            Toast.makeText(activity, "Please enter a message", Toast.LENGTH_SHORT).show()
            return
        }

        if (networkManager.sendTextMessage(device, message)) {
            // Clear the input field
            messageEditText.setText("")
            Toast.makeText(activity, "Message sent", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(activity, "Failed to send message", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Open file picker to select a file to send
     */
    private fun openFilePicker() {
        val device = selectedDevice ?: return

        if (!device.isConnected) {
            Toast.makeText(activity, "Device not connected", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*" // All file types
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        try {
            activity.startActivityForResult(
                Intent.createChooser(intent, "Select a file to send"),
                FILE_PICK_REQUEST_CODE
            )
        } catch (e: Exception) {
            Toast.makeText(activity, "No file picker available", Toast.LENGTH_SHORT).show()
            logger.log("Error opening file picker: ${e.message}")
        }
    }

    /**
     * Handle file selection result
     */
    fun handleFilePickerResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_PICK_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                sendSelectedFile(uri)
            }
        }
    }

    /**
     * Send the selected file to the device
     */
    private fun sendSelectedFile(uri: Uri) {
        val device = selectedDevice ?: return

        coroutineScope.launch {
            try {
                val fileName = getFileName(uri)
                val fileContent = readFileBytes(uri)

                if (fileContent != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Sending file: $fileName", Toast.LENGTH_SHORT).show()
                    }

                    networkManager.sendFile(device, fileName, fileContent)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "Failed to read file", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                logger.log("Error sending file: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Error sending file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Get file name from Uri
     */
    private fun getFileName(uri: Uri): String {
        val cursor = activity.contentResolver.query(uri, null, null, null, null)

        cursor?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex("_display_name")
                if (displayNameIndex != -1) {
                    return it.getString(displayNameIndex)
                }
            }
        }

        // Fallback to last path segment or timestamp
        val lastPathSegment = uri.lastPathSegment
        return lastPathSegment ?: "file_${System.currentTimeMillis()}"
    }

    /**
     * Read file content as byte array
     */
    private fun readFileBytes(uri: Uri): ByteArray? {
        return try {
            activity.contentResolver.openInputStream(uri)?.use { inputStream ->
                readInputStream(inputStream)
            }
        } catch (e: Exception) {
            logger.log("Error reading file: ${e.message}")
            null
        }
    }

    /**
     * Read input stream to byte array
     */
    private fun readInputStream(inputStream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val data = ByteArray(16384)
        var bytesRead: Int

        while (inputStream.read(data, 0, data.size).also { bytesRead = it } != -1) {
            buffer.write(data, 0, bytesRead)
        }

        return buffer.toByteArray()
    }

    /**
     * Show command selection dialog
     */
    private fun showCommandDialog() {
        val device = selectedDevice ?: return

        if (!device.isConnected) {
            Toast.makeText(activity, "Device not connected", Toast.LENGTH_SHORT).show()
            return
        }

        val commands = arrayOf(
            MessageProtocol.CMD_PING,
            MessageProtocol.CMD_GET_INFO,
            MessageProtocol.CMD_RESTART,
            MessageProtocol.CMD_SHUTDOWN,
            "Custom Command..."
        )

        AlertDialog.Builder(activity)
            .setTitle("Send Command")
            .setItems(commands) { dialog, which ->
                when {
                    which < commands.size - 1 -> {
                        // Send predefined command
                        val command = commands[which]
                        networkManager.sendCommand(device, command)
                        Toast.makeText(activity, "Command sent: $command", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Show dialog for custom command
                        showCustomCommandDialog()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show dialog for entering a custom command
     */
    private fun showCustomCommandDialog() {
        val device = selectedDevice ?: return

        val inputLayout = LinearLayout(activity)
        inputLayout.orientation = LinearLayout.VERTICAL
        inputLayout.setPadding(50, 30, 50, 30)

        val commandInput = EditText(activity)
        commandInput.hint = "Command"

        val argsInput = EditText(activity)
        argsInput.hint = "Arguments (optional)"

        inputLayout.addView(commandInput)
        inputLayout.addView(argsInput)

        AlertDialog.Builder(activity)
            .setTitle("Custom Command")
            .setView(inputLayout)
            .setPositiveButton("Send") { dialog, which ->
                val command = commandInput.text.toString().trim()
                val args = argsInput.text.toString().trim()

                if (command.isNotEmpty()) {
                    networkManager.sendCommand(device, command, args)
                    Toast.makeText(activity, "Custom command sent", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Command cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Get the app version
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            packageInfo.versionName
        } catch (e: Exception) {
            "unknown"
        }.toString()
    }

    private fun handleCommand(device: BluetoothDeviceInfo, payload: String) {
        val parts = payload.split(":", limit = 2)
        val command = parts[0]
        val args = if (parts.size > 1) parts[1] else ""

        // Log the command
        logger.log("Command from ${device.name}: $command $args")

        // Process common commands
        when (command) {
            MessageProtocol.CMD_PING -> {
                // Send a response back
                networkManager.sendCommand(device, "PONG")
                Toast.makeText(activity, "${device.name} pinged you", Toast.LENGTH_SHORT).show()
            }
            MessageProtocol.CMD_GET_INFO -> {
                // Send device information
                val deviceInfo = "Model: ${android.os.Build.MODEL}\n" +
                        "Android: ${android.os.Build.VERSION.RELEASE}\n" +
                        "App: ${activity.packageName} v${getAppVersion()}"
                networkManager.sendCommand(device, "DEVICE_INFO", deviceInfo)
                Toast.makeText(activity, "${device.name} requested device info", Toast.LENGTH_SHORT).show()
            }
            else -> {
                // Just show a toast for other commands
                Toast.makeText(
                    activity,
                    "Command from ${device.name}: $command",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Handle received message
     */
    private fun handleReceivedMessage(device: BluetoothDeviceInfo, type: String, payload: String) {
        // Check if it's a file-related message
        if (fileReceiver.processMessage(device, type, payload)) {
            // Already processed by file receiver
            return
        }

        // Log the message
        logger.log("Message from ${device.name}: [$type] $payload")

        // Handle message based on type
        when (type) {
            MessageProtocol.TYPE_TEXT -> {
                Toast.makeText(activity, "${device.name}: $payload", Toast.LENGTH_LONG).show()
            }
            MessageProtocol.TYPE_COMMAND -> {
                handleCommand(device, payload)
            }
            MessageProtocol.TYPE_ERROR -> {
                Toast.makeText(
                    activity,
                    "Error from ${device.name}: $payload",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

}