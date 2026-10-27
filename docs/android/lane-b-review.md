# Lane B review — payment advisory

Scope: static review of commits `6f97974`, `4c75476`, and `52beede`, current advisory-related source, and required payment-advisory docs. No source/test files changed; no Gradle, JVM tests, emulator, or device run. Findings are prioritized by impact.

## Findings

### P1 — Result presenter comparison capability is implemented, but production caller supply remains a gap

`ResultPresenter.present` now calls `QrPayeePaymentBridge.toAdvisoryInput` when `ResultInput.registeredPayees` is nonempty and evaluates that input (`apps/android/app/src/main/java/ai/trustshield/android/ui/result/ResultUiState.kt:456-462, 883-905`). This is an implemented explicit-payee-list component path. However, no inspected production caller populates that field from QR Guard or the persisted registry. With its default empty list, the presenter falls back to legacy `registeredPayeeCount`/`payeeMatchesRegistered` values, defaulting to `0`/`false`. The QR integration test source exercises a constructed `QrGuardSession` payee snapshot through the bridge, but does not establish a production scan-to-Result caller or an executed test (`apps/android/app/src/androidTest/java/ai/trustshield/android/ui/result/PaymentAdvisoryUiPathTest.kt`; `apps/android/app/src/main/java/ai/trustshield/android/ui/qr/QrPayeePaymentBridge.kt`). Distinguish the implemented state capability from the unresolved caller wiring.

### P1 — Store's HMAC protection has no demonstrated production salt lifecycle or advisory integration

`RegisteredPayeeStore` requires caller-supplied salt and HMACs normalized IDs (`apps/android/app/src/main/java/ai/trustshield/android/data/preferences/RegisteredPayeeStore.kt:55-68, 175-178`); its SharedPreferences adapter persists values at `:288-312`. The reviewed changes add defensive store tests, but do not add a keystore-backed salt provider or wire the store into Result/QR advisory input. Without a stable protected key source, restart consistency and meaningful at-rest protection are not established. This limitation is explicitly documented in `PAYMENT_ADVISORY.md` and `ARCHITECTURE.md` (keystore/PIN remains a gate). Do not claim persisted local-payee matching as a shipped end-to-end capability.

### P1 — Confirmation, localization, accessibility, and runtime behavior remain unverified

The current checklist marks Result/QR paths, localization, privacy, and lead verification unchecked (`docs/android/evidence/payment-advisory-acceptance-checklist.md`). The advisory copy evidence identifies Hinglish/English but does not establish Hindi Devanagari coverage; no actual TalkBack/UI ordering or disclaimer-last observation is recorded. Source-level card ordering or tests present on disk do not prove rendered behavior. Lead-owned build/test/device gates are explicitly not run in `docs/android/PAYMENT_ADVISORY.md` and `docs/android/evidence/laneB-payment-advisory.md`.

### P2 — QR bridge deliberately excludes malformed/masked IDs, but this is source-only and incomplete integration evidence

Commit `4c75476` adds `isPlausibleUpiId` gating to `QrPayeePaymentBridge.comparableId` (`apps/android/app/src/main/java/ai/trustshield/android/ui/qr/QrPayeePaymentBridge.kt:105-119`) and QR integration tests. This avoids turning malformed/stale IDs into positive comparisons, but null/incomparable values and the complete QrGuard-to-advisory behavior have not been demonstrated at runtime. The acceptance checklist's QR path remains unchecked. Tests were not executed.

### P2 — Parser and policy edge behavior is not established by test additions

The commits add broad adversarial assertions to `RegisteredPayeeStoreTest` and integration/parser/engine test sources; they do not provide passing-test evidence. The parser's status-negation handling is a bounded English-only preceding-window heuristic (`PaymentFieldsParser.kt:494-499`), not general negation/intent interpretation. `PAYMENT_ADVISORY.md` describes urgency as literal whole-word cues within a limited text window and amount/status rules as demo heuristics. Boundaries, adversarial input outcomes, and policy correctness therefore require the lead's permitted test gate and product-policy review; no correctness claim follows from test presence.

## Commit notes

- `6f97974`: adds store validation, restart/masking, malformed-input and persistence-failure test cases. Review supports stronger asserted coverage, not execution or secure key provisioning.
- `4c75476`: adds QR payee integration and related tests, rejects implausible bridge IDs, and adjusts accessibility semantics on the QR remove-payee button. This does not itself connect the registry to Result's presenter or verify accessible UI at runtime.
- `52beede`: removes an empty recovery artifact; no functional source change.

## Verification limits

Static inspection only. No tests/build, emulator, device, runtime privacy/logging, or end-to-end Result/QR behavior was verified. Findings are limited to inspected diffs, source, and the cited advisory documentation.