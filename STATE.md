# Finals run state

- Last update: 2026-10-23 20:17 IST / 2026-10-23T14:47Z.
- Recovery T0: 2026-10-23 19:55 IST. Hard finish: 2026-10-24 01:55 IST.
- Branch `finals-48h`, HEAD `a2f6e0f`, pushed to `origin/finals-48h`.
- All pre-outage Hermes sessions are dead: prior Lane B terminal 86379 / Hermes PID 32720 / worker 31084; prior Lane A terminal 91730 / Hermes PID 33368 / worker 28832.
- Current local processes started in recovery: API PID 32660 on port 8000; web PID 28920 on port 3000; Nilabh gate PID 8076 on 127.0.0.1:20190. Keep the gate active. Latest check: 19.2 GB free RAM.
- Recovery milestone is complete and pushed. W1 generated screenshot/manifest churn was restored to committed baseline and excluded; unrelated existing untracked folders/logs remain untouched.

## Compressed schedule (HEAD_ORDERS.md section 5)

- Lane A: W2 web UI until 22:15; W5 Chrome extension 22:15-00:45.
- Lane B: W3 uploads, including issues #14-#16, until 21:30; W4 accuracy/look-alike regression until 23:15; W6 Android until 01:00.
- 01:00-01:55: W7 security/regression batteries, demo flow, deck/notes numbers, FINAL-REPORT.md, push, and issue #8 update.
- Freeze incomplete work at its best verified state and file exact gaps. Never cut full-suite gate, spent-data protections, honest metrics, or production boundary. No PR or merge.

## W1 - auth/backend

- Previously verified locally: isolated Playwright auth matrix 3 runs, 102 checks each; full pytest before outage was 959 passed, 7 skipped, 5 warnings.
- Real Supabase email confirmation and live token refresh remain unverified; see `docs/finals/NEEDS_SHUBH.md`.

## W2 - web UI

- Discovery spec/captures remain at prior pushed milestone 9a831b3.
- Recovery shell integration is committed and pushed at a2f6e0f. Typecheck and build pass. `npm run lint` has two existing `react-hooks/set-state-in-effect` errors in `Chat.tsx` around lines 235 and 259; issue #18 is open.
- Local app passed HTTP 200, zero console errors, and no horizontal overflow at 390x844, 834x1112, and 1440x900 via `http://localhost:3000`. Screenshots are ignored under `work/finals/recovery/w2/`.
- W2 remains unaccepted pending lint disposition, keyboard/streaming/a11y/interactions review, and a lane report. Unused draft components and unrun UI test scaffolds were excluded from the recovery commit for review.

## W3 - safe uploads

- Extractor foundation is committed/pushed: bounded PDF/DOCX/XLSX/TXT/CSV extraction, synthetic hostile-input coverage, required `defusedxml`, shared hard upload cap, and normalized corrupt XML/ZIP handling.
- Latest focused W3 suite: 77 passed in 0.90s. Latest mandatory full suite after all recovery code edits: 1041 passed, 7 skipped, 5 warnings in 189.20s.
- Issues #14 (hard upload cap), #15 (PDF decompression cap), and #16 (parser exception normalization) are closed after full-suite and focused verification. Issue #11 font token fix is also closed.
- W3 remains unaccepted: API route and composer/browser/mobile upload wiring are not complete.

## W4-W7

- W4: left; preserve baseline metrics (sealed holdout recall 82.1 / specificity 78.3; 90% target not met). Never use spent data or change the 0.16 threshold.
- W5: left; `extension/` absent at recovery.
- W6: left; `android/` absent at recovery.
- W7: left; full security batteries, demo readiness, final report, and tracker close remain scheduled 01:00-01:55.

## Recovery test evidence

- Full suite command `.venv\Scripts\python -m pytest -q`: 1041 passed, 7 skipped, 5 warnings in 189.20s.
- Focused W3 command recorded in `work/finals/recovery/REPORT.md`: 77 passed in 0.90s.
- Web: `npm run typecheck` exit 0; `npm run build` exit 0; `npm run lint` exit 1 on the two existing findings above.
- Local API: `GET /api/v1/health` HTTP 200. Web: `GET http://localhost:3000` HTTP 200.

## Open decisions and defects

- `NEEDS_SHUBH.md` still records the external Supabase verification gap; no action is needed during this unattended run.
- Issue #18 tracks the W2 lint errors. Issue #17's W1 evidence path is restored, but the exact pre-outage writer command is unknown; keep the issue open with that limitation.
- No PR opened; production and Vercel boundaries remain in force.

## Active work lanes (2026-10-23 20:14 IST)

- Lane A W2 resumed via `hermes -z` in `work/finals/w2-resume/`; command-session id 16572; Hermes PID 32600. Initial run log is empty; per MISSION/HEAD_ORDERS do not treat silence under 15 minutes as a hang.
- Lane B W3 resumed via `hermes -z` in `work/finals/w3-integration/`; command-session id 97179; Hermes PID 30336. It owns API/extractor integration and must not edit `Chat.tsx` while Lane A owns W2.
- Current active model route remains behind gate PID 8076 on 127.0.0.1:20190. 8 GB minimum launch gate met at 22.8 GB free / 37% CPU for Lane A and 21.9 GB / 70.4% for Lane B.
- Next schedule handoff: Lane B freezes W3 by 21:30 then W4; Lane A continues W2 to 22:15 then W5. Full suite is required before every code fix/acceptance commit.


