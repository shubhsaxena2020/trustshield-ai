# P8 review: QR Guard and payment advisory

Slice: `input/qr/*`, `ui/qr/*`, plus a grep for payment, advisory and payee across `core/` and `data/`.
Method: read-only code reading against `BEHAVIOR_SPEC.md` ("QR and payment semantics"). No Gradle, adb or emulator was run.
Paths below are relative to `apps/android/app/src/main/java/ai/trustshield/android/`.

## Scope gap: no payment advisory engine and no input provenance

- The grep over `core/`, `data/` and `ui/` found no payment-screenshot advisory (A09 in `HEAD_ORDERS_PHASE4.md` item 8).
  Nothing extracts OCR amount, time, payee or UTR, and no code uses `am` for any user-facing purpose.
- `core/rules/StageRules.kt` and `UtilityServiceRules.kt` only match "payment" and "amount" words as scam-stage signals.
  `DigitalArrestSignals.kt` has an "advisory cue" that is unrelated to payment.
- `ORCHESTRATOR-LOG.md` says payment advisory is "gated on P0 completion", so this is consistent with the plan.
  I recorded it as absent, not as a bug.
- `ScanScreen` has an image-scan (OCR) path but no payment logic.
- `AppScreens.kt:123` imports `UpiQrParser` but never uses it.
- `UpiPayload.flags` (collect mode, prefilled amount, malformed amount) is produced but nothing outside the parser and fixtures reads it (see QR-10).
- No input-provenance model exists. `DecodedPayee`, `SetupCapture` and `RegisterPayee` do not record whether the VPA came from camera, manual typing or demo (see QR-11).
- The mandatory settlement line is present: `QrUiState.kt:841` and `QrGuardScreen.kt:681-684`.

## Findings

### QR-01 (P1): Payee match is case-sensitive, while the rest of the UI is case-insensitive
- Files: `input/qr/QrWatcher.kt:141-150` (`identifierOf` hashes `vpa.trim()` only) and `:285` (`registry.matches`).
  `ui/qr/QrUiState.kt:170` (`normalizePayeeId` lowercases) and `ui/qr/QrGuardHost.kt:673` (`captureSetup` dedupe, case-insensitive).
- Scenario:
  - The shopkeeper types `Shop@OkAxis` into the manual field, or registers from a QR that has that casing.
  - The real QR encodes `shop@okaxis`. `VPA_PATTERN` accepts both casings.
  - Every frame is a "valid mismatch", so three frames raise a false **Tampered** alert.
  - Because the dedupe is case-insensitive, `captureSetup` also hides a re-scan of the same payee in a different casing.
  - The reducer and the watcher therefore disagree about identity.
- Fix: lowercase after trim in `SaltedPayeeRegistry.identifierOf` and in `PayeeMasking`. UPI VPAs are case-insensitive.
- Test: register `Shop@OkAxis`, then `observeDecoded("upi://pay?pa=shop@okaxis")` three times. Expect `Watching` and no alert.

### QR-02 (P1): SETUP candidate can change between the shopkeeper reading it and tapping Confirm
- File: `ui/qr/QrGuardHost.kt:671-684` (`captureSetup`); `:673-678` is a second path that clears `pendingSetup`.
- Scenario:
  - In SETUP every decoded frame replaces `pendingSetup`. The screen shows `ra***@okaxis`.
  - The shopkeeper reads it, but the camera drifts to a sticker QR, a neighbouring stand, or a stray QR.
  - `pendingSetup` silently becomes the other payee, and `confirmSetupCapture` registers whatever is pending at tap time.
  - That makes the attacker's payee the trusted one. The same race applies when the scan matches an already-registered payee: the candidate is cleared under the finger.
- Fix: freeze the candidate once shown (ignore further decodes until Confirm or Dismiss).
  Alternatively, require the same payee on N consecutive decodes and bind Confirm to the displayed masked id.
- Test: `onDecoded(merchantQr)`, `onDecoded(stickerQr)`, `confirmSetupCapture()`. Expect the merchant payee, or no change.

### QR-03 (P1): The re-register PIN gate is bypassable
- Files: `ui/qr/QrGuardHost.kt:307-320` (`sessionPin` starts null, `pinConfigured = true` always), `:1035-1036` (PinSetupPanel), `:540-547`.
- Scenario (first use):
  - Anyone holding the phone can long-press for 5 s and get the "create PIN" panel, because no PIN exists yet.
  - They set their own PIN, submit it, and the gate opens.
  - They then scan the sticker QR and register it as the trusted payee. The PIN protects nothing until the shopkeeper has already created one.
