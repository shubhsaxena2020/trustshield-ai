# Finals run state

- Launch time (T0): 2026-10-22T23:48:19Z (UTC)
- Branch: finals-48h; remote pushed through c28e49d; local status/docs commits remain unpushed.
- Phase: W1/W2 execution; no worker package accepted yet.
- Lane A: W1-auth; command session 2238; Hermes PID 13880; worker Python PID 23624; nine child scopes. Changed but uncommitted: login/signup pages, auth/API/history code, auth tests and new W1 browser matrix. Package run log remains empty.
- Lane B: W2-discovery; command session 37963; Hermes PID 22560; worker Python PID 27928; REA enabled. Seven observation child folders are active (desktop shell, composer, messages, streaming, responsive, auth, accessibility); captures/probes are arriving. No accepted UI_SPEC.md/report yet. Package log remains empty.
- Baseline pytest: 802 passed, 7 skipped, 5 warnings in 181.26 s.
- W1 first browser run: 72 pass, 15 fail, 1 skip; invalid test setup. Corrected matrix is now 92 pass, 0 fail, 0 skip (generated 00:18:36Z); one green run only, not accepted pending two more consecutive independent runs. No .env.local read/printed. Expected mocked 400/422 responses must be distinguished from unexpected errors.
- Defects filed: #9 duplicate auth submission, #10 auth error field association, #11 self-referential sans font. Issues #1-#7 are workstreams; #8 pinned tracker. Initial progress comments on #1/#2.
- Head orders latest verified: local proxy PID 4124 listens on 127.0.0.1:20190 at 120 requests/minute; upstream cap was removed. Keep all calls through the proxy because direct 30-connection bursts had TLS handshake drops. Up to 2 sessions x 9 children allowed. Observed active work is W1 nine child scopes + W2 seven observation scopes (about 16 total).
- W1 replaced its own prior API process; PID 27952 (descendant of W1 worker 23624) owns 127.0.0.1:8000 and `/api/v1/health` returned 200 at 00:28Z. An earlier stop request was rejected by tool policy; no further termination attempts.
- GitHub: finals branch pushed; PR deferred because CI runs on pull_request and Vercel preview behavior is unknown under the no-deploy rule; see NEEDS_SHUBH.md.
- Next: monitor sessions; review complete reports/diffs; rerun corrected W1 matrix 3 times and independently verify.
- Pre-existing untracked paths remain untouched: .playwright-mcp/, agent-workspaces/, docs/goals/, docs/reports/, runs/, security progress/restart logs, and Uvicorn logs.

> HEAD UPDATE 05:46: rate limit removed upstream. Gate restarted as PID 4124 at 120 req/min; full 2x9 fan-out is allowed. See docs/finals/HEAD_ORDERS.md section 4.
