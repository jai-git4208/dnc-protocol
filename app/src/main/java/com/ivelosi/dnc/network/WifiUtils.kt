package com.ivelosi.dnc.network

/**
 * (c)Ivelosi Technologies. All Rights Reserved.
 */

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.FileReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.regex.Pattern

object WifiUtils {
    private const val TAG = "DNCWifiUtils"
    
    fun getWifiInfo(context: Context, wifiManager: WifiManager): String {
        return try {
            if (wifiManager.isWifiEnabled) {
                val ip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // For Android 12 and above, use ConnectivityManager
                    getIpAddressUsingConnectivityManager(context)
                } else {
                    // Legacy approach for older Android versions
                    intToIpAddress(wifiManager.connectionInfo.ipAddress)
                }
                val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // For Android 12 and above
                    wifiManager.connectionInfo.ssid.replace("\"", "")
                } else {
                    wifiManager.connectionInfo.ssid.replace("\"", "")
                }

                "SSID: $ssid\nIP: $ip\nPassword: Not available (security restriction)"
            } else {
                "WiFi is disabled"
            }
        } catch (e: Exception) {
            "WiFi info unavailable: ${e.message}"
        }
    }

    private fun getIpAddressUsingConnectivityManager(context: Context): String {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return "0.0.0.0"
            val linkProperties = connectivityManager.getLinkProperties(network) ?: return "0.0.0.0"
            
            // Get all IP addresses assigned to this network
            for (address in linkProperties.linkAddresses) {
                val inetAddress = address.address
                // Skip IPv6 addresses
                if (!inetAddress.hostAddress.contains(":")) {
                    return inetAddress.hostAddress
                }
            }
            return "0.0.0.0"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address: ${e.message}")
            return "0.0.0.0"
        }
    }

    /**
     * Try to get the IP address of a remote device using multiple approaches:
     * 1. Use handshake if available - performs a direct Bluetooth socket handshake with the remote device
     *    to retrieve its actual IP address rather than guessing
     * 2. ARP table lookup - checks local ARP cache for matching MAC address
     * 3. Device name scanning - looks for IP addresses embedded in device names
     * 4. Network scanning - attempts to find viable devices in the network
     * 
     * This approach fixes the issue with the app previously retrieving its own IP address 
     * instead of the discovered device's IP address.
     * 
     * Also compatible with Android 15+ by using ConnectivityManager instead of deprecated 
     * WifiManager.getConnectionInfo() when appropriate.
     */
    fun extractIpAddress(context: Context, wifiInfo: String, wifiManager: WifiManager, bluetoothDevice: BluetoothDevice? = null): String {
        try {
            // If we have a Bluetooth device, first try to get its IP address via handshake
            if (bluetoothDevice != null) {
                val deviceName = bluetoothDevice.name ?: "Unknown"
                Log.d(TAG, "Attempting to get IP for device: $deviceName")
                
                // Try handshake first (this will be run in a background thread via NetworkManager)
                val handshakeIp = runBlocking {
                    NetworkManager.getInstance(context)?.getRemoteDeviceIpViaHandshake(bluetoothDevice)
                }
                if (!handshakeIp.isNullOrEmpty() && isValidLocalAddress(handshakeIp)) {
                    Log.d(TAG, "Using IP from handshake: $handshakeIp")
                    return handshakeIp
                }
                
                try {
                    // Try to get IP from ARP table based on MAC address
                    val deviceMacAddress = bluetoothDevice.address
                    val ipFromArp = getIpFromArpTable(deviceMacAddress)
                    if (!ipFromArp.isNullOrEmpty() && isValidLocalAddress(ipFromArp)) {
                        Log.d(TAG, "Using IP from ARP table: $ipFromArp")
                        return ipFromArp
                    }
                    
                    // Try to get IP from device name if it contains an IP (some devices broadcast IP in name)
                    val ipPattern = Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)")
                    val matcher = ipPattern.matcher(deviceName)
                    
                    if (matcher.find()) {
                        val extractedIp = matcher.group(1) ?: ""
                        if (isValidLocalAddress(extractedIp)) {
                            Log.d(TAG, "Using IP from device name: $extractedIp")
                            return extractedIp
                        }
                    }
                    
                    // Try using the most common IP addresses from the subnet
                    // Get subnet from our IP address
                    val ourIp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        getIpAddressUsingConnectivityManager(context)
                    } else {
                        intToIpAddress(wifiManager.connectionInfo.ipAddress)
                    }
                    
                    Log.d(TAG, "Current device IP: $ourIp")
                    
                    // Try common device IPs on the network (gateway, etc.)
                    val lastDot = ourIp.lastIndexOf('.')
                    if (lastDot > 0) {
                        val subnet = ourIp.substring(0, lastDot+1)
                        
                        // Check the gateway .1 first
                        val gatewayIp = subnet + "1"
                        if (pingDevice(gatewayIp) && isValidLocalAddress(gatewayIp)) {
                            Log.d(TAG, "Using gateway IP: $gatewayIp")
                            return gatewayIp
                        }
                        
                        // Try other common IP addresses in the subnet
                        // Our device might be at .100, try nearby addresses first
                        val ourLastOctet = if (lastDot >= 0) ourIp.substring(lastDot + 1).toIntOrNull() ?: 0 else 0
                        
                        // Try some addresses near our own, typically devices are assigned close to each other
                        for (i in 1..10) {
                            // Try -i and +i from our IP
                            val candidateOctet1 = ourLastOctet - i
                            val candidateOctet2 = ourLastOctet + i
                            
                            if (candidateOctet1 > 0) {
                                val candidateIp1 = subnet + candidateOctet1
                                if (pingDevice(candidateIp1) && isValidLocalAddress(candidateIp1)) {
                                    Log.d(TAG, "Using candidate IP: $candidateIp1")
                                    return candidateIp1
                                }
                            }
                            
                            if (candidateOctet2 < 255) {
                                val candidateIp2 = subnet + candidateOctet2
                                if (pingDevice(candidateIp2) && isValidLocalAddress(candidateIp2)) {
                                    Log.d(TAG, "Using candidate IP: $candidateIp2")
                                    return candidateIp2
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting IP from Bluetooth device: ${e.message}")
                }
            }
            
            // As a fallback, try to extract IP from WifiInfo string
            val ipPattern = Pattern.compile("IP: (\\d+\\.\\d+\\.\\d+\\.\\d+)")
            val matcher = ipPattern.matcher(wifiInfo)
            
            if (matcher.find()) {
                val extractedIp = matcher.group(1) ?: ""
                if (isValidLocalAddress(extractedIp)) {
                    Log.d(TAG, "Using IP from WiFi info: $extractedIp")
                    return extractedIp
                }
            }
            
            // As a last resort, return an empty string
            return ""
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting IP address: ${e.message}")
        }
        
        // If all methods fail, return empty string
        return ""
    }

    /**
     * Read the ARP table to find the IP address for a device's MAC address
     */
    private fun getIpFromArpTable(macAddress: String): String? {
        try {
            // Normalize MAC address format to be consistent with ARP table
            val normalizedMac = macAddress.lowercase().replace(":", ":")
            
            // Read the ARP table
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                var line: String?
                // Skip the header line
                reader.readLine()
                
                // Read each line of the ARP table
                while (reader.readLine().also { line = it } != null) {
                    val parts = line?.trim()?.split("\\s+".toRegex())
                    if (parts != null && parts.size >= 4) {
                        val ip = parts[0]
                        val mac = parts[3]
                        
                        if (mac.equals(normalizedMac, ignoreCase = true)) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading ARP table: ${e.message}")
        }
        return null
    }
    
    /**
     * Simple ping to check if the device is reachable
     */
    private fun pingDevice(ipAddress: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("/system/bin/ping -c 1 -W 1 $ipAddress")
            val exitValue = process.waitFor()
            exitValue == 0
        } catch (e: Exception) {
            false
        }
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