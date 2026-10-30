# TrustShield AI 0.2.0 install compatibility (checked 29 Oct 2026)

Why "App not installed" happens, and what this build changes:

| Cause | Before (0.1.0) | Now (0.2.0) |
|---|---|---|
| No matching CPU type (32-bit ARM phones) | arm64-v8a only | arm64-v8a, armeabi-v7a, x86, x86_64 in one universal APK |
| Android too old | minSdk 26 (Android 8.0) | minSdk 23 (Android 6.0) |
| Old-style signature checks | v2 only | v1 + v2 + v3, same release key as 0.1.0 (certificate SHA-256 4af38d98...e138) |
| Update refused (downgrade / different key) | versionCode 1 | versionCode 2, same signer, so it updates in place |
| Installer filters out the phone | camera feature only marked optional | camera, microphone, telephony, bluetooth all marked not required |
| 16 KB page alignment (newer phones) | n/a | zipalign -P 16 check passed |
| Split or bundle APK | per-ABI split + universal | one universal file |

Verified by running commands (not by assumption):
- apksigner verify: v1 true, v2 true, v3 true.
- aapt2 badging: minSdk 23, targetSdk 36, native-code arm64-v8a armeabi-v7a x86 x86_64, no INTERNET permission.
- zipalign -c -P 16: verification successful.
- Fresh install + launch + 2,000 monkey events, no crash: Android 8.0 (API 26, 812 ms launch), Android 11 (API 30, 1681 ms launch). Both x86_64 emulators.
- JVM unit tests: 1,493 total, 0 failures, 0 errors, 0 skipped.

NOT verified: no physical phone (Samsung, Vivo, Oppo, Asus, Xiaomi) was connected, and no 32-bit ARM
device or emulator was available, so the armeabi-v7a libraries are present in the file but never executed.
Vendor-specific installers (for example Xiaomi MIUI "Install via USB" prompts or Play Protect warnings
for sideloaded apps) cannot be removed by any APK change: they are phone settings.