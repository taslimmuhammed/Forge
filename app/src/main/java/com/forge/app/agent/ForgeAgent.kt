package com.forge.app.agent

import android.content.Context
import android.util.Log
import com.forge.app.data.models.*
import com.forge.app.data.repository.ProjectFileManager
import com.forge.app.utils.SecureStorage
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ForgeAgent(
    private val context: Context,
    private val fileManager: ProjectFileManager,
    private val project: ForgeProject
) {
    companion object {
        private const val TAG = "ForgeAgent"
        private const val ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-opus-4-6"
        private const val MAX_TOKENS = 4096
        private const val MAX_RETRIES = 3
        private const val SUMMARY_THRESHOLD = 8  // Summarize after this many turns
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Conversation history (in-memory, bounded)
    private val conversationHistory = mutableListOf<ConversationTurn>()

    data class ConversationTurn(val role: String, val content: String)

    // ─────────────────────────────────────────────────────────────────
    // SYSTEM PROMPT
    // ─────────────────────────────────────────────────────────────────
    private fun buildSystemPrompt(): String = """
You are Forge, an expert Android developer agent embedded inside the Forge app builder.
You autonomously modify Android projects based on natural language instructions.

## YOUR CAPABILITIES
- Write/modify/delete Java files, XML layouts, resources, manifests
- Add Android dependencies (build.gradle)  
- Create new Activities, Fragments, Services
- Implement any Android feature: networking, databases, UI, animations, etc.

## STRICT OUTPUT FORMAT
You MUST respond with ONLY a JSON object. No markdown, no explanation outside JSON.

{
  "thinking": "Your internal reasoning (1-2 sentences)",
  "userMessage": "What you tell the user (friendly, brief, no code shown)",
  "operations": [
    {"type": "write", "path": "relative/path/from/project/root/file.java", "content": "full file content"},
    {"type": "delete", "path": "relative/path/to/delete"},
    {"type": "mkdir", "path": "relative/path/to/create"}
  ],
  "forgeMdUpdate": "Complete updated forge.md content (always update this)",
  "needsConfirmation": false,
  "confirmationMessage": null,
  "newDependencies": ["groupId:artifactId:version"]
}

## RULES
1. ALWAYS read and update forge.md - it is your memory
2. Make MINIMAL changes to achieve the goal — don't rewrite what isn't broken
3. Always provide complete file content in "write" operations (not diffs)
4. Keep package names consistent with ${project.packageName}
5. When adding dependencies, add to app/build.gradle AND list in forgeMdUpdate
6. If a change could break something, set needsConfirmation=true  
7. Never show raw code to users in userMessage — describe what you did instead
8. Prefer Java over Kotlin for generated code (better ECJ compatibility)
9. All layouts should use ConstraintLayout or LinearLayout for simplicity
10. When fixing build errors, focus ONLY on the error — don't refactor other code

## ANDROID CODING STANDARDS
- Target API 26+ features when possible
- Always declare Activities in AndroidManifest.xml
- Use R.layout.xxx for setContentView, not hardcoded IDs
- Prefer AppCompat variants (AppCompatActivity, etc.)
- For networking: use OkHttp or HttpURLConnection (no Retrofit unless requested)
- For database: use SQLiteOpenHelper or Room (if user requests)
- String literals go in res/values/strings.xml
- Colors go in res/values/colors.xml
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────
    // MAIN AGENT CALL
    // ─────────────────────────────────────────────────────────────────
    suspend fun processUserRequest(
        userMessage: String,
        buildError: String? = null
    ): Flow<AgentStreamEvent> = flow {
        emit(AgentStreamEvent.Thinking("Reading project state..."))

        // 1. Build context
        val context = buildContext(userMessage, buildError)

        // 2. Check if summarization needed
        if (conversationHistory.size >= SUMMARY_THRESHOLD) {
            emit(AgentStreamEvent.Thinking("Summarizing context to optimize tokens..."))
            summarizeAndCompress()
        }

        // 3. Add user message to history
        conversationHistory.add(ConversationTurn("user", context))

        emit(AgentStreamEvent.Thinking("Consulting Claude Opus..."))

        // 4. Call Claude API with retry logic
        var lastError: String? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val response = callClaudeAPI()
                val parsed = parseAgentResponse(response)

                // 5. Add assistant response to history
                conversationHistory.add(ConversationTurn("assistant", response))

                // 6. Handle confirmation if needed
                if (parsed.needsConfirmation && parsed.confirmationMessage != null) {
                    emit(AgentStreamEvent.NeedsConfirmation(parsed.confirmationMessage, parsed))
                    return@flow
                }

                // 7. Apply file operations
                emit(AgentStreamEvent.Thinking("Applying ${parsed.operations.size} file changes..."))
                applyOperations(parsed)

                // 8. Update forge.md
                parsed.forgeMdUpdate?.let { fileManager.writeForgeMd(it) }

                // 9. Update file hashes
                fileManager.updateFileHashes()

                // 10. Emit completion
                emit(AgentStreamEvent.Complete(parsed))
                return@flow

            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                Log.e(TAG, "API attempt $attempt failed: $lastError", e)
                if (attempt < MAX_RETRIES) {
                    emit(AgentStreamEvent.Thinking("Retrying... (attempt $attempt)"))
                }
            }
        }

        emit(AgentStreamEvent.Error("Failed after $MAX_RETRIES attempts: $lastError"))
    }

    // ─────────────────────────────────────────────────────────────────
    // CONTEXT BUILDING (Token-Optimized)
    // ─────────────────────────────────────────────────────────────────
    private fun buildContext(userMessage: String, buildError: String?): String {
        val sb = StringBuilder()

        // Always include forge.md
        sb.appendLine("## FORGE.MD (Project Memory)")
        sb.appendLine(fileManager.readForgeMd())
        sb.appendLine()

        // Include context summary if exists
        val summary = fileManager.readContextSummary()
        if (summary != "{}" && summary.isNotBlank()) {
            sb.appendLine("## PREVIOUS CONTEXT SUMMARY")
            sb.appendLine(summary)
            sb.appendLine()
        }

        // Include build error if any (auto-repair mode)
        if (buildError != null) {
            sb.appendLine("## BUILD ERROR (AUTO-REPAIR MODE)")
            sb.appendLine("The following build error occurred. Fix it:")
            sb.appendLine("```")
            sb.appendLine(buildError.take(3000)) // Limit error length
            sb.appendLine("```")
            sb.appendLine()
        }

        // Smart file inclusion: only changed files or files referenced in request
        val allFiles = fileManager.getAllSourceFiles()
        val relevantFiles = selectRelevantFiles(allFiles, userMessage)

        if (relevantFiles.isNotEmpty()) {
            sb.appendLine("## RELEVANT PROJECT FILES")
            relevantFiles.forEach { (path, content) ->
                sb.appendLine("### $path")
                sb.appendLine("```")
                sb.appendLine(content.take(5000)) // Limit per file
                sb.appendLine("```")
                sb.appendLine()
            }
        }

        // User request
        sb.appendLine("## USER REQUEST")
        sb.appendLine(userMessage)

        return sb.toString()
    }

    private fun selectRelevantFiles(
        allFiles: Map<String, String>,
        userMessage: String
    ): Map<String, String> {
        val relevant = mutableMapOf<String, String>()
        val messageLower = userMessage.lowercase()

        // Always include manifest and main layout
        allFiles.keys.filter {
            it.contains("AndroidManifest") ||
                    it.contains("activity_main.xml") ||
                    it.contains("MainActivity") ||
                    it.contains("build.gradle") && it.contains("app/build")
        }.forEach { key ->
            allFiles[key]?.let { relevant[key] = it }
        }

        // Include files mentioned by name in the request
        allFiles.forEach { (path, content) ->
            val filename = path.substringAfterLast("/").lowercase()
            if (messageLower.contains(filename.substringBeforeLast("."))) {
                relevant[path] = content
            }
        }

        // If request mentions specific UI elements, include all layouts
        if (messageLower.contains("layout") || messageLower.contains("ui") ||
            messageLower.contains("screen") || messageLower.contains("button")) {
            allFiles.filter { it.key.contains("/res/layout/") }.forEach { (k, v) ->
                relevant[k] = v
            }
        }

        return relevant
    }

    // ─────────────────────────────────────────────────────────────────
    // CLAUDE API CALL
    // ─────────────────────────────────────────────────────────────────
    private suspend fun callClaudeAPI(): String = withContext(Dispatchers.IO) {
        val apiKey = SecureStorage.getApiKey(context)
            ?: throw IllegalStateException("API key not set")

        val messages = JSONArray()

        // Add history (bounded to last 10 turns to control tokens)
        val historyToSend = conversationHistory.takeLast(10)
        historyToSend.forEach { turn ->
            messages.put(JSONObject().apply {
                put("role", turn.role)
                put("content", turn.content)
            })
        }

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("system", buildSystemPrompt())
            put("messages", messages)
        }

        val request = Request.Builder()
            .url(ANTHROPIC_API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error body"
            throw IOException("API error ${response.code}: $errorBody")
        }

        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        val json = JSONObject(responseBody)
        json.getJSONArray("content").getJSONObject(0).getString("text")
    }

    // ─────────────────────────────────────────────────────────────────
    // RESPONSE PARSING
    // ─────────────────────────────────────────────────────────────────
    private fun parseAgentResponse(rawResponse: String): AgentResponse {
        // Strip markdown code fences if present
        val cleaned = rawResponse
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val json = JSONObject(cleaned)
            val operations = mutableListOf<AgentOperation>()

            json.optJSONArray("operations")?.let { ops ->
                for (i in 0 until ops.length()) {
                    val op = ops.getJSONObject(i)
                    operations.add(
                        AgentOperation(
                            type = OperationType.valueOf(
                                op.getString("type").uppercase()
                            ),
                            path = op.getString("path"),
                            content = op.optString("content", null)
                        )
                    )
                }
            }

            val newDeps = mutableListOf<String>()
            json.optJSONArray("newDependencies")?.let { deps ->
                for (i in 0 until deps.length()) {
                    newDeps.add(deps.getString(i))
                }
            }

            AgentResponse(
                thinking = json.optString("thinking", ""),
                userMessage = json.optString("userMessage", "Done!"),
                operations = operations,
                forgeMdUpdate = json.optString("forgeMdUpdate", null),
                needsConfirmation = json.optBoolean("needsConfirmation", false),
                confirmationMessage = json.optString("confirmationMessage", null),
                newDependencies = newDeps
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: $rawResponse", e)
            AgentResponse(userMessage = "Error parsing response: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // APPLY OPERATIONS
    // ─────────────────────────────────────────────────────────────────
    private fun applyOperations(response: AgentResponse) {
        response.operations.forEach { op ->
            when (op.type) {
                OperationType.WRITE -> {
                    op.content?.let { content ->
                        fileManager.writeFile(op.path, content)
                        Log.d(TAG, "Written: ${op.path}")
                    }
                }
                OperationType.DELETE -> {
                    fileManager.deleteFile(op.path)
                    Log.d(TAG, "Deleted: ${op.path}")
                }
                OperationType.MKDIR -> {
                    val dir = java.io.File(fileManager.getProjectRoot(), op.path)
                    dir.mkdirs()
                    Log.d(TAG, "Created dir: ${op.path}")
                }
                OperationType.RENAME -> {
                    // Handle rename
                    val src = java.io.File(fileManager.getProjectRoot(), op.path)
                    val dst = java.io.File(fileManager.getProjectRoot(), op.content ?: "")
                    src.renameTo(dst)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // CONTEXT SUMMARIZATION (Token Optimization)
    // ─────────────────────────────────────────────────────────────────
    private suspend fun summarizeAndCompress() {
        if (conversationHistory.size < 4) return

        val historyText = conversationHistory.dropLast(2)
            .joinToString("\n---\n") { "${it.role}: ${it.content.take(500)}" }

        // Keep last 2 turns, replace rest with summary
        val recentTurns = conversationHistory.takeLast(2).toMutableList()
        conversationHistory.clear()
        conversationHistory.addAll(recentTurns)

        // Write a brief summary to context_summary.json
        val summary = """
{
  "summary": "Conversation summarized at ${java.util.Date()}",
  "keyDecisions": "See forge.md for full project state",
  "turnsProcessed": ${historyText.length}
}
        """.trimIndent()
        fileManager.writeContextSummary(summary)
    }

    // Apply confirmed operations after user confirmation
    suspend fun applyConfirmedOperations(response: AgentResponse): Flow<AgentStreamEvent> = flow {
        emit(AgentStreamEvent.Thinking("Applying confirmed changes..."))
        applyOperations(response)
        response.forgeMdUpdate?.let { fileManager.writeForgeMd(it) }
        fileManager.updateFileHashes()
        emit(AgentStreamEvent.Complete(response))
    }

    fun clearHistory() {
        conversationHistory.clear()
    }
}

// ─────────────────────────────────────────────────────────────────
// Stream Events
// ─────────────────────────────────────────────────────────────────
sealed class AgentStreamEvent {
    data class Thinking(val message: String) : AgentStreamEvent()
    data class Complete(val response: AgentResponse) : AgentStreamEvent()
    data class Error(val message: String) : AgentStreamEvent()
    data class NeedsConfirmation(
        val message: String,
        val pendingResponse: AgentResponse
    ) : AgentStreamEvent()
}