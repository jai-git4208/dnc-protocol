package com.ivelosi.dncprotocol.ui

/**
 * (c) Ivelosi Technologies. All rights reserved.
 */

data class MessageItem(
    val id: String,
    val content: String,
    val timestamp: String,
    val isOutgoing: Boolean
)