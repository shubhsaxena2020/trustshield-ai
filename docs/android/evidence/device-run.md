# Emulator device run — Phase 3 end-to-end journeys (P3-18, issue #97)

Run on the shared emulator `emulator-5554` on **26 Oct 2026, 03:45–04:20 IST**, from the integrated
`android-finals` tree. This file records what the device actually did: commands, output, screenshots,
defects and gaps. Nothing here is a physical-phone result.

## 1. Device and build under test

| item | value (measured with `adb shell getprop`) |
|---|---|
| AVD | `trustshield-api36` (Android 16, SDK 36, `userdebug`) |
| model / abi | `sdk_gphone64_x86_64` / `x86_64` |
| viewport | `wm size 1080x2400`, `wm density 420` (411 dp wide) |
| radio | `airplane_mode_on = 1` — every journey below therefore ran with **no network** |
| font scale | `1.0` for the journeys, `1.3` for the accessibility capture |
| source tree | `android-finals` @ `8e31f50` (merge of #113, the scan/share/call/QR wiring) |
| APK | `app-x86_64-debug.apk`, 37,020,410 bytes, SHA-256 `9CC290F8BC5DEE914BECDEAB1876030F154E2E281880F93B09B7217CF466BB08` |

The emulator is shared with the other Phase 3 sessions and with Codex. Every command below was run
while holding `%TEMP%\android\emulator.lock` (created with `mkdir`, removed in a `finally`/`trap`).
A second AVD, `head-api36`, is attached to the same host and the instrumented runner therefore
executed the suite on **both** devices; results are reported for both where they differ.

## 2. Instrumented journey suite

`apps/android/app/src/androidTest/java/ai/trustshield/android/Phase3JourneysTest.kt`

```
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ai.trustshield.android.Phase3JourneysTest
```

| journey | assertion | result |
|---|---|---|
| scan → result | a typed Hinglish SBI-KYC/OTP message reaches the Result screen with a risk band **and** a separate confidence line | **PASS** (`Risk score: 70/100`, `Bharosa: Zyada bharosa. Saare dikhaye gaye quotes source text se match hue.`) |
| unreadable input | emoji-only input keeps the check action **disabled** and produces no verdict | **PASS** |
| legitimate message | the report's protective OTP warning is not shown as high risk | **PASS** |
| share intake | an `ACTION_SEND` text share reaches the local pipeline without typing | **PASS** |
| Call Shield demo | the scripted demo advances and keeps its “no microphone or call recording” disclosure | **PASS** (`Kam · 10/100 · HIGH` after two steps) |
| QR Guard simulate | three valid mismatches raise the payment-stop warning, masked ids only, occlusion copy still distinct | **PASS** |
| settings persistence | English + haptics-off survive activity recreation | **PASS** |
| offline scan | with the radio off, the local verdict still appears | **PASS** |
| result after recreation | the Result screen keeps its risk/confidence after the activity is rebuilt | **FAIL → documented as defect D1, test carries `@Ignore` with the reason** |

Final run: `Finished 8 tests on trustshield-api36(AVD) - 16`, `0 failed`; the ninth (D1) is reported
as skipped with its reason, not as a pass. The same eight passed on `head-api36`.

### Why the suite starts by resetting settings

Earlier runs in this session failed for a test-hygiene reason worth recording: the shared emulator
still had **English + haptics-off** persisted from a previous run, so journeys that look for Hinglish
copy timed out. `@Before resetPersistedSettings()` now writes the shipping defaults through the app's
own `SharedPreferencesUserPreferencesStore` before every journey. The timeout log is the evidence
that this was a test defect, not an app defect:

```
ComposeTimeoutException: Condition still not satisfied after 15000 ms
  at ...Phase3JourneysTest.waitForText(Phase3JourneysTest.kt:273)
```

## 3. Screenshots (all under `docs/android/evidence/`)

| file | what it shows |
|---|---|
| `p3-18-scan-light-hinglish.png` | real Home/Scan screen: multiline field, `0 / 20,000` counter, Paste/Clear, four synthetic example chips, offline notice, `Check karein (offline)`, screenshot OCR action, Call Shield / QR Guard entries, Settings |
| `p3-18-result-light-hinglish.png` | Result screen: `Risk score: 70/100`, tone word + glyph, confidence as its own line, three evidence cards with exact quotes (`KYC 24 ghante mein band ho`, `Turant`, `OTP aaya hai woh batao`), actions, limitations, `Backend check band hai`, `Itne characters check hue:112`, “verdict deterministic rules se aata hai” |
| `p3-18-settings-light-hinglish.png` | Settings: theme (system/light/dark), language, three sensitivity presets with the “cannot turn warnings off” note, haptics, reduced motion, reset |
| `p3-18-settings-dark-hinglish.png` | same screen after selecting “Hamesha dark” — dark scheme applied immediately |
| `p3-18-settings-dark-english.png` | same screen after switching to English — full English copy, detached from any restart |
| `p3-18-home-dark-english-persisted.png` | cold start after `am force-stop`: **dark + English survived the restart** |
| `p3-18-scan-font13-english.png` | Home/Scan at font scale 1.3 |
| `p3-18-defect-d1-result-blank-after-recreate.png` | defect D1: Result screen rendered empty after a configuration change |
| `p3-18-qr-camera-denied.png` | QR Guard with CAMERA revoked: “Enable camera” action, setup state, simulate control |
| `p3-18-qr-simulated.png` | QR Guard simulate path after three valid mismatches: “QR looks changed”, `Do not pay right now.`, **masked** ids `Mila: dem***@ybl | Expected: duk***@okaxis`, counter `3 / 3`, and the standing line “Not seeing the QR is not proof of tampering.” |
| `p3-18-qr-camera-live.png` | QR Guard with CAMERA granted: live CameraX preview, `Camera is on — keep the QR inside the frame` |
| `p3-18-call-consent.png` | Call Shield: consent gate, mic state, recognizer disclosure, honest limits (“Yeh app call ke doosre taraf ki awaaz nahi pakadta”), five stage chips all “abhi nahi mila” |
| `p3-18-call-demo.png` | Call Shield scripted demo: `Scripted demo only. No microphone or call recording.`, step `Kam · 10/100 · HIGH`, Hinglish line, honest-limits paragraph |

The full text of every screen (33–43 strings each) is in `p3-18-screen-transcript.txt`, captured
with `uiautomator dump` while scrolling to the end of each screen. It is the source for every quote
above.

## 4. Stability

```
adb shell monkey -p ai.trustshield.android --throttle 100 -v 500
Events injected: 500
:Dropped: keys=0 pointers=0 trackballs=0 flips=2 rotations=0
## Network stats: elapsed time=22199ms (0ms mobile, 0ms wifi, 22199ms not connected)
// Monkey finished
```

* **0 crashes, 0 ANRs** from the 500-event run; `ls /data/anr` was empty afterwards and the crash
  buffer contained no application frames.
* One unattributed `FATAL EXCEPTION: main / Caused by: android.os.RemoteException: Remote stack
  trace:` was observed earlier in the session when routes were being force-launched back to back.
  It carried no `ai.trustshield.*` frame, did not reproduce in the controlled run above, and the
  shared logcat buffer was cleared by another session before it could be captured in full. **It is
  recorded as unexplained, not as a clean result.**

## 5. Defect D1 — Result screen is empty after a configuration change

* **Repro (automated, deterministic, two devices):** open the Result screen from a scan, then
  `ActivityScenario.recreate()`. `Phase3JourneysTest#resultJourney_survivesActivityRecreation`
  waits for `Risk score` and times out on both `trustshield-api36` and `head-api36`.
* **Repro (manual):** with the Result screen open, `adb shell settings put system font_scale 1.3`
  — the activity is rebuilt and the screen renders blank
  (`p3-18-defect-d1-result-blank-after-recreate.png`); the accessibility tree contains no nodes.
* **Cause (read from source, not fixed here):** the scan outcome lives in the route host's
  `remember { mutableStateOf(...) }` in `ui/navigation/AppScreens.kt`; the back stack is restored by
  `rememberSaveable`, so the app returns to the Result route with no outcome. `AppScreens.kt` is
  owned by P3-06/Codex, so this package reports the defect instead of editing it.
* **Impact:** any rotation, font-scale change, dark-mode switch or process recreation while reading a
  result loses the result — a visible quality gap against `UI_SPEC` (“app survives rotation/process
  recreation”) and `QUALITY_GATES` gate 5.
* The test stays in the suite with `@Ignore("Known defect D1 …")` so it runs the moment the host is
  fixed.

## 6. Gates run for this package

```
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug
```

Measured on this worktree (`android-finals` @ `8e31f50` plus this package's files), all
`BUILD SUCCESSFUL`:

| task | result |
|---|---|
| `:app:testDebugUnitTest` | **965 tests, 0 failures, 0 errors, 0 skipped** (28 suites) |
| `:app:compileDebugAndroidTestKotlin` | pass — the journey suite compiles |
| `:app:lintDebug` | pass — **0 errors**, 13 issues total (12 warnings + 1 hint), all pre-existing (e.g. `ApplySharedPref` in `UserPreferencesStore.kt`, `ModifierParameter`, `GradleDependency`) |
| `:app:assembleDebug` | pass — `app-x86_64-debug.apk`, 37,020,410 bytes, SHA-256 `9CC290F8BC5DEE914BECDEAB1876030F154E2E281880F93B09B7217CF466BB08` |
| `:app:connectedDebugAndroidTest` | 8 journeys pass on `trustshield-api36` **and** `head-api36`; the D1 test is reported as skipped with its reason |

Two build attempts failed for **host** reasons, recorded here rather than hidden: the Gradle daemon
aborted with `insufficient memory for the Java Runtime Environment` / `Could not reserve enough space
for object heap` / `Gradle build daemon disappeared unexpectedly` while two emulators and several
parallel agent sessions were running. Re-running with a smaller daemon heap
(`-Dorg.gradle.jvmargs="-Xmx1400m -XX:MaxMetaspaceSize=512m"`) made both tasks green; no source
change was involved. This is a machine-capacity limitation, not a code defect.

## 7. Honest gaps

| item | status |
|---|---|
| Physical phone install, real camera QR, real Hindi ASR | **not done** (owner-dependent, `NEEDS_SHUBH.md`). |
| Screenshot/OCR journey end-to-end | **not automated here.** The OCR adapter has its own 18-test connected suite (Codex recorded it on the integrated tree); this package did not drive the system photo picker, so no journey claim is made. |
| Share *chooser* (picking TrustShield from another app's sheet) | **not automated.** The `ACTION_SEND` intent path is asserted; the chooser UI needs a second app and was captured by Codex in `p3-06-share-chooser.png`. |
| QR decode of a printed QR | **not possible** — the emulator camera has no QR in view; the simulate path and `CameraXBarcodeDecoderTest` are the evidence, and the mismatch journey above is explicitly labelled as a demo inside the app. |
| TalkBack spoken pass, 320 dp, 200 % text | see `accessibility.md` §7. |
| Bleed-through of other sessions | the shared emulator had English/haptics persisted from another run and its logcat buffer was cleared twice mid-session; both are handled in the suite (`@Before` reset) and disclosed here. |


## 8. Current merged-tree verification addendum (2026-10-26)

The earlier D1 finding above is historical and has been fixed by PR #122 (`f026b29`). `MainActivityResultRetentionTest` passed 2/2 Activity-recreation tests (Result remains in memory across configuration changes; Settings remains on its route). The formerly ignored `resultJourney_survivesActivityRecreation` is enabled and passed in the 9/9 `Phase3JourneysTest` run on the single `trustshield-api36` AVD. The journey selectors were updated for current Result, Call Shield, and QR copy.

Final merged-tree Android gate: 1,061 JVM tests passed, lint passed, debug APK and Android-test APK assembled. The final x86_64 APK installed successfully and its local debug signature verified. A 2,000-event controlled touch-only monkey run completed without injection failures; the last 10,000 logcat lines contained no app FATAL/ANR records. The generic unfiltered monkey invocation completed 2,000 events but reported EACCES for some flip-event injections; see `p3-final-monkey.txt` and `p3-final-crash-scan.txt`.

Result recreation is now verified for Activity recreation. Process death intentionally drops the in-memory result and returns to Home; message content is not saved to disk or instance state. Issue #97 remains open: manual TalkBack traversal, 320 dp and 200% font scale, physical-phone install, real camera QR decode, and live Hindi ASR have not been verified. The new dark/light system-bar captures are `p3-125-settings-light-window-bars.png` and `p3-125-settings-dark-window-bars.png`.
