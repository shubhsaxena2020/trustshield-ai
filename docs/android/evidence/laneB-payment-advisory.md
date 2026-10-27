# Lane B — payment advisory (A09) record

- Date: 2026-10-27, ~20:50 IST
- Lane: B (payment advisory, HEAD_ORDERS Phase 4 item 8), branch `android/P6-laneB-features`, base `origin/android-finals` 8c3d24b
- Worktree: `C:\Users\shubh\trustshield-android-p4\agent2\wt-B-p6`

**Status: written artifacts only. This is not evidence of behaviour.** No Gradle task was run, no test was executed, no emulator was started, no phone was touched. Everything below is a static reading of files on disk. The Pass/Not-run table in `docs/android/PAYMENT_ADVISORY.md` is still all Not run.

## What was added

| File | Purpose |
|---|---|
| `docs/android/PAYMENT_ADVISORY.md` | Spec of intent: purpose, non-claim + mandatory line, one row per hint kind, thresholds as policy data, privacy rules, wiring, verification status, limitations, open questions |
| `docs/android/evidence/laneB-payment-advisory.md` | This record |
| `core/payment/PaymentAdvisoryContract.kt` | Pinned types: `PaymentFields`, `PaymentHint`, `PaymentHintKind`, `PaymentAdvisoryVerdict`, `PaymentAdvisoryPolicy`, `maskedUpi` |
| `core/payment/PaymentAdvisoryEngine.kt` | Deterministic rules: fields + registered-payee facts in, ordered hints + verdict out |
| `core/payment/PaymentFieldsParser.kt` | Pasted text or OCR text out to `PaymentFields` (amount, UPI id, UTR, status, timestamp) |
| `ui/qr/QrPayeePaymentBridge.kt` | Supplies `registeredPayeeCount` and `payeeMatchesRegistered` from QR Guard's payee list; never compares names |
| `ui/result/PaymentAdvisoryCard.kt` | Result-screen card: hints in engine order, then the mandatory confirmation line last |
| `ui/result/PaymentAdvisoryCopy.kt` | Hinglish + English wording for the card, verdicts and hints |
| `ui/result/ResultUiState.kt` (one field) | `paymentAdvisory: PaymentAdvisory? = null`, last parameter, defaults to null |

The Kotlin files were written by the lane's parallel sessions in this same worktree; this record only documents what is there. I wrote the two markdown files above and nothing else.

## Tests present on disk (not executed)

Counts are a static count of `@Test` annotations, not a test report.

| Test file | Tests |
|---|---|
| `app/src/test/java/ai/trustshield/android/core/payment/PaymentAdvisoryEngineTest.kt` | 52 |
| `app/src/test/java/ai/trustshield/android/core/payment/PaymentFieldsParserTest.kt` | 42 |
| `app/src/test/java/ai/trustshield/android/core/payment/PaymentFixturesTest.kt` | 24 |
| `app/src/test/java/ai/trustshield/android/ui/result/PaymentAdvisoryCopyTest.kt` | 34 |
| `app/src/test/java/ai/trustshield/android/ui/qr/QrPayeePaymentBridgeTest.kt` | 18 |
| `app/src/test/java/ai/trustshield/android/ui/result/PaymentAdvisoryWiringTest.kt` | 8 |
| **Total** | **178** |

`core/payment/PaymentFixtures.kt` is a fixture helper and holds no tests. No instrumentation test exists for the card: the emulator path is unverified.

## Not verified, and why

- **Compile: Not run.** The lane lead owns Gradle; one build at a time is the standing rule, and this session was told not to run Gradle. Nothing here is known to compile.
- **JVM tests: Not run.** Same gate. The 178 tests above are unexercised, so a green count is unknown, not implied.
- **Emulator UI path: Not run.** One emulator machine-wide under a lock; the shared instance was in use. No screenshot of the card exists.
- **Real device: not attempted.** Owner-only. Serial `63b0de52` was never targeted and no `adb` command was issued by this session.
- **Wiring gap found by reading, not by running:** `ResultUiState.paymentAdvisory` exists and `PaymentAdvisoryCard` renders it, but the presenter in `ResultUiState.kt` never sets the field, so a real scan cannot currently produce a card. Someone must connect parser + bridge + engine at the presenter before the UI path can be checked at all.

