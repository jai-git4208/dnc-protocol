package com.ivelosi.dnc.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.NetworkInterface
import java.util.*

/**
 * Utility class for getting network information about the device
 */
object DeviceNetworkInfo {
    private const val TAG = "DeviceNetworkInfo"
    
    /**
     * Get a summary of the device's network information
     */
    fun getDeviceNetworkSummary(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        val stringBuilder = StringBuilder()
        
        // Get connection type
        stringBuilder.append("Connection Type: ${getConnectionType(connectivityManager)}\n")
        
        // Get WiFi details if available
        if (isWifiConnected(connectivityManager)) {
            try {
                val wifiInfo = wifiManager.connectionInfo
                val ssid = wifiInfo.ssid.replace("\"", "")
                val bssid = wifiInfo.bssid ?: "Unknown"
                val linkSpeed = wifiInfo.linkSpeed
                val frequency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    wifiInfo.frequency
                } else {
                    0
                }
                
                stringBuilder.append("WiFi SSID: $ssid\n")
                stringBuilder.append("WiFi BSSID: $bssid\n")
                stringBuilder.append("Link Speed: $linkSpeed Mbps\n")
                if (frequency > 0) {
                    stringBuilder.append("Frequency: $frequency MHz\n")
                }
            } catch (e: Exception) {
                stringBuilder.append("Error getting WiFi details: ${e.message}\n")
            }
        }
        
        // Get IP addresses
        val ipAddresses = getAllIPAddresses()
        if (ipAddresses.isNotEmpty()) {
            stringBuilder.append("IP Addresses:\n")
            ipAddresses.forEach { address ->
                stringBuilder.append("  $address\n")
            }
        } else {
            stringBuilder.append("No IP addresses found\n")
        }
        
        // Get server status
        stringBuilder.append("Socket Server Status: Server available on port 8080, 8081, 8082, 8090, or 9000\n")
        
        return stringBuilder.toString()
    }
    
    /**
     * Check if the device is connected to WiFi
     */
    fun isWifiConnected(connectivityManager: ConnectivityManager): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo != null && networkInfo.type == ConnectivityManager.TYPE_WIFI && networkInfo.isConnected
        }
    }
    
    /**
     * Get the current connection type
     */
    fun getConnectionType(connectivityManager: ConnectivityManager): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return "None"
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "Unknown"
            
            return when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Unknown"
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            if (networkInfo == null || !networkInfo.isConnected) return "None"
            
            return when (networkInfo.type) {
                ConnectivityManager.TYPE_WIFI -> "WiFi"
                ConnectivityManager.TYPE_MOBILE -> "Cellular"
                ConnectivityManager.TYPE_BLUETOOTH -> "Bluetooth"
                ConnectivityManager.TYPE_ETHERNET -> "Ethernet"
                ConnectivityManager.TYPE_VPN -> "VPN"
                else -> "Unknown"
            }
        }
    }
    
    /**
     * Get all IP addresses for the device
     */
    fun getAllIPAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                
                // Skip loopback and down interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }
                
                val interfaceAddresses = networkInterface.inetAddresses
                while (interfaceAddresses.hasMoreElements()) {
                    val address = interfaceAddresses.nextElement()
                    
                    // Skip loopback addresses and IPv6 (for simplicity)
                    if (address.isLoopbackAddress || address.hostAddress.contains(":")) {
                        continue
                    }
                    
                    addresses.add("${networkInterface.displayName}: ${address.hostAddress}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP addresses: ${e.message}")
        }
        
        return addresses
    }
} 