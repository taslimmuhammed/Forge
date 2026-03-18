package com.forge.app.build_engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.forge.app.data.models.BuildResult
import com.forge.app.data.models.DependencySpec
import com.forge.app.data.repository.ProjectFileManager
import dalvik.system.DexClassLoader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.*
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder

class BuildEngine(
    private val context: Context,
    private val fileManager: ProjectFileManager
) {
    companion object {
        private const val TAG = "ForgeBuilder"
        private const val BUNDLED_ANDROID_JAR_ASSET = "android.jar"
        private const val GENERATED_MIN_SDK = 26
        private const val GENERATED_TARGET_SDK = 34
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        // Network fallback URLs if bundled tool extraction is not available.
        private val ANDROID_JAR_URLS = listOf(
            "https://raw.githubusercontent.com/nicksay/aosp-jars/main/android-26-api.jar",
            "https://cdn.jsdelivr.net/gh/nicksay/aosp-jars@main/android-26-api.jar",
            "https://github.com/nicksay/aosp-jars/raw/main/android-26-api.jar",
            "https://repo1.maven.org/maven2/com/google/android/android/4.1.1.4/android-4.1.1.4.jar"
        )
    }

    private val toolsDir = File(context.filesDir, "forge_tools").also { it.mkdirs() }
    private val androidJar get() = File(toolsDir, "android.jar")

    // ─────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Runs the full build pipeline on Dispatchers.IO and emits progress events.
     * channelFlow is used so send() is safe from any coroutine context.
     */
    fun build(packageName: String): Flow<BuildEvent> = channelFlow {
        withContext(Dispatchers.IO) {
            runBuildPipeline(packageName)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // BUILD PIPELINE  (suspend fun — plain returns are fine here)
    // ─────────────────────────────────────────────────────────────────

    private suspend fun ProducerScope<BuildEvent>.runBuildPipeline(packageName: String) {
        val startTime = System.currentTimeMillis()
        send(BuildEvent.Log("🔨 Starting build for $packageName..."))

        try {
            // ── Step 1: tools ──────────────────────────────────────────
            send(BuildEvent.Log("🔧 Checking build tools..."))
            if (!ensureBuildTools { msg -> send(BuildEvent.Log(msg)) }) {
                send(BuildEvent.Error(
                    "Could not prepare build tools. Check your connection and retry. " +
                            "If this keeps failing, reinstall Forge to restore bundled offline tools."
                ))
                return
            }

            // ── Step 2: environment ────────────────────────────────────
            send(BuildEvent.Log("📦 Preparing build environment..."))
            val env = prepareBuildEnvironment()

            // ── Step 3: dependencies ───────────────────────────────────
            send(BuildEvent.Log("📥 Resolving dependencies..."))
            val deps = resolveDependencies()
            deps.forEach { send(BuildEvent.Log("  ↳ ${it.coordinate}")) }

            // ── Step 3.5: Generate R.java ──────────────────────────────
            send(BuildEvent.Log("📝 Generating R.java..."))
            val genDir = File(env.buildDir, "gen").also { it.mkdirs() }
            try {
                val resDir = File(env.srcDir, "res")
                val manifestFile = File(env.srcDir, "AndroidManifest.xml")
                if (resDir.exists() && manifestFile.exists()) {
                    val resourceCompiler = ResourceCompiler()
                    // Quick scan resources to generate R.java (no full compile yet)
                    val resourceIds = scanResourceIds(resDir, packageName)
                    resourceCompiler.generateRJava(resourceIds, packageName, genDir)
                    send(BuildEvent.Log("  ✓ R.java generated"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "R.java generation failed (non-fatal): ${e.message}")
                send(BuildEvent.Log("  ⚠ R.java generation skipped: ${e.message}"))
            }

            // ── Step 4: compile ────────────────────────────────────────
            send(BuildEvent.Log("☕ Compiling Java sources..."))
            val compileResult = compileWithEcj(env, deps, genDir)
            if (!compileResult.success) {
                send(BuildEvent.Error(compileResult.errorLog))
                return
            }
            send(BuildEvent.Log("  ✓ Compilation successful"))

            // ── Step 5: dex ────────────────────────────────────────────
            send(BuildEvent.Log("🔄 Converting to Dalvik bytecode..."))
            val dexResult = createDex(env)
            if (!dexResult.success) {
                send(BuildEvent.Error(dexResult.errorLog))
                return
            }
            send(BuildEvent.Log("  ✓ DEX created"))

            // ── Step 6: resources ──────────────────────────────────────
            send(BuildEvent.Log("🎨 Packaging resources..."))
            val resResult = packageResources(env, packageName)
            if (!resResult.success) {
                send(BuildEvent.Error(resResult.errorLog))
                return
            }
            send(BuildEvent.Log("  ✓ Resources packaged"))

            // ── Step 7: assemble ───────────────────────────────────────
            send(BuildEvent.Log("📦 Assembling APK..."))
            val apkPath = assembleApk(env)
            send(BuildEvent.Log("  ✓ APK assembled"))

            // ── Step 8: sign ───────────────────────────────────────────
            send(BuildEvent.Log("✍️ Signing APK..."))
            val signedPath = signApk(apkPath)
            send(BuildEvent.Log("  ✓ APK signed"))

            // Validate final APK before reporting success/installing.
            validateInstallableApk(signedPath)?.let { validationError ->
                send(BuildEvent.Error(validationError))
                return
            }

            val duration = System.currentTimeMillis() - startTime
            send(BuildEvent.Log("✅ Build successful in ${duration / 1000}s"))
            send(BuildEvent.Success(BuildResult(true, signedPath, durationMs = duration)))

        } catch (e: Exception) {
            Log.e(TAG, "Build failed", e)
            send(BuildEvent.Error("Build failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 1 — Download build tools (ECJ + android.jar)
    // ─────────────────────────────────────────────────────────────────

    private suspend fun ensureBuildTools(log: suspend (String) -> Unit): Boolean {
        // Check if we already have a valid android.jar
        if (androidJar.exists()) {
            if (isValidAndroidJar(androidJar)) {
                Log.d(TAG, "android.jar exists and is valid (${androidJar.length()} bytes)")
                return true
            } else {
                Log.e(TAG, "[BUILD] android.jar exists but is INVALID (${androidJar.length()} bytes) — deleting and re-downloading")
                androidJar.delete()
            }
        }

        // Offline-first: extract bundled android.jar asset from the APK.
        if (extractBundledAndroidJar()) {
            if (isValidAndroidJar(androidJar)) {
                log("  ✓ Using bundled Android platform stubs")
                Log.d(TAG, "[BUILD] ✓ Using bundled android.jar (${androidJar.length()} bytes)")
                return true
            }
            Log.e(TAG, "[BUILD] Bundled android.jar is invalid (${androidJar.length()} bytes)")
            androidJar.delete()
        } else {
            log("  ↳ Bundled platform stubs unavailable, trying network download")
        }

        // Network fallback when bundled asset isn't available.
        for ((index, url) in ANDROID_JAR_URLS.distinct().withIndex()) {
            Log.d(TAG, "[BUILD] Attempting android.jar download (attempt ${index + 1}/${ANDROID_JAR_URLS.distinct().size}): $url")
            if (downloadFile(url, androidJar)) {
                if (isValidAndroidJar(androidJar)) {
                    Log.d(TAG, "[BUILD] ✓ android.jar downloaded and validated: ${androidJar.length()} bytes")
                    log("  ✓ Downloaded Android platform stubs")
                    return true
                } else {
                    Log.e(TAG, "[BUILD] ✗ Downloaded file is not a valid android.jar (${androidJar.length()} bytes), trying next URL...")
                    androidJar.delete()
                }
            } else {
                Log.e(TAG, "[BUILD] ✗ Download failed from: $url")
            }
        }

        Log.e(TAG, "[BUILD] CRITICAL: Could not obtain a valid android.jar from any source")
        return false
    }

    private fun extractBundledAndroidJar(): Boolean {
        val tmp = File(toolsDir, "android.jar.asset.tmp")
        return try {
            context.assets.open(BUNDLED_ANDROID_JAR_ASSET).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.exists() || tmp.length() == 0L) return false

            val moved = tmp.renameTo(androidJar)
            if (!moved) {
                tmp.copyTo(androidJar, overwrite = true)
                tmp.delete()
            }
            androidJar.exists() && androidJar.length() > 0
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "[BUILD] Bundled android.jar asset not found")
            false
        } catch (e: Exception) {
            Log.w(TAG, "[BUILD] Failed extracting bundled android.jar: ${e.message}")
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Validate that a file is a real android.jar by checking:
     * 1. It's large enough (real android.jar is > 1MB)
     * 2. It has ZIP magic bytes (PK header)
     * 3. It contains java/lang/Object.class (the most fundamental class)
     */
    private fun isValidAndroidJar(file: File): Boolean {
        if (!file.exists()) return false
        if (file.length() < 100_000) { // Any real android.jar is > 100KB
            Log.w(TAG, "[BUILD] android.jar too small: ${file.length()} bytes (expected >100KB)")
            return false
        }
        return try {
            // Check ZIP magic bytes
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
                Log.w(TAG, "[BUILD] android.jar has invalid magic bytes (not a ZIP): ${header.map { "%02x".format(it) }}")
                return false
            }
            // Check for java/lang/Object.class inside the JAR
            var hasObjectClass = false
            ZipInputStream(file.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "java/lang/Object.class") {
                        hasObjectClass = true
                        break
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (!hasObjectClass) {
                Log.w(TAG, "[BUILD] android.jar missing java/lang/Object.class — not a valid Android platform JAR")
            }
            hasObjectClass
        } catch (e: Exception) {
            Log.e(TAG, "[BUILD] android.jar validation error: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 2 — Prepare build directories
    // ─────────────────────────────────────────────────────────────────

    private fun prepareBuildEnvironment(): BuildEnvironment {
        val root = fileManager.getProjectRoot()
        val buildDir = File(root, "build")
        val classesDir = File(buildDir, "classes").also { it.deleteRecursively(); it.mkdirs() }
        val dexDir = File(buildDir, "dex").also { it.deleteRecursively(); it.mkdirs() }
        val outputDir = File(buildDir, "output").also { it.deleteRecursively(); it.mkdirs() }
        val resDir = File(buildDir, "res").also { it.deleteRecursively(); it.mkdirs() }
        return BuildEnvironment(
            projectRoot = root,
            srcDir = fileManager.srcMainDir,
            classesDir = classesDir,
            dexDir = dexDir,
            outputDir = outputDir,
            resDir = resDir,
            androidJar = androidJar,
            buildDir = buildDir
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 3 — Resolve dependencies
    // ─────────────────────────────────────────────────────────────────

    private fun resolveDependencies(): List<DependencySpec> {
        val buildGradle = fileManager.readFile("app/build.gradle")
            ?: fileManager.readFile("app/build.gradle.kts")
            ?: return emptyList()

        val depRegex = Regex(
            """implementation\s*[\("']([^:'"()\s]+):([^:'"()\s]+):([^'"()\s]+)[\)"']"""
        )
        val deps = depRegex.findAll(buildGradle).map { m ->
            DependencySpec(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }.toList()

        deps.forEach { downloadDependencyIfNeeded(it) }
        return deps
    }

    private fun downloadDependencyIfNeeded(dep: DependencySpec) {
        val cacheDir = File(context.filesDir, "maven_cache/${dep.cacheKey}").also { it.mkdirs() }
        if (File(cacheDir, "classes.jar").exists()) return
        if (File(cacheDir, "${dep.artifactId}-${dep.version}.jar").exists()) return

        val groupPath = dep.groupId.replace('.', '/')
        val base = "${dep.artifactId}-${dep.version}"

        for (repoBase in listOf("https://maven.google.com", "https://repo1.maven.org/maven2")) {
            val aarDest = File(cacheDir, "$base.aar")
            if (downloadFile("$repoBase/$groupPath/${dep.artifactId}/${dep.version}/$base.aar", aarDest)) {
                extractClassesFromAar(aarDest, cacheDir)
                return
            }
            if (downloadFile("$repoBase/$groupPath/${dep.artifactId}/${dep.version}/$base.jar",
                    File(cacheDir, "$base.jar"))) return
        }
        Log.w(TAG, "Could not download ${dep.coordinate}")
    }

    private fun extractClassesFromAar(aarFile: File, destDir: File) {
        try {
            ZipInputStream(FileInputStream(aarFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "classes.jar") {
                        File(destDir, "classes.jar").outputStream().use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AAR extraction failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 3.5 — Generate R.java resource IDs
    // ─────────────────────────────────────────────────────────────────

    /**
     * Quick-scan resource directories and values files to build resource ID map.
     * Used for generating R.java before compilation.
     */
    private fun scanResourceIds(resDir: File, packageName: String): Map<String, Map<String, Int>> {
        val result = mutableMapOf<String, MutableMap<String, Int>>()
        val typeCounts = mutableMapOf<String, Int>()
        val appPackageId = 0x7F

        fun addResource(type: String, name: String, typeId: Int) {
            val map = result.getOrPut(type) { mutableMapOf() }
            if (name in map) return // already added
            val entryId = typeCounts.getOrDefault(type, 0)
            typeCounts[type] = entryId + 1
            map[name] = (appPackageId shl 24) or (typeId shl 16) or entryId
        }

        // Values files (strings.xml, colors.xml, etc.)
        val valuesDir = File(resDir, "values")
        if (valuesDir.exists()) {
            valuesDir.listFiles()?.filter { it.extension == "xml" }?.forEach { file ->
                try {
                    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    val doc = factory.newDocumentBuilder().parse(file)
                    val children = doc.documentElement.childNodes
                    for (i in 0 until children.length) {
                        val node = children.item(i)
                        if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                        val elem = node as org.w3c.dom.Element
                        val name = elem.getAttribute("name") ?: continue
                        when (elem.tagName) {
                            "string" -> addResource("string", name, 0x06)
                            "color" -> addResource("color", name, 0x07)
                            "dimen" -> addResource("dimen", name, 0x08)
                            "style" -> addResource("style", name, 0x09)
                            "bool" -> addResource("bool", name, 0x0B)
                            "integer" -> addResource("integer", name, 0x0C)
                            "item" -> {
                                if (elem.getAttribute("type") == "id") addResource("id", name, 0x0A)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to scan ${file.name}: ${e.message}")
                }
            }
        }

        // File-based resources
        val fileDirs = mapOf(
            "layout" to 0x04, "drawable" to 0x02, "mipmap" to 0x03,
            "menu" to 0x0F, "xml" to 0x0E, "anim" to 0x05
        )
        fileDirs.forEach { (dirPrefix, typeId) ->
            resDir.listFiles()?.filter { it.isDirectory && it.name.startsWith(dirPrefix) }?.forEach { dir ->
                dir.listFiles()?.forEach { file ->
                    addResource(dirPrefix.substringBefore('-'), file.nameWithoutExtension, typeId)
                }
            }
        }

        // IDs from @+id/ in layout XML files
        val layoutDir = File(resDir, "layout")
        if (layoutDir.exists()) {
            layoutDir.listFiles()?.filter { it.extension == "xml" }?.forEach { file ->
                try {
                    Regex("@\\+id/(\\w+)").findAll(file.readText()).forEach { match ->
                        addResource("id", match.groupValues[1], 0x0A)
                    }
                } catch (_: Exception) {}
            }
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 4 — Compile Java → .class via ECJ
    // ─────────────────────────────────────────────────────────────────

    private fun compileWithEcj(env: BuildEnvironment, deps: List<DependencySpec>, genDir: File? = null): BuildResult {
        val javaFiles = mutableListOf<String>()
        
        // Source files from src/main/java
        env.srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { javaFiles.add(it.absolutePath) }
        
        // Generated R.java files
        genDir?.walkTopDown()
            ?.filter { it.isFile && it.extension == "java" }
            ?.forEach { javaFiles.add(it.absolutePath) }

        if (javaFiles.isEmpty()) {
            Log.e(TAG, "[BUILD] No .java source files found in ${env.srcDir.absolutePath}")
            return BuildResult(false, errorLog = "No .java source files found in ${env.srcDir}")
        }

        Log.d(TAG, "[BUILD] Found ${javaFiles.size} Java files to compile")
        javaFiles.forEach { Log.d(TAG, "[BUILD]   → $it") }

        // Pre-flight check: validate android.jar before compilation
        if (!env.androidJar.exists()) {
            Log.e(TAG, "[BUILD] CRITICAL: android.jar not found at ${env.androidJar.absolutePath}")
            return BuildResult(false, errorLog = "android.jar not found at ${env.androidJar.absolutePath}. " +
                    "Check your internet connection and restart the build.")
        }
        if (!isValidAndroidJar(env.androidJar)) {
            Log.e(TAG, "[BUILD] CRITICAL: android.jar is invalid (${env.androidJar.length()} bytes)")
            env.androidJar.delete() // Force re-download next time
            return BuildResult(false, errorLog = "android.jar is corrupted (${env.androidJar.length()} bytes). " +
                    "It has been deleted — tap Run again to re-download.")
        }

        val classpath = buildClasspath(env, deps)
        Log.d(TAG, "[BUILD] Classpath: $classpath")
        Log.d(TAG, "[BUILD] Bootclasspath: ${env.androidJar.absolutePath} (${env.androidJar.length()} bytes)")

        // Try ECJ (bundled as Gradle dependency, loaded via Class.forName)
        val ecjResult = tryEcj(javaFiles, classpath, env.classesDir, env.androidJar.absolutePath)
        if (ecjResult.success) return ecjResult

        // If ECJ produced regular compile diagnostics, surface them directly.
        // Fallback to javac only when ECJ itself is unavailable/broken.
        val ecjError = ecjResult.errorLog.orEmpty()
        if (ecjError.isNotBlank() && !shouldFallbackToJavac(ecjError)) {
            return ecjResult
        }

        // Fallback: any javac on the system
        return tryJavac(javaFiles, classpath, env.classesDir, ecjError.ifBlank { "ECJ failed" })
    }

    private fun shouldFallbackToJavac(ecjError: String): Boolean {
        val msg = ecjError.lowercase()
        if (msg.isBlank()) return true
        return msg.contains("ecj compiler class not found") ||
                msg.startsWith("ecj error:") ||
                msg.contains("classnotfoundexception") ||
                msg.contains("nosuchmethodexception") ||
                msg.contains("invocationtargetexception")
    }

    private fun tryEcj(javaFiles: List<String>, classpath: String, outputDir: File, androidJarPath: String): BuildResult {
        return try {
            val mainClass = Class.forName("org.eclipse.jdt.internal.compiler.batch.Main")
            Log.d(TAG, "[BUILD] ECJ class loaded successfully")

            val errSw = StringWriter()
            val outSw = StringWriter()

            // Try the 3-arg constructor first (PrintWriter, PrintWriter, boolean)
            // then fall back to 5-arg if needed
            val instance = try {
                mainClass.getConstructor(
                    PrintWriter::class.java,
                    PrintWriter::class.java,
                    Boolean::class.javaPrimitiveType
                ).newInstance(PrintWriter(outSw), PrintWriter(errSw), false)
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "[BUILD] ECJ 3-arg constructor not found, trying 5-arg")
                mainClass.getConstructor(
                    PrintWriter::class.java,
                    PrintWriter::class.java,
                    Boolean::class.javaPrimitiveType,
                    java.util.Map::class.java,
                    Any::class.java
                ).newInstance(PrintWriter(outSw), PrintWriter(errSw), false, null, null)
            }

            val args = (listOf("-source", "8", "-target", "8",
                "-bootclasspath", androidJarPath,
                "-cp", classpath,
                "-d", outputDir.absolutePath, "-encoding", "UTF-8", "-nowarn") + javaFiles).toTypedArray()

            Log.d(TAG, "[BUILD] ECJ bootclasspath: $androidJarPath")
            Log.d(TAG, "[BUILD] ECJ output dir: ${outputDir.absolutePath}")
            Log.d(TAG, "[BUILD] ECJ compiling ${javaFiles.size} files...")

            val success = mainClass.getMethod("compile", Array<String>::class.java)
                .invoke(instance, args) as Boolean

            val errText = errSw.toString().trim()
            val outText = outSw.toString().trim()

            if (success) {
                Log.d(TAG, "[BUILD] ✓ ECJ compilation successful")
                if (outText.isNotEmpty()) Log.d(TAG, "[BUILD] ECJ output: $outText")
                BuildResult(true)
            } else {
                Log.e(TAG, "[BUILD] ✗ ECJ compilation FAILED")
                if (errText.isNotEmpty()) Log.e(TAG, "[BUILD] ECJ errors:\n$errText")
                if (outText.isNotEmpty()) Log.e(TAG, "[BUILD] ECJ output:\n$outText")
                BuildResult(false, errorLog = errText.ifEmpty { outText.ifEmpty { "ECJ: Compilation failed" } })
            }
        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "[BUILD] ECJ class not found on classpath", e)
            BuildResult(false, errorLog = "ECJ compiler class not found: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "ECJ failed", e)
            val rootCause = e.cause?.message ?: e.message
            BuildResult(false, errorLog = "ECJ error: $rootCause")
        }
    }

    private fun tryJavac(javaFiles: List<String>, classpath: String, outputDir: File, ecjError: String = ""): BuildResult {
        val candidates = listOf(
            "javac",
            "/data/data/com.termux/files/usr/bin/javac",
            "/system/bin/javac",
            "/usr/bin/javac"
        )
        for (javac in candidates) {
            if (javac.startsWith("/") && !File(javac).canExecute()) continue
            try {
                val cmd = mutableListOf(javac, "-source", "8", "-target", "8",
                    "-cp", classpath, "-d", outputDir.absolutePath, "-encoding", "UTF-8")
                cmd.addAll(javaFiles)
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().readText()
                if (proc.waitFor() == 0) return BuildResult(true)
                return BuildResult(false, errorLog = out)
            } catch (e: Exception) {
                continue
            }
        }
        return BuildResult(
            false,
            errorLog = "No Java compiler found.\n\n" +
                    "ECJ compiler error: $ecjError\n\n" +
                    "Please report this issue — the bundled ECJ compiler should have worked."
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 5 — DEX conversion
    // ─────────────────────────────────────────────────────────────────

    private fun createDex(env: BuildEnvironment): BuildResult {
        val classFiles = env.classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        if (classFiles.isEmpty()) return BuildResult(false, errorLog = "No .class files found after compilation")

        val dexFile = File(env.dexDir, "classes.dex")

        // Primary: D8 (bundled as Gradle dependency, pure Java — works on any Android device)
        val d8Result = tryD8(env, classFiles, dexFile)
        if (d8Result.success) return d8Result
        Log.w(TAG, "D8 failed: ${d8Result.errorLog}")

        // Fallback 1: dx.jar bundled in system
        tryDxJar(env, dexFile)?.let { if (it.success) return it }

        // Fallback 2: dx shell command
        tryShellCommand(listOf("dx", "--dex", "--output=${dexFile.absolutePath}", env.classesDir.absolutePath))
            .let { if (it.success && dexFile.exists()) return BuildResult(true) }

        // Fallback 3: Termux dx
        val termuxDx = "/data/data/com.termux/files/usr/bin/dx"
        if (File(termuxDx).canExecute()) {
            tryShellCommand(listOf(termuxDx, "--dex", "--output=${dexFile.absolutePath}", env.classesDir.absolutePath))
                .let { if (it.success && dexFile.exists()) return BuildResult(true) }
        }

        return BuildResult(
            false,
            errorLog = "DEX conversion failed.\nD8 error: ${d8Result.errorLog}\n\n" +
                    "Fallback: Install Termux from F-Droid then run:\n  pkg install dx\n\nThen tap Run again."
        )
    }

    /**
     * Convert .class files to .dex using D8 (Google's modern DEX compiler).
     * D8 is bundled as a Gradle dependency (com.android.tools:r8) and runs in pure Java.
     */
    private fun tryD8(env: BuildEnvironment, classFiles: List<File>, dexFile: File): BuildResult {
        return try {
            Log.d(TAG, "Attempting D8 dexing with ${classFiles.size} class files")

            // Collect all dependency JARs for the D8 classpath (library classes)
            val libClasspath = mutableListOf<Path>()
            libClasspath.add(env.androidJar.toPath())

            // Add any downloaded maven dependency JARs
            val mavenCache = File(context.filesDir, "maven_cache")
            if (mavenCache.exists()) {
                mavenCache.walkTopDown()
                    .filter { it.isFile && it.extension == "jar" }
                    .forEach { libClasspath.add(it.toPath()) }
            }

            // Use D8 via reflection to avoid compile-time coupling issues
            val d8Class = Class.forName("com.android.tools.r8.D8")
            val d8CommandClass = Class.forName("com.android.tools.r8.D8Command")
            val builderClass = Class.forName("com.android.tools.r8.D8Command\$Builder")

            // Get D8Command.builder()
            val builderMethod = d8CommandClass.getMethod("builder")
            val builder = builderMethod.invoke(null)

            // Add program files (.class files)
            val addProgramFiles = builderClass.getMethod("addProgramFiles", Collection::class.java)
            addProgramFiles.invoke(builder, classFiles.map { it.toPath() })

            // Add library classpath (android.jar + dependencies)
            val addLibFiles = builderClass.getMethod("addLibraryFiles", Collection::class.java)
            addLibFiles.invoke(builder, libClasspath)

            // Set output directory
            val setOutput = builderClass.getMethod("setOutput", Path::class.java, Class.forName("com.android.tools.r8.OutputMode"))
            val outputModeClass = Class.forName("com.android.tools.r8.OutputMode")
            val dexOutputMode = outputModeClass.getField("DexIndexed").get(null)
            setOutput.invoke(builder, env.dexDir.toPath(), dexOutputMode)

            // Set min API level
            val setMinApi = builderClass.getMethod("setMinApiLevel", Int::class.javaPrimitiveType)
            setMinApi.invoke(builder, 26)

            // Build the command
            val buildMethod = builderClass.getMethod("build")
            val d8Command = buildMethod.invoke(builder)

            // Run D8
            val runMethod = d8Class.getMethod("run", d8CommandClass)
            runMethod.invoke(null, d8Command)

            if (dexFile.exists() && dexFile.length() > 0) {
                Log.d(TAG, "D8 dexing successful: ${dexFile.length()} bytes")
                BuildResult(true)
            } else {
                BuildResult(false, errorLog = "D8 completed but produced no output")
            }
        } catch (e: Exception) {
            val rootCause = generateSequence<Throwable>(e) { it.cause }.last()
            Log.e(TAG, "D8 dexing failed", e)
            BuildResult(false, errorLog = "D8 error: ${rootCause.message ?: e.message}")
        }
    }

    private fun tryDxJar(env: BuildEnvironment, dexFile: File): BuildResult? {
        val dxJarPaths = listOf("/system/framework/dx.jar", "/system/lib/dx.jar")
        for (path in dxJarPaths) {
            if (!File(path).exists()) continue
            return try {
                val dxDexDir = File(toolsDir, "dx_dex").also { it.mkdirs() }
                val loader = DexClassLoader(path, dxDexDir.absolutePath, null, context.classLoader)
                val main = loader.loadClass("com.android.dx.command.Main")
                main.getMethod("main", Array<String>::class.java).invoke(null,
                    arrayOf("--dex", "--output=${dexFile.absolutePath}", env.classesDir.absolutePath))
                if (dexFile.exists() && dexFile.length() > 0) BuildResult(true)
                else BuildResult(false, errorLog = "dx.jar produced no output")
            } catch (e: Exception) {
                Log.w(TAG, "dx.jar failed: ${e.message}")
                null
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 6 — Resource packaging
    // ─────────────────────────────────────────────────────────────────

    private fun packageResources(env: BuildEnvironment, packageName: String): BuildResult {
        val resDir = File(env.srcDir, "res")
        val manifestFile = File(env.srcDir, "AndroidManifest.xml")
        val outputApk = File(env.outputDir, "resources.ap_")
        val genDir = File(env.buildDir, "gen").also { it.mkdirs() }
        val androidJarPath = env.androidJar.absolutePath
        val failureLogs = mutableListOf<String>()

        ensureManifestSdkLevels(manifestFile)?.let { return BuildResult(false, errorLog = it) }

        // aapt2 compile + link
        val compiledDir = File(env.resDir, "compiled").also { it.mkdirs() }
        val aapt2Result = tryAapt2("aapt2", resDir, manifestFile, compiledDir, outputApk, androidJarPath)
        if (aapt2Result.success) return aapt2Result
        if (aapt2Result.errorLog.isNotBlank()) failureLogs.add(aapt2Result.errorLog)

        // Termux aapt2
        val termuxAapt2 = "/data/data/com.termux/files/usr/bin/aapt2"
        if (File(termuxAapt2).canExecute()) {
            val r = tryAapt2(termuxAapt2, resDir, manifestFile, compiledDir, outputApk, androidJarPath)
            if (r.success) return r
            if (r.errorLog.isNotBlank()) failureLogs.add(r.errorLog)
        }

        // aapt v1
        val aaptResult = tryAapt("aapt", resDir, manifestFile, genDir, outputApk, androidJarPath)
        if (aaptResult.success) return aaptResult
        if (aaptResult.errorLog.isNotBlank()) failureLogs.add(aaptResult.errorLog)

        // Termux aapt v1
        val termuxAapt = "/data/data/com.termux/files/usr/bin/aapt"
        if (File(termuxAapt).canExecute()) {
            val r = tryAapt(termuxAapt, resDir, manifestFile, genDir, outputApk, androidJarPath)
            if (r.success) return r
            if (r.errorLog.isNotBlank()) failureLogs.add(r.errorLog)
        }

        // Pure-Java resource compiler (binary XML + resources.arsc, no native binary needed)
        val pureJavaResult = tryPureJavaResources(resDir, manifestFile, outputApk, packageName)
        if (pureJavaResult.success) return pureJavaResult

        val toolDetails = failureLogs
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
            .joinToString("\n\n")

        return BuildResult(
            false,
            errorLog = "Resource packaging failed.\n\n" +
                    "${pureJavaResult.errorLog.ifBlank { "Forge could not generate a valid binary AndroidManifest.xml." }}\n\n" +
                    (if (toolDetails.isNotBlank()) "Native tool errors:\n$toolDetails\n\n" else "") +
                    "Install Termux and run:\n  pkg install aapt aapt2\n\nThen tap Run again."
        )
    }

    /**
     * Compile resources using the pure-Java ResourceCompiler.
     * Produces binary XML, resources.arsc, and a valid resources.ap_ package.
     */
    private fun tryPureJavaResources(resDir: File, manifestFile: File, outputApk: File, packageName: String): BuildResult {
        return try {
            firstInvalidXmlError(manifestFile, resDir)?.let { return BuildResult(false, errorLog = it) }

            val resourceCompiler = ResourceCompiler()
            resourceCompiler.compile(resDir, manifestFile, outputApk, packageName)
            if (outputApk.exists() && outputApk.length() > 0 && hasBinaryManifest(outputApk)) {
                Log.d(TAG, "Pure-Java resource compilation successful: ${outputApk.length()} bytes")
                BuildResult(true)
            } else {
                BuildResult(
                    false,
                    errorLog = "Forge could not generate a valid binary AndroidManifest.xml from this project."
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pure-Java resource compilation failed: ${e.message}", e)
            val root = generateSequence<Throwable>(e) { it.cause }.last()
            val details = root.message?.takeIf { it.isNotBlank() }
                ?: e.message?.takeIf { it.isNotBlank() }
                ?: root.javaClass.simpleName
            BuildResult(
                false,
                errorLog = "Resource compiler error (${root.javaClass.simpleName}): $details"
            )
        }
    }

    private fun tryAapt2(
        binary: String, resDir: File, manifestFile: File,
        compiledDir: File, outputApk: File, androidJarPath: String
    ): BuildResult {
        return try {
            val compileResult = tryShellCommand(listOf(binary, "compile", "--dir", resDir.absolutePath, "-o", compiledDir.absolutePath))
            if (!compileResult.success) return BuildResult(false, errorLog = "aapt2 compile: ${compileResult.errorLog}")

            val flatFiles = compiledDir.walkTopDown().filter { it.isFile && it.extension == "flat" }.map { it.absolutePath }.toList()
            if (flatFiles.isEmpty()) return BuildResult(false, errorLog = "aapt2: no flat files produced")

            val linkCmd = mutableListOf(binary, "link", "-o", outputApk.absolutePath,
                "-I", androidJarPath, "--manifest", manifestFile.absolutePath, "--auto-add-overlay")
            linkCmd.addAll(flatFiles)

            val linkResult = tryShellCommand(linkCmd)
            if (linkResult.success && outputApk.exists()) BuildResult(true)
            else BuildResult(false, errorLog = "aapt2 link: ${linkResult.errorLog}")
        } catch (e: Exception) {
            BuildResult(false, errorLog = "aapt2: ${e.message}")
        }
    }

    private fun tryAapt(
        binary: String, resDir: File, manifestFile: File,
        genDir: File, outputApk: File, androidJarPath: String
    ): BuildResult {
        return try {
            val result = tryShellCommand(listOf(binary, "package", "-f", "-m",
                "-J", genDir.absolutePath, "-M", manifestFile.absolutePath,
                "-S", resDir.absolutePath, "-I", androidJarPath, "-F", outputApk.absolutePath))
            if (result.success && outputApk.exists()) BuildResult(true)
            else BuildResult(false, errorLog = "aapt: ${result.errorLog}")
        } catch (e: Exception) {
            BuildResult(false, errorLog = "aapt: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 7 — Assemble APK (merge resources.ap_ + classes.dex)
    // ─────────────────────────────────────────────────────────────────

    private fun assembleApk(env: BuildEnvironment): String {
        val outputApk = File(env.outputDir, "app-unsigned.apk")
        val resourcesApk = File(env.outputDir, "resources.ap_")
        val dexFile = File(env.dexDir, "classes.dex")
        val writtenEntries = mutableSetOf<String>()

        CountingOutputStream(FileOutputStream(outputApk)).use { cos ->
            ZipOutputStream(cos).use { zos ->
                // Copy everything from resources.ap_
                if (resourcesApk.exists()) {
                    ZipInputStream(FileInputStream(resourcesApk)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory && writtenEntries.add(entry.name)) {
                                val bytes = zis.readBytes()
                                val forceStoredAligned = entry.name == "resources.arsc"
                                writeApkEntry(zos, cos, entry.name, bytes, forceStoredAligned)
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }

                // Add classes.dex
                if (dexFile.exists() && writtenEntries.add("classes.dex")) {
                    writeApkEntry(zos, cos, "classes.dex", dexFile.readBytes(), forceStoredAligned = false)
                }
            }
        }
        return outputApk.absolutePath
    }

    private fun writeApkEntry(
        zos: ZipOutputStream,
        cos: CountingOutputStream,
        name: String,
        bytes: ByteArray,
        forceStoredAligned: Boolean
    ) {
        val entry = ZipEntry(name)
        if (forceStoredAligned) {
            val crc32 = CRC32().apply { update(bytes) }
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            entry.crc = crc32.value
            // Android 11+ requires resources.arsc data offset to be 4-byte aligned.
            val nameLen = name.toByteArray(Charsets.UTF_8).size
            val dataOffsetWithoutExtra = cos.bytesWritten + 30L + nameLen
            val padLen = ((4 - (dataOffsetWithoutExtra % 4)) % 4).toInt()
            if (padLen > 0) {
                entry.extra = ByteArray(padLen)
            }
        }

        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 8 — Sign APK (pure Java, no keytool needed)
    // ─────────────────────────────────────────────────────────────────

    private fun signApk(unsignedApkPath: String): String {
        val unsignedApk = File(unsignedApkPath)
        val signedApk = File(unsignedApk.parent, "app-debug.apk")
        val (privateKey, cert) = getOrCreateDebugKey()

        // Primary: apksig (platform-consistent signing blocks)
        try {
            if (tryApkSigSign(unsignedApk, signedApk, privateKey, cert) &&
                canParsePackageArchive(signedApk.absolutePath)
            ) {
                return signedApk.absolutePath
            }
            Log.w(TAG, "apksig produced an unparseable APK; trying fallback signers")
        } catch (e: Exception) {
            Log.w(TAG, "apksig signing failed: ${e.message}")
        }

        // Secondary: legacy in-process signer
        try {
            ApkSigner(privateKey, cert).sign(unsignedApk, signedApk)
            if (canParsePackageArchive(signedApk.absolutePath)) {
                return signedApk.absolutePath
            }
            Log.w(TAG, "Legacy in-process signed APK is not parseable; trying apksigner fallback")
        } catch (e: Exception) {
            Log.w(TAG, "Legacy in-process signing failed: ${e.message}")
        }

        // Fallback: shell apksigner
        val keystoreFile = File(context.filesDir, "forge_debug.keystore")
        for (bin in listOf("apksigner", "/data/data/com.termux/files/usr/bin/apksigner")) {
            if (bin.startsWith("/") && !File(bin).canExecute()) continue
            try {
                val r = tryShellCommand(listOf(bin, "sign",
                    "--ks", keystoreFile.absolutePath,
                    "--ks-pass", "pass:forge123",
                    "--out", signedApk.absolutePath,
                    unsignedApkPath))
                if (r.success && canParsePackageArchive(signedApk.absolutePath)) {
                    return signedApk.absolutePath
                }
            } catch (e: Exception) { continue }
        }

        // Last resort: copy unsigned
        unsignedApk.copyTo(signedApk, overwrite = true)
        return signedApk.absolutePath
    }

    private fun tryApkSigSign(
        unsignedApk: File,
        outputApk: File,
        privateKey: java.security.PrivateKey,
        cert: java.security.cert.X509Certificate
    ): Boolean {
        val signerConfig = com.android.apksig.ApkSigner.SignerConfig.Builder(
            "forge_debug",
            privateKey,
            listOf(cert)
        ).build()

        com.android.apksig.ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(outputApk)
            .setAlignFileSize(true)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .build()
            .sign()

        return outputApk.exists() && outputApk.length() > 0
    }

    private fun getOrCreateDebugKey(): Pair<java.security.PrivateKey, java.security.cert.X509Certificate> {
        val keystoreFile = File(context.filesDir, "forge_debug.keystore")
        val alias = "forge_debug_key"
        val password = "forge123".toCharArray()

        if (keystoreFile.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12")
                ks.load(FileInputStream(keystoreFile), password)
                val key = ks.getKey(alias, password) as java.security.PrivateKey
                val cert = ks.getCertificate(alias) as java.security.cert.X509Certificate
                return Pair(key, cert)
            } catch (e: Exception) {
                Log.w(TAG, "Existing keystore invalid, regenerating: ${e.message}")
                keystoreFile.delete()
            }
        }

        // Generate key pair + self-signed cert using BouncyCastle (exportable, unlike AndroidKeyStore)
        java.security.Security.addProvider(BouncyCastleProvider())

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom())
        val keyPair = kpg.generateKeyPair()

        val issuer = X500Name("CN=Forge Debug, O=Forge, C=US")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + 10000L * 86400000) // ~27 years

        val certBuilder = JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer, keyPair.public
        )
        val signer = try {
            JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(keyPair.private)
        } catch (e: Exception) {
            Log.w(TAG, "BC signer unavailable, falling back to default provider: ${e.message}")
            JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.private)
        }

        val certHolder = certBuilder.build(signer)
        val cert = try {
            JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(certHolder)
        } catch (e: Exception) {
            Log.w(TAG, "BC certificate converter unavailable, using default provider: ${e.message}")
            JcaX509CertificateConverter().getCertificate(certHolder)
        }

        // Save to PKCS12 keystore
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, password)
        ks.setKeyEntry(alias, keyPair.private, password, arrayOf(cert))
        FileOutputStream(keystoreFile).use { ks.store(it, password) }

        Log.d(TAG, "Generated new debug signing key")
        return Pair(keyPair.private, cert)
    }

    // ─────────────────────────────────────────────────────────────────
    // INSTALL
    // ─────────────────────────────────────────────────────────────────

    fun installApk(context: Context, apkPath: String, onComplete: (Boolean) -> Unit) {
        val apkFile = File(apkPath)
        if (!apkFile.exists()) { onComplete(false); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            installWithPackageInstaller(context, apkFile, onComplete)
        } else {
            installWithIntent(context, apkFile, onComplete)
        }
    }

    private fun installWithPackageInstaller(context: Context, apkFile: File, onComplete: (Boolean) -> Unit) {
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            session.openWrite("package", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(PackageInstallReceiver.INSTALL_RESULT_ACTION).apply {
                setClass(context, PackageInstallReceiver::class.java)
                setPackage(context.packageName)
            }
            val pi = android.app.PendingIntent.getBroadcast(
                context, sessionId, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            session.commit(pi.intentSender)
            session.close()
            Log.d(TAG, "Install session committed: id=$sessionId, apk=${apkFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller failed", e)
            installWithIntent(context, apkFile, onComplete)
        }
    }

    private fun installWithIntent(context: Context, apkFile: File, onComplete: (Boolean) -> Unit) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        onComplete(true)
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    private fun buildClasspath(env: BuildEnvironment, deps: List<DependencySpec>): String {
        val entries = mutableListOf(env.androidJar.absolutePath)
        deps.forEach { dep ->
            File(context.filesDir, "maven_cache/${dep.cacheKey}").walkTopDown()
                .filter { it.isFile && it.extension == "jar" }
                .forEach { entries.add(it.absolutePath) }
        }
        return entries.filter { File(it).exists() }.joinToString(":")
    }

    private fun tryShellCommand(cmd: List<String>): BuildResult {
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit == 0) BuildResult(true)
            else BuildResult(false, errorLog = out)
        } catch (e: Exception) {
            BuildResult(false, errorLog = "${cmd.firstOrNull()}: ${e.message}")
        }
    }

    private fun firstInvalidXmlError(manifestFile: File, resDir: File): String? {
        val xmlFiles = mutableListOf<File>()
        if (manifestFile.exists()) xmlFiles.add(manifestFile)
        if (resDir.exists()) {
            resDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("xml", ignoreCase = true) }
                .forEach { xmlFiles.add(it) }
        }

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for (file in xmlFiles.distinct()) {
            try {
                factory.newDocumentBuilder().parse(file)
            } catch (e: Exception) {
                val relativePath = runCatching {
                    file.relativeTo(fileManager.getProjectRoot()).path
                }.getOrElse { file.absolutePath }
                val reason = e.message ?: "parse error"
                return "Invalid XML in $relativePath: $reason\n" +
                        "Tip: escape special characters such as &, <, >, and quotes in XML values."
            }
        }
        return null
    }

    private fun ensureManifestSdkLevels(manifestFile: File): String? {
        if (!manifestFile.exists()) {
            return "AndroidManifest.xml not found at ${manifestFile.absolutePath}"
        }
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(manifestFile)
            val manifest = doc.documentElement ?: return "Invalid AndroidManifest.xml: missing <manifest> root."
            if (manifest.tagName != "manifest") {
                return "Invalid AndroidManifest.xml: root element must be <manifest>."
            }

            if (!manifest.hasAttribute("xmlns:android")) {
                manifest.setAttribute("xmlns:android", ANDROID_NS)
            }

            var usesSdk: org.w3c.dom.Element? = null
            val children = manifest.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE &&
                    (node as org.w3c.dom.Element).tagName == "uses-sdk"
                ) {
                    usesSdk = node
                    break
                }
            }

            if (usesSdk == null) {
                usesSdk = doc.createElement("uses-sdk")
                var inserted = false
                for (i in 0 until children.length) {
                    val node = children.item(i)
                    if (node.nodeType == org.w3c.dom.Node.ELEMENT_NODE &&
                        (node as org.w3c.dom.Element).tagName == "application"
                    ) {
                        manifest.insertBefore(usesSdk, node)
                        inserted = true
                        break
                    }
                }
                if (!inserted) {
                    manifest.appendChild(usesSdk)
                }
            }

            val existingMin = usesSdk.getAttributeNS(ANDROID_NS, "minSdkVersion").toIntOrNull()
            val existingTarget = usesSdk.getAttributeNS(ANDROID_NS, "targetSdkVersion").toIntOrNull()
            val minSdk = maxOf(existingMin ?: GENERATED_MIN_SDK, GENERATED_MIN_SDK)
            val targetSdk = maxOf(existingTarget ?: GENERATED_TARGET_SDK, GENERATED_TARGET_SDK)
            usesSdk.setAttributeNS(ANDROID_NS, "android:minSdkVersion", minSdk.toString())
            usesSdk.setAttributeNS(ANDROID_NS, "android:targetSdkVersion", targetSdk.toString())

            val versionCode = VersionManager(fileManager).getCurrentVersionCode()
            manifest.setAttributeNS(ANDROID_NS, "android:versionCode", versionCode.toString())
            manifest.setAttributeNS(ANDROID_NS, "android:versionName", "1.0.$versionCode")

            val transformer = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            }
            transformer.transform(DOMSource(doc), StreamResult(manifestFile))
            null
        } catch (e: Exception) {
            "Could not update manifest SDK levels: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun hasBinaryManifest(apkFile: File): Boolean {
        return try {
            ZipInputStream(FileInputStream(apkFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "AndroidManifest.xml") {
                        val header = ByteArray(4)
                        val read = zis.read(header)
                        return read == 4 &&
                                header[0] == 0x03.toByte() &&
                                header[1] == 0x00.toByte() &&
                                header[2] == 0x08.toByte() &&
                                header[3] == 0x00.toByte()
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Manifest validation failed: ${e.message}")
            false
        }
    }

    private fun validateInstallableApk(apkPath: String): String? {
        val apkFile = File(apkPath)
        if (!apkFile.exists()) return "Generated APK not found at $apkPath"
        if (!hasBinaryManifest(apkFile)) {
            return "Generated APK has an invalid AndroidManifest.xml (binary XML expected)."
        }
        resourcesArscPackagingError(apkFile)?.let { return it }

        val packageInfo = packageArchiveInfoOrNull(apkPath)
        if (packageInfo == null) {
            val unsignedCandidate = File(apkFile.parentFile, "app-unsigned.apk")
            if (unsignedCandidate.exists() && packageArchiveInfoOrNull(unsignedCandidate.absolutePath) != null) {
                return "Generated APK became unparseable after signing. " +
                        "The resource package is valid, but signing output was rejected by PackageManager."
            }
            return "Generated APK is not installable (PackageManager could not parse it)."
        }
        return null
    }

    private fun resourcesArscPackagingError(apkFile: File): String? {
        val info = findZipEntryInfo(apkFile, "resources.arsc")
            ?: return "Generated APK is missing resources.arsc."

        if (info.method != ZipEntry.STORED) {
            return "Generated APK has compressed resources.arsc. " +
                    "Android 11+ requires resources.arsc to be uncompressed."
        }
        if (info.dataOffset % 4L != 0L) {
            return "Generated APK has unaligned resources.arsc. " +
                    "Android 11+ requires resources.arsc to be aligned on a 4-byte boundary."
        }
        return null
    }

    private fun findZipEntryInfo(apkFile: File, targetName: String): ZipEntryInfo? {
        RandomAccessFile(apkFile, "r").use { raf ->
            val fileLength = raf.length()
            val maxEocdSearch = 22 + 0xFFFF
            val searchStart = maxOf(0L, fileLength - maxEocdSearch)
            raf.seek(searchStart)
            val searchBuf = ByteArray((fileLength - searchStart).toInt())
            raf.readFully(searchBuf)

            var eocdOffsetInBuf = -1
            for (i in searchBuf.size - 22 downTo 0) {
                if (searchBuf[i] == 0x50.toByte() &&
                    searchBuf[i + 1] == 0x4B.toByte() &&
                    searchBuf[i + 2] == 0x05.toByte() &&
                    searchBuf[i + 3] == 0x06.toByte()
                ) {
                    eocdOffsetInBuf = i
                    break
                }
            }
            if (eocdOffsetInBuf < 0) return null

            val eocdOffset = searchStart + eocdOffsetInBuf
            raf.seek(eocdOffset + 10) // total entries
            val totalEntries = readUInt16LE(raf)
            raf.skipBytes(4) // central directory size
            val centralDirOffset = readUInt32LE(raf)

            raf.seek(centralDirOffset)
            repeat(totalEntries) {
                val sig = readUInt32LE(raf)
                if (sig != 0x02014B50L) return null
                raf.skipBytes(4) // version made by + version needed
                val flags = readUInt16LE(raf)
                val method = readUInt16LE(raf)
                // last mod time/date (4) + crc32 (4) + compressed size (4) + uncompressed size (4)
                raf.skipBytes(16)
                val nameLen = readUInt16LE(raf)
                val extraLen = readUInt16LE(raf)
                val commentLen = readUInt16LE(raf)
                raf.skipBytes(8) // disk number start + internal attr + external attr
                val localHeaderOffset = readUInt32LE(raf)

                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                val charset = if ((flags and 0x800) != 0) Charsets.UTF_8 else Charsets.ISO_8859_1
                val name = String(nameBytes, charset)

                raf.skipBytes(extraLen + commentLen)

                if (name == targetName) {
                    raf.seek(localHeaderOffset)
                    if (readUInt32LE(raf) != 0x04034B50L) return null
                    raf.skipBytes(22)
                    val localNameLen = readUInt16LE(raf)
                    val localExtraLen = readUInt16LE(raf)
                    val dataOffset = localHeaderOffset + 30L + localNameLen + localExtraLen
                    return ZipEntryInfo(method = method, dataOffset = dataOffset)
                }
            }
        }
        return null
    }

    private fun readUInt16LE(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        if (b0 < 0 || b1 < 0) throw EOFException("Unexpected EOF while reading uint16")
        return b0 or (b1 shl 8)
    }

    private fun readUInt32LE(raf: RandomAccessFile): Long {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) throw EOFException("Unexpected EOF while reading uint32")
        return (b0.toLong() and 0xFF) or
                ((b1.toLong() and 0xFF) shl 8) or
                ((b2.toLong() and 0xFF) shl 16) or
                ((b3.toLong() and 0xFF) shl 24)
    }

    private fun canParsePackageArchive(apkPath: String): Boolean {
        return packageArchiveInfoOrNull(apkPath) != null
    }

    private fun packageArchiveInfoOrNull(apkPath: String): android.content.pm.PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(apkPath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkPath, 0)
        }
    }

    private fun downloadFile(urlStr: String, dest: File): Boolean {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "ForgeApp/1.0")
            if (conn.responseCode != 200) return false
            val tmp = File(dest.parent, dest.name + ".tmp")
            conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(dest)
            dest.exists() && dest.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "Download failed [$urlStr]: ${e.message}")
            false
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// APK v1 Signer — proper PKCS#7 via BouncyCastle
// ─────────────────────────────────────────────────────────────────

class ApkSigner(
    private val privateKey: java.security.PrivateKey,
    private val certificate: java.security.cert.X509Certificate
) {
    fun sign(input: File, output: File) {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(FileInputStream(input)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zis.readBytes()
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // Build MANIFEST.MF with SHA-256 digests of every entry
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val manifest = StringBuilder("Manifest-Version: 1.0\r\nCreated-By: Forge\r\n\r\n")
        entries.forEach { (name, bytes) ->
            val digest = android.util.Base64.encodeToString(sha256.digest(bytes), android.util.Base64.NO_WRAP)
            manifest.append("Name: $name\r\nSHA-256-Digest: $digest\r\n\r\n")
        }
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)

        // Build CERT.SF with manifest digest
        val certSfText = "Signature-Version: 1.0\r\nCreated-By: Forge\r\n" +
                "SHA-256-Digest-Manifest: ${android.util.Base64.encodeToString(sha256.digest(manifestBytes), android.util.Base64.NO_WRAP)}\r\n\r\n"
        val certSfBytes = certSfText.toByteArray(Charsets.UTF_8)

        // Build CERT.RSA — proper PKCS#7 SignedData using BouncyCastle
        java.security.Security.addProvider(BouncyCastleProvider())

        val cmsGen = CMSSignedDataGenerator()
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(privateKey)
        val digestCalcProvider = JcaDigestCalculatorProviderBuilder()
            .setProvider("BC")
            .build()
        cmsGen.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(digestCalcProvider)
                .build(contentSigner, certificate)
        )
        cmsGen.addCertificate(org.bouncycastle.cert.jcajce.JcaX509CertificateHolder(certificate))

        val cmsData = CMSProcessableByteArray(certSfBytes)
        val signedData = cmsGen.generate(cmsData, false) // detached signature
        val certRsaBytes = signedData.encoded

        // Write signed APK
        ZipOutputStream(FileOutputStream(output)).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); zos.write(manifestBytes); zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/CERT.SF")); zos.write(certSfBytes); zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/CERT.RSA")); zos.write(certRsaBytes); zos.closeEntry()
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Data classes
// ─────────────────────────────────────────────────────────────────

data class BuildEnvironment(
    val projectRoot: File,
    val srcDir: File,
    val classesDir: File,
    val dexDir: File,
    val outputDir: File,
    val resDir: File,
    val androidJar: File,
    val buildDir: File
)

private data class ZipEntryInfo(
    val method: Int,
    val dataOffset: Long
)

private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var bytesWritten: Long = 0
        private set

    override fun write(b: Int) {
        out.write(b)
        bytesWritten += 1
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        bytesWritten += len.toLong()
    }
}

sealed class BuildEvent {
    data class Log(val message: String) : BuildEvent()
    data class Success(val result: BuildResult) : BuildEvent()
    data class Error(val message: String) : BuildEvent()
    data class Progress(val percent: Int) : BuildEvent()
}
