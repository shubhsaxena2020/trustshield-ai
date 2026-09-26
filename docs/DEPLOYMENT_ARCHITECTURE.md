# Deployment architecture (Milestone A: public MVP)

> **Superseded topology (2026-09-27): the deployment is one Vercel project with Vercel Services; see `docs/VERCEL_DEPLOYMENT.md`.**
> Render was cancelled and never created. The measured sizing, configuration and hardening below still apply unchanged to the
> FastAPI container, which now runs as the `/api/*` service on Vercel (1 vCPU / 2 GB on Hobby).

Decisions fixed by the project lead: **Vercel** (Next.js frontend), **Render Docker Web Service** (FastAPI API, target `1 CPU / 2 GB`),
provider-generated HTTPS domains, **stateless** single API instance, no PostgreSQL, trusted-release-artifact model delivery,
`ENABLE_WEB_SANDBOX=false`. Budget target <= $30/month; no paid infrastructure is added without a report.

```
Browser --HTTPS--> Vercel: Next.js (static + client JS)
Browser --HTTPS (direct, CORS restricted to the Vercel origin)--> Render: FastAPI container
                      Rules | ML v3 | Tesseract OCR | QR (OpenCV) | static URL analysis | fusion
(separate, NOT deployed) Sandbox worker image: Playwright + Chromium, authenticated jobs, egress-deny network policy
```

Uploads (screenshots, QR images) go **directly** from the browser to the API; they are not proxied through a Vercel function.

## D1 runtime requirements (measured)

| item | value |
|---|---|
| Python | 3.13 (image: `python:3.13-slim-bookworm`, pinned by digest; dev 3.13.14, image 3.13.15) |
| Node | 24 (frontend build only; nothing Node runs on the API) |
| model | `trustshield_text_v3.joblib` 9.6 MB, sklearn 1.9.1, threshold 0.16 (rollback v1 7.2 MB) |
| native binaries | Tesseract OCR (Debian 5.3, English data; dev used 5.4) and OpenCV headless; **Chromium only in the sandbox image** |
| API image size | 985 MB (dominated by numpy/scipy/scikit-learn/OpenCV wheels) |
| persistent storage | none (results are in memory, TTL 15 min, sandbox jobs only; uploads never stored; `/tmp` tmpfs only) |
| subprocess use | Tesseract per OCR call (child process) |

**Memory and time, Linux container (Python 3.13.15)**

| scenario | RSS (process tree) |
|---|---:|
| API imported, model not loaded | 91 MB |
| v3 loaded (startup) | 213 MB |
| peak text analysis (30 requests) | 222 MB |
| peak QR analysis | 235 MB |
| peak OCR, typical screenshots | 293 MB |
| peak OCR, worst case (24 MP image, the pixel limit) | 687 MB |
| startup: import 0.8 s + model load about 1.9 s | ~3 s to ready |

Stress test in the hardened production container (`--memory 2g --cpus 1`): four simultaneous worst-case OCR uploads. Two ran
(concurrency gate = 2), two were rejected with 503 after the 8 s queue timeout, peak container memory **831 MiB of 2 GiB**, no
OOM, no restart. Each worst-case OCR takes about 30 s on one CPU (typical screenshots take a few seconds).

**Conclusion: 1 CPU / 2 GB is sufficient with about 60% headroom** (idle 213 MB; worst supported load 831 MiB). A 512 MB
plan is **not** sufficient for OCR (a single worst-case OCR peaks near 690 MB), so Render Free may be used only for preliminary
experiments that do not exercise large screenshots and must not be the final backend. No larger plan is needed.

## Why serverless-only is a poor fit for the API

Tesseract is a native binary and OpenCV/scipy/sklearn add roughly 1 GB of native wheels (over typical function size limits);
OCR is CPU-bound for seconds up to 30 s (function timeouts and per-invocation CPU/memory pricing are unfavourable); the model
is loaded once into memory (cold starts would reload it and the classifier on every instance); the sandbox needs a browser and
subprocess control. The frontend, by contrast, is static/client-rendered and suits Vercel.

