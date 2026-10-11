# Auth, Supabase, History, and UI/UX — design spec

Written 2026-10-11, the night before TechFool Round 2 (2026-10-12). Builds on top of the live,
tested system at `4f23c41` on `conversational-safety-fixes`. This spec covers four additive
features plus a security-testing phase; none of it touches the existing detection pipeline
(rules, ML classifier, fusion, Safe Browsing, cert history), which stays exactly as validated.

## Goals

1. Real user accounts (Supabase Auth, email/password) — opt-in, not a gate.
2. A Postgres-backed scan history, visible only to the logged-in owner.
3. A visibly better UI/UX pass using shadcn/ui on top of the existing Tailwind v4 setup.
4. A security/adversarial testing phase against the new surface area before it ever reaches
   production.

## Non-goals

- OAuth providers (Google/GitHub login) — email/password only, to keep scope tight tonight.
- Any change to anonymous-user behavior. Scanning without an account stays exactly as it is
  today: stateless, nothing persisted, no login prompt blocking the flow.
- Any change to the detection/fusion logic, the ML model, or Safe Browsing/cert-history checks.
- Training or replacing any model. `trustshield_text_v3` stays the fraud-detection authority.
- A real DoS attack against the live production URL. See "Security & adversarial testing phase."

## IMPLEMENTED (target state)

### Data model (Supabase Postgres)

New table `scan_history`, owned by Supabase Auth's `auth.users`:

```sql
create table scan_history (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  input_type text not null check (input_type in ('text', 'url', 'image', 'qr')),
  input_summary text not null,       -- truncated/redacted preview, never the full raw message
  risk_score int not null,
  risk_label text not null,
  result_json jsonb not null,        -- the full AnalysisResult, for re-display
  created_at timestamptz not null default now()
);

alter table scan_history enable row level security;

create policy "owner can select own history"
  on scan_history for select using (auth.uid() = user_id);

create policy "owner can delete own history"
  on scan_history for delete using (auth.uid() = user_id);
-- No insert/update policy for the anon/authenticated roles: all writes go through the
-- backend's service-role client, which bypasses RLS by design. This is the second line of
-- defense behind the backend's own auth check.
```

### Auth flow