- Scenario (later): the PIN and the registry are in-memory only, and the file's own header says registry persistence is a known gap.
  Killing and reopening the app returns to an empty SETUP, and the sticker can be registered with no PIN at all.
- Fix: require a PIN created at first registration (before the first WATCH), and persist payees plus a PIN hash (keystore salt).
  Until persistence exists, show a visible "not protected" state rather than implying protection.
- Test: new session, register payee, `RequestReRegister`, `setSessionPin("1234")`, `SubmitPin("1234")`. Expect the gate to stay closed.

### QR-04 (P1): Session state is a plain `remember`, so config changes wipe registry, PIN and alert
- Files: `ui/qr/QrGuardHost.kt:~1454` (`val session = remember { QrGuardSession(...) }`); `AndroidManifest.xml:14-16` (no `configChanges` or `screenOrientation`).
- Scenario: a rotation, dark-mode toggle, font-scale change or locale change recreates the Activity.
  The registered payee, the PIN, the mismatch counters and a raised **Tampered** alert are all lost.
  The user lands on SETUP, and the next QR they scan becomes the trusted one.
- Not verified: whether `MainActivity` or the nav host holds the session elsewhere. I saw no ViewModel or saver for it.
- Fix: hold the session in a ViewModel, or persist registry and alert through a saver.
- Test: Compose `StateRestorationTester` with a registered payee and an alert, then recreate. Expect the state to survive.

### QR-05 (P1): Decoded non-UPI payloads, and the sticker-over-QR case, never raise tamper
- Files: `input/qr/SimulatedQrFixtures.kt:157-167` (shared extractor), `input/qr/QrWatcher.kt:269-272`, `ui/qr/QrGuardHost.kt:421-422`.
- Scenario:
  - A sticker whose payload is a URL, plain text, a BharatQR/EMV string (`000201...`), or `upi://pay?pa=<malformed>` is decoded successfully.
  - Because `extract` returns null, it is treated as "unreadable": the counter is untouched and `lastValidDecodeAt` is not refreshed.
  - After 5 minutes the screen says "QR dikh nahi raha (dhak gaya...)" instead of an alarm, and the shopkeeper is told to point the camera better.
- This reads the spec literally for no-decode. A successfully decoded payload that is not the registered UPI payee is evidence of a swap, though.
  This is the easiest evasion for an attacker: paste any phishing QR.
- Fix: add a distinct "decoded but not a valid UPI payee" watcher input.
  It should either count as a mismatch, or raise a separate "different QR on counter" warning that is not described as occlusion.
- Test: `observeDecoded("https://evil.example")` three times with a registered payee. Expect not to be silent `Watching`.

### QR-06 (P1): Parser and real UPI apps can disagree about which `pa` wins
- File: `input/qr/UpiQrParser.kt:309-321` (`parseQuery`, last value wins at `:320`).
- Scenario: `upi://pay?pa=evil@ybl&pa=shop@okaxis`, or the same with `PA=` or `p%61=`.
  - QR Guard lowercases keys, decodes percent-escapes, and takes the last value, so it sees `shop@okaxis` and reports a match.
  - A payer app that takes the first value (`Uri.getQueryParameter` returns the first) or is case-sensitive on keys would pay `evil@ybl`.
- Unverified in practice: which behaviour the common UPI apps have. The parser's differential is certain; the exploitability depends on the app.
- Fix: treat a duplicate `pa`, a non-lowercase `pa` key, or a percent-encoded key as `MALFORMED_PAYEE` (extract returns null, ideally combined with QR-05 handling).
- Test: `UpiQrParser.parse("upi://pay?pa=evil@ybl&pa=shop@okaxis")`. Expect `MALFORMED_PAYEE`.

### QR-07 (P1): Demo flows contaminate and clear the live session
- File: `ui/qr/QrGuardHost.kt:569-636` (`simulate`), `:796-804` (`installDemoPayees`), `:1039` (SimulatePanel rendered unconditionally).
- Scenario 1: `installDemoPayees` merges `dukaan.demo@okaxis` (and `dukaan.demo@paytm` for DualStand) into `state.payees`, and nothing removes them.
  After one demo tap, those public VPAs stay trusted in the real registry.
