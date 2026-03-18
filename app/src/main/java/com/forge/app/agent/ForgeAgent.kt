package com.forge.app.agent

import android.content.Context
import android.util.Base64
import android.util.Log
import com.forge.app.data.models.*
import com.forge.app.data.repository.ProjectFileManager
import com.forge.app.utils.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    // Conversation history (in-memory, bounded)
    private val conversationHistory = mutableListOf<ConversationTurn>()

    data class ConversationTurn(val role: String, val content: String)
    data class AnthropicResult(val text: String, val toolUse: JSONObject? = null)

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

## HOW TO RESPOND
You have access to a tool named `apply_project_changes`. 
If the user asks you to modify the project, you MUST CALL `apply_project_changes` with the requested operations.
If the user is only asking a question or no changes are needed, you can just reply with a normal text message.

## RULES FOR PROJECT CHANGES
1. ALWAYS read and update the forgeMdUpdate field.
2. Make MINIMAL changes to achieve the goal.
3. For EVERY "write" operation, use "contentBase64" with UTF-8 file bytes encoded as base64.
4. Paths must be clean relative paths.
5. This pipeline supports Java + XML projects ONLY. Do NOT generate Kotlin, Compose, or Gradle Kotlin DSL code!
6. Keep package names consistent with ${project.packageName}
7. Default to android.app.Activity, not AppCompatActivity.
8. Use ConstraintLayout or LinearLayout for layouts.
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────────
    // MAIN AGENT CALL
    // ─────────────────────────────────────────────────────────────────
    suspend fun processUserRequest(
        userMessage: String,
        buildError: String? = null
    ): Flow<AgentStreamEvent> = flow {
        emit(AgentStreamEvent.Thinking("Reading project state..."))

        val requestContext = buildContext(userMessage, buildError)

        if (conversationHistory.size >= SUMMARY_THRESHOLD) {
            emit(AgentStreamEvent.Thinking("Summarizing context to optimize tokens..."))
            summarizeAndCompress()
        }

        emit(AgentStreamEvent.Thinking("Consulting Claude (Tool Use enabled)..."))

        var lastError: String? = null
        for (attempt in 1..MAX_RETRIES) {
            try {
                val messagesArray = JSONArray()
                val historyToSend = conversationHistory.takeLast(10)
                historyToSend.forEach { turn ->
                    messagesArray.put(JSONObject().apply {
                        put("role", turn.role)
                        put("content", turn.content)
                    })
                }
                messagesArray.put(JSONObject().apply {
                    put("role", "user")
                    put("content", requestContext)
                })

                val anthropicResult = callAnthropic(buildSystemPrompt(), messagesArray)
                
                val parsed: AgentResponse = if (anthropicResult.toolUse != null) {
                    val input = anthropicResult.toolUse
                    input.put("userMessage", input.optString("userMessage", anthropicResult.text))
                    parseStructuredAgentResponse(input.toString())
                } else {
                    AgentResponse(
                        thinking = "",
                        userMessage = anthropicResult.text.ifBlank { "I have reviewed your request but could not determine any file changes to make." },
                        operations = emptyList(),
                        forgeMdUpdate = null
                    )
                }

                conversationHistory.add(
                    ConversationTurn("user", buildConversationUserTurn(userMessage, buildError))
                )
                conversationHistory.add(
                    ConversationTurn("assistant", buildConversationAssistantTurn(parsed))
                )

                if (parsed.needsConfirmation && parsed.confirmationMessage != null) {
                    emit(AgentStreamEvent.NeedsConfirmation(parsed.confirmationMessage, parsed))
                    return@flow
                }

                if (parsed.operations.isEmpty()) {
                    emit(AgentStreamEvent.Complete(parsed))
                    return@flow
                }

                emit(AgentStreamEvent.Thinking("Applying ${parsed.operations.size} file changes..."))
                applyOperations(parsed)

                parsed.forgeMdUpdate?.let { fileManager.writeForgeMd(it) }
                fileManager.updateFileHashes()

                emit(AgentStreamEvent.Complete(parsed))
                return@flow

            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                Log.e(TAG, "API attempt $attempt failed: $lastError", e)
                val retriable = e is IOException
                if (retriable && attempt < MAX_RETRIES) {
                    emit(AgentStreamEvent.Thinking("Retrying... (attempt $attempt)"))
                } else {
                    emit(AgentStreamEvent.Error(lastError ?: "Unknown agent error"))
                    return@flow
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


    private suspend fun callAnthropic(systemPrompt: String, messages: JSONArray): AnthropicResult = withContext(Dispatchers.IO) {
        val apiKey = SecureStorage.getApiKey(context)
            ?: throw IllegalStateException("API key not set")

        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("system", systemPrompt)
            put("messages", messages)
            put("tools", JSONArray().apply {
                put(JSONObject("""
                    {
                      "name": "apply_project_changes",
                      "description": "Applies file modifications to the Android project and updates the forge.md memory file. Use this when you need to make code edits.",
                      "input_schema": {
                        "type": "object",
                        "properties": {
                          "thinking": { "type": "string" },
                          "userMessage": { "type": "string", "description": "Friendly summary to the user." },
                          "operations": {
                            "type": "array",
                            "items": {
                              "type": "object",
                              "properties": {
                                "type": { "type": "string", "enum": ["write", "delete", "mkdir", "rename"] },
                                "path": { "type": "string" },
                                "contentBase64": { "type": "string", "description": "Base64 encoded UTF-8 file bytes" },
                                "content": { "type": "string", "description": "Target string for rename operation" }
                              },
                              "required": ["type", "path"]
                            }
                          },
                          "forgeMdUpdate": { "type": "string", "description": "Full updated forge.md memory content" },
                          "needsConfirmation": { "type": "boolean" },
                          "confirmationMessage": { "type": "string" },
                          "newDependencies": {
                            "type": "array",
                            "items": { "type": "string", "description": "group:artifact:version" }
                          }
                        },
                        "required": ["operations", "userMessage"]
                      }
                    }
                """.trimIndent()))
            })
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
        
        var textResult = ""
        var toolUseObj: JSONObject? = null

        val contentArr = json.optJSONArray("content") ?: JSONArray()
        for (i in 0 until contentArr.length()) {
            val block = contentArr.optJSONObject(i) ?: continue
            val type = block.optString("type")
            if (type == "text") {
                textResult += block.optString("text")
            } else if (type == "tool_use" && block.optString("name") == "apply_project_changes") {
                toolUseObj = block.optJSONObject("input")
            }
        }
        
        if (textResult.isBlank() && toolUseObj == null) {
            throw IOException("No valid text or tool_use in Anthropic response")
        }
        
        AnthropicResult(textResult, toolUseObj)
    }

    // ─────────────────────────────────────────────────────────────────
    // RESPONSE PARSING
    // ─────────────────────────────────────────────────────────────────
    private fun parseStructuredAgentResponse(jsonText: String): AgentResponse {
        val json = JSONObject(jsonText)
        val warnings = mutableListOf<String>()
        val operations = mutableListOf<AgentOperation>()

        json.optJSONArray("operations")?.let { ops ->
            for (i in 0 until ops.length()) {
                val op = ops.optJSONObject(i)
                if (op == null) {
                    warnings += "Skipped operation ${i + 1} because it was not a JSON object."
                    continue
                }

                val typeName = op.optString("type").trim().uppercase()
                val type = runCatching { OperationType.valueOf(typeName) }.getOrElse {
                    warnings += "Skipped operation ${i + 1} because type \"$typeName\" is unsupported."
                    return@getOrElse null
                } ?: continue

                val rawPath = op.optNullableString("path")
                if (rawPath.isNullOrBlank()) {
                    warnings += "Skipped a ${type.name.lowercase()} operation because the path was missing."
                    continue
                }

                val path = runCatching { normalizeOperationPath(rawPath) }.getOrElse { error ->
                    warnings += "Skipped ${type.name.lowercase()} for \"$rawPath\": ${error.message}"
                    null
                } ?: continue

                if (type == OperationType.WRITE && path.endsWith(".kt")) {
                    warnings += "Skipped write for \"$path\" because generated projects currently support Java/XML edits only."
                    continue
                }

                val content = when (type) {
                    OperationType.WRITE -> decodeWriteContent(op, path, warnings)
                    OperationType.RENAME -> {
                        val target = op.optNullableString("content")
                        if (target.isNullOrBlank()) {
                            warnings += "Skipped rename for \"$path\" because the target path was missing."
                            null
                        } else {
                            runCatching { normalizeOperationPath(target) }.getOrElse { error ->
                                warnings += "Skipped rename for \"$path\": ${error.message}"
                                null
                            }
                        }
                    }
                    else -> op.optNullableString("content")
                }

                if (type == OperationType.WRITE && content == null) {
                    continue
                }

                operations += AgentOperation(
                    type = type,
                    path = path,
                    content = content
                )
            }
        }

        val newDeps = mutableListOf<String>()
        json.optJSONArray("newDependencies")?.let { deps ->
            for (i in 0 until deps.length()) {
                deps.optString(i).takeIf { it.isNotBlank() }?.let(newDeps::add)
            }
        }

        return AgentResponse(
            thinking = json.optString("thinking", ""),
            userMessage = json.optString("userMessage", "Done!"),
            operations = operations,
            forgeMdUpdate = json.optNullableString("forgeMdUpdate"),
            needsConfirmation = json.optBoolean("needsConfirmation", false),
            confirmationMessage = json.optNullableString("confirmationMessage"),
            newDependencies = newDeps,
            warningMessage = warnings.distinct().take(3).joinToString("\n").ifBlank { null }
        )
    }

    private fun decodeWriteContent(
        op: JSONObject,
        path: String,
        warnings: MutableList<String>
    ): String? {
        op.optNullableString("contentBase64")?.takeIf { it.isNotBlank() }?.let { encoded ->
            return try {
                String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                warnings += "Skipped write for \"$path\" because contentBase64 was invalid."
                null
            }
        }

        op.optNullableString("content")?.let { return it }
        warnings += "Skipped write for \"$path\" because file content was missing."
        return null
    }



    private fun normalizeOperationPath(path: String): String {
        val normalized = path
            .replace('\\', '/')
            .replace(Regex("""\s*/\s*"""), "/")
            .replace(Regex("/+"), "/")
            .removePrefix("./")
            .trim()
            .trim('/')

        require(normalized.isNotBlank()) { "path is blank" }
        require(
            normalized != ".." &&
                !normalized.startsWith("../") &&
                !normalized.contains("/../")
        ) {
            "path cannot escape the project root"
        }
        return normalized
    }

    private fun JSONObject.optNullableString(name: String): String? {
        val value = opt(name)
        return when (value) {
            null, JSONObject.NULL -> null
            else -> value.toString()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // APPLY OPERATIONS
    // ─────────────────────────────────────────────────────────────────
    private fun applyOperations(response: AgentResponse) {
        response.operations.forEach { op ->
            try {
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
                        val dir = resolveOperationFile(op.path)
                        dir.mkdirs()
                        Log.d(TAG, "Created dir: ${op.path}")
                    }
                    OperationType.RENAME -> {
                        val targetPath = op.content
                            ?: throw IllegalArgumentException("rename target path missing")
                        val src = resolveOperationFile(op.path)
                        val dst = resolveOperationFile(targetPath)
                        dst.parentFile?.mkdirs()

                        if (!src.exists()) {
                            throw IllegalArgumentException("source path does not exist")
                        }

                        val renamed = src.renameTo(dst)
                        if (!renamed) {
                            if (src.isDirectory) {
                                src.copyRecursively(dst, overwrite = true)
                                src.deleteRecursively()
                            } else {
                                src.copyTo(dst, overwrite = true)
                                src.delete()
                            }
                        }
                        Log.d(TAG, "Renamed: ${op.path} -> $targetPath")
                    }
                }
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Failed to apply ${op.type.name.lowercase()} operation for ${op.path}: ${e.message}",
                    e
                )
            }
        }
    }

    private fun resolveOperationFile(relativePath: String): File {
        val normalized = normalizeOperationPath(relativePath)
        val root = fileManager.getProjectRoot().canonicalFile
        val target = File(root, normalized).canonicalFile
        require(
            target.path == root.path || target.path.startsWith(root.path + File.separator)
        ) {
            "Resolved path escapes project root: $relativePath"
        }
        return target
    }

    private fun buildConversationUserTurn(userMessage: String, buildError: String?): String = buildString {
        append(userMessage.trim())
        if (!buildError.isNullOrBlank()) {
            append("\nBuild error context:\n")
            append(buildError.take(800))
        }
    }.trim()

    private fun buildConversationAssistantTurn(response: AgentResponse): String = buildString {
        appendLine(response.userMessage.ifBlank { "Applied project changes." })
        if (response.operations.isNotEmpty()) {
            appendLine("Operations:")
            response.operations.take(8).forEach { operation ->
                appendLine("- ${operation.type.name.lowercase()}: ${operation.path}")
            }
            val remaining = response.operations.size - 8
            if (remaining > 0) {
                appendLine("- ... and $remaining more")
            }
        }
        if (response.newDependencies.isNotEmpty()) {
            appendLine("Dependencies: ${response.newDependencies.joinToString()}")
        }
        response.warningMessage?.takeIf { it.isNotBlank() }?.let { warning ->
            appendLine("Warning: $warning")
        }
    }.trim()

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
