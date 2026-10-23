# HEAD ORDERS (from Claude Code, 2026-10-23 ~05:35 IST) - read now, supersedes MISSION.md where they differ

These come from Shubh and from live tests I ran myself. Acknowledge them in ORCHESTRATOR-LOG.md and STATE.md.

## 1. The chat model has a hard rate limit: 30 requests per minute for the whole key
Measured today: the Nilabh DeepSeek route (`Deepseek-v4.1-Flash`) answers HTTP 429 "Rate limit exceeded (30 requests/minute)" above that,
and 9Router then returns 503 to every client for a few seconds. A burst of 18 parallel agents makes this happen constantly.
- I restarted the local gate `C:\Users\shubh\ai-memory-system\ops\nilabh_ratelimit_proxy.py` on `127.0.0.1:20190` (26 requests/min, FIFO queue, retries 429 itself).
  9Router's `nilabh` provider, the Hermes `nilabh-direct` provider and the TrustShield `.env` (`NILABH_LLM_BASE_URL`) all go through it now.
  Verified: 36 of 36 requests in two 18-way bursts succeeded with zero 429s. Do not bypass it, do not stop it, do not edit it.
- Consequence: total throughput is about 26 model calls per minute for ALL agents together. Eighteen subagents therefore share that budget; first-token delay
  under a full burst is 25-45 s. That is normal queueing, not a hang. Do NOT treat a 30-60 s silence as a dead worker; use the 15-minute rule in MISSION.md.
- Plan for it: give each subagent coarse tasks (fewer, bigger model calls, long tool runs), prefer local work (tests, builds, Playwright, grep) over chatty loops,
  stagger the two sessions' fan-outs, and keep the effective number of simultaneously active subagents around 8-12 rather than 18. Per-stream speed once started is ~100-150 tokens/s.
- If the gate is down (`Get-NetTCPConnection -LocalPort 20190` shows nothing), restart it with `Start-Process python -ArgumentList 'C:\Users\shubh\ai-memory-system\ops\nilabh_ratelimit_proxy.py' -WindowStyle Hidden`. Log: `ai-memory-system\ops\nilabh_proxy.log`.
- Still: no fallback models. If the route is down, wait and retry with backoff.

## 2. You now have full GitHub access (Shubh's order) - use it as a real workflow
`gh` is authenticated as `shubhsaxena2020` (scopes repo, workflow, read:org, gist; ADMIN on the private repo `shubhsaxena2020/trustshield-ai`).
- Commit small and often on `finals-48h`; push after every green milestone.
- Use GitHub Issues as the work tracker: create one issue per workstream package (W1..W7) with acceptance criteria, label them (`finals-48h`, `W1`..`W7`, `bug`, `blocked`),
  comment progress and evidence (test output, screenshot paths) on each, and close an issue only after you verified its acceptance test yourself. File an issue for every defect found.
- Use Pull Requests: open one PR per workstream from a short-lived branch `finals/<workstream>` into `finals-48h` (or one rolling PR from `finals-48h` into `main`), with a description of what changed and how it was verified. Use `gh run` / Actions logs if CI exists.
- Keep a pinned tracking issue "48h finals program" whose body is the current checklist (update it from STATE.md every few hours).
- Guardrails that stay: no force-push, no deleting branches/repos/issues, no changing repo visibility/settings/secrets/webhooks/collaborators, no pushing to `main` or `conversational-safety-fixes`
  (production history), no merging anything into `main` (Shubh merges after he reviews), no GitHub Actions secrets, no committing `.env`, keys, datasets with unclear licences, or spent holdouts.

## 3. Health facts (Claude Code, 05:35)
Laptop: 23 GB free RAM, CPU about 10 percent. Nilabh route healthy: single stream ~106 tokens/s, 12 parallel streams ~65-75 tokens/s each; 9Router `nilabh` connection active.
You are 20 minutes in and the 15 plan documents exist - good. Keep HEARTBEAT.txt fresh.

## 4. UPDATE 05:46 IST - the 30 requests/minute limit is GONE (Shubh's friend removed it); gate retuned
Re-tested: direct and through the gate, 78 of 78 requests succeeded with zero 429s; 18 parallel long streams ran at about 580 tokens/s together (was 121 under the limit).
Direct bursts of 30 brand-new connections still drop some TLS handshakes (SSL EOF), so the gate stays as a smoother: I restarted it as PID 4124 at 120 requests/minute
(0.5 s spacing; 25944 is dead). The earlier "about 26 calls per minute, 8-12 active agents" guidance no longer applies: you may run both sessions with up to 9 children each.
Keep everything going through 127.0.0.1:20190 and still add no fallback models.

> HEAD DEFECT 06:42 IST (blocking, found by independent full-suite run): tests/integration/test_history_endpoints.py line 346 contains a stray literal backslash-n at end of file (commit 2c5b415), causing a SyntaxError and a collection ERROR that aborts `pytest -q`. W1 acceptance was premature. Fix it (remove the stray line), rerun the FULL suite (expect >= 802 passed), file/reopen a GitHub issue, and make the full-suite run a mandatory gate before every acceptance and commit message saying "fix"/"accepted". Do not trust a worker REPORT.md that ran only a subset.

## RESOLVED 2026-10-23 01:18 UTC - test collection defect
The stray suffix in tests/integration/test_history_endpoints.py was reproduced and removed. Full suite passed: 959 passed, 7 skipped, 5 warnings in 168.41s. Fix commit 335ad49 is pushed; GitHub issue #13 records the defect and verification. Keep full pytest as a required gate before any further fix or acceptance commit.

## 5. POWER-OFF RECOVERY + 6-HOUR COMPRESSION (Shubh's order, 2026-10-23 19:55 IST)
The laptop was shut down at about 07:25 IST and was off for about 12 hours (Shubh's room light failed). Every process died: Codex exec, both Hermes sessions, the local API/web servers.
Disk is intact: `git fsck` reports only dangling objects; the gate (new PID 8076, 120 req/min, port 20190) and 9Router are up again; the Nilabh route answers; Hermes config still has `fallback_providers: []`.
**New clock: T0 = 19:55 IST on 2026-10-23. Hard finish = 01:55 IST on 2026-10-24 (6 hours).** Shubh wants the WHOLE program finished in this window, using as much of the machine as needed. PLAN-48H.md is superseded by this compressed schedule:
1. 19:55-20:25 Recovery. Triage the 32 uncommitted files (W2 UI edits made before the crash; regenerated W1 evidence screenshots/`w1-run.json` are churn - do not commit noise). Keep what passes review, discard what does not, run the FULL pytest suite and `tsc`/lint/build, restart the API (8000) and web (3000), mark every Hermes PID in STATE.md as dead, relaunch both lanes. Commit and push `finals-48h`.
2. Lane A: W2 web UI (to 22:15), then W5 Chrome extension (22:15-00:45).  Lane B: W3 uploads incl. the open issues #14-#16 (to 21:30), then W4 accuracy + look-alike regression set (21:30-23:15), then W6 Android (23:15-01:00).
3. 01:00-01:55 W7: full security/regression batteries, demo flow, deck/notes numbers, FINAL-REPORT.md, push, update issue #8. Never cut: the full-suite gate, spent-data rules, honest numbers, no production changes, no PR/merge.
4. If a workstream cannot reach acceptance in its slot, freeze it at its best verified state, file an issue with exactly what is missing, and move on. A smaller verified result beats a larger unverified one.
5. Resources: you may use up to both Hermes sessions with 9 children each continuously. The free-RAM launch gate is relaxed to >= 8 GB. Keep unattended: do not stop for questions.
6. GPU: Shubh asked to keep the GPU at 100 percent. I am not doing that and you must not either: nothing in this program is GPU-bound (the model runs on the remote Nilabh server; pytest, Gradle, Playwright and git are CPU/IO), and burning the Intel Arc iGPU artificially only adds heat to a laptop that already hit 95 C once. Use the GPU where it helps for real: Chromium/Playwright keep hardware acceleration on, run the Android emulator with `-gpu host`. Do not run local LLMs unless a package needs one.

> HEAD DEFECT 22:47 IST (blocking for W4): see the new GitHub issue "W4 bug: 3-letter look-alike rule flags legitimate domains". The uncommitted static.py change makes ubi.com, sbs.com.au and abi.org score 50 (0 on HEAD). Do not commit or accept W4 until fixed with a regression fixture of real 3-letter domains. Also dedupe the IP+credential floor (engine already applies it to every strategy). Safety backup refreshed on a backup/finals-wip-* branch. Commit+push at every handoff.

## 6. TEMPORARY MODEL SWITCH (Shubh's order, 22:52 IST) - Nilabh is down
The Nilabh DeepSeek route is failing (its own upstream answers "Rate limit exceeded"; through 9Router every call returns 503).
Shubh ordered a TEMPORARY switch until his friend fixes it: Hermes now runs on **`cc/claude-sonnet-5`** through 9Router's "Mukesh (PAPA)" Claude Code connection.
Done by the head and verified: Hermes `model.default` and `delegation.model` = `cc/claude-sonnet-5` (provider custom:9router), `fallback_providers: []` kept;
a live test ran a parent plus 3 parallel children, all on cc/claude-sonnet-5, 3/3 correct.
- Every NEW `hermes -z` launch picks this up automatically (do not pass `--provider`/`-m`). Sessions that started on Nilabh are failing now: relaunch them.
- The PAPA Claude Code quota is a 5-hour session budget (shown as 100 units) plus a weekly budget. It is finite: keep fan-out efficient (coarse tasks, fewer chatty calls). If 9Router starts returning quota errors for cc/*, slow down rather than add other models.
- The TrustShield app itself (`.env` NILABH_LLM_*) is NOT switched: the product's chat model stays the friend's DeepSeek; tests do not need the live model. Do not change it.
- Shubh will say when Nilabh is back; the head switches Hermes back then.

> HEAD NOTE 00:05 IST: my full-suite run at 23:54 had 1 failure, tests/unit/test_v2_promotion_eval.py::test_spent_v2_scripts_refuse_without_archival_override (1279 passed). Rerun alone it passes; all four spent-v2 scripts still import require_archival_override and the v2 access log still has exactly 1 entry, so no spent-data access happened. Likely subprocess start-up failure under ~0-4 GB free RAM. Treat as environment flake, but if it recurs in your own full run, investigate before any acceptance.

> HEAD DEFECT 00:20 IST (blocking resource leak): 8 orphaned `find / ...` processes started by Hermes subagents between 20:22 and 21:28 (PIDs 34712 7204 33092 9220 24504 7468 1844 6928; command `...hermes	ools\git...ind.exe / -name ...`) are walking the whole disk and hold the CPU at ~100%; that is why your W6 CPU gate never opens. Their parents are gone. I cannot kill them (Access is denied - they appear to run under the sandbox account); try killing exactly these PIDs from your own session. Add to EVERY worker prompt from now on: never search from `/` or a drive root; search only inside C:\Users\shubh	rustshield-ai (use `rg --files` or `git ls-files`), and wrap any find/search in a 60-second timeout.
(The line above was cut off by a shell escape; full order below.)

> HEAD DEFECT 00:20 IST (blocking resource leak): 8 orphaned `find / ...` processes started by Hermes subagents between 20:22 and 21:28
> (PIDs 34712 7204 33092 9220 24504 7468 1844 6928; command `C:\Users\shubh\AppData\Local\hermes\tools\git-2.53.0+3-win32-x64\usr\bin\find.exe / -name ...`)
> are walking the whole disk and hold the CPU at about 100 percent. That is why your W6 CPU gate never opens. Their parents are gone.
> The head cannot stop them ("Access is denied"; they appear to run under the sandbox account). Try stopping exactly these PIDs from your own session.
> Add to EVERY worker prompt from now on: never search from `/` or a drive root; search only inside C:\Users\shubh\trustshield-ai
> (use `rg --files` or `git ls-files`), and wrap any find or search in a 60-second timeout.

## 7. SECOND TEMPORARY MODEL SWITCH (Shubh's order, 01:00 IST) - PAPA Claude Code limit reached
`cc/claude-sonnet-5` now answers 429 "would exceed your account's rate limit" (9Router returns 503). Shubh ordered Hermes moved to the
**Codex (mukku) account through 9Router, model `cx/gpt-6-luna`**. Done by the head and verified: Hermes `model.default`, `delegation.model` and the
9router provider model are `cx/gpt-6-luna`; `fallback_providers: []` kept; a live test ran a parent plus 3 parallel children, all on cx/gpt-6-luna, 3/3 correct.
- New `hermes -z` launches use it automatically (still no `--provider`/`-m`). Relaunch any Hermes session that is failing on Sonnet 503s (W6 lane).
- This 9Router Codex account shares quota with Shubh's Codex usage (including yours). Keep W6/W7 fan-out lean.
- Still temporary: Nilabh DeepSeek remains the long-term default once Shubh says it is fixed.

> HEAD NOTE 01:12 IST (W7 interpretation): the 256-injection and 35-routing batteries hit the local `/api/v1/chat`, whose model is the Nilabh DeepSeek route (`.env`), which is DOWN tonight. Before running them, send one real chat request and check that the model actually answered. If it did not, record the batteries as "not measured: product chat model unavailable" in FINAL-REPORT.md, not as passes. Do not repoint the app's `.env` to another model.

> HEAD DEFECT 01:30 IST (blocking for FINAL-REPORT): evidence that the batteries ran WITHOUT the chat model. In `work/finals/w7-final/model_routing_battery_35_coord_raw.json`, MR-02 replies "my language model isn't available at the moment ... I won't guess at an answer". The local `/api/v1/chat` answers only from deterministic templates while Nilabh is down.
> So: the 35-prompt routing result (33/35) measures the deterministic router plus fallback only, and the 256-prompt injection result cannot show model-level jailbreak resistance. FINAL-REPORT.md must say exactly that ("chat model unavailable during W7; model-level results NOT measured"), keep the earlier 11 Oct model-backed results as the last real measurement, and list a rerun as a NEEDS_SHUBH item once Nilabh is back. Do not present tonight's battery numbers as model results.

## 8. Commit cadence + deploy fix (Shubh's order, 01:40 IST)
- Shubh: commit after every phase or medium-sized piece of work, not only at lane handoffs or the end. A finished sub-task that passes its tests = a commit and a push.
- The head committed `requirements-api.lock.txt` adding `defusedxml==0.7.1`: the W3 DOCX/XLSX extractors import it at module level and `services/api/main.py` imports them at startup, so the production container would not start without it. Any new runtime import must be added to that lock file in the same commit.
- Shubh asked for tonight's work to go live. The head is deploying the committed snapshot from a separate worktree (Preview first, smoke-tested, then promote). Do not run vercel commands yourself.

## 9. PRODUCTION DEPLOYED by the head (01:55 IST, Shubh's explicit order)
Commit `c4f783e` (finals-48h, includes the defusedxml lock fix) is LIVE at https://trustshield-ai-ecru.vercel.app
(Preview `trustshield-48zbozvvr` verified, promoted -> production deployment `trustshield-8meq14w6d`; previous production `trustshield-9j1cy7l2z` kept for `vercel rollback`).
Verified on the live site after promotion: health ok (v3, Tesseract, QR), readyz true; SBI scam text 85 high_risk; onlinesbi.sbi 0; ubi.com 0;
DOCX and XLSX chat uploads HTTP 200 (new capability); chat model answers ("Good morning! I'm doing well..."; production env model, not Nilabh); / /login /signup 200.
Add this to FINAL-REPORT.md: production now carries the WIP/unaccepted W2 UI state and the W3/W4 work, deployed on Shubh's order; W5/W6 are not part of the web deployment.

## 10. TWO-HOUR RUN (Shubh's order, 2026-10-24 ~02:45 IST) - UI 1:1 clone + leftovers. Android app and Chrome extension are EXCLUDED.
**T0 = 02:45 IST, Codex freeze = 04:15 IST, head deploys 04:15-04:45, hard end 04:45 IST.** Do not touch `extension/` or `android/`.
Shubh's complaint: "the live site is not working and the UI has not changed as I asked, it is even worse than before". Head's check at 02:35: the live site loads (/, /login, /signup 200), chat streaming works,
but the UI is only a loose ChatGPT-style shell (screenshot: `C:\Users\shubh\c language\live-home-1440.png`). Shubh wants the REAL thing: **clone the ChatGPT web UI 1:1, then make minor changes to make it TrustShield's.**

### Model routes tonight (head tested 02:40): ONLY `cx/gpt-6-luna` answers. Nilabh DeepSeek and cc/claude-sonnet-5 both return 503. Hermes is on cx/gpt-6-luna; do not add fallbacks.
That route shares the Codex quota with you. Use at most 2 Hermes sessions with at most 6 children each; coarse tasks, few chatty calls; stop searching the whole disk (search only inside the repo, 60 s timeouts).

### Lane A - W2: faithful ChatGPT UI clone via REA (Shubh explicitly ordered the REA / reverse-engineering skill)
1. Write the REA instruction first (you), then hand it to Hermes. Skills: `C:\Users\shubh\Projects\rea\.agents\skills\reverse-engineer-anything` and `rea-tool-design`; REA MCP tools (`mcp__rea__*` in Hermes: list_browser_targets, inspect_web_page, capture_web_screenshot, capture_browser_scenario, compare_web_screenshots, observe_web_session, analyze_web_bundle) work through a Chrome DevTools endpoint: launch an ISOLATED Chrome/Chromium with `--remote-debugging-port=9222 --user-data-dir=<temp dir>` (never Shubh's real profile, no sign-in), open the public logged-out ChatGPT web page, and capture.
2. Capture at 1440x900, 834x1112 and 390x844: empty state, composer focus/typing/multi-line growth, sidebar expanded/collapsed/mobile drawer, new-chat/history item hover and menus, message layout (user bubble vs assistant plain text), streaming caret, copy/regenerate/edit/feedback icons, code block header, scroll behaviour and scroll-to-bottom button, top bar/model selector, login/signup pages and modals, loading/error/empty states, keyboard shortcuts, animations (durations/easing), light-theme tokens (colors, radii, font sizes/weights/line heights, spacing scale, shadows). Record computed CSS values, not impressions. Save the evidence under `docs/finals/evidence/w2/` (screenshots only; no copied source, no logos).
3. Write `docs/finals/UI_SPEC.md` with the measured spec, then implement it in `apps/web` (Chat shell, sidebar, composer, messages, login/signup, empty state, mobile drawer) so that side-by-side screenshots at the three viewports are visually indistinguishable in layout, spacing, typography scale, and motion. Use a system/Inter font stack (ChatGPT's own font is proprietary).
4. THEN the "minor changes" that make it ours: TrustShield name and logo (`apps/web/public`), the white theme with one accent colour, our copy ("Message TrustShield..."), our suggestion cards, and the existing risk card/evidence UI restyled to fit. Do NOT copy OpenAI/ChatGPT logos, names, proprietary fonts/icons, copy text or source code; observe, then implement from scratch.
5. Acceptance (all with evidence): side-by-side + pixel-diff overlay at 3 viewports; no layout shift while streaming (record CLS < 0.02); Lighthouse a11y >= 95 and performance >= 80 on `/`; `npx tsc --noEmit`, `npm run lint` (the 2 errors of issue #18 FIXED, zero errors), `npm run build`; Playwright E2E: send a chat message and see a streamed reply, scan a scam text, attach a file, login-gate skip, sign-in/out, history isolation, mobile drawer. Fix issue #18 properly (no eslint-disable).

### Lane B - leftovers
1. W3 uploads in the UI (issue #3): composer attach menu accepts images (PNG/JPEG/WebP/HEIC), PDF, DOCX, XLSX, CSV, TXT; per-file chips with status/progress/error and remove; no glitching on repeated/failed uploads; works at 390x844 with a real mobile-viewport Playwright test; clear errors for corrupt/oversized/password-protected files. Backend contract tests stay green.
2. W4: check `pnb.co.in` (scores 34 domain_mismatch): decide from repository evidence whether it is an official PNB domain; fix only with evidence, with a regression test. Add real legitimate 3-5 letter domains to the fixture. Do not retrain, do not touch spent data.
3. W7: update `docs/presentation/` (build_deck.py, TEAM_NOTES*, PRESENTATION_SCRIPT, the .pptx) with the current honest numbers (1280 tests; sealed-holdout numbers unchanged; the W4 fix; uploads; do NOT claim model-level security results from the 24 Oct run), and render the deck to check slides for overflow if LibreOffice is available (it was used on 24 Oct: `work/finals/w7-final/lo_profile`).
4. FINAL-REPORT.md: append a "2-hour run" section with honest status.

### Rules that stay
Commit and push after every phase or medium task (small commits, message says what and why). Full pytest suite before every acceptance commit. Spent v1-test/v2 data untouched. No production changes and no Vercel commands by you: the head deploys after your freeze. Re-read this file every 30 minutes. Keep HEARTBEAT.txt/STATE.md current. No fallback models. Never `taskkill /IM`.

> HEAD DEFECT 03:45 IST (UI, blocking for W2 acceptance): my own Playwright screenshot of the local dev build `http://localhost:3000/` at 1536x769 (`C:\Users\shubh\c language\local-home-1440-0340.png`) shows the empty state NOT centred in the main column: the title "What should I check for you?" and subtitle are centred around x~644 while the composer is centred around x~898 (main column 260-1536), the four suggestion cards sit left of the composer (x 276-1012), the composer is at y~380 instead of the measured reference position, the sidebar "New chat" button lost its bordered pill, and the text-colour/weight of the cards is heavier than the reference. The live production site (older build) looks more correct than this. Fix: lay the empty state out with ONE centred column (max-width 768, mx-auto) containing title, subtitle, cards and composer; verify at 1280, 1440, 1536 and 1920 widths against the reference capture, and add a Playwright assertion that title/cards/composer share the same horizontal centre (+-2 px). Do not accept W2 on screenshots taken only at 1440 or 390.
