# Forge

Forge is an on-device Android IDE app that lets users create Android apps from chat prompts, build APKs, and install them on-device.

## Current State (March 10, 2026)

- Project CRUD and per-project chat history are stable.
- Build pipeline runs fully on-device: `Java -> class -> DEX -> resources -> APK -> sign -> install`.
- Build tools are now offline-first:
  - `android.jar` is bundled in app assets and extracted at runtime.
  - Network download is fallback only.
- Install flow now handles PackageInstaller callbacks correctly, including user-confirmation (`STATUS_PENDING_USER_ACTION`).
- Build log UX improved:
  - full error is shown in chat (no 300-char truncation),
  - full log dialog is available in chat,
  - long logs are chunked to Logcat (`ForgeBuildLog`).
- New project boilerplate is dependency-free by default (`android.app.Activity`), so first build can work offline.
- Legacy projects that used `AppCompatActivity` are auto-migrated when loaded.
- Chat Run button moved out of the toolbar to a dedicated right-aligned row below the app bar to avoid notch overlap.

## Key Paths

- App module: `app/`
- Build pipeline: `app/src/main/java/com/forge/app/build_engine/BuildEngine.kt`
- Install callback receiver: `app/src/main/java/com/forge/app/build_engine/PackageInstallReceiver.kt`
- Chat orchestration: `app/src/main/java/com/forge/app/ui/chat/ChatViewModel.kt`
- Chat UI: `app/src/main/java/com/forge/app/ui/chat/ChatActivity.kt`
- Project boilerplate generator: `app/src/main/java/com/forge/app/data/repository/ProjectFileManager.kt`

## Build + Install Notes

### Install callback behavior

- Successful build no longer implies install success.
- APK is now validated before build success is emitted:
  - binary `AndroidManifest.xml` check
  - `PackageManager.getPackageArchiveInfo()` parse check
- If invalid, build fails early with a clear error instead of hanging at install.

### Useful Logcat tags

- `ForgeBuilder` -> build pipeline logs
- `ForgeBuildLog` -> full build errors and full build log chunks
- `PackageInstaller` -> install session status and status message

## Known Limitations

- Kotlin source compilation for generated projects is not implemented yet.
- Resource packaging on stock Android is still constrained without native `aapt2/aapt`.
- On some devices, installing Termux build tools improves reliability:
  - `pkg install aapt aapt2 apksigner`

## Local Verify Commands

```bash
./gradlew :app:compileDebugKotlin -x lint
./gradlew :app:assembleDebug -x lint
```
