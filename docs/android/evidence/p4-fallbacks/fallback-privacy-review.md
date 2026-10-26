# Fallback paths — privacy review (P4-fallbacks)

Read-only privacy review of the P4 fallback surfaces, on branch `android/P4-fallbacks` at `01b0694`
plus the uncommitted working-tree changes of the fallback package (4 modified production files,
5–7 new test files). This document is the **only** file this review created: no production or test
source, no build file, no keystore or `.env` was read as a secret or modified.

Scope reviewed:

| # | Fallback surface | Where |
|---|---|---|
| A | Call Shield readiness / copy | `ui/call/CallShieldReadiness.kt`, `ui/call/CallShieldCopy.kt` |
| B | Typed + staged demo | `ui/call/CallDemoScript.kt` |
| C | QR Guard camera-permission copy | `ui/qr/QrGuardHost.kt` (`QrHostCopy`) |
| C2 | QR Guard busy-camera copy | `ui/qr/QrGuardHost.kt:949-952`, `:992-997` |
| D | Device check | `ui/settings/DeviceCheck.kt`, `ui/settings/SettingsScreen.kt:243` |

A note on method: everything below is **code reading**. No Gradle run, no emulator, no `adb`, no
device, no network, per the P4 phone hold (`docs/android/HEAD_DIRECTIVE_PHONE_HOLD.md:5-7`). Line
numbers are from the working tree as reviewed; if they drift, re-read the cited symbol.

---

## 0. Bottom line

* **No fallback path stores message text, audio, camera frames or credentials** — not in memory that
  outlives the screen, not on disk, not in preferences, not in logs. Evidence in §1.
* **Every type in scope redacts itself in `toString()`**, with one benign exception
  (`DeviceCheckSnapshot` has no override but holds only non-sensitive device facts). Evidence in §2.
* **All seven parallel fallback tests now exist.** Their fixtures carry no real content and none of
  them opens camera or microphone hardware. They have **not been compiled or run** in this task, so
  "they pass" is UNPROVEN. Evidence in §3.
* **One real (low-severity) over-claim found**: `ReadinessCopy.nothingStored` says, unscoped,
  *"Audio is not saved."* — and it ships on the network-recogniser route, where the app cannot
  measure what the recogniser provider retains. Evidence in §4.
* **No fallback path makes a network call or reads a keystore.** The app declares no `INTERNET`
  permission at all, so this is enforced by the OS, not by convention. Evidence in §5.

---

## 1. Does any fallback path store content anywhere?

**Answer: no.** Checked for each sink separately: memory that survives the screen, disk
(`cacheDir`/`filesDir`/`MediaStore`/file I/O), `SharedPreferences`/DataStore, logs, and crash
reporting.

| Sink | Result | Evidence |
|---|---|---|
| `android.util.Log` / Timber / `println` / `printStackTrace` in `app/src/main` | **Zero call sites.** The only matches in the whole main tree are two KDoc sentences stating that there are none. | `input/share/MlKitTextRecognizer.kt:15`, `ui/home/ImageScanLauncher.kt:18` (both comments) |
| File / `cacheDir` / `filesDir` / `MediaStore` / `FileOutputStream` / `writeText` | **Zero call sites** in `app/src/main`. | repo-wide grep, no match |
| `MediaRecorder` / `AudioRecord` / PCM buffers | **Absent.** Audio capture is `SpeechRecognizer` only; the recognition buffer is discarded. | `input/speech/CallSpeechSession.kt:30-35` (KDoc hard rules), no match for `MediaRecorder`/`AudioRecord` |
| `SharedPreferences` (the only persistence in the app) | Allowlisted to **five** settings keys, none content-shaped. File is `MODE_PRIVATE`, and a write **clears** the file first so stray keys cannot survive. | `data/preferences/UserPreferencesStore.kt:33-41`, `:55`, `:59`; `data/preferences/UserPreferences.kt:137-152` (`SettingsKeys.ALL`: theme, language, sensitivity, haptics, reduced motion) |
| `onSaveInstanceState` (the only `Bundle` write in the activity) | Writes an `Int` counter and a route **name** only. The scan result travels via `onRetainCustomNonConfigurationInstance` (memory), explicitly "never written to saved state". | `MainActivity.kt:193`, `:195-199` |
| QR payee registry | `SaltedPayeeRegistry` is a `LinkedHashMap` in memory only, with an explicit "this class does not persist" integration note; the salt is a per-session `SecureRandom` array. | `input/qr/QrWatcher.kt:117-127`, `:121`; `ui/qr/QrGuardHost.kt:362`, `:829` |
| QR PIN | `sessionPin` is a nullable field on the host object — memory only, no `SharedPreferences` key for it. | `ui/qr/QrGuardHost.kt:333`; `SettingsKeys.ALL` above has no PIN key |
| Demo transcript / typed demo text | Held in `DemoProgress.executed` and `CallSessionState` in memory. `reset()` empties both. Compose state is `remember`, **not** `rememberSaveable`, so it is not written to the activity `Bundle`. | `ui/call/CallDemoScript.kt:397-406`, `:420-421`, `:458-462`; `ui/navigation/AppScreens.kt:396` |
| `allowBackup` | `false` — preferences and app-private files are excluded from cloud backup. | `apps/android/app/src/main/AndroidManifest.xml:7` |

