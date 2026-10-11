PASS - safe to merge

# Final pre-merge verification gate

- Backend: `python -m pytest tests -q` — `790 passed, 7 skipped, 5 warnings in 155.17s (0:02:35)`.
- Frontend: `npm run typecheck` passed; `npm run build` passed and listed all 6 routes: `/`, `/_not-found`, `/analyzer`, `/history`, `/login`, `/signup`.
- Commits in `conversational-safety-fixes..feature/auth-supabase-history-uiux`: **22**.
