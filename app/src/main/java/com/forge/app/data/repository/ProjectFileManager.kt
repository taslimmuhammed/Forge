package com.forge.app.data.repository

import android.content.Context
import com.forge.app.data.models.ForgeProject
import java.io.File
import java.security.MessageDigest

class ProjectFileManager(
    private val context: Context,
    private val project: ForgeProject
) {
    private val projectRoot = File(context.filesDir, "projects/${project.id}")
    val srcMainDir = File(projectRoot, "app/src/main")
    val javaDir = File(srcMainDir, "java/${project.domain}/${project.appName}")
    val resDir = File(srcMainDir, "res")
    val buildDir = File(projectRoot, "build")
    val forgeDir = File(projectRoot, ".forge")
    val forgeMdFile = File(projectRoot, "forge.md")
    val contextSummaryFile = File(forgeDir, "context_summary.json")
    val fileHashesFile = File(forgeDir, "file_hashes.json")
    val outputApkFile = File(buildDir, "output/app-debug.apk")

    fun initializeProjectStructure() {
        // Create all directories
        listOf(javaDir, resDir, buildDir, forgeDir,
            File(resDir, "layout"),
            File(resDir, "values"),
            File(resDir, "drawable"),
            File(resDir, "mipmap-hdpi"),
            File(resDir, "mipmap-xhdpi"),
            File(resDir, "mipmap-xxhdpi"),
            File(buildDir, "classes"),
            File(buildDir, "dex"),
            File(buildDir, "output")
        ).forEach { it.mkdirs() }

        // Write boilerplate files
        writeBoilerplate()
        initForgeMd()
        initFileHashes()
    }

    private fun writeBoilerplate() {
        // AndroidManifest.xml
        writeFile("app/src/main/AndroidManifest.xml", """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="${project.packageName}">
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="${project.name}"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.Light.DarkActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:label="${project.name}">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
    </application>
</manifest>
        """.trimIndent())

        // MainActivity.java
        writeFile("app/src/main/java/${project.domain}/${project.appName}/MainActivity.java", """
package ${project.packageName};

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
        """.trimIndent())

        // activity_main.xml
        writeFile("app/src/main/res/layout/activity_main.xml", """
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="32dp"
    android:background="#FFFFFF">

    <TextView
        android:id="@+id/tvAppName"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="${project.name}"
        android:textSize="28sp"
        android:textStyle="bold"
        android:textColor="#1A1A2E"
        android:layout_marginBottom="16dp"/>

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Your app is ready.\nOpen Forge to start building!"
        android:textSize="16sp"
        android:textColor="#666666"
        android:gravity="center"
        android:lineSpacingMultiplier="1.4"/>

</LinearLayout>
        """.trimIndent())

        // strings.xml
        writeFile("app/src/main/res/values/strings.xml", """
<resources>
    <string name="app_name">${project.name}</string>
</resources>
        """.trimIndent())

        // colors.xml
        writeFile("app/src/main/res/values/colors.xml", """
<resources>
    <color name="colorPrimary">#6200EE</color>
    <color name="colorPrimaryDark">#3700B3</color>
    <color name="colorAccent">#03DAC5</color>
    <color name="white">#FFFFFF</color>
    <color name="black">#FF000000</color>
</resources>
        """.trimIndent())

        // styles.xml
        writeFile("app/src/main/res/values/styles.xml", """
<resources>
    <style name="AppTheme" parent="Theme.AppCompat.Light.DarkActionBar">
        <item name="colorPrimary">@color/colorPrimary</item>
        <item name="colorPrimaryDark">@color/colorPrimaryDark</item>
        <item name="colorAccent">@color/colorAccent</item>
    </style>
</resources>
        """.trimIndent())

        // build.gradle for generated app
        writeFile("app/build.gradle", """
apply plugin: 'com.android.application'

android {
    compileSdkVersion 34
    defaultConfig {
        applicationId "${project.packageName}"
        minSdkVersion 26
        targetSdkVersion 34
        versionCode 1
        versionName "1.0"
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
}
        """.trimIndent())
    }

    private fun initForgeMd() {
        forgeMdFile.writeText("""
# Project: ${project.name}
## Package: ${project.packageName}
## Created: ${java.util.Date()}

## Current State
- Screens: [MainActivity]
- Activities: 1
- Dependencies: [androidx.appcompat:appcompat:1.6.1]
- Features: [Basic boilerplate, LinearLayout with welcome text]
- Last build: Never

## Architecture Decisions
- Single Activity architecture to start
- Using AppCompat theme for broad device support

## Known Issues
(none)

## User Preferences
(will be learned from conversations)

## File Registry
- app/src/main/AndroidManifest.xml
- app/src/main/java/${project.domain}/${project.appName}/MainActivity.java
- app/src/main/res/layout/activity_main.xml
- app/src/main/res/values/strings.xml
- app/src/main/res/values/colors.xml
- app/src/main/res/values/styles.xml
        """.trimIndent())
    }

    private fun initFileHashes() {
        fileHashesFile.writeText("{}")
        updateFileHashes()
    }

    fun writeFile(relativePath: String, content: String) {
        val file = File(projectRoot, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun readFile(relativePath: String): String? {
        val file = File(projectRoot, relativePath)
        return if (file.exists()) file.readText() else null
    }

    fun deleteFile(relativePath: String): Boolean {
        return File(projectRoot, relativePath).delete()
    }

    fun fileExists(relativePath: String): Boolean {
        return File(projectRoot, relativePath).exists()
    }

    fun getAllSourceFiles(): Map<String, String> {
        val files = mutableMapOf<String, String>()
        projectRoot.walkTopDown()
            .filter { it.isFile }
            .filter { !it.path.contains("/.forge/") && !it.path.contains("/build/") }
            .forEach { file ->
                val relativePath = file.relativeTo(projectRoot).path
                files[relativePath] = file.readText()
            }
        return files
    }

    fun getChangedFiles(sinceHashes: Map<String, String>): Map<String, String> {
        val changed = mutableMapOf<String, String>()
        getAllSourceFiles().forEach { (path, content) ->
            val currentHash = sha256(content)
            if (sinceHashes[path] != currentHash) {
                changed[path] = content
            }
        }
        return changed
    }

    fun updateFileHashes() {
        val hashes = mutableMapOf<String, String>()
        getAllSourceFiles().forEach { (path, content) ->
            hashes[path] = sha256(content)
        }
        val json = hashes.entries.joinToString(",\n", "{\n", "\n}") {
            "  \"${it.key}\": \"${it.value}\""
        }
        fileHashesFile.writeText(json)
    }

    fun readForgeMd(): String = if (forgeMdFile.exists()) forgeMdFile.readText() else ""

    fun writeForgeMd(content: String) = forgeMdFile.writeText(content)

    fun readContextSummary(): String =
        if (contextSummaryFile.exists()) contextSummaryFile.readText() else "{}"

    fun writeContextSummary(json: String) = contextSummaryFile.writeText(json)

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getProjectRoot(): File = projectRoot
}