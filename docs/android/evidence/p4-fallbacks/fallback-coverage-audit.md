# Phase 4 fallback coverage audit

- **Scope:** the eight fallbacks named in `docs/android/HEAD_DIRECTIVE_PHONE_HOLD.md:15-16` ("Make missing Hindi recognition, camera permission, busy camera, and microphone capability degrade to clear explanation plus a demo/simulate path… add a Settings-hidden Device check screen… with tests") and their source → test → copy → status map.
- **Branch:** `android/P4-fallbacks`, worktree `wt-fallbacks`, base commit `01b0694`.
- **Audit window:** 2026-10-26 23:55 – 2026-10-27 00:10 IST (UTC 18:25–18:40).
- **Method:** read the production sources in this worktree and confirmed every cited line with `grep -n` / `sed -n` against the file on disk. No Gradle, no emulator, no adb, no device, no build, no network. Nothing here is a test result.
- **Important caveat on the source of truth:** this audit read the **uncommitted working tree**, not `HEAD`. Four production files and seven test files were being written by parallel agents while this pass ran (`git status` at 00:06 IST: `M SpeechCapability.kt`, `M QrGuardHost.kt`, `M DeviceCheck.kt`, `M SettingsScreen.kt`, plus seven untracked `*FallbackTest.kt` / capability test files). **Every line number below is the working-tree file at that moment** and may shift by a line or two after those commits land. The symbol names are the stable reference.
- **Test status, stated once and not repeated:** every test named here is **written, Gradle gate pending**. No `:app:testDebugUnitTest` or `:app:connectedDebugAndroidTest` run has been made against this tree by this pass. The lane-lead gate result for these files is **unknown**. This document asserts *what the test source asserts*, never that it passed.
- **Privacy properties of these paths** are audited separately in `docs/android/evidence/p4-fallbacks/fallback-privacy-review.md` (sibling document, different question). This document does not repeat that analysis.

## Status vocabulary

| Status | Meaning here |
|---|---|
| **COVERED** | A production implementation exists, a test pins the decision/copy, and the user reaches the explanation *and* a working way forward from the shipped screen. |
| **PARTIAL** | The logic and/or copy exist and are pinned, but something in the shipped path is missing: the sentence is never rendered, the route is never offered, or one sub-state cannot be represented. |
| **GAP** | Nothing in the shipped path handles it. |

## 1. Requirement → source → test → copy → status

| # | Fallback | Production source (file:lines) | Test that pins it | User-visible copy | Status |
|---|---|---|---|---|---|
| 1 | Missing Hindi (`hi-IN`) speech recognition | `input/speech/SpeechCapability.kt:329-340` (reason + degrade), `:361-368` (`OnDeviceUnavailable` → `offlineCapable=false`, `cloudMayBeUsed=true`), `:246-249` (`SpeechCopy.hindiModelMissing`), `:438-442` (`SpeechLabels.statusFor`); rendered path `ui/call/CallShieldCopy.kt:48-51` via `ui/call/CallShieldScreen.kt:296` | `input/speech/HindiRecognitionCapabilityTest.kt` — `supportedHiInCallbackReportsSupportFromEitherLanguageList`, `completedNegativeResponseIsUnsupportedNotNotChecked`, `callbackErrorsAndOldApisStayNotChecked`, `probeNeverStartsRecognitionOnAnyApiLevel`, `hiInTagMatchingIsStrict`, `toNullableMapsAllThreeOutcomesDistinctly`; `input/speech/CapabilityTest.kt:74` `api31WithOnDeviceButMissingHiInModelIsDegradedOnDeviceUnavailable`, `:107` `hiInUnsupportedIsDegraded`; `ui/call/CallShieldRecognizerFallbackTest.kt:212` `missingHindiOnDeviceModelIsNeverReadyOnDeviceAndDisclosesTheNetworkPath` | "Hindi model download nahi hai, internet wala path chalega" / "Hindi model is not downloaded; the internet path will be used" (`SpeechCapability.kt:246-249`). **Shipped screen instead shows** "Recognizer internet use kar sakta hai; kuch audio device se bahar ja sakta hai." / "The recognizer may use the internet; some audio may leave this device." (`CallShieldCopy.kt:48-51`) | **PARTIAL** |
| 2 | `CAMERA` permission denied (incl. permanent refusal, and no-camera hardware) | `ui/qr/QrGuardHost.kt:148-160` (states), `:186-211` (fallback data types), `:213-243` (`QrPermissionPolicy.decide`), `:877-908` (`permissionFallback`), `:1185-1210` (render), `:1494-1521` (probe + launcher), `:1526-1532` (re-probe on `ON_RESUME`) | `ui/qr/QrCameraPermissionFallbackTest.kt` — `denialIsNotConflatedWithADeviceWithoutACamera`, `denialStillOffersTheLabelledSimulation`, `offeredRoutesMatchTheState`, `simulationIsLabelledAsNotLive`, `denialStartsNoCameraAnalysis`, `decideAndReduceAreDeterministic`; `ui/settings/DeviceCheckTest.kt:20` | Both languages, per state: `cameraAskHelp` (`:848-851`), `permanentlyDeniedHelp` (`:852-855`), `noCameraHelp` (`:856-859`), `allowCamera` / `openSettings` (`:860-861`), `manualFallback` (`:862-865`) | **COVERED** |
| 3 | Camera busy (`CAMERA_IN_USE` / `CAMERA_MAX_IN_USE`) | `input/qr/CameraXBarcodeDecoder.kt:890-904` (`CameraFaultMapper.token`), `:726-729` (bind failure → token), `:869` (`FAULT_CAMERA_BUSY`); copy + render `ui/qr/QrGuardHost.kt:949-952`, `:992-997`, `:1078-1085` | `input/qr/CameraBusyFallbackTest.kt` — `bothBusyReasonsCollapseToOneRecoverableBusyState`, `busyCopyExplainsRecoveryAndKeepsDemoAvailable`, `busyFallbackDoesNotSwallowOtherFaults`, `busyFallbackIsAPureFunctionThatCannotStartACameraSession`; also `input/qr/CameraFaultMapperTest.kt:9`, `ui/qr/QrHostCopyTest.kt:9` | "Camera kisi aur app mein chal raha hai. Use band karke dobara try karein, ya QR Guard ka demo chalayein." / "Another app is using the camera. Close it and try again, or run the QR Guard demo." (`:949-952`) | **PARTIAL** (copy + demo route covered; no in-app retry control) |
| 4 | Missing `RECORD_AUDIO` permission | `input/speech/SpeechCapability.kt:121-122` (`MicPermission`), `:384-403` (mic gate), `:234-241` (copy), `:445-449` (`SpeechLabels.statusFor`); shipped: `ui/call/CallUiState.kt:61` (`MicPermission {UNKNOWN, GRANTED, DENIED}`), `ui/call/CallShieldScreen.kt:573-607` (`PermissionCard`), `ui/call/CallShieldCopy.kt:234-241`, wiring `ui/navigation/AppScreens.kt:372-374, 399-401, 428` | `ui/call/CallShieldMicrophonePermissionFallbackTest.kt` — `recordAudioDeniedCannotOpenTheMicrophoneEvenAtListeningPhase`, `deniedCopySaysPermissionWasNotGrantedAndNothingWasHeardOrRecorded`, `demoLadderIsOfferedInEveryMicrophoneFailureState`, `permanentlyDeniedIsItsOwnStateWithTheSettingsRouteAndIsNeverReady`, `callShieldCopyIsHonestAboutTheTypedAndStagedFallback`, `sameInputEvaluatesToTheSameStateAndTheSameLineCount`; `input/speech/CapabilityTest.kt:117,132,143`; `ui/call/CallShieldReadinessTest.kt:316`; `ui/call/CallScreenTest.kt:301` | "Mic ki anumati nahi mili" / "Microphone permission not granted" + "Phone ki Settings mein is app ke liye Microphone allow karein…" (`CallShieldCopy.kt:234-241`) | **PARTIAL** |
| 5 | Missing speech-recognizer capability / no recognizer service | `input/speech/SpeechCapability.kt:146-164` (status enum), `:347-350` (`MissingService`), `:242-245` (`SpeechCopy.recognizerUnavailable`), `:430-452`; shipped: `ui/call/CallShieldHost.kt:509-514` (`recognizerFor`), `:234-239` (null capability → blocked), `ui/call/CallShieldScreen.kt:609-645` (`RecognizerCard`), `ui/call/CallShieldCopy.kt:243-253` | `ui/call/CallShieldRecognizerFallbackTest.kt` — `missingRecognizerServiceRefusesLiveAndOffersSpeechSettingsPlusDemo`, `missingHindiOnDeviceModelIsNeverReadyOnDeviceAndDisclosesTheNetworkPath`, `unsupportedHiInLocaleKeepsOnlyTheNetworkRouteAndSaysSupportIsNotConfirmed`, `noLiveListeningIsClaimedWithoutARealPath`, `identicalEvaluationsProduceIdenticalReports`; `input/speech/CapabilityTest.kt:96`; `ui/settings/DeviceCheckTest.kt:8` | "Is phone par Hindi speech recognition nahi mila" / "Hindi speech recognition is not available on this phone" + "Live speech-to-text is not running on this device, so no live alert is available. Install a speech service, or use the typed transcript / staged demo below — that is not live ASR." (`CallShieldCopy.kt:243-253`) | **PARTIAL** |
| 6 | Settings-hidden Device check (incl. unavailable locale support reading as **Not checked**, never **Not supported**, on API < 33) | `ui/settings/SettingsScreen.kt:183` (mount), `:242-279` (`DeviceCheckPanel`, collapsed by default); `ui/settings/DeviceCheck.kt:13-36` (`DeviceCheckLocaleRules`), `:48-53` (`DeviceCheckCopy.localeStatus`), `:70-101` (`DeviceCheckProbe.snapshot` / `checkHindiLocale`); probe rules `input/speech/SpeechCapability.kt:54-119` | `ui/settings/DeviceCheckFallbackTest.kt` — `androidCannotAnswerMapsToNotCheckedNeverUnsupported`, `missingRecognitionServiceIsAnAnswerNotARefusal`, `supportedAnswerMapsToSupportedWithItsOwnCopy`, `initialStatusIsNotCheckedAndCheckAllowedOnlyFromApi33`, `noRecognizerSessionStartsOnAnySupportedApiLevel`, `availabilityAndPermissionAreNotConflated`; `ui/settings/DeviceCheckTest.kt:8,20`; instrumented `Phase3JourneysTest.settingsDeviceCheck_reportsPlatformAndPermissionSnapshot` | "Supported" / "Not supported" / **"Not checked on this Android version"** ("Is Android par check nahi ho saka"), plus "Available"/"Mila", "Granted"/"Mili" (`DeviceCheck.kt:48-53`) | **COVERED** |
| 7 | Simulate (QR), typed, and staged-demo paths that must work with hardware/permission missing | QR simulate: `ui/qr/QrGuardHost.kt:1095` (always composed), `:1326-1378` (`SimulatePanel`), `:918-930` (labels incl. "not live"), `:210` (`offersSimulation`); manual UPI: `ui/qr/QrGuardScreen.kt:346-347`, `:397-422`; typed scan text: `ui/home/ScanScreen.kt:509-542`; staged demo: `ui/navigation/AppScreens.kt:403-416, 452-464`, `ui/call/CallDemoScript.kt:424-431` (`submitTypedLine`) | `ui/call/CallShieldTypedDemoFallbackTest.kt` — `` `ladder climbs low to critical on a device with no microphone capability` ``, `` `typed line scores through the deterministic engine and blank input adds no step` ``, `` `reset empties the fallback state and back re-derives the previous reading` ``, `` `readiness offers the demo exactly where live speech is impossible` ``, `` `two independent runs give the identical score and position sequence` ``, `` `the fallback stores nothing after reset` ``; `ui/qr/QrCameraPermissionFallbackTest.kt:141`; `ui/call/CallDemoScriptTest.kt`; `ui/home/ScanScreenTest.kt:85`; `ui/qr/QrGuardScreenTest.kt` | "Simulate scan (demo)" + "Yeh simulation hai — camera se live decode nahi hota." / "This is a simulation — it is not live camera decoding." (`:918-922`); "Staged demo (live mic nahi)" / "Staged demo (not live mic)" (`CallShieldCopy.kt:255`); "Typed transcript" (`:256`, **never rendered — see F4**) | **PARTIAL** |
| 8 | No phone actions before an explicit owner release (phone hold) | Process control, not app code: `docs/android/HEAD_DIRECTIVE_PHONE_HOLD.md:5-7, 22`, `docs/android/HEAD_ORDERS_PHASE4.md:22`. Code-side analogue verified: **zero** `ACTION_CALL` / `ACTION_DIAL` occurrences in `app/src/main`, and the only `startActivity` in the QR flow targets this app's own settings page (`ui/qr/QrGuardHost.kt:1630-1644`) | None — a process rule cannot be pinned by a JVM test. Nothing in this document claims a phone measurement. | n/a | **COVERED** (process) |

## 2. Findings — every PARTIAL and GAP, with the check that found it

### F1 — (R1) The "Hindi model missing" sentence is built and never shown — PARTIAL

The decision and the sentence both exist and are well-pinned:

- `SpeechCapability.kt:337-340` — `if (onDeviceUsable && !env.hiInModelDownloaded) { degraded = true; reasons += CapabilityReason.HiInModelMissing }`
- `SpeechCapability.kt:438-442` — `SpeechStatus.OnDeviceUnavailable -> … SpeechCopy.hindiModelMissing`
- `SpeechCapability.kt:246-249` — the sentence itself.

But `SpeechLabels` (the only object that maps a report to that line) has **zero call sites outside its own file**:

```
$ grep -rn "SpeechLabels\." --include="*.kt" app/src/main/java | grep -v SpeechCapability.kt
(no output)
```

`AndroidSpeechRecognizerAdapter.kt:419-424` does place `SpeechCopy.hindiModelMissing` into `SpeechSessionPlan.limitations`, but `limitations` is never read anywhere in `ui/` (`grep -rn "limitations" ui/` matches only `CallShieldReadiness.kt` — itself unwired — and the unrelated result screen). The shipped Call Shield screen shows `CallShieldCopy.analysisDisclosure(state.recognitionDisclosure)` at `CallShieldScreen.kt:296`, which for the degraded-Hindi case resolves to the generic "the recognizer may use the internet" line.

**Honest status:** the user is told the truth (audio may leave the device) but is **not** told *why* (no Hindi model), and no route is offered to fix it — `ReadinessCopy.openSpeechSettings` ("Speech settings kholein", `CallShieldReadiness.kt:467-470`) exists only inside the unwired readiness layer. So an `hi-IN`-less phone degrades to a correct-but-vague disclosure rather than to the specific explanation the directive asks for.

### F2 — (R3) Busy camera explains itself but offers no retry control — PARTIAL

`CameraFaultMapper` collapses both CameraX reasons to one token (`CameraXBarcodeDecoder.kt:895-902`) and the banner renders the recovery sentence (`QrGuardHost.kt:1078-1085`). What is missing is an actionable control: the busy text says "Close it and try again" (`:950-951`) but there is no retry/`Simulate` button beside the banner, and the camera `DisposableEffect` (`:1552`) is keyed on `cameraShouldRun`, which does not change when a fault arrives — so closing the other app does not restart the scanner until the screen is left and re-entered or the app is resumed. The demo route *is* always present (`SimulatePanel` at `:1095`), which is why this is PARTIAL and not GAP.

### F3 — (R4/R5) Permanent microphone denial cannot be represented by the shipped UI — PARTIAL

Two different `MicPermission` types exist:

- `input/speech/SpeechCapability.kt:122` — `enum class MicPermission { Unknown, Granted, Denied, PermanentlyDenied }`
- `ui/call/CallUiState.kt:61` — `enum class MicPermission { UNKNOWN, GRANTED, DENIED }`

The shipped screen's `PermissionCard` (`CallShieldScreen.kt:573-607`) triggers only on `state.permission != MicPermission.DENIED` → and `AppScreens.kt:430, 469` can only ever pass `GRANTED` or `DENIED` (`if (micGranted) … else …`). Consequences, each verified by reading the wiring:

1. After "don't ask again", the card still shows **"Anumati dein" / "Grant permission"** (`CallShieldCopy.kt:235`), wired at `CallShieldScreen.kt:595-596` to `CallEvent.MicPermissionGranted` → `permissionRequest.launch(...)` (`AppScreens.kt:428`). Android suppresses that dialog, so the button becomes a no-op with no explanation.
2. The settings route that would fix it exists only in the unwired layer: `ReadinessState.MIC_PERMISSION_PERMANENTLY_DENIED -> listOf(openAppSettings) + demoAction(...)` (`CallShieldReadiness.kt:669-671`). Nothing renders `ReadinessCopy`.
3. `CallShieldReadiness` and `ReadinessCopy` have **no production caller at all**:

```
$ grep -rn "CallShieldReadiness\|ReadinessState\." app/src/main/java | grep -v "ui/call/CallShieldReadiness.kt"
(no output)
```

The stacked-card copy is at least honest ("Without permission the app cannot listen"), and the *staged demo* button is reachable (`AppScreens.kt:452`, plus the always-present button at `:455-464`), so the user is never trapped without a working demo.

### F4 — (R5/R7) The copy promises a typed transcript that the shipped screen never renders — PARTIAL (honesty defect)

`CallShieldCopy.recognizerUnavailableBody` (`:247-253`) tells the user, in both languages, to "use the typed transcript / staged demo below". The typed-transcript button is only drawn when `onTypeTranscript != null` (`CallShieldScreen.kt:599-604` for the permission card, `:631-636` for the recognizer card) — and the only production caller passes **null**:

```kotlin
// ui/navigation/AppScreens.kt:452-453
onOpenStagedDemo = { demoOpen = true },
onTypeTranscript = null
```

So on a device with no recognizer, the screen names a fallback that is not on the screen. The staged demo it also names *is* on the screen. This is the one place in the audited set where the user-visible text and the shipped UI disagree, and it should be fixed either by rendering the typed-transcript path or by removing the words. `CallShieldTypedDemoFallbackTest.kt:270` (`callShieldCopyIsHonestAboutTheTypedAndStagedFallback`) pins the *copy*, not the missing button.

### F5 — (R6) "Settings-hidden" is implemented as collapsed-by-default, not hidden — note, not a defect

`SettingsScreen.kt:242-259` mounts `DeviceCheckPanel` inline inside Settings behind a `TextButton` reading "Device ki jaankari dekhein" / "Show device details". It is not gesture-gated and has no separate navigation route. I record the difference from the directive's word "hidden" so the head can decide whether a toggle is the intended reading; the panel is not visible until tapped, which meets the intent as I read it.

The NotChecked/Never-Unsupported requirement is genuinely satisfied at three layers, each verified:

| Layer | Line | Guarantee |
|---|---|---|
| Probe cannot run below API 33 | `SpeechCapability.kt:83` `probeAllowed(apiLevel) = apiLevel >= 33` | `checkHindiLocale` therefore returns `null` |
| Probe maps "could not ask" to `NotChecked` | `SpeechCapability.kt:98` (`outcomeForError`), `:104` (`outcomeWhenNotMeasurable`), `:101` (`outcomeForMissingService` → `Unsupported`) | "we could not ask" ≠ "the phone said no" |
| Device check never collapses them | `DeviceCheck.kt:25-29` exhaustive `when`, `:52` distinct copy | `NOT_CHECKED` renders "Not checked on this Android version" |

Pinned by `DeviceCheckFallbackTest.androidCannotAnswerMapsToNotCheckedNeverUnsupported`, which asserts both `assertEquals(DeviceCheckLocale.NOT_CHECKED, …)` and `assertNotEquals(DeviceCheckLocale.UNSUPPORTED, …)` (lines 49-52 of the test).

### F6 — (R2) Camera fallback is the strongest of the eight — COVERED

Worth stating explicitly because the contract is data, not prose: `QrHostCopy.permissionFallback(state)` returns a `QrPermissionFallback` whose `actions` list is asserted state-by-state by `offeredRoutesMatchTheState`, and `QrPermissionFallback.offersSimulation` (`QrGuardHost.kt:210`) makes "a demo exists without a camera" a queryable property rather than a screenshot claim. `camera.any` is `required="false"` in `apps/android/app/src/main/AndroidManifest.xml:4`, so the `NO_CAMERA` branch is a real state on a lens-less device.

### F7 — (R8) Phone hold — COVERED as a process control

The hold is not app behaviour and no code can enforce it. It is recorded here because the audit must not smuggle phone evidence in: `HEAD_DIRECTIVE_PHONE_HOLD.md:5-7` forbids physical-phone work until the owner releases, and `:22` requires phone-free work to continue past 22:45 IST. This pass used no device, and no claim in this document derives from one. The nearest code-level analogue — that the app itself never originates a phone call — holds: `grep -rc "ACTION_CALL\|ACTION_DIAL"` over `app/src/main/java` returns no matches, and the single `startActivity` in the QR flow (`QrGuardHost.kt:1630-1644`) opens `ACTION_APPLICATION_DETAILS_SETTINGS` for our own package.

## 3. Which test files existed when I looked

The seven parallel test files were still landing during this pass. Two checks were taken:

| Test file | 18:28 UTC check | 18:36 UTC check (final) | Read for this audit |
|---|---|---|---|
| `ui/settings/DeviceCheckFallbackTest.kt` | present (219 lines) | present | yes |
| `input/speech/HindiRecognitionCapabilityTest.kt` | present (161 lines) | present | yes |
| `ui/qr/QrCameraPermissionFallbackTest.kt` | present (416 lines) | present | yes |
| `input/qr/CameraBusyFallbackTest.kt` | present (231 lines) | present | yes |
| `ui/call/CallShieldRecognizerFallbackTest.kt` | present (396 lines) | present | yes |
| `ui/call/CallShieldTypedDemoFallbackTest.kt` | present (330 lines) | present | yes |
| `ui/call/CallShieldMicrophonePermissionFallbackTest.kt` | **MISSING** | present (6 test methods) | yes (test names only, read after it landed) |

At the first check `CallShieldMicrophonePermissionFallbackTest.kt` did not exist; it appeared between the two checks. I did **not** invent its test names before it did — the six names in row 4 above were read from the file after it landed. All seven are untracked (`??`) in `git status`: none is committed yet at `01b0694`.

**Status of all of them, without exception: written, Gradle gate pending.** No compile or test run has been performed against this tree. In particular, nothing in this document supports a claim that `CallShieldReadiness.kt` compiles, that the `CameraUnavailableException` imports in `CameraBusyFallbackTest` resolve, or that the seven files pass `:app:testDebugUnitTest`.

## 4. Remaining evidence

Everything below is unproven by construction in this pass and must not be asserted as working.

**Needs a Gradle run (JVM), no phone:**
1. That the seven parallel test files compile and pass — the `:app:testDebugUnitTest` gate has not run against this tree, and the lane-lead gate result is **unknown**.
2. That `ui/call/CallShieldReadiness.kt` (778 lines, no production caller) compiles cleanly alongside the shipped screen — it is only exercised by tests today.
3. That the four modified production files (`SpeechCapability.kt`, `QrGuardHost.kt`, `DeviceCheck.kt`, `SettingsScreen.kt`) still satisfy the pre-existing suites (`CapabilityTest`, `DeviceCheckTest`, `QrHostCopyTest`, `QrGuardScreenTest`, `CallShieldReadinessTest`, `ScanScreenTest`) — file:line citations here are the working tree, not a green build.

**Needs an emulator (`connectedDebugAndroidTest`):**
4. `Phase3JourneysTest.settingsDeviceCheck_reportsPlatformAndPermissionSnapshot` — the only instrumented assertion over the Device check panel. Cited from `evidence/p4-device-check-connected.md`, which reports it passing on `trustshield-api36` at 21:18 IST; **not re-run for this tree.**
5. That the `DeviceCheckPanel` actually paints on expand, and that `checkHindiLocale` delivers a callback on API 36 (the emulator is API 36, so the API < 33 "Not checked" branch is *only* reachable by unit test here — the emulator can never show it).
6. That `PermissionCard` / `RecognizerCard` render their buttons in the shipped layout after `AppScreens.kt:453`'s `onTypeTranscript = null` (F4) — a rendering fact, not a reducer fact.

**Needs a real phone (hold active, not attempted):**
7. That any device ever reaches the `NO_CAMERA` branch — no lens-less hardware is available.
8. That a real `CAMERA_IN_USE` busy fault occurs and clears the way the copy assumes (the JVM test proves the mapping, not the OS behaviour).
9. That `hi-IN` recognition works or fails the way `SpeechCapabilityRules` predicts. `evidence/speech-device-matrix.md` §2.6 records that the only emulator available carries an `en-US`-only on-device pack, so **no `hi-IN` measurement exists anywhere in this repo**, and every "degraded Hindi" claim is a decision-table claim, not a measurement.
10. That the permanent-denial recovery story (F3) is acceptable once the user has actually refused twice — requires driving the real permission dialog with "don't ask again".
11. Any TalkBack read-out of the fallback copy (`cameraPanelDescription` at `QrGuardHost.kt:963-982`, `simulationControlDescription` at `:985-989`).

**Findings that need a code change, not more evidence:**
12. F1 (render the Hindi-model reason and a repair route), F2 (retry control after a busy fault), F3 (carry `PermanentlyDenied` into `CallUiState.MicPermission` and route to app settings), F4 (either render the typed-transcript button or delete the words that promise it). All four are outside this document's write scope.
