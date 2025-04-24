package com.ivelosi.dncprotocol.ui

/**
 * (c) Ivelosi Technologies. All rights reserved.
 */

import android.Manifest
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
import androidx.annotation.RequiresPermission
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.ivelosi.dncprotocol.databinding.FragmentChatBinding
import com.ivelosi.dncprotocol.network.ChatMessage
import com.ivelosi.dncprotocol.network.P2PChatService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatFragment : Fragment() {
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private val args: ChatFragmentArgs by navArgs()
    private lateinit var messagesAdapter: MessagesAdapter
    private val messagesList = mutableListOf<MessageItem>()

    private var p2pChatService: P2PChatService? = null
    private var bound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as P2PChatService.LocalBinder
            p2pChatService = binder.getService()
            bound = true

            collectMessages()
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
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerView()
        setupMessageSending()

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

    private fun setupToolbar() {
        binding.textPeerName.text = args.peerName
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupRecyclerView() {
        messagesAdapter = MessagesAdapter(requireContext())

        binding.recyclerMessages.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            adapter = messagesAdapter
        }

        updateMessagesList()
    }

    @SuppressLint("MissingPermission")
    private fun setupMessageSending() {
        binding.buttonSend.setOnClickListener {
            val messageText = binding.editMessage.text.toString().trim()
            if (messageText.isNotEmpty()) {
                sendMessage(messageText)
                binding.editMessage.setText("")
            }
        }
    }

    private fun collectMessages() {
        p2pChatService?.let { service ->
            viewLifecycleOwner.lifecycleScope.launch {
                service.messageReceivedFlow.collect { chatMessage ->
                    if (chatMessage.senderId == args.peerId) {
                        // Message from the current chat peer
                        addMessage(chatMessage, isOutgoing = false)
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendMessage(text: String) {
        p2pChatService?.sendMessage(args.peerId, text)

        // Add outgoing message to the UI
        val timestamp = System.currentTimeMillis()
        val chatMessage = ChatMessage("me", text, timestamp)
        addMessage(chatMessage, isOutgoing = true)
    }

    private fun addMessage(chatMessage: ChatMessage, isOutgoing: Boolean) {
        val messageItem = MessageItem(
            id = chatMessage.timestamp.toString(),
            content = chatMessage.content,
            timestamp = formatTimestamp(chatMessage.timestamp),
            isOutgoing = isOutgoing
        )

        messagesList.add(messageItem)
        updateMessagesList()

        // Scroll to the bottom
        binding.recyclerMessages.post {
            binding.recyclerMessages.smoothScrollToPosition(messagesList.size - 1)
        }
    }

    private fun updateMessagesList() {
        messagesAdapter.submitList(messagesList.toList())

        // Show empty view if no messages
        binding.textEmpty.visibility = if (messagesList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

