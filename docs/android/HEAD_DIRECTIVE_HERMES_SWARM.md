# HEAD DIRECTIVE: Hermes must run one session = 9 parallel subagents

Issued 26 Oct 2026 22:30 IST by the head (Claude Code) after auditing the three Hermes state.db files.

## Finding
Across ~2,500 to ~4,000 messages per profile, each Hermes session made only 7-8 `delegate_task` calls in total, mostly 1-5 tasks wide (one 8). The user's mandate is 3 sessions x 9 parallel subagents. The lanes are working mostly as single agents.

## Rule from the next Hermes launch onward
1. Every Hermes lane prompt must instruct the lane to: split its work package into 9 independent, non-overlapping sub-tasks (different files or test areas), then call `delegate_task` ONCE with `tasks` = 9 entries (max_concurrent_children is 9). Wait for all 9, review each result, integrate, then run the next wave of 9. Repeat until the package is done.
2. Each sub-task prompt must be self-contained: repo path, worktree path, files it owns, files it must not touch, the full acceptance check, and "read docs/android/MISSION.md, ARCHITECTURE.md and BEHAVIOR_SPEC.md first; verify your own work before reporting".
3. Subagents share one worktree per lane: assign disjoint file sets so they never edit the same file. The lane lead (the Hermes session itself) is the only one that commits, after running the lane's gradle test task.
4. If fewer than 9 independent pieces exist, pad with review/test/fixture/accessibility/verification sub-tasks (e.g. adversarial test writers, fixture authors, code reviewers for the other 8 pieces). Never launch a wave narrower than 7 unless the package truly cannot be split; log why in ORCHESTRATOR-LOG.md.
5. Do NOT kill a lane mid-commit. Let the current lane finish or reach a commit boundary, then relaunch it with the swarm prompt. Orchestrator (Codex) decides the boundary.
6. Orchestrator verification: after each lane, check `state.db` for `delegate_task` calls and their `tasks` length; record "wave sizes" in ORCHESTRATOR-LOG.md. Target: 3 lanes x 9 = 27 concurrent subagents at peak, bounded by RAM (alarm 8 GB free; at most one emulator, owned by the orchestrator).
7. Gradle is the shared bottleneck: subagents must not run gradle concurrently. Only the lane lead runs gradle (`--no-daemon`, org.gradle.jvmargs 2g). Subagents write code/tests/docs and may run pure Kotlin/JVM unit tests only via the lane lead.

## Phone hold still applies
No work that needs the real USB phone before ~22:45 IST (see HEAD_DIRECTIVE_PHONE_HOLD.md).

---
## UPDATE 27 Oct 2026 00:50 IST - owner: 3 sessions x 18 parallel subagents (time is short)
The owner raised the width: every Hermes session now runs 18 parallel subagents per wave (3 lanes x 18 = 54 concurrent). `max_concurrent_children` is set to 18 in the default config and in profiles agent1/agent2/agent3 (backups `config.yaml.bak-pre18-*`); it takes effect on each lane's NEXT launch, so relaunch every lane at its next safe commit boundary. Rules change as follows: one `delegate_task` call with `tasks` of length 18 per wave (never narrower than 14 without a logged reason); pad with adversarial test writers, fixture authors, reviewers of the other subagents' diffs, and accessibility/localisation checkers; disjoint file ownership is mandatory (18 writers on one worktree is a merge hazard: give each subagent an exclusive file list, and the lane lead alone runs Gradle and commits). Watch 9Router rate limits and free RAM (alarm below 8 GB): if 9Router returns 429/503, drop that lane to 12 for the wave and log it. Verification: Codex checks `delegate_task` tasks length in each profile state.db and logs wave sizes.
