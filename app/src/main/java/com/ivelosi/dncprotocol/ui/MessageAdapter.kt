package com.ivelosi.dncprotocol.ui

/**
 * (c) Ivelosi Technologies. All rights reserved.
 */

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ivelosi.dncprotocol.R
import com.ivelosi.dncprotocol.databinding.ItemMessageBinding

class MessagesAdapter(private val context: Context) :
    ListAdapter<MessageItem, MessagesAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(message: MessageItem) {
            binding.textMessage.text = message.content
            binding.textTimestamp.text = message.timestamp

            if (message.isOutgoing) {
                // Outgoing message
                binding.messageBubble.background = ContextCompat.getDrawable(
                    context,
                    R.drawable.bg_message_outgoing
                )
                binding.textMessage.setTextColor(ContextCompat.getColor(context, android.R.color.white))

                // Align to the right
                (binding.messageBubble.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = 100
                    marginEnd = 0
                }
                (binding.textTimestamp.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = 100
                    marginEnd = 0
                }
            } else {
                // Incoming message
                binding.messageBubble.background = ContextCompat.getDrawable(
                    context,
                    R.drawable.bg_message_incoming
                )
                binding.textMessage.setTextColor(ContextCompat.getColor(context, android.R.color.black))

                // Align to the left
                (binding.messageBubble.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = 0
                    marginEnd = 100
                }
                (binding.textTimestamp.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    marginStart = 0
                    marginEnd = 100
                }
            }
        }
    }

    private class MessageDiffCallback : DiffUtil.ItemCallback<MessageItem>() {
        override fun areItemsTheSame(oldItem: MessageItem, newItem: MessageItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MessageItem, newItem: MessageItem): Boolean {
            return oldItem == newItem
        }
    }
}