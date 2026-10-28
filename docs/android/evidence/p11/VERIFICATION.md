# P11 verification record

All device commands used `ANDROID_SERIAL=emulator-5554`; the emulator font scale was 1.3. No commands targeted the real-device serial.

## Unit tests

Command (from `apps/android`):

```powershell
$env:ANDROID_HOME='C:\Users\shubh\AppData\Local\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest --no-daemon '-Dorg.gradle.jvmargs=-Xmx2g'
```

Result: 1,475 tests, 0 failures, 0 skipped.

Output tail:

```text
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 23s
25 actionable tasks: 4 executed, 21 up-to-date
```

## Connected Android tests

Command (from `apps/android`):

```powershell
$env:ANDROID_HOME='C:\Users\shubh\AppData\Local\Android\Sdk'
$env:ANDROID_SERIAL='emulator-5554'
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon '-Dorg.gradle.jvmargs=-Xmx2g'
```

Result: 72 tests, 0 failures, 0 skipped; `Phase3JourneysTest` 11/11; Bomb Shield demo and permission-denied paths 2/2. An earlier full attempt had a 15-second timeout in the existing CallShield offline UI test; its focused rerun passed 1/1 and the complete rerun passed.

Output tail:

```text
head-api36(AVD) - 16 Tests 71/72 completed. (0 skipped) (0 failed)
Finished 72 tests on head-api36(AVD) - 16

BUILD SUCCESSFUL in 2m 32s
68 actionable tasks: 1 executed, 67 up-to-date
```

## Startup and APK size

`adb shell am start -W` cold-start `TotalTime` in milliseconds, three runs each:

```text
origin/android-finals: 3038, 2522, 2737 (median 2737)
P11:                   2432, 2380, 3019 (median 2432)
```

Debug arm64 APK size increased from 86,956,059 bytes to 87,287,047 bytes (+330,988 bytes, about 0.38%). The offline Hindi model remains packaged.

Final APK: `apps/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

SHA-256: `E0C0F92E833D26951211A7EC3A518F75B7E7D6C1B67CCAE12C1DF33FE4D82764`

## Emulator exercise and screenshots

`adb -s emulator-5554 shell monkey -p ai.trustshield.android 2000` reported `Events injected: 2000`; the app process remained alive and the captured logcat had no fatal exception or process-death record. The emulator denied six low-level `/dev/input/event0` injections with `EACCES`; these were harness input failures, not app crashes.

Screenshots were captured at font scale 1.3:

- `bombshield-black-en.png`
- `bombshield-black-hi.png`
- `bombshield-white-en.png`
- `bombshield-white-hi.png`

The emulator permission-denied test confirms the UI does not claim protection when the required permissions are absent. Hardware call/SMS delivery was not exercised.
