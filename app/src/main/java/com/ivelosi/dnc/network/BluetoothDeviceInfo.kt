package com.ivelosi.dnc.network

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 */

// Data class to hold device information
data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val wifiInfo: String,
    val ipAddress: String = "",
    var connectionStatus: String = "Not connected",
    var isConnected: Boolean = false,
    val rssi: Int = -100  // Signal strength (RSSI) with default value
)