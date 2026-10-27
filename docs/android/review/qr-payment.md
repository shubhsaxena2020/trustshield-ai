# QR Guard + Payment Advisory — review (P8)

Slice: `input/qr/*`, `core/payment/*`, `ui/qr/*`, plus the payee-registry / prefs code they touch.
Base: `origin/android-finals` @ `d32fcda` (includes PR #164, PR #165, `80f9a9f`). Method: static read of
the real code path only; no Gradle, adb, emulator or git write. Every finding below names the functions
it was verified through.

**Status of `_prev/qr-payment.md`:** its central claim — "no payment advisory engine exists" — is false
at this base. `core/payment/PaymentAdvisoryContract.kt`, `PaymentAdvisoryEngine.kt`,
`PaymentFieldsParser.kt` exist and are wired into both `ui/qr/QrGuardHost.paymentAdvisoryForQr` and
`ui/result`. Nothing in this report is carried over from that file.

## Findings

### QP-01 — Duplicate `pa` is resolved last-wins with no ambiguity flag, so a two-payee QR reads as MATCH
Severity: P1
Where: `apps/android/app/src/main/java/ai/trustshield/android/input/qr/UpiQrParser.kt:309-322`;
`.../input/qr/SimulatedQrFixtures.kt:157-167`; `.../input/qr/QrWatcher.kt:264-299`
Scenario: payload `upi://pay?pa=attacker@ybl&pa=shop@okaxis&cu=INR`. `UpiQrParser.parseQuery` keeps one
value per key (`out[key] = ...`, last wins), so `UpiPayload.payeeVpa == "shop@okaxis"` and no flag is
emitted for the duplicate (`UpiFlag` has no duplicate/ambiguity member, and `parseQuery` discards the
earlier value before any caller can see it). The bound extractor (`SimulatedQrFixtures.DEMO_EXTRACTOR`,
seeded at `QrGuardHost.kt:315`) therefore returns the merchant VPA, `QrWatcher.observeDecoded` hits
`registry.matches(payee)` → match, `consecutiveMismatches = 0`, state `Watching`. The screen shows
`QrSignal.MATCH` ("Aapke QR pe nazar hai") for a QR that carries a second, different payee. If the
payer's UPI app honours the *first* `pa`, the payer sends money to `attacker@ybl` while TrustShield
reports green — a full bypass of the product's only promise. The parser behaviour is pinned on purpose
(`UpiQrParserTest."duplicate parameters keep the last value like parse_qs"`) for backend parity, but
parity with `_parse_upi` (`values[-1]` in `services/analysis/pipeline.py:294`) is not a safety argument
for a *guard*: on ambiguity the guard must fail closed, not certify one of two payees.
Fix: in the extractor binding, treat a payload with more than one `pa` as `null` (unreadable → pause +
occlusion, never a mismatch and never a match). Surgical form: have `parseQuery` also return the
duplicate keys it collapsed (or a `hasDuplicate("pa")` bit on `UpiPayload`) and return `null` from
`DEMO_EXTRACTOR` when it is set. Do not touch the selected-field contract the parity tests assert.
Failing test: `UpiQrParserTest` new method `duplicate pa is not a comparable payee` — assert
`payeeExtractor().extract("upi://pay?pa=a@ybl&pa=b@okaxis") == null`; today it returns `"b@okaxis"`.

### QP-02 — `pinConfigured` is hardcoded `true`, so a shop without a session PIN is locked out of re-registering
Severity: P2
Where: `.../ui/qr/QrGuardHost.kt:328-331`, `:502-517`; `.../ui/qr/QrUiState.kt:684-693`
Scenario: `QrPolicy(pinConfigured = true, pinVerifier = PinVerifier { sessionPin?.let { ... } ?: false })`
is built once, and `sessionPin` is `null` until `setSessionPin` is called. In `QrPhase.WATCH` the
operator long-presses re-register (`QrEvent.RequestReRegister` → `requestReRegister`), the PIN field
opens, and any PIN they type is `WRONG_PIN` because the verifier has no reference. Three attempts →
`submitPin` sets `lockedOut = true` and drops the phase to `WATCH`; every later `requestReRegister`
returns `LOCKED_OUT`. So a shopkeeper who never created a session PIN loses the ability to re-register
the new QR *in that session*, and `QrPinError.NOT_CONFIGURED` — the honest "no PIN set yet" branch in
`requestReRegister`/`submitPin` — is unreachable by construction (`pinConfigured` is a constant `true`).
Only `Stop` → `StartWatch` (which resets `pinGate`) recovers, and nothing on screen says so.
Fix: derive the flag from the real state instead of the constant — e.g. in `onUiEvent`, pass
`policy.copy(pinConfigured = sessionPin != null)` to `QrReducer.reduce` for `RequestReRegister` and
`SubmitPin` (one-line change at each call site; `QrPolicy` is already a `data class`).
Failing test: `QrGuardSessionTest` new method `re-register without a session pin reports NOT_CONFIGURED`
— after `setSessionPin` is never called, `onUiEvent(QrEvent.SubmitPin("1234"))` must leave
`pinGate.lastError == QrPinError.NOT_CONFIGURED`; today it is `WRONG_PIN` and the second/third attempt
locks out.

### QP-03 — The payment advisory is never cleared, so a stale card survives Stop, occlusion and unreadable frames
Severity: P2
Where: `.../ui/qr/QrGuardHost.kt:318` (field), `:433` (assign), `:491-500` (`Stop`), `:670` (`publish`);
`.../ui/qr/QrGuardScreen.kt:139-148`
Scenario: `onDecoded` sets `paymentAdvisory = paymentAdvisoryForQr(payload)` for every decoded frame;
`onUnreadable()`, `tick()` and `QrEvent.Stop` never touch it. `QrEvent.Stop` explicitly drops the rest
of the session ("A stopped session keeps no verdict memory", creating a fresh watcher and nulling
`lastDecoded`), yet `publish()` keeps re-emitting the old advisory and `QrGuardScreen` draws
`paymentAdvisory?.let { PaymentAdvisoryCard(...) }` outside every phase check. So after Stop, the
last-scanned payee's masked id, verdict and hints stay on a screen that claims the session ended; the
same card also stays up while the QR is occluded or unreadable, describing a QR no longer in view.
Fix: set `paymentAdvisory = null` in `onUnreadable()`, in the `tick()` projection when
`status.state == Occluded`, and in the `QrEvent.Stop` / `StartWatch` branches of `onUiEvent`; and gate
the render on `state.phase == QrPhase.WATCH || state.phase == QrPhase.RE_REGISTER`.
Failing test: `QrGuardSessionTest` new method `stop clears the payment advisory` — decode the demo
merchant payload, `onUiEvent(QrEvent.Stop)`, then `snapshot().paymentAdvisory` must be `null`; today it
is non-null. `QrGuardScreenTest` companion: compose with `phase = STOPPED` and a non-null advisory and
assert the confirmation line is absent.

### QP-04 — "Same payee" is case-insensitive in the advisory and case-sensitive in the watcher
Severity: P2
Where: `.../input/qr/QrWatcher.kt:141-150` (`SaltedPayeeRegistry.identifierOf`, `normalized = vpa.trim()`);
`.../ui/qr/QrPayeePaymentBridge.kt:109-116` (`comparableId` → `normalizePayeeId` = `trim().lowercase()`);
`.../data/preferences/RegisteredPayeeStore.kt:37-41` (documented as `trim` + `lowercase`)
Scenario: the merchant registers `shop@okaxis`; a re-encoded QR reads `pa=Shop@OkAxis` (different case is
a different byte string, so a different HMAC → mismatch in B06). `QrWatcher.observeDecoded` counts it as
a mismatch and reaches `Tampered` on the third frame, while `QrGuardHost.paymentAdvisoryForQr` →
`QrPayeePaymentBridge.matchesRegisteredPayee` lowercases both sides and emits
`PAYEE_MATCHES_REGISTERED` ("This payee matches a payee you saved"). Both are derived in the same
`onDecoded` call, so one screen shows a red tamper alert above a green "matches a saved payee" hint.
The bridge KDoc claims it is "exactly one definition of 'the same payee' in the app"; there are three
(`SaltedPayeeRegistry`, `QrPayeePaymentBridge`/`QrReducer`, `RegisteredPayeeStore`).
Fix: make one normaliser authoritative — have `SaltedPayeeRegistry.identifierOf` (and
`RegisteredPayeeStore.identifierOf`) hash `vpa.trim().lowercase()`, keeping the existing case-insensitive
`normalizePayeeId` as the single definition. Registry entries are per-session, so no migration is needed.
Failing test: `QrPayeePaymentBridgeTest` new method `bridge and registry agree on payee identity` —
`QrWatcher(extractor, SaltedPayeeRegistry(salt), clock).also { it.registerPayee("shop@okaxis") }`
`.observeDecoded("upi://pay?pa=Shop@OkAxis").state` must be `Watching` (not `Tampered`), and
`matchesRegisteredPayee("Shop@OkAxis", listOf(RegisteredPayee("shop@okaxis")))` must agree with it.

### QP-05 — The QR advisory accepts a `pa` the watcher rejects as malformed, so the card warns on a frame B06 calls unreadable
Severity: P2
Where: `.../ui/qr/QrGuardHost.kt:674-693`
Scenario: `paymentAdvisoryForQr` takes `qr.payeeVpa` and only filters the *amount*
(`takeIf { !qr.hasFlag(MALFORMED_AMOUNT) }`); it never checks `UpiFlag.MALFORMED_PAYEE`. For
`upi://pay?pa=a@b&am=5000` (local part 1 char: fails `VPA_PATTERN` {2,256} in `UpiQrParser`, so
`UpiFlag.MALFORMED_PAYEE` is set) the extractor returns `null` → `QrWatcher.observeDecoded` takes the
unreadable path (correct: no counter, occlusion timer), while the same frame builds
`PaymentFields(payeeUpi = "a@b")` → `hasPayeeUpi` true → `PAYEE_NOT_REGISTERED` WARNING and, with the
amount, `AMOUNT_LARGE` → verdict `STOP`. The screen then shows a red "!!" advisory about a payee the
guard itself refuses to believe, which is the one thing the "unreadable is never tamper" rule exists to
prevent.
Fix: add `&& !qr.hasFlag(UpiFlag.MALFORMED_PAYEE)` to the payee `takeIf` (return `null` for the whole
advisory when the payee is malformed, matching `DEMO_EXTRACTOR`).
Failing test: `QrGuardSessionTest` new method `malformed pa produces no advisory` — feed
`"upi://pay?pa=a@b&am=5000"`, assert `snapshot().paymentAdvisory == null` and
`state.consecutiveMismatches == 0`; today the advisory is non-null with verdict `STOP`.

### QP-06 — The occlusion timer is not reset when watching starts, so a pause in SETUP opens with a false "QR dikh nahi raha"
Severity: P2
Where: `.../ui/qr/QrGuardHost.kt:482-489` (`StartWatch` branch), `:729-773` (`project`), `:760-761`;
`.../input/qr/QrWatcher.kt:339-350` (`registerPayee` sets `lastValidDecodeAt`), `:365-369`
Scenario: `confirmSetupCapture` → `RegisterPayee` → `syncRegistry` → `watcher.registerPayee(...)` sets
`lastValidDecodeAt = now` and `monitoringSince = now`. The UI is still `QrPhase.SETUP`, so `tick()`
returns early and no occlusion is evaluated. If the shopkeeper leaves the screen there for longer than
`occlusionBudgetMs` (5 min) and then presses Start Watch, the `StartWatch` branch resets the demo state
but not the watcher; the first `tick()` → `refresh()` → `evaluateOcclusion(now)` sees
`now - lastValidDecodeAt >= 300_000` and flips to `Occluded`, and `project()` builds
`Occlusion(startedAtMs = <registration time>, elapsedMs > 300_000, timedOut = true)`. The operator sees
"QR dikh nahi raha (dhak gaya, hil gaya ya roshni kam hai)" with a six-minute timer before a single
frame has been read.
Fix: in the `QrEvent.StartWatch` branch call `watcher = newWatcher()` (or `watcher.refresh()` after
setting a fresh reference), exactly as `QrEvent.Stop` already does — `newWatcher()` also resets
`watchReferenceMs`, keeping `project()`'s fallback consistent.
Failing test: `QrGuardSessionTest` new method `starting a watch after a long setup does not open occluded`
— register a payee at `t = 0` with a fake clock, advance the clock 301_000 ms, `onUiEvent(StartWatch)`,
`tick()`, assert `state.signal != QrSignal.UNREADABLE_TIMEOUT`; today it is `UNREADABLE_TIMEOUT`.

### QP-07 — A demo run permanently pollutes the live payee registry and disables the occlusion tick
Severity: P2
Where: `.../ui/qr/QrGuardHost.kt:581-648` (`simulate`), `:830-838` (`installDemoPayees`), `:465-469`
(`tick`), `:652-657` (`now`, `demoShiftMs`)
Scenario: `simulate` calls `installDemoPayees(flow.registeredPayees)`, which *merges*
`dukaan.demo@okaxis` into `state.payees` (`merged = (state.payees + demo).distinctBy { it.key }`) and
calls `syncRegistry`. Nothing removes demo payees afterwards: the `Stop` branch and `QrReducer.stop()`
deliberately keep `payees`, and `LaunchedEffect(view.state.payees) { onPayeesChanged(...) }` pushes the
merged list to the Result route. A shop that registered its own QR and then ran "Demo: sticker tamper"
ends up with a synthetic payee in its expected list, visible in the expected-merchant panel, and the
Result advisory will report `PAYEE_MATCHES_REGISTERED` for `dukaan.demo@okaxis` — a payee the user never
registered. Second half: `demoFlow` is cleared only by `StartWatch`/`Stop`, and `tick()` starts with
`if (demoFlow != null) return`, so after any demo the five-minute occlusion rule cannot fire; and
`demoShiftMs` keeps the last demo offset (up to `FrameIntervalMs * 31 = 310_000` ms), so after a later
`StartWatch` resets the shift to 0 the watcher's `lastValidDecodeAt` stays 310 s in the future and
`now - reference` is negative — the occlusion timer is frozen for ~5 min of real time.
Fix: (a) `installDemoPayees` should keep demo payees in a separate `demoPayees` list rendered with the
demo badge and merged only inside `project()`'s `expectedMasked`, never into `state.payees`/the registry
that `onPayeesChanged` publishes; (b) clear `demoFlow` and `demoShiftMs` in `tick()`'s guard, e.g. run
`if (demoFlow != null && state.phase != QrPhase.WATCH) return`, or simply drop the guard and reset
`demoShiftMs = 0L` when the flow's last scripted step has been replayed.
Failing test: `QrGuardSessionTest` new method `demo does not change the registered payee list` — register
`shop@okaxis`, `simulate(QrSimulationFlow.Mismatch)`, assert `snapshot().state.payees.map { it.handle } ==
listOf("shop@okaxis")` and that a subsequent `tick()` advances `occlusion`; both fail today.

### QP-08 — Receipt timestamps are read in the device zone, so `TIME_RECENT` and the verdict depend on `TimeZone`
Severity: P2
Where: `.../core/payment/PaymentFieldsParser.kt:526-540` (`ZoneId.systemDefault()` in `parseTimestamp`)
Scenario: the field parser is the only producer of `PaymentFields.timestampEpochMs`, and
`PaymentAdvisoryEngine.isRecent` turns it into `TIME_RECENT` inside the verdict input
(`cautionCount` drives `CHECK`). `"27 Oct 2026, 7:04 pm"` parses to a different epoch on a phone set to
`Asia/Kolkata` than on one set to `UTC`, i.e. the same screenshot text yields a different hint set on a
differently-configured device — a direct deviation from the product invariant "deterministic rules
engine: same input → same verdict, no locale/time dependence in the decision path"
(`docs/android/review/CONTEXT.md`). The contract KDoc acknowledges the choice ("read in the device zone
by the parser"), but the consequence (a verdict input flipping with the device setting) is not recorded
anywhere, and it fails open: a genuinely recent receipt on a device whose zone is behind the receipt's
zone reads as not-recent.
Fix: pass the zone in (`PaymentFieldsParser.parse(text, zone: ZoneId = ZoneId.systemDefault())`) so the
caller owns it, and have the Result/QR call sites pass `ZoneId.systemDefault()` once at the composition
boundary — the decision path then takes the zone as an explicit input like `nowEpochMs`. Cheapest
alternative if the signature must not move: mark the parsed time as zone-uncertain by leaving
`timestampEpochMs` null for zone-less formats (the engine then says `TIME_MISSING`, which is honest).
Failing test: `PaymentFieldsParserTest` new method `timestamp parsing is independent of the default zone`
— parse `"27 Oct 2026, 7:04 pm"` under two `TimeZone` values and assert equal `timestampEpochMs`.

### QP-09 — Setup capture silently does nothing for a structurally valid payee longer than 64 characters
Severity: P2
Where: `.../ui/qr/QrGuardHost.kt:705-718` (`captureSetup`), `.../ui/qr/QrUiState.kt:152-167`
(`isPlausibleUpiId`: `trimmed.length !in 3..64`), `:627-629` (`registerPayee` returns `s` unchanged);
`.../input/qr/UpiQrParser.kt:152` (`VPA_PATTERN` accepts up to 256+64 chars, `VPA_LIMIT = 320`)
Scenario: `captureSetup` accepts any payee the extractor returns — the extractor only rejects
`MALFORMED_PAYEE`, which permits a `pa` of 100 characters. `pendingSetup` is built, the panel invites
"Confirm", `confirmSetupCapture` sets `pendingSetup = null` and raises `QrEvent.RegisterPayee(vpa, name)`;
`QrReducer.registerPayee` rejects it because `isPlausibleUpiId` caps at 64, so `state.payees` is
unchanged and `project()` republishes the same SETUP state. The operator sees the candidate panel
disappear with the payee never registered and no explanation (and the same QR is re-offered on the next
frame). The two validators disagree (3..64 vs 2..320).
Fix: make `captureSetup` use the same predicate as the reducer — reject (`return`) when
`!isPlausibleUpiId(vpa)` — so the candidate never reaches the confirm button; or widen one of the two
bounds to match. Preferable: `captureSetup` also sets a one-line reason on the view so a rejected read is
explained rather than ignored.
Failing test: `QrGuardSessionTest` new method `setup capture of an over-long payee is not offered` — feed
`"upi://pay?pa=${"a".repeat(70)}@okaxis"`, assert `snapshot().pendingSetup == null`; today it is non-null
while `confirmSetupCapture()` leaves `state.payees` empty.

### QP-10 — The QR advisory's urgency scan reads the raw payload, so `tn`/`pn` text drives the verdict
Severity: P2
Where: `.../ui/qr/QrGuardHost.kt:687-692` (`sourceText = payload`),
`.../core/payment/PaymentAdvisoryEngine.kt:389-420` (`hasUrgencyCue` over `sourceText`),
`.../core/payment/PaymentAdvisoryContract.kt:155-156`
Scenario: `paymentAdvisoryForQr` passes the entire raw URI as `sourceText`, whose only consumer is the
`URGENCY_PRESSURE` scan. So `upi://pay?pa=shop@okaxis&am=2000&tn=jaldi+karo` — or `pn=Abhi Store` with
the `+`/space boundary satisfied, since the scan is a literal substring test with non-alphanumeric
boundaries — emits a WARNING from text the attacker (or the merchant's own note field) controls, and a
single WARNING forces `CHECK` even when the payee matches a registered payee and nothing else fires.
Conversely a perfectly innocent note (`tn=Aaj hi khola`, `tn=Diwali offer abhi`) produces a false
"pause" on a legitimate stand. `PAYMENT_ADVISORY.md` defines the cue as coming from "the accompanying
text" — for a QR there is no accompanying text, so feeding the payload in is an undocumented widening
of the rule; and the note is QR data, i.e. an injection surface into the verdict.
Fix: pass `sourceText = ""` on the QR path (the payload's fields are already parsed), or pass the
decoded `qr.note` only and gate it behind an explicit `noteIsAttackerControlled` flag; document the
choice in `PAYMENT_ADVISORY.md`'s wiring diagram.
Failing test: `QrGuardSessionTest` new method `qr note does not raise an urgency hint` — register the
demo merchant payee, decode `upi://pay?pa=dukaan.demo@okaxis&tn=jaldi+abhi`, then assert
`snapshot().paymentAdvisory!!.hints.none { it.kind == PaymentHintKind.URGENCY_PRESSURE }` and
`verdict == PaymentAdvisoryVerdict.CLEAR`; today the hint is present and the verdict is `CHECK`.

## Reviewed and found sound

- **No link/URL/Intent handling.** `grep` over `input/qr`, `core/payment`, `ui/qr` finds one
  `startActivity`, at `QrGuardHost.kt:1649`, targeting the app's own settings page with a
  `package:` URI; `UpiQrParser.classify` sends `http(s)://`, `javascript:`, `data:`, `tel:`, `mailto:`,
  `sms:`, `geo:`, `wifi:` and vCard/WIFI blocks to `URL`/`OTHER` kinds that are never resolved.
- **No raw-payload persistence or logging.** No `Log.`, `printStackTrace` or `System.out` in the slice;
  `UpiPayload.toString`, `PaymentFields.toString`, `PaymentHint.toString`, `QrUiState.toString`,
  `QrGuardView.toString`, `RegisteredPayee.toString`, `ObservedQr.toString` and `SetupCapture.toString`
  are all value-free or masked; the analyzer drops the exception message (`errorToken` takes the class
  name only); `SaltedPayeeRegistry` stores HMAC hex only, and `RegisteredPayeeStore` stores ids + masked
  forms only.
- **Amount handling.** `UpiQrParser.amountIsAcceptable` is ASCII-decimal-only (documented tightening vs
  `Decimal`), rejects negative/`NaN`/`Inf`/non-ASCII digits and anything above `1e8`;
  `PaymentFieldsParser.paise` bounds the whole part to 15 digits (no overflow), rejects >2 fraction
  digits, maps `₹0` to no amount, and `guarded()` keeps one bad field from killing the parse.
  `paymentAdvisoryForQr`'s `BigDecimal(...).movePointRight(2).setScale(0, UNNECESSARY)` is inside
  `runCatching`, so `am=1e-9`/`am=100.555`/`am=` yield `amountMinor = null` instead of a crash.
- **Watcher state machine.** `observeUnreadable()` cannot reach `Tampered`; a `null` payee never
  increments the counter or refreshes `lastValidDecodeAt`; `Tampered` is sticky until
  `acknowledgeTamper`; `mismatchLimit = 3` / `occlusionMs = 300_000` come from `QrPolicy` defaults, not
  from a weaker local constant; the `QrCameraAnalyzer` 1 500 ms same-payload debounce means a static
  mismatched QR takes ~4.5 s (not three frames at 30 fps) to alert.
- **Advisory honesty.** `confirmationRequired` is a constant `true`; `PaymentAdvisoryCard` draws
  `PaymentAdvisoryCopy.CONFIRMATION` as the last child and last clause of the merged content
  description; `maskedForPresentation()` clears `amountRaw`/`payeeName`/`utr`/`statusRaw`/`timestampRaw`
  and rewrites `payeeUpi` to `sho***@okaxis` before the advisory reaches `QrGuardView`, so no raw VPA,
  name or reference survives into UI state.

## Unverified suspicions

1. **`RegisteredPayeeStore` has no production caller.** `grep -rn RegisteredPayeeStore main/` finds only
   the class, its adapter and a KDoc reference in `PaymentAdvisoryContract.kt` — no wiring into
   `QrGuardSession` or the Result route. Registered payees therefore live and die with the process
   (`QrGuardSession.registry = SaltedPayeeRegistry(newSessionSalt())`, a fresh `SecureRandom` salt per
   session). That is disclosed as open question 2, but it also means a future wiring cannot work as-is:
   ids persisted with session salt A can never match an HMAC computed with session salt B. I did not
   classify this as a finding because the doc already records the gap and I cannot see the intended
   Phase 2 keystore design.
2. **Three incompatible mask widths.** `PayeeMasking.mask` and `UpiQrParser.maskVpa` keep 2 local chars,
   `maskPayeeId` keeps `length/2` capped at 3, `maskedUpi` keeps 3. Same id can render as `ra***@okaxis`
   in one panel and `ram***@okaxis` in another. Cosmetic; `QrGuardHost.captureSetup` comments claim "one
   screen must not use two masking styles", which the parser's 2-char mask contradicts. Not verified as
   user-visible because I did not trace every render site.
3. **`QrReducer.onValidFrame` / `onNoDecode` are a second decision engine.** They implement the same
   three-mismatch and occlusion rules as `QrWatcher`, but no production caller sends
   `QrEvent.ValidFrame`/`UnreadableFrame`/`Tick` (only `QrGuardScreenTest` does). They already diverge
   (`onValidFrame` freezes while an alert is up and resets `occlusion = null` on every valid frame,
   whereas `QrWatcher` keeps counting and only re-evaluates on the tick). Dead code today, a
   future-drift risk I could not turn into a defect.
4. **`am` with a sign or exponent.** `DECIMAL_PATTERN` accepts `+` and `[eE]`, so `am=+5` is accepted
   (`PREFILLED_AMOUNT`) and rendered as `₹+5` in `amountRaw` (which `maskedForPresentation` then clears,
   so it is never displayed). `am=1e5` also passes and becomes 10 000 000 paise. Whether the backend
   intends to accept exponent notation is not documented; I did not raise it because no UI surface
   renders the raw string.
5. **`evaluateOcclusion` never runs outside `Watching`.** `QrWatcher.evaluateOcclusion` returns unless
   `state == Watching`, so a machine parked in `ReRegisterPending` (long press refused, PIN refused)
   cannot reach `Occluded` at all. `QrGuardSession` masks this by rebuilding the watcher on
   `SubmitPin`/`CloseReRegister`, so I could not construct a live path where the operator is stuck
   without the occlusion warning.
