# Lane B wave 2 review — first-wave diffs

Scope: static review of the current first-wave working-tree diffs against the lane brief and existing `docs/android/lane-b-review.md`. No source or test files changed; this report is the only file created. No Gradle/JVM tests or emulator/device runs were performed (lane lead owns those gates).

## Blockers

### P1 — Registry bridge is still caller-supplied, not production persistence-to-Result integration

`ResultPresenter.build` now uses `QrPayeePaymentBridge.toAdvisoryInput` only when `ResultInput.registeredPayees` is non-empty (`apps/android/app/src/main/java/ai/trustshield/android/ui/result/ResultUiState.kt:886-907`). The presenter still does not load `RegisteredPayeeStore`; this change therefore does not establish that production Result callers pass current locally registered payees. Empty registered-payee lists also fall through to legacy precomputed `registeredPayeeCount`/`payeeMatchesRegistered`, so a caller can still assert a match without the bridge performing comparison. Exact blocker: demonstrate production source of the registry snapshot and prevent stale/externally asserted legacy match data from overriding the empty-registry case. The documentation correctly continues to disclose no production store-to-Result wiring.

### P1 — No safe production salt lifecycle for persisted identity comparison

The first-wave changes add store tests, not a production key/salt provider. `RegisteredPayeeStore` remains caller-salted; tests validate only synthetic salt/MemoryStore behavior. Restart matching at the store abstraction does not prove that production uses a stable protected salt or that persisted HMAC entries survive real app lifecycle/key rotation. Until provider and lifecycle are supplied and integrated, do not claim secure durable registered-payee matching.

### P1 — First-wave tests are assertions in source, not verified behavior

The added parser, store, bridge, presenter, and UI-path assertions have not been run. The lane brief expressly reserves Gradle/JVM execution for the lead, and the docs indicate runtime gates remain pending. Consequently compile validity, test outcomes, actual TalkBack/card ordering, and end-to-end scan/Result behavior remain unverified; test-source additions do not close these acceptance gates.

## Interaction / unsafe assumptions

- The new `registeredPayees` branch gives a non-empty list precedence over legacy count/match fields, but an empty list means “use legacy data,” not necessarily “registry known empty.” That ambiguity can surface false mismatch/match claims when callers pass stale values. Prefer an explicit source/availability contract, or always derive count/match from the authoritative snapshot.
- `ResultUiState.kt` imports QR UI types (`QrPayeePaymentBridge`, `RegisteredPayee`) into Result presentation. This creates a direct feature-to-feature dependency and makes the supposedly pure presenter depend on QR Guard representation; review layering/ownership before extending this path. It is not a demonstrated runtime failure, but is an architectural coupling to address or explicitly accept.
- The parser change correctly prevents prefix acceptance before `.`/`,` continuations, but the new tests only describe intended outcomes; without execution, do not treat boundary behavior as established.
- Store privacy tests inspect synthetic persisted values and masking; they do not establish that production callers avoid logging raw source text/identifiers or that all display/error paths are safe.

## Changes reviewed

Current working-tree diffs include payment parser/engine/copy and Result presenter changes, test additions/updates for parser/store/QR bridge/copy/Result/UI, and edits to `docs/android/PAYMENT_ADVISORY.md`. Existing `docs/android/lane-b-review.md` remains the earlier review baseline. The only file created by this review is `docs/android/lane-b-wave2-review.md`.

## Verification limits

Static diff/source inspection only. No tests/build, emulator, physical device, or runtime privacy/accessibility verification. No code changes made.