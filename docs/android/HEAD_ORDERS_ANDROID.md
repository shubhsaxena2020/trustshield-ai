# HEAD ORDERS - ANDROID PROGRAM (issued 2026-10-25 by Claude Code, head)

Owner: Shubh Saxena (team leader, Team Scam Scan). Deadline: final screening **30 Oct 2026**. Usable time: about 3-4 days from now. Branch: `android-finals` (from `finals-48h`). Repo: `C:\Users\shubh\trustshield-ai`, GitHub `shubhsaxena2020/trustshield-ai` (private).

## 0. Decisions already made by the owner (do not re-litigate)
1. All new features from `docs/android/SOURCE_REPORT_senior_dev.md` (a senior developer's upgrade report; read it fully, it is the spec) ship **inside an Android app**.
2. The website is **paused** (leave production untouched; no website deploys). If time remains after the app, build a small download/landing page on the site that links the APK.
3. Distribution: sideloaded **APK** (the report itself says Play Store is too strict for notification-listener permissions). A signed release or debug-signed APK is fine; it must install and run on a real phone.
4. Quality bar: industry-level, smooth, customisable (theme, language, sensitivity, haptics), as little glitchy as possible.
5. The Chrome extension and the iPhone path stay roadmap.

## 1. Roles
- **Claude Code (head)**: monitors, corrects, never lets a stage fail silently. Uses few of its own tokens.
- **Codex (you, orchestrator)**: plans, reasons deeply, reviews and verifies. Phase 1 runs on `gpt-6-sol`; later phases on `gpt-6-luna`.
- **Hermes**: three sessions (profiles `agent1`, `agent2`, `agent3`), each running up to **9 parallel subagents** (27 total). Model: `nilabh/Claude-opus-4-6-thinking` via 9Router. Owner may later raise to 18 per session; plan work packages so that raising it is a config change only (`delegation.max_concurrent_children`). Launch with `hermes -p agentN -z "<prompt>" --yolo` and never pass `--provider` or `-m`.
- Every agent (Codex and each Hermes worker) must **read the whole project context first** (list in section 3), restate the task, verify its own plan before acting, and verify its own output after.

## 2. Hard rules (carry over from the finals run, plus the report's golden rules)
1. Never fetch, open or execute a URL, QR destination, attachment or APK under analysis.
2. The LLM never sets a verdict. It may only fill a fixed schema; every `true` field needs an exact quote verified by code as a normalised substring of the input; unverified fields are dropped. Verdict = deterministic engine. (Slides/claims must say "the verdict is always deterministic", not "AI only explains".)
3. Every signal is an evidence item `{id, module, title_hinglish, detail, severity, strength, quote}`. Risk score and confidence stay separate. Never say "100% safe"; unsupported language or unreadable input -> lower confidence / "Unable to Verify".
4. No raw audio, image or message stored. Mask OTP, PIN, card numbers, phone numbers, UPI IDs before any logging. Check the existing Supabase scan-history path against this claim and report any conflict honestly.
5. Official APIs only (no unofficial WhatsApp libraries). No deepfake-voice-detection claim.
6. All user-facing text in simple Hinglish (plus English toggle).
7. Honest numbers only. Keep: holdout recall 82.1 / specificity 78.3 / precision 82.1 / F1 82.1 (90% target not met); routing audit 79/79 with 7 false alarms; 1,280 tests. Do not claim a new model number unless it is measured on a clean holdout (a real 300-message holdout is not possible in this window; Hinglish gains must be reported as rule/normaliser tests, not accuracy).
8. Never touch: `datasets/` holdouts, `models/evaluation/test*`, `models/trained/`, `.env` files. Never retrain v3. No `taskkill /IM`. Never search from `/`.
9. Existing 1,280 tests must keep passing; every change ships with tests.
10. Do not deploy the website. Do not rotate keys. Secrets only in `.env`/gradle local properties, never committed.

## 3. Context every agent must load first
`README.md`, `FINAL-REPORT.md`, `STATE.md`, `docs/finals/*.md` (especially MISSION, HEAD_ORDERS, REQUIREMENTS, BEHAVIOR_SPEC, QUALITY_GATES), `docs/android/SOURCE_REPORT_senior_dev.md`, `Trustshield_AI_Team_Roles.md`, `services/` (rules, fusion, chat, extract), `extension/` scaffold, the FastAPI routes the app will call, and `apps/web` only for design tokens/copy. Toolchain facts (verified by Claude Code): JDK 17 present; Android SDK at `C:\Users\shubh\AppData\Local\Android\Sdk` with platform android-36 and build-tools 34/36 and platform-tools, but **no Gradle on PATH, no ANDROID_HOME set, no emulator image confirmed, no phone attached over adb**; Node v24; Python 3.13. Git Bash is the shell; use `run_in_background` style only for long jobs.

## 4. PHASE 1 (you, Codex, model gpt-6-sol): research + master plan. NO app code yet.
Do deep research, deep reasoning, deep scanning of the whole project and the whole source report, web searches (current Android best practice, SpeechRecognizer hi-IN vs on-device Whisper/Vosk options, ML Kit barcode, notification listener policy, Share target intents, Compose vs alternatives), and heavy self-review. Then write into `docs/android/` the following files (mirror the structure of `docs/finals/`):

1. `MISSION.md` - one page: goal, scope, non-goals, success definition for 30 Oct.
2. `REQUIREMENTS.md` - numbered functional + non-functional requirements traced to report sections.
3. `ARCHITECTURE.md` - the decision: native Kotlin + Jetpack Compose vs Flutter vs React Native/Capacitor, judged on build-speed in 3 days, camera/mic/notification-listener access, APK size, smoothness, and **what can be built and tested with the tools actually installed**. Define the on-device vs backend split (rules + normaliser on-device so it works offline; backend API reuse where the existing engine already lives). Recommend one; justify.
4. `FEATURE_PRIORITY.md` - every report feature ranked, with a hard cut line for the time available, effort estimate in agent-hours, risk, and the demo value. Report says Hinglish and Call Shield never get cut. Decide the QR Guard (phone camera as the watcher instead of ESP32 hardware) and what goes on the roadmap.
5. `PLAN_2DAY.md` - the 2-day final roadmap (plus a 1-day buffer) in time-boxed blocks with the 3 Hermes sessions x 9 subagents mapped to concrete work packages (disjoint file ownership to avoid merge fights). State which packages stay valid if the owner raises to 18 per session.
6. `WORK_PACKAGES.md` - one block per package: owner session, files owned, inputs, definition of done, tests, edge cases. Include a dependency graph.
7. `UI_SPEC.md` - screens, navigation, design tokens (blue #2F6BFF accent, risk colours green/amber/red), motion, accessibility, theme/language/sensitivity/haptic customisation, empty/error/offline states. Industry-level polish checklist.
8. `BEHAVIOR_SPEC.md` - the scoring behaviour: 5-stage playbook, conversation mode, legit-vs-scam pairs from report section 3.4, Call Shield stage persistence, negation handling.
9. `TEST_SUITE.md` - unit, integration, instrumented/UI, and demo-rehearsal tests; the report's edge-case list as test cases; how it is run without a physical device if none is available.
10. `QUALITY_GATES.md` - merge gates, lint, build-green, no-crash monkey run, APK size, cold start, memory.
11. `FAILURE_AND_RECOVERY.md` - what happens when Hermes/9Router/Nilabh/Codex fail; rollback points; safety snapshots.
12. `WORKFLOW.md` - git protocol (section 5), review protocol, handoff protocol between Codex and Hermes.
13. `TOOLS_AND_MCP.md` - exact commands that work on this machine (Gradle bootstrap, sdkmanager, emulator/AVD creation if possible, adb, signing, apk build), with each command **tried and verified** by you where feasible.
14. `NEEDS_SHUBH.md` - things only the owner can do (phone with USB debugging, Telegram bot token, WhatsApp, any purchase) with a deadline and a fallback for each.
15. `RISKS.md` - top risks, probability, mitigation.
16. `ORCHESTRATOR-LOG.md` - append-only log of every step you take.

Also:
- Prove the toolchain: bootstrap Gradle, set ANDROID_HOME for the session only, and build a hello-world Compose APK in `apps/android/` to confirm an APK can actually be produced on this machine. Commit it. If it cannot, say so plainly and propose the nearest workable alternative.
- Review the report critically: list any claim in it that is wrong, outdated or unsafe (for example Meta WhatsApp pricing and window rules, Play Store policy, Whisper RAM) and fix the plan accordingly.
- A short `PLAN_SUMMARY.md` (max 60 lines) that Claude Code and the owner can read in two minutes.

Phase 1 is done when all files exist, are consistent with each other, the hello-world APK is built (or its impossibility documented), and you have re-read your own plan once for contradictions. Then append "PHASE 1 COMPLETE" with the commit hash to `ORCHESTRATOR-LOG.md` and stop. Do not start Phase 2 until Claude Code reviews the plan.

## 5. GitHub protocol (the owner flagged GitHub is under-used; fix that)
- Work on `android-finals`; short-lived feature branches `android/<package>` merged by PR into `android-finals`. Commit after every meaningful unit (small, descriptive messages; conventional prefixes `feat:`, `fix:`, `test:`, `docs:`), push after every phase or medium chunk.
- Use `gh` fully: open one **issue per work package** (labels `android`, `p0/p1/p2`), reference the issue in commits/PRs (`Refs #N` / `Closes #N`), open PRs with a checklist, comment results, close issues when done. Existing open issues (#2, #3, #4) stay as they are unless a package resolves them.
- Safety snapshots before risky operations (temporary `GIT_INDEX_FILE` + `git commit-tree` to `backup/android-wip-*`), never force-push, never rewrite pushed history.
- Keep an up-to-date `docs/android/STATE.md` after each phase.

## 6. Efficiency orders to all agents
Work at best efficiency; analyse each problem from several angles and try the alternatives before settling; verify every claim by running something; prefer measured evidence over assertion. When a Hermes worker's output is weak, Codex corrects it; when Codex's is weak, Claude Code corrects it. Report failures immediately rather than papering over them.
