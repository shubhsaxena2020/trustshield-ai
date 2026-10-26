# Android debug install

Build the debug APK from a checkout with Android SDK 36 and JDK 17:

```powershell
cd apps/android
$env:ANDROID_HOME = 'C:\Users\shubh\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleDebug
```

The build creates ABI split APKs. Choose the one matching the device:

- Emulator: `apps/android/app/build/outputs/apk/debug/app-x86_64-debug.apk`
- ARM64 phone: `apps/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- 32-bit ARM phone: `apps/android/app/build/outputs/apk/debug/app-armeabi-v7a-debug.apk`
- x86 emulator: `apps/android/app/build/outputs/apk/debug/app-x86-debug.apk`

Install on the attached x86_64 emulator from the repository root:

```powershell
adb -s emulator-5554 install -r apps/android/app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s emulator-5554 shell monkey -p ai.trustshield.android 1
```

## Final x86_64 artifact

Built from `android-finals` at `f23a8700ab709e5f8be7e542b2a4915bcf562d9a` after PRs #127-#130, #122, and #125 were merged:

- File: `apps/android/app/build/outputs/apk/debug/app-x86_64-debug.apk`
- Package: `ai.trustshield.android`
- Version: `0.1.0` (version code `1`)
- Minimum Android: API 26 (Android 8.0)
- Target Android: API 36
- Size: 37,060,136 bytes
- SHA-256: `373A74D5E1E49775B62101F6DC9E04984EEF6D571527F07DF63E8898B23F82F7`
- Signature: verified APK Signature Scheme v2; local Android Debug certificate (SHA-256 `91872436B128862E8F25AC5F12F99F34A8EA5647792A9678F0FF163013BA1E20`). This is a debug/demo artifact, not a production release signature.

The APK is present in the local ignored Gradle output directory and is not committed to Git. Re-run the build command to regenerate it. The hash above identifies that exact file and changes when source or build inputs change.

## Verification

Final merged-tree gate passed: `:app:testDebugUnitTest` (1,061 tests, 0 failures), `:app:lintDebug`, `:app:assembleDebug`, and `:app:assembleDebugAndroidTest`. Installed APK verification and 2,000-event checks are recorded in `evidence/device-run.md` and `evidence/p3-final-monkey-touch-only.txt`.

The 9 P3-18 connected emulator journeys passed, including Result and Settings Activity recreation. Broader acceptance is not claimed complete: issue #97 remains open for manual TalkBack traversal, 320 dp / 200% font-scale coverage, and physical-device evidence. See `STATE.md` and `evidence/accessibility.md` for the remaining limits.

## Signed release APK (Phase 4)

Build the signed ABI splits and universal APK from `apps/android` with JDK 17 and Android SDK 36:

```powershell
$env:ANDROID_HOME = 'C:\Users\shubh\AppData\Local\Android\Sdk'
.\gradlew.bat :app:assembleRelease
```

The local build reads `apps/android/keystore/release.properties` and `apps/android/keystore/trustshield-release.jks`. Both are git-ignored. Never commit either file or put their contents in a log, issue, or chat. If the local signing identity must be regenerated, run this from `apps/android` and answer the prompts locally (use the same password for store and key):

```powershell
New-Item -ItemType Directory -Force .\keystore | Out-Null
keytool -genkeypair -v -storetype PKCS12 -keystore .\keystore\trustshield-release.jks -alias trustshield-release -keyalg RSA -keysize 4096 -validity 10000
```

Then recreate `release.properties` locally with the generated store file, alias, and passwords. Replacing the signing identity means Android will treat an APK installed over an older identity as a different signer; uninstall the old app first.

The package and install steps for the owner's USB-connected arm64 phone are:

```powershell
adb devices -l
adb -s <SERIAL> install -r .\app\build\outputs\apk\release\app-arm64-v8a-release.apk
adb -s <SERIAL> shell monkey -p ai.trustshield.android 1
```

For an APK copied to the phone and opened in a file manager, allow that file manager under **Settings → Apps → Special app access → Install unknown apps**. Direct `adb install` does not require this file-manager permission; USB debugging must be authorized.

To reset data while keeping the app installed, use `adb -s <SERIAL> shell pm clear ai.trustshield.android`. To remove the app and its data, use `adb -s <SERIAL> uninstall ai.trustshield.android`.

The current signed APK measurements, signer certificate SHA-256, `apksigner verify` results, ABI file sizes, and APK SHA-256 values will be recorded here after the final TrustShield label/icon/splash resources are merged and rebuilt. The earlier signed build is only a packaging baseline and is not the phone handoff.