- Scenario 2: `simulate` sets `mismatchAlert = null`, `occlusion = null` and resets the PIN gate, and moves the phase to WATCH.
  A live tamper alert can be cleared by tapping Demo. The panel is not debug-gated.
- Scenario 3: while `demoFlow != null`, `tick()` returns early, so the occlusion timer is disabled until Stop.
- Fix: gate the panel on `BuildConfig.DEBUG` or a setting. Run demos on a separate `QrGuardSession`/registry, and refuse while an alert is up.
- Test: register a real payee, `simulate(Match)`, assert `state.payees` is unchanged. Raise an alert, `simulate(Match)`, assert the alert is still present.

### QR-08 (P2): Re-register paths reset watcher evidence and break the stated invariant
- File: `ui/qr/QrGuardHost.kt:506-515`; the C06 invariant is in `QrUiState.kt:474`.
- Scenario: `CloseReRegister`, or a PIN lockout, replaces the watcher with `newWatcher()`.
  `project()` (`:755`) then copies `consecutiveMismatches = 0` into the UI state.
  Two mismatches followed by "hold 5 s, then Back" erases the streak. The reducer says "re-register does not touch the counters".
- Fix: only reset the watcher's `ReRegisterPending` marker, or add a `QrWatcher.cancelReRegister()` that returns to Watching and keeps the counters.
- Test: 2 mismatches, `RequestReRegister`, `CloseReRegister`, then 1 mismatch. Expect Tamper (3/3).

### QR-09 (P2): Stale watcher timers cause a false "Occluded" on (re)start
- Files: `ui/qr/QrGuardHost.kt:661-664` (`newWatcher` sets `monitoringSince`), `:470-477` (`StartWatch` does not recreate the watcher), `:522`.
- Scenario: after `registerPayee` or `Stop`, the user waits more than 5 minutes and taps Start.
  `watcher.status()` runs immediately, `evaluateOcclusion` sees the stale reference, and the UI shows UNREADABLE_TIMEOUT before a single frame arrives.
  It is not a tamper verdict, so this is wrong copy rather than a false alarm.
- Fix: on `StartWatch`, rebuild the watcher or add `QrWatcher.restartMonitoring()`.
- Test: frozen clock; register, advance 6 min, send `StartWatch`. Expect `Watching` and no occlusion.

### QR-10 (P2): The UI occlusion timer can diverge from B06 during RE_REGISTER; parser flags are unused
- File: `ui/qr/QrGuardHost.kt:728-739`; `input/qr/QrWatcher.kt:365-369` (`evaluateOcclusion` only runs in `Watching`).
- RE_REGISTER: the watcher is `ReRegisterPending`, so it never reports `Occluded`. The tick branch still builds `Occlusion(timedOut = elapsed >= budget)`.
  The UI then shows UNREADABLE_TIMEOUT while the watcher says otherwise. It is harmless, but the two sources disagree.
- Unused flags: `UpiFlag.COLLECT_MODE`, `PREFILLED_AMOUNT` and `MALFORMED_AMOUNT` are never surfaced.
  A QR with `mode=04` (collect: asks the payer to approve a debit) shows nothing to the shopkeeper.
- Path blind spot: the parser ignores the path. `upi://mandate?...` (recurring debit) and `upi://collect?...` parse like `upi://pay`.
- Fix: pull occlusion only from `watcher.status()`. Add a flag for non-`pay` actions.

### QR-11 (P2): `pn` is attacker-controlled text shown in the alert, with no provenance label
- Files: `ui/qr/QrGuardScreen.kt:306-314` (alert), `ui/qr/QrGuardHost.kt:1210` (setup capture), `ui/qr/QrUiState.kt:204`.
- Scenario: the sticker has `pn=VERIFIED+MERCHANT+-+Payment+safe,+ignore+warning+call+1800...`.
  Up to 200 characters, control characters and bidi marks stripped, appear in the red mismatch panel as "QR par likha naam: ...", with no length cap.
  This is the one place QR payload text reaches the screen. `tn`, `mc` and `cu` are parsed but not shown.
- Positives: Compose `Text` does not interpret markup. `{id}` replacement uses a masked VPA that is regex-limited. Format characters (bidi) are stripped. No `Log` calls exist in the slice.
- Provenance gap: the shopkeeper-typed payee, the scanned `pn` and demo data are indistinguishable (see "Scope gap").
- Fix: cap `pn` at about 30 characters, quote it, and omit it from the tamper alert.
  Add an `origin` (`CAMERA`, `MANUAL`, `DEMO`) to `RegisteredPayee` and `ObservedQr`.

