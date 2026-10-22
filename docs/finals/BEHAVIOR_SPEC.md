# Behavior specification — verify, don't trust

## Orchestrator loop

For each package: define one goal and exclusive file ownership; write `PROMPT.txt`; check RAM/CPU and launch only within gates; monitor the exact process; inspect all changed files and the report; rerun claimed checks locally; inspect the real browser/device where required; return concrete defects (maximum four rounds); accept only evidenced results. Log unresolved items and move to the next best safe package.

## Evidence rules

- Separate observed facts, inferred explanations, and unknowns.
- Preserve test output, screenshots, hashes, exact commands, environment limits, and scope. Redact secrets and personal data.
- “Pass” means the specified check actually ran and succeeded. A skipped test, unavailable service, mock, or screenshot is labeled as such.
- Compare before/after behavior on named fixtures. Never describe validation results as holdout performance.
- Never open a user-supplied link or enable external browsing in the app.

## Change rules

- Workers edit only the files listed in their package prompt. Disjoint children may not share files.
- Codex reviews and commits; agents do not commit, push, deploy, or change settings.
- No speculative refactor, fake data, placeholder implementation, dead code, or copied reference-brand asset/copy.
- If a scope collision or safety boundary appears, stop that package, record it, and continue independent work.

## Recovery behavior

Follow `FAILURE_AND_RECOVERY.md`. An unavailable tool or dependency narrows the package to a truthful fallback; it never weakens a hard rule or acceptance gate.
