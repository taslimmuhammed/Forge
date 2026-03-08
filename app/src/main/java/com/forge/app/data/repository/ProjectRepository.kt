package com.forge.app.data.repository

import android.content.Context
import com.forge.app.data.db.ForgeDatabase
import com.forge.app.data.models.BuildStatus
import com.forge.app.data.models.ForgeProject
import kotlinx.coroutines.flow.Flow
import java.io.File

class ProjectRepository(private val context: Context) {

    private val dao = ForgeDatabase.getInstance(context).projectDao()
    private val projectsRoot = File(context.filesDir, "projects")

    init {
        projectsRoot.mkdirs()
    }

    val allProjects: Flow<List<ForgeProject>> = dao.getAllProjects()

    suspend fun createProject(name: String, domain: String, appName: String): ForgeProject {
        val packageName = "${domain.lowercase()}.${appName.lowercase()}"
        val project = ForgeProject(
            name = name,
            packageName = packageName,
            domain = domain.lowercase(),
            appName = appName.lowercase()
        )
        // Create directory structure
        ProjectFileManager(context, project).initializeProjectStructure()
        dao.insertProject(project)
        return project
    }

    suspend fun getProject(id: String): ForgeProject? = dao.getProjectById(id)

    suspend fun updateProject(project: ForgeProject) = dao.updateProject(project)

    suspend fun deleteProject(project: ForgeProject) {
        // Delete files
        val dir = File(projectsRoot, project.id)
        dir.deleteRecursively()
        dao.deleteProject(project)
    }

    suspend fun updateBuildStatus(id: String, status: BuildStatus) {
        dao.updateBuildStatus(id, status)
    }

    suspend fun markBuilt(id: String, success: Boolean) {
        dao.markBuilt(
            id,
            System.currentTimeMillis(),
            if (success) BuildStatus.BUILD_SUCCESS else BuildStatus.BUILD_FAILED
        )
    }
}