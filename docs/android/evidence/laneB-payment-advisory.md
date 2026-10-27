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

## Follow-up static reconciliation (2026-10-27)

Re-read the brief and current implementation after the earlier review. This is a documentation-only
reconciliation; it does not add execution evidence.

- `ResultUiState.paymentAdvisory` remains nullable with a null default. `ResultPresenter` does not
  set it, and a search of production Kotlin found no caller wiring `PaymentFieldsParser`,
  `QrPayeePaymentBridge`, and `PaymentAdvisoryEngine` together. `ResultScreen` can render the card
  when given a non-null advisory; that is not an end-to-end path.
- The current QR bridge compares the screenshot UPI ID against the passed QR Guard payee list by
  exact normalized ID. The inspected bridge does not establish durable HMAC-backed persistence or
  keystore-protected salt integration. Do not describe that storage as integrated or persisted.
- Existing verification rows remain **Not run**: no Gradle/JVM execution, emulator check, or device
  check was performed by this lane. Test annotation counts below are static source counts only.
- No implementation changes were made in this follow-up. This evidence is limited to the static
  observations above and leaves runtime behavior pending.

## Required next step for the lane lead

1. Run the Gradle gate (`:app:testDebugUnitTest`, then `:app:assembleDebug`) and paste the real result into the verification table in `docs/android/PAYMENT_ADVISORY.md`.
2. Finish the presenter wiring, or record that the card is unreachable this phase.
3. Run one emulator spot check (Result screen with a payment fixture: card visible, hints ordered, confirmation line last, TalkBack reads it) and update the emulator row.
4. Only then change any status from Not run to Pass. If a row fails, keep it failed and say so.

## Acceptance checklist and known limitations (static source review)

This checklist records source inspection only. It is not a build, test, emulator, or device result.

### Acceptance checklist

- [x] Advisory contract, parser, deterministic engine, QR bridge, Result card/copy, and nullable Result state field are present in the worktree.
- [x] Static source shows the card places its mandatory bank/UPI-history confirmation disclaimer after the hints; the disclaimer is a non-settlement-claim.
- [x] QR bridge compares normalized payee IDs exactly, does not compare printed names, and treats masked IDs as not comparable.
- [x] `RegisteredPayeeStore` exists and stores HMAC identifiers plus masked display strings; it does not expose raw IDs through its read APIs.
- [ ] End-to-end Result acceptance: `ResultPresenter` does not set `paymentAdvisory`, and no production caller was found connecting parser, bridge, and engine. The feature is not established as reachable from a real scan.
- [ ] Policy acceptance: source sets `PAYEE_NAME_SPOOFABLE` to CAUTION and uses a two-caution CHECK threshold. Product-policy reconciliation is still needed; source/test presence is not a policy sign-off.
- [ ] Privacy architecture acceptance: the store requires a caller-supplied salt (minimum 16 bytes), copies it, and HMACs IDs, but the inspected code does not establish keystore-backed salt provisioning or prove that this store is wired into the advisory/QR path.
- [ ] Runtime acceptance: no compile, JVM test, emulator UI/TalkBack, or device check was run by this lane. All verification rows remain Not run; annotation counts above are not passed-test counts.

### Known limitations and follow-up

- Result presenter wiring is absent; a renderable card and optional state field do not make an end-to-end feature.
- Store implementation is present, but durable integration and production salt/key lifecycle are not established by this inspection. `ARCHITECTURE.md` requires salted/HMAC-protected identifiers and treats keystore/PIN design as a gate; do not claim that gate has been met.
- Parser and threshold behavior require executed regression coverage and policy review; this review does not infer correctness from tests on disk.
- Localization/accessibility claims remain unverified at runtime, including Hindi Devanagari coverage, TalkBack order, clipping, and the confirmation line's actual rendered position.
- No payment screenshot establishes settlement. The required confirmation text directs users to bank SMS or UPI app history; no success/fraud claim is supported.

Before any verification status is changed, the lane lead should wire or explicitly defer the presenter path, run the permitted Gradle gate and record actual results, then perform the authorized emulator spot check for visibility, ordering, disclaimer-last, and accessibility. No phone/device result is claimed here.

## Prior-wave diff review (2026-10-28)

Scope: reviewed the current worktree status and the available `apps/android` diff, then inspected current Result state/card references. This is static source/diff evidence only; no Gradle, JVM test, emulator, or device execution was performed.

- The tracked diff contains a five-line change in `apps/android/app/src/main/java/ai/trustshield/android/core/payment/PaymentFieldsParser.kt`: it attempts to skip status matches preceded by English `not`, `never`, or `no`. This is an unexecuted parser change, not evidence that negation handling works. The displayed Kotlin regex literal contains doubled backslashes in its `\\b` and `\\s` expressions; review flags this for author/compile/test confirmation rather than asserting runtime effect.
- The worktree also contains untracked `apps/android/app/src/test/java/ai/trustshield/android/ui/result/PaymentAdvisoryLocalizationTest.kt` and `docs/android/evidence/payment-advisory-acceptance-checklist.md`. They are present, but were not part of the tracked `git diff`; their test assertions/results were not validated or executed.
- Current source still declares `ResultUiState.paymentAdvisory` nullable with a `null` default. `ResultScreen` conditionally renders `PaymentAdvisoryCard` for a non-null value. The inspected state declaration alone does not show presenter population or prove a production end-to-end path; retain that wiring gap unless separately demonstrated.
- The card source documents that it draws the mandatory confirmation line last, while `PaymentAdvisoryCopy` has bilingual Hinglish/English copy. This is source intent, not verified rendered order, Hindi Devanagari support, or TalkBack behavior.
- No test/build result is claimed. Payment amount/payee/urgency policy correctness, parser regression behavior, privacy at runtime, localization/accessibility, and actual Result reachability remain unverified in this review.
