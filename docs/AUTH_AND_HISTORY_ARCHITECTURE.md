# Auth and history architecture

Opt-in Supabase email/password accounts and a per-user scan history, layered on top of the
existing stateless analysis pipeline. Anonymous scanning is unchanged: no login prompt, nothing
persisted, identical responses to before this feature existed. See `docs/SUPABASE_SETUP.md` for
the Supabase project/schema itself.

## IMPLEMENTED

### Auth flow

1. The frontend (`apps/web/lib/supabaseClient.ts`) uses `@supabase/supabase-js` directly for
   sign-up/login/logout and session persistence — no custom token handling.
2. `apps/web/lib/authFetch.ts` wraps `fetch`: if a Supabase session exists, it attaches
   `Authorization: Bearer <access_token>`; if not, the request goes out exactly as before. The
   analyzer page, the chat component, and the history page all use it instead of bare `fetch`.
3. `services/api/auth.py`'s `get_current_user` FastAPI dependency resolves the token:
   - No header, or a non-Bearer header → `None` (anonymous; every analyze endpoint proceeds
     exactly as it did before this feature).
   - A Bearer token → validated against Supabase Auth's `GET /auth/v1/user` REST endpoint
     (plain `urllib.request`, matching the existing style in
     `services/analysis/reputation/safe_browsing.py` — no new backend dependency). A resolved
     user returns `CurrentUser(id, email)`; an invalid/expired token raises `401` rather than
     silently falling back to anonymous, so a broken session is visible in the UI instead of
     quietly losing history.
   - If `SUPABASE_URL`/`SUPABASE_SERVICE_ROLE_KEY` are unset, any request carrying a token gets
     `503`; unset entirely, the whole account system is simply off and every endpoint behaves as
     before Supabase existed.

### History data flow

1. Each `/api/v1/analyze/*` handler takes an optional `user: CurrentUser | None =
   Depends(get_current_user)`. After a successful analysis, `_record_history`
   (`services/api/main.py`) writes a row only when `user is not None` and Supabase is configured.
2. `services/history/store.py` writes/reads via Supabase's PostgREST REST API
   (`{SUPABASE_URL}/rest/v1/scan_history`) using the service-role (secret) key — no SDK, same
   `urllib.request` style as the auth check. A write failure is logged and swallowed; a
   history-write error must never fail or change the analysis response itself.
3. `input_summary` is a truncated (~120 char), whitespace-collapsed preview — the first ~120
   characters of the submitted text/URL for text/URL scans, or a generic label (`"Screenshot scan
   (suspicious)"`) for image/QR scans, since OCR text isn't threaded out of `_upload_endpoint`'s
   signature (no change to the detection pipeline). The full `AnalysisResult` is stored separately
   in `result_json` for the history page's "View" action to re-render.
4. `GET /api/v1/history`, `DELETE /api/v1/history/{id}`, `DELETE /api/v1/history` all require a
   resolved user (401 otherwise) and operate only on that user's own rows.

### Isolation: two independent layers, not one

- **Database (RLS):** `scan_history` has row-level security with owner-only `select`/`delete`
  policies (`auth.uid() = user_id`). This protects any request made with an ordinary user's own
  Supabase access token.
- **Application (explicit filter):** because the backend's own history client authenticates with
  the *service-role* key — which bypasses RLS by Supabase's design — RLS is **not** the operative
  defense for requests the backend itself makes. The actual isolation boundary for those requests
  is that every store function takes the verified `user_id` (from `get_current_user`, never from
  request input) and includes it as an explicit, AND'd equality filter in every select/delete
  query. `services/history/store.py`'s `_eq()` helper additionally URL-encodes every interpolated
  filter value, so a crafted id can't inject extra query parameters into that filter (found and
  fixed during the pre-launch security review — see `docs/MULTI_AI_REVIEW_LOG.md`).

In short: RLS is real and correct for any future direct-client access path, but today, the
explicit backend-side `user_id` filter is what actually keeps one user from reading or deleting
another user's history.

### Frontend routes

- `/login`, `/signup` — Supabase email/password forms (shadcn/ui), redirect to `/analyzer` on
  success.
- `/history` — requires a session (redirects to `/login` if absent, including mid-session on
  logout); lists the user's scans, "View" re-renders the full stored result through the existing
  `ResultDashboard` component, per-row delete and "Clear all".
- `NavBar` (in the root layout) shows Log in/Sign up when signed out, or the user's email + a
  History link + Log out when signed in.
