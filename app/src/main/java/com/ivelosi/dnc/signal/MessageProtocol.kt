package com.ivelosi.dnc.signal

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 */

/**
 * Protocol definition for messages exchanged between devices
 */
object MessageProtocol {
    // Message types
    const val TYPE_HANDSHAKE = "HANDSHAKE"
    const val TYPE_HEARTBEAT = "HEARTBEAT"
    const val TYPE_TEXT = "TEXT"
    const val TYPE_COMMAND = "COMMAND"
    const val TYPE_FILE_START = "FILE_START"
    const val TYPE_FILE_CHUNK = "FILE_CHUNK"
    const val TYPE_FILE_END = "FILE_END"
    const val TYPE_STATUS = "STATUS"
    const val TYPE_ERROR = "ERROR"

    // Common commands
    const val CMD_PING = "PING"
    const val CMD_SHUTDOWN = "SHUTDOWN"
    const val CMD_RESTART = "RESTART"
    const val CMD_GET_INFO = "GET_INFO"

    /**
     * Creates a formatted message according to the protocol
     * Format: TYPE|TIMESTAMP|PAYLOAD
     */
    fun createMessage(type: String, payload: String): String {
        val timestamp = System.currentTimeMillis()
        return "$type|$timestamp|$payload"
    }

    /**
     * Parses a message string into its components
     * @return Triple of (type, timestamp, payload)
     */
    fun parseMessage(message: String): Triple<String, Long, String>? {
        try {
            val parts = message.split("|", limit = 3)
            if (parts.size == 3) {
                val type = parts[0]
                val timestamp = parts[1].toLongOrNull() ?: 0L
                val payload = parts[2]
                return Triple(type, timestamp, payload)
            }
        } catch (e: Exception) {
            // Message doesn't conform to protocol
        }
        return null
    }
}