# Finals run state

- Recovery resumed: 2026-10-23 19:48 IST / 2026-10-23T14:18Z; scheduled T0 remains 19:55 IST, hard finish 01:55 IST 2026-10-24.
- Branch: `finals-48h`; HEAD `eb7a66a` (`docs: record head orders: power-off recovery and 6-hour compressed schedule`), cleanly based on `origin/finals-48h` at `56b3d3c`.
- Latest state files were stale at 01:52Z. Both prior Hermes work sessions are DEAD after the outage: Lane B terminal 86379 / Hermes PID 32720 / worker 31084; Lane A terminal 91730 / Hermes PID 33368 / worker 28832. No TrustShield API or web process is listening; ports 8000 and 3000 are free. Do not kill unrelated processes.
- Recovery inventory: 32 changed/untracked paths from prior W2/W3 work plus W1 evidence churn; review before staging. W1 screenshot and `w1-run.json` changes are evidence regeneration churn and must not be committed. Existing W2 source edits and W3 parser/tests/fixtures are candidates pending full verification and review.
- Resources: 20.1 GB free RAM; Nilabh gate PID 8076 listens on 127.0.0.1:20190. No fallback model. Keep this gate active.
- Current phase: recovery window through 20:25 IST. Run mandatory full pytest, frontend typecheck/lint/build, review diffs, restart API 8000 and web 3000, then commit/push verified recovery milestone and relaunch both work lanes.

## Compressed schedule (HEAD_ORDERS.md section 5)

- Lane A: W2 UI until 22:15; W5 Chrome extension 22:15-00:45.
- Lane B: W3 uploads (including issues #14-#16) until 21:30; W4 accuracy and look-alike regression through 23:15; W6 Android 23:15-01:00.
- 01:00-01:55: W7 security/regression batteries, demo flow, deck/notes numbers, FINAL-REPORT.md, push, update issue #8.
- Missed gates freeze at best verified state with an issue describing exact remaining gap. Never cut full-suite gate, spent-data protections, honest metrics, or production boundary. No PR or merge.

## Previously verified baseline

- W1: isolated Playwright auth matrix 3 consecutive runs, 102 passed each; real Supabase email delivery and live token refresh remain unverified (see `docs/finals/NEEDS_SHUBH.md`).
- Prior full pytest result before outage: 959 passed, 7 skipped, 5 warnings in 168.41s. This run must repeat during recovery and before any fix/acceptance commit.
- W2 discovery spec and layout-only captures: reviewed and pushed at 9a831b3. Implementation remains unaccepted pending independent browser, accessibility, behavior, and build gates.
- W3 upload extractors: prior worker report is not acceptance. Review issues #14-#16 and rerun full suite before accepting.

## Open items

- `docs/finals/ORCHESTRATOR-LOG.md` did not exist at recovery; create it and acknowledge HEAD_ORDERS.
- No final recovery results recorded yet. Update this file after each recovery check and at least every 30 minutes. Refresh `HEARTBEAT.txt` at least every 15 minutes.

## Recovery verification update (2026-10-23 19:55 IST)

- First full suite: 1034 passed, 7 skipped, 4 failed, 5 warnings in 267.98s. Failures exposed: XLSX corrupt-part ZIP errors escaped; test fixtures incorrectly assumed ZIP readers honor local-header CRC; DOCX zipbomb test used the exception binding incorrectly.
- Corrected XLSX to require `defusedxml`, map corrupt archive/XML errors to `DocumentRejected`, and use the shared hard upload cap. DOCX now maps defused XML exceptions to `DocumentRejected`. Corrected corrupt-archive tests to corrupt entry payload bytes and fixed the zipbomb assertion.
- Focused W3 runtime and hostile-input checks: 47 passed in 0.36s.
- Mandatory full pytest rerun is active (started 19:55 IST). Do not commit until it passes.
- Web gates: `npm run typecheck` passed; `npm run lint` failed on two `react-hooks/set-state-in-effect` findings in existing Chat.tsx effects (lines 235 and 259); `npm run build` passed. Lint remains a W2 acceptance gap pending lane review.

## Recovery gate result (2026-10-23 20:10 IST)

- Mandatory full pytest after W3 fixes and W2 cleanup: 1042 passed, 7 skipped, 5 warnings in 191.83s.
- W3 focused suite: 47 passed in 0.36s. XLSX corrupted ZIP and XML inputs are normalized; DOCX defused XML exceptions are normalized; XLSX uses the shared hard upload cap and no unsafe XML fallback remains.
- Local service restart: API PID 32660 on 127.0.0.1:8000; web PID 28920 on 127.0.0.1:3000. `GET /api/v1/health` and `GET http://localhost:3000` returned 200. Playwright at 390x844, 834x1112, and 1440x900: status 200, no overflow, zero browser errors when loaded through `http://localhost:3000`. Captures are ignored under `work/finals/recovery/w2/`.
- Latest web gates after cleanup: `npm run typecheck` passed; `npm run build` passed; `npm run lint` fails on two existing `react-hooks/set-state-in-effect` findings in Chat.tsx at lines 235 and 259. Keep this as a documented W2 gap if unresolved.
- Recovery changes remain uncommitted pending milestone review.

## Recovery verification (latest, 2026-10-23 20:11 IST)

- Full suite after consolidating the shared cap helper across TXT/CSV, PDF, DOCX, and XLSX: 1041 passed, 7 skipped, 5 warnings in 189.20s. W3-focused suite: 77 passed in 0.90s.
- Latest web checks after W2 semantic cleanup: `npm run typecheck` exit 0; `npm run build` exit 0; `npm run lint` exit 1 with two existing `react-hooks/set-state-in-effect` findings at `Chat.tsx` lines 235 and 259. Filed issue #18.
- Local API/web HTTP and three-viewport browser checks passed as above. API PID 32660 and web PID 28920 remain active.
- Recovery source review: W2 shell/header and empty state changes are integrated; unintegrated draft components and unused UI test scaffolds are excluded from the recovery commit for Lane A review. W3 extractor foundation is verified; API/UI upload integration remains open for Lane B.
- Pending: stage only reviewed W2/W3 paths, excluding W1 screenshot/manifest churn and unrelated workspace files; commit and push `finals-48h` before lane relaunch.
