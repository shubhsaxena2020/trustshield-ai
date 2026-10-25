# HEAD ORDERS - PHASE 2 (issued 2026-10-25 ~21:30 IST by Claude Code, head)

**Phase 1 is ACCEPTED** (plan merged at ccb52ad / c02f1ba). Re-read `HEAD_ORDERS_ANDROID.md` (still binding), `PLAN_SUMMARY.md`, `PLAN_2DAY.md`, `WORK_PACKAGES.md`, `NEEDS_SHUBH.md`. You (Codex) now run on `gpt-6-luna`.

## Amendments to the plan
1. Wall-clock matters: 30 Oct is the final. Target: **all P0 packages (A01-A08, B01-B08, C01-C08) merged and the APK building within ~8 hours wall-clock from now**, a first vertical slice (paste Hinglish text -> evidence + score; share intent; QR watcher; Call Shield screen) within ~5 hours. Elapsed-hour blocks in PLAN_2DAY are an upper bound, not a target.
2. Fix GitHub issue #54 first (small, on its own branch/PR): add `defusedxml==0.7.1` to `requirements-lock.txt` and resolve the dependency-audit findings only if the fix is trivial and does not change the model/runtime behaviour; run the Python test suite to prove nothing broke. If the audit findings are not trivial, leave them documented and move on.
3. A06/A07: until the privacy audit passes, the Android app must work **fully offline with local analysis**; backend enrichment stays disabled by default.
4. Keep the "no claim without measurement" rule: Call Shield claims are limited to what is measured on a device or emulator; label anything unmeasured.
5. Do not wait for the owner's phone to start; build and unit-test against JVM tests and an emulator if you can create one (try `sdkmanager` + AVD; document the result either way). Report what the owner must do for real-device acceptance.

## Execution protocol (how the three Hermes sessions are driven)
- Each Hermes session works in **its own git worktree** to avoid Gradle/file collisions: `C:\Users\shubh\trustshield-android-wt\agent1` (branch base `android-finals`), `...\agent2`, `...\agent3`. Create them with `git worktree add`. Sessions never share a working directory. Codex integrates in the main checkout.
- Launch each session in the background (separate run, no nohup, no `&`; use your shell's background facility), stdin closed:
  `hermes -p agent1 -z "<prompt>" --yolo < NUL > <log> 2>&1`, same for `agent2`, `agent3`. Never pass `--provider` or `-m`. Logs under `C:\Users\shubh\AppData\Local\Temp\android\` (use forward-slash `/tmp/android/` in Git Bash).
- Each Hermes prompt must: name the session's package list from WORK_PACKAGES.md (A = agent1, B = agent2, C = agent3), instruct it to use up to **9 parallel subagents** (`delegate_task`), one per package with the exclusive file paths listed, to read the full project context first (section 3 of HEAD_ORDERS_ANDROID.md), to restate and self-verify its plan before coding, to commit small and often on branch `android/<package>`, push, open a PR with `gh` (`Refs #<issue>`), run `./gradlew testDebugUnitTest` in its worktree, and report changed paths, test output and known gaps. Workers must not edit `*.gradle.kts` or `AndroidManifest.xml` (Codex owns those) - they list the needed changes in the PR body.
- Hermes is slow and buffers output. Do not judge it by silence: watch the worktree git log/status. If a session has produced no commit and no log growth for 25 minutes, investigate, then relaunch that session only.
- Codex reviews every PR (correctness, tests, honesty rules, privacy), merges one at a time into `android-finals`, resolves manifest/Gradle needs, keeps the APK buildable, and closes the matching issues. If a worker's output is weak, send it back with specific corrections or fix it yourself.
- Run the full relevant checks after each merge: `./gradlew testDebugUnitTest lintDebug assembleDebug` in `apps/android`.

## Stop conditions for this Codex run
Stop and write a status entry in `ORCHESTRATOR-LOG.md` when: (a) all P0 packages are merged and the APK builds with tests green ("PHASE 2 P0 COMPLETE" + commit + APK SHA-256), or (b) 5 hours of wall-clock have elapsed, whichever is first, or (c) a blocker needs the owner or Claude Code. Update `docs/android/STATE.md` each time. Do not start P1 (A09/B09/C09 conditional work) until P0 is complete and reviewed.
