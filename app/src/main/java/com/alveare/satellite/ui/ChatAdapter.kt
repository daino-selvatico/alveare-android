package com.alveare.satellite.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.alveare.satellite.R
import com.alveare.satellite.data.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatAdapter(
    private val messages: MutableList<ChatMessage> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_ASSISTANT = 2
        private const val TYPE_TOOL = 3

        private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].sender) {
            "user" -> TYPE_USER
            "assistant" -> TYPE_ASSISTANT
            "tool" -> TYPE_TOOL
            else -> TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> {
                val v = inflater.inflate(R.layout.item_chat_user, parent, false)
                UserViewHolder(v)
            }
            TYPE_TOOL -> {
                val v = inflater.inflate(R.layout.item_chat_tool, parent, false)
                ToolViewHolder(v)
            }
            else -> {
                val v = inflater.inflate(R.layout.item_chat_assistant, parent, false)
                AssistantViewHolder(v)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = messages[position]
        when (holder) {
            is UserViewHolder -> holder.bind(item)
            is AssistantViewHolder -> holder.bind(item)
            is ToolViewHolder -> holder.bind(item)
        }
    }

    override fun getItemCount(): Int = messages.size

    fun getMessages(): List<ChatMessage> = messages.toList()

    fun setMessages(newMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun appendAssistantDelta(delta: String) {
        if (messages.isEmpty()) {
            addMessage(ChatMessage(sender = "assistant", text = delta, isStreaming = true))
            return
        }

        val last = messages.last()
        if (last.sender == "assistant" && last.isStreaming) {
            if (last.text == "Sto pensando..." || last.text == "In ascolto...") {
                last.text = delta
            } else {
                last.text += delta
            }
            notifyItemChanged(messages.size - 1)
        } else {
            addMessage(ChatMessage(sender = "assistant", text = delta, isStreaming = true))
        }
    }

    fun finalizeAssistant(fullText: String?) {
        if (messages.isEmpty()) return
        val last = messages.last()
        if (last.sender == "assistant") {
            last.isStreaming = false
            if (!fullText.isNullOrBlank()) {
                last.text = fullText
            }
            notifyItemChanged(messages.size - 1)
        }
    }

    fun updateOrAddToolCall(toolName: String, args: String?, summary: String?, status: String?) {
        // Find existing tool item with matching toolName if running
        val existingIndex = messages.indexOfLast { it.sender == "tool" && it.toolName == toolName && it.toolStatus == "running" }
        if (existingIndex != -1) {
            val item = messages[existingIndex]
            item.toolStatus = status ?: "success"
            if (!summary.isNullOrBlank()) item.toolSummary = summary
            if (!args.isNullOrBlank()) item.toolArgs = args
            notifyItemChanged(existingIndex)
        } else {
            // New tool call card
            val item = ChatMessage(
                sender = "tool",
                text = "Tool Call",
                toolName = toolName,
                toolArgs = args,
                toolSummary = summary,
                toolStatus = status ?: "running"
            )
            addMessage(item)
        }
    }

    fun clearMessages() {
        val count = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, count)
    }

    // --- ViewHolders ---

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tvUserSender)
        private val tvText: TextView = itemView.findViewById(R.id.tvUserText)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvUserMeta)

        fun bind(item: ChatMessage) {
            tvText.text = item.text
            val time = timeFormatter.format(Date(item.timestamp))
            val meta = if (item.latencyMs > 0) {
                "$time • ${item.latencyMs.toInt()} ms"
            } else {
                time
            }
            tvMeta.text = meta
        }
    }

    class AssistantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tvAssistantSender)
        private val tvText: TextView = itemView.findViewById(R.id.tvAssistantText)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvAssistantMeta)
        private val pbStreaming: ProgressBar = itemView.findViewById(R.id.pbStreaming)

        fun bind(item: ChatMessage) {
            tvText.text = item.text
            tvMeta.text = timeFormatter.format(Date(item.timestamp))
            pbStreaming.visibility = if (item.isStreaming) View.VISIBLE else View.GONE
        }
    }

    class ToolViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvIcon: TextView = itemView.findViewById(R.id.tvToolIcon)
        private val tvHeader: TextView = itemView.findViewById(R.id.tvToolHeader)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvToolStatus)
        private val tvSummary: TextView = itemView.findViewById(R.id.tvToolSummary)

        fun bind(item: ChatMessage) {
            val name = item.toolName ?: "tool"
            val icon = when (name.lowercase()) {
                "web_search" -> "🔍"
                "bash" -> "⚡"
                "homeassistant", "hass" -> "🏠"
                else -> "⚙️"
            }
            tvIcon.text = icon

            val headerText = when (name.lowercase()) {
                "web_search" -> {
                    val query = extractQuery(item.toolArgs)
                    if (query.isNotEmpty()) "Ricerca Web: \"$query\"" else "Ricerca Web"
                }
                "bash" -> "Terminale: ${item.toolArgs ?: ""}"
                else -> "Strumento: $name"
            }
            tvHeader.text = headerText

            val isRunning = item.toolStatus == "running"
            if (isRunning) {
                tvStatus.text = "In corso..."
                tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.primary))
            } else if (item.toolStatus == "error") {
                tvStatus.text = "Fallito"
                tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.accent_red))
            } else {
                tvStatus.text = "✓ Completato"
                tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.secondary))
            }

            if (!item.toolSummary.isNullOrBlank()) {
                tvSummary.visibility = View.VISIBLE
                tvSummary.text = item.toolSummary
            } else {
                tvSummary.visibility = View.GONE
            }
        }

        private fun extractQuery(args: String?): String {
            if (args.isNullOrBlank()) return ""
            return try {
                if (args.contains("query")) {
                    val match = Regex("\"query\"\\s*:\\s*\"(.*?)\"").find(args)
                    match?.groupValues?.get(1) ?: args
                } else {
                    args.trim('"')
                }
            } catch (e: Exception) {
                args
            }
        }
    }
}
