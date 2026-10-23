# MISSION: TrustShield AI - 48-hour finals program (you are Codex, the ORCHESTRATOR)

> **READ `docs/finals/HEAD_ORDERS.md` FIRST - it supersedes this file where they differ (rate limit gate, full GitHub workflow).**

Written 2026-10-23 by Claude Code (the head) on behalf of Shubh Saxena (team leader, team "Scam Scan").
The final screening that decides first prize at PSIT Tech Expo 2026 is on **30 October 2026**. Today is 23 October.
Shubh is asleep for most of this run. Nobody will answer questions. Make minor decisions yourself, write anything
that truly needs him into `docs/finals/NEEDS_SHUBH.md`, and keep working on everything else.

## 1. Command structure (Shubh's design - follow it)
- Claude Code = head. Reads your STATE.md and HEARTBEAT.txt, re-verifies, relaunches you if you stop, reports to Shubh.
- **You (Codex) = orchestrator.** You plan, dispatch, verify and review. You do NOT write the bulk of the code.
- **Two Hermes sessions, A and B, run concurrently.** Each session fans out to **at most 9 parallel subagents**
  using Hermes' `delegate_task` tool (verified 2026-10-23: one session ran 9 children in parallel, 9/9 returned, 17 s).
  That is up to 18 subagents total. Never more than 2 Hermes sessions at once, never more than 9 children per session.
- A "session" is a lane slot. A lane runs one work package at a time as one `hermes -z` call; when a package is verified
  you launch the next package into the same slot. Keep both slots busy.
- Launch (the Bash tool's own `run_in_background: true` only - NEVER wrap in `nohup` or add `&`):
  `hermes -z "<contents of PROMPT.txt>" --yolo --in "C:\Users\shubh\trustshield-ai\work\finals\<pkg>" > "<pkg>\run.log" 2>&1`
  Do NOT pass `--provider` or `-m` (the config default is the Nilabh DeepSeek route; passing them rewrites the config).
  Write each prompt to `<pkg>\PROMPT.txt` first: goal, exact files it owns, acceptance tests, "write REPORT.md".
  Prompt every package to split its work across 9 `delegate_task` children with disjoint files, then to merge and self-review.
- Hermes buffers output until the end; zero output for 10+ minutes is not by itself a hang. Budget 40 minutes per
  package; if `run.log` is empty AND the process has used no CPU for 15 minutes, kill ONLY that PID (never `taskkill /IM`).
- Hermes workers and children claim success too easily. A finished process or a REPORT.md is NOT evidence. You re-run
  the tests, open the screens in a real browser, read the diff, and send defects back (max 4 rounds per package, then
  record why and take the next-best option). Check rationales/outputs for templating or gaming, not only counts.
- Code quality bar: write real, readable, tested code in the style of the surrounding repo. No AI slop, no placeholder
  functions, no dead code, no comments narrating the obvious, no fake data presented as real. Every change traces to a goal below.
- Keep your own token use low (Shubh's Codex quota is limited): plan, verify, review; push bulk work to Hermes.

## 2. Hard rules (non-negotiable)
1. Work only on branch `finals-48h` in `C:\Users\shubh\trustshield-ai`. Only YOU commit (small, verified commits with clear messages). You may `git push origin finals-48h` after each green milestone (private repo, backup).
2. **No production changes.** No `vercel` deploys or promotes, no Vercel env changes, no DNS, no paid services, no new accounts.
   Production https://trustshield-ai-ecru.vercel.app stays exactly as is. Local and branch work only. Read-only curl checks of prod are fine.
3. **Spent data stays spent:** the v1 test split and the v2 holdout (`datasets/holdout/trustshield-text-v2-holdout`) must never be read,
   trained on, tuned on, or evaluated. Read `docs/SPENT_DATA_POLICY.md`. Never run scripts that could touch them to "test" a guard
   (grep for the guard first; chain commands with `&&`, not `;`). `trustshield_text_v3` is never retrained or overwritten; threshold 0.16 unchanged.
   Any new model is a NEW candidate and needs a NEW sealed holdout that is created, hashed, and left UNOPENED for Shubh's approval.
4. Live external browsing in the sandbox stays disabled. The app never opens user links.
5. Never `taskkill /IM`. Stop only PIDs you started. No uncontrolled malware. No destructive git (no reset --hard, no force push).
6. Do not install system-wide software. Project-local installs (npm, pip into `.venv`, Gradle wrapper, Playwright browsers) are fine.
   The Android SDK already exists at `C:\Users\shubh\AppData\Local\Android\Sdk` (JDK 17 is installed); set `ANDROID_HOME` yourself in the shell.
7. Never print or commit secrets. `.env` is git-ignored; its chat settings now point at the new Nilabh endpoint (`Deepseek-v4.1-Flash`, case-sensitive).
   Do not add fallback LLM providers. The chat model is only the Nilabh DeepSeek.
8. Resource gates: before every Hermes launch check free RAM >= 12 GB (`Win32_OperatingSystem`) and that CPU is not pinned;
   if the laptop is hot or RAM is low, wait. Ramp at most 2 launches per minute. Ports: API 8000, web 3000 (check free, stop only your own).
9. Honesty: never claim accuracy that was not measured on data that was not used to tune. Existing numbers (sealed holdout recall 82.1 / spec 78.3, 90% target NOT met) stay as they are.
10. UI cloning limit: replicate the LAYOUT, spacing, motion and interaction patterns of ChatGPT (web) and the Claude Android app.
    Do NOT copy their logos, brand names, proprietary fonts/assets, copy text, or source code. TrustShield keeps its own white theme and logo
    (`apps/web/public`). Use the REA skills (see W2) for observation; implement from scratch.

## 3. Verified starting state (2026-10-23, by Claude Code)
- Repo HEAD `8d143e8` = pushed to origin; branch `finals-48h` is cut from it. Tests last green: 802 (11 Oct) - re-run first: `.venv\Scripts\python -m pytest -q`.
- Production serves the ChatGPT-style white UI (Next.js `apps/web`, FastAPI `services/api/main.py`). Supabase email/password auth is optional; history only for signed-in users.
- Read first: `docs/PROJECT_ARCHITECTURE_EXPLAINER.md`, `docs/AUTH_AND_HISTORY_ARCHITECTURE.md`, `docs/UI_UX_REDESIGN_NOTES.md`, `docs/TECH_STACK.md`,
  `docs/presentation/TEAM_NOTES.md`, `docs/presentation/TEAM_NOTES_DEEP.md`, `docs/SPENT_DATA_POLICY.md`, `docs/ACCURACY_VALIDATION.md`,
  `docs/V4_RESEARCH_PLAN.md`, `docs/HYBRID_V4_SELECTION_POLICY.md`, `docs/MULTI_AI_REVIEW_LOG.md`, and Shubh's vault note
  `C:\Users\shubh\Second Brain\TrustShield AI - session resume (2026-10-10).md` (read-only).
- Known weak spots: login/signup pages look rough and sometimes glitch; UI still not a faithful ChatGPT-class experience;
  uploads sometimes glitch in the chat box and only images are accepted; classifier recall/specificity 82/78; look-alike links `sbl.co.in`, `irctc-refund.xyz`, `sbii.co.in`, raw-IP login paths still under-scored.

## 4. Phase 0 (first 60-90 minutes, BEFORE anything else): plan documents
Shubh goes to sleep about one hour after launch, so the plan must exist and be sane by then.
1. Create `docs/finals/` with the project's standard 11 documents, adapted to THIS program (concrete, short, no filler):
   `SKILL_BRIEF.md` (goal and mental model), `REQUIREMENTS.md`, `BEHAVIOR_SPEC.md` (verify, don't trust), `WORKFLOW.md` (dispatch/verify/review loop),
   `TOOLS_AND_MCP.md` (exact Hermes/Playwright/REA/Gradle commands that work here), `PROMPT_ARCHITECTURE.md` (how worker prompts are built),
   `CONTEXT_MODEL.md` (what each lane may read/write), `FAILURE_AND_RECOVERY.md` (hangs, rate limits, power-off, quota),
   `QUALITY_GATES.md` (per-workstream pass criteria), `TEST_SUITE.md` (what is run, how, expected), `EVOLUTION_PLAN.md` (roadmap beyond 48 h).
   The framework's originals are in `C:\Users\shubh\.claude\skills\multi-ai-framework\` - use them as templates.
2. Create `docs/finals/ROADMAP.md`: done / left / planned for every item in section 5, with owners (lane A or B) and dependencies.
3. Create `docs/finals/PLAN-48H.md`: a continuous, hour-by-hour plan for 48 hours from launch (T0 = launch time, record it), with gates,
   buffer blocks, and the explicit "never cut" items. Both Hermes slots busy at all times; show which package runs in which slot each hour.
4. Create `STATE.md` (update every 30 minutes), `ORCHESTRATOR-LOG.md` (append-only), `NEEDS_SHUBH.md`, and `HEARTBEAT.txt` (one line: ISO time + current packages;
   rewrite it every 15 minutes - Claude Code uses it to detect that you are alive).
5. Commit the docs on `finals-48h`. Then start executing PLAN-48H.md. Do not wait for approval.

## 5. Workstreams, in priority order (Shubh's list, with the head's decisions)

### W1 - Backend + login/signup: fix first
- Reproduce every glitch with Playwright (real browser; Chrome profile use only if needed - Shubh's Chrome may be closed). Cover: sign-up, sign-in, sign-out,
  password errors, email-confirm flow, token expiry/refresh, first-visit gate with Skip, history bound to identity (logged-out = no leaked history), double clicks, slow network, mobile viewport.
- Rebuild the login and signup screens to a clean, edge-to-edge, ChatGPT-class layout in TrustShield's white theme. Fix backend auth/history edge cases found.
- Acceptance: a written Playwright test script covering the matrix above passes 3 consecutive runs; `pytest` green; no console errors; screenshots in `docs/finals/evidence/w1/`.

### W2 - Web UI to a faithful ChatGPT-class experience (REA + reverse engineering)
- Process (Shubh's order): YOU first write the REA/reverse-engineering instruction, then hand it to the Hermes agents. Use the skills at
  `C:\Users\shubh\Projects\rea\.agents\skills\reverse-engineer-anything` (and `rea-tool-design`); the REA MCP is configured in Hermes. If REA is unavailable,
  fall back to Playwright capture of the public ChatGPT web UI (computed styles, spacing scale, layout tree, animation timings, keyboard shortcuts, responsive breakpoints) and record the method used.
- Deliver a measured spec (`docs/finals/UI_SPEC.md`: layout grid, sidebar, composer, message rendering, streaming caret, code blocks, copy/regenerate/edit, empty state, scroll behaviour, mobile drawer, loading and error states, accessibility), then implement it in `apps/web` with TrustShield's own theme/logo/copy.
- Acceptance: side-by-side screenshot comparison at 3 viewports, smooth 60 fps scrolling with no layout jump while streaming, Lighthouse accessibility >= 95, existing chat/scan behaviour unchanged, `npx tsc --noEmit`, lint and `npm run build` green.

### W3 - Uploads
- Fix the glitching chat-box upload. Accept images (PNG/JPEG/WebP/HEIC), PDF, DOCX, XLSX/CSV, TXT, and QR images. Extract text safely (no macros, no external fetch, size/page/zip-bomb limits, no path traversal, timeouts), route extracted text through the existing scanner pipeline, and show per-file status and errors in the UI.
- Acceptance: a fixture set (clean, malicious-looking, corrupt, oversized, password-protected, macro-enabled, mismatched MIME) with unit + browser tests; mobile upload verified; no crash on any fixture.

### W4 - Accuracy
- Allowed: new data sourcing with verified licences, hard-negative mining, error analysis on TRAIN/VALIDATION only, better rules for the known misses (`sbl.co.in`, `irctc-refund.xyz`, `sbii.co.in`, raw-IP login paths) with regression tests, a new candidate model trained on train+validation only, leave-one-source-out checks, calibration.
- Forbidden: touching spent data, retraining or replacing v3 in the repo defaults, changing the 0.16 threshold, tuning on a holdout. Create a NEW sealed holdout for any candidate, hash it, and leave it unopened; list it in NEEDS_SHUBH.md.
- Acceptance: documented before/after on validation and on a fixed real-link regression set (>= 60 links: official bank/college/government vs phishing look-alikes, shorteners, tunnels), no new false positive on official domains.

### W5 - Chrome extension (MV3), after W1-W3 are green
Head's design decisions (implement these, do not re-litigate):
- Purpose: tell the user whether the site they are on is safe, block dangerous ones, and cut suspicious ads/links, WITHOUT the usual blocker problem of breaking sites people rely on.
- Three tiers, not a blocklist: **Allow** (default), **Warn** (interstitial banner, one-click continue), **Block** (full interstitial, user can always unblock or "allow this site"). Block only when risk >= 85 AND at least two independent strong signals agree
  (e.g. Safe Browsing listed + brand look-alike, or tunnel host + credential lure). Everything else warns or allows.
- Never-block protections: a built-in allowlist of official domains (banks, `.gov.in`, `.ac.in`, `.edu`, major platforms), the TrustShield site itself, and any site the user has visited on many separate days (habit trust) or manually allowed. User allow/block lists sync in `chrome.storage`.
- Scan inputs: the current tab URL and navigations, plus an opt-in "scan my saved sites" that reads the browser's history/bookmarks LOCALLY (permission requested only when the user turns it on) and checks domains in batches. Cookie access is limited to domain names, never cookie values. Nothing leaves the device except the registered domain sent to the TrustShield API (and Google Safe Browsing through it), same privacy promise as the web app. Provide a fully offline mode (bundled rules only).
- Ad/suspicious-link blocking: `declarativeNetRequest` static rulesets (curated, licence-checked list subset) plus a content script that flags scam-style ads and links on a page with a visible, reversible marker. Per-site pause switch. Never remove content the user asked for without a way to restore it.
- Acceptance: loads unpacked in Chrome via Playwright persistent context; tests prove (a) official bank/college/Google/GitHub/etc. are never blocked, (b) known phishing-pattern fixtures are warned/blocked, (c) unblock works and persists, (d) offline mode works, (e) no permission beyond what the features need. Provide `extension/` source, build script, README and a privacy statement.

### W6 - Android app (after W5 is functional)
- Native Kotlin + Jetpack Compose (light and smooth), minSdk 26, in `android/`. Backend: the existing TrustShield HTTP API (configurable base URL). Features: chat with streaming, scan text/links/screenshots/QR/files, share-sheet "Scan with TrustShield", history (signed-in), settings (theme, server, privacy), and clean error/offline states.
- UI model: the interaction design of the Claude Android app (navigation, composer, message list, drawer, animations) re-expressed in TrustShield's own white theme, logo and copy. Observe with the REA skill / emulator screenshots of public material; copy no assets or branding.
- Acceptance: `gradlew assembleDebug` succeeds with the existing SDK; unit tests pass; if an emulator or device is available run an instrumented smoke test, otherwise document exactly what was not run. Do not install system software; if the Gradle wrapper needs to download project-local dependencies, that is fine.

### W7 - Cross-cutting
- Security regression: re-run the 256-prompt injection battery and the 35-prompt routing battery against the branch; fix any regression. Keep per-IP rate limits. Re-check upload and extension attack surface.
- Demo readiness for 30 Oct: a rehearsed 5-minute flow still works end to end locally (`docs/DEMO_PLAN.md`), a screen recording script, an offline fallback, updated deck/notes numbers (`docs/presentation/`). Do not claim anything unmeasured.
- Final: `FINAL-REPORT.md` listing done / not done / evidence / risks, with exact commands to reproduce each claim.

## 6. Failure policy (make this run survive the night)
- Rate limit or 5xx from the chat route (Nilabh): retry with backoff (30 s, 60 s, 120 s), reduce the number of concurrent children for that slot, never add a different model.
- Hermes slot dead or silent: kill only that PID, relaunch the package once with a smaller scope, log it.
- Laptop power-off or crash: on restart read STATE.md and resume; nothing is lost on disk because you commit often.
- Anything you are unsure is safe to do on the production site, spent data, secrets or the user's other projects: DON'T; log it in NEEDS_SHUBH.md and move to the next item.
- Finish condition: all of W1-W4 and W7 accepted by you with evidence, W5/W6 at the best verified state, FINAL-REPORT.md written, branch pushed. If you finish early, use remaining time on deeper review and test hardening, not new scope.