Path-by-path:

* **A — readiness/copy.** `CallShieldReadiness.evaluate` is a pure function over `ReadinessInput`;
  its KDoc states the input carries "no text, no audio, no clock"
  (`ui/call/CallShieldReadiness.kt:154`, `:244`). `ReadinessCopy` is a `BilingualCopy` constant
  table (`:371-390`). Neither touches I/O.
* **B — typed/staged demo.** One typed line becomes a `DemoLine` held in `executed`
  (`ui/call/CallShieldDemo…` → `ui/call/CallDemoScript.kt:458-462`); `DemoStep.quotes` holds quote
  substrings from the engine, also in memory (`:338-346`). `CallDemoRunner.transcript()` is documented
  as "in memory only … must not be logged or persisted" (`:633-638`). `reset()` clears it (`:420-421`).
* **C / C2 — QR Guard copy.** `QrFallbackAction`, `QrPermissionFallback` and
  `QrHostCopy.permissionFallback` are data/enums over `ToneCopy` constants
  (`ui/qr/QrGuardHost.kt:190-212`, `:877-908`). `faultCameraBusy` / `faultGeneric` are literal
  `ToneCopy` constants (`:945-952`); `faultText(token)` is a `String`→copy map that "never echoes the
  token itself" (`:991-997`). No I/O, and the token that reaches it is a fixed `FAULT_*` constant
  (`input/qr/CameraXBarcodeDecoder.kt:869`), not camera data.
* **D — Device check.** `DeviceCheckProbe.snapshot` reads `Build.*` and two
  `checkSelfPermission` results (`ui/settings/DeviceCheck.kt:72-87`); `checkHindiLocale` forwards the
  probe outcome (`:89-101`). The `DeviceCheckLocaleRules` mapping is pure (`:23-36`). Nothing written
  anywhere; the panel renders, it does not persist.

**Verdict: PASS.**

---

## 2. Do the in-scope types redact themselves in `toString()`?

Checked every override plus the one type that has none.

| Type | `toString()` | What it prints | Verdict |
|---|---|---|---|
| `ReadinessReport` | `ui/call/CallShieldReadiness.kt:216-225` | state, three booleans, `disclosure`, `privacyMode`, `phase`, `interruption`, and **counts** (`actions=`, `limitations=`). No copy text at all. | **PASS** |
| `DemoLine` | `ui/call/CallDemoScript.kt:115-116` | `text=<redacted:N chars>`. Speaker + presenter note only. | **PASS** |
| `DemoStep` (the type that actually holds quotes) | `ui/call/CallDemoScript.kt:357-361` | `quotes=${quotes.size} verified`, score/label/position/stage ids. No quote text. | **PASS** |
| `DemoProgress` | `ui/call/CallDemoScript.kt:465-467` | counts, cursor, position. Omits `steps`, `executed`, `normalizer`. | **PASS** |
| `QrUiState` | `ui/qr/QrUiState.kt:374-377` | `payees=${payees.size}`, mismatch counter, `signal`, `pinGate`, `alert=${… != null}`. No handle, no PIN. | **PASS** |
| `RegisteredPayee` | `ui/qr/QrUiState.kt:201-202` | `maskedHandle=…`, `hasName=`. Raw `handle` never printed. | **PASS** |
| `ObservedQr` | `ui/qr/QrUiState.kt:215-216` | `maskedHandle=…`, `matched`, `atMs`. | **PASS** |
| `MismatchAlert` (adjacent, same alert path) | `ui/qr/QrUiState.kt:260-261` | `observedMasked=…` only; drops the name and the expected list. | **PASS** |
| `DeviceCheckSnapshot` | **no override** — default data-class `toString` (`ui/settings/DeviceCheck.kt:38-46`) | `androidVersion`, `apiLevel`, `abi`, `recognizerAvailable`, `hindiLocale`, `cameraPermission`, `microphonePermission` | **PASS (note)** |

