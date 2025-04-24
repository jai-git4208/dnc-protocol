package com.ivelosi.dncprotocol.network

/**
 * (c) Ivelosi Technologies. All rights reserved.
 */

data class ChatMessage(
    val senderId: String,
    val content: String,
    val timestamp: Long
)