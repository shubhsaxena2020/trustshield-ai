# Finals run state

- Last update: 2026-10-23 22:46 IST / 2026-10-23T17:16Z.
- Recovery T0: 2026-10-23 19:55 IST. Hard finish: 2026-10-24 01:55 IST.
- Branch `finals-48h`, HEAD `4a64c2f`, pushed; W2 UI edits remain uncommitted.
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



## GitHub tracker updates (2026-10-23 20:18 IST)

- Closed defect issues #11 and #14-#16 after pushed, independent verification. Posted progress on W2 issue #2 and W3 issue #3; tracker #8 body and comment reflect recovery.
- Issue #18 remains open for the existing two W2 lint errors. Issue #17 remains open because pre-outage screenshot writer attribution is unavailable; W1 files are restored and excluded.
- `android/` is absent; SDK is installed, but `adb devices` found no attached/emulated device. W4-W7 package prompts are staged under ignored `work/finals/` folders.



## Security review note (2026-10-23 20:24 IST)

- `security-reviewer` skill applied to W3 upload parser/API boundary. Automated scanners `semgrep`, `bandit`, `gitleaks`, and `trivy` are not installed; no system tools were installed. Manual parser review found a potential PDF CPU denial of service: `_TJ_RE` in `pdf_text.py` has near-quadratic repeated-suffix scanning on unmatched `[` input, while the deadline is checked outside the regex. Small synthetic timings: 1k=0.0038s, 2k=0.012s, 4k=0.051s, 8k=0.251s, 12k=0.498s.
- Filed issue #19 and commented on W3 issue #3. Parser is not yet API-wired; do not accept W3 until the regex path is bounded, has an in-memory regression, and the full suite passes. No large payload was sent to an API.


## Lane progress (2026-10-23 20:29 IST)

- Both Hermes sessions are still running with empty parent run logs, but fresh work is present. W2 added a 390px viewport capture at 20:28; UI gates still pending.
- W3 interim report at `work/finals/w3-integration/REPORT.md`: focused CSV/text 27 passed, XLSX hostile cases 20 passed, API upload tests 14 passed/1 warning. It reports new `/api/v1/analyze/document` and `/api/v1/chat/upload` integration. This is worker-reported evidence only; independent route/security review and full pytest are pending.
- At 20:29 IST, W3 PDF regex issue #19 was still a blocking finding; the coordinator replaced the regex at 20:39 IST and added a direct timing regression later that evening.

## Independent W3 API/security review (2026-10-23 20:33 IST)

- Reviewed the integrated document dispatcher, analysis pipeline, `/api/v1/analyze/document`, `/api/v1/chat/upload`, and chat orchestrator. Both upload endpoints read at most the configured document cap plus one byte; the outer `BodyLimitMiddleware` rejects oversized request bodies before multipart parsing. Document parser errors are translated to HTTP status/message responses; history stores only a short scan label.
- At 20:33 IST, API/extractor claims were still worker-reported and `_TJ_RE` remained present. Later lane edits and the coordinator parser fix are recorded below.
- Lane B has active Hermes child work; do not infer a hang from an empty parent log. Next scheduled handoff remains 21:30 IST.

## W3 independent lane report (2026-10-23 20:38 IST)

- Lane child 09 completed a review-only security pass and reports: no raw request logging or external fetch in the document path; macro/XXE protections and bounded request/file reads present. It found two follow-up advisories outside the immediate #19 blocker: magic-byte ZIP dispatch reads archive names before `_safe_zip()` entry-count checks, and image uploads use an env cap without the document hard ceiling.
- Child reports focused document tests 128 passed, API/image/QR/chat/hardening tests 67 passed with 5 warnings, and full pytest 1139 passed / 7 skipped / 6 warnings in 175.94s. These are worker-reported totals; coordinator must independently rerun full pytest after source freeze.
- At the 20:38 review, #19 was still open and the regex unchanged. The coordinator replaced it at 20:39; keep the issue open only until a full suite run against frozen W3 source is green, then close with the new evidence.
- A route-contract child report also flagged dead imports (`extract_document` in `api/main.py`, `os` in `orchestrator.py`), chat-upload dispatch requiring an extension/specific MIME for documents, and `chat/upload` using the image cap. That report's statement that no API upload tests exist is stale: `tests/integration/test_document_upload_api.py` exists and covers PDF/DOCX clean, corrupt, oversized, and unsupported cases. It does not yet cover all five document formats at the API boundary; chat generic `application/octet-stream` documents still fall through image validation.
- Coordinator replaced the quadratic PDF text-array regex with a single-pass scanner and added `tests/unit/test_document_pdf_regex_dos.py`; the focused PDF suite passes 23 tests, and 12,000 unmatched brackets scan in 0.0008s locally. The independent full suite then passed 1142 passed / 7 skipped / 6 warnings in 194.94s. `services/api/main.py` changed at 20:40:41 while that suite was running, so rerun the full suite after the W3 lane freezes; do not treat this run as final acceptance evidence.
- Independent web gates: `npm run typecheck` exit 0 and `npm run build` exit 0. `npm run lint` exit 1 on two `react-hooks/set-state-in-effect` findings in `Chat.tsx` at lines 279 and 303 (storage hydration and auth gate effects); issue #18 remains open and W2 is not accepted. No browser keyboard/auth-interaction report has been written yet.
- Coordinator Playwright verification passed the login navigation, gate skip, 390px drawer opening, focus entering the drawer, Tab/Shift+Tab wrapping, Escape close with focus restored to “Open chats”, and attachment menu. Existing 390/834/1440 captures report zero overflow. Browser console: 0 errors/warnings, only React DevTools/HMR info. An unintended click on the canned starter created one local `POST /api/v1/chat/stream`; payload was synthetic text only, and upstream provider contact is unverified. This is documented in ignored `work/finals/w2-resume/COORDINATOR-VERIFICATION.md`. W2 remains unaccepted pending worker’s final report and lint disposition.
- Lighthouse accessibility on local `http://localhost:3000` scored 100; JSON saved to ignored `work/finals/recovery/w2/lighthouse.json`. The spec screenshot comparison, streaming 60fps/no-layout-jump, and existing chat/scan behavior regression gates remain outstanding.
- The route-contract report's generic-MIME finding was corrected by W3's later `orchestrator.py` edit at 20:40 (byte-based detection). Its API-test coverage statement described the report's review point; coordinator confirmed PDF/DOCX API integration tests exist, while XLSX/CSV/TXT API/browser upload coverage is still outstanding.

## W4 read-only preflight (2026-10-23 20:55 IST)

- Synthetic `analyze_url_static` probes: `sbl.co.in/login` -> informational credential-path only; `irctc-refund.xyz/login` -> low suspicious-TLD + informational credential-path; `sbii.co.in/login` -> high lookalike; `192.0.2.8/login` -> high raw-IP + low no-HTTPS. No model, training, or holdout data was used.
- Official-source checks: SBI's own migration notice identifies `sbi.bank.in` as its corporate site ([SBI notice](https://sbi.co.in/redirect/?view=606)); IRCTC's support pages use `irctc.co.in`/`equery.irctc.co.in` ([IRCTC customer support](https://contents.irctc.co.in/en/ContactUsEn.html)). Search results also show `sbl.co.in` in a scholarly look-alike-domain study but in some historical SBI email addresses; avoid blanket blocking without validating the intended risk context. No candidate host was opened directly.
- No W4 code or data changed in this preflight. W4 starts after the scheduled W3 handoff at 21:30 IST.

## Live continuation checkpoint (2026-10-23 21:06 IST)

- Coordinator re-read HEAD_ORDERS.md sections 4-5, MISSION.md, STATE.md, and NEEDS_SHUBH.md. Hard stop remains 01:55 IST. No user input is being requested.
- The resumed Hermes lane processes are no longer running. API PID 32660 (127.0.0.1:8000), web PID 28920 (localhost:3000), and Nilabh gate PID 8076 (127.0.0.1:20190) are listening. Free RAM is 18.5 GB; CPU was about 50%, so the >=8 GB launch gate is met.
- W2 report addendum at 21:05 IST corrects stale earlier geometry/Lighthouse notes: form geometry matches at 390x844, 834x1112, 1440x900; Lighthouse accessibility 100; typecheck/build pass; lint still has one identity-hydration `react-hooks/set-state-in-effect` error. W2 remains NOT accepted; streaming frame/layout-shift timing, slash shortcut, and full existing chat/scan regression remain incomplete.
- W3 report at 21:02 claims 175 focused tests pass and full pytest 1142 passed / 7 skipped / 6 warnings. Coordinator diff review found duplicate `_DOCUMENT_EXTENSIONS`/`_DOCUMENT_MIMES` declarations and an unused `os` import in `services/chat/orchestrator.py`, plus advisory memory amplification from ZIP central-directory name enumeration and env-raised image/chat caps. W3 remains NOT accepted because composer/browser/mobile integration and broader API/browser fixtures are incomplete. Independent mandatory full suite is currently running as session 72432; record the actual exit and totals before relying on it.
- W3 PDF regex DoS fix and direct regression are present, but issue #19 stays open until the coordinator's full suite result and route review are recorded. No production, model, threshold, or spent-data access occurred.
- Next scheduled work: keep W2 active until 22:15; freeze W3 at 21:30 and start W4 in Lane B; start W5 after W2 handoff. Do not claim acceptance where stated gates remain unmet.

- W2 Lane A added `work/finals/w2-resume/review-scanner-auth.md` at 21:16: review-only coverage says authFetch, session identity scoping, history, health polling, and upload request shape were preserved; the composer remains image-only, so W3 documents are not reachable from UI. This is not an automated auth/scanner regression test and does not close W2's existing behavior/performance gaps.
- W2 Lane A added `work/finals/w2-resume/gate-check.md` at 21:17. It independently reproduced typecheck/build pass, lint's single error at Chat.tsx:301, and the remaining screenshot/streaming/accessibility gaps. It recommends keeping W2 NOT accepted. RAM was 11.7 GB at 21:19; the 2 Hermes sessions remain active.

## W3 backend milestone commit (2026-10-23 21:22 IST)

- After independent full pytest passed 1142/7/6 and cached diff check was clean, coordinator committed only the W3 API/extractor/parser/tests as `4a64c2f` (`feat: add safe document upload analysis path`) and pushed `finals-48h`.
- W2 UI edits, orchestration notes, unrelated untracked artifacts, and W3 acceptance claims were not included. W3 issue #3 remains open for its UI/mobile/fixture gaps; #19 is closed for the verified PDF scan fix.

## W4 live implementation review (2026-10-23 21:23 IST)

- Lane B has uncommitted changes in `services/analysis/fusion/{engine,strategies}.py` and `services/analysis/url_analysis/{brands,static}.py`, plus `tests/fixtures/w4_link_regression.jsonl`. Initial review: raw-IP plus credential path gets a deterministic 70-point floor; IRCTC was added to the brand registry; the 3-letter `short_brand` distance was widened to catch `sbl` vs `sbi`. The fixture identifies synthetic cases explicitly; final size/provenance and false-positive tests are pending. No full test or acceptance claim yet.
- These edits do not conflict with Lane A's web UI files. Free RAM is about 10.4 GB; both Hermes lanes are active and no third lane will be launched before a slot frees.

## W2 keyboard repro and follow-up (2026-10-23 21:26 IST)

- The first W2 run finished at 21:23 without code changes. Its independent detailed report conflicts with the earlier focus-trap claim. Coordinator reproduced at 390x844: after opening the drawer with focus on the sidebar container, `Shift+Tab` moves focus to `BODY` outside the drawer; then `Tab` re-enters.
- Started a focused W2 follow-up in `work/finals/w2-followup/` via Hermes (session 63955) while W4 remains active in Lane B. Scope is reverse/forward focus trap, modal semantics, hamburger ARIA, and the `/` composer shortcut; preserve identity hydration and do not suppress lint. No model calls or commits.
- Before launch: CPU 54.9%, free RAM 13.1 GB. Two Hermes sessions active; maintain the >=8 GB launch gate.

## W4 safety and fixture audit (2026-10-23 21:31 IST)

- Read `scripts/w4_validation_measurement.py` before relying on its output: it hard-sets deterministic-only mode and reads only `datasets/processed/trustshield-text-v3/validation.jsonl`; path/split guards reject test/holdout names. The generated output is under `models/evaluation/w4/`. No spent data was opened by the coordinator.
- `docs/finals/W4_SOURCE_INVENTORY.md` has arithmetic/text inconsistencies: it reports 74 rows but notes IDs 075-078; both fixture copies currently parse as 74 rows (`w4lr-001` through `w4lr-074`: 21 `real_local`, 53 `synthetic`). Mission's 60-link acceptance set calls for real links; this does not satisfy a 60-real-link gate. The report also says `rbi.org.in` currently scores 50 and explicitly marks it unverified.
- A coordinator read-only availability check of the allowed v3 validation split found 36 unique hosts total, with only 17 URL-bearing legitimate rows and 20 URL-bearing synthetic-fraud rows; this is not enough verified material to build a 60-real-link set. No row text or URLs were emitted or saved.
- The post-change validation artifact reports 625 rows (501 legitimate, 124 malicious), 25 legitimate scores at the fixed 0.16 threshold (4 at score >=50; none >=70). This is one post-change measurement only; no before/after baseline artifact is present yet, and it is not an accuracy claim.
- W4 remains active. These report/provenance gaps and the 60-real-link requirement must be stated accurately in its final report; no acceptance, full-suite, or commit claim yet.

## Active lane check (2026-10-23 21:43 IST)

- W2 focused follow-up (Hermes PID 29680, Python PID 25836) and W4 (Hermes PID 29456, Python PID 26196) are active with recent CPU increments. Their parent `run.log` files remain empty due to output buffering; both have work artifacts and no kill condition is met.
- W4 has not produced a final report or before/after measurement yet. Existing post-change artifact and fixture counts remain as recorded above. Free RAM is 11.0 GB; do not launch another Hermes session until a slot ends.

- At 21:47 IST, both worker Python processes still show small CPU increments; their empty parent logs and last file timestamps have not changed. No hang kill rule is met. Free RAM is 11.1 GB.

## 22:00 IST progress checkpoint

- W2 follow-up and W4 remain active. W2 `Chat.tsx`/`AppHeader.tsx` timestamps have not changed since before the follow-up; no report is present. W4 source/fixture timestamps likewise have not changed since 21:24, with no final report. Both worker CPU counters continue to increment, so the hang rule is not met.
- Current free RAM is 15.6 GB; both Hermes sessions still occupy the two allowed slots. W2 handoff to W5 is 22:15; W4 handoff to W6 is 23:15. Any unfinished package freezes at its best verified state with exact gaps.
- W2 report now includes a coordinator correction: the 21:26 Playwright repro shows Shift+Tab from the focused sidebar container escapes to BODY. The earlier wrap claim is superseded until the follow-up verifies the reverse/forward trap from relevant positions.

## Lane A handoff to W5 (2026-10-23 22:15 IST)

- W2 focused follow-up did not produce source changes or a report before handoff. It was stopped at the schedule boundary using only its owned process tree; evidence and exact gaps are recorded in `work/finals/w2-followup/REPORT.md`. W2 remains NOT accepted; issue #18 and the confirmed Shift+Tab escape remain open.
- Started W5 Chrome extension lane in `work/finals/w5-extension/` (Hermes exec session 96283). Lane B W4 continues. Before launch, CPU was 60.0% and free RAM 21.0 GB.
- W5 slot is 22:15-00:45 IST. No PR, merge, deployment, or production changes.

- W6 preflight at 22:27: `android/` and `android/gradlew.bat` are absent; Android SDK exists at the configured user path; `adb devices` lists no device/emulator. W6 can still build/test per prompt, but hardware smoke cannot be claimed unless a device appears.

## W7 harness preflight (2026-10-23 22:32 IST)

- Reviewed `scripts/security/prompt_injection_battery_256.py` and `scripts/security/model_routing_battery_35.py` before execution. Their assertions define 256 unique and 35 unique prompts respectively; neither references spent datasets. The 256 harness reads the configured API key only for an in-memory match check and redacts it before raw serialization; progress prints only a boolean/status summary.
- Both checked-in scripts write to tracked `docs/security-runs/*_raw.json`; untracked local copies are prepared in ignored `work/finals/w7-final/`, with only root resolution and raw/report/progress output paths changed. `git diff --no-index` confirms prompt lists and request logic are unchanged. Copies have not been run yet; run them in W7 and review sanitized aggregates only.
- Existing untracked `scripts/security/prompt_injection_battery_256.progress.log` belongs to an earlier run and is not final-run evidence. No W7 chat requests have been sent in this continuation.

## W4/W5 liveness checkpoint (2026-10-23 22:46 IST)

- W5 and W4 have not produced new source files or final reports since their launches/initial W4 artifacts; both Python process CPU counters continue to increase slightly, so the no-CPU-15-minute hang criterion is not met. Parent logs remain empty/buffered.
- Free RAM 9.3 GB and CPU about 74% at 22:45; no further worker launch while both slots are occupied.
- W5 handoff remains 00:45; W6 00:45-01:00; W7 remains 01:00-01:55 hard finish.

## W3 independent suite checkpoint (2026-10-23 21:10 IST)

- Coordinator independently ran `.venv\Scripts\python -m pytest -q` against the current W3 snapshot: **1142 passed, 7 skipped, 6 warnings in 198.18s**. Warnings: one Starlette/httpx deprecation, four registered-domain deprecations, and one intentional duplicate ZIP member in a hostile fixture.
- W3's PDF regex denial-of-service fix and regression are covered by this full run. Issue #19's reported defect is fixed in code; its issue closure can cite this run, while W3 overall remains NOT accepted due to browser/mobile upload and fixture-coverage gaps.
- The full-suite run is complete. No source changes were made during it.

## Scheduled lane handoff (2026-10-23 21:12 IST)

- Lane A W2 resumed via Hermes in `work/finals/w2-resume/` (Hermes PID 11660; process/session owned by coordinator exec 89363). Keep it on W2 through 22:15.
- Lane B W3 backend work is frozen at its verified state. Issue #19 was commented and closed after the independent full-suite pass. W3 issue #3 received an evidence-based progress comment and remains open, explicitly recording browser/mobile and fixture gaps.
- Lane B W4 started via Hermes in `work/finals/w4-rules/` (coordinator exec session 1758). It must use only local allowed TRAIN/VALIDATION material or clearly labeled synthetic cases; no external browsing, holdout access, model retraining, v3 or threshold changes.
- An additional pytest process appeared under a separate shell at 21:10:44 while the coordinator run was finishing; it was not started or stopped by this coordinator. The independent coordinator result remains 1142 passed / 7 skipped / 6 warnings in 198.18s.
- Both Hermes slots are occupied; API 32660, web 28920, and gate 8076 remain the only expected local services. Continue checking free RAM and CPU before further launches.

> HEAD NOTE 22:10 IST: no commit since 4a64c2f (21:21) and 12 tracked files are uncommitted. After last night's power loss, do not hold verified work in the tree: at the 22:15 W2 handoff, commit whatever passes the full suite (my independent run at 22:06: 1278 passed / 7 skipped) and push, even if W2 is not yet accepted; label it WIP in the message.

> HEAD ORDER 22:26 IST (blocking): the 22:10 note was not acted on - W2 was frozen at 22:15 without a commit. I pushed a safety snapshot of the working tree to branch backup/finals-wip-2224 (521df33, not for merge; your index and tree were not touched). Before any further W4/W5 dispatch, commit the frozen W2 state and the verified W4 files on finals-48h (after a full-suite pass), push, and from now on commit+push at every lane handoff. Do not commit services/analysis/fusion/result.py.orig or log files.

> HEAD DEFECT 22:47 IST (blocking for W4): see the new GitHub issue "W4 bug: 3-letter look-alike rule flags legitimate domains". The uncommitted static.py change makes ubi.com, sbs.com.au and abi.org score 50 (0 on HEAD). Do not commit or accept W4 until fixed with a regression fixture of real 3-letter domains. Also dedupe the IP+credential floor (engine already applies it to every strategy). Safety backup refreshed on a backup/finals-wip-* branch. Commit+push at every handoff.

> HEAD ORDER 22:52 IST: Nilabh is down. Hermes is TEMPORARILY switched to cc/claude-sonnet-5 via 9Router (Mukesh PAPA). Relaunch any Hermes session that started on Nilabh. See HEAD_ORDERS.md section 6.

## 2026-10-23 22:55 IST — restart at head direction

- Re-read docs/finals/HEAD_ORDERS.md completely first, then MISSION, NEEDS_SHUBH, and GitHub issues #18/#20/#8. Head orders acknowledged in docs/finals/ORCHESTRATOR-LOG.md.
- Treat all prior Hermes sessions as dead. Current configured model is cc/claude-sonnet-5 via 9Router; do not pass --provider or -m. No session relaunched yet.
- Full pytest on the current source snapshot passed: 1278 passed, 7 skipped, 6 warnings in 145.10s. Preparing separate W2 WIP and W4 verified commits. The defective three-letter static.py hunk is excluded; issue #20 remains blocking until regression fixture covers at least 20 real legitimate three-letter domains plus the four required URLs.
- W2 remains NOT accepted (issue #18 and behavioral/performance gaps remain). W4 remains NOT accepted. W5/W6/W7 and final report remain outstanding. Hard finish: 2026-10-24 01:55 IST.
- Never stage *.orig, logs, .env, or generated pentest uns/; don't read spent data, change production, open PR/merge, or alter the model/threshold.
