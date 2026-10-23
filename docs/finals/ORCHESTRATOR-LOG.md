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

- 20:17 IST: Closed W3 issues #14-#16 and W2 font-token issue #11 after pushed verification; commented on workstream issues #2/#3 and updated tracker #8. Kept #17 open because pre-outage writer attribution is unavailable; opened #18 for lint. Roadmap owner assignments now match the compressed lane schedule.

- 20:24 IST: Applied security-reviewer skill. Automated scanners (semgrep, bandit, gitleaks, trivy) are unavailable; no system installs. Manual review plus small in-memory timing confirmed near-quadratic PDF `_TJ_RE` suffix scanning on unmatched `[` bytes. Filed issue #19, commented on #3; W3 acceptance now also requires a bounded scan and synthetic regression. No large payload sent to API.

- 20:29 IST: After nearly 15 minutes, both lanes had fresh files despite empty buffered logs, so neither process was killed. W2 wrote a 390px screenshot. W3 interim report claims focused CSV/text 27 pass, XLSX hostile 20 pass, and API upload integration 14 pass/1 warning; no acceptance until independent diff/security review plus full pytest. PDF regex blocker #19 remains.

### 2026-10-23 20:33 IST — W3 API/security review
Reviewed W3 diff at the API, orchestrator, dispatcher, and pipeline seams. Both document routes use a max+1 bounded read; request body middleware rejects oversize before multipart parsing; normalized parser errors and metadata-only history label observed. PDF _TJ_RE remains unchanged and #19 is a hard acceptance blocker. Focused W3 results remain worker-reported pending independent full pytest. Hermes child processes remain active; next handoff 21:30 IST.

### 2026-10-23 20:38 IST — W3 independent lane report
W3 child 09 reports security review complete: no raw request logging/external fetch in document flow; macro/defusedxml checks and capped body/file reads present. It notes ZIP magic-byte fallback enumerates names before _safe_zip() and the pre-existing image cap honors MAX_UPLOAD_SIZE without a hard ceiling. Reported tests: focused document 128 passed; API/image/QR/chat/hardening 67 passed/5 warnings; full repo 1139 passed/7 skipped/6 warnings in 175.94s. These are worker-reported pending independent rerun. #19 remains unresolved; W3 acceptance blocked.

### 2026-10-23 20:39 IST — W3 route-contract report reconciliation
The route-contract child reports API/document contract gaps and dead imports. Its claim that no API tests exist is stale: tests/integration/test_document_upload_api.py (last write 20:28) covers PDF/DOCX clean, corrupt, oversized, and unsupported cases through both routes. Coverage does not yet include all five document formats at API level; chat generic octet-stream documents fall through image validation and return 422. Record these as remaining acceptance gaps, not as absent test files.

### 2026-10-23 20:44 IST — PDF parser fix and interim full suite
Coordinator replaced the repeated-suffix PDF text-array regex with a single-pass bracket/string-aware scan. Added tests/unit/test_document_pdf_regex_dos.py with a 24k unmatched-bracket adversarial case, normal array extraction, and brackets inside a literal string. Focused PDF tests: 23 passed in 0.84s; direct 12k adversarial timing: 0.0008s. Independent full pytest: 1142 passed, 7 skipped, 6 warnings in 194.94s. api/main.py changed at 20:40:41 during this run, so rerun after W3 lane freeze before acceptance.

### 2026-10-23 20:46 IST — W2 independent web gates
Ran npm run typecheck (exit 0), npm run lint (exit 1), and npm run build (exit 0) from apps/web. Lint reports only the two known react-hooks/set-state-in-effect errors in Chat.tsx at lines 279 and 303 (storage hydration and gate effects). Issue #18 remains open. UI interaction/keyboard report still pending, so W2 is not accepted.

### 2026-10-23 20:50 IST — W2 coordinator Playwright checks
Login link navigated to /login; browser Back restored the first-visit gate; Skip for now dismissed it. On 390x844 the drawer opened, took focus, Tab and Shift+Tab wrapped at both ends, Escape closed it, and focus returned to Open chats. Attachment menu opened; no file uploaded. Lane captures at 390x844, 834x1112, and 1440x900 report zero overflow. Playwright console reports 0 errors/0 warnings, React DevTools/HMR info only. One accidental click on a canned starter auto-submitted one synthetic message to local /api/v1/chat/stream; no user data was present, and upstream provider contact is unverified. Details in ignored work/finals/w2-resume/COORDINATOR-VERIFICATION.md. W2 still pending final worker report and lint disposition.
### 2026-10-23 20:53 IST — W2 Lighthouse
Ran Lighthouse accessibility against local http://localhost:3000 with Chrome headless. Accessibility score: 100. JSON report: ignored work/finals/recovery/w2/lighthouse.json. This closes the numeric accessibility gate. Screenshot comparison to the measured UI spec and streaming scroll/performance remain unverified; W2 lint still fails on issue #18.
### 2026-10-23 20:55 IST — W4 read-only preflight
Synthetic static probes: sbl.co.in/login yields only informational credential-path evidence; irctc-refund.xyz/login yields low suspicious-TLD plus informational credential-path evidence; sbii.co.in/login yields high lookalike evidence; 192.0.2.8/login yields high raw-IP plus low no-HTTPS. No model/training/holdout data used. Official-source checks: SBI migration notice names sbi.bank.in; IRCTC support uses irctc.co.in and equery.irctc.co.in. Search results list sbl.co.in in look-alike research and in historical SBI email addresses; avoid blanket blocking without validation. No candidate host opened. No W4 source/data changes yet.
# 2026-10-23 21:06 IST - Power outage continuation checkpoint

