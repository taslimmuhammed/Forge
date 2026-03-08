package com.forge.app.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.forge.app.data.models.BuildStatus
import com.forge.app.data.models.ForgeProject
import com.forge.app.databinding.ItemProjectCardBinding
import java.text.SimpleDateFormat
import java.util.*

class ProjectAdapter(
    private val onProjectClick: (ForgeProject) -> Unit,
    private val onProjectLongClick: (ForgeProject) -> Unit
) : ListAdapter<ForgeProject, ProjectAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProjectCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemProjectCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(project: ForgeProject) {
            binding.apply {
                tvProjectName.text = project.name
                tvPackageName.text = project.packageName

                // Status indicator
                val (statusText, statusColor) = when (project.buildStatus) {
                    BuildStatus.NEVER_BUILT -> "Not built" to 0xFF9E9E9E.toInt()
                    BuildStatus.BUILDING -> "Building..." to 0xFFFF9800.toInt()
                    BuildStatus.BUILD_SUCCESS -> "Built" to 0xFF4CAF50.toInt()
                    BuildStatus.BUILD_FAILED -> "Build failed" to 0xFFF44336.toInt()
                    BuildStatus.INSTALLING -> "Installing..." to 0xFF2196F3.toInt()
                    BuildStatus.INSTALLED -> "Installed ✓" to 0xFF4CAF50.toInt()
                    BuildStatus.INSTALL_FAILED -> "Install failed" to 0xFFF44336.toInt()
                }

                tvBuildStatus.text = statusText
                tvBuildStatus.setTextColor(statusColor)

                // Last modified
                val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
                tvLastModified.text = sdf.format(Date(project.lastModifiedAt))

                // Project icon color
                cardRoot.setCardBackgroundColor(generateProjectColor(project.name))

                // Icon letter
                tvProjectIcon.text = project.name.first().uppercase()

                root.setOnClickListener { onProjectClick(project) }
                root.setOnLongClickListener {
                    onProjectLongClick(project)
                    true
                }
            }
        }

        private fun generateProjectColor(name: String): Int {
            val colors = intArrayOf(
                0xFF1A237E.toInt(), // Deep blue
                0xFF880E4F.toInt(), // Deep pink
                0xFF1B5E20.toInt(), // Deep green
                0xFF4A148C.toInt(), // Deep purple
                0xFF0D47A1.toInt(), // Blue
                0xFFBF360C.toInt(), // Deep orange
                0xFF006064.toInt(), // Cyan
                0xFF37474F.toInt()  // Blue grey
            )
            return colors[name.hashCode().and(0x7FFFFFFF) % colors.size]
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ForgeProject>() {
        override fun areItemsTheSame(a: ForgeProject, b: ForgeProject) = a.id == b.id
        override fun areContentsTheSame(a: ForgeProject, b: ForgeProject) = a == b
    }
}