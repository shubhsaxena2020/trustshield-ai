# Review: Speech + Call Shield (P8, slice speech-callshield)

Base 74db613. Read-only static review. Nothing was built, run or put on a device. Scope: `input/speech/*`, `ui/call/*`, `ui/voice/*`, plus the wiring in `ui/navigation/AppScreens.kt` (`CallShieldRouteHost`, lines ~381-723), because that is where the policy actually runs. Paths below are relative to `apps/android/app/src/main/java/ai/trustshield/android/`.

Severity: P0 crash/data/privacy, P1 wrong verdict/broken flow, P2 quality.
Counts: P0 0, P1 11, P2 10.

Each finding below was confirmed by reading the code path.

## Wiring facts the findings rely on
- `AndroidSpeechRecognizerAdapter` (the class) is not constructed anywhere in `main`. `CallShieldRouteHost` builds `SpeechSessionCoordinator` and `CallShieldHost` directly.
- `CallShieldHost.onRecognizerError` has no caller. Recogniser errors go `created.onError(code)` (AppScreens.kt:430), which is the coordinator and not the host.
- Session and consent are hard-wired: `CallSpeechSession(consentGranted = true)` (AppScreens.kt:390) and `var consent by remember { SpeechConsent.initial() }` (:456).
- The manifest has no `configChanges`, no `screenOrientation`, no `INTERNET` permission, and only `ACCESS_NETWORK_STATE`.

---

## P1

### SC-01 (P1) The Google path stops listening after the first final result
`input/speech/CallSpeechSession.kt:630-632` (`Forwarder.onResults`), `:566-590` (the only `startListening` call), `AndroidSpeechRecognizerAdapter.kt:361-387`.
- `SpeechRecognizer` is single-shot. After `onResults` it is finished and needs a new `startListening`.
- `onResults` only forwards text to the session. The only restart paths are `coordinator.onError` for `NoSpeech` (`replaceRecognizer`) and for retryable errors.
- Scenario: the user starts Call Shield on a phone with the Google recogniser, says "aapka account block ho jayega", and gets one final result. The state stays `Listening` and the UI shows a live timer, but no recogniser is running. Later phrases are never heard, so the scorer is blind and the verdict is wrong.
- Fix: in the `Forwarder`, after `onResults`, tell the coordinator to restart. Call `session.replaceRecognizer(handleFactory(...))`, or call `startListening` again on a short post-delay. Cap the restart rate.
- Test idea: coordinator test with a fake handle. Call `onUtterance("x")`, then assert `handleFactory` was invoked again, or that the handle's `restartListening()` ran, while the session is still active.
- Note: the platform behaviour is from the SpeechRecognizer contract. It is not measured on a device.

### SC-02 (P1) Silence is counted as a platform failure and flips the app to offline Vosk
`ui/navigation/AppScreens.kt:417-419`, `input/speech/OfflineSpeechEngine.kt:175-185`.
- The error listener calls `speechRoute.platformError(code)` for every code, including `SPEECH_TIMEOUT`(6) and `NO_MATCH`(7), which B05 deliberately treats as silence.
- Two consecutive silent timeouts, such as a quiet pause on the call, set `useOffline = true` for the rest of the screen's life (nothing resets it except the user toggle).
- Scenario: a caller pauses for about 6 s twice. The next restart silently builds a `VoskSpeechRecognizerHandle`. Vosk is Hindi-only and has no English model. An English or Hinglish-in-Latin call then yields garbage and the scam is missed.
- Fix: skip `platformError` for codes 6 and 7, or for any code where `CallSpeechSession.mapError(code) == null`. Reset the counter on a partial result as well as on a final.
- Test idea: `OfflineSpeechPolicy` stays online after `platformError(7)` x3. Better, drive the real wiring through an extracted function.

