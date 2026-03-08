package com.forge.app.build_engine

import com.forge.app.data.repository.ProjectFileManager

/**
 * Manages version codes for generated APKs.
 * Android requires a higher versionCode to reinstall over an existing version.
 */
class VersionManager(private val fileManager: ProjectFileManager) {

    private val versionFile = java.io.File(
        fileManager.getProjectRoot(),
        ".forge/version_code.txt"
    )

    fun getNextVersionCode(): Int {
        val current = try {
            versionFile.readText().trim().toInt()
        } catch (e: Exception) {
            0
        }
        val next = current + 1
        versionFile.writeText(next.toString())
        return next
    }

    fun getCurrentVersionCode(): Int {
        return try {
            versionFile.readText().trim().toInt()
        } catch (e: Exception) {
            1
        }
    }

    /**
     * Updates the versionCode in build.gradle of the generated project before each build.
     */
    fun bumpVersionInBuildGradle() {
        val nextCode = getNextVersionCode()
        val buildGradle = fileManager.readFile("app/build.gradle") ?: return
        val updated = buildGradle.replace(
            Regex("""versionCode\s+\d+"""),
            "versionCode $nextCode"
        )
        fileManager.writeFile("app/build.gradle", updated)
    }
}