Note on the last row: a default `toString()` here prints only OS build facts and two permission
booleans — there is no message, audio, frame or credential field on the type, so nothing leaks
(`:38-46`). It is not an oversight that needs fixing, but it is the one in-scope type whose safety
depends on its **field list** rather than on an override, so a future field added to that data class
would be printed by default. Worth a review rule rather than a code change.

Also confirmed: `maskPayeeId` (`ui/qr/QrUiState.kt:126-139`) keeps the **domain** part of the UPI id
(`…@okaxis`-shaped) and at most `localPart/2` capped at 3 leading characters, and the KDoc's claim
that the full local part can never be shown holds for every length (a 1–2 character local part masks
to zero visible characters). See §6 for the residual identifiability this leaves.

**Verdict: PASS.**

---

## 3. The parallel fallback tests

### 3.1 Which existed when I looked

Per the task, the set was re-checked twice. On the first check **none** of the seven existed; on the
second check, minutes later, **five** existed; on the final check **all seven** existed. All seven
were read in full. Nothing in this section describes a file that did not exist.

| Test file | Existed at final check | Read |
|---|---|---|
| `ui/settings/DeviceCheckFallbackTest.kt` | yes | yes |
| `input/speech/HindiRecognitionCapabilityTest.kt` | yes | yes |
| `ui/qr/QrCameraPermissionFallbackTest.kt` | yes | yes |
| `input/qr/CameraBusyFallbackTest.kt` | yes | yes |
| `ui/call/CallShieldMicrophonePermissionFallbackTest.kt` | yes | yes |
| `ui/call/CallShieldRecognizerFallbackTest.kt` | yes | yes |
| `ui/call/CallShieldTypedDemoFallbackTest.kt` | yes | yes |

Two of them (`CallShieldMicrophonePermissionFallbackTest`, `CallShieldTypedDemoFallbackTest`)
appeared only on the final check — they were **not** present when the review began, and their
contents below describe what I read, not what I expected.

### 3.2 Do they avoid real content?

**Yes.** Every fixture is a capability flag, enum, copy constant, or an already-synthetic repo
fixture.

* `CallShieldRecognizerFallbackTest.kt:14-15` and `CallShieldMicrophonePermissionFallbackTest.kt:17-18`
  both state "no speech, no audio, no transcript, no personal data, no OTP/PIN, no phone number and
  no UPI id". Verified: every report is built from `SpeechEnvironment` booleans
  (`CallShieldRecognizerFallbackTest.kt:70-80`, `CallShieldMicrophonePermissionFallbackTest.kt:69-78`).
* `CallShieldMicrophonePermissionFallbackTest.kt:108-115` goes further and **asserts** that no line of
  any report contains a digit, so an OTP/PIN/amount/UPI id cannot appear in the copy it pins.
* `CallShieldTypedDemoFallbackTest.kt:29-30` feeds only `CallDemoScript`'s own synthetic ladder lines;
  no free-text fixture is typed. It also pins that the transcript is empty after `reset()` (`:310-329`).