### SC-03 (P1) The bundled Vosk engine can never run on a phone with no system recogniser
`input/speech/SpeechConsent.kt:109-110`, `ui/call/CallShieldHost.kt:518-523`, `AppScreens.kt:402` and `:248` (via `prepare`).
- `ConsentGate.check` blocks with `RecognizerUnavailable` when `report.status == MissingService`.
- The host's `recognizerFor` also maps that status to `RecognizerStatus.UNAVAILABLE`, which the reducer's start gate blocks.
- Vosk is the offline fallback, but it is gated on a capability that describes only the platform recogniser.
- Scenario: a Huawei, AOSP or no-GMS phone, or an emulator without Google. `isRecognitionAvailable` is false, so Start shows "recogniser unavailable". The 42 MB offline model never starts, which is exactly the case it is for.
- Fix: when the Vosk asset exists, treat `sessionPossible` as true for the offline route. The capability or consent gate should know about the second engine. Show the offline-only disclosure.
- Test idea: `CallShieldHost.onStartRequested` with a `MissingService` report and an offline route enabled returns `Listening` through a Vosk factory.

### SC-04 (P1) The host never learns of a terminal recogniser error, so the UI shows "Listening" over a dead engine
`AppScreens.kt:430`, `CallShieldHost.kt:329-339` (never called), `CallSpeechSession.kt:570,574,579,584`.
- Terminal paths: `NO_PERMISSION` goes to `session.stop()` in `coordinator.onError`. The handle's own `start()` failures (no permission, service unavailable, null recogniser) call `session.onRecognizerError(...)` directly. Both skip the host.
- `host.state.phase` stays `LISTENING`. `host.isListening` is false, but the screen renders `state`. The tick keeps running.
- Scenario: RECORD_AUDIO is revoked during a session. The user sees a running timer and a "listening" badge, but nothing is recording. A safe-looking screen on a call that is not being checked is a wrong-verdict risk.
- Fix: route `created.onError` and the handle's start failures through `host.onRecognizerError`, and have the host publish a blocked or interrupted reason. Remove the dead `RecognizerErrorCode.CLIENT || BUSY || NETWORK return` special case or document it.
- Test idea: `CallShieldHost` test. After `coordinator.onError(INSUFFICIENT_PERMISSIONS)`, assert `host.state.phase != LISTENING`.

### SC-05 (P1) Unbounded zero-delay retry for non-network errors
`AndroidSpeechRecognizerAdapter.kt:368-381`.
- `delay` is 0 unless the reason is BUSY or NETWORK. AUDIO, SERVER, CLIENT and UNKNOWN are all `retryable` and are rescheduled with no cap and no backoff.
- BUSY and NETWORK back off to 4 s but still never give up, since `retryAttempt` is capped at 3, not counted against a limit.
- Scenario: a service returns `ERROR_CLIENT` (5) on every start. A tight main-thread loop of create, start, error, destroy runs, with possible beeps, battery drain, and RECOGNIZER_BUSY and TOO_MANY_REQUESTS.
- Mitigation: `OfflineSpeechPolicy` flips to Vosk after 2 errors, but only for the next factory call. If `useOffline` was already false the loop still runs 2 times first. Offline selection does not help when Vosk itself cannot start.
- Fix: apply the backoff to all reasons, add a max attempts counter (for example 5), then settle to `Error` and tell the host.
- Test idea: coordinator with a `retryScheduler` that records delays. 20 consecutive `onError(CLIENT)` calls must produce increasing delays and a final non-retry.

### SC-06 (P1) Rotation or a config change ends the session and silently drops consent
`AppScreens.kt:456-460, 700-714`, `AndroidManifest.xml` (no `configChanges`).
- A rotation recreates the Activity. `remember` state is lost, and `DisposableEffect.onDispose` calls `host.onCleared()`, which destroys the recogniser and wipes the transcript.
- The new composition builds a fresh `SpeechConsent.initial()` and fresh `CallShieldHost`. It is `Idle`, with no auto-resume and no consent. `CallSpeechSession.snapshot()/restore()` (`:277-320`) is implemented and tested but never called.
- Scenario: a mid-call rotation, or dark-mode, language, or font-scale change, silently ends protection. The user must grant consent again and press Start, and the A05 stage memory is lost.
- Fix: either lock `screenOrientation=portrait` (or declare `configChanges="orientation|screenSize|keyboardHidden|uiMode|locale"`), or hoist consent and the host into a ViewModel or `rememberSaveable`. Consent is a persisted enum per B04's own docs.
- Test idea: androidTest, `ActivityScenario.recreate()` with Call Shield listening. Assert consent is still `Granted` and the phase is `PAUSED` or `IDLE` with a visible reason.

