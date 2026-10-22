# Requirements

## Operating requirements

1. Stay on `finals-48h`; only the orchestrator commits. Commit small green milestones and push only after verification.
2. Do not alter production, Vercel, DNS, environment settings, paid services, or user accounts.
3. Do not read, train on, tune on, or evaluate the v1 test split or v2 holdout. Do not run an unguarded script capable of touching them. Keep v3 and threshold `0.16` unchanged.
4. Any new model candidate uses train/validation only, gets a newly sealed and hashed holdout, and leaves it unopened pending Shubh's approval.
5. Disable live external browsing for the app; never open a submitted URL. Read-only production checks are allowed.
6. Do not install system software, add LLM providers, expose secrets, or stop processes not started by this run. Never use `taskkill /IM` or destructive git operations.
7. Before every Hermes launch: RAM >= 12 GB, CPU not pinned, laptop not hot; no more than two launches per minute and no more than two sessions/nine child agents per session.
8. Keep `HEARTBEAT.txt` refreshed every 15 minutes, `STATE.md` every 30 minutes, and append decisions/events to `ORCHESTRATOR-LOG.md`.
9. Claims require evidence from commands, files, browser observations, or fixtures. Reports and agent consensus do not establish success.

## Product requirements

- W1: repair and verify auth, signup/login/logout, confirmation/error/expiry paths, first-visit Skip gate, identity-bound history, duplicate submits, slow network, and mobile layout; retain anonymous scans.
- W2: measure reference interaction/layout behavior using REA, or document a Playwright-only fallback; write `UI_SPEC.md`; implement TrustShield's own branded chat UI.
- W3: robust uploads for images, PDF, DOCX, XLSX/CSV, TXT, and QR images; safe extraction, bounded resources, file status/errors, fixtures, browser/mobile verification.
- W4: permitted improvements only on train/validation and fixed regression links; include leave-one-source-out and calibration checks when a candidate is evaluated; no false positive on official domains; no holdout claims without a new sealed holdout.
- W5: MV3 extension with Allow/Warn/Block policy, never-block protections, local optional saved-site checks, offline mode, reversible ad/link markers, least permissions.
- W6: native Kotlin/Compose Android app, minSdk 26, existing API, listed scan/chat/history/settings/offline flows.
- W7: 256-prompt injection and 35-prompt routing regressions; demo flow, recording script, offline fallback, current presentation facts, final report.

## Acceptance authority

Exact per-workstream pass criteria are in `QUALITY_GATES.md`. A gated dependency is not waived to meet the calendar. Unavailable secrets, hardware, external APIs, or reference tools are documented in `NEEDS_SHUBH.md` and the final report.
