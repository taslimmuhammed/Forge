# ⚡ Forge — AI Session Handoff Document

> This file is the complete context needed to continue developing Forge in a new Claude session.
> Paste this entire file at the start of your conversation.

---

## What is Forge?

Forge is an on-device Android IDE app powered by Claude Opus 4.6. It lets users build, compile, install, and run Android apps entirely on their phone using natural language chat — no computer required. Think Cursor / Claude Code, but for Android, running on Android.

---

## Current State — What Is Built and Working

### ✅ Fully implemented
- **Project management** — Create / rename / delete multiple Android projects, each with name + domain + package name
- **Chat screen per project** — Full chat UI with user / assistant / system message types, persisted to disk
- **Claude Opus 4.6 agent** (`ForgeAgent.kt`) — Reads forge.md memory, selects relevant files, calls API, parses structured JSON response, applies file operations, updates forge.md
- **forge.md memory system** — Per-project markdown memory file tracking screens, dependencies, architecture decisions, user preferences; read before every agent call, updated after
- **Token optimization** — File hashing (only changed files sent), conversation summarization after 8 turns, max 10 turns per API call, keyword-based file relevance scoring
- **On-device build pipeline** (`BuildEngine.kt`) — Full Java → DEX → APK → Sign → Install pipeline with multi-tier fallbacks at each stage
- **Boilerplate generation** — Blank Android app generated on project creation (no API key needed)
- **Auto error repair** — Build errors automatically fed back to Claude to fix (only triggers when API key is set and it's a code error, not a tooling error)
- **APK installation** — PackageInstaller API (Android 8+) with intent fallback
- **APK export/share** — Share built APK via Android share sheet
- **Settings screen** — API key management, install permission grant, Termux setup guide
- **BuildService** — Foreground service keeps process alive during long builds
- **Version bumping** — versionCode auto-incremented before each build so reinstall works
- **Chat history persistence** — Messages saved to JSON per project, survive app restarts
- **Rename project** — Full rename flow with dialog
- **Permission helper** — REQUEST_INSTALL_PACKAGES runtime permission handling

### ⚠️ Known Build Issues Fixed in Session
1. `CodeView` dependency removed (wasn't used, couldn't resolve)
2. Gradle files converted from Groovy `.gradle` → Kotlin Script `.kts` syntax
3. `Flow invariant violated` — Fixed by using `channelFlow` + `withContext(Dispatchers.IO)` + a `ProducerScope<BuildEvent>` extension function so `return` statements work correctly
4. `newBinarySerializer` unresolved reference — Removed, replaced with `android.util.Xml.newSerializer()`
5. `@RequiresApi` annotation removed (was only needed for the removed API)
6. Hex literal syntax error `0xFF BF360C` → `0xFFBF360C.toInt()` in ProjectAdapter
7. All `return@withContext` labels replaced with plain `return` inside proper suspend functions

---

## File Map — Every File and Its Purpose

### Root
```
ForgeApp/
├── build.gradle.kts          ← Root Gradle (Kotlin Script syntax)
├── settings.gradle.kts       ← Includes :app, declares repos (includes jitpack)
├── gradle.properties         ← JVM args, AndroidX flags
├── gradlew                   ← Gradle wrapper script
└── gradle/wrapper/
    └── gradle-wrapper.properties  ← Points to Gradle 8.0
```

### App module
```
app/
├── build.gradle.kts          ← App-level Gradle (viewBinding=true, dataBinding=true, minSdk=26)
└── proguard-rules.pro        ← Keep rules for Room, ECJ, OkHttp, Gson
```

### Kotlin Sources — `app/src/main/java/com/forge/app/`
```
ForgeApplication.kt           ← App class, creates NotificationChannel for BuildService

MainActivity.kt               ← Home screen: project grid, FAB, rename/delete/export options

agent/
└── ForgeAgent.kt             ← Claude Opus 4.6 API client
                                 Reads forge.md + relevant files → builds prompt
                                 Parses structured JSON response → applies file ops
                                 Manages conversation history + summarization
                                 Returns: AgentStreamEvent (Thinking/Complete/Error/NeedsConfirmation)

build_engine/
├── BuildEngine.kt            ← Full on-device build pipeline
│                                channelFlow + withContext(IO) + ProducerScope extension
│                                Step 1: Download android.jar (first run only, cached)
│                                Step 2: Prepare build dirs
│                                Step 3: Resolve + download dependencies
│                                Step 4: Compile Java via ECJ (bundled Gradle dependency) → javac fallback
│                                Step 5: DEX via dx.jar reflection → dx shell → Termux dx
│                                Step 6: Resources via aapt2 → aapt → Termux aapt → plain zip
│                                Step 7: Assemble APK (merge resources.ap_ + classes.dex)
│                                Step 8: Sign via Android Keystore (pure Java) → apksigner shell
│                                Emits: BuildEvent.Log / BuildEvent.Success / BuildEvent.Error
├── BuildService.kt           ← Foreground service, keeps process alive during builds
│                                Shows notification with cancel action
│                                Exposes BuildService.startBuild() / cancelBuild()
├── PackageInstallReceiver.kt ← BroadcastReceiver for PackageInstaller session results
│                                Broadcasts INSTALL_RESULT_ACTION with success/packageName
└── VersionManager.kt         ← Reads/writes .forge/version_code.txt
                                 bumpVersionInBuildGradle() patches generated app's build.gradle

data/
├── db/
│   └── ForgeDatabase.kt      ← Room database, holds ForgeProjectDao
├── models/
│   └── Models.kt             ← ForgeProject, ChatMessage, AgentResponse, AgentOperation,
│                                BuildResult, DependencySpec, MessageRole, BuildStatus,
│                                OperationType, AgentStreamEvent
└── repository/
    ├── ChatHistoryManager.kt ← Persists chat messages to JSON per project
    │                            Location: /files/projects/{id}/.forge/chat_history.json
    │                            Keeps last 100 messages, used by ChatViewModel
    ├── ProjectFileManager.kt ← All file I/O for generated projects
    │                            getProjectRoot(), readFile(), writeFile()
    │                            readForgeMd() / writeForgeMd()
    │                            writeBoilerplate() — generates blank Android app on create
    │                            srcMainDir property used by BuildEngine
    └── ProjectRepository.kt  ← Room-backed CRUD for ForgeProject
                                 updateBuildStatus(), markBuilt(), deleteProject()

ui/
├── SettingsActivity.kt       ← API key change/clear, install permission grant,
│                                Termux setup guide, app version
├── chat/
│   ├── ApiKeyDialog.kt       ← DialogFragment shown when user sends first message
│   │                            without an API key set
│   ├── ChatActivity.kt       ← Chat screen: toolbar, RecyclerView, send button, Run button
│   │                            Registers install BroadcastReceiver
│   │                            Overflow menu: Export APK, Clear Chat, Settings
│   ├── ChatViewModel.kt      ← Orchestrates agent + build + install
│   │                            sendMessage() → ForgeAgent → applies ops → addAssistantMessage
│   │                            buildAndRun() → VersionManager → BuildService → BuildEngine
│   │                            autoRepairBuildError() — only when API key set + code error
│   │                            clearChatHistory(), addSystemMessagePublic()
│   └── MessageAdapter.kt     ← RecyclerView adapter for 3 view types: user/assistant/system
└── home/
    ├── HomeViewModel.kt      ← Loads projects from Room, createProject(), deleteProject(),
    │                            renameProject()
    ├── NewProjectDialog.kt   ← DialogFragment: name + domain + appName inputs
    └── ProjectAdapter.kt     ← RecyclerView adapter for project cards grid

utils/
├── PermissionHelper.kt       ← canInstallPackages(), openInstallPermissionSettings(),
│                                hasStoragePermission()
└── SecureStorage.kt          ← EncryptedSharedPreferences wrapper for API key
                                 saveApiKey(), getApiKey(), hasApiKey(), clearApiKey()
```

### Resources — `app/src/main/res/`
```
layout/
├── activity_main.xml         ← CoordinatorLayout: toolbar + project grid RecyclerView + FAB
├── activity_chat.xml         ← Chat screen: toolbar + message list + build log sheet + input bar
├── activity_settings.xml     ← Settings: API key section + build tools section + about
├── dialog_api_key.xml        ← API key entry dialog
├── dialog_new_project.xml    ← New project dialog: 3 inputs
├── item_project_card.xml     ← Project card: name, package, status badge, last modified
├── item_message_user.xml     ← User chat bubble (right-aligned, purple)
├── item_message_assistant.xml← Assistant bubble (left-aligned, dark surface)
└── item_message_system.xml   ← System message (centered, dim text, error variant)

menu/
├── menu_main.xml             ← Main screen overflow: Settings
└── menu_chat.xml             ← Chat screen overflow: Export APK, Clear Chat, Settings

values/
├── colors.xml                ← Dark theme palette: forge_bg, forge_surface, forge_purple etc.
├── strings.xml               ← App strings
└── themes.xml                ← Theme.Forge (dark, no action bar — uses Toolbar)

drawable/
└── ic_launcher_foreground.xml← Lightning bolt vector for adaptive icon

mipmap-anydpi-v26/
├── ic_launcher.xml           ← Adaptive icon (background=#6C63FF, foreground=lightning bolt)
└── ic_launcher_round.xml     ← Same

xml/
├── file_paths.xml            ← FileProvider paths (used for APK sharing)
├── backup_rules.xml          ← Excludes API key from backup
└── data_extraction_rules.xml ← Android 12+ data extraction config
```

---

## Key Architecture Decisions

### Build Pipeline Design
The build pipeline uses `channelFlow` (not `flow`) because it needs to emit from `Dispatchers.IO`. The entire pipeline runs in a single `withContext(Dispatchers.IO)` block via a `ProducerScope<BuildEvent>` extension function called `runBuildPipeline`. This is the only correct pattern — any `flow { withContext(IO) { emit() } }` will throw `Flow invariant violated`.

```kotlin
fun build(packageName: String): Flow<BuildEvent> = channelFlow {
    withContext(Dispatchers.IO) {
        runBuildPipeline(packageName)  // extension on ProducerScope, plain return works
    }
}
```

### ECJ Compiler Loading
ECJ (Eclipse Java Compiler) is bundled as a direct Gradle dependency (`org.eclipse.jdt:ecj:3.33.0`). It is loaded at runtime via `Class.forName("org.eclipse.jdt.internal.compiler.batch.Main")` and invoked through reflection. The `compile()` method is called with `-source 8 -target 8` flags for maximum compatibility.

### API Key Security
Stored in Android Keystore via `EncryptedSharedPreferences`. Never logged or sent anywhere except Anthropic's API endpoint.

### Agent Structured Output
The agent is prompted to return **only** a JSON object in this exact shape:
```json
{
  "thinking": "internal reasoning",
  "userMessage": "friendly message for user — NO code shown",
  "operations": [
    {"type": "write", "path": "app/src/main/java/...", "content": "..."},
    {"type": "delete", "path": "..."},
    {"type": "mkdir", "path": "..."}
  ],
  "forgeMdUpdate": "complete updated forge.md content",
  "needsConfirmation": false,
  "newDependencies": ["group:artifact:version"]
}
```

### forge.md Memory Format
```markdown
# ProjectName — forge.md

## App Overview
[what the app does]

## Screens
- MainActivity — [description]

## Architecture
[patterns used]

## Dependencies
[list]

## Known Issues
[list]

## User Preferences
[learned preferences]

## Recent Changes
[last few changes]
```

---

## Dependencies in app/build.gradle.kts

```kotlin
// Core
androidx.core:core-ktx:1.12.0
androidx.annotation:annotation:1.7.1
androidx.appcompat:appcompat:1.6.1
com.google.android.material:material:1.11.0
androidx.constraintlayout:constraintlayout:2.1.4

// Lifecycle / ViewModel
androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2
androidx.lifecycle:lifecycle-livedata-ktx:2.6.2
androidx.lifecycle:lifecycle-runtime-ktx:2.6.2
androidx.activity:activity-ktx:1.8.2
androidx.fragment:fragment-ktx:1.6.2

// Navigation
androidx.navigation:navigation-fragment-ktx:2.7.6
androidx.navigation:navigation-ui-ktx:2.7.6

// Room
androidx.room:room-runtime:2.6.1
androidx.room:room-ktx:2.6.1
kapt androidx.room:room-compiler:2.6.1

// WorkManager
androidx.work:work-runtime-ktx:2.9.0

// RecyclerView / CardView
androidx.recyclerview:recyclerview:1.3.2
androidx.cardview:cardview:1.0.0

// Security (API key storage)
androidx.security:security-crypto:1.1.0-alpha06

// Networking (Anthropic API + Maven downloads)
com.squareup.okhttp3:okhttp:4.12.0
com.squareup.okhttp3:logging-interceptor:4.12.0
com.google.code.gson:gson:2.10.1

// Coroutines
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3

// Markdown rendering (for AI responses)
io.noties.markwon:core:4.6.2
io.noties.markwon:syntax-highlight:4.6.2
io.noties.markwon:ext-strikethrough:4.6.2

// File operations
commons-io:commons-io:2.13.0

// Zip handling
net.lingala.zip4j:zip4j:2.11.5

// Lottie animations
com.airbnb.android:lottie:6.3.0

// Preferences
androidx.preference:preference-ktx:1.2.1
```

Build config:
- `minSdk = 26` (Android 8.0)
- `targetSdk = 34`
- `compileSdk = 34`
- `viewBinding = true`
- `dataBinding = true`
- `jvmTarget = "17"`
- `sourceCompatibility = JavaVersion.VERSION_17`

---

## AndroidManifest Key Entries

```xml
android:name=".ForgeApplication"          <!-- App class -->

<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Activities: MainActivity, ChatActivity, SettingsActivity -->
<!-- Service: BuildService (foregroundServiceType="dataSync") -->
<!-- Provider: FileProvider (authority="${applicationId}.fileprovider") -->
<!-- Receiver: PackageInstallReceiver -->
```

---

## Runtime Behavior — First Run

1. User creates first project → `ProjectFileManager.writeBoilerplate()` generates blank app
2. User taps Run → `BuildEngine` starts
3. First run: downloads android.jar (~16MB) to `/files/forge_tools/` — takes 10–30s on good WiFi
4. Subsequent runs: android.jar already cached, build starts immediately
5. If ECJ fails: detailed error shown with the actual compiler error
6. User enters API key on first chat message → stored in Android Keystore
7. Agent called → forge.md created/updated per project

---

## What Doesn't Work Yet (Honest Assessment)

| Issue | Severity | Notes |
|---|---|---|
| No Kotlin source support | High | ECJ only compiles Java. Kotlin needs `kotlinc` bundled or downloaded |
| DEX step may fail on some devices | High | `dx.jar` not present on all AOSP builds; Termux dx is the reliable fallback |
| AAPT2/AAPT not available on stock Android | High | Not bundled in user-facing Android. Plain zip fallback is a best-effort workaround |
| APK signing may produce invalid signature | Medium | v1 signing without full PKCS7 wrapping may be rejected by some Android versions |
| Generated app only supports Java | Medium | No Kotlin boilerplate, no Compose |
| Large dependency trees | Low | Mobile data + time constraints; may timeout |
| No Gradle support | Low | Custom pipeline only, not full Gradle compatibility |

**Practical reality**: The build pipeline will work reliably on devices where Termux is installed with `openjdk-21 + aapt + apksigner`. On stock Android without Termux, ECJ compilation may work but DEX and resource packaging steps often fail because Android doesn't ship `dx` or `aapt` as user-accessible binaries.

---

## Recommended Next Tasks (Priority Order)

### 1. 🔴 Fix DEX step — Most critical blocker
The `dx` tool is not reliably available. Options:
- Bundle D8 (Google's modern DEX compiler) as a pre-dexed JAR inside Forge's assets
- Or download it at runtime like ECJ
- D8 JAR URL: `https://maven.google.com/com/android/tools/r8/8.3.37/r8-8.3.37.jar`
- Call via reflection: `com.android.tools.r8.D8.main(args)`

### 2. 🔴 Fix resource packaging — Second critical blocker
AAPT2 is not available on stock Android. Options:
- Bundle a pre-compiled `aapt2` binary for arm64-v8a as a native lib in `jniLibs/`
- Extract at runtime to app's files dir, chmod +x, execute
- Source: Android SDK build-tools (can extract `aapt2` binary)

### 3. 🟡 Fix APK v1 signing
Current PKCS7 output is simplified. Need proper DER-encoded PKCS7 SignedData:
- Use `org.bouncycastle:bcpkix-jdk15on` (add to deps) for proper PKCS7
- Or use Android's `sun.security.pkcs.PKCS9Attribute` via reflection

### 4. 🟡 Add Kotlin compilation support
- Download `kotlin-compiler-embeddable` JAR at runtime
- Load via DexClassLoader same way as ECJ
- Add `.kt` file detection in BuildEngine

### 5. 🟢 Improve agent prompt quality
- The system prompt in ForgeAgent.kt can be tuned for better code quality
- Add examples of good vs bad responses to the prompt
- Consider adding a "review" step before applying operations

### 6. 🟢 Add streaming Claude responses
- Currently waits for full response before showing anything
- Use SSE streaming from Anthropic API for live token display
- Show "Claude is typing..." with partial response

### 7. 🟢 Template library
- Pre-built starter projects (calculator, to-do, weather, etc.)
- Show in NewProjectDialog as an option

---

## Files Location Summary

All 52 project files are at:
```
/mnt/user-data/outputs/ForgeApp/
```

The complete file tree:
```
ForgeApp/
├── HANDOFF.md                          ← This file
├── README.md                           ← User-facing docs
├── ARCHITECTURE.md                     ← Technical architecture
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/forge/app/
        │   ├── ForgeApplication.kt
        │   ├── MainActivity.kt
        │   ├── agent/ForgeAgent.kt
        │   ├── build_engine/
        │   │   ├── BuildEngine.kt
        │   │   ├── BuildService.kt
        │   │   ├── PackageInstallReceiver.kt
        │   │   └── VersionManager.kt
        │   ├── data/
        │   │   ├── db/ForgeDatabase.kt
        │   │   ├── models/Models.kt
        │   │   └── repository/
        │   │       ├── ChatHistoryManager.kt
        │   │       ├── ProjectFileManager.kt
        │   │       └── ProjectRepository.kt
        │   ├── ui/
        │   │   ├── SettingsActivity.kt
        │   │   ├── chat/
        │   │   │   ├── ApiKeyDialog.kt
        │   │   │   ├── ChatActivity.kt
        │   │   │   ├── ChatViewModel.kt
        │   │   │   └── MessageAdapter.kt
        │   │   └── home/
        │   │       ├── HomeViewModel.kt
        │   │       ├── NewProjectDialog.kt
        │   │       └── ProjectAdapter.kt
        │   └── utils/
        │       ├── PermissionHelper.kt
        │       └── SecureStorage.kt
        └── res/
            ├── drawable/ic_launcher_foreground.xml
            ├── layout/ (9 XML files)
            ├── menu/ (2 XML files)
            ├── mipmap-anydpi-v26/ (2 XML files)
            ├── values/ (colors, strings, themes)
            └── xml/ (backup_rules, data_extraction_rules, file_paths)
```

---

## How to Ask Claude to Continue

Start your new session with:

> "I'm continuing development of Forge, an on-device Android IDE app powered by Claude Opus. Here is the full project context: [paste this file]. I need help with: [your task]"

Good starting tasks:
- "Fix the DEX compilation step by bundling D8"
- "Fix the APK resource packaging step by bundling aapt2 as a native library"
- "Add Kotlin compilation support"
- "Fix the APK v1 signing to produce valid PKCS7"
- "There's a new compiler error: [paste error]"

---

*Forge v1.0 — Session handoff generated after full build pipeline implementation*
*Model used: Claude Sonnet 4.6 · Target model for Forge: claude-opus-4-6*