### QR-12 (P2): Three masking functions disagree, and `maskPayeeId` shows too much
- Files: `ui/qr/QrUiState.kt:131-143` (`maskPayeeId`: `len/2`, up to 3 characters), `input/qr/QrWatcher.kt:82-91` (`PayeeMasking`: 2 characters, fully hidden if the local part is shorter than 3), `input/qr/UpiQrParser.kt:272-287` (`maskVpa`: 1 character for a 2-character local part).
- Scenario: the UI uses `maskPayeeId`, so `9876543210@ybl` is shown as `987***@ybl`, and `ab@ybl` as `a***@ybl`.
  The docs and `PayeeMasking` promise `98***` and `***`. The tamper detail and the alert can show differently masked strings for the same VPA.
- Fix: use one masker (`PayeeMasking`) everywhere. Add a test that no masker reveals more than 2 characters.

### QR-13 (P2): Validation mismatch between parser and `isPlausibleUpiId`
- Files: `input/qr/UpiQrParser.kt:152` (local part 2-256 characters) versus `ui/qr/QrUiState.kt:152-167` (total length 3-64, Unicode letters allowed).
- Scenario 1: a valid VPA longer than 64 characters is shown as a setup candidate. Tapping Confirm is rejected silently by the reducer.
  The candidate vanishes and nothing is registered.
- Scenario 2: manual entry accepts non-ASCII letters that the scan path rejects.
- Fix: share one validator. Show an error when registration is rejected.

### QR-14 (P2): The mismatch counter resets on any matching frame, so alternating frames evade it
- File: `input/qr/QrWatcher.kt:285-290`.
- Scenario: the real QR and the sticker are both partly in view (or the sticker covers half the code), so frames alternate A, B, A, B.
  Each match resets the streak to 0 and 3-in-a-row never occurs. The spec says "consecutive", so this is a design weakness rather than a bug.
- Suggestion: a leaky counter (3 mismatches in the last 6 valid decodes).

### QR-15 (P2): Amount edge cases (parser)
- File: `input/qr/UpiQrParser.kt:155,409-418`.
- Accepted as `PREFILLED_AMOUNT`: `0`, `-0`, `+5`, `5.`, `1e8`, `1.2345678`, `1e2`.
- UPI `am` is a plain decimal with at most 2 fractional digits.
- A zero or exponent form is treated as legitimate.
- Safe cases: negatives, NaN/Inf, and non-ASCII digits are flagged. Huge exponents are bounded by the 32-character limit and the `NumberFormatException` catch. Blank `am=` gives no flag.
- Fix: use `^\d{1,9}(\.\d{1,2})?$` and reject zero.

## Verified OK

- The parser never throws, is bounded (8 KiB payload, 4096-character field input, 64-character names), and does no I/O or navigation.
- Control, format and private-use characters are stripped from every field, and oversized fields are nulled and flagged.
- Unreadable input never increments the counter (`QrWatcher.kt:269,306`).
- Occlusion ends in `Occluded`, never `Tampered`.
- Tampered is sticky (`:277`).
- Re-register needs both the 5 s hold and a verified PIN (`:317-335`).
- `registerPayee` is gated by `registrationAuthorized`.
- The raw VPA is not logged or put in exception messages; every `toString` is masked.
- The only `startActivity` targets the app's own settings (`QrGuardHost.kt:1611`).
- The camera is stopped on ON_STOP, on Stop and on dispose.
- The permission matrix is correct.

## Unverified

- U1: whether common UPI payer apps take the first or the last `pa` (QR-06).
- U2: whether anything else retains the session across Activity recreation (QR-04).
- U3: whether `MultiFormatReader` returns one of two QRs in the same frame, and which (QR-14).
- U4: whether bank BharatQR stickers (`000201...`) are common in the target market (QR-05).
- U5: the exact line of `remember { QrGuardSession }`. It is near `QrGuardHost.kt:1454`; grep it before citing.

## Test gaps

The existing tests (`QrWatcherTest`, `UpiQrParserTest`, `QrGuardSessionTest`) do not cover:
- case-variant VPAs
- duplicate `pa` keys
- candidate replacement in SETUP
- re-register counter preservation
- stale `monitoringSince`
- demo versus live isolation
- state restoration
