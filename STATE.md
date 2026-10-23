# Finals run state

- Launch time (T0): 2026-10-22T23:48:19Z (UTC)
- Branch: inals-48h; last pushed commit df2a7b; W1 test-collection fix is locally verified and awaiting commit.
- Last update: 2026-10-23T01:17:11Z.
- Phase: W1 gate restored after full-suite rerun; W2 discovery deliverables awaiting orchestrator audit; W3 backend extraction active.

## W1 - verified; acceptance restored after collection fix

- Browser matrix: isolated worktree, fake Supabase config and local API; 3 consecutive runs x 102 passed / 0 failed / 0 skipped. Evidence: docs/finals/evidence/w1/w1-run.json, 26 screenshots.
- Full pytest after removing the literal trailing \\n from 	ests/integration/test_history_endpoints.py: 959 passed, 7 skipped, 5 warnings in 168.41 s. Focused set: 180 passed, 1 warning. Typecheck and webpack production build passed.
- A blocking collection defect was reported by HEAD and reproduced. Exact two trailing bytes were removed. New GitHub issue #13 filed; fix commit pending. The earlier commit 2c5b415 had included the bad test-file suffix. Full suite must pass before future acceptance/fix commits.
- Lint still has 3 baseline set-state-in-effect errors in Chat.tsx and useSession.ts. Default Turbopack build in isolated worktree cannot traverse its external 
ode_modules junction; webpack build passed.
- Real Supabase email delivery and token refresh remain unverified without test account/mailbox; see docs/finals/NEEDS_SHUBH.md.

## W2 - discovery delivered; orchestrator audit pending

- Worker produced docs/finals/UI_SPEC.md and docs/finals/REPORT.md; 9 observation reports are now present in ignored work/finals/w2-discovery/observations/.
- 7 masked layout-only captures were visually checked, committed and pushed at df2a7b.
- Worker has added 20 more screenshots to docs/finals/evidence/w2/; they have not yet passed orchestrator visual/content audit. Keep all unreviewed images out of commits. The raw REA inspection JSON was moved from repo evidence to ignored work because it contains retained public page text; the spec/report still cite its former path and need correction.
- Review the measurements, remove reference wording/copy from implementation-facing spec, reconcile evidence paths/counts, check all screenshots, then hand W2 implementation into the same lane.

## W3 - backend stage active

- Hermes session 46515, PID 36460, package work/finals/w3-uploads/. Worker files currently appearing: document_types.py, document_text.py, pdf_text.py, and synthetic fixtures. No W3 report/tests/API wiring yet.
- W3 worker is barred from W2-owned chat UI. Parent owns API integration after reviewing the extractor interface. Browser/mobile acceptance remains pending UI ownership release.

## Program controls / open items

- Phase 0 plan docs committed at d02645e; baseline suite: 802 passed, 7 skipped, 5 warnings in 181.26 s.
- Head order: Nilabh proxy PID 4124 at 127.0.0.1:20190, 120 requests/minute; keep calls routed through it. Up to two Hermes sessions with nine children each; no fallback models.
- GitHub issues #1-#13 track workstreams/defects. Pinned tracker #8. No PR opened because Vercel preview behavior is unknown and MISSION.md prohibits deployments.
- No spent data access, production changes, secret exposure, v3 changes, or threshold changes.
- Pre-existing unrelated untracked paths remain untouched: .playwright-mcp/, gent-workspaces/, docs/goals/, docs/reports/, uns/, security logs, and Uvicorn logs.
