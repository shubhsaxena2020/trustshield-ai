# P8 review — shared context (read this first)

Worktree: `C:\Users\shubh\trustshield-android-p4\agent4\wt-review`
Branch: `android/P8-review`, base = `origin/android-finals` tip `d32fcda` (this INCLUDES PR #164 P6-U2-voice, PR #165 P7-B-payment-advisory, and 80f9a9f Phase3 journey stabilization).
Note: the older reports in `docs/android/review/_prev/` say "base 74db613" and are STALE — re-verify anything they claim, especially the claim that no payment advisory engine exists (`core/payment/` now exists).

## Where the app is
- Gradle root: `apps/android` (`settings.gradle.kts`, single module `:app`).
- Main sources: `apps/android/app/src/main/java/ai/trustshield/android/` (~75 .kt files)
  - `core/analysis`  `core/model`  `core/normalize`  `core/payment`  `core/privacy`  `core/rules`  `core/score`
  - `data/api`  `data/preferences`
  - `input/qr`  `input/share`  `input/speech`
  - `ui/call`  `ui/home`  `ui/navigation`  `ui/qr`  `ui/result`  `ui/settings`  `ui/theme`  `ui/voice`
- Tests: `apps/android/app/src/test` (77 .kt, JVM) and `apps/android/app/src/androidTest`.

## Docs to read before judging behaviour
`docs/android/MISSION.md`, `ARCHITECTURE.md`, `BEHAVIOR_SPEC.md`, `UI_SPEC.md`, `PAYMENT_ADVISORY.md`, `PRIVACY_AUDIT.md`, `I18N_AUDIT.md`, `TEST_SUITE.md`, `QUALITY_GATES.md`, `DETECTION_FIXTURES.md`, `FAILURE_AND_RECOVERY.md`.
Those specs are the source of truth for what is a *defect* vs a *design choice*. A deviation from BEHAVIOR_SPEC/UI_SPEC is a finding; a deviation from a nice-to-have is P2 at most.

## Product invariants (a violation is P0 or P1, never P2)
- Fully offline: no INTERNET permission, no network call in `main/`.
- Never opens links / URLs / intents from scanned or shared content.
- No raw scanned text and no raw audio persisted anywhere (no file, no prefs, no Log).
- Only masked identifiers are shown or stored (payee VPAs, UPI ids, phone numbers, account numbers).
- Deterministic rules engine: same input -> same verdict, no locale/time/random dependence in the decision path.
- Verdicts must be explainable with an exact quoted span of the input, and the quote must appear verbatim in the input.

## Hard limits for every reviewer
READ-ONLY on code. You may write ONLY your own `docs/android/review/<slice>.md`.
Do NOT run Gradle, adb, the emulator, or any git write command. Do NOT touch the main checkout `C:\Users\shubh\trustshield-ai`, other worktrees, `.env`, secrets, keystores, `models/`, datasets. Do NOT modify `Phase3JourneysTest`.

## Finding format (use exactly this, one block per finding)
```
### <ID> — <one-line title>
Severity: P0 | P1 | P2
Where: <path>:<line>[-<line>]
Scenario: <concrete inputs / exact user steps -> what actually happens, and why that is wrong>
Fix: <minimal surgical change>
Failing test: <test class + method name + assertion>
```
Then a separate section `## Unverified suspicions` for anything you could not confirm by reading the code path.

Verification bar: every finding must trace an actual code path you read (name the functions). No speculation in the verified list. Prefer 15 real findings over 40 guesses — a false P1 costs the team more than a missed P2.

## Merge-pressure note
Other implementers are concurrently changing detection rules (`android/P7-A-detection`), payment advisory wiring (`android/P7-B-payment-advisory`) and accessibility (`android/P7-C-accessibility`). Part of that is already merged into your base; keep suggested fixes small and surgical so they merge cleanly.
