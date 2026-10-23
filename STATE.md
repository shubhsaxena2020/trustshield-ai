# Finals run state

- Launch time (T0): 2026-10-22T23:48:19Z UTC
- Branch: finals-48h; latest pushed commit 335ad49.
- Last update: 2026-10-23T01:18Z.
- Phase: W1 full-suite gate green; W2 discovery deliverables awaiting audit; W3 backend extraction active.

## W1 - acceptance restored

- Browser matrix: isolated worktree, fake Supabase configuration and local API; 3 consecutive runs x 102 passed / 0 failed / 0 skipped. Evidence: docs/finals/evidence/w1/w1-run.json and 26 screenshots.
- Full pytest after removing the stray two-byte suffix from tests/integration/test_history_endpoints.py: 959 passed, 7 skipped, 5 warnings in 168.41 s. Focused set: 180 passed, 1 warning. Typecheck and webpack production build passed.
- HEAD reported and we reproduced the collection defect. Issue #13 filed. Fix is pushed in 335ad49; the full suite passed before that fix commit. Full pytest remains mandatory before future fix or acceptance commits.
- Lint still has 3 baseline set-state-in-effect errors in Chat.tsx and useSession.ts. Default Turbopack build in the isolated worktree cannot traverse its external node_modules junction; webpack build passed.
- Real Supabase email delivery and token refresh remain unverified without a test account or mailbox; see docs/finals/NEEDS_SHUBH.md.

## W2 - discovery delivered; orchestrator audit pending

- Worker produced docs/finals/UI_SPEC.md and docs/finals/REPORT.md. Nine observation folders are under ignored work/finals/w2-discovery/observations/.
- Seven masked layout-only captures were inspected, committed and pushed at bdf2a7b.
- The worker added 20 more screenshots to docs/finals/evidence/w2/. These have not passed orchestrator visual and content audit and must stay out of commits. The raw REA page inspection JSON was moved to ignored work because it retained public page text; spec and report still cite its former path and need correction.
- Review spec measurements and reference wording, reconcile report paths and screenshot inventory, audit all new images, then start W2 implementation in the same lane.

## W3 - backend stage active

- Hermes session 46515, PID 36460, package work/finals/w3-uploads/. Worker files currently appearing: document_types.py, document_text.py, pdf_text.py and synthetic fixtures. Tests, API wiring and report are pending.
- W3 worker must not edit W2-owned chat UI. Parent owns API integration after reviewing the extractor interface. Browser and mobile acceptance remain pending UI ownership release.

## Program controls / open items

- Phase 0 plan docs committed at d02645e; baseline suite: 802 passed, 7 skipped, 5 warnings in 181.26 s.
- Head order: Nilabh proxy PID 4124 at 127.0.0.1:20190, 120 requests/minute; route calls through it. Up to two Hermes sessions with nine children each; no fallback models.
- GitHub issues #1-#13 track workstreams and defects. Pinned tracker #8. No PR opened because Vercel preview behavior is unknown and MISSION.md prohibits deployments.
- No spent data access, production changes, secret exposure, v3 changes, or threshold changes.
- Pre-existing unrelated untracked paths remain untouched: .playwright-mcp/, agent-workspaces/, docs/goals/, docs/reports/, runs/, security logs and Uvicorn logs.