- Re-read HEAD_ORDERS.md sections 4-5 before MISSION.md, STATE.md, and NEEDS_SHUBH.md. Hard finish remains 01:55 IST. No questions asked.
- Reconciled process state: resumed Hermes PIDs are gone; API 32660, web 28920, and gate 8076 remain listening. Free RAM 18.5 GB; CPU about 50%.
- Confirmed W2's 21:05 addendum supersedes its earlier geometry and Lighthouse gap claims. W2 remains unaccepted for one lint error and incomplete streaming/behavior gates.
- Read W3 final report and reviewed the diff. Reported full suite: 1142 passed, 7 skipped, 6 warnings; coordinator independent `.venv\Scripts\python -m pytest -q` is running. W3 remains unaccepted pending front-end/mobile acceptance and wider route fixture coverage. The PDF regex issue remains open until independent full-suite evidence is in.
- W3 code review noted duplicate document-detection constants and an unused import, plus bounded-but-amplifiable archive/image reads to assess at the 21:30 freeze. No source edits made in this checkpoint.

# 2026-10-23 21:10 IST - Independent W3 full suite

- Ran `.venv\Scripts\python -m pytest -q` independently against the frozen W3 snapshot: 1142 passed, 7 skipped, 6 warnings in 198.18s. Warnings are Starlette/httpx deprecation, four registered-domain deprecations, and the intentional duplicate ZIP member in a hostile fixture.
- The new linear PDF array scanner and timing regression are included in the full pass; the specific #19 code defect is resolved. W3 remains unaccepted for missing browser/mobile upload flows and full fixture-matrix coverage.
- No source edits occurred during the suite. API, web, and the 20190 gate remain up. Free RAM 18.4 GB; CPU sample 67.5%, not pinned.


# 2026-10-23 21:12 IST - W3 freeze and W4 handoff

- W3 backend is frozen with independent full pytest 1142 passed, 7 skipped, 6 warnings in 198.18s. Closed issue #19 with the linear PDF scanner fix and regression evidence; commented on issue #3 that backend is verified but composer/browser/mobile and full fixture coverage remain missing.
- Resumed Lane A W2 (Hermes PID 11660) and started Lane B W4 (Hermes session 1758) after resource checks. W4 is restricted to local allowed TRAIN/VALIDATION material or clearly labeled synthetic cases; external browsing and all spent data are off limits.
- A separate pytest process appeared under a different shell while the coordinator suite was finishing; not started or stopped by this coordinator.

# 2026-10-23 21:20 IST - W2 independent gate review

- W2 review-only child found authFetch/session/history/scanner request paths unchanged; composer remains image-only, so W3 document uploads are not user-reachable. See `work/finals/w2-resume/review-scanner-auth.md`.
- W2 gate check reproduced typecheck/build pass and one lint failure at `Chat.tsx:301`; streaming timing, `/` shortcut, some accessibility items, and full existing chat/scan regression remain open. W2 stays NOT accepted. See `work/finals/w2-resume/gate-check.md`.
- W2 and W4 Hermes sessions remain active; free RAM was 11.7 GB. No additional session launched.

# 2026-10-23 21:22 IST - W3 backend milestone pushed

- Cached diff check was clean. With independent full suite at 1142 passed / 7 skipped / 6 warnings, committed only W3 backend/parser/tests as `4a64c2f` (`feat: add safe document upload analysis path`) and pushed `finals-48h`.
- W2 UI changes remain uncommitted and outside this commit. W3 issue #3 remains open with exact UI/mobile/API-fixture gaps; #19 is closed after the bounded parser fix and full-suite evidence.

# 2026-10-23 21:23 IST - W4 live review

