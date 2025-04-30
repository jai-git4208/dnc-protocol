package com.ivelosi.dnc.network

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 */

import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.regex.Pattern

object WifiUtils {
    private const val TAG = "DNCWifiUtils"
    
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
        try {
            // First try to extract from WifiInfo string
            val ipPattern = Pattern.compile("IP: (\\d+\\.\\d+\\.\\d+\\.\\d+)")
            val matcher = ipPattern.matcher(wifiInfo)
            
            if (matcher.find()) {
                val extractedIp = matcher.group(1) ?: ""
                if (isValidLocalAddress(extractedIp)) {
                    Log.d(TAG, "Using IP from WiFi info: $extractedIp")
                    return extractedIp
                }
            }
            
            // Next, try to get from WifiManager directly
            try {
                val wifiInfo = wifiManager.connectionInfo
                val ipFromWifi = intToIpAddress(wifiInfo.ipAddress)
                if (isValidLocalAddress(ipFromWifi)) {
                    Log.d(TAG, "Using IP from WifiManager: $ipFromWifi")
                    return ipFromWifi
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting IP from WifiManager: ${e.message}")
            }
            
            // As a fallback, try to get any valid local IP address from all network interfaces
            // This helps when using mobile hotspot or other connection types
            try {
                val localIp = getLocalIpAddress()
                if (localIp.isNotEmpty()) {
                    Log.d(TAG, "Using IP from network interfaces: $localIp")
                    return localIp
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting IP from network interfaces: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting IP address: ${e.message}")
        }
        
        // If all methods fail, return empty string
        return ""
    }

    /**
     * Convert integer IP address to string format
     */
    fun intToIpAddress(ipInt: Int): String {
        return "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
    }
    
    /**
     * Get local IP address from all available network interfaces
     */
    private fun getLocalIpAddress(): String {
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                
                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    
                    // Skip IPv6 and loopback addresses
                    if (!address.isLoopbackAddress && address is InetAddress) {
                        val hostAddress = address.hostAddress ?: ""
                        if (isValidLocalAddress(hostAddress)) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address: ${e.message}")
        }
        return ""
    }
    
    /**
     * Check if an IP address is a valid local address (not loopback or special address)
     */
    private fun isValidLocalAddress(ip: String): Boolean {
        if (ip.isEmpty() || ip == "0.0.0.0") {
            return false
        }
        
        // Check for standard IP format
        val ipPattern = Pattern.compile("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
        if (!ipPattern.matcher(ip).matches()) {
            return false
        }
        
        // Don't use loopback addresses
        if (ip.startsWith("127.")) {
            return false
        }
        
        return true
    }
}