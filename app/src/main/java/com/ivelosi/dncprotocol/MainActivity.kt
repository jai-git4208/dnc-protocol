package com.ivelosi.dncprotocol

/**
 * (c) Ivelosi Technologies. All rights reserved.
 */

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.ivelosi.dncprotocol.databinding.ActivityMainBinding
import com.ivelosi.dncprotocol.network.P2PChatService
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var chatService: P2PChatService? = null
    private var serviceBound = false

    // Permissions needed
    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACCESS_NETWORK_STATE
    ).apply {
        // For Android 12+ (API 31 and higher)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // For older Android versions
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // For Android 13+ (API 33 and higher)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // Permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            setupApp()
        } else {
            Toast.makeText(this, "Permissions are required for this app to work", Toast.LENGTH_LONG).show()

            // Check which critical permissions are missing
            val missingCritical = requiredPermissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED &&
                        (it == Manifest.permission.ACCESS_FINE_LOCATION ||
                                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && it == Manifest.permission.NEARBY_WIFI_DEVICES))
            }

            if (missingCritical.isNotEmpty()) {
                Toast.makeText(this, "Missing critical permissions: ${missingCritical.joinToString()}", Toast.LENGTH_LONG).show()
                finish()
            } else {
                // We can still try to run with limited functionality
                setupApp()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as P2PChatService.LocalBinder
            chatService = binder.getService()
            serviceBound = true

            // Monitor connection state
            chatService?.connectionStateFlow?.onEach { state ->
                // Update UI or show toast based on connection state
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Connection state: $state", Toast.LENGTH_SHORT).show()
                }
            }?.launchIn(lifecycleScope)
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
        // Bind to the service if permissions are granted
        if (hasPermissions()) {
            val intent = Intent(this, P2PChatService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            startService(intent)
        }
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun checkPermissions() {
        if (hasPermissions()) {
            setupApp()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun setupApp() {
        // Setup navigation
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)

        // Start the service
        val serviceIntent = Intent(this, P2PChatService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Bind to the service
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
}