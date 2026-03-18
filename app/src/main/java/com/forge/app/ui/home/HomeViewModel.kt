package com.forge.app.ui.home

import android.app.Application
import androidx.lifecycle.*
import com.forge.app.data.models.ForgeProject
import com.forge.app.data.repository.ProjectRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)

    val projects = repository.allProjects.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )

    fun createProject(name: String, domain: String, appName: String) {
        viewModelScope.launch {
            repository.createProject(name, domain, appName)
        }
    }

    fun deleteProject(project: ForgeProject) {
        viewModelScope.launch {
            repository.deleteProject(project)
        }
    }

    fun renameProject(project: ForgeProject, newName: String) {
        viewModelScope.launch {
            repository.updateProject(project.copy(name = newName, lastModifiedAt = System.currentTimeMillis()))
        }
    }

    suspend fun getProject(id: String): ForgeProject? = repository.getProject(id)

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(application) as T
        }
    }
}
