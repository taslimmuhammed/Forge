package com.forge.app.data.repository

import android.content.Context
import com.forge.app.data.models.ChatMessage
import com.forge.app.data.models.MessageRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists chat messages to disk per-project so history survives app restarts.
 * Uses a simple JSON file — avoids Room complexity for chat data.
 */
class ChatHistoryManager(context: Context, projectId: String) {

    private val historyFile = File(
        context.filesDir,
        "projects/$projectId/.forge/chat_history.json"
    )

    init {
        historyFile.parentFile?.mkdirs()
    }

    fun loadMessages(projectId: String): List<ChatMessage> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val json = historyFile.readText()
            val arr = JSONArray(json)
            val messages = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                messages.add(
                    ChatMessage(
                        id = obj.getString("id"),
                        projectId = projectId,
                        role = MessageRole.valueOf(obj.getString("role")),
                        content = obj.getString("content"),
                        timestamp = obj.getLong("timestamp"),
                        isError = obj.optBoolean("isError", false),
                        attachedFiles = obj.optJSONArray("files")
                            ?.let { files -> List(files.length()) { files.getString(it) } }
                            ?: emptyList()
                    )
                )
            }
            messages
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveMessages(messages: List<ChatMessage>) {
        try {
            val arr = JSONArray()
            // Only persist last 100 messages to avoid unbounded growth
            messages.takeLast(100).forEach { msg ->
                arr.put(JSONObject().apply {
                    put("id", msg.id)
                    put("role", msg.role.name)
                    put("content", msg.content)
                    put("timestamp", msg.timestamp)
                    put("isError", msg.isError)
                    put("files", JSONArray(msg.attachedFiles))
                })
            }
            historyFile.writeText(arr.toString(2))
        } catch (e: Exception) {
            // Non-fatal — messages just won't be persisted this time
        }
    }

    fun appendMessage(message: ChatMessage) {
        val current = loadMessages(message.projectId).toMutableList()
        current.add(message)
        saveMessages(current)
    }

    fun clearHistory() {
        historyFile.delete()
    }
}