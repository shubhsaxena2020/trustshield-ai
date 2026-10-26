# Android debug install

Build the debug APK from a checkout with Android SDK 36 and JDK 17. In PowerShell:

```powershell
cd apps/android
$env:ANDROID_HOME = 'C:\Users\shubh\AppData\Local\Android\Sdk'
.\gradlew.bat assembleDebug
```

The build creates ABI split APKs. Choose the one matching the device; this workspace's emulator uses x86_64:

- Emulator: `apps/android/app/build/outputs/apk/debug/app-x86_64-debug.apk`
- ARM64 phone: `apps/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- 32-bit ARM phone: `apps/android/app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk`
- x86 emulator: `apps/android/app/build/outputs/apk/debug/app-x86-debug.apk`

Install on the attached x86_64 emulator from the repository root:

```powershell
adb -s emulator-5554 install -r apps/android/app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s emulator-5554 shell monkey -p ai.trustshield.android 1
```

## Current x86_64 artifact

Built from the source tree merged as `b766cb9` (P3-06 Result score placement fix):

- File: `apps/android/app/build/outputs/apk/debug/app-x86_64-debug.apk`
- Package: `ai.trustshield.android`
- Version: `0.1.0` (version code `1`)
- Minimum Android: API 26 (Android 8.0)
- Target Android: API 36
- Size: 37,266,272 bytes
- SHA-256: `F5ECC52D0F58E5AA6C3DD29897B208DE98A4B256C1353BFD0076AC8E5D0B0E72`
- Signing: local debug key from the Android Gradle build; this is a demo/debug artifact, not a production release signature.

The APK is present in the local ignored Gradle output directory and is not committed to Git. Re-run the build command to regenerate it. The hash above identifies that exact file; it changes when source or build inputs change.

## Verification status

The P3-06 merged APK was installed and exercised on `emulator-5554`; evidence is linked in PR #113 and copied under `docs/android/evidence/`. The current score-card candidate was also installed and verified at dark theme and 1.3× font scale; see PR #121. Further integrated accessibility and settings-restart verification is still in progress under P3-18 (#97). See `STATE.md` for current limits; this APK is not a claim that every Phase 3 acceptance item has passed.
