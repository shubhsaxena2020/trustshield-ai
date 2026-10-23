# Items needing Shubh

No decision blocks Phase 0. The team already supplied the product and safety decisions in `MISSION.md`; do not reopen them.

## Approval-dependent items

- If W4 produces a new model candidate: review the frozen candidate, data sources/licences, criteria, and hash of a newly sealed holdout before anyone opens/evaluates it. The holdout must remain unopened during this run.
- Supply/authorize a test account or mailbox only if real Supabase email-confirmation/password-reset flows cannot be verified with existing local test fixtures. Do not place credentials in this file; provide them through the approved secret mechanism.
- Decide whether to pursue any acceptance gate that requires a missing emulator/device or external account after the exact local build/test gap is documented.

## Opened during execution

Append concrete item, why Shubh is required, evidence already gathered, and the exact decision/action needed. Continue independent work while waiting; do not use this file for routine implementation choices.

## Tool-policy blocker (2026-10-23)

- Claude's head order asks to restart the W1-owned API PID 29800 so it reloads .env (modified 05:29:33 local); the API responds 200 but may have stale chat routing. The tool policy rejected the attempted process stop/restart. I did not stop any process. Current safe next step: wait for W1 to finish/release port 8000, then start a new local API with --env-file .env. If the port remains occupied afterward, Shubh needs to restart or authorize an alternative.

## PR and deployment boundary (2026-10-23)

- HEAD_ORDERS.md requests a rolling PR from finals-48h to main. .github/workflows/ci.yml runs CI on pull_request and contains no deploy action, but this checkout does not establish whether Vercel Git integration would create a preview deployment. MISSION.md prohibits Vercel deploys. No PR has been opened. Shubh must confirm whether Vercel previews are disabled/acceptable before opening the rolling PR.


## W1 auth evidence gap (2026-10-23)

- **What is unverified:** real Supabase email-confirmation delivery (does this project require confirmation? does the delivered link land on a working callback?) and real token refresh against the live project. The local API runs with `SUPABASE_URL`/`SUPABASE_SERVICE_ROLE_KEY` unset by design, and no credentials or mailbox were used, so neither flow was exercised against the real service.
- **What IS verified locally (mocked, deterministic):** `tests/e2e/test_finals_w1_auth.py` mocks `/auth/v1/signup`, `/auth/v1/token` (password + refresh_token grants), `/auth/v1/logout`, `/auth/v1/user` and covers both branches — confirmation required (no session, "Check your email" state) and auto-confirm (session returned, redirect to `/`) — plus expired-session refresh success, failing refresh → signed-out, and duplicate-submit invariants. All 92 checks pass across 3 consecutive runs in `docs/finals/evidence/w1/w1-run.json`.
- **Exact decision/action needed from Shubh:** either (a) confirm the Supabase project's "Confirm email" setting and provide a disposable test mailbox/alias through the approved secret mechanism so the real delivery + link callback can be run once, or (b) accept the mocked coverage as sufficient for the finals and keep email confirmation disabled for the demo account. No credentials are stored in this repo or in this file.