## Privacy checks (static reading only)

- Masked UPI everywhere: `maskedUpi`, `RegisteredPayee.maskedHandle`, `PaymentHint.maskedPayee`; `toString` overrides are value-free.
- Payees compared by id, never by printed name; masked ids are treated as not comparable.
- No storage of source text or image, no network call, no intent launch in this feature.
- Residual risk: the bridge compares against QR Guard's in-memory session payee list, while the contract names a persisted `RegisteredPayeeStore` (salted HMAC) that does not exist. Until it does, the "salted identifier at rest" claim in `ARCHITECTURE.md` is only honoured for the QR screen's in-memory registry.

## No phone work happened

No `adb`, no install, no monkey run, no screenshot, no logcat capture for this feature. Nothing on this page came from a running app.

## Required next step for the lane lead

1. Run the Gradle gate (`:app:testDebugUnitTest`, then `:app:assembleDebug`) and paste the real result into the verification table in `docs/android/PAYMENT_ADVISORY.md`.
2. Finish the presenter wiring, or record that the card is unreachable this phase.
3. Run one emulator spot check (Result screen with a payment fixture: card visible, hints ordered, confirmation line last, TalkBack reads it) and update the emulator row.
4. Only then change any status from Not run to Pass. If a row fails, keep it failed and say so.

## Independent review — prior-wave diff and evidence

Review scope: the preceding working-tree diff for the payment engine, parser, payee store, Result card/copy, and associated unit tests, plus this evidence record. Static review only; no tests or build were run.

### Review checklist

- [ ] Confirm current changes compile and the complete relevant JVM suite passes (lane lead owns Gradle; not run in this review).
- [ ] Wire parser + registered-payee lookup + bridge + engine into the actual Result presenter; verify a real payment result reaches the card.
- [ ] Exercise adversarial parser inputs and UPI comparison boundaries after the stricter VPA validation changes.
- [ ] Verify registered payee persistence, salt lifetime/ownership, and comparison behavior against the intended salted-HMAC contract.
- [ ] On the shared emulator, verify card visibility, hint order, mandatory confirmation last, and TalkBack reading order.
- [ ] Keep all verification rows Not run until actual execution evidence is recorded.

### Outstanding risks and observations

- **Result wiring remains unverified and appears incomplete:** the earlier static review found `ResultUiState.paymentAdvisory` was not set by the presenter. The reviewed diff does not include presenter changes, so this gap remains outstanding; a renderable card is not evidence of an end-to-end path.
- **Threshold behavior changed:** the diff makes `PAYEE_NAME_SPOOFABLE` a CAUTION and sets the verdict threshold to two cautions. Reconcile the resulting verdicts (including name-only and combinations of cautions) with the product policy and adversarial fixture expectations; test source additions are not execution evidence.
- **Parser cleanup/validation needs another pass:** `normaliseUpi` contains a no-op `end`/`Int.MAX_VALUE` block. The new regex has an ASCII-token boundary guard, but malformed OCR/address cases and extraction context still need executed regression coverage; remove or justify the dead block.
- **RegisteredPayeeStore change is only a defensive salt copy:** this diff does not establish that persisted HMAC storage is wired into the QR/advisory bridge or that the key/salt lifecycle meets the architecture contract. Treat the earlier in-memory registry limitation as unresolved pending review and integration evidence.
- **Accessibility and localization remain static claims:** changed spoken-description construction and copy do not prove TalkBack order, clipping, or the full localized UI behavior. Emulator verification is still required.
- **No verification claim:** prior evidence reports no Gradle, JVM, emulator, or device execution. This independent review did not run any either; no tests are claimed as passed.
