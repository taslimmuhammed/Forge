package com.forge.app.ui.chat

import android.app.Dialog
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.forge.app.databinding.DialogApiKeyBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ApiKeyDialog : DialogFragment() {

    private var onSave: ((String) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = DialogApiKeyBinding.inflate(layoutInflater)

        binding.etApiKey.transformationMethod = PasswordTransformationMethod.getInstance()
        binding.cbShowKey.setOnCheckedChangeListener { _, isChecked ->
            binding.etApiKey.transformationMethod =
                if (isChecked) null else PasswordTransformationMethod.getInstance()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("🔑 Enter Anthropic API Key")
            .setMessage("Your API key is stored securely on this device and never sent anywhere except directly to Anthropic's servers.")
            .setView(binding.root)
            .setPositiveButton("Save & Continue") { _, _ ->
                val key = binding.etApiKey.text.toString().trim()
                if (key.isEmpty() || !key.startsWith("sk-ant")) {
                    Toast.makeText(context, "Enter a valid API key (starts with sk-ant-...)", Toast.LENGTH_SHORT).show()
                } else {
                    onSave?.invoke(key)
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Get API Key") { _, _ ->
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://console.anthropic.com/settings/keys"))
                startActivity(intent)
            }
            .create()
    }

    companion object {
        private const val TAG = "ApiKeyDialog"

        fun show(fragmentManager: FragmentManager, onSave: (String) -> Unit) {
            ApiKeyDialog().apply {
                this.onSave = onSave
            }.show(fragmentManager, TAG)
        }
    }
}