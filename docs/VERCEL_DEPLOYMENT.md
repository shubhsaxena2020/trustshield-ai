# Vercel deployment (Milestone A: one project, one origin)

Decision (2026-09-27): the Render plan is cancelled; nothing was created or paid on Render. TrustShield runs as **one Vercel project**
using Vercel Services (beta): the Next.js frontend at `/` and the unchanged FastAPI application as a **container service** at `/api/*`.

```
https://<project>.vercel.app
   /        -> service "web": Next.js (apps/web)
   /api/*   -> service "api": FastAPI container (Dockerfile.vercel)
               rules | trustshield_text_v3 (v1 baked in for rollback) | Tesseract | OpenCV QR | static URL | Phase 4 fusion
```

The browser calls the API with relative URLs (`/api/v1/...`), so production needs no CORS (the strict profile accepts an empty
`CORS_ALLOWED_ORIGINS` and then allows no cross-origin caller). Local development keeps `http://localhost:8000` and
`http://localhost:3000`. The API receives the original path (`/api/v1/...`); `/api/v1/healthz` and `/api/v1/readyz` exist because only
`/api/*` is routed to the backend.

## What was verified about the platform (docs + a throwaway probe project, deleted afterwards)

| item | finding |
|---|---|
| container beta on Hobby | works: a `Dockerfile.vercel` service built and ran on the free Hobby team |
| build system | buildah; `apt-get install tesseract-ocr` works at build time (Tesseract 5.3.0) |
| runtime | 1 vCPU, cgroup limit 2 GiB, writable `/tmp`, `PORT=80` by default |
| port | non-root user cannot bind 80: the project sets `PORT=8000` and the container listens on `$PORT` |
| limits (Hobby) | 2 GB / 1 vCPU, 300 s max duration, **4.5 MB request body**, function size 250/500 MB standard, "large functions" (beta) up to 5 GB |
| scale | instances stop after 30 s idle in preview and 5 min in production (cold start = boot + model load, a few seconds) |
| client address | Vercel **replaces** `X-Forwarded-For` with exactly the client address (client-supplied values are discarded); observed peer = client |
| build-time secrets | **not documented and not provided as BuildKit secrets**: project variables reach a build only as `ARG`s. See "Model delivery" |

## Model delivery (safest supported mechanism: no credential on Vercel at all)

Vercel container builds cannot reproduce the BuildKit-secret approach (project variables arrive only as build `ARG`s, which would be
recorded in image metadata unless carefully isolated). Rather than weaken secret handling, **no credential is given to Vercel**:

1. The exact artifacts are downloaded locally from the **private GitHub release** `model-v3` with the authenticated fetcher
   (`scripts/fetch_model_artifact.py --github-release ...`, token only from the local environment, dropped on the signed-URL redirect,
   SHA-256 verified against `models/release/v3_release_manifest.json`).
2. `scripts/build_vercel_bundle.py` re-verifies both hashes (v3 promoted, v1 rollback) and assembles the deployment bundle
   (`vercel.json`, `web/`, `api/` with `Dockerfile.vercel`, `services/`, manifests and `api/release-artifacts/`).
3. The bundle is deployed with the Vercel CLI (**no Git integration**, so nothing model-related ever touches Git). `Dockerfile.vercel`
   verifies both hashes again during the image build (the build fails otherwise) and the API verifies hash, identity, threshold 0.16
   and sklearn compatibility at every start (it refuses to start otherwise).
4. The bundle contains no token, no `.env`, no `NEXT_PUBLIC_*` secret; the repository secret scan and `docker history` show none.

Rollback to v1: set `TRUSTSHIELD_ML_MODEL=trustshield_text_v1` **and** `TRUSTSHIELD_ROLLBACK_CONFIRM=v1` (both required; the startup
check then verifies the v1 artifact against the manifest) and redeploy. Retraining is never part of a deployment.

## Project settings (all identical for Preview and Production)

