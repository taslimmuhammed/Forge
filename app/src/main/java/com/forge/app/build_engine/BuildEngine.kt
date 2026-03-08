package com.forge.app.build_engine

import android.content.Context
import android.content.Intent
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
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BuildEngine(
    private val context: Context,
    private val fileManager: ProjectFileManager
) {
    companion object {
        private const val TAG = "ForgeBuilder"
        private const val ECJ_VERSION = "3.26.0"
        private const val ECJ_JAR_URL =
            "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/$ECJ_VERSION/ecj-$ECJ_VERSION.jar"
        private const val ANDROID_JAR_URL =
            "https://github.com/Reginer/aosp-android-jar/raw/main/android-26/android.jar"
    }

    private val toolsDir = File(context.filesDir, "forge_tools").also { it.mkdirs() }
    private val ecjJar get() = File(toolsDir, "ecj-$ECJ_VERSION.jar")
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
                    "Could not download build tools. Check your internet connection and try again."
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

            // ── Step 4: compile ────────────────────────────────────────
            send(BuildEvent.Log("☕ Compiling Java sources..."))
            val compileResult = compileWithEcj(env, deps)
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

    private fun ensureBuildTools(log: suspend (String) -> Unit): Boolean {
        if (!ecjJar.exists()) {
            // log is suspend but we're in a blocking context — call synchronously via runBlocking
            // Actually we're already on IO dispatcher, so call a non-suspend version
            Log.d(TAG, "Downloading ECJ...")
            if (!downloadFile(ECJ_JAR_URL, ecjJar)) {
                Log.e(TAG, "Failed to download ECJ")
                return false
            }
        }
        if (!androidJar.exists()) {
            Log.d(TAG, "Downloading android.jar...")
            if (!downloadFile(ANDROID_JAR_URL, androidJar)) {
                Log.e(TAG, "Failed to download android.jar")
                return false
            }
        }
        return true
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 2 — Prepare build directories
    // ─────────────────────────────────────────────────────────────────

    private fun prepareBuildEnvironment(): BuildEnvironment {
        val root = fileManager.getProjectRoot()
        val buildDir = File(root, "build")
        val classesDir = File(buildDir, "classes").also { it.deleteRecursively(); it.mkdirs() }
        val dexDir = File(buildDir, "dex").also { it.deleteRecursively(); it.mkdirs() }
        val outputDir = File(buildDir, "output").also { it.mkdirs() }
        val resDir = File(buildDir, "res").also { it.mkdirs() }
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
    // STEP 4 — Compile Java → .class via ECJ
    // ─────────────────────────────────────────────────────────────────

    private fun compileWithEcj(env: BuildEnvironment, deps: List<DependencySpec>): BuildResult {
        val javaFiles = env.srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.absolutePath }
            .toList()

        if (javaFiles.isEmpty()) {
            return BuildResult(false, errorLog = "No .java source files found in ${env.srcDir}")
        }

        val classpath = buildClasspath(env, deps)

        // Try ECJ (loaded from downloaded JAR via DexClassLoader)
        val ecjResult = tryEcj(javaFiles, classpath, env.classesDir)
        if (ecjResult.success) return ecjResult

        // Fallback: any javac on the system
        return tryJavac(javaFiles, classpath, env.classesDir)
    }

    private fun tryEcj(javaFiles: List<String>, classpath: String, outputDir: File): BuildResult {
        if (!ecjJar.exists()) return BuildResult(false, errorLog = "ECJ jar not found")
        return try {
            val ecjDexDir = File(toolsDir, "ecj_dex").also { it.mkdirs() }
            val loader = DexClassLoader(ecjJar.absolutePath, ecjDexDir.absolutePath, null, context.classLoader)
            val mainClass = loader.loadClass("org.eclipse.jdt.internal.compiler.batch.Main")

            val errSw = StringWriter()
            val outSw = StringWriter()
            val instance = mainClass.getConstructor(
                PrintWriter::class.java,
                PrintWriter::class.java,
                Boolean::class.javaPrimitiveType,
                java.util.Map::class.java,
                Any::class.java
            ).newInstance(PrintWriter(outSw), PrintWriter(errSw), false, null, null)

            val args = (listOf("-source", "8", "-target", "8", "-cp", classpath,
                "-d", outputDir.absolutePath, "-encoding", "UTF-8", "-nowarn") + javaFiles).toTypedArray()

            val success = mainClass.getMethod("compile", Array<String>::class.java)
                .invoke(instance, args) as Boolean

            val errText = errSw.toString().trim()
            if (success) BuildResult(true)
            else BuildResult(false, errorLog = errText.ifEmpty { "ECJ: Compilation failed" })
        } catch (e: Exception) {
            Log.w(TAG, "ECJ failed: ${e.message}")
            BuildResult(false, errorLog = "ECJ error: ${e.message}")
        }
    }

    private fun tryJavac(javaFiles: List<String>, classpath: String, outputDir: File): BuildResult {
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
                    "Install Termux from F-Droid then run:\n  pkg install openjdk-17\n\nThen tap Run again."
        )
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 5 — DEX conversion
    // ─────────────────────────────────────────────────────────────────

    private fun createDex(env: BuildEnvironment): BuildResult {
        val classFiles = env.classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        if (classFiles.isEmpty()) return BuildResult(false, errorLog = "No .class files found after compilation")

        val dexFile = File(env.dexDir, "classes.dex")

        // Try dx.jar bundled in system
        tryDxJar(env, dexFile)?.let { if (it.success) return it }

        // Try dx shell command
        tryShellCommand(listOf("dx", "--dex", "--output=${dexFile.absolutePath}", env.classesDir.absolutePath))
            .let { if (it.success && dexFile.exists()) return BuildResult(true) }

        // Try Termux dx
        val termuxDx = "/data/data/com.termux/files/usr/bin/dx"
        if (File(termuxDx).canExecute()) {
            tryShellCommand(listOf(termuxDx, "--dex", "--output=${dexFile.absolutePath}", env.classesDir.absolutePath))
                .let { if (it.success && dexFile.exists()) return BuildResult(true) }
        }

        return BuildResult(
            false,
            errorLog = "DEX conversion failed.\n\nInstall Termux from F-Droid then run:\n  pkg install dx\n\nThen tap Run again."
        )
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

        // aapt2 compile + link
        val compiledDir = File(env.resDir, "compiled").also { it.mkdirs() }
        val aapt2Result = tryAapt2("aapt2", resDir, manifestFile, compiledDir, outputApk, androidJarPath)
        if (aapt2Result.success) return aapt2Result

        // Termux aapt2
        val termuxAapt2 = "/data/data/com.termux/files/usr/bin/aapt2"
        if (File(termuxAapt2).canExecute()) {
            val r = tryAapt2(termuxAapt2, resDir, manifestFile, compiledDir, outputApk, androidJarPath)
            if (r.success) return r
        }

        // aapt v1
        val aaptResult = tryAapt("aapt", resDir, manifestFile, genDir, outputApk, androidJarPath)
        if (aaptResult.success) return aaptResult

        // Termux aapt v1
        val termuxAapt = "/data/data/com.termux/files/usr/bin/aapt"
        if (File(termuxAapt).canExecute()) {
            val r = tryAapt(termuxAapt, resDir, manifestFile, genDir, outputApk, androidJarPath)
            if (r.success) return r
        }

        // Last resort: bundle files as plain zip (works for simple apps without resources)
        return buildPlainResourcePackage(env, manifestFile, resDir, outputApk)
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

    private fun buildPlainResourcePackage(
        env: BuildEnvironment, manifestFile: File, resDir: File, outputApk: File
    ): BuildResult {
        return try {
            ZipOutputStream(FileOutputStream(outputApk)).use { zos ->
                // AndroidManifest.xml
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                manifestFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
                // res/ files
                if (resDir.exists()) {
                    resDir.walkTopDown().filter { it.isFile }.forEach { f ->
                        zos.putNextEntry(ZipEntry("res/" + f.relativeTo(resDir).path))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            BuildResult(true)
        } catch (e: Exception) {
            BuildResult(false, errorLog = "Resource packaging failed: ${e.message}\n\n" +
                    "Install Termux from F-Droid then run:\n  pkg install aapt\n\nThen tap Run again.")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 7 — Assemble APK (merge resources.ap_ + classes.dex)
    // ─────────────────────────────────────────────────────────────────

    private fun assembleApk(env: BuildEnvironment): String {
        val outputApk = File(env.outputDir, "app-unsigned.apk")
        val resourcesApk = File(env.outputDir, "resources.ap_")
        val dexFile = File(env.dexDir, "classes.dex")

        ZipOutputStream(FileOutputStream(outputApk)).use { zos ->
            // Copy everything from resources.ap_
            if (resourcesApk.exists()) {
                ZipInputStream(FileInputStream(resourcesApk)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        try {
                            zos.putNextEntry(ZipEntry(entry.name))
                            zis.copyTo(zos)
                            zos.closeEntry()
                        } catch (e: Exception) { /* skip duplicates */ }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            // Add classes.dex
            if (dexFile.exists()) {
                zos.putNextEntry(ZipEntry("classes.dex"))
                FileInputStream(dexFile).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return outputApk.absolutePath
    }

    // ─────────────────────────────────────────────────────────────────
    // STEP 8 — Sign APK (pure Java, no keytool needed)
    // ─────────────────────────────────────────────────────────────────

    private fun signApk(unsignedApkPath: String): String {
        val unsignedApk = File(unsignedApkPath)
        val signedApk = File(unsignedApk.parent, "app-debug.apk")

        // Try pure-Java in-process signing
        try {
            val (privateKey, cert) = getOrCreateDebugKey()
            ApkSigner(privateKey, cert).sign(unsignedApk, signedApk)
            return signedApk.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "In-process signing failed: ${e.message}")
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
                if (r.success) return signedApk.absolutePath
            } catch (e: Exception) { continue }
        }

        // Last resort: copy unsigned
        unsignedApk.copyTo(signedApk, overwrite = true)
        return signedApk.absolutePath
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
                keystoreFile.delete()
            }
        }

        // Generate via Android Keystore (no BouncyCastle needed)
        val kpg = KeyPairGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore"
        )
        kpg.initialize(
            android.security.keystore.KeyGenParameterSpec.Builder(
                "forge_signing_v1",
                android.security.keystore.KeyProperties.PURPOSE_SIGN
            )
                .setKeySize(2048)
                .setSignaturePaddings(android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setDigests(android.security.keystore.KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=Forge Debug"))
                .setCertificateNotBefore(java.util.Date())
                .setCertificateNotAfter(java.util.Date(System.currentTimeMillis() + 10000L * 86400000))
                .build()
        )
        kpg.generateKeyPair()

        val ks = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val privateKey = ks.getKey("forge_signing_v1", null) as java.security.PrivateKey
        val cert = ks.getCertificate("forge_signing_v1") as java.security.cert.X509Certificate
        return Pair(privateKey, cert)
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
            val intent = Intent(context, PackageInstallReceiver::class.java)
            val pi = android.app.PendingIntent.getBroadcast(
                context, sessionId, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            session.commit(pi.intentSender)
            session.close()
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
// APK v1 Signer (pure Java)
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

        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val manifest = StringBuilder("Manifest-Version: 1.0\r\nCreated-By: Forge\r\n\r\n")
        entries.forEach { (name, bytes) ->
            val digest = android.util.Base64.encodeToString(sha256.digest(bytes), android.util.Base64.NO_WRAP)
            manifest.append("Name: $name\r\nSHA-256-Digest: $digest\r\n\r\n")
        }
        val manifestBytes = manifest.toString().toByteArray(Charsets.UTF_8)

        val certSfText = "Signature-Version: 1.0\r\nCreated-By: Forge\r\n" +
                "SHA-256-Digest-Manifest: ${android.util.Base64.encodeToString(sha256.digest(manifestBytes), android.util.Base64.NO_WRAP)}\r\n\r\n"
        val certSfBytes = certSfText.toByteArray(Charsets.UTF_8)

        val sig = java.security.Signature.getInstance("SHA256withRSA")
        sig.initSign(privateKey)
        sig.update(certSfBytes)
        val sigBytes = sig.sign()

        ZipOutputStream(FileOutputStream(output)).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name)); zos.write(bytes); zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("META-INF/MANIFEST.MF")); zos.write(manifestBytes); zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/CERT.SF")); zos.write(certSfBytes); zos.closeEntry()
            zos.putNextEntry(ZipEntry("META-INF/CERT.RSA")); zos.write(sigBytes); zos.closeEntry()
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

sealed class BuildEvent {
    data class Log(val message: String) : BuildEvent()
    data class Success(val result: BuildResult) : BuildEvent()
    data class Error(val message: String) : BuildEvent()
    data class Progress(val percent: Int) : BuildEvent()
}