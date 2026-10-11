# Backend Security Review: Auth and Scan History

**Review date:** 2026-10-11  
**Scope:** `services/api/auth.py`, `services/history/store.py`, the `scan_history` SQL/RLS in `docs/SUPABASE_SETUP.md`, and related `services/api/main.py` call sites (`get_current_user`, `history_store`, `/api/v1/history`).

## Summary

No exploitable unauthenticated access to scan history or cross-user read/delete path was found in the reviewed call sites. All three history routes reject a missing/unresolved user before calling the store. The store also includes an explicit user ID equality filter in each history read/delete query. Invalid or expired bearer tokens return 401; verification failures return 503.

One defense-in-depth statement is inaccurate for the backend path: the backend sends the Supabase service/secret key, which bypasses RLS. Therefore, the explicit `user_id` filters are the effective isolation boundary for requests made by this backend. RLS still applies to requests made with ordinary user credentials.

## Findings

### RLS does not protect backend history queries made with the service/secret key

**Severity:** informational  
**Location:** `services/history/store.py:27-35`, `services/history/store.py:41-45`; `docs/SUPABASE_SETUP.md:49-52`

**Assessment:** The store puts `service_role_key` in both `apikey` and `Authorization: Bearer` headers. Supabase service/secret-key requests bypass RLS. This contradicts the documentation's claim that RLS is a second line of defense behind the backend's user ID filters for these calls. The backend currently does apply explicit ownership filters, so this is not itself a demonstrated cross-user exploit.

**Concrete scenario:** If a future backend read/delete call using this helper omits or weakens its `user_id` filter, the database RLS policies will not contain that mistake; the privileged request can access rows across users. Ordinary clients using publishable keys and user JWTs remain subject to the select/delete policies.

**Suggested fix:** Prefer making user-scoped read/delete requests with the validated user's access token so the database evaluates `auth.uid()` and RLS independently. If the backend retains the secret key, correct the documentation to say backend isolation depends on the explicit filters and keep those filters mandatory in every history operation. Never expose the secret key to the frontend.

## Exploitability checks with no finding

### Authentication bypass

`services/api/auth.py:40-58` returns `None` for absent or non-Bearer authorization, resolves Bearer tokens through Supabase Auth, returns `401` for invalid/expired tokens, and returns `503` on verification/configuration failure. `services/api/main.py:238-243`, `246-253`, and `256-262` each reject `user is None` before calling `history_store`. Anonymous analysis endpoints may run, but `_record_history` skips writes without a resolved user (`services/api/main.py:98-110`, called at `171-218`); they do not expose history. No request to the reviewed history routes reaches history data without a valid Supabase user.

### PostgREST query interpolation and cross-user access

`services/history/store.py:62-77` interpolates filter values directly into the URL. `row_id` is request-controlled, so this is brittle query construction and should be URL-encoded/UUID-validated as hardening. However, in the reviewed delete call the server appends the verified `user_id` equality filter after `row_id` (`services/history/store.py:68-72`), and PostgREST combines separate filters with AND by default ([PostgREST filtering documentation](https://docs.postgrest.org/en/stable/references/api/tables_views.html#horizontal-filtering)). Added query fragments from a crafted row ID do not remove the required ownership equality. The `user_id` used in all store calls comes from the verified `/auth/v1/user` response, not from request input. I found no demonstrated way for a crafted `row_id` or `user_id` to read/delete another user's row. Building query strings with a URL encoder and validating `row_id` as a UUID would reduce parser ambiguity and malformed-query errors.

### RLS policy behavior

`docs/SUPABASE_SETUP.md:40-46` enables RLS and defines owner-only select/delete policies. Those policies protect direct requests using normal user credentials, but they are bypassed by the backend's secret key as described above. The app's current explicit filters—not RLS—are what constrain backend history reads and deletes to the authenticated user's rows.

## Review method

Focused manual review of the requested files and call sites. `semgrep`, `bandit`, and `gitleaks` were not available in the review environment. No active testing or tests were run.
