# Deployment & Production Readiness — governing plan and gap analysis

Status: **plan preserved, nothing implemented.** Written 2026-09-26 against commit `2347bb3`. No hosting account, domain,
network or irreversible change has been made. Live external sandbox browsing stays disabled; spent holdouts (v1 test, v2) are
never used; v3 is not retrained.

## Governing plan (summary of the approved specification)

Goal: a stable HTTPS deployment shared with faculty, Tech Expo judges, teammates and controlled testers (Text, Screenshot OCR,
URL static, QR, ML v3, evidence/risk fusion, dashboard; web sandbox initially off). Architecture: browser -> HTTPS Next.js ->
HTTPS FastAPI (rules, ML, OCR, QR, static URL) with the Playwright sandbox as a **separate restricted worker** that never shares
the API's privileges. Phases: D1 audit (`docs/DEPLOYMENT_ARCHITECTURE.md`), D2 containers (API image; separate sandbox image;
non-root, minimal, pinned, health checks, limits), D3 env-driven config (`TRUSTSHIELD_ENV`, `TRUSTSHIELD_ML_MODEL`,
`DATABASE_URL`, `CORS_ALLOWED_ORIGINS`, `PUBLIC_API_URL`, `TESSERACT_CMD`, `ENABLE_WEB_SANDBOX`, `ENABLE_EXTERNAL_REPUTATION`,
`MAX_UPLOAD_SIZE`, `WEB_SCAN_TIMEOUT`; `.env.example`; no secrets), D4 API hardening, D5 persistence decision (stateless unless
durable history is truly needed; PostgreSQL not SQLite if durable), D6 frontend deploy, D7 backend deploy, D8 sandbox deploy
(only after independent review; `ENABLE_WEB_SANDBOX=false` on first public release), D9 CI/CD, D10 production preflight, D11
local -> staging -> production with browser E2E on staging, D12 shareable URL + "Research / Tech Expo Prototype" footer, D13
monitoring without sensitive data, D14 verification from a different device/network. Parallel **Accuracy track**: benchmark
word/char TF-IDF+SVM, ensemble, MiniLM + classifier, a compact transformer, calibrated ensemble on real Indian fraud and real
legitimate financial messages with hard-negative mining; never select on spent v1/v2; a replacement for v3 needs a **new sealed
holdout**. Milestone A = public MVP (frontend + API + v3 + four inputs, sandbox off). Milestone B = full Tech Expo version
(validated better model, isolated sandbox, threat intel, stronger fusion, monitoring, polish). **Stop before** enabling live
external sandbox browsing or evaluating any future sealed holdout without explicit approval.

## Gap analysis (state of the repository)

Legend: DONE / PARTIAL / MISSING.

