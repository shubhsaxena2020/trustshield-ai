# Workflow — two continuously used lanes

## Lane model

Lane A and Lane B are the two session slots. Each runs one package at a time. A package may use up to nine Hermes `delegate_task` children, all on disjoint paths. Never run more than two Hermes sessions concurrently or more than nine children in a session. Keep both slots assigned whenever a safe, dependency-ready package exists.

## Package lifecycle

1. Select the highest-priority ready package from `ROADMAP.md`; check file overlap with the other lane.
2. Create `work/finals/<pkg>/PROMPT.txt` with objective, owned paths, forbidden paths, exact acceptance checks, dependencies, and request for `REPORT.md`.
3. Before launch check free RAM (`Win32_OperatingSystem`) >= 12 GB, CPU not pinned, laptop cool, and launch rate. Write prompt before process launch. Use the exact default Hermes route; do not pass provider/model overrides.
4. Launch via the supported background execution facility. This environment exposes `exec_command` sessions, not the Bash `run_in_background` parameter named in `MISSION.md`; use a resumable command session where available, document any mismatch, and never use `nohup` or shell `&`.
5. Allow up to 40 minutes/package. Empty buffered output for 10 minutes is not a hang. If output is empty and PID CPU time has not advanced for 15 minutes, stop only that PID.
6. Review the diff and report. Independently execute checks, open browser screens, compare screenshots, inspect logs, and scrutinize generated rationale for templating/gaming.
7. Give actionable evidence-backed feedback for at most four rounds. If still failing, record why and choose the next-best option.
8. Integrate, rerun relevant checks, update evidence and state, commit only a green milestone, then dispatch the next package into that slot.

## Priority and dependencies

W1 first. W2 reference measurement can run while W1 code is isolated; W2 implementation waits until its measured spec and W1 ownership release. W3 begins after W1's shared composer path is stable, or works in isolated backend/parser paths. W4 may progress in rules/regression paths after W1–W3 green. W5 starts only after W1–W3 green. W6 starts only after W5 functions. W7 runs cross-cutting checks once feature work stabilizes and closes the run.

## Tracking cadence

Rewrite heartbeat every 15 minutes with UTC ISO time and active packages. Update `STATE.md` at least every 30 minutes and after every package/gate/commit. Append timestamped events to `ORCHESTRATOR-LOG.md`; never rewrite past entries.
