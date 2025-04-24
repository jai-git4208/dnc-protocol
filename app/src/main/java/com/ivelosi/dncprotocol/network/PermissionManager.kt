package com.ivelosi.dncprotocol.network

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class PermissionManager(private val activity: AppCompatActivity) {
    companion object {
        // BLE permissions
        val BLE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        // WiFi Aware permissions
        val WIFI_AWARE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var permissionCallback: ((Boolean) -> Unit)? = null

    init {
        setupPermissionLauncher()
    }

    private fun setupPermissionLauncher() {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            permissionCallback?.invoke(allGranted)
        }
    }

    fun requestBlePermissions(callback: (Boolean) -> Unit) {
        permissionCallback = callback

        if (hasBlePermissions()) {
            callback(true)
            return
        }

        permissionLauncher.launch(BLE_PERMISSIONS)
    }

    fun requestWifiAwarePermissions(callback: (Boolean) -> Unit) {
        permissionCallback = callback

        if (hasWifiAwarePermissions()) {
            callback(true)
            return
        }

        permissionLauncher.launch(WIFI_AWARE_PERMISSIONS)
    }

    fun hasBlePermissions(): Boolean {
        return BLE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasWifiAwarePermissions(): Boolean {
        return WIFI_AWARE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}