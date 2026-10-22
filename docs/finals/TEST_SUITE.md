# Test suite and execution order

## Baseline (after Phase 0 commit)

1. Confirm `git branch --show-current` returns `finals-48h` and inspect `git status`.
2. Before any test/script that may touch data/model paths, inspect its code and guards and confirm allowed input paths. Never run with `--split test` or archival override.
3. Run `.venv\Scripts\python -m pytest -q`. Mission's starting snapshot says 802 tests passed on 11 October; record the actual current count, failures and skips. Do not treat the old count as current.

## Workstream checks

- W1: focused auth/history API tests, then full pytest; Playwright matrix three consecutive times with fresh context/state; collect browser console errors and screenshots.
- W2: REA/Playwright measurements, three viewport captures, streaming layout observation, Lighthouse accessibility, `npm run typecheck`, `npm run lint`, `npm run build` from `apps/web`.
- W3: parser/extractor unit tests against synthetic fixtures; API/browser/mobile upload paths; check limits and no external fetch, macro execution, path traversal or unbounded expansion.
- W4: static guard inspection before any data tool; validation-only evaluation, leave-one-source-out and calibration checks for any candidate; fixed 60+ link regression fixture; official-domain false-positive diff. No spent split or holdout invocation.
- W5: extension unit checks and persistent-context browser cases; inspect requested permissions and network egress.
- W6: `android\gradlew.bat assembleDebug`, `android\gradlew.bat test`; device smoke if present.
- W7: guarded injection/routing harness review before invocation; run exactly 256 and 35 prompt cases; demo locally; verify scripts are offline and claims match produced output.

## Recording

Each report provides exact working directory, command, exit status, duration, counts, and evidence path. Failed/blocked/skipped checks remain visible. The final report states commands to reproduce every accepted claim.
