package com.forge.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.forge.app.data.models.ChatMessage
import com.forge.app.data.models.MessageRole
import com.forge.app.databinding.ItemMessageUserBinding
import com.forge.app.databinding.ItemMessageAssistantBinding
import com.forge.app.databinding.ItemMessageSystemBinding
import io.noties.markwon.Markwon

class MessageAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DiffCallback()) {
    private lateinit var markwon: Markwon

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
        ensureMarkwon(parent.context)
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

    private fun ensureMarkwon(context: Context) {
        if (!::markwon.isInitialized) {
            markwon = Markwon.create(context)
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
            bindMessageText(binding.root, binding.tvMessage, message, "Prompt")
        }
    }

    inner class AssistantViewHolder(
        private val binding: ItemMessageAssistantBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            bindMessageText(binding.root, binding.tvMessage, message, "Forge reply")

            // Show modified files
            if (message.attachedFiles.isNotEmpty()) {
                binding.tvModifiedFiles.visibility = View.VISIBLE
                val latestFiles = message.attachedFiles.takeLast(4)
                binding.tvModifiedFiles.text = buildString {
                    appendLine("Updated files")
                    latestFiles.forEach { appendLine("- ${it.substringAfterLast("/")}") }
                    val remaining = message.attachedFiles.size - latestFiles.size
                    if (remaining > 0) append("+ $remaining more")
                }.trim()
            } else {
                binding.tvModifiedFiles.visibility = View.GONE
            }
        }
    }

    inner class SystemViewHolder(
        private val binding: ItemMessageSystemBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            bindMessageText(binding.root, binding.tvMessage, message, "System message")
            if (message.isError) {
                binding.tvMessage.setTextColor(Color.parseColor("#F44336"))
                binding.cardSystem.setCardBackgroundColor(Color.parseColor("#FFEBEE"))
            } else {
                binding.tvMessage.setTextColor(Color.parseColor("#37474F"))
                binding.cardSystem.setCardBackgroundColor(Color.parseColor("#F5F5F5"))
            }
        }
    }

    private fun bindMessageText(root: View, textView: TextView, message: ChatMessage, clipLabel: String) {
        markwon.setMarkdown(textView, message.content)

        val copyListener = View.OnLongClickListener {
            copyMessage(it.context, clipLabel, message.content)
            true
        }

        root.setOnLongClickListener(copyListener)
        textView.setOnLongClickListener(copyListener)
    }

    private fun copyMessage(context: Context, label: String, content: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, content))
        Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
    }

    class DiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
        override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
    }
}
