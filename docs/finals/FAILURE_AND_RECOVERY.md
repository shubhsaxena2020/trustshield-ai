# Failure and recovery

| Condition | Response |
|---|---|
| Nilabh chat route rate limit or 5xx | Retry after 30 s, 60 s, 120 s; reduce children in that lane; no alternate model/provider. Preserve failed output. |
| Hermes silent | Buffered output for 10+ min is not evidence of a hang. After 15 min of empty log and no CPU-time increase, stop only the PID launched by this run; log and relaunch once with smaller scope. |
| Package fails after four review rounds | Record observed reason and evidence; take next-best safe option; do not claim acceptance. |
| RAM < 12 GB, CPU pinned, laptop hot | Do not launch. Wait/recheck; keep non-Hermes independent planning/review moving. |
| Tool/session mismatch | Use only a supported resumable process facility if it preserves liveness and PID control. Mission's Bash background option is absent in this session; do not emulate it with shell `&` or `nohup`. Record effects and limit dispatch accordingly. |
| Power-off or context loss | Read `STATE.md`, `HEARTBEAT.txt`, then latest log/report and git status. Resume from committed milestone; do not re-run a completed package blindly. |
| Web/API port occupied | Identify owner PID and port. Never stop it unless this run started it. Pick an available local port only if app configuration supports it; record changed address. |
| REA unavailable | Use Playwright only against public reference pages; record date, viewport, computed measurements, browser/version, and limitations. No crawling or opening target links. |
| Browser/account secret missing | Use mock/injected auth only for deterministic unit tests; record that real email confirmation/refresh was not verified. Add a specific Shubh action to `NEEDS_SHUBH.md`. |
| SDK/emulator/Gradle unavailable | Preserve the existing environment, use project-local wrapper/dependencies only, run assemble/unit checks if possible, document exact unavailable device test. |
| Spent-data uncertainty | Stop before command execution. Search the target script for guard and allowed paths; if unresolved, record the exact script and reason in `NEEDS_SHUBH.md`. Never inspect data to check a guard. |
| Laptop crash or lost process | Record last verified package/evidence. Restart from disk state; no global process kill. |

## Recovery log minimum

Record UTC time, lane/package, PID if applicable, observable failure, commands attempted, recovery action, and any acceptance impact. Never include tokens or secret-bearing logs.
