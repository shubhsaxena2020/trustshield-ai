# Deployment

Written for: whoever runs the service, for the expo booth or for any other environment.

## Options

**Expo booth (local, recommended for the screening and demo).** Backend, frontend and the 9Router LLM relay run on the
presenter's laptop. Only the frontend is exposed, through a Cloudflare quick tunnel. Full steps are in
`docs/EXPO_DEPLOYMENT_RUNBOOK.md`. This is the setup that was verified end to end on 2026-10-02.

**Container.** `docker/api.Dockerfile` builds the API image: non-root user, read-only root filesystem, dropped
capabilities, pinned Python 3.13 base, Tesseract installed, 2 GB and 1 CPU limits. Measured sizing: idle about 213 MB,
worst supported load about 831 MB, so 1 CPU and 2 GB is sufficient.

**Hosted (Vercel).** The frontend and a Python function share one origin. Preview only; it was not used for the expo.
Details in `docs/VERCEL_DEPLOYMENT.md`, which describes an earlier branch and should be checked before reuse.

## Promotion rules

1. Build the same image digest and the same frontend commit for every environment.
2. Staging runs the production profile (`TRUSTSHIELD_ENV=staging`) and must pass `scripts/preflight.py --profile production`
   and `scripts/smoke_remote.py` before promotion.
3. Production promotion needs explicit approval.

## Before any deployment

- `scripts/preflight.py` checks the model artifact (hash, load, threshold), OCR, the API environment and the frontend.
- `scripts/verify_v3_release.py` checks the release manifest against the artifact.
- The full test suite passes (see TESTING.md).

## Things that must not be deployed

- 9Router, or any port that reaches it. Two published vulnerabilities make it unsafe to expose, and no tunnel changes
  that (see SECURITY.md).
- The sandboxed-browser component. It was removed, not disabled.