## Configuration (environment; `.env.example` lists every variable, no secrets are needed for Milestone A)

| variable | role |
|---|---|
| `TRUSTSHIELD_ENV` | `development` / `staging` / `production`; the last two are the same hardened profile and refuse to start on bad config |
| `TRUSTSHIELD_ML_MODEL` | empty = promoted default v3 (production refuses anything else) |
| `CORS_ALLOWED_ORIGINS` | bare https origin(s) of the frontend; wildcard/localhost/http rejected in staging/production |
| `PUBLIC_API_URL`, `NEXT_PUBLIC_API_URL` | API origin; the latter is baked into the frontend build and must equal the former |
| `TESSERACT_CMD` | Tesseract path (`/usr/bin/tesseract` in the image) |
| `MAX_UPLOAD_SIZE` | upload cap (default 5 MiB); the body limit is this plus 64 KiB multipart overhead |
| `ENABLE_WEB_SANDBOX`, `TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL`, `ENABLE_EXTERNAL_REPUTATION` | must be false; startup fails otherwise |
| `TRUSTSHIELD_RESULT_STORE`, `TRUSTSHIELD_RESULT_TTL` | `sandbox_only` in staging/production: ordinary analyses are never retained |
| `TRUSTSHIELD_RATE_*`, `TRUSTSHIELD_HEAVY_CONCURRENCY`, `TRUSTSHIELD_REQUEST_TIMEOUT`, `TRUSTSHIELD_TRUSTED_PROXY_HOPS` | rate limits, OCR/QR concurrency (2), timeouts, proxy trust |
| `DATABASE_URL`, `WEB_SCAN_TIMEOUT` | reserved/unused in Milestone A (stateless; sandbox off) |

## API hardening implemented (D4/D13)

Body-size cap enforced before parsing (declared and streamed; 413); MIME/decoded-pixel/dimension validation; per-request and
queue timeouts (504/503); per-client rate limits (light 120/min, heavy uploads 30/min per client, sized so a shared campus NAT can demo, `Retry-After`); structured errors, no
tracebacks, validation errors never echo input; CORS restricted to the frontend origin; security headers (nosniff, frame deny,
no-store, CSP `default-src 'none'`, HSTS in staging/production); `/docs` and `/openapi.json` off outside development; request ID
on every response and log line; access log has method, path, status, duration, analysis ID only (no body, query, or content);
events for ML unavailable, OCR/module failure, sandbox failure and unhandled errors log class names only; uploads are processed
in memory and closed; result store bounded (200) with TTL and no ordinary retention; `/healthz` (liveness), `/readyz`
(ML and OCR ready), `/api/v1/health` (capabilities). Startup in staging/production verifies the model artifact SHA-256 against
the release manifest, loads it, checks identity, threshold 0.16 and sklearn compatibility, requires Tesseract, and validates the
environment; any failure aborts startup so the deploy fails rather than serving a degraded system.

The container runs as a non-root user, read-only root filesystem with a 128 MB `/tmp` tmpfs, all capabilities dropped,
`no-new-privileges`, PID limit, 2 GB / 1 CPU limits, one uvicorn worker, no Server header, and its own health check.

## Environments

`local` (dev servers or `docker compose up`) -> `staging` (Render service #1 + Vercel preview, same image and build args as
production, browser E2E and `scripts/smoke_remote.py` run against it) -> `production` (promote the same image digest and the same
frontend commit). Staging uses the Render Free plan only if the measured footprint fits the test set; the Tech Expo production
backend must not be a sleeping free service.

## Known limits and follow-ups

Rate limiting is in-memory and per instance (fine for one instance). Client identity behind Render's proxy requires
`TRUSTSHIELD_TRUSTED_PROXY_HOPS` to be set from the observed `X-Forwarded-For` chain in staging. A worker that outlives a timed-out
request cannot be cancelled (Tesseract has its own 30 s timeout). No durable history, custom domain, threat-intelligence layer or sandbox in
Milestone A. The sandbox image exists (`docker/sandbox.Dockerfile`) but is not deployed and needs an authenticated job
transport, Linux containment and network egress policy first (phase D8, explicit approval).
