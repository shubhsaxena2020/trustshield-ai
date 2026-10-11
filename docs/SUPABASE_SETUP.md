# Supabase setup (auth and scan history)

TrustShield uses one Supabase project for opt-in user accounts and the per-user scan history. It
is new infrastructure, added alongside the existing stateless analysis pipeline — anonymous
scanning is completely unaffected and nothing about detection/fusion changed.

## IMPLEMENTED

### Project

- Project name: `trustshield-ai`, organization `shubhsaxena2020's Org` (Free plan).
- Region: `ap-south-1` (South Asia, Mumbai) — closest available region to the team and most
  users.
- Project ref: `xssorflxyuanvkpukjon`. Project URL: `https://xssorflxyuanvkpukjon.supabase.co`.
- Created 2026-10-11 via browser automation (same approach used for the Google Safe Browsing key
  in an earlier session). The database password is recorded only in `.env`, never in this doc or
  in git.
- This project uses Supabase's current key format (`sb_publishable_...` /
  `sb_secret_...`) rather than the legacy JWT `anon`/`service_role` keys. Both formats work
  identically with the `apikey` + `Authorization: Bearer` header pattern used throughout
  `services/api/auth.py` and `services/history/store.py` — nothing in the app code assumes one
  format over the other.

### Schema

One table, `scan_history`, created via the SQL editor:

```sql
create table scan_history (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  input_type text not null check (input_type in ('text', 'url', 'image', 'qr')),
  input_summary text not null,
  risk_score int not null,
  risk_label text not null,
  result_json jsonb not null,
  created_at timestamptz not null default now()
);

alter table scan_history enable row level security;

create policy "owner can select own history"
  on scan_history for select using (auth.uid() = user_id);

create policy "owner can delete own history"
  on scan_history for delete using (auth.uid() = user_id);
```

No insert/update policy exists for the `anon`/`authenticated` roles: every write goes through the
backend's service-role (now secret-key) client, which bypasses RLS by design. RLS is the second
line of defense behind the backend's own `user_id` filtering, not the only one — see
`docs/AUTH_AND_HISTORY_ARCHITECTURE.md`.

### Auth

Supabase Auth, email/password only (no OAuth providers configured) — opt-in accounts for history,
never a gate in front of scanning.

### Environment variables

Backend (`.env`, git-ignored):

```
SUPABASE_URL=https://xssorflxyuanvkpukjon.supabase.co
SUPABASE_SERVICE_ROLE_KEY=<the project's secret key>
```

Frontend (`apps/web/.env.local`, git-ignored):

```
NEXT_PUBLIC_SUPABASE_URL=https://xssorflxyuanvkpukjon.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=<the project's publishable key>
```

Both sets of actual secret values live only in the git-ignored env files, never in this
repository's tracked files.

### Recreating this from scratch

1. Create a new Supabase project (any region), note its URL and keys from Project Settings →
   API Keys.
2. Run the schema SQL above in the SQL editor.
3. Set the four environment variables above in `.env` and `apps/web/.env.local`.
4. No further manual steps — the backend and frontend read these at startup/build time.
