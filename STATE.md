# Finals run state

- Launch time (T0): 2026-10-22T23:48:19Z UTC
- Branch: finals-48h; latest verified/pushed milestone: 9a831b3.
- Last update: 2026-10-23T01:25Z UTC.
- Phase: W2 discovery evidence reviewed; W2 UI implementation active in Lane B. W3 safe extractor work active in Lane A.

## W1 - local acceptance restored

- Isolated Playwright auth matrix: 3 consecutive runs, each 102 passed / 0 failed / 0 skipped; evidence at `docs/finals/evidence/w1/w1-run.json` plus 26 screenshots.
- Full pytest after the history-test collection fix: 959 passed, 7 skipped, 5 warnings in 168.41s. Full pytest is mandatory before every fix or acceptance commit.
- Typecheck and webpack build passed. Three pre-existing lint errors remain in Chat.tsx/useSession.ts. Real Supabase email delivery and token refresh remain unverified; see `docs/finals/NEEDS_SHUBH.md`.

## W2 - discovery reviewed; implementation active

- `docs/finals/UI_SPEC.md`, `docs/finals/REPORT.md`, and 17 layout-only images at `docs/finals/evidence/w2/` are pushed at 9a831b3.
- Raw DOM/accessibility JSON and ten text/brand-bearing captures stay in ignored `work/finals/w2-discovery/` quarantine; they are not tracked evidence.
- Implementation brief: `work/finals/w2-implementation/PROMPT.txt`. Lane B terminal session 86379, Hermes PID 32720, Python worker PID 31084. Parent owns `apps/web/components/chat/Chat.tsx` and `apps/web/app/globals.css`; nine children have disjoint ownership. Acceptance remains open pending independent visual, behavior, accessibility, and build gates.

## W3 - safe extraction foundation active

- Package: `work/finals/w3-uploads/`, terminal session 46515, observed Hermes parent PID 36460 and active Python worker PID 32344. Extractor modules, tests, and synthetic fixtures are in progress. No independent review or API/UI acceptance yet.
- W3 cannot edit W2-owned chat UI. W3-owned files remain unstaged pending review and full-suite verification.

## Program controls and open work

- Phase 0 plan suite committed at d02645e; baseline was 802 passed, 7 skipped, 5 warnings.
- HEAD gate: Nilabh proxy PID 4124 on 127.0.0.1:20190, 120 rpm. No fallback model; at most two Hermes sessions and nine children per session. No production changes, spent-data access, v3 retraining, threshold change, or secrets.
- GitHub issues #1-#13 track workstreams and defects; pinned tracker #8. No PR opened because preview deployment behavior is unresolved and deployment is prohibited.
- Other pre-existing untracked paths remain untouched: `.playwright-mcp/`, `agent-workspaces/`, `docs/goals/`, `docs/reports/`, `runs/`, security and Uvicorn logs.
