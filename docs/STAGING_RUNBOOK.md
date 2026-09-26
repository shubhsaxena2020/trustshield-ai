# Staging runbook (Render + Vercel)

State when written: repository, private release, CI (all five jobs) and the API image are ready and verified; **no Render or Vercel
resource exists yet**. Steps that need the account owner are marked **[owner]**.

## 1. Provider plan and price (from render.com/pricing, checked 2026-09-26)

Plan id `1c-2g` (legacy name "Standard"): 1 CPU, 2 GB RAM, **$25/month** (prorated). Smaller plans do not fit OCR: Starter
(`0.5c-512mb`, $7) and Free (0.1 CPU, 512 MB, spins down) are below the measured 687 MB worst-case OCR peak. Vercel Hobby is $0.
Budget: about $25/month against the <= $30 target; staging and production share this one service until a production service is
approved (a second Standard service would raise the total to about $50/month and needs your decision).

## 2. Create the read-only model token [owner]

GitHub -> Settings -> Developer settings -> Fine-grained personal access tokens: repository `shubhsaxena2020/trustshield-ai` only,
permission **Contents: Read-only**, expiry 90 days. Give it only to Render as the secret `MODEL_FETCH_TOKEN` (never in the repo,
never `NEXT_PUBLIC_*`).

## 3. Render staging [owner clicks; then the assistant verifies]

Dashboard -> New -> Blueprint -> connect GitHub (grant the Render app access to this repository) -> select `render.yaml`. Confirm
the plan (`standard`, $25/month). Fill the unsynced variables: `MODEL_FETCH_TOKEN`, `CORS_ALLOWED_ORIGINS` (temporary
`https://trustshield-staging.vercel.app`, replaced in step 5), `PUBLIC_API_URL` (the `onrender.com` URL shown by Render).
The build fetches the artifact with a BuildKit secret and fails unless the SHA-256 matches the release manifest. The service starts
only if the startup checks pass (v3 hash/identity/threshold, Tesseract, CORS, sandbox off).

## 4. Observe the proxy chain, then set `TRUSTSHIELD_TRUSTED_PROXY_HOPS`

`python scripts/observe_proxy_chain.py --base https://<api>.onrender.com` sends requests with a spoofed `X-Forwarded-For` and reads
back what the API actually receives (via a diagnostic that is enabled only when `TRUSTSHIELD_DEBUG_PROXY=1`, off by default and
refused in staging/production unless explicitly set for the observation window). Set the hops from the observed chain and remove
the diagnostic.

## 5. Vercel staging

Create the project (root `apps/web`), set `NEXT_PUBLIC_API_URL` to the staging API origin, deploy, then set the exact Vercel
origin in Render `CORS_ALLOWED_ORIGINS` and redeploy the API.

## 6. Verify

`python scripts/smoke_remote.py --base https://<api> --origin https://<web>` and
`python tests/e2e/run_remote_e2e.py --web https://<web> --api https://<api>`; plus rate-limit, request-id/header and
sandbox-disabled checks. Production is promoted only from the same image digest and frontend commit.