### SC-07 (P1) Microphone permission is read once; returning from Settings leaves it stale
`AppScreens.kt:457-459, 479-481`. `micGranted` is set at first composition and by the launcher result only.
- Scenario: the user denies (permanently), opens system Settings, grants the mic, and returns. `ON_RESUME` only calls `host.onHostResumed()`. `micGranted` stays false, so Start stays blocked with `MIC_PERMISSION_DENIED` until the route is re-entered.
- The reverse (permission revoked in the background) is also not re-checked. The handle then fails (see SC-04).
- `PermanentlyDenied` is never produced here (always `Granted` or `Denied`), so B04's "open Settings" copy (`SpeechPlanCopy.openSettingsForMic`) is unreachable.
- Fix: re-check `ContextCompat.checkSelfPermission` on every `ON_RESUME`. Pass `shouldShowRequestPermissionRationale` to distinguish `PermanentlyDenied`.
- Test idea: instrumented. Grant via `UiAutomation` after denial and assert the Start button works without re-entry.

### SC-08 (P1) Possible ANR: `stop()` and `destroy()` on the main thread join a worker that may be loading the 42 MB model
`input/speech/OfflineSpeechEngine.kt:52, 78-83`, `:112-113, 134-143`, called from `CallSpeechSession.stop()` and `replaceRecognizer`, and so from `ON_PAUSE`, `onDispose` and the Stop button.
- The worker does `decoderFactory.open()` first. On a first run that is `copyAssets` of ~42 MB plus `Model(...)`, which can take multiple seconds. `stop()` only flips a flag, closes PCM (not yet started) and then `worker.join()` with no timeout. Neither load is interruptible.
- Scenario: the user taps Start and immediately backgrounds the app or presses Stop. `ON_PAUSE`, then `coordinator.stop()`, then `join()`, blocks the UI thread until the model is copied and loaded. That is > 5 s on low-end phones, so an ANR is plausible.
- Aggravating factor: `stopPcm()` was already called, so when `open()` finishes, `pcm.start()` runs on a released `AudioRecord`, throws, and fires `onFailure()` (see SC-10).
- Fix: do not join on the caller thread. Set the flag, close the resources from the worker's `finally`, and run `join` with a short timeout or none. Check `stopped` between `copyAssets` and `Model(...)`.
- Test idea: `OfflineSpeechEngine` with a decoder factory that sleeps 3 s. `stop()` must return < 200 ms.

### SC-09 (P1) A partially copied Vosk model is never repaired
`OfflineSpeechEngine.kt:112, 134-143`.
- Readiness is judged by one file, `am/final.mdl`. `assets.list()` order is arbitrary and there is no temp dir, no atomic rename, no size or hash check.
- If the process is killed or the disk fills mid-copy after `am/final.mdl` is written, the next start skips the copy and `Model()` fails or crashes natively on missing files. This repeats forever, with no self-heal.
- Fix: copy to `model-hi.tmp`, then rename. Write a `.complete` marker last, or compare file count and size to the asset listing. Delete and retry on `Model` failure.
- Test idea: a fake `AssetManager` throwing halfway through. A second `start()` must re-copy.

