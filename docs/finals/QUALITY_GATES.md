# Quality gates

## Global gates

- Branch `finals-48h`; clean review of only owned paths; secrets absent; no production change; no modification to pre-existing untracked paths.
- Every package claim is reproduced locally. Relevant checks pass; failures and skips are reported exactly.
- No spent data access; no v3 artifact/default/threshold change; no live link browsing.

## Workstream gates

| Stream | Pass criteria |
|---|---|
| W1 | Written Playwright matrix (signup, sign-in/out, password error, confirmation, expiry/refresh, first-visit Skip, identity-bound history, duplicate clicks, slow network, mobile) passes 3 consecutive runs; pytest green; no browser console errors; screenshots under `docs/finals/evidence/w1/`. Real external email flow gaps are explicit. |
| W2 | `UI_SPEC.md` contains measured grid, sidebar, composer, message states/actions, streaming, code, scroll, mobile, loading/error, and accessibility details with source/method. Side-by-side captures at 3 viewports; smooth streaming without layout jump; Lighthouse accessibility >=95; existing scan/chat behavior unchanged; typecheck, lint, build green. |
| W3 | Synthetic clean, malicious-looking, corrupt, oversized, password-protected, macro-enabled, and MIME-mismatch fixtures; unit and browser tests; mobile upload; safe bounded parsing; no fixture crashes; per-file states/errors. |
| W4 | Before/after validation-only results with limitations; leave-one-source-out and calibration checks for any candidate; >=60 fixed real-link regression cases across official and phishing/lookalike/shortener/tunnel categories; zero new official-domain false positives; regression tests for named misses; v3 and threshold unchanged; any candidate holdout newly sourced, hashed, sealed, unopened. |
| W5 | MV3 loads unpacked in persistent Playwright Chromium; tests prove official never-block domains, fixture Warn/Block, persistent unblock, offline rules, least permissions, reversible markers and per-site pause; README/privacy/build script present. |
| W6 | `gradlew assembleDebug` and unit tests pass; minSdk 26; required flows represented; instrumented smoke test if device/emulator exists, otherwise exact gap recorded. |
| W7 | 256-prompt injection and 35-prompt routing batteries rerun on branch with no regressions; per-IP limits retained; upload/extension surfaces reviewed; local 5-minute demo rehearsed; recording script/offline fallback/deck notes updated without unmeasured claims; final report has reproduction commands. |

## Evidence layout

Store screenshots, logs, command transcripts, fixture hashes, Playwright traces, and Android output in `docs/finals/evidence/<workstream>/`. Reports must identify evidence filenames and whether real services/devices or mocks were used.
