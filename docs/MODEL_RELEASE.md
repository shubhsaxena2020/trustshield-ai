# Model release artifact process

Production never trains and never regenerates a model. The exact promoted joblib is delivered as a **trusted release artifact**
and verified byte-for-byte against `models/release/v3_release_manifest.json` (`models.trustshield_text_v3.artifact_sha256`).

## Publishing (once per promoted model)

1. Confirm the hash recorded by `python scripts/build_release_manifest.py` equals
   `d69aadbb758d186c8b33946895b456588cf8f5ce5eca504647d9e7a009c1189a` for the promoted v3.
2. Create a **private** GitHub Release (tag `model-v3`) on the repository and attach `trustshield_text_v3.joblib`,
   `trustshield_text_v1.joblib` (rollback) and `SHA256SUMS`. Release assets of a private repository are private.

## Authenticated retrieval (server-side, narrowly scoped)

| where | credential | how |
|---|---|---|
| GitHub Actions | the job's built-in `GITHUB_TOKEN` (`contents: read`) | `scripts/fetch_model_artifact.py --github-release owner/repo@model-v3` |
| Render Docker build | a **fine-grained personal access token**, single repository, permission *Contents: Read-only*, expiry <= 90 days, stored as a Render environment variable / secret `MODEL_FETCH_TOKEN` | `docker/api.Dockerfile` reads it as a BuildKit **secret** (`RUN --mount=type=secret,id=MODEL_FETCH_TOKEN`), never as `ARG`/`ENV`, so it cannot appear in image layers, `docker history` or build logs; `MODEL_RELEASE=owner/repo@model-v3` is a plain (non-secret) build argument |
| local build | `docker build --secret id=MODEL_FETCH_TOKEN,env=MODEL_FETCH_TOKEN --build-arg MODEL_RELEASE=owner/repo@model-v3 ...` | same script |

The fetcher sends the token only to `api.github.com`; the asset endpoint answers with a redirect to a pre-signed storage URL and the
`Authorization` header is dropped on that cross-host redirect (tested). The token is read from a file/env, never placed on a command
line, never printed (errors show only an HTTP status), and never written to disk. The token is not `NEXT_PUBLIC_*`, is not used by the
running API (the image already contains the verified artifact) and is never returned by any endpoint. Rotate it by replacing the Render
secret; revoke it in GitHub if exposed.

| step | check |
|---|---|
| fetch | HTTPS only, size-capped, atomic write |
| install | SHA-256 must equal the release manifest, otherwise nothing is installed and the build fails |
| API startup (staging/production) | re-verifies hash, load, identity, threshold 0.16 and sklearn; aborts on any mismatch |

## Vercel delivery (current): no credential on the platform

Vercel container builds receive project variables only as build `ARG`s (no BuildKit secret mounts), so the token is **not** given to
Vercel. The artifacts are fetched locally from the private release with the authenticated fetcher, verified, and shipped inside the
CLI-uploaded deployment bundle (`scripts/build_vercel_bundle.py`); `docker/Dockerfile.vercel` and API startup verify the hashes again.
Details: `docs/VERCEL_DEPLOYMENT.md`. The Render/BuildKit-secret path above remains valid for any host that supports secret mounts
(GitHub Actions, local Docker).

## Recovery only

If the release asset is lost, `docs/REPRODUCIBILITY.md` describes deterministic functional regeneration from the committed data.
A regenerated artifact is functionally identical but **not** byte-identical, so it will fail the hash gate by design; using it
requires an explicit, reviewed update of the release manifest, never a silent bypass.
