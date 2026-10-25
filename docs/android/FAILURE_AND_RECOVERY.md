# Failure and recovery

Keep a green `android-finals` base and small PRs. Before a risky merge/reset/rebase, Codex records `git status`, creates a safety ref via a temporary `GIT_INDEX_FILE` plus `git write-tree`/`git commit-tree` at `backup/android-wip-*`, and verifies the ref. Never force-push or rewrite pushed history. Workers do not manipulate Git history. An ordinary failed build is fixed on its package branch; revert only that package PR if it breaks the base.

| Failure | Response | Resume gate |
|---|---|---|
| Hermes/9Router/Nilabh session fails or produces weak output | Preserve prompt/log/diff; stop that package's edits, assign independent ready work to free slots; Codex repairs or returns at most four concrete defects. Do not silently accept worker claims. | Same tests reproduced locally and diff reviewed. |
| Codex unavailable | Claude Code reads `STATE.md`, this log, open PR/issue status and latest green commit; no worker merge until reviewer returns. | Reviewer confirms ownership and exact green build. |
| Gradle/Maven download outage | Use wrapper/cache; keep locked versions; retry official repositories. Never mark build green from prior run. | Fresh `assembleDebug` success and APK hash. |
| No phone/emulator or unsupported Hindi ASR | Continue unit/build/camera logic; record capability gap; demonstrate staged speech only if labeled. Request owner's phone by deadline. | Physical install/ASR evidence or explicit unaccepted device gate. |
| API unavailable/429 | Local result remains, confidence reflects missing enrichment; no retry storm. | Fixture/API contract tests and explicit offline UI. |
| Privacy/quote verification failure | Stop affected feature/API use; remove claim from APK; do not log raw evidence while diagnosing. | Redaction and quote tests plus source audit. |
| QR/mic demo unstable | Rehearse bounded scripted input; preserve backup video/transcript as labeled demonstration, not live-device proof. | Real sensor run required for hero claim. |

Checkpoints: Phase 1 build-proof PR; Phase 1 docs PR; after each Day 1/Day 2 integration block; candidate APK before Day 3. Record hash, open gaps and rollback commit in `STATE.md`. Safety refs are only for recovery, never substituted for a pushed working branch.
