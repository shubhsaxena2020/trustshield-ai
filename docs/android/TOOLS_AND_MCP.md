# Tools and verified commands (Windows PowerShell, 25 Oct 2026)

Run from repo root unless `Set-Location apps/android` is shown. This section records **tried** commands separately from future commands. Set `ANDROID_HOME` in each shell session only; do not persist it globally or commit `local.properties`.

## Verified in Phase 1

```powershell
java -version                                   # Microsoft OpenJDK 17.0.10
adb devices                                     # worked; no devices attached
$env:ANDROID_HOME='C:\Users\shubh\AppData\Local\Android\Sdk'
Set-Location apps/android
& 'C:\Users\shubh\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat' wrapper --gradle-version 8.14.3 --distribution-type bin
.\gradlew.bat :app:assembleDebug --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug --console=plain
Get-FileHash -Algorithm SHA256 app/build/outputs/apk/debug/app-debug.apk
& "$env:ANDROID_HOME\build-tools\36.0.0\aapt.exe" dump badging app/build/outputs/apk/debug/app-debug.apk
& "$env:ANDROID_HOME\build-tools\36.0.0\apksigner.bat" verify --print-certs app/build/outputs/apk/debug/app-debug.apk
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --list_installed
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\avdmanager.bat" list avd
```

Wrapper generation succeeded (`BUILD SUCCESSFUL in 2m 9s`). First APK attempt with Compose BOM `2026.08.00` failed its AAR metadata gate: Compose 1.12 requires compile SDK 37 and AGP 9.1; machine has SDK 36/AGP 8.13. Pinned BOM `2025.04.01` (Compose 1.8), then `assembleDebug` succeeded in 1m 21s. `:app:testDebugUnitTest :app:lintDebug` also succeeded in 43s; tests were `NO-SOURCE` because this is only build proof. APK path: `apps/android/app/build/outputs/apk/debug/app-debug.apk`; 10,090,308 bytes, package `ai.trustshield.android`, minSdk 26, target/compile SDK 36. SHA-256: `A39995443D4C79EC91A0443C27905F5A0508A86EC17BEF51DD6A8079A408F766`. `apksigner verify --print-certs` passed with Android Debug certificate. Artifact is generated and git-ignored; source/wrapper were merged via PR #52.

SDK platforms: `android-36`; build-tools initially 34.0.0 and 36.0.0; Gradle installed 35.0.0 during the build. `sdkmanager --list_installed` confirmed these packages; it warned of newer SDK XML version 4, but exited 0. `avdmanager list avd` showed none. JDK and `adb` are on PATH; Gradle is not on PATH, but cached 8.14.3 and checked-in wrapper work. `gh auth status` succeeded as `shubhsaxena2020`; labels and 27 package issues were created. No phone was attached. No emulator executable or system image was found; `Win32_Processor.VirtualizationFirmwareEnabled` reported false, so do not budget an emulator without a host change.

## Next-stage commands: only use when prerequisites exist

```powershell
$sdk='C:\Users\shubh\AppData\Local\Android\Sdk'
& "$sdk\emulator\emulator.exe" -list-avds       # only if emulator.exe exists
adb devices
adb install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p ai.trustshield.android 1
adb shell monkey -p ai.trustshield.android 500
adb shell dumpsys meminfo ai.trustshield.android
```

If the owner enables host virtualization and chooses an emulator, a **candidate, untried** setup is `sdkmanager.bat "emulator" "system-images;android-36;google_apis;x86_64"`, then `avdmanager.bat create avd -n TrustShieldApi36 -k "system-images;android-36;google_apis;x86_64"`, then `emulator.exe -avd TrustShieldApi36`; confirm package availability with `sdkmanager --list` before installing. An emulator system image/AVD should be installed only if host virtualization and RAM are measured adequate; it is not proof of phone compatibility. Debug APK is already debug-signed by Gradle, and its signature was verified. A release APK would need a locally stored signing key and `:app:assembleRelease`; never commit a keystore/password. Prefer the debug-signed APK for the 30 Oct sideload if a release key is unavailable. No install or launch of the Phase 1 APK was claimed.

Research used agent-reach's Exa search workflow (`agent-reach doctor --json`, `mcporter call exa.web_search_exa ...`) and checked official Android/Google/Gradle sources linked in `ARCHITECTURE.md`. Graphify queried existing `graphify-out/graph.json` for API/fusion/chat/file relationships; direct source reads resolved contract and privacy details. No submitted URL/QR destination was opened.