- Lane B is editing the deterministic URL/fusion files and a labeled regression fixture. Initial diff adds a 70-point floor for raw-IP plus credential-path evidence, IRCTC to the local brand registry, and broadens the 3-letter brand-distance rule to catch `sbl`/`sbi`.
- Fixture currently identifies local-registry examples and synthetic named misses separately. The exact set count, provenance, new false-positive tests, and before/after allowed validation measurement remain pending. No tests or acceptance claims yet.
- RAM about 10.4 GB; only W2/W4 Hermes sessions are active.

# 2026-10-23 21:26 IST - W2 independent focus-trap reproduction

- The W2 package finished with no code changes and a detailed report. Coordinator's fresh 390x844 Playwright check reproduced a gap: from the focused sidebar container, Shift+Tab moved focus to BODY outside the open drawer, then Tab re-entered.
- Started a focused Lane A follow-up in `work/finals/w2-followup/` (session 63955) to fix forward/reverse trapping, modal semantics, hamburger ARIA, and `/` composer focus where safe. It must preserve identity hydration and not suppress lint. Local-only, no model calls.
- W4 remains active in Lane B. Before W2 follow-up launch: CPU 54.9%, free RAM 13.1 GB.

# 2026-10-23 21:31 IST - W4 validation and fixture audit

- Inspected `scripts/w4_validation_measurement.py` before relying on output. It hard-sets deterministic-only mode and reads only v3 `validation.jsonl`, with resolved-path guards excluding test/holdout; output is under `models/evaluation/w4/`. No spent data opened by coordinator.
- Inventory claims 74 cases yet references IDs through 078. Parsed both fixture copies: 74 rows only (`w4lr-001`..`w4lr-074`), 21 `real_local`, 53 synthetic. This does not satisfy MISSION's 60-real-link acceptance set. Inventory flags `rbi.org.in` at score 50 and not verified. W4 is not accepted; report must reconcile counts and exact set gap.
- Lane A follow-up for reproduced drawer focus escape is active; Lane B W4 continues.
- Additional read-only check of the allowed v3 validation file counted URL availability without outputting/saving rows or URLs: 36 unique hosts total, 17 legitimate URL-bearing rows, 20 synthetic-fraud URL-bearing rows. This does not provide a verified 60-real-link set.
- Sanitized summary from the post-change v3-validation artifact: 625 rows (501 legitimate, 124 malicious), 25 legitimate scores at fixed 0.16 (4 >=50, 0 >=70). This is post-change only; no before/after baseline is present yet, so it is not an accuracy claim.

# 2026-10-23 21:43 IST - Active lane and evidence checkpoint

- W2 focus-trap follow-up and W4 accuracy/rule work remain active; both process trees have recent CPU increments. Logs are buffered, and no kill condition is met.
- W4 does not yet have a final report or before/after baseline. Fixture remains 74 cases: 21 local references and 53 synthetic; validation artifact is post-change only. W4 remains unaccepted.
- Free RAM 11.0 GB. Both lane slots are occupied.

# 2026-10-23 21:47 IST - Worker liveness/resource check

- W2 follow-up and W4 still run; both worker Python CPU counters have incremented since the prior sample. No hang rule is met. Free RAM 11.1 GB.
- W4 fixed set remains 74 rows (21 local/real references + 53 synthetic), short of a 60-real-link set; no final report/baseline yet.

# 2026-10-23 22:00 IST - Lane progress checkpoint

- W2 follow-up and W4 remain active; both worker CPU counters increment. W2 Chat/AppHeader and W4 source/fixture timestamps have not changed since 21:01 / 21:24 respectively; no new final reports are present. No hang kill rule is met.
- Free RAM 15.6 GB. W2 lane handoff is 22:15; W4 handoff is 23:15. Preserve best verified state and report exact gaps at each handoff.

# 2026-10-23 22:02 IST - W2 focus claim correction

- Appended a correction to `work/finals/w2-resume/REPORT.md`: independent 390x844 Playwright repro showed Shift+Tab from focused sidebar container escapes to BODY; next Tab re-enters. The earlier wrap claim is superseded until the W2 follow-up verifies all relevant positions. No chat submission.
- W2 remains NOT accepted. Issue #18 remains open.

# 2026-10-23 22:15 IST - Lane A W2 freeze and W5 handoff

- W2 follow-up produced no source changes or report before its scheduled slot end. Stopped only the W2 Hermes process tree started by this coordinator (PID 29680; child 25836) at 22:15. W2 stays NOT accepted; issue #18, Shift+Tab escape, `/` shortcut, streaming performance, and regression gaps are documented in `work/finals/w2-followup/REPORT.md`.
- Started W5 in `work/finals/w5-extension/` (exec session 96283). W4 remains active in Lane B. Prelaunch resource check: CPU 60.0%, free RAM 21.0 GB.
- W5 slot 22:15-00:45 IST. No production actions or PR/merge.