### SC-10 (P1) A Vosk failure is silent and the post-stop failure can kill the wrong session
`AppScreens.kt:413, 561`, `OfflineSpeechEngine.kt:68-70`.
- `onFailure` posts `offlineFailureAction`, which is `host.onStop(); offlineMode=false; state = host.state`. There is no error copy and no reason; the user sees the session just end. `offlineMode=false` is set while `speechRoute.useOffline` stays true, so the UI and the route disagree.
- `state = host.state` skips `synced(...)`. This re-introduces issue #138, where the language, haptics and reduced motion preferences reset to defaults after a Vosk failure.
- The handler is posted asynchronously. A failure from an old engine (SC-08's released-record exception) can land after the user pressed Stop and Start again, and then `host.onStop()` kills the new, healthy session.
- Catch scope: only `Exception` is caught. `UnsatisfiedLinkError`, which can happen if a 32-bit-only device installs the arm64/x86_64-only APK, and `OutOfMemoryError` on model load are `Error`s. They kill the process from a non-main thread. `Model` is also leaked when the `Recognizer` constructor throws (`:113-114`).
- Fix: carry a generation or engine id in `onFailure` and ignore stale ones. Surface a `BlockReason`. Use `synced(host.state)`. Catch `Throwable` around Vosk load and close the model in `finally`.
- Test idea: engine whose `open()` throws `UnsatisfiedLinkError` must call `onFailure` without crashing the test JVM thread. A late failure after a new `start` must not stop the new session.

### SC-11 (P1) The spoken alert plays while the mic is open and can be heard back as a "caller" utterance
`AppScreens.kt:598-617`, `ui/voice/AndroidSpokenVerdictTts.kt:28-33`.
- `onSpeakAlert` speaks the warning text and the stage labels without pausing the recogniser (no stop, no mute). TTS audio is played through the speaker, so the same phone mic can transcribe it.
- The Vosk or Google result then arrives as a normal utterance and goes into `callState`/scorer, so the app can re-trigger and reinforce its own alert. If a stage label or the explainer contains a word the rules match (for example "turant", "OTP", "police"), it adds evidence that nobody said.
- Which labels do match was not checked; see the Unverified list.
- Fix: before `speak`, call `host.onHostPaused()` or an internal mute and resume when the utterance completes (needs an `UtteranceProgressListener`). Or drop any utterance that arrives while TTS is active.
- Test idea: `CallShieldHost` test. `onUtterance` while `speaking=true` is ignored.

---

## P2

### SC-12 (P2) Error codes 10, 11 and 13 are not mapped, and 12 is named wrongly
`CallSpeechSession.kt:396-406, 448`.
- By my recollection of the platform constants (not verified against `android.jar`, which I could not search in time): `ERROR_TOO_MANY_REQUESTS`=10, `ERROR_SERVER_DISCONNECTED`=11, `ERROR_LANGUAGE_NOT_SUPPORTED`=12, `ERROR_LANGUAGE_UNAVAILABLE`=13.
- The app names 12 `LANGUAGE_UNAVAILABLE` and has no entry for 10, 11 or 13, which all fall into `UNKNOWN` (retry delay 0).
- Effect: 13 (language pack not downloaded) does not trigger the Hindi-to-English fallback nor the immediate offline switch (`AppScreens.kt:420`). 10 (rate limited) gets an instant retry, which makes SC-05 worse.
- Fix: confirm against `android.speech.SpeechRecognizer`. Map 12 and 13 to a language reason (retry after fallback or offline) and 10 and 11 to backoff.
- Test idea: table test for `mapError` over 1..15 against `SpeechRecognizer.ERROR_*` in a Robolectric or androidTest.

### SC-13 (P2) The NoSpeech restart has no delay or cap
`AndroidSpeechRecognizerAdapter.kt:364-367`. Each `NO_MATCH` or `SPEECH_TIMEOUT` immediately creates a new recogniser. In a quiet room this repeats every ~5 s indefinitely, which is the usual cause of system beeps and `RECOGNIZER_BUSY`. Not wrong for a short call, but it should have a minimum gap and a rate limit.

### SC-14 (P2) Offline route with English or a non-Hindi language gives silent false negatives
`AppScreens.kt:504-538`. The English warning banner is shown, but Start is still allowed on a Hindi-only model, with no gate. English speech decoded by `vosk-model-small-hi` is mostly junk, so no stage lights. A "no stage found" result then looks like "calm call". The banner is informational only.
- Fix: for English app language plus offline, show Unable to Verify (or block Start) rather than a calm meter, unless the user confirms.

### SC-15 (P2) Spoken-verdict voice fallback can read Devanagari or Hindi text with an English voice
`ui/voice/SpokenVerdict.kt:34-39`. When the app language resolves to Hindi, candidates are `[hi-IN, en-IN]`. If there is no hi-IN voice, the Devanagari text is sent to an en-IN voice and reported as `Spoken`. Output is garbled or silent, and the UI thinks it spoke. For `HINGLISH`, Roman text is read by a Hindi voice.
- Related: `AndroidSpokenVerdictTts.kt:45-55` requires an exact country match (an en-US/en-GB-only phone gets "no voice"). It also does not exclude voices carrying `KEY_FEATURE_NOT_INSTALLED`, so an uninstalled voice can pass `hasVoice` and then fail in `speak`.
- Fix: fall back to English only when the text is in Latin script. Match the language first, then prefer the country. Filter `voice.features`.
- Test idea: `SpokenVerdictSpeaker` with Hindi text and a fake TTS that only has en-IN must return `TextOnly`.

### SC-16 (P2) TTS lifecycle and focus
- `AndroidSpokenVerdictTts.kt:11-24, 28-33`: no audio focus is requested or abandoned (`USAGE_MEDIA`, no `AudioFocusRequest`). Other media is not ducked and nothing is released on interruption. `speak` returning `SUCCESS` only means queued.
- `ready` is false until the async init callback, so an early tap reports "no voice".
- TTS is not stopped on `ON_PAUSE` of Call Shield (only on Stop or dispose), so a warning can keep speaking after the screen is locked.
- `grep` found no audio focus code anywhere in the app (only an `Interruption.AudioFocusLost` enum in `CallShieldReadiness.kt:76`, never produced). A phone call or another app capturing the microphone gives the recorder silence while the UI says Listening (Vosk `read` returns zeros, no error). There is no `AudioManager.OnAudioFocusChangeListener` or `AudioRecordingCallback` to detect it.

### SC-17 (P2) Language auto-switch is one-directional and its detector is stale
`input/speech/SpeechLanguagePolicy.kt:22-57`, `AppScreens.kt:396, 669-699`.
- It looks only at the script of the result. An `en-IN` recogniser always emits Latin, even for Hindi speech, so it never reports Devanagari. After the switch to English the app can never switch back to Hindi.
- A `hi-IN` recogniser transliterates English words, so it only switches to English if the engine emits Latin.
- The switch only takes effect at the next recogniser creation (the handle's language is fixed). With SC-01 this may be never.
- `scriptDetector` is `remember(selectedSpeechTag)`, but the utterance sink is installed once in `remember(host)` and captures the first detector. After the user changes the app language, the detector is stale. `selectedLanguage` in the status copy is also captured stale.
- Vosk ignores the language entirely.
- Fix: base the switch on a confidence signal or an explicit user control. Rebuild the sink when the detector changes (hold the detector in a `rememberUpdatedState`).
- Test idea: `SpeechScriptLanguageDetector("en-IN")` fed two Devanagari finals does switch; the product scenario (en-IN engine that never emits Devanagari) is the gap to document.

### SC-18 (P2) Transcript visible while paused or backgrounded, and Devanagari digits are not masked
- `CallShieldHost.kt:355-359`: `onHostPaused` stops the engine but keeps the UI `transcript` and `callState` while `PAUSED`. The masked transcript stays in RAM and in the Recents thumbnail. No `FLAG_SECURE` anywhere in the app. Only Stop or dispose wipes it. The header says "nothing survives Stop or process death", which is accurate, but "screen off" is not "stop".
- `CallShieldHost.kt:562`: `\d{4,}` matches ASCII digits only in Kotlin. A Hindi recogniser can emit Devanagari digits (`१२३४५६`) or spoken digits in words, and those are not masked in the on-screen transcript or the quote.
- Fix: use `\p{Nd}{4,}` (Unicode digits). Consider clearing the on-screen transcript on pause.
- Test idea: `CallShieldRedaction.mask("ओटीपी १२३४५६")` must not contain `१२३`.

### SC-19 (P2) Dead or misleading consent and doc claims
- `CallSpeechSession(consentGranted = true)` (AppScreens.kt:390) turns B05's own consent check into a no-op. Consent is only gated by the coordinator, and `consent` is never persisted (see SC-06), which contradicts the B04 "persisted enum" model.
- `CallSpeechSession.kt:546-547` says the intent sets `EXTRA_PREFER_OFFLINE`; `buildIntent` (`:600-606`) does not. `SpeechCapability.kt:474` says it is intentionally unset. Fix the doc.
- When Vosk is the route, the plan from `coordinator.prepare` still describes the Google route. The disclosure may read `MAY_USE_NETWORK` while no audio leaves the device. It errs on the safe side, but it is not true. The Google path's mode comes from the capability, not from `OfflineSpeechPolicy`.

### SC-20 (P2) Vosk partials flood the session transcript; stale posts can cross sessions
`OfflineSpeechEngine.kt:62-66, 85-88`; `AppScreens.kt:411-412`.
- A partial is emitted for every 4096-byte read (~8 per second), even if unchanged. Each becomes an `Utterance` in `CallSpeechSession.rolling` (cap 200), so the rolling buffer is mostly partials and holds ~25 s. Nothing reads it today (the host has its own `CallSessionState`), but `transcript()` and `stoppedTranscriptIsEmpty` suggest otherwise.
- `handler.post { created.onUtterance(text) }` has no session or generation check. A final posted by an old engine just before `destroy()` can land in a quickly restarted session.
- Fix: dedupe unchanged partials. Tag posts with the engine instance and drop mismatches.

### SC-21 (P2) Retry callbacks are never cancelled
`AndroidSpeechRecognizerAdapter.kt:375-381`, `AppScreens.kt:407`. Pending `postDelayed` retries are not removed on `stop()` or `onCleared`. They check `session.state is Error`, which holds for stale events if a later session ends in `Error` within the 4 s, and they keep the Activity context (via `handleFactory`) reachable until they fire.

---

## Checked and OK (for the record)
- No raw audio is stored. `onBufferReceived` is a no-op, the Vosk buffer is a local `ByteArray(4096)`, and there is no file write beyond the model copy. `AndroidManifest` has no `INTERNET` permission, so the app process cannot open a socket. The Google recogniser does its own network use out of process, and the disclosure says so.
- `stop()` is idempotent and wipes the B05 rolling transcript and counters. The destroy-once guard works (`CallSpeechSession.stop`, `AndroidSpeechRecognizerHandle.destroy`, `VoskSpeechRecognizerHandle.destroy`). `OfflineSpeechEngine` closes the decoder and the PCM source in `finally`, which releases native memory on stop.
- `ON_PAUSE` stops the recogniser (screen off, background), and `ON_RESUME` does not auto-restart the mic.
- The backoff arithmetic for BUSY and NETWORK (500 ms, 1 s, 2 s, 4 s) is correct; the problem is only what it excludes (SC-05).
- The Vosk model download is SHA-256 pinned at build time, with a path-traversal check on the zip entries. Nothing is downloaded at runtime.
- TTS only picks voices with `isNetworkConnectionRequired == false`.

## Unverified (suspicions, not confirmed)
- U-1: Whether the stage labels and the explainer spoken by `onSpeakAlert` contain tokens that `LocalScorer` matches, which would make SC-11 self-reinforcing rather than only noisy. Needs the rules and `CallShieldCopy` strings.
- U-2: Android 10+ concurrent-capture behaviour. During a telephony call a third-party `VOICE_RECOGNITION` source is generally silenced or may be blocked. This affects whether the "Listening" state is truthful on a real call (the product already states the limit).
- U-3: Whether `AudioRecord(...)` in `AndroidPcmSource` (`OfflineSpeechEngine.kt:148-154`), which is constructed on the main thread in `VoskSpeechRecognizerHandle.start()`, can throw on some OEMs (for example `UnsupportedOperationException` or `IllegalArgumentException` on a bad buffer size). It is not wrapped by try/catch, so it would throw out of `coordinator.start()` after the state is already `Starting`.
- U-4: Calling `SpeechRecognizer.destroy()` inside its own `onError` callback (`replaceRecognizer` -> `previous.destroy()`) on some OEM recognisers. Common in practice, but I have not found a hard failure.
- U-5: `32-bit-only` install behaviour for an APK with only arm64-v8a and x86_64 libraries (`app/build.gradle.kts:29`).
- U-6: The exact numeric values of `SpeechRecognizer.ERROR_*` 10-15 (SC-12), from memory and not from `android.jar`.

## Test gaps
- No test drives `CallShieldRouteHost` (the wiring where SC-01, 02, 04, 06, 07, 10 live). `AndroidSpeechRecognizerAdapterTest` exercises the coordinator with fakes, so passing tests say little about these paths.
- `OfflineSpeechEngineTest` has no slow-decoder-factory, throwing-factory, or stop-during-load case.
- There is no test for `callState.reset()`/transcript wipe on `onHostPaused`, and none for the Devanagari digit mask.
