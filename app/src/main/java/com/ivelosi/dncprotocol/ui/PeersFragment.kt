package com.ivelosi.dncprotocol.ui

/**
 * (c) Ivelosi Technologies. All rights reserved.
 */

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.ivelosi.dncprotocol.databinding.FragmentPeersBinding
import com.ivelosi.dncprotocol.network.P2PChatService
import kotlinx.coroutines.launch

class PeersFragment : Fragment() {
    private var _binding: FragmentPeersBinding? = null
    private val binding get() = _binding!!

    private lateinit var peersAdapter: PeersAdapter
    private val peersList = mutableListOf<PeerItem>()

    private var p2pChatService: P2PChatService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as P2PChatService.LocalBinder
            p2pChatService = binder.getService()
            bound = true

            collectServiceData()
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
        _binding = FragmentPeersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()

        binding.fabRefresh.setOnClickListener {
            refreshPeers()
        }

        // Start and bind to the service
        val intent = Intent(requireContext(), P2PChatService::class.java)
        requireActivity().startService(intent)
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

    private fun setupRecyclerView() {
        peersAdapter = PeersAdapter { peer ->
            // Navigate to chat fragment when a peer is clicked
            findNavController().navigate(
                PeersFragmentDirections.actionPeersFragmentToChatFragment(
                    peerId = peer.id,
                    peerName = peer.name
                )
            )
        }

        binding.recyclerPeers.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = peersAdapter
        }

        updatePeersList()
    }

    private fun collectServiceData() {
        p2pChatService?.let { service ->
            viewLifecycleOwner.lifecycleScope.launch {
                service.peerDiscoveredFlow.collect { peerId ->
                    // Add new discovered peer
                    val newPeer = PeerItem(
                        id = peerId,
                        name = "Device ${peerId.takeLast(6)}",
                        connectionType = if (peerId.startsWith("ble-")) "Bluetooth LE" else "WiFi Aware"
                    )

                    if (!peersList.any { it.id == peerId }) {
                        peersList.add(newPeer)
                        updatePeersList()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshPeers() {
        // Clear the list and restart discovery
        peersList.clear()
        updatePeersList()

        p2pChatService?.let { service ->
            service.startDiscovery()
            service.startAdvertising()
        }
    }

    private fun updatePeersList() {
        peersAdapter.submitList(peersList.toList())

        // Show empty view if no peers found
        binding.textEmpty.visibility = if (peersList.isEmpty()) View.VISIBLE else View.GONE
    }
}