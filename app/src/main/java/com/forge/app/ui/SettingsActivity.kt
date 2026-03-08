package com.forge.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.forge.app.databinding.ActivitySettingsBinding
import com.forge.app.utils.PermissionHelper
import com.forge.app.utils.SecureStorage

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Settings"
        }
        binding.toolbar.setNavigationOnClickListener { onBackPressed() }

        setupApiKeySection()
        setupBuildSection()
        setupAboutSection()
    }

    private fun setupApiKeySection() {
        val hasKey = SecureStorage.hasApiKey(this)
        binding.tvApiKeyStatus.text = if (hasKey) "✅ API key saved" else "⚠️ No API key"

        binding.btnChangeApiKey.setOnClickListener {
            showApiKeyInput()
        }

        binding.btnClearApiKey.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Clear API Key?")
                .setMessage("You'll need to re-enter your API key to use AI features.")
                .setPositiveButton("Clear") { _, _ ->
                    SecureStorage.clearApiKey(this)
                    binding.tvApiKeyStatus.text = "⚠️ No API key"
                    Toast.makeText(this, "API key cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnGetApiKey.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://console.anthropic.com/settings/keys")))
        }
    }

    private fun setupBuildSection() {
        // Check install permission
        val canInstall = PermissionHelper.canInstallPackages(this)
        binding.tvInstallPermission.text = if (canInstall)
            "✅ Install permission granted"
        else
            "⚠️ Install permission required"

        binding.btnGrantInstallPermission.setOnClickListener {
            PermissionHelper.openInstallPermissionSettings(this)
        }

        // Termux setup
        binding.btnTermuxGuide.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Install Termux Build Tools")
                .setMessage("""
For the best build experience, install Termux from F-Droid and run:

pkg install openjdk-17
pkg install aapt
pkg install apksigner

This gives Forge a complete Java build toolchain. Without it, builds rely on system binaries which may not be available on all devices.

Note: Install Termux from F-Droid (not Play Store) for full functionality.
                """.trimIndent())
                .setPositiveButton("Open F-Droid") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://f-droid.org/packages/com.termux/")))
                }
                .setNegativeButton("Close", null)
                .show()
        }

        // Check Termux availability
        val termuxInstalled = isTermuxInstalled()
        binding.tvTermuxStatus.text = if (termuxInstalled)
            "✅ Termux detected"
        else
            "ℹ️ Termux not installed (optional)"
    }

    private fun setupAboutSection() {
        binding.tvVersion.text = "Forge v1.0 · Powered by Claude Opus"
        binding.btnViewGithub.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://github.com")))
        }
    }

    private fun showApiKeyInput() {
        val input = android.widget.EditText(this).apply {
            hint = "sk-ant-api03-..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Enter API Key")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val key = input.text.toString().trim()
                if (key.startsWith("sk-ant")) {
                    SecureStorage.saveApiKey(this, key)
                    binding.tvApiKeyStatus.text = "✅ API key saved"
                    Toast.makeText(this, "API key updated!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Invalid key format", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isTermuxInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("com.termux", 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}