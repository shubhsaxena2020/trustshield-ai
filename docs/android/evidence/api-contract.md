# Android API contract and offline fixtures

Issue #31 / package A08. The fixtures in `OfflineContractTest` are hand-written examples based on `services/analysis/fusion/result.py` and `services/analysis/evidence/models.py` schema version `1.0`. They are not captured backend responses, live calls, or accuracy measurements.

## Covered shapes

| Fixture | Purpose |
|---|---|
| F1 | High risk text response with multiple evidence records and recommended actions |
| F2 | Low risk legitimate bank credit notification |
| F3 | Unsupported Tamil input with limitations and Unable to Verify status |
| F4 | Image/OCR response containing module data that must not reach the adapted UI result |
| F5 | Suspicious KYC message response |
| F6 | Deliberately low backend reading of a Hinglish OTP request; records divergence, not a local verdict |
| F7 / F8 | Rate-limit and server-busy transport outcomes |
| F9 | Missing required risk field (schema drift) |
| F10 | Unknown evidence severity; invalid item is dropped and counted |

The test pins the Android contract version and adapter schema version. It checks input type, status, score, label, confidence, evidence mapping, actions and limitations. Backend evidence has no source quote, so the adapter does not invent one. Backend IDs are not reused as deterministic local evidence IDs. Module `data` is ignored, which prevents raw OCR text from entering the result. URL-bearing fields are not represented in the Android result model or turned into clickable links.

## Offline boundary

All network behavior in these tests uses an in-memory fake `HttpTransport`. No test resolves a host, opens a socket, follows a URL or contacts the backend. The shipped default keeps enrichment disabled; tests assert that a disabled API returns `BackendOutcome.Disabled` without invoking even a throwing transport. Local scoring is exercised with `LocalScorer` and `HinglishNormalizer` alone. Its result remains attributed to `DataOrigin.LOCAL` with `BackendStatus.DISABLED`.

Backend and local results can disagree. Such fixtures document adapter behavior only; they do not change local risk, do not establish model quality, and must not be presented as measured backend behavior. A low score is not a safety guarantee. Unreadable or unsupported input remains Unable to Verify.

## Run

From `apps/android/`, run `./gradlew :app:testDebugUnitTest`. These are JVM tests and need no emulator or network access. Keep raw message bodies, OCR strings, account values and URLs out of test logs and generated reports.