* `DeviceCheckFallbackTest.kt:21-22` and `:198-206` use `"test-version"` / `"test-abi"` placeholders.
* `QrCameraPermissionFallbackTest.kt:27-32` uses the repo's existing synthetic shop handle and a
  deliberately impossible phone-number-shaped handle as the "swapped sticker" — both already the
  established fixtures in `QrGuardScreenTest`. It also instantiates the policy with a **4-digit
  synthetic PIN literal** (`:34`). Neither is real data; both are worth knowing about because they
  are credentials-shaped strings living in source rather than in a fixture file.
* No fallback test writes to disk, and none calls `println` (the `println` call sites in the test tree
  are all in older suites — `DemoMessagesTest`, `ThemeTest`, etc. — outside this scope).

### 3.3 Do they open camera or microphone hardware?

**No.** None of the seven constructs `ProcessCameraProvider`, `Preview`, `ImageAnalysis`,
`MediaRecorder`, `AudioRecord`, or calls `startListening`. The only hardware-adjacent class touched is
`CameraUnavailableException`, and only as an allocated `Exception` carrying a reason int
(`input/qr/CameraBusyFallbackTest.kt:34-35`, `:49-67`), never via a camera provider —
`CameraBusyFallbackTest.kt:21-27` says so and `CameraFaultMapperTest` already sets that precedent.

`DeviceCheckFallbackTest.kt:16-19` and `HindiRecognitionCapabilityTest.kt:13-16` both record that
`android.jar` bodies throw on a plain JVM, so they restrict themselves to the pure cores.

### 3.4 Test-strength findings

* **RISK (low, honesty of the test, not of the product).** The "the Device check never opens the
  microphone" claim rests on `HindiLocaleSupportRules.startsRecognition(apiLevel)` returning a
  constant `false` (`input/speech/SpeechCapability.kt:111`) and two tests asserting that constant
  (`DeviceCheckFallbackTest.kt:165-174`, `HindiRecognitionCapabilityTest.kt:109-119`). It is a
  tautology: it proves the constant, not that `probeHindiLocaleSupport`
  (`input/speech/SpeechCapability.kt:576`) avoids `startListening`. The **real** evidence that the
  probe is microphone-free is by reading it — it calls `checkRecognitionSupport` (`:606`) and
  `destroy()`, and `startListening` appears only in `CallSpeechSession.kt:560` (the live session
  path). Recommend the test be described as a documented invariant, not as proof.
* `CameraBusyFallbackTest.kt:216-229` pins `QrHostCopy.faultText`'s signature by reflection — that is
  a genuinely useful structural guard (presenting copy cannot reach a camera handle), not a tautology.
* **UNPROVEN: none of the seven has been compiled or run.** No Gradle in this task. I verified the
  symbols the tests call exist (`CallDemoRunner.transcript()` at `ui/call/CallDemoScript.kt:631`,
  `positionFor` at `:276`, `LocalScorer.analyzeTranscriptScored` at `core/score/LocalScorer.kt:198`,
  `CallSessionState.transcript` at `core/score/CallSessionState.kt:85`), so nothing is obviously
  broken — but "compiles and passes" is not claimed here. `CallShieldTypedDemoFallbackTest.kt:24-27`
  says the same about itself.

**Verdict: PASS on content and hardware independence; UNPROVEN on execution.** One RISK on the
tautological `startsRecognition` assertion.

---

## 4. Does any fallback copy claim more privacy than it can measure?

One real finding, one benign wording note, and a clean bill for the rest.

### 4.1 RISK (low) — unscoped "Audio is not saved" ships on the network route

`ui/call/CallShieldReadiness.kt:385-390`:

> `english = "Audio is not saved. Recognised text stays in this session's memory only and is cleared when the session ends."`

`nothingStored` is a **base limitation added to every report**
(`ui/call/CallShieldReadiness.kt:751`, `:249`), including `READY_MAY_USE_NETWORK`, where
`privacyModeFor` returns `MAY_LEAVE_DEVICE` (`:345-349`) and the recogniser is documented as possibly
streaming audio off the device (`docs/android/ARCHITECTURE.md:21`, `:25`).

