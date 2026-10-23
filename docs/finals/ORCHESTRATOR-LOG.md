# Orchestrator log

## 2026-10-23 recovery

- 19:48 IST: Read `docs/finals/HEAD_ORDERS.md` sections 4-5, then `MISSION.md`, `STATE.md` (repo root), and `NEEDS_SHUBH.md`.
- Acknowledge section 4: limit removed; Nilabh gate PID 8076 at 120 requests/minute remains mandatory; use no fallback; prior 30 RPM concurrency guidance is superseded. Full pytest remains required before any fix or acceptance commit, per the resolved collection defect.
- Acknowledge section 5: outage killed the prior TrustShield workers and servers. Hard finish is 01:55 IST 2026-10-24. Compressed lanes are Lane A W2 then W5; Lane B W3 then W4 then W6; W7/final close 01:00-01:55. Keep 8 GB free RAM minimum; no artificial GPU load; no production changes, spent-data access, PR, or merge.
- Recovery inventory: branch `finals-48h`; HEAD `eb7a66a`; 32 candidate changed/untracked paths. W1 regenerated screenshots and run manifest are churn and are excluded from commits. Previous lane sessions/PIDs recorded dead; API/web ports 8000/3000 are free; gate 20190 is listening.
- Recovery validation started: pending full pytest, frontend tsc/lint/build, source review, server restart, then verified commit/push by 20:25 IST.

- 19:55 IST: First full suite exposed four W3 corrupt-archive regression failures (1034 passed, 7 skipped); review found two test-fixture defects and two actionable XLSX boundary defects. Fixed parser normalization, removed the XML fallback, shared the hard cap, and corrected synthetic CRC tests/zipbomb assertion. Focused tests pass 47/47. Full suite rerun active.
- Recovery web gates: TypeScript typecheck and Next build pass; lint fails on two existing effect-setState rules in Chat.tsx. Captured and left for W2 owner review.

- 20:10 IST: Mandatory full pytest after recovery parser fixes and W2 semantic cleanup passed: 1042 passed, 7 skipped, 5 warnings in 191.83s. API PID 32660 and web PID 28920 are listening; `/api/v1/health` and `localhost:3000` returned 200. Playwright at 390/834/1440 had zero console errors and zero horizontal overflow. Screenshots remain in ignored `work/finals/recovery/w2/`.
- Web gates after cleanup: typecheck and build pass; lint reports two pre-existing `react-hooks/set-state-in-effect` errors in `Chat.tsx` at lines 235 and 259.

- 20:11 IST: Consolidated all per-format size checks onto `document_types.max_document_bytes()` and removed unused helper aliases. Focused W3 suite passed 77/77; mandatory full suite passed 1041 passed, 7 skipped, 5 warnings in 189.20s. Opened W2 lint issue #18 for two pre-existing effect-rule errors; typecheck/build pass.

- 20:12 IST: Recovery milestone committed and pushed as `a2f6e0f` after the 1041/7 full-suite gate. W1 regenerated evidence restored to baseline and excluded. API PID 32660, web PID 28920, and gate PID 8076 are live. Tracker and defect issue updates pending before lane launch.

- 20:14 IST: Relaunched Lane A W2 (Hermes PID 32600, command-session 16572) and Lane B W3 (command-session 97179) after preflight checks (gate 8076 active; 22.8/21.9 GB free RAM, CPU 37/70%). No output yet is normal; apply the 15-minute rule.
