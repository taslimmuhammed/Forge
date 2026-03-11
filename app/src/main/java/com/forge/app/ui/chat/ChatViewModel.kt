package com.forge.app.ui.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.forge.app.agent.AgentStreamEvent
import com.forge.app.agent.ForgeAgent
import com.forge.app.build_engine.BuildEngine
import com.forge.app.build_engine.BuildEvent
import com.forge.app.build_engine.BuildService
import com.forge.app.build_engine.VersionManager
import com.forge.app.data.models.*
import com.forge.app.data.repository.ChatHistoryManager
import com.forge.app.data.repository.ProjectFileManager
import com.forge.app.data.repository.ProjectRepository
import com.forge.app.utils.SecureStorage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

sealed class ChatUiState {
    object Idle : ChatUiState()
    data class AgentThinking(val message: String) : ChatUiState()
    data class Building(val log: String) : ChatUiState()
    data class ConfirmationRequired(val message: String, val pendingResponse: AgentResponse) : ChatUiState()
    data class Error(val message: String) : ChatUiState()
}

class ChatViewModel(
    application: Application,
    private val projectId: String
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ForgeBuildLog"
        private const val LOGCAT_CHUNK_SIZE = 3500
    }

    private val repository = ProjectRepository(application)
    private var fileManager: ProjectFileManager? = null
    private var agent: ForgeAgent? = null
    private var buildEngine: BuildEngine? = null
    private var chatHistoryManager: ChatHistoryManager? = null

    private val _project = MutableStateFlow<ForgeProject?>(null)
    val project: StateFlow<ForgeProject?> = _project.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Idle)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _buildLog = MutableStateFlow("")
    val buildLog: StateFlow<String> = _buildLog.asStateFlow()

    private var lastBuildError: String? = null

    init { loadProject() }

    private fun loadProject() {
        viewModelScope.launch {
            val proj = repository.getProject(projectId) ?: return@launch
            _project.value = proj

            fileManager = ProjectFileManager(getApplication(), proj)
            agent = ForgeAgent(getApplication(), fileManager!!, proj)
            buildEngine = BuildEngine(getApplication(), fileManager!!)
            chatHistoryManager = ChatHistoryManager(getApplication(), projectId)
            val repairedBoilerplate = fileManager!!.migrateLegacyBoilerplateIfNeeded()

            // Load persisted chat history
            val history = chatHistoryManager!!.loadMessages(projectId)
            if (history.isNotEmpty()) {
                _messages.value = history
                if (repairedBoilerplate) {
                    addSystemMessage(
                        "Updated project boilerplate for offline build compatibility. Tap Run again."
                    )
                }
            } else {
                addSystemMessage("Project loaded: **${proj.name}**\n\nTap ▶ Run to build and install the boilerplate app, or describe what you want to build!")
                if (repairedBoilerplate) {
                    addSystemMessage(
                        "Project boilerplate was repaired for offline build compatibility."
                    )
                }
            }
        }
    }

    fun sendMessage(userText: String) {
        viewModelScope.launch {
            addUserMessage(userText)
            agent?.processUserRequest(userText, lastBuildError)?.collect { event ->
                when (event) {
                    is AgentStreamEvent.Thinking -> _uiState.value = ChatUiState.AgentThinking(event.message)
                    is AgentStreamEvent.Complete -> {
                        lastBuildError = null
                        addAssistantMessage(event.response.userMessage, event.response.operations)
                        if (event.response.newDependencies.isNotEmpty()) {
                            addSystemMessage("Adding dependencies: ${event.response.newDependencies.joinToString()}")
                        }
                        _uiState.value = ChatUiState.Idle
                        if (event.response.operations.isNotEmpty()) {
                            addSystemMessage("Files updated. Tap Run to build and test your changes.")
                        }
                    }
                    is AgentStreamEvent.Error -> {
                        addErrorMessage(event.message)
                        _uiState.value = ChatUiState.Idle
                    }
                    is AgentStreamEvent.NeedsConfirmation -> {
                        _uiState.value = ChatUiState.ConfirmationRequired(event.message, event.pendingResponse)
                    }
                }
            }
        }
    }

    fun buildAndRun() {
        viewModelScope.launch {
            val proj = _project.value ?: return@launch
            val engine = buildEngine ?: return@launch
            val fm = fileManager ?: return@launch

            // Bump version code so reinstall works
            VersionManager(fm).bumpVersionInBuildGradle()

            // Start foreground service to keep process alive during long builds
            BuildService.startBuild(getApplication(), projectId, proj.packageName)

            repository.updateBuildStatus(projectId, BuildStatus.BUILDING)
            _project.value = proj.copy(buildStatus = BuildStatus.BUILDING)

            val buildLogBuffer = StringBuilder()
            addSystemMessage("Starting build for ${proj.name}...")

            engine.build(proj.packageName).collect { event ->
                when (event) {
                    is BuildEvent.Log -> {
                        buildLogBuffer.appendLine(event.message)
                        _buildLog.value = buildLogBuffer.toString()
                        _uiState.value = ChatUiState.Building(event.message)
                    }
                    is BuildEvent.Success -> {
                        repository.markBuilt(projectId, true)
                        _project.value = proj.copy(buildStatus = BuildStatus.BUILD_SUCCESS)
                        _uiState.value = ChatUiState.Idle
                        lastBuildError = null
                        addSystemMessage("Build successful! Installing on device...")

                        event.result.apkPath?.let { apkPath ->
                            repository.updateBuildStatus(projectId, BuildStatus.INSTALLING)
                            engine.installApk(getApplication(), apkPath) { success ->
                                viewModelScope.launch {
                                    if (!success) {
                                        onInstallFailed("Could not start package install session.")
                                    }
                                }
                            }
                        }
                    }
                    is BuildEvent.Error -> {
                        repository.markBuilt(projectId, false)
                        _project.value = proj.copy(buildStatus = BuildStatus.BUILD_FAILED)
                        lastBuildError = event.message
                        buildLogBuffer.appendLine("ERROR: ${event.message}")
                        _buildLog.value = buildLogBuffer.toString()
                        addErrorMessage("Build failed:\n${event.message}")
                        logLongError("Build failed for ${proj.packageName}:\n${event.message}")
                        logLongError("Full build log:\n${buildLogBuffer}")
                        if (event.message.contains("AppCompatActivity", ignoreCase = true) &&
                            event.message.contains("cannot be resolved", ignoreCase = true)
                        ) {
                            addSystemMessage(
                                "Dependency download likely failed (`androidx.appcompat`). " +
                                        "For offline builds, use `android.app.Activity` instead of `AppCompatActivity` " +
                                        "or reconnect internet and rebuild."
                            )
                        }
                        _uiState.value = ChatUiState.Idle
                        // Only attempt auto-repair if we have an API key AND
                        // it's a code/compile error (not a missing-tool error)
                        val hasKey = SecureStorage.hasApiKey(getApplication())
                        val isToolError = event.message.contains("NO_COMPILER") ||
                                event.message.contains("No Java compiler") ||
                                event.message.contains("Install Termux") ||
                                event.message.contains("not found") &&
                                event.message.contains("compiler")
                        if (hasKey && !isToolError) {
                            autoRepairBuildError(event.message)
                        }
                    }
                    is BuildEvent.Progress -> {}
                }
            }
        }
    }

    private fun autoRepairBuildError(error: String) {
        // Never attempt auto-repair without an API key
        if (!com.forge.app.utils.SecureStorage.hasApiKey(getApplication())) {
            if (error.contains("javac", ignoreCase = true) ||
                error.contains("compiler", ignoreCase = true) ||
                error.contains("inaccessible or not found", ignoreCase = true) ||
                error.contains("No Java", ignoreCase = true)) {
                addSystemMessage(
                    "Java compilation failed on device.\n\n" +
                            "The bundled ECJ compiler encountered an error. " +
                            "Please check the build log for details.\n\n" +
                            "If the issue persists, try clearing the app data and rebuilding.\n\n" +
                            "Alternatively, enter your API key so Forge can help debug build issues."
                )
            } else {
                addErrorMessage("Build failed. Enter your API key so Forge can automatically fix build errors.")
            }
            return
        }

        viewModelScope.launch {
            addSystemMessage("Attempting automatic fix...")
            agent?.processUserRequest("Fix the build error automatically", error)?.collect { event ->
                when (event) {
                    is AgentStreamEvent.Complete -> {
                        addAssistantMessage(event.response.userMessage, event.response.operations)
                        lastBuildError = null
                        _uiState.value = ChatUiState.Idle
                        addSystemMessage("Fix applied. Tap Run to rebuild.")
                    }
                    is AgentStreamEvent.Error -> {
                        addErrorMessage("Auto-repair failed: ${event.message}")
                        _uiState.value = ChatUiState.Idle
                    }
                    is AgentStreamEvent.Thinking -> _uiState.value = ChatUiState.AgentThinking(event.message)
                    else -> {}
                }
            }
        }
    }

    fun confirmPendingOperation(response: AgentResponse) {
        viewModelScope.launch {
            agent?.applyConfirmedOperations(response)?.collect { event ->
                when (event) {
                    is AgentStreamEvent.Complete -> {
                        addAssistantMessage(event.response.userMessage, event.response.operations)
                        _uiState.value = ChatUiState.Idle
                    }
                    else -> {}
                }
            }
        }
    }

    fun cancelPendingOperation() {
        addSystemMessage("Operation cancelled.")
        _uiState.value = ChatUiState.Idle
    }

    fun onInstallSuccess(packageName: String) {
        viewModelScope.launch {
            repository.updateBuildStatus(projectId, BuildStatus.INSTALLED)
            _project.value = _project.value?.copy(buildStatus = BuildStatus.INSTALLED)
            addSystemMessage("App installed and launched!")
            _uiState.value = ChatUiState.Idle
        }
    }

    fun onInstallFailed(reason: String? = null) {
        viewModelScope.launch {
            repository.updateBuildStatus(projectId, BuildStatus.INSTALL_FAILED)
            _project.value = _project.value?.copy(buildStatus = BuildStatus.INSTALL_FAILED)
            val suffix = reason?.takeIf { it.isNotBlank() }?.let { "\nReason: $it" } ?: ""
            val baseMessage = if (reason?.contains("INSTALL_PARSE_FAILED_NO_CERTIFICATES", ignoreCase = true) == true) {
                "Installation failed due to invalid APK certificates. " +
                        "Please update to the latest Forge build and run again."
            } else {
                "Installation failed. Go to Settings > Grant Install Permission, then tap Run again."
            }
            addErrorMessage(
                "$baseMessage$suffix"
            )
            _uiState.value = ChatUiState.Idle
        }
    }

    fun clearChatHistory() {
        chatHistoryManager?.clearHistory()
        agent?.clearHistory()
        _messages.value = emptyList()
        addSystemMessage("Chat history cleared. Project files are untouched.")
    }

    // Public wrapper so ChatActivity can call it
    fun addSystemMessagePublic(text: String) = addSystemMessage(text)

    private fun addUserMessage(text: String) = addMessage(
        ChatMessage(projectId = projectId, role = MessageRole.USER, content = text)
    )

    private fun addAssistantMessage(text: String, operations: List<AgentOperation> = emptyList()) = addMessage(
        ChatMessage(
            projectId = projectId, role = MessageRole.ASSISTANT, content = text,
            attachedFiles = operations.filter { it.type == OperationType.WRITE }.map { it.path }
        )
    )

    private fun addSystemMessage(text: String) = addMessage(
        ChatMessage(projectId = projectId, role = MessageRole.SYSTEM, content = text)
    )

    private fun addErrorMessage(text: String) = addMessage(
        ChatMessage(projectId = projectId, role = MessageRole.SYSTEM, content = text, isError = true)
    )

    private fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
        chatHistoryManager?.appendMessage(message)
    }

    private fun logLongError(message: String) {
        if (message.isBlank()) return
        val chunks = message.chunked(LOGCAT_CHUNK_SIZE)
        chunks.forEachIndexed { index, chunk ->
            Log.e(TAG, "[${index + 1}/${chunks.size}] $chunk")
        }
    }

    class Factory(private val application: Application, private val projectId: String) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(application, projectId) as T
        }
    }
}
