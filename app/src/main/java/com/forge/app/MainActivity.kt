package com.forge.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.forge.app.data.models.ForgeProject
import com.forge.app.databinding.ActivityMainBinding
import com.forge.app.ui.SettingsActivity
import com.forge.app.ui.chat.ChatActivity
import com.forge.app.ui.home.HomeViewModel
import com.forge.app.ui.home.NewProjectDialog
import com.forge.app.ui.home.ProjectAdapter
import com.forge.app.utils.ProjectExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val STATE_PENDING_EXPORT_PROJECT_ID = "pending_export_project_id"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: HomeViewModel by viewModels { HomeViewModel.Factory(application) }
    private lateinit var adapter: ProjectAdapter
    private var pendingCodeExportProjectId: String? = null
    private val exportCodeLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val projectId = pendingCodeExportProjectId
        pendingCodeExportProjectId = null

        if (uri == null || projectId == null) return@registerForActivityResult

        lifecycleScope.launch {
            val project = viewModel.getProject(projectId)
            if (project == null) {
                Toast.makeText(this@MainActivity, "Project not found", Toast.LENGTH_SHORT).show()
                return@launch
            }

            binding.progressBar.visibility = View.VISIBLE
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ProjectExportManager(this@MainActivity).exportProjectSource(project, uri)
                }
            }
            binding.progressBar.visibility = View.GONE

            result.onSuccess {
                Toast.makeText(
                    this@MainActivity,
                    "Code exported to your phone storage",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "Export failed: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        pendingCodeExportProjectId = savedInstanceState?.getString(STATE_PENDING_EXPORT_PROJECT_ID)
        setSupportActionBar(binding.toolbar)
        setupRecyclerView()
        setupFab()
        observeProjects()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_EXPORT_PROJECT_ID, pendingCodeExportProjectId)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupRecyclerView() {
        adapter = ProjectAdapter(
            onProjectClick = { project -> openProject(project) },
            onProjectLongClick = { project -> showProjectOptions(project) }
        )
        binding.rvProjects.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            adapter = this@MainActivity.adapter
        }
    }

    private fun setupFab() {
        binding.fabNewProject.setOnClickListener {
            NewProjectDialog.show(supportFragmentManager) { name, domain, appName ->
                createProject(name, domain, appName)
            }
        }
    }

    private fun observeProjects() {
        lifecycleScope.launch {
            viewModel.projects.collect { projects ->
                adapter.submitList(projects)
                binding.emptyState.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
                binding.rvProjects.visibility = if (projects.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun createProject(name: String, domain: String, appName: String) {
        lifecycleScope.launch {
            try {
                binding.progressBar.visibility = View.VISIBLE
                viewModel.createProject(name, domain, appName)
                Toast.makeText(this@MainActivity, "Project '$name' created!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun openProject(project: ForgeProject) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_PROJECT_ID, project.id)
        })
    }

    private fun showProjectOptions(project: ForgeProject) {
        val options = arrayOf("Rename", "Export Code", "Export APK", "Delete")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(project.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(project)
                    1 -> exportProjectCode(project)
                    2 -> exportProjectApk(project)
                    3 -> confirmDeleteProject(project)
                }
            }.show()
    }

    private fun showRenameDialog(project: ForgeProject) {
        val input = EditText(this).apply {
            setText(project.name)
            setPadding(48, 24, 48, 24)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Rename Project")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        viewModel.renameProject(project, newName)
                        Toast.makeText(this@MainActivity, "Renamed to '$newName'", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun exportProjectCode(project: ForgeProject) {
        pendingCodeExportProjectId = project.id
        exportCodeLauncher.launch(ProjectExportManager.suggestedArchiveName(project))
    }

    private fun exportProjectApk(project: ForgeProject) {
        val apkFile = File(filesDir, "projects/${project.id}/build/output/app-debug.apk")
        if (!apkFile.exists()) {
            Toast.makeText(this, "Build the app first in the project screen", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "${project.name}.apk")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Export APK"))
    }

    private fun confirmDeleteProject(project: ForgeProject) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete ${project.name}?")
            .setMessage("This will permanently delete all project files and source code. This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { viewModel.deleteProject(project) }
            }
            .setNegativeButton("Cancel", null).show()
    }
}
