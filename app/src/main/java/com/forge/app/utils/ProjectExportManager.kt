package com.forge.app.utils

import android.content.Context
import android.net.Uri
import com.forge.app.data.models.ForgeProject
import com.forge.app.data.repository.ProjectFileManager
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectExportManager(private val context: Context) {

    fun exportProjectSource(project: ForgeProject, destinationUri: Uri) {
        val projectRoot = ProjectFileManager(context, project).getProjectRoot()
        require(projectRoot.exists()) { "Project files are missing." }

        val filesToExport = projectRoot.walkTopDown()
            .filter { it.isFile }
            .filter { shouldInclude(projectRoot, it) }
            .sortedBy { it.relativeTo(projectRoot).path }
            .toList()

        require(filesToExport.isNotEmpty()) { "There are no project files to export yet." }

        val outputStream = context.contentResolver.openOutputStream(destinationUri)
            ?: error("Could not open the selected save location.")

        val rootFolderName = suggestedExportFolderName(project)
        outputStream.buffered().use { bufferedOutput ->
            ZipOutputStream(bufferedOutput).use { zipOutput ->
                filesToExport.forEach { file ->
                    val relativePath = file.relativeTo(projectRoot)
                        .path
                        .replace(File.separatorChar, '/')
                    val entry = ZipEntry("$rootFolderName/$relativePath").apply {
                        time = file.lastModified()
                    }
                    zipOutput.putNextEntry(entry)
                    file.inputStream().buffered().use { input -> input.copyTo(zipOutput) }
                    zipOutput.closeEntry()
                }
            }
        }
    }

    private fun shouldInclude(projectRoot: File, file: File): Boolean {
        val relativePath = file.relativeTo(projectRoot).path.replace(File.separatorChar, '/')
        return !relativePath.startsWith("build/") && !relativePath.startsWith(".forge/")
    }

    companion object {
        fun suggestedArchiveName(project: ForgeProject): String {
            return "${suggestedExportFolderName(project)}.zip"
        }

        private fun suggestedExportFolderName(project: ForgeProject): String {
            val normalized = project.name.trim()
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-', '_', '.')

            return "${normalized.ifBlank { "forge-project" }}-source"
        }
    }
}
