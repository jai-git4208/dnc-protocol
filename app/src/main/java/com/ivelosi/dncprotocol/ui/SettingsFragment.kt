package com.ivelosi.dncprotocol.ui

/**
 * (c) Ivelosi Technologies. All rights reserved.
 */

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.ivelosi.dncprotocol.databinding.FragmentSettingsBinding
import com.ivelosi.dncprotocol.network.P2PChatService

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var p2pChatService: P2PChatService? = null
    private var bound = false

    private lateinit var sharedPreferences: SharedPreferences

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as P2PChatService.LocalBinder
            p2pChatService = binder.getService()
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            p2pChatService = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        setupUserProfile()
        setupConnectivitySettings()

        // Bind to the service
        val intent = Intent(requireContext(), P2PChatService::class.java)
        requireActivity().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (bound) {
            requireActivity().unbindService(serviceConnection)
            bound = false
        }
        _binding = null
    }

    private fun setupUserProfile() {
        // Load saved display name
        val savedName = sharedPreferences.getString(KEY_DISPLAY_NAME, "")
        binding.editDisplayName.setText(savedName)

        binding.buttonSaveName.setOnClickListener {
            val displayName = binding.editDisplayName.text.toString().trim()
            if (displayName.isNotEmpty()) {
                // Save display name
                sharedPreferences.edit()
                    .putString(KEY_DISPLAY_NAME, displayName)
                    .apply()

                Toast.makeText(requireContext(), "Display name saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupConnectivitySettings() {
        // Load saved settings
        binding.switchWifiAware.isChecked = sharedPreferences.getBoolean(KEY_WIFI_AWARE_ENABLED, true)
        binding.switchBluetooth.isChecked = sharedPreferences.getBoolean(KEY_BLUETOOTH_ENABLED, true)

        binding.switchWifiAware.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit()
                .putBoolean(KEY_WIFI_AWARE_ENABLED, isChecked)
                .apply()
        }

        binding.switchBluetooth.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit()
                .putBoolean(KEY_BLUETOOTH_ENABLED, isChecked)
                .apply()
        }
    }

    companion object {
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_WIFI_AWARE_ENABLED = "wifi_aware_enabled"
        private const val KEY_BLUETOOTH_ENABLED = "bluetooth_enabled"
    }
}