The sentence is true of **this app** (nothing in `app/src/main` writes audio anywhere, §1). It is not
measurable as a statement about *audio*, because on that route the audio has left the app's control
and the app cannot know what the recogniser service retains. The sibling copy is careful in exactly
this way — `CallShieldCopy.consentBody` scopes it: *"The **app** does not store audio recordings"*
(`ui/call/CallShieldCopy.kt:67-70`) — so the two surfaces make differently-scoped claims about the
same fact.

Why it matters: the accompanying limitation on that route says audio *may leave the device*
(`:401-404`), and a reader can compose "is not saved" + "may leave the device" into "leaves but is not
stored", which is a claim about the remote side. The honesty test does not close this gap: it forbids
`on-device` / `offline` / `private` on the network route
(`CallShieldRecognizerFallbackTest.kt:236-238`, `:394`) but says nothing about `saved` / `stored`.

Suggested fix direction (not made here — findings only): scope it to the app, e.g. "**This app** does
not save audio…", matching `CallShieldCopy.kt:67`.

### 4.2 Note — the session PIN copy understates rather than over-claims

`ui/qr/QrGuardHost.kt:940-943`: *"This PIN stays on this phone only and has to be created again after
the app closes."* In fact the PIN is memory-only (`:333`), so it does not stay on the phone at all.
Understating persistence is the safe direction; no action needed beyond not treating that line as a
description of storage.

### 4.3 Clean

Verified honest, with citations:

* No cloud/network line is labelled on-device/offline/private. `privacyModeFor` returns
  `ON_DEVICE_CLAIMED_BY_OS` only when `offlineCapable`, and its KDoc says that is "what the operating
  system reported — not a measurement by this app" (`ui/call/CallShieldReadiness.kt:141-147`,
  `:345-349`, `:45-46`).
* The on-device body explicitly disclaims the offline claim: *"Running offline has not been measured
  on this device, so this is only what the OS reports"* (`:548-554`).
* The missing-Hindi-model body names the network path and the consequence:
  *"…live listening would have to use the internet recogniser, and on that path some audio may leave
  the device. A live session is therefore not opened here."* (`:612-621`) — and the state indeed has
  `canStartLiveSession = false`.
* The demo is labelled as not-live in both languages: *"Demo script mode is on: the live microphone is
  off… nothing is recorded. The demo only runs on scripted lines inside the app."*
  (`:642-646`), `CallShieldCopy.stagedDemo` "not live mic" (`:255`), and the QR simulate control
  "not live camera decoding" (`ui/qr/QrGuardHost.kt:984-989`), pinned verbatim by
  `QrCameraPermissionFallbackTest.kt:262-316`.
* Camera fallback copy makes no privacy claim at all — it only names a recovery route
  (`ui/qr/QrGuardHost.kt:945-952`). The busy-camera copy tells the user to close the other app or use
  the demo (`:949-952`); it does not say the other app stopped watching, and it correctly does not
  imply any tamper verdict.
* Device check makes no privacy claim; it reports facts (`ui/settings/DeviceCheck.kt:49-67`).

**Verdict: RISK (low) on `nothingStored`; PASS everywhere else in scope.**

---

## 5. Network calls and keystore / credential reads

**Network: no.** Two independent reasons.

1. **The app has no `INTERNET` permission.** The manifest declares exactly two permissions, `CAMERA`
   and `RECORD_AUDIO` (`apps/android/app/src/main/AndroidManifest.xml:2-3`). Any socket open would
   fail with `SecurityException` on a release build, so "no network call from a fallback path" is an
   OS guarantee, not a code-review convention.
2. **The enrichment adapter is not wired to any screen.** `AnalysisApi` is referenced only inside
   `data/api/` itself; no UI, fallback, or navigation file constructs it (repo-wide grep). No
   `okhttp`/`retrofit`/`ktor` dependency appears in `app/build.gradle.kts` either.

The four fallback surfaces in scope do no I/O at all: `CallShieldReadiness.evaluate`
(`ui/call/CallShieldReadiness.kt:244`), `QrHostCopy.permissionFallback` /
`faultText` (`ui/qr/QrGuardHost.kt:877`, `:992`), `DemoProgress`/`CallDemoRunner`
(`ui/call/CallDemoScript.kt:397`, `:606`), and `DeviceCheckProbe`
(`ui/settings/DeviceCheck.kt:72`, `:89`) contain no URL, socket, or reader.

