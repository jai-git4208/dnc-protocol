package com.ivelosi.dncprotocol.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility class to diagnose WiFi Aware functionality on the device
 */
class WifiAwareDiagnostics(private val context: Context) {
    private val TAG = "WifiAwareDiagnostics"

    /**
     * Runs a full diagnostic check of WiFi Aware capabilities on the device
     * @return A string with diagnostic information
     */
    suspend fun runDiagnostics(): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()

        sb.appendLine("==== WiFi Aware Diagnostics ====")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("")

        // Check basic requirements
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            sb.appendLine("❌ WiFi Aware requires Android 8.0 (API 26) or higher")
            return@withContext sb.toString()
        } else {
            sb.appendLine("✓ Android version supported (${Build.VERSION.SDK_INT} >= 26)")
        }

        // Check if hardware supports WiFi Aware
        val hasWifiAwareFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        if (hasWifiAwareFeature) {
            sb.appendLine("✓ Device reports WiFi Aware hardware support")
        } else {
            sb.appendLine("❌ Device does NOT report WiFi Aware hardware support")
        }

        // Check WiFi status
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiEnabled = wifiManager.isWifiEnabled
        sb.appendLine(if (wifiEnabled) "✓ WiFi is enabled" else "❌ WiFi is disabled")

        // Get WiFi Aware service
        val wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        if (wifiAwareManager == null) {
            sb.appendLine("❌ WiFi Aware service is not available")
        } else {
            sb.appendLine("✓ WiFi Aware service is available")

            // Check if WiFi Aware is currently available
            val isAvailable = wifiAwareManager.isAvailable
            sb.appendLine(if (isAvailable) "✓ WiFi Aware is currently available" else "❌ WiFi Aware is currently NOT available")
        }

        // Check Location Services
        val locationEnabled = try {
            val locationMode = Settings.Secure.getInt(context.contentResolver, Settings.Secure.LOCATION_MODE)
            locationMode != Settings.Secure.LOCATION_MODE_OFF
        } catch (e: Exception) {
            false
        }
        sb.appendLine(if (locationEnabled) "✓ Location services are enabled" else "❌ Location services are disabled")

        // Check permissions
        val hasFineLocation = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        sb.appendLine(if (hasFineLocation) "✓ ACCESS_FINE_LOCATION permission granted" else "❌ ACCESS_FINE_LOCATION permission missing")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNearbyWifiDevices = context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) ==
                    PackageManager.PERMISSION_GRANTED
            sb.appendLine(if (hasNearbyWifiDevices) "✓ NEARBY_WIFI_DEVICES permission granted" else "❌ NEARBY_WIFI_DEVICES permission missing")
        }

        // Summary
        sb.appendLine("")
        sb.appendLine("==== Summary ====")
        if (!hasWifiAwareFeature) {
            sb.appendLine("❌ This device does not support WiFi Aware")
        } else if (!wifiEnabled) {
            sb.appendLine("❌ WiFi must be enabled to use WiFi Aware")
        } else if (!locationEnabled) {
            sb.appendLine("❌ Location services must be enabled to use WiFi Aware")
        } else if (!hasFineLocation) {
            sb.appendLine("❌ Missing required permission: ACCESS_FINE_LOCATION")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            sb.appendLine("❌ Missing required permission: NEARBY_WIFI_DEVICES")
        } else if (wifiAwareManager == null || !wifiAwareManager.isAvailable) {
            sb.appendLine("❌ WiFi Aware is not currently available on this device")
            sb.appendLine("   This could be due to power saving mode, airplane mode,")
            sb.appendLine("   or WiFi Aware being disabled in system settings")
        } else {
            sb.appendLine("✓ All checks passed! WiFi Aware should be functional")
        }

        // Recommendations
        sb.appendLine("")
        sb.appendLine("==== Recommendations ====")
        if (!wifiEnabled) sb.appendLine("1. Enable WiFi in system settings")
        if (!locationEnabled) sb.appendLine("${if (!wifiEnabled) 2 else 1}. Enable Location Services in system settings")
        if (!hasFineLocation) sb.appendLine("${if (!wifiEnabled || !locationEnabled) 3 else 1}. Grant ACCESS_FINE_LOCATION permission")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            sb.appendLine("${if (!wifiEnabled || !locationEnabled || !hasFineLocation) 4 else 1}. Grant NEARBY_WIFI_DEVICES permission")
        }
        if (wifiAwareManager != null && !wifiAwareManager.isAvailable) {
            sb.appendLine("- Check if Power Saving Mode is enabled (disable it)")
            sb.appendLine("- Check if Airplane Mode is enabled (disable it)")
            sb.appendLine("- Try rebooting your device")
        }
        if (!hasWifiAwareFeature) {
            sb.appendLine("This device does not support WiFi Aware. You should fall back to BLE connections only.")
        }

        Log.d(TAG, sb.toString())
        return@withContext sb.toString()
    }
}