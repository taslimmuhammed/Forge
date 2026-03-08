package com.forge.app.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "projects")
data class ForgeProject(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val packageName: String,          // e.g. com.example.myapp
    val domain: String,               // e.g. com.example
    val appName: String,              // e.g. myapp
    val createdAt: Long = System.currentTimeMillis(),
    val lastModifiedAt: Long = System.currentTimeMillis(),
    val lastBuiltAt: Long? = null,
    val buildStatus: BuildStatus = BuildStatus.NEVER_BUILT,
    val installedVersionCode: Int = 0,
    val description: String = "",
    val iconColor: Int = 0xFF6200EE.toInt()  // Material purple default
)

enum class BuildStatus {
    NEVER_BUILT,
    BUILDING,
    BUILD_SUCCESS,
    BUILD_FAILED,
    INSTALLING,
    INSTALLED,
    INSTALL_FAILED
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val buildTriggered: Boolean = false,
    val attachedFiles: List<String> = emptyList()  // file paths modified
)

enum class MessageRole {
    USER, ASSISTANT, SYSTEM, BUILD_LOG
}

data class AgentOperation(
    val type: OperationType,
    val path: String,
    val content: String? = null
)

enum class OperationType {
    WRITE, DELETE, MKDIR, RENAME
}

data class AgentResponse(
    val thinking: String = "",
    val operations: List<AgentOperation> = emptyList(),
    val forgeMdUpdate: String? = null,
    val needsConfirmation: Boolean = false,
    val confirmationMessage: String? = null,
    val newDependencies: List<String> = emptyList(),
    val userMessage: String = ""
)

data class BuildResult(
    val success: Boolean,
    val apkPath: String? = null,
    val errorLog: String = "",
    val warningLog: String = "",
    val durationMs: Long = 0
)

data class DependencySpec(
    val groupId: String,
    val artifactId: String,
    val version: String
) {
    val coordinate: String get() = "$groupId:$artifactId:$version"
    val cacheKey: String get() = "${groupId}_${artifactId}_$version"
}