package com.ivelosi.dncprotocol.ui

/**
 * (c) Ivelosi Technologies. All rights reserved.
 */

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ivelosi.dncprotocol.databinding.ItemPeerBinding

class PeersAdapter(private val onPeerClicked: (PeerItem) -> Unit) :
    ListAdapter<PeerItem, PeersAdapter.PeerViewHolder>(PeerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val binding = ItemPeerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PeerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PeerViewHolder(private val binding: ItemPeerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onPeerClicked(getItem(position))
                }
            }
        }

        fun bind(peer: PeerItem) {
            binding.textPeerName.text = peer.name
            binding.textPeerId.text = peer.id
            binding.textConnectionType.text = peer.connectionType
        }
    }

    private class PeerDiffCallback : DiffUtil.ItemCallback<PeerItem>() {
        override fun areItemsTheSame(oldItem: PeerItem, newItem: PeerItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: PeerItem, newItem: PeerItem): Boolean {
            return oldItem == newItem
        }
    }
}