| Phase | Requirement | State | Evidence / gap |
|---|---|---|---|
| D1 | Deployment architecture doc | MISSING | No `docs/DEPLOYMENT_ARCHITECTURE.md`. Facts gathered below. |
| D1 | Runtime requirements known | PARTIAL | Python 3.13.14, Node 24.18, sklearn 1.9.1 (exact pins in `requirements-lock.txt`), v3 model 10 MB, v1 rollback 7.6 MB, Tesseract 5.4 binary required, OpenCV headless, Chromium ~0.7 GB (sandbox only). RAM per worker and container size not measured. |
| D2 | API Dockerfile | MISSING | `docker/` is empty. |
| D2 | Sandbox worker image | MISSING | Worker is currently a subprocess of the API host (`services.worker.sandbox_worker`), not a separate container. |
| D2 | Non-root/minimal/pinned/health checks/limits | MISSING | Depends on Dockerfiles. Pinned Python deps exist (lock file); `requirements.txt` itself is unpinned. |
| D2 | **Model artifacts in the image** | DECISION NEEDED | `.joblib` files are git-ignored. The image build must COPY the promoted artifact from a trusted location (hash `d69aadbb...`) or regenerate it (functionally identical, bytes differ; `docs/REPRODUCIBILITY.md`). |
| D3 | Env-driven config | PARTIAL | Read today: `TRUSTSHIELD_ENV`, `TRUSTSHIELD_ML(_MODEL)`, `TRUSTSHIELD_FUSION`, `TESSERACT_CMD`, `MAX_UPLOAD_SIZE`, `ENABLE_WEB_SANDBOX`, `TRUSTSHIELD_OCR_*`, `TRUSTSHIELD_SANDBOX_*`. Missing/unused: `CORS_ALLOWED_ORIGINS` (CORS is hard-coded to `http://localhost:3000`), `PUBLIC_API_URL` (frontend uses build-time `NEXT_PUBLIC_API_URL`, falling back to localhost), `DATABASE_URL` and `ENABLE_EXTERNAL_REPUTATION` (declared in `.env.example`, unused), `WEB_SCAN_TIMEOUT` (declared, sandbox uses `TRUSTSHIELD_SANDBOX_*`). |
| D3 | `.env.example`, no secrets committed | PARTIAL | File exists but is incomplete and stale (no CORS/PUBLIC_API_URL/model/Tesseract). No secrets found in tracked files. |
| D4 | Request size limit | PARTIAL | Upload read capped at `MAX_UPLOAD_SIZE` (5 MiB) + 1 byte; text capped at 20,000 chars. No server-level body limit before parsing (needs reverse proxy). |
| D4 | MIME / image validation | DONE | Decoded-pixel validation, declared MIME/filename not trusted, PNG/JPEG/WebP only. |
| D4 | Image dimension limits | DONE | `MAX_PIXELS` 25 MP and `MAX_SIDE` enforced, Pillow bomb guard. |
| D4 | Request timeouts | PARTIAL | OCR timeout (30 s) and sandbox timeouts exist; no per-request overall timeout, no server keep-alive/read timeouts configured. |
| D4 | Rate limiting | PARTIAL | Only on sandbox analysis (in-memory, per client host, breaks behind a proxy without trusted forwarded headers). Text/URL/image/QR unlimited (`THREAT_MODEL.md` says so). |
| D4 | Structured errors, no tracebacks | PARTIAL | Image/input errors are structured 4xx; no global exception handler (unhandled 500 shows a generic body but tracebacks go to server logs, not verified for prod mode). |
| D4 | CORS restricted to deployed frontend | MISSING | Hard-coded localhost origin. |
| D4 | Security headers | MISSING | No CSP/HSTS/X-Content-Type-Options/frame options on API or Next.js. |
| D4 | Temp upload cleanup | PARTIAL | Uploads are processed in memory (no disk writes found); sandbox temp dirs are removed. Needs an explicit test. |
| D4 | Health / readiness | PARTIAL | `GET /api/v1/health` reports module state; no separate readiness/liveness contract, no model-loaded gate. |
| D5 | Persistence decision | PARTIAL | Bounded in-memory store (200 results, lost on restart). Adequate for the demo if the API runs as **one instance**; sandbox polling needs the same instance. `DATABASE_URL` is a leftover; no DB code. Decision needed: stay stateless for Milestone A (recommended) or add PostgreSQL. |
| D6 | Frontend deploy readiness | PARTIAL | Production build passes; API URL env-driven but with a localhost fallback baked at build time; footer/"prototype" label missing; unreachable-API handling exists (error message) but no health/capability banner beyond ML/OCR notices. |
| D7 | Backend deployment | MISSING | No provider chosen; needs Docker + Tesseract + about 1 GB RAM headroom (to be measured) + HTTPS. |
| D8 | Sandbox deployment | PARTIAL | Reviewed twice (adversarial reviews fixed; 331 tests); authenticated proxy, SSRF/redirect/DNS pinning, Job Object (Windows only), quotas, download cleanup all implemented. Missing for production: Linux containment (Job Object is Windows-only), separate container/network egress policy, authenticated API-to-worker jobs across a process boundary (currently a local subprocess), queue. Stays off. |
| D9 | CI/CD | MISSING | No `.github/workflows`. Local commands exist (pytest 331, tsc, lint, build, E2E). No dependency audit or secret scanning configured. |
| D10 | Production preflight | PARTIAL | `scripts/preflight.py` checks default model, artifact hash, threshold 0.16, Tesseract, deps, sandbox off, ports. Missing: env-profile awareness (dev config flags, `TRUSTSHIELD_ENV=production`), CORS/PUBLIC_API_URL, writable temp dir, required dirs, DB, remote-URL mode. |
| D11 | Staging pipeline | MISSING | Nothing beyond local. E2E script exists (`tests/e2e/run_browser_e2e.py`) but targets localhost only. |
| D12 | Shareable URL + footer | MISSING | No public URL, no status footer. |
| D13 | Monitoring | MISSING | No request IDs, timings, module-failure logging, or log redaction policy. |
| D14 | External-device verification | MISSING | Needs deployed URLs. Local equivalents (8-step E2E + degraded scenario) exist. |
| Accuracy | Model benchmark branch | MISSING | Only word/char TF-IDF linear models were studied (v1, v3). No embeddings/transformer benchmarks. Constraint recorded: v1 test and v2 holdout are spent; any replacement needs a new sealed holdout and a new dataset-quality pass (real Indian fraud, real legitimate financial messages, hard negatives). |

Already satisfied and reusable: v3 default with rollback, health output, image validation, SSRF/URL controls, sandbox off by
default with a safety preflight, release manifest and reproducibility docs, regression tests, degraded-mode UI banner.

## Risks to settle before D2 starts

1. **Model artifact supply** into the Docker image (see D2 row).
2. **Single-instance assumption**: in-memory results and sandbox polling break with multiple replicas or restarts; acceptable for
   Milestone A only if pinned to one instance and documented.
3. **Rate limiting behind a proxy**: keying on `request.client.host` sees only the proxy; needs trusted forwarded headers or an
   edge limiter, plus limits on the non-sandbox endpoints (OCR is CPU-heavy).
4. **Serverless is a poor fit** for the API (Tesseract binary, OpenCV, ~100 MB+ native stack, CPU-bound OCR with 30 s timeouts,
   in-process model load, subprocess execution); the frontend alone suits serverless/static hosting.
5. **Platform choice and cost** (need your decision): a container host with HTTPS for the API and a separate host for Next.js;
   free tiers may not have enough RAM for ML plus OCR.

## Proposed order (for your approval; nothing started)

1. D1 architecture doc with measured RAM/size numbers, and decisions above.
2. D3 config + D4 hardening in code (local, testable): CORS/PUBLIC_API_URL/env profiles, security headers, request IDs and
   redacted structured logging, readiness endpoint, limits on all endpoints, global error handler, footer.
3. D2 Dockerfiles (API and separate sandbox image, sandbox unused), D10 preflight extension, D9 CI workflow.
4. Provider and domain decision, then D11 staging on the same images, browser E2E against staging, then production (D7, D6, D12).
5. D14 verification from another network. D8 sandbox only after your explicit approval.
6. Accuracy track proceeds in parallel as research only, on a new dataset plan with a new sealed holdout.

## Decisions I need from you before implementation

- Hosting for the API (container-capable, about 1-2 GB RAM) and for the frontend; any budget limit; whether you already have
  accounts (I will not create accounts or change hosting settings without approval).
- Whether the demo may be stateless (recommended) or needs durable history.
- Where the promoted model artifact should be sourced for image builds.
- Preferred domain/subdomain, or the provider's default URL.
