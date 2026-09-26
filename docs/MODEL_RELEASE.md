# Model release artifact process

Production never trains and never regenerates a model. The exact promoted joblib is delivered as a **trusted release artifact**
and verified byte-for-byte against `models/release/v3_release_manifest.json` (`models.trustshield_text_v3.artifact_sha256`).

## Publishing (once per promoted model)

1. Compute and confirm the hash: `python scripts/build_release_manifest.py` records it; the value must equal
   `d69aadbb758d186c8b33946895b456588cf8f5ce5eca504647d9e7a009c1189a` for the promoted v3.
2. Attach `models/trained/trustshield_text_v3.joblib` (and the v1 rollback for CI) to a GitHub Release of the repository as
   release assets. Note the asset URLs; store them as GitHub repository **variables** `MODEL_V3_URL` / `MODEL_V1_URL`
   (URLs are not secrets; integrity comes from the hash, not from the URL).

## Consuming

| where | how |
|---|---|
| Docker build | `docker build -f docker/api.Dockerfile --build-arg MODEL_ARTIFACT_URL=<https asset URL> .` or place the file in `release-artifacts/` (git-ignored). The build **fails** if the SHA-256 differs. |
| CI | `scripts/fetch_model_artifact.py --url $MODEL_V3_URL` before tests, preflight and the container job. |
| Render | the image already contains the verified artifact; nothing is downloaded at runtime. |
| API startup (staging/production) | re-verifies hash, load, identity, threshold 0.16 and sklearn; aborts on any mismatch. |

`scripts/fetch_model_artifact.py` accepts HTTPS only, refuses non-HTTPS redirects, caps size, writes atomically, and installs
nothing when the hash differs.

## Recovery only

If the release asset is lost, `docs/REPRODUCIBILITY.md` describes deterministic functional regeneration from the committed data.
A regenerated artifact is functionally identical but **not** byte-identical, so it will fail the hash gate by design; using it
requires an explicit, reviewed update of the release manifest, never a silent bypass.
