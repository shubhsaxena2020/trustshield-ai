# Phase 4 Device check emulator proof

- Date: 2026-10-26, 21:18 IST
- Target: `trustshield-api36`, Android API 36, x86_64, `emulator-5554`
- Start parameters: `-memory 2048 -no-snapshot -no-boot-anim`; only one emulator was online.
- Command: `:app:connectedDebugAndroidTest --no-daemon --max-workers=1` with `ANDROID_SERIAL=emulator-5554` and `JAVA_TOOL_OPTIONS=-Xmx2048m`.
- Result: BUILD SUCCESSFUL. Android test report: 55 tests, 54 passed, 0 failures, 1 existing skip (`offlineJourney_localVerdictWithoutNetwork`, which requires airplane mode already enabled). `Phase3JourneysTest.settingsDeviceCheck_reportsPlatformAndPermissionSnapshot` passed.
- Existing QR Guard permission-denied/simulate and Call Shield staged-demo tests were part of the connected run. JVM tests additionally cover camera-busy recovery and no-microphone/no-recognizer reducer fallbacks.
- Emulator was shut down after the run and the shared emulator lock was released. No physical phone was used.
- Generated XML report: `apps/android/app/build/outputs/androidTest-results/connected/debug/TEST-trustshield-api36(AVD) - 16-_app-.xml` (local Gradle output; not tracked).
