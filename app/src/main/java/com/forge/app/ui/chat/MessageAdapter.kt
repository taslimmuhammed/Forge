package com.forge.app.ui.chat

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.forge.app.data.models.ChatMessage
import com.forge.app.data.models.MessageRole
import com.forge.app.databinding.ItemMessageUserBinding
import com.forge.app.databinding.ItemMessageAssistantBinding
import com.forge.app.databinding.ItemMessageSystemBinding

class MessageAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback()) {

    companion object {
        const val TYPE_USER = 0
        const val TYPE_ASSISTANT = 1
        const val TYPE_SYSTEM = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position).role) {
            MessageRole.USER -> TYPE_USER
            MessageRole.ASSISTANT -> TYPE_ASSISTANT
            MessageRole.SYSTEM, MessageRole.BUILD_LOG -> TYPE_SYSTEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_USER -> {
                val binding = ItemMessageUserBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                UserViewHolder(binding)
            }
            TYPE_ASSISTANT -> {
                val binding = ItemMessageAssistantBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                AssistantViewHolder(binding)
            }
            else -> {
                val binding = ItemMessageSystemBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                SystemViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.bind(message)
            is AssistantViewHolder -> holder.bind(message)
            is SystemViewHolder -> holder.bind(message)
        }
    }

    inner class UserViewHolder(
        private val binding: ItemMessageUserBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvMessage.text = message.content
        }
    }

    inner class AssistantViewHolder(
        private val binding: ItemMessageAssistantBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvMessage.text = message.content

            // Show modified files
            if (message.attachedFiles.isNotEmpty()) {
                binding.tvModifiedFiles.visibility = View.VISIBLE
                binding.tvModifiedFiles.text = "Modified: " + message.attachedFiles
                    .takeLast(3)
                    .joinToString(", ") { it.substringAfterLast("/") }
            } else {
                binding.tvModifiedFiles.visibility = View.GONE
            }
        }
    }

    inner class SystemViewHolder(
        private val binding: ItemMessageSystemBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvMessage.text = message.content
            if (message.isError) {
                binding.tvMessage.setTextColor(Color.parseColor("#F44336"))
                binding.cardSystem.setCardBackgroundColor(Color.parseColor("#FFEBEE"))
            } else {
                binding.tvMessage.setTextColor(Color.parseColor("#37474F"))
                binding.cardSystem.setCardBackgroundColor(Color.parseColor("#F5F5F5"))
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
        override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
    }
}