# Review — Speech + Call Shield (P8, slice `speech-callshield`)

Base `d32fcda` (`android/P8-review`). Read-only static review: nothing was built, installed or run — no Gradle, no adb, no emulator. Code paths below were read end to end; every finding names the functions it traces through.

Scope: `input/speech/*`, `ui/call/*`, `ui/voice/*`, and the Call Shield wiring in `ui/navigation/AppScreens.kt` (`CallShieldRouteHost`, lines 401–743), because that is where the policy actually runs. All paths are relative to `apps/android/app/src/main/java/ai/trustshield/android/`.

IDs are `SCS-nn`, re-issued for this review. Every `_prev` lead was re-verified against this commit: the ones that still hold are restated with a fresh code path, and three of its P1s are restated at P2 with the reason given (§SCS-10, §SCS-18, §SCS-19).

Severity: P0 crash / privacy / data loss, P1 wrong verdict or broken or dishonest flow, P2 quality.

**Counts: P0 0, P1 9, P2 13.**

---

## Wiring facts these findings rely on (re-verified at this commit)

1. `CallShieldRouteHost` builds the pieces itself (`AppScreens.kt:410-455`): one `CallSpeechSession(consentGranted = true)`, one `OfflineSpeechPolicy`, one `SpeechSessionCoordinator`, one `CallShieldHost`. `AndroidSpeechRecognizerAdapter` (the class) is not constructed anywhere in `main`.
2. The coordinator's `handleFactory` closure (`AppScreens.kt:427-452`) chooses the engine **at handle-creation time** from `speechRoute.useOffline`: `VoskSpeechRecognizerHandle` (offline) or `AndroidSpeechRecognizerHandle` (platform). That is the only place an engine is chosen.
3. Platform errors reach the coordinator through the handle's `errorListener` (`AppScreens.kt:437-451` → `created.onError(code)`). They do **not** reach `CallShieldHost.onRecognizerError`; `grep` finds no caller of that method in `main` (traced in SCS-02).
4. `CallSpeechSession.snapshot()` / `restore()` exist and are unit-tested, and are called **nowhere** in `main` (`grep '\.snapshot()'`, `'\.restore('`). Nothing in the Call Shield path uses `rememberSaveable`.
5. `AndroidManifest.xml`: no `configChanges`, no `screenOrientation`, permissions = `CAMERA`, `RECORD_AUDIO`, `ACCESS_NETWORK_STATE` (no `INTERNET`).

---

## Static check: does any path persist audio or the raw transcript? — **No**

Searched `input/speech`, `ui/call`, `ui/voice`, `ui/navigation` for `Log.`, `println`, `System.out`, `File(`, `outputStream`, `SharedPreferences`, `writeText`, `openFileOutput`, `getSharedPreferences`; and the whole `main/` tree for `android.util.Log`.

- `onBufferReceived` is an empty body with no copy (`CallSpeechSession.kt:622`) — the only place a platform audio buffer could leak.
- The Vosk path reads into a local `ByteArray(4096)` (`OfflineSpeechEngine.kt:56`) and never writes it anywhere.
- The only file I/O in the slice is the model copy (`OfflineSpeechEngine.kt:108-143`) — Vosk assets only, no captured data.
- No `Log` call exists in `main/`. Preference persistence is limited to the five presentation values (`UserPreferencesStore`), never text.
- Transcript and chips live in RAM (`CallSessionState`, `CallUiState`) and are wiped by `onStop`/`onCleared`.

Caveats that are *not* persistence but are still privacy-relevant: the masked transcript stays on screen while `PAUSED` and there is no `FLAG_SECURE` anywhere (SCS-15); masking misses Devanagari digits (SCS-16); and the microphone stays open with no indicator when the staged demo is opened mid-session (SCS-07).

---

# P1

### SCS-01 — The Google path is deaf after the first final result, and the screen keeps saying "Listening"
Severity: P1
Where: `input/speech/CallSpeechSession.kt:630-632` and `:566-590`; `input/speech/AndroidSpeechRecognizerAdapter.kt:361-387`
Scenario: `SpeechRecognizer` is single-shot: it stops after `onResults` and only resumes if `startListening` is called again. `AndroidSpeechRecognizerHandle.Forwarder.onResults` only does `session.onFinalResult(...)`; nothing calls `startListening`, `replaceRecognizer` or `restart` from the results path. The only restart is `SpeechSessionCoordinator.onError` → `session.replaceRecognizer(...)` for `NoSpeech` (`:364-366`), and the platform does not fire an error after a successful `onResults`. So on a phone using the Google engine the user says one sentence, the app transcribes it, and from then on no audio is processed — while `CallSpeechSession.accept()` has set the state to `Listening` and the tick loop keeps the timer and the "mic listening" label running (`CallShieldScreen.kt:303-352`). Everything said after the first phrase is never scored, so the verdict is a live-looking screen over a dead recogniser.
Fix: in `Forwarder.onResults`, after forwarding, ask the coordinator for the next recogniser while the session is still active — e.g. `session.replaceRecognizer(handleFactory(language))` guarded by `acceptingResults()`, with a minimum inter-start gap (~300 ms) and a per-minute cap to avoid a beep loop. The Vosk handle does not need this (its engine runs continuously).
Failing test: `input/speech/AndroidSpeechRecognizerAdapterTest.recording restarts the platform recogniser after a final result` — drive `onFinalResult` through a fake handle, assert `handleFactory` returns a second handle and `session.state is Listening`; today the factory is invoked once.

### SCS-02 — A terminal recogniser error leaves the host in `LISTENING`, so a dead engine is presented as listening
Severity: P1
Where: `ui/navigation/AppScreens.kt:437-451`; `ui/call/CallShieldHost.kt:329-339` (no caller); `input/speech/AndroidSpeechRecognizerAdapter.kt:382-384`
Scenario: every terminal path ends in the coordinator, not the host. `INSUFFICIENT_PERMISSIONS` → `onError` → `session.stop()`; the handle's own `start()` failures (`CallSpeechSession.kt:568-586`: no permission, no service, null recogniser) call `session.onRecognizerError(...)` directly. `CallShieldHost.onRecognizerError` — the only code that calls `endSession()` and would publish a blocked/interrupted reason — has **no caller in `main`**. Result: revoke `RECORD_AUDIO` from the system dialog/Settings during a call, and `host.state.phase` stays `LISTENING`; the screen keeps the running timer, the "mic listening" label and an enabled Pause button, while `host.isListening` is already false. The user's only recovery is Pause → Resume; Start is not even offered. This is the exact class of bug the `CallShieldReadiness` vocabulary (`Interruption.PermissionRevokedDuringSession`, `RecognizerError`) was written for, and that object is never evaluated (SCS-12).
Fix: pass `host.onRecognizerError(code)` into the handle's `errorListener` ahead of `created.onError(code)` (or have the host poll `coordinator.isActive`) and map a terminal `lastError` to a blocked/interrupted reason, so the phase leaves `LISTENING`. Delete the unreachable `CLIENT || BUSY || NETWORK` early-return in `onRecognizerError` at the same time.
Failing test: `ui/call/CallShieldHostTest.a terminal recogniser error leaves LISTENING` — after `coordinator.onError(INSUFFICIENT_PERMISSIONS)`, assert `host.state.phase != SessionPhase.LISTENING`.