- Frontend: `@supabase/supabase-js` browser client, configured with
  `NEXT_PUBLIC_SUPABASE_URL` / `NEXT_PUBLIC_SUPABASE_ANON_KEY`. Handles sign-up, login, logout,
  and session persistence (localStorage, Supabase's default).
- Every call from the frontend to the backend's analyze/history endpoints attaches
  `Authorization: Bearer <access_token>` when a session exists; omitted entirely when anonymous.
- Backend: new `services/api/auth.py` exposes a FastAPI dependency `get_current_user`:
  - No `Authorization` header → returns `None` (anonymous). Analyze endpoints proceed exactly
    as today; no history write happens.
  - Header present → the dependency calls Supabase Auth's `GET /auth/v1/user` directly over
    HTTPS (plain `urllib.request`, matching the existing pattern in
    `services/analysis/reputation/safe_browsing.py` — no new SDK dependency, no lock-file
    changes) to resolve a user id. Invalid/expired tokens raise 401 rather than silently
    falling back to anonymous, so a broken session is visible in the UI instead of silently
    losing history.
- Backend never sees or stores a password. Backend holds exactly two new secrets:
  `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` (service-role, used only for the trusted
  server-side history write/read, never exposed to the frontend).

### Backend API changes

- `services/api/main.py`: each of the four `/api/v1/analyze/*` handlers gains an optional
  `user = Depends(get_current_user)`. After a successful analysis, if `user` is not `None`, the
  result is written to `scan_history` via a new `services/history/store.py`, which calls
  Supabase's PostgREST endpoint (`{SUPABASE_URL}/rest/v1/scan_history`) directly with the
  service-role key — same `urllib.request` style, no SDK. Write failures are logged and
  swallowed — a history-write error must never fail or degrade the analysis response itself.
- New endpoints:
  - `GET /api/v1/history` — requires a valid user (401 if anonymous); returns that user's rows,
    newest first, paginated.
  - `DELETE /api/v1/history/{id}` — requires a valid user; the query filters on `user_id` in
    addition to RLS, so one user can never delete another's row even on a server bug.
- `input_summary` is a short, explicitly truncated/redacted preview (first ~120 chars, no OCR
  raw text beyond that, no full QR payload) — history keeps the UX useful without becoming a
  second permanent store of full sensitive message content.

### Frontend changes

- New routes: `/login`, `/signup`, `/history`.
- New shared layout: a top nav (logo, Analyzer, History, Login/Signup or Logout+email depending
  on session) wrapping the existing `app/layout.tsx`.
- shadcn/ui added for: form inputs, buttons, card, table (history list), toast (success/error
  feedback), dialog (delete-confirmation on a history row). Existing analyzer components
  (`ResultDashboard`, `FusionPanel`, `MlPanel`, `OcrPanel`, `QrPanel`) are reskinned to the same
  design tokens, not rewritten — their props/logic are untouched.
- `/history` fetches `GET /api/v1/history` with the session's access token, renders a table
  (date, type, score/label, a "view" action that re-renders the stored `result_json` through
  the existing `ResultDashboard` component), and a delete action per row.

### UI/UX design direction

Clean security-SaaS aesthetic: light theme, generous whitespace, a single accent color for risk
severity (reusing whatever color scale `ResultDashboard` already uses for score bands, so the
new nav/history page doesn't introduce a second color language). No dark mode tonight — one
polished theme beats two half-finished ones.

A copy pass rides along with the reskin: coverage panel entries get one accurate status each
(`not_configured` / `not_run` / `failed` / `no_match` / `threat_found` — whatever the module
already reports internally, worded precisely instead of blurring "unavailable" and "not
configured" into the same word), and anywhere the risk score sits next to a confidence label,
the copy makes clear confidence describes the evidence/model, not certainty about the verdict.
This is a wording and layout fix only — no change to how risk/confidence are computed.

The history page's delete affordance includes a "clear all" action alongside the per-row
delete, and the analyzer page gets one explicit line of copy: scans are only saved when you're
logged in; anonymous scans are never stored. Both come from the same ChatGPT-sourced review
that flagged the ambiguity (see note below) and are the only two items from it folded into this
build.

### Testing plan

- `tests/unit`: `get_current_user` dependency — no header (anonymous), valid token (resolves
  user), invalid/expired token (401). Mocked Supabase client, no live network calls, consistent
  with the existing `TRUSTSHIELD_REPUTATION=off` / `TRUSTSHIELD_SAFE_BROWSING=off` test posture.
- `tests/integration`: history endpoints — insert-then-list round trip, ownership isolation
  (user A cannot see or delete user B's row), analyze-without-login writes nothing.
- Full existing suite (765 passed, 7 skipped baseline) must stay green — any regression there
  blocks merge regardless of how the new features look.
- Manual browser pass before anything touches production: sign up, log in, run all four analyze
  types, confirm each appears in `/history`, log out, confirm anonymous scanning still works and
  writes nothing, log back in, delete a history row, confirm it's gone.

### Security & adversarial testing phase (after the build, before production rollout)

1. **PentestGPT web-app pentest** against the local/staging instance: auth bypass, IDOR on
   `/api/v1/history/{id}` (cross-user access attempts), RLS bypass attempts directly against
   Supabase's REST API, JWT tampering (expired/forged/wrong-signature tokens), injection on the
   analyze endpoints with an authenticated session.
2. **Prompt-injection / confusion-prompt red-teaming** of the chat assistant
   (`services/chat/*`): a scripted battery of adversarial prompts attempting to make it reveal
   the underlying model name/version/system prompt, or override the existing guarantee that
   chat cannot change the computed risk score. Run directly against `/api/v1/chat` locally.
3. **Rate-limit / resilience verification**: controlled request bursts against the
   local/staging instance confirming `TRUSTSHIELD_RATE_LIGHT_PER_MIN`,
   `TRUSTSHIELD_RATE_HEAVY_PER_MIN`, and `TRUSTSHIELD_HEAVY_CONCURRENCY` actually return 429s
   under pressure. This is a bounded resilience check, not a destructive flood, and it never
   targets the live production URL — that boundary is fixed regardless of authorization.

Findings from all three go into a new `docs/MULTI_AI_REVIEW_LOG.md` (see below) and must be
fixed or explicitly accepted-as-risk before merge.

### Multi-AI delegation plan

- **Codex** — security review of `services/api/auth.py`, `services/history/store.py`, and the
  SQL/RLS policy above (auth logic and injection surface are its strength).
- **Antigravity** — UI/UX and accessibility review of the new pages and reskinned components.
- **Hermes** — a broad second-opinion pass over the full diff (backend + frontend), plus
  execution of the prompt-injection battery against the chat endpoint.
- All three run as independent review/test passes, not as code-writers; I write the
  implementation, they review and adversarially test it, and every finding is verified against
  the actual code before being acted on.

### Documentation to produce

Following the project's existing `docs/*.md` naming convention (no forced file count):

- `docs/SUPABASE_SETUP.md` — project ref, schema, RLS policies, env vars, how to recreate.
- `docs/AUTH_AND_HISTORY_ARCHITECTURE.md` — the auth flow and history data flow, in the same
  style as `ARCHITECTURE.md`.
- `docs/UI_UX_REDESIGN_NOTES.md` — what changed visually and why.
- `docs/MULTI_AI_REVIEW_LOG.md` — every delegated review/test finding and its resolution.
- Updates to `docs/presentation/TEAM_NOTES.md` / `PRESENTATION_SCRIPT.md` once the feature is
  live, matching how the Safe Browsing addition was documented last session.

### Rollout

All work happens on `feature/auth-supabase-history-uiux`, branched off
`conversational-safety-fixes` at `4f23c41`. Production (`conversational-safety-fixes`, currently
deployed) is not touched until: the full pytest suite is green, the manual browser pass is
green, and the security/adversarial phase's findings are resolved. Only then does the branch
merge and get deployed — commit before building the Vercel bundle, per the standing rule in
`TEAM_NOTES_DEEP.md` section 12.
