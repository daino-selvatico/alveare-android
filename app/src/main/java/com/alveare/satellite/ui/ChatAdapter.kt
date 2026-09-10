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

        val lastIndex = messages.indexOfLast { it.sender == "assistant" }
        if (lastIndex != -1 && messages[lastIndex].isStreaming) {
            val last = messages[lastIndex]
            if (last.text == "Sto pensando..." || last.text == "In ascolto...") {
                last.text = delta
            } else {
                last.text += delta
            }
            notifyItemChanged(lastIndex)
        } else {
            addMessage(ChatMessage(sender = "assistant", text = delta, isStreaming = true))
        }
    }

    fun updateAssistantMetrics(ttftMs: Float = 0f, ttfaMs: Float = 0f) {
        val lastIndex = messages.indexOfLast { it.sender == "assistant" }
        if (lastIndex != -1) {
            val last = messages[lastIndex]
            if (ttftMs > 0) last.ttftMs = ttftMs
            if (ttfaMs > 0) last.ttfaMs = ttfaMs
            notifyItemChanged(lastIndex)
        }
    }

    fun setAssistantPlaying(isPlaying: Boolean) {
        val lastIndex = messages.indexOfLast { it.sender == "assistant" }
        if (lastIndex != -1) {
            val last = messages[lastIndex]
            if (last.isPlaying != isPlaying) {
                last.isPlaying = isPlaying
                notifyItemChanged(lastIndex)
            }
        }
    }

    fun finalizeAssistant(fullText: String?) {
        val lastIndex = messages.indexOfLast { it.sender == "assistant" }
        if (lastIndex != -1) {
            val last = messages[lastIndex]
            last.isStreaming = false
            if (!fullText.isNullOrBlank()) {
                last.text = fullText
            }
            notifyItemChanged(lastIndex)
        }
    }

    fun updateOrAddToolCall(toolName: String, args: String?, summary: String?, status: String?) {
        val existingIndex = messages.indexOfLast { it.sender == "tool" && it.toolName == toolName && (it.toolStatus == "running" || it.toolStatus == null) }
        if (existingIndex != -1) {
            val item = messages[existingIndex]
            item.toolStatus = status ?: "ok"
            if (!summary.isNullOrBlank()) item.toolSummary = summary
            if (!args.isNullOrBlank()) item.toolArgs = args
            notifyItemChanged(existingIndex)
        } else {
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
        private val badgeStt: TextView = itemView.findViewById(R.id.badgeSttLatency)

        fun bind(item: ChatMessage) {
            tvText.text = item.text
            tvMeta.text = timeFormatter.format(Date(item.timestamp))

            val latency = if (item.sttLatencyMs > 0) item.sttLatencyMs else item.latencyMs
            if (latency > 0) {
                badgeStt.visibility = View.VISIBLE
                badgeStt.text = "⚡ STT: ${latency.toInt()}ms"
            } else {
                badgeStt.visibility = View.GONE
            }
        }
    }

    class AssistantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSender: TextView = itemView.findViewById(R.id.tvAssistantSender)
        private val tvText: TextView = itemView.findViewById(R.id.tvAssistantText)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvAssistantMeta)
        private val pbStreaming: ProgressBar = itemView.findViewById(R.id.pbStreaming)
        private val badgeLatency: TextView = itemView.findViewById(R.id.badgeAssistantLatency)
        private val badgePlayback: TextView = itemView.findViewById(R.id.badgePlayback)

        fun bind(item: ChatMessage) {
            tvText.text = item.text
            tvMeta.text = timeFormatter.format(Date(item.timestamp))
            pbStreaming.visibility = if (item.isStreaming) View.VISIBLE else View.GONE

            // TTFT and TTFA badges
            val hasTtft = item.ttftMs > 0
            val hasTtfa = item.ttfaMs > 0
            if (hasTtft || hasTtfa) {
                badgeLatency.visibility = View.VISIBLE
                val ttftStr = if (hasTtft) "TTFT: ${item.ttftMs.toInt()}ms" else ""
                val ttfaStr = if (hasTtfa) "TTFA: ${item.ttfaMs.toInt()}ms" else ""
                badgeLatency.text = if (hasTtft && hasTtfa) "⚡ $ttftStr • $ttfaStr" else "⚡ ${ttftStr.ifEmpty { ttfaStr }}"
            } else {
                badgeLatency.visibility = View.GONE
            }

            // Playback status badge
            if (item.isPlaying) {
                badgePlayback.visibility = View.VISIBLE
                badgePlayback.text = "🔊 In riproduzione"
                badgePlayback.setTextColor(ContextCompat.getColor(itemView.context, R.color.accent_purple))
            } else if (!item.isStreaming && item.text.isNotBlank() && item.text != "Sto pensando...") {
                badgePlayback.visibility = View.VISIBLE
                badgePlayback.text = "✓ Risposto"
                badgePlayback.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_muted))
            } else {
                badgePlayback.visibility = View.GONE
            }
        }
    }

    class ToolViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvIcon: TextView = itemView.findViewById(R.id.tvToolIcon)
        private val tvHeader: TextView = itemView.findViewById(R.id.tvToolHeader)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvToolStatus)
        private val tvSummary: TextView = itemView.findViewById(R.id.tvToolSummary)
        private val pbProgress: ProgressBar = itemView.findViewById(R.id.pbToolProgress)

        fun bind(item: ChatMessage) {
            val name = item.toolName ?: "tool"
            val isWebSearch = name.equals("web_search", ignoreCase = true)
            val isBash = name.equals("bash", ignoreCase = true)

            tvIcon.text = when {
                isWebSearch -> "🔍"
                isBash -> "💻"
                name.contains("home", ignoreCase = true) -> "🏠"
                else -> "⚙️"
            }

            val query = extractQuery(item.toolArgs)
            val headerText = when {
                isWebSearch -> {
                    if (query.isNotEmpty()) "Ricerca Web: \"$query\"" else "Ricerca Web"
                }
                isBash -> {
                    val cmd = query.ifEmpty { item.toolArgs ?: "" }
                    if (cmd.isNotEmpty()) "Comando Bash: \"$cmd\"" else "Comando Bash"
                }
                else -> "Strumento: $name"
            }
            tvHeader.text = headerText

            val isRunning = item.toolStatus == "running"
            val isError = item.toolStatus == "error"

            pbProgress.visibility = if (isRunning) View.VISIBLE else View.GONE

            if (isRunning) {
                tvStatus.text = "Ricerca in corso..."
                tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.accent_blue))
            } else if (isError) {
                tvStatus.text = "✗ Errore"
                tvStatus.setTextColor(ContextCompat.getColor(itemView.context, R.color.accent_red))
            } else {
                tvStatus.text = if (isWebSearch) "✓ Risultati trovati" else "✓ Completato"
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
                } else if (args.contains("command")) {
                    val match = Regex("\"command\"\\s*:\\s*\"(.*?)\"").find(args)
                    match?.groupValues?.get(1) ?: args
                } else {
                    args.trim('"', '{', '}', ' ')
                }
            } catch (e: Exception) {
                args
            }
        }
    }
}