### SCS-03 — The bundled Vosk engine can never start on the phones it exists for
Severity: P1
Where: `input/speech/SpeechConsent.kt:108-114`; `ui/call/CallShieldHost.kt:518-523`; `ui/call/CallUiState.kt:364-378`; `ui/navigation/AppScreens.kt:428-435`
Scenario: on a phone with no recognition service (`SpeechRecognizer.isRecognitionAvailable == false`, e.g. AOSP, some Huawei, a bare emulator), `SpeechCapabilityRules.evaluate` returns `status = MissingService` with `offlineCapable = false` and `cloudMayBeUsed = false`. That makes `report.sessionPossible == false`, so (a) `ConsentGate.check` blocks with `RecognizerUnavailable`, (b) `recognizerFor` yields `UNAVAILABLE`, (c) the reducer's `start` gate returns `RECOGNIZER_UNAVAILABLE`, and (d) `state.canStart` is false so the Start button is disabled. The Vosk handle is only ever built inside the factory *after* a successful start, so the 82 MB Hindi model — the documented fallback for exactly this device class (ARCHITECTURE.md:21, ASR_BAKEOFF) — is unreachable. Toggling "Use offline mode" does not help: it changes the engine, not the gate.
Fix: make `sessionPossible` (and the reducer's recogniser gate) aware of the offline engine — e.g. `sessionPossible = offlineCapable || cloudMayBeUsed || voskModelBundled` — and let `ConsentGate` allow the offline route when the Vosk asset is present, showing the offline-only disclosure.
Failing test: `input/speech/CapabilityTest.a missing service still allows the bundled offline route` — `evaluate(MissingService env).sessionPossible` is true when the offline engine is bundled; plus a `CallShieldHostTest` case where a `MissingService` report with offline selected reaches `LISTENING`.

### SCS-04 — Silence is counted as a platform failure, so two quiet pauses silently swap the engine to Hindi-only Vosk
Severity: P1
Where: `ui/navigation/AppScreens.kt:437-439`; `input/speech/OfflineSpeechEngine.kt:175-184`
Scenario: the handle's error listener calls `speechRoute.platformError(code)` for **every** code, including `SPEECH_TIMEOUT` (6) and `NO_MATCH` (7) — the two codes `CallSpeechSession.mapError` deliberately classifies as silence, not failure. `OfflineSpeechPolicy.platformError` increments `consecutivePlatformErrors` for any other code and sets `useOffline = true` at 2. A caller who simply pauses twice (~6 s of silence each) therefore flips the whole route to `VoskSpeechRecognizerHandle`, and nothing resets it except the manual toggle (`selectOffline(false)`), because `platformSuccess()` clears the counter but not `useOffline`. Vosk here is Hindi-only; an English or Latin-script Hinglish call then decodes to junk, no stage lights up, and "no stage found" reads to the user as a calm call. The flip is also silent unless the app language is English (the banner at `:524` is gated on `selectedLanguage == ENGLISH`).
Fix: skip `platformError` for any code where `CallSpeechSession.mapError(code) == null` (6, 7), and require the flip to be backed by `SpeechStatus` evidence or an explicit user choice rather than by elapsed silence.
Failing test: `input/speech/OfflineSpeechEngineTest.silence never selects the offline route` — `platformError(6)`/`platformError(7)` several times must leave `useOffline` false.

### SCS-05 — A rotation mid-call silently ends the session and drops consent; `snapshot()`/`restore()` are never wired
Severity: P1
Where: `ui/navigation/AppScreens.kt:476-479` and `:720-734`; `AndroidManifest.xml` (no `configChanges`); `input/speech/CallSpeechSession.kt:277-320`
Scenario: the activity is recreated for a rotation, dark-mode toggle, font-scale or app-language change. `remember { mutableStateOf(SpeechConsent.initial()) }` is lost and the new composition starts at `NotAsked`; `DisposableEffect.onDispose` calls `host.onCleared()` → `endSession()` → recogniser destroyed, transcript and A05 stage memory wiped; nothing auto-resumes and no message explains why. A user who rotates the phone (very likely when switching to speakerphone) loses protection and the five-chip stage memory mid-call, and must re-consent and press Start. BEHAVIOR_SPEC (line 17) requires detected stage IDs and quotes to persist for the active session, and `CallSpeechSession.snapshot()/restore(preserveTranscript = true)` — implemented and tested for exactly this — is never called.
Fix: lock `screenOrientation="portrait"` and declare `configChanges="orientation|screenSize|keyboardHidden|uiMode|locale"` for `MainActivity` (one-line manifest change, smallest merge surface), or hoist consent + host into a `ViewModel` and restore through `snapshot()`/`restore()`.
Failing test: `CallShieldOfflineUiTest.rotation keeps consent and stage memory` — `ActivityScenario.recreate()` while listening, assert consent is still `Granted` and the phase is `PAUSED`/`IDLE` with a visible reason; today it is `IDLE` with `NOT_GIVEN`.

### SCS-06 — The spoken alert is transcribed back into the evidence stream as if the caller had said it
Severity: P1
Where: `ui/navigation/AppScreens.kt:618-637`; `ui/voice/AndroidSpokenVerdictTts.kt:26-33`; `core/rules/StageRules.kt:506-527`; `ui/call/CallShieldCopy.kt:197-203`
Scenario: "Alert sunen" speaks `CallShieldCopy.warning(severity)` + `riskExplainer` + the stage labels through `USAGE_MEDIA` while the recogniser is still listening — no `coordinator.stop()`, no mute, no `UtteranceProgressListener` gate. The result arrives through the normal sink → `host.onUtterance` → `callState.appendUtterance` → `LocalScorer`, i.e. the app's own words become caller evidence. This is not merely noise: `warningCritical` contains "**Turant** call kaat dijiye", and `URGENCY_RULES[0]` matches the bare token `turant` with no `skip`/`guarded` guard (`StageRules.kt:512-517`), so a CAUTION alert followed by "read aloud" can light the URGENCY chip — and `ask + two other stages` is exactly the RED/CRITICAL gate (`CallRiskGate.evaluate`). The app can escalate its own alert.
Fix: pause recognition around speech — call `host.onHostPaused()` (or a dedicated mute flag) before `speak`, and resume on `UtteranceProgressListener.onDone`/`onError`; as a cheap second belt, drop any `onUtterance` that arrives while the TTS engine is speaking.
Failing test: `ui/call/CallShieldHostTest.an utterance during the spoken alert is not scored` — `host.onUtterance("turant call kaat dijiye", …)` while speaking must not light `CallStage.URGENCY`.

### SCS-07 — Opening the staged demo while listening hides a live microphone with no indicator and no Stop control
Severity: P1
Where: `ui/navigation/AppScreens.kt:503-522` and `:641-650`
Scenario: `demoOpen` replaces the whole Call Shield screen with `CallDemoExperience`, but nothing stops the session (`onOpenStagedDemo = { demoOpen = true }`; no `host.onHostPaused()`), and the launch button is rendered unconditionally — the only gated control nearby is the offline toggle (`if (!state.isListening)` at `:651`). Tap "staged demo" during a LISTENING session and the visible consent state, mic indicator, timer, Stop bar and sticky warning all disappear while `AndroidSpeechRecognizerHandle`/`VoskSpeechRecognizerHandle` keep the microphone open and `host.onUtterance` keeps appending and scoring. UI_SPEC (line 3) requires Call Shield to present mic status, the active timer and "a large Stop control"; the consent copy promises "you can stop at any time". The tick loop lives outside the branch (`:735`), so the hidden session keeps ageing too.
Fix: gate the demo entry on `!state.isListening` (same as the offline toggle) and disable/hide it in `CallDemoExperience` while a session is live; or call `host.onHostPaused()` when entering the demo.
Failing test: `CallShieldOfflineUiTest.the staged demo cannot be opened during a live session` — with a listening state, the "Call Shield · Script demo" entry is absent or disabled.

### SCS-08 — `stop()` joins the Vosk worker on the main thread while it may be copying and loading the 82 MB model (ANR)
Severity: P1
Where: `input/speech/OfflineSpeechEngine.kt:78-83`, `:48-75`, `:103-128`
Scenario: the worker's first action is `decoderFactory.open()`, which for a first run is `copyAssets` of ~82 MB of assets plus `Model(...)` (`:111-114`) — seconds on a low-end phone. `stop()` only flips a flag, calls `stopPcm()` and then `worker?.join()` **with no timeout**, on the caller's thread. `stop()` is reached from `OfflineSpeechEngine.stop()` via `VoskSpeechRecognizerHandle.destroy()` from `coordinator.stop()`, i.e. from the Stop button, `ON_PAUSE` (screen off), `onDispose` and `replaceRecognizer`. Tapping Start and immediately pressing Stop or locking the screen blocks the UI thread until the model copy finishes — > 5 s is plausible, i.e. an ANR. Second-order: `stopPcm()` has already released the `AudioRecord`, so when `open()` finally returns, `pcm.start()` throws and the worker reports `onFailure()` (SCS-18).
Fix: do not join on the caller thread — set the flag, let the worker's `finally` release PCM/decoder, and check `stopped` between `copyAssets` and `Model(...)`; if any join is kept, bound it (e.g. 200 ms).
Failing test: `input/speech/OfflineSpeechEngineTest.stop returns immediately while the decoder is opening` — a decoder factory that sleeps 3 s; `stop()` must return in < 200 ms.

### SCS-09 — Microphone permission is read once, so a grant made in Settings never unblocks Start, and revocation is never noticed
Severity: P1
Where: `ui/navigation/AppScreens.kt:477-479`, `:499-501`, `:678-684`, `:720-734`
Scenario: `micGranted` is a `remember` snapshot of `checkSelfPermission` taken at first composition plus the permission-launcher result. `ON_RESUME` calls only `host.onHostResumed()` → `refreshBlocked()`, which re-reads `state.permission` — the stale value. So: deny, open system Settings, grant the microphone, come back → Start stays blocked with `MIC_PERMISSION_DENIED` until the user leaves and re-enters the route. Because the page only ever produces `Granted`/`Denied`, `PermanentlyDenied` never exists, so `SpeechPlanCopy.openSettingsForMic` is unreachable and the card offers no Settings shortcut. UI_SPEC (line 26) requires "revocation is handled"; the reverse direction (revoked while backgrounded) is equally unobserved and only shows up as SCS-02's dead-but-listening state.
Fix: re-check `ContextCompat.checkSelfPermission` in the `ON_RESUME` branch and feed it through `host.onCapability(...)`; derive `PermanentlyDenied` from `shouldShowRequestPermissionRationale == false` after a denial.
Failing test: `CallShieldOfflineUiTest.granting the mic in Settings enables Start on resume` — after granting via `UiAutomation`, the Start button is enabled without re-entering the route.

---

# P2

### SCS-10 — Retries have no attempt cap, and AUDIO/SERVER/CLIENT/UNKNOWN retry with zero delay
Severity: P2
Where: `input/speech/AndroidSpeechRecognizerAdapter.kt:367-384`
Scenario: `delay` is 0 unless the reason is BUSY or NETWORK; `retryAttempt` only shapes the BUSY/NETWORK backoff and is never compared against a limit, so there is no give-up state. In the shipped wiring the practical blast radius is smaller than the `_prev` report claimed — the second error already flips `speechRoute.useOffline`, so the third handle is Vosk (or Vosk fails and `onFailure` ends the session) — but the coordinator itself can still create/start/destroy recognisers in a tight main-thread loop for two iterations with system beeps, and `onError` is also the only path that publishes `lastError`, so a permanent failure has no terminal presentation.
Fix: apply backoff to every reason and add a max-attempt counter (5) that settles into `Error` and tells the host.
Failing test: `AndroidSpeechRecognizerAdapterTest.retries are bounded` — 20 consecutive `onError(CLIENT)` calls must produce increasing delays and a final non-retry.

### SCS-11 — `LANGUAGE_UNAVAILABLE` switches the language to English *and* the engine to Hindi-only Vosk, and says so
Severity: P2
Where: `ui/navigation/AppScreens.kt:438-449`; `input/speech/OfflineSpeechEngine.kt:176-180`; `input/speech/SpeechLanguagePolicy.kt:14-19`
Scenario: on `ERROR_LANGUAGE_UNAVAILABLE` the wiring sets `recognitionLanguage.value = "en-IN"` and shows "Hindi speech unavailable; switching to English", while `speechRoute.platformError` in the same block sets `useOffline = true`, so the next handle is `VoskSpeechRecognizerHandle` — which ignores `languageTag` entirely and only speaks `vosk-model-small-hi`. The user is told recognition moved to English; it moved to a Hindi model. For Hinglish-in-Latin input the result is junk evidence and a calm-looking meter.
Fix: pick one outcome. Either keep the platform recogniser with `en-IN` (no offline flip) or stay on Hindi and go offline; do not do both, and make the banner name the engine actually in use.
Failing test: `OfflineSpeechEngineTest.language-unavailable selects exactly one fallback` — after `platformError(LANGUAGE_UNAVAILABLE)`, `useOffline` and the language fallback must not both be applied.

### SCS-12 — `CallShieldReadiness` (778 lines) and its `Interruption` vocabulary are dead code in the app
Severity: P2
Where: `ui/call/CallShieldReadiness.kt:236-302`, `:711-745`
Scenario: `grep` finds exactly one consumer of the file — its own unit test. `ReadinessState.INTERRUPTED`, `Interruption.AudioFocusLost`, `Interruption.PermissionRevokedDuringSession`, `Interruption.ProcessRecreated` and their "why it stopped" copy are never produced by the screen, so the documented recovery language never appears. This is why SCS-02, SCS-09 and SCS-18 all degrade to silent state changes instead of an explained interruption.
Fix: either evaluate `CallShieldReadiness` from the wiring when a session ends unexpectedly, or delete the module before it is mistaken for shipped behaviour.
Failing test: none (removal) — or `CallShieldOfflineUiTest.a revoked permission shows the interrupted reason`.

### SCS-13 — The platform on-device recogniser is unreachable, and the disclosure never reflects the engine actually used
Severity: P2
Where: `input/speech/SpeechCapability.kt:430`, `:441-499`; `ui/navigation/AppScreens.kt:421-423`, `:428`; `ui/call/CallUiState.kt:70-76`
Scenario: `probe()` hard-codes `hiInModelDownloaded = false`, and `probeHindiModel` (the only thing that could set it) has no caller in `main`, so `SpeechStatus.OnDeviceReady` / `offlineCapable = true` can never be produced. `recognitionModeFor` therefore never returns `OnDevice`, and `createOnDeviceSpeechRecognizer` is never called: on every phone the platform route is the default (possibly network) recogniser. Conversely, when the app is running Vosk on-device (no network, or the user picked offline), `SpeechSessionPlan` is still built from the capability report, so the screen discloses "audio may leave the device" while nothing can leave the device. Errs safe, but it is not true, and it hides the fact that an on-device platform path was never offered.
Fix: call `SpeechCapabilityProbe.probeHindiModel` once at route entry and re-evaluate, or state plainly in the copy that only the bundled offline engine is on-device; and when `useOffline` is true, build the plan for the offline route.
Failing test: `CapabilityTest.a verified hi-IN model produces OnDeviceReady` plus a plan test asserting the disclosure matches the engine chosen.

### SCS-14 — Vosk emits a partial for every 4 KB read, and posts are not tagged to the session that created them
Severity: P2
Where: `input/speech/OfflineSpeechEngine.kt:56-66`, `:85-88`; `ui/navigation/AppScreens.kt:431-434`
Scenario: each loop iteration emits `partialText()` whether or not it changed (~8/s), and every partial is appended to `CallSpeechSession.rolling` (cap 200) — so the "two-minute transcript" is mostly duplicate partials and actually holds ~25 s of it. Separately, `handler.post { created.onUtterance(text) }` carries no generation token, so a final posted just before `destroy()` can land in the next session started milliseconds later.
Fix: emit a partial only when the text differs from the last one; tag each post with the handle instance and drop mismatches.
Failing test: `OfflineSpeechEngineTest.an unchanged partial is emitted once`.

### SCS-15 — The masked transcript stays on screen while `PAUSED`/backgrounded, and nothing sets `FLAG_SECURE`
Severity: P2
Where: `ui/call/CallUiState.kt:328-332`; `ui/navigation/AppScreens.kt:720-734`; no `FLAG_SECURE` in `main/`
Scenario: `ON_PAUSE` stops the recogniser (correct) and reduces `CallEvent.Pause`, which flips the phase but leaves `state.transcript` and the chip quotes intact, so the last two minutes of (masked) conversation are still rendered when the user returns, and appear in the recents thumbnail because no window flag is set. Only Stop/dispose wipe them. The header copy claims the transcript is cleared on Stop, which is true; the gap is that screen-off is not Stop.
Fix: clear the on-screen transcript on `CallEvent.Pause` (keeping A05's stage memory, which is the part BEHAVIOR_SPEC wants to survive), and add `window.setFlags(FLAG_SECURE)` while a Call Shield session is live.
Failing test: `CallScreenTest.pause clears the on-screen transcript but keeps stage memory`.

### SCS-16 — Devanagari digits are not masked
Severity: P2
Where: `ui/call/CallShieldHost.kt:556-566`
Scenario: `LONG_DIGITS = Regex("""\d{4,}""")` is ASCII-only on the JVM, so a Hindi recogniser emitting `१२३४५६` for a spoken OTP/PIN/account number is rendered unmasked in the transcript line and the chip quote — the one place the masking promise ("only masked identifiers are shown") is stated. ASCII sequences are masked correctly.
Fix: use `\p{Nd}{4,}`.
Failing test: `CallShieldHostTest.devanagari digits are masked` — `CallShieldRedaction.mask("ओटीपी १२३४५६")` must not contain `१२३`.

### SCS-17 — TTS lifecycle: no audio focus, not stopped on pause, uninstalled voices pass `hasVoice`
Severity: P2
Where: `ui/voice/AndroidSpokenVerdictTts.kt:17-55`; `ui/voice/SpokenVerdict.kt:33-53`; `ui/navigation/AppScreens.kt:722-723`
Scenario: `USAGE_MEDIA` with no `AudioFocusRequest`, so a warning talks over music and a phone call cannot duck it; the utterance is never stopped on `ON_PAUSE` (only on Stop/dispose), so it can keep speaking after the screen locks; `hasVoice`/`speak` require an exact language+country match, so an en-US/en-GB-only phone reports "no offline voice" for `en-IN`; `voice.features` is not filtered for `KEY_FEATURE_NOT_INSTALLED`, so an uninstalled voice can pass and then fail; and when the app language resolves to Hindi the Devanagari text is sent to the `en-IN` candidate if `hi-IN` is missing, reported as `Spoken`. There is also a `ready` race: the init callback sets `ready = true` even if `shutdown()` already ran.
Fix: request/abandon audio focus, stop TTS on `ON_PAUSE`, match language first then prefer country, filter uninstalled voices, and only fall back to `en-IN` when the text is Latin script.
Failing test: `SpokenVerdictTest.hindi text never falls back to an english-only voice`.

### SCS-18 — A Vosk failure ends the session with no reason, resets the stored choices, and can kill the wrong session
Severity: P2
Where: `ui/navigation/AppScreens.kt:433`, `:581`; `input/speech/OfflineSpeechEngine.kt:68-74`
Scenario: `onFailure` posts `offlineFailureAction = { host.onStop(); offlineMode = false; state = host.state }`. (a) No reason or copy is surfaced — the session just ends. (b) `state = host.state` skips `synced(...)`, so the language/haptics/reduced-motion choices revert to `CallUiState` defaults — the exact issue #138 regression the file's own comment says must not happen. (c) `offlineMode = false` while `speechRoute.useOffline` stays true, so the banner and the engine disagree. (d) The action is posted asynchronously and carries no generation, so a failure from an engine that was stopped during startup (SCS-08's released-`AudioRecord` throw) lands *after* the user paused and resumed and stops the healthy new session. (e) Only `Exception` is caught, so `UnsatisfiedLinkError`/`OutOfMemoryError` on model load escape on a worker thread.
Fix: pass a generation/engine id through `onFailure` and ignore stale ones; surface a `BlockReason`; publish through `synced(host.state)`; catch `Throwable` around model load and close the `Model` in `finally`.
Failing test: `OfflineSpeechEngineTest.a late failure does not stop a newer session`.

### SCS-19 — A partially copied Vosk model is never repaired
Severity: P2
Where: `input/speech/OfflineSpeechEngine.kt:108-143`
Scenario: readiness is a single file: `if (!File(localModel, "am/final.mdl").isFile) copyAssets(...)`. `assets.list()` order is arbitrary and there is no temp directory, no atomic rename, no marker file and no size check. If the process is killed or the disk fills after `am/final.mdl` is written, every later start skips the copy and `Model()` fails or dies natively on missing files — permanently, with no self-heal.
Fix: copy into `model-hi.tmp`, write a `.complete` marker last, rename; delete and re-copy when `Model()` throws.
Failing test: `OfflineSpeechEngineTest.an interrupted copy is retried` — a fake `AssetManager` throwing halfway; the second `start()` must re-copy.

### SCS-20 — The language auto-switch only takes effect at the next handle creation, and the sink holds a stale detector
Severity: P2
Where: `ui/navigation/AppScreens.kt:416`, `:689-717`; `input/speech/SpeechLanguagePolicy.kt:22-57`
Scenario: the detector judges script only, and the recogniser's language is fixed when the handle is created (`recognitionLanguageState.value` is read inside the factory). With SCS-01 there is no next handle creation on the Google path, so a detected switch never applies. The sink is installed once inside `remember(host)` and closes over the `scriptDetector` from first composition, while the detector is `remember(selectedSpeechTag)` — after an app-language change the sink keeps the old detector (and `languageName`/`selectedLanguage` captures). An `en-IN` engine always emits Latin, so a Hindi call can never switch back.
Fix: hold the detector in a `rememberUpdatedState` and rebuild the sink when it changes; treat a detected switch as a reason to `replaceRecognizer(...)` immediately.
Failing test: `CallShieldOfflineUiTest.a detected script switch recreates the recogniser in the same session`.

### SCS-21 — `AudioRecord` and `getMinBufferSize` are constructed on the main thread inside `coordinator.start()`
Severity: P2
Where: `input/speech/OfflineSpeechEngine.kt:146-154`, `:109-111`; `input/speech/AndroidSpeechRecognizerAdapter.kt:327-342`
Scenario: `AndroidPcmSource()` is constructed as an argument to `OfflineSpeechEngine(...)` inside `VoskSpeechRecognizerHandle.start()`, which runs synchronously from `coordinator.start()` ← `host.onStartRequested()` ← the Compose `onEvent` lambda. `AudioRecord.getMinBufferSize` and the `AudioRecord` constructor are unguarded, so any `IllegalArgumentException`/`UnsupportedOperationException` a device raises (mic held by another app, odd buffer size) propagates out of the click handler and kills the screen instead of producing a blocked state. `VoskSpeechRecognizerHandle.start()` is also the only handle whose `start()` can throw at all.
Fix: build the PCM source on the worker, inside the engine's `try`, and report failure through `onFailure()`/`BlockReason` rather than throwing at the caller.
Failing test: `OfflineSpeechEngineTest.an unavailable AudioRecord reports a failure instead of throwing` (with an injectable `PcmSource` factory).

### SCS-22 — With the microphone denied, the screen offers no fallback path at all
Severity: P2
Where: `ui/navigation/AppScreens.kt:639`; `ui/call/CallShieldScreen.kt:608-642`
Scenario: `onTypeTranscript = null` is passed into `CallShieldScreen`, so `PermissionCard`'s "typed transcript" button never renders and the mic-denied state offers only "grant permission". The recogniser-unavailable card still offers the staged demo, but the denied-mic path has no demo and no typed input. UI_SPEC (line 17) requires "offer typed transcript or staged speech demo path" for both "mic denied" and "ASR unavailable".
Fix: pass the staged-demo callback (or a typed-transcript entry) into `PermissionCard`.
Failing test: `CallShieldOfflineUiTest.a denied microphone offers the staged demo`.

---

## Unverified suspicions (not counted above)

- **U-1: platform echo suppression.** SCS-06 assumes the recogniser can transcribe the device's own speaker output. `VOICE_RECOGNITION` capture is often AEC-processed, which may suppress it on some devices. The code path (mic open, no mute) is certain; the acoustic outcome is not measured. The same applies to whether a phone call silences a third-party `VOICE_RECOGNITION` source (Android 10+), which is a product limit the copy already states.
- **U-2: `SpeechRecognizer` single-shot semantics.** SCS-01 rests on the documented contract that the recogniser stops after `onResults`. Not measured on a device here; every OEM recogniser I know behaves this way.
- **U-3: `destroy()` inside the recogniser's own `onError`.** `replaceRecognizer` destroys the previous handle from inside the callback. Common and generally safe; no hard failure evidence.
- **U-4: 32-bit-only installs.** The APK ships arm64-v8a/armeabi-v7a/x86_64 (`app/build.gradle.kts`), so a 32-bit-only device should fail at install rather than at `UnsatisfiedLinkError`. Not verified.
- **U-5: `TextToSpeech` after `shutdown()`.** Whether `setVoice`/`speak` on a shut-down engine throws or returns `ERROR` depends on the platform version; SCS-17's `ready` race is real either way.
- **U-6: whether Vosk reaches `onFailure` for a `null`/empty `AudioRecord.read`.** `read` returning 0 loops forever with no watchdog (`OfflineSpeechEngine.kt:61`); a permanently muted mic (call in progress) would spin the worker at 100% CPU. Not confirmed how often `read` returns 0 on real devices.

## Test gaps

- No test drives `CallShieldRouteHost`, where SCS-01/02/04/05/07/09/11/18 live. `CallShieldHostTest` and `AndroidSpeechRecognizerAdapterTest` exercise the pure layers with fakes, so a green suite says nothing about the wiring.
- `CallShieldOfflineUiTest` (instrumented) only asserts the offline toggle's 48 dp target and selection state.
- No rotation, permission-revoked-mid-session, spoken-alert-while-listening, or Vosk-slow-start test exists.
- `OfflineSpeechEngineTest` has no slow-factory, throwing-factory, or stop-during-load case.