`PORT=8000`, `TRUSTSHIELD_ENV=production` (the same hardened profile everywhere), `ENABLE_WEB_SANDBOX=false`,
`TRUSTSHIELD_TRUSTED_PROXY_HOPS=1` (observed: the edge sets a single `X-Forwarded-For` entry). Unset: `CORS_ALLOWED_ORIGINS`,
`NEXT_PUBLIC_API_URL` (same-origin), `TRUSTSHIELD_DEBUG_PROXY` (observation-only route; set temporarily on a Preview, never in production).
`MAX_UPLOAD_SIZE=4194304` is baked into the image (below the 4.5 MB platform cap; the UI also limits uploads to 4 MB).

## Workflow: local -> Preview (staging) -> Production

```
python scripts/fetch_model_artifact.py --model trustshield_text_v3 --github-release <owner/repo>@model-v3 --dest <artifacts>   # and v1
python scripts/build_vercel_bundle.py --out <bundle> --artifacts <artifacts>
cd <bundle> && vercel deploy                     # Preview
python scripts/smoke_remote.py --base <preview-url> --bypass-token <secret>
python tests/e2e/run_remote_e2e.py --web <preview-url> --same-origin --bypass-token <secret>
vercel promote <preview-url>                     # the exact verified build becomes Production
python scripts/smoke_remote.py --base https://<project>.vercel.app ; python tests/e2e/run_remote_e2e.py --web https://<project>.vercel.app --same-origin
```

Preview URLs are behind Vercel Authentication; automation uses a project "Protection Bypass for Automation" secret passed as the
`x-vercel-protection-bypass` header (`VERCEL_AUTOMATION_BYPASS_SECRET`). Revoke it in Project Settings -> Deployment Protection when
testing is finished. There is no separate staging backend and no extra cost.

## Resource gate results (Preview on Hobby, 2026-09-27)

| check | result |
|---|---|
| container starts, v3 loads, Tesseract works | yes (`/api/v1/health`: `trustshield_text_v3`, tesseract available, sandbox false) |
| remote smoke (22 checks) and browser E2E (15 checks) | all pass |
| container memory after startup and first requests | 329 MB (limit 2048 MB) |
| 4 simultaneous worst-case 24 MP OCR uploads | 2 completed in 29 s, 2 returned 503 "busy" after 9 s by design; **peak 1053 MiB of 2048 MiB, no OOM, no 5xx** |
| durations | worst supported OCR about 29 s (limit 300 s) |
| rate limiter under 300 parallel requests | 113 served, 180 answered 429 |

## Rate limiting under autoscaling

The limiter is **in-process and per instance**. Under Vercel autoscaling several instances can exist, so the effective global limit is
`limit x instances`, not a strict global cap. It still bounds a single instance (and therefore memory/CPU per instance), OCR
concurrency is capped per instance (2), and the platform's own firewall/DDoS protection sits in front. A shared limiter (Redis or the
Vercel firewall rate-limit rules) is deliberately not added for the Tech Expo MVP; revisit only if testing shows real abuse or overload.

## Hobby monitoring and limits to watch

Function Active CPU, provisioned memory, invocations and deployment size (Vercel dashboard -> Usage). If Hobby's monthly included
usage is reached the platform stops or bills; nothing here upgrades the plan automatically. The container image is large (about 1 GB;
numpy/scipy/scikit-learn/OpenCV wheels) which relies on the "large functions" beta (up to 5 GB); it deployed successfully on Hobby.

## Known limitations

Beta features (Services, Container Images, large functions) can change; cold start after idle adds a few seconds; result history is
not kept (stateless; `GET /api/v1/analysis/{id}` only serves sandbox jobs, which are disabled); degraded ML mode is unreachable in
production by design (startup refuses to run without v3), and is covered by local tests and the local browser E2E; the sandbox stays
disabled and external browsing is not enabled.


## Conversational build (Preview only)

The conversational assistant lives on branch `feature/trustshield-conversational-next` and is **not promoted**. It is deployed, if at all, as a
Vercel **Preview** using the same bundle/`vercel.json`; production keeps serving the four-tab app until a final report is approved. No
new environment variables are required (`TRUSTSHIELD_EXPLANATION_PROVIDER` is ignored in production). Same limits as before: Hobby, 2 GiB / 1 vCPU,
~4 MiB uploads, sandbox disabled, stateless, per-instance rate limits. Promotion: `vercel promote <preview>` (rebuilds) after approval.
