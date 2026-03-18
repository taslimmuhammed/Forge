package com.forge.app.ui.chat

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Bundle
import android.content.pm.PackageInstaller
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.forge.app.R
import com.forge.app.build_engine.PackageInstallReceiver
import com.forge.app.data.models.AgentResponse
import com.forge.app.data.models.BuildStatus
import com.forge.app.databinding.ActivityChatBinding
import com.forge.app.utils.PermissionHelper
import com.forge.app.utils.ProjectExportManager
import com.forge.app.utils.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
    }

    private lateinit var binding: ActivityChatBinding
    private var latestBuildLog: String = ""
    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(
            application,
            intent.getStringExtra(EXTRA_PROJECT_ID) ?: error("No project ID")
        )
    }
    private lateinit var messageAdapter: MessageAdapter
    private val exportCodeLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val project = viewModel.project.value
        if (uri == null || project == null) return@registerForActivityResult

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ProjectExportManager(this@ChatActivity).exportProjectSource(project, uri)
                }
            }

            result.onSuccess {
                Toast.makeText(
                    this@ChatActivity,
                    "Code exported to your phone storage",
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { error ->
                Toast.makeText(
                    this@ChatActivity,
                    "Export failed: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(PackageInstallReceiver.EXTRA_SUCCESS, false)
            val pkg = intent.getStringExtra(PackageInstallReceiver.EXTRA_PACKAGE_NAME)
            val status = intent.getIntExtra(PackageInstallReceiver.EXTRA_STATUS, -1)
            val statusMessage = intent.getStringExtra(PackageInstallReceiver.EXTRA_STATUS_MESSAGE)

            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                viewModel.addSystemMessagePublic("Install confirmation required. Approve the prompt to continue.")
                Toast.makeText(context, "Please confirm app install", Toast.LENGTH_SHORT).show()
                return
            }

            if (success) {
                viewModel.onInstallSuccess(pkg ?: "")
                Toast.makeText(context, "App installed! Launching...", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.onInstallFailed(statusMessage)
                Toast.makeText(context, "Installation failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        setupChat()
        setupBuildButton()
        setupBuildLogSheet()
        observeViewModel()
        registerInstallReceiver()
        checkInstallPermission()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupChat() {
        messageAdapter = MessageAdapter()
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply { stackFromEnd = true }
            adapter = messageAdapter
        }
        binding.btnSend.setOnClickListener {
            val message = binding.etMessage.text.toString().trim()
            if (message.isEmpty()) return@setOnClickListener
            if (!SecureStorage.hasApiKey(this)) { showApiKeyDialog(); return@setOnClickListener }
            binding.etMessage.setText("")
            viewModel.sendMessage(message)
        }
    }

    private fun setupBuildButton() {
        binding.btnRun.setOnClickListener {
            if (!PermissionHelper.canInstallPackages(this)) {
                showInstallPermissionDialog(); return@setOnClickListener
            }
            viewModel.buildAndRun()
        }
    }

    private fun setupBuildLogSheet() {
        binding.buildLogSheet.setOnClickListener { openFullBuildLog() }
        binding.tvBuildLog.setOnClickListener { openFullBuildLog() }
    }

    private fun checkInstallPermission() {
        if (!PermissionHelper.canInstallPackages(this)) {
            viewModel.addSystemMessagePublic("To install your built apps, go to Settings > Grant Install Permission.")
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.project.collect { project ->
                project?.let {
                    supportActionBar?.title = it.name
                    binding.tvPackageName.text = it.packageName
                    updateBuildButtonState(it.buildStatus)
                    invalidateOptionsMenu()
                }
            }
        }
        lifecycleScope.launch {
            viewModel.messages.collect { messages ->
                messageAdapter.submitList(messages) {
                    if (messages.isNotEmpty()) binding.rvMessages.scrollToPosition(messages.size - 1)
                }
            }
        }
        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                when (state) {
                    is ChatUiState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnSend.isEnabled = true
                        binding.tvStatus.visibility = View.GONE
                    }
                    is ChatUiState.AgentThinking -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.btnSend.isEnabled = false
                        binding.tvStatus.text = state.message
                        binding.tvStatus.visibility = View.VISIBLE
                    }
                    is ChatUiState.Building -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvStatus.text = state.log
                        binding.tvStatus.visibility = View.VISIBLE
                    }
                    is ChatUiState.ConfirmationRequired -> showConfirmationDialog(state.message, state.pendingResponse)
                    is ChatUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnSend.isEnabled = true
                        binding.tvStatus.text = state.message
                        binding.tvStatus.visibility = View.VISIBLE
                    }
                }
            }
        }
        lifecycleScope.launch {
            viewModel.buildLog.collect { log ->
                latestBuildLog = log
                if (log.isNotBlank()) {
                    binding.tvBuildLog.text = previewBuildLog(log)
                    binding.buildLogSheet.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_chat, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressed(); true }
            R.id.action_export_code -> { exportCode(); true }
            R.id.action_export_apk -> { exportApk(); true }
            R.id.action_clear_chat -> { confirmClearChat(); true }
            R.id.action_settings -> {
                startActivity(Intent(this, com.forge.app.ui.SettingsActivity::class.java)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportCode() {
        val project = viewModel.project.value ?: return
        exportCodeLauncher.launch(ProjectExportManager.suggestedArchiveName(project))
    }

    private fun exportApk() {
        val project = viewModel.project.value ?: return
        val apkFile = File(filesDir, "projects/${project.id}/build/output/app-debug.apk")
        if (!apkFile.exists()) {
            Toast.makeText(this, "Build the app first (tap Run)", Toast.LENGTH_SHORT).show(); return
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

    private fun confirmClearChat() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear Chat History?")
            .setMessage("This clears the conversation but keeps your project files and code.")
            .setPositiveButton("Clear") { _, _ -> viewModel.clearChatHistory() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun updateBuildButtonState(status: BuildStatus) {
        when (status) {
            BuildStatus.BUILDING, BuildStatus.INSTALLING -> {
                binding.btnRun.text = "Building..."; binding.btnRun.isEnabled = false
            }
            BuildStatus.INSTALLED -> { binding.btnRun.text = "Run Again"; binding.btnRun.isEnabled = true }
            else -> { binding.btnRun.text = "Run"; binding.btnRun.isEnabled = true }
        }
    }

    private fun showApiKeyDialog() {
        ApiKeyDialog.show(supportFragmentManager) { apiKey ->
            SecureStorage.saveApiKey(this, apiKey)
            Toast.makeText(this, "API key saved! Now tap Send again.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInstallPermissionDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Install Permission Required")
            .setMessage("To install your built app, Forge needs permission to install packages. Tap Open Settings and allow it.")
            .setPositiveButton("Open Settings") { _, _ -> PermissionHelper.openInstallPermissionSettings(this) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showConfirmationDialog(message: String, pendingResponse: AgentResponse) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Confirm Changes")
            .setMessage(message)
            .setPositiveButton("Proceed") { _, _ -> viewModel.confirmPendingOperation(pendingResponse) }
            .setNegativeButton("Cancel") { _, _ -> viewModel.cancelPendingOperation() }.show()
    }

    private fun openFullBuildLog() {
        if (latestBuildLog.isBlank()) return
        val textView = TextView(this).apply {
            text = latestBuildLog
            textSize = 12f
            setTextColor(0xFF58A6FF.toInt())
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(32, 24, 32, 24)
        }
        val scrollView = ScrollView(this).apply { addView(textView) }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Full Build Log")
            .setView(scrollView)
            .setPositiveButton("Copy") { _, _ -> copyBuildLog(latestBuildLog) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun previewBuildLog(log: String): String {
        val trimmed = log.trimEnd()
        val maxChars = 6000
        val body = if (trimmed.length > maxChars) {
            "... (showing last $maxChars chars)\n${trimmed.takeLast(maxChars)}"
        } else {
            trimmed
        }
        return "$body\n\n(Tap to open full log)"
    }

    private fun copyBuildLog(log: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Forge Build Log", log))
        Toast.makeText(this, "Build log copied", Toast.LENGTH_SHORT).show()
    }

    private fun registerInstallReceiver() {
        val filter = IntentFilter(PackageInstallReceiver.INSTALL_RESULT_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(installReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(installReceiver) } catch (e: Exception) {}
    }
}
