package com.forge.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.forge.app.data.models.ForgeProject
import com.forge.app.databinding.ActivityMainBinding
import com.forge.app.ui.SettingsActivity
import com.forge.app.ui.chat.ChatActivity
import com.forge.app.ui.home.HomeViewModel
import com.forge.app.ui.home.NewProjectDialog
import com.forge.app.ui.home.ProjectAdapter
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: HomeViewModel by viewModels { HomeViewModel.Factory(application) }
    private lateinit var adapter: ProjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        setupRecyclerView()
        setupFab()
        observeProjects()
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
        val options = arrayOf("Rename", "Export APK", "Delete")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(project.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(project)
                    1 -> openProjectForExport(project)
                    2 -> confirmDeleteProject(project)
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

    private fun openProjectForExport(project: ForgeProject) {
        // Open chat screen where user can tap Export APK from the menu
        openProject(project)
        Toast.makeText(this, "Tap the menu (⋮) > Export APK in the project screen", Toast.LENGTH_LONG).show()
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