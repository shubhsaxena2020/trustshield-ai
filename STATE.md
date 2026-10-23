# Finals run state

- Launch time (T0): 2026-10-22T23:48:19Z (UTC)
- Branch: finals-48h; remote branch pushed through commit 2ac089c.
- Phase: W1/W2 execution
- Lane A: W1-auth; session 2238; Hermes PID 13880; worker Python PID 23624; nine child scopes; local API PID 29800 on 8000 and web PID 28236 on 3000. Package output remains buffered.
- Lane B: W2-discovery; session 37963; Hermes PID 22560; worker Python PID 27928; REA enabled, no child fan-out observed yet. Package output remains buffered.
- Baseline: `.venv\Scripts\python -m pytest -q` -> 802 passed, 7 skipped, 5 warnings, 181.26 s.
- Head order acknowledged: Nilabh rate gate PID 25944 listens on 127.0.0.1:20190; target 26 rpm and 8-12 active agents. Current effective fan-out is W1 nine children plus W2 parent. No more session launches until lane readiness/rate gate review.
- API PID 29800 predates `.env` mtime 05:29:33; health endpoint returned 200. Stop/restart was rejected by tool policy; see NEEDS_SHUBH.md. Do not retry termination. Wait for W1 to release port.
- GitHub: issues #1-#7 created by workstream, #8 tracker pinned; initial progress comments on #1/#2. No PR opened: a pull_request triggers CI, and possible Vercel preview deployment has not been ruled out under the no-deploy mission rule. See NEEDS_SHUBH.md.
- Next: monitor active lanes; review their reports and diffs; independently rerun acceptance.
- Pre-existing untracked paths remain untouched: .playwright-mcp/, agent-workspaces/, docs/goals/, docs/reports/, runs/, security progress/restart logs, and Uvicorn logs.