**Keystore / credentials: no.** Nothing in `app/src/main` calls `KeyStore`, `AndroidKeyStore`,
`Cipher`, or a credential store — the only crypto is in-memory HMAC-SHA256 for payee hashing
(`input/qr/QrWatcher.kt:144-152`) over a `SecureRandom` per-session salt
(`ui/qr/QrGuardHost.kt:829`). No keystore file, `.env`, or secret is read by any fallback path. The
absence of a keystore-backed salt is already a documented integration item, not a hidden one
(`input/qr/QrWatcher.kt:117-120`).

One caveat that belongs to this answer: the Device check's hi-IN probe creates a
`SpeechRecognizer`, binds to the recognition service, calls `checkRecognitionSupport`, and destroys it
(`input/speech/SpeechCapability.kt:576-606`). That is a **service bind, not audio capture** — no
`startListening` call exists on that path (only `CallSpeechSession.kt:560` has one) — but it is the one
place in scope that touches a system service, and on a phone it is observable. Worth stating plainly
if the Device check is ever described as "does nothing".

**Verdict: PASS.**

---

## 6. Verdicts

| # | Item | Verdict |
|---|---|---|
| 1 | Fallback paths store message text / audio / frames / credentials anywhere | **PASS** |
| 2 | In-scope types redact themselves in `toString()` | **PASS** (note: `DeviceCheckSnapshot` relies on its field list, not an override) |
| 3 | Parallel fallback tests avoid real content and avoid camera/mic hardware | **PASS** on content + hardware; **UNPROVEN** on compiling/passing (no Gradle in this task) — 7 of 7 files existed at final check |
| 3b | Test strength of the "never opens the microphone" assertion | **RISK** (low) — tautological constant assertion |
| 4 | Fallback copy claims more privacy than it can measure | **RISK** (low) — `ReadinessCopy.nothingStored` unscoped on the network route |
| 5 | Fallback paths make a network call or read a keystore/credential | **PASS** |

---

## 7. Remaining risk

* **The `nothingStored` wording is a product decision, not a bug.** It is true of the app and false as
  a statement about the audio once the network recogniser is used. Until it is scoped, do not quote
  the limitation list as evidence that *nothing anywhere* retains call audio.
* **Execution is unproven.** All seven tests are source-reviewed only. The pinned demo ladder
  (`CallShieldTypedDemoFallbackTest.kt:62-67`, scores 10/44/52/75) is copied from `CallDemoScript`'s
  own KDoc table; if a rule change has already moved a rung, that test fails on first run and the
  failure is a fixture drift, not a privacy regression.
* **`DeviceCheckSnapshot` is one field away from printing something.** It has no `toString()`
  override. Today that is harmless (§2); a future field (a locale tag from user input, a device name)
  would print by default. A review rule at that data class is cheaper than relying on memory.
* **Masking is partial by design.** `maskPayeeId` keeps the full PSP/domain part and up to three
  leading characters of the local part (`ui/qr/QrUiState.kt:126-139`), so a `toString()` that reaches
  a log would narrow a payee to a recognisable shortlist. There is no log sink today (§1), so this is
  latent, not live. It is also why `QrUiState.toString()` printing only `payees.size` matters.
* **Credentials-shaped literals live in test source.** `QrCameraPermissionFallbackTest.kt:34` carries
  a 4-digit synthetic PIN and `:32` a phone-number-shaped handle. Both are synthetic and both are the
  repo's existing fixtures, but a future "no credential literals in the repo" scan will flag them.
  Move them to a shared fixture object if that scan is adopted.
* **Adjacent surfaces were not re-audited.** `ui/home/ScanScreen.kt:157-159` carries the strongest
  privacy sentence in the app (*"your message is checked on this phone only; the app does not save,
  share or upload it"*). It is outside this fallback scope and I did not re-verify it end to end; it
  is true of today's wiring (no `INTERNET` permission, enrichment unwired) but it is the sentence that
  breaks first if enrichment is ever connected.
* **Nothing was fixed.** This package is findings only; every RISK above is still present in the
  working tree as reviewed.
