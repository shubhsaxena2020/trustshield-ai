# PR #135 independent re-verification

Candidate commit: `2b12406d742f0944852236deb67d1cadb09722af` (`android/P4-133`). Run on the sole API 36 `trustshield-api36` emulator (x86_64); emulator was stopped after capture. No physical-phone action was run.

## JVM/build

- `:app:testDebugUnitTest`: **1,063 passed, 0 failures, 0 errors, 0 skipped**.
- `:app:lintDebug`: passed; existing `ApplySharedPref` advisory remains in `UserPreferencesStore.kt` because synchronous persistence is intentional.
- `:app:assembleDebug`: passed.
- `:app:compileDebugAndroidTestKotlin`: passed.
- Fresh x86_64 debug APK: 30,312,428 bytes; SHA-256 `54C5CFF28A672FED208A9BAD5189C7A3680C4C11A7A17CC6D8E8243376389A4F`.
- APK archive inspection found no `libbarhopper` entries.
- Full `:app:connectedDebugAndroidTest` re-run: **54 tests passed, 0 failed, 1 skipped** on API 36. The one skipped case is the suite's existing offline journey.

## Emulator

- `CameraXBarcodeDecoderTest`: **16/16 instrumented tests passed** on Android API 36.
- Installed the candidate and opened QR Guard before the seeded runs. The emulator showed the camera-disabled explanation and retained the Simulate scan controls. Screenshot and UI hierarchy: `p4-135-qr-guard-first.png` and `p4-135-qr-guard-first.xml`.
- Each run used `adb shell monkey -p ai.trustshield.android -s <seed> --throttle 40 --pct-syskeys 0 --pct-appswitch 0 2000`, after opening QR Guard. Each injected 2,000 events and exited 0.

| Seed | Injected | Exit | Crash/ANR scan |
|---:|---:|---:|---|
| 4242 | 2,000 | 0 | PASS — no SIGSEGV, FATAL EXCEPTION, app ANR, or `libbarhopper` marker in captured 20,000 logcat lines |
| 7 | 2,000 | 0 | PASS — same scan |
| 99 | 2,000 | 0 | PASS — same scan |

Each run also printed an emulator `EACCES` for an attempted `/dev/input/event0` flip event (`// Injection Failed`); Monkey nevertheless reported all 2,000 events injected and exited 0. This is an emulator input limitation, separate from the crash/ANR scan. Per-seed monkey output and crash scans are in adjacent `p4-135-codex-seed-*` files. This proves emulator/JVM behavior only; real-camera QR decoding remains on the held phone checklist.
