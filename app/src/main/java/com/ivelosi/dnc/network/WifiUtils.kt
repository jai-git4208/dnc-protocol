package com.ivelosi.dnc.network

import android.net.wifi.WifiManager
import java.util.regex.Pattern

object WifiUtils {
    fun getWifiInfo(wifiManager: WifiManager): String {
        return try {
            if (wifiManager.isWifiEnabled) {
                val connectionInfo = wifiManager.connectionInfo
                val ssid = connectionInfo.ssid.replace("\"", "")
                val ip = intToIpAddress(connectionInfo.ipAddress)

                // Note: Getting WiFi password programmatically is not possible without root access
                // or system app privileges due to security restrictions

                "SSID: $ssid\nIP: $ip\nPassword: Not available (security restriction)"
            } else {
                "WiFi is disabled"
            }
        } catch (e: Exception) {
            "WiFi info unavailable: ${e.message}"
        }
    }

    fun extractIpAddress(wifiInfo: String, wifiManager: WifiManager): String {
        // Extract IP address from WiFi info string
        val ipPattern = Pattern.compile("IP: (\\d+\\.\\d+\\.\\d+\\.\\d+)")
        val matcher = ipPattern.matcher(wifiInfo)
        return if (matcher.find()) {
            matcher.group(1) ?: ""
        } else {
            // If can't extract, use the device's current IP as fallback
            try {
                val wifiInfo = wifiManager.connectionInfo
                intToIpAddress(wifiInfo.ipAddress)
            } catch (e: Exception) {
                ""
            }
        }
    }

    fun intToIpAddress(ipInt: Int): String {
        return "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
    }
}