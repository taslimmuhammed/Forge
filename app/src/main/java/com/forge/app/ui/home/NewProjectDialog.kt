package com.forge.app.ui.home

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.forge.app.databinding.DialogNewProjectBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class NewProjectDialog : DialogFragment() {

    private var onConfirm: ((name: String, domain: String, appName: String) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogNewProjectBinding.inflate(layoutInflater)

        // Auto-fill app name from project name
        binding.etProjectName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val raw = s.toString().trim()
                if (raw.isNotEmpty()) {
                    // Auto-generate safe app name
                    val safeName = raw.lowercase()
                        .replace(Regex("[^a-z0-9]"), "")
                        .take(20)
                    binding.etAppName.setText(safeName)
                }
            }
        })

        // Set default domain
        binding.etDomain.setText("com.example")

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("New Project")
            .setView(binding.root)
            .setPositiveButton("Create") { _, _ ->
                val name = binding.etProjectName.text.toString().trim()
                val domain = binding.etDomain.text.toString().trim()
                val appName = binding.etAppName.text.toString().trim()

                when {
                    name.isEmpty() -> Toast.makeText(context, "Enter a project name", Toast.LENGTH_SHORT).show()
                    domain.isEmpty() -> Toast.makeText(context, "Enter a domain", Toast.LENGTH_SHORT).show()
                    appName.isEmpty() -> Toast.makeText(context, "Enter an app name", Toast.LENGTH_SHORT).show()
                    !domain.contains(".") -> Toast.makeText(context, "Domain must contain a dot (e.g. com.example)", Toast.LENGTH_SHORT).show()
                    appName.contains(" ") -> Toast.makeText(context, "App name cannot contain spaces", Toast.LENGTH_SHORT).show()
                    else -> onConfirm?.invoke(name, domain, appName)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }

    companion object {
        private const val TAG = "NewProjectDialog"

        fun show(
            fragmentManager: FragmentManager,
            onConfirm: (name: String, domain: String, appName: String) -> Unit
        ) {
            NewProjectDialog().apply {
                this.onConfirm = onConfirm
            }.show(fragmentManager, TAG)
        }
    }
}