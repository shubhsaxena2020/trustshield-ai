# Phase 4 merged-tree re-verification

Validated `android-finals` at merge commit `c903a1cf793dca242e0d7c119c952541a3a312c6` (PRs #135, #134 and #136 integrated). All work in this report was emulator/JVM-only; the physical-phone hold remained active.

## Fresh Android gate

- `:app:testDebugUnitTest`: **1,083 passed, 0 failed, 0 errors, 0 skipped**.
- `:app:lintDebug`, `:app:assembleDebug`, and `:app:compileDebugAndroidTestKotlin`: passed.
- `:app:connectedDebugAndroidTest`: **54 passed, 0 failed, 1 skipped** on API 36. The existing offline journey test was skipped.
- APK: `apps/android/app/build/outputs/apk/debug/app-x86_64-debug.apk`, 30,328,812 bytes, SHA-256 `9A8E9406DD8105A468BDF75800DB1E3C55702085F6C123084AE2D4ADB2DE3097`.

## Stability and behavior

- Opened QR Guard before each seeded run. Seeds 4242, 7, and 99 each injected 2,000 events and exited 0. Each captured 20,000-line logcat scan was clear of SIGSEGV, FATAL EXCEPTION, app ANR, and `libbarhopper` markers. Monkey reported emulator `/dev/input/event0` EACCES for flip events; all 2,000 events still completed. Per-seed output and scans are adjacent `p4-integrated-seed-*` files.
- On the emulator, scanned the digital-arrest awareness advisory fixture and observed **24/100, LOW / “Kam khatra”** with the exact source quote. Screenshot and UI hierarchy are `p4-advisory-result.png` and `.xml`.
- PR #136's merged demo meter/haptics behavior is covered by its JVM tests and the full connected suite; its four-rung emulator screenshots remain in `evidence/defects/d119-after-demo-step1..4.png`.

The connected test runner uninstalled its target app on completion. The first Monkey attempt therefore failed to start `MainActivity`; after installing the just-built debug APK, the three valid runs above passed. Emulator `trustshield-api36` was stopped after verification. A separate `head-api36` emulator appeared online afterward and was left untouched.
