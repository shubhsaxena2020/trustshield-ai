# Configuration

Written for: anyone running or deploying the service. Every variable below is read by the code. The template with
placeholder values is `.env.example`; this file explains what each one controls.

Environment profile: `TRUSTSHIELD_ENV` is `development`, `staging`, `production` or `test`. Staging and production run the
same hardened profile and refuse to start on bad configuration.

## Model and detection

| Variable | Default | Effect |
|---|---|---|
| `TRUSTSHIELD_ML` | `on` | `off` disables the classifier; refused in staging and production |
| `TRUSTSHIELD_ML_MODEL` | empty (promoted v3) | Override the model. `trustshield_text_v1` is the rollback and needs `TRUSTSHIELD_ROLLBACK_CONFIRM=v1` in staging and production |
| `TRUSTSHIELD_FUSION` | `ml_aware_weighted` | Fusion strategy; staging and production require the default |

## OCR and extraction

| Variable | Default | Effect |
|---|---|---|
| `TRUSTSHIELD_OCR_PROVIDER` | `tesseract` | `mock` is demo and test only; refused in staging and production |
| `TESSERACT_CMD` | `tesseract` on PATH | Path to the Tesseract binary |
| `TRUSTSHIELD_OCR_PREPROCESS` | `current` | Image preprocessing mode |
| `TRUSTSHIELD_OCR_TIMEOUT` | `30` | Seconds per OCR call |

## API and limits

| Variable | Default | Effect |
|---|---|---|
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` in development | Bare https origins in staging and production; no wildcard, no localhost |
| `PUBLIC_API_URL` | empty | Public origin of the API |
| `MAX_UPLOAD_SIZE` | `5242880` | Upload cap in bytes |
| `TRUSTSHIELD_TRUSTED_PROXY_HOPS` | `0` | Trusted reverse-proxy hops for client identity; wrong values weaken rate limiting |
| `TRUSTSHIELD_RATE_LIGHT_PER_MIN` | `120` | Per-client limit on light routes |
| `TRUSTSHIELD_RATE_HEAVY_PER_MIN` | `30` | Per-client limit on upload routes |
| `TRUSTSHIELD_HEAVY_CONCURRENCY` | `2` | Concurrent OCR and QR jobs |
| `TRUSTSHIELD_HEAVY_QUEUE_TIMEOUT` | `8` | Seconds a heavy job waits for a slot before 503 |
| `TRUSTSHIELD_REQUEST_TIMEOUT` | `45` | Per-request timeout in seconds |
| `TRUSTSHIELD_RESULT_STORE` | `all` (dev), `sandbox_only` (staging and production) | Retention policy; `sandbox_only` keeps no ordinary analyses |
| `TRUSTSHIELD_RESULT_TTL` | `900` | Seconds a stored result is kept |
| `TRUSTSHIELD_RESULT_MAX` | `200` | Maximum stored results |
| `TRUSTSHIELD_DEBUG_PROXY` | empty | `1` enables proxy diagnostics; refused in production by preflight |
| `ENABLE_EXTERNAL_REPUTATION` | `false` | Not implemented; must stay false |

## Frontend

| Variable | Effect |
|---|---|
| `NEXT_PUBLIC_API_URL` | Baked into the frontend build. Unset means same-origin `/api` |
| `API_PROXY_TARGET` | Build-time. When set, the Next.js server forwards `/api/*` to this backend. Used for the expo setup; see DEPLOYMENT.md |

## Conversational AI

| Variable | Effect |
|---|---|
| `TRUSTSHIELD_AI_PROVIDER` | `deterministic` (default), `remote`, or `ollama` (development only) |
| `TRUSTSHIELD_AI_BASE_URL` | OpenAI-compatible base URL. Loopback is allowed only outside staging and production; other http hosts are refused |
| `TRUSTSHIELD_AI_API_KEY` | Bearer token. Secret. Never commit, never prefix with `NEXT_PUBLIC_` |
| `TRUSTSHIELD_AI_MODEL` | Model for single-tier remote configuration |
| `TRUSTSHIELD_AI_PRIMARY_MODEL` | Setting this builds the multi-tier chain |
| `TRUSTSHIELD_AI_SECONDARY_MODEL` | Second tier of the chain, optional |
| `TRUSTSHIELD_OLLAMA_URL`, `TRUSTSHIELD_OLLAMA_MODEL` | Development-only Ollama provider |

## Signing

| Variable | Effect |
|---|---|
| `TRUSTSHIELD_CONTEXT_SECRET` | HMAC key that signs analysis context for follow-ups. Set a long random value outside tests |

## Other

| Variable | Effect |
|---|---|
| `DATABASE_URL` | Unused. The service is stateless |
| `GITHUB_TOKEN`, `MODEL_FETCH_TOKEN` | Used only by release tooling (`scripts/fetch_model_artifact.py`), not by the running service |

## Removed

`ENABLE_WEB_SANDBOX`, `TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL` and `WEB_SCAN_TIMEOUT` were removed with the sandboxed browser
(see DECISIONS.md). `TRUSTSHIELD_EXPLANATION_PROVIDER` was removed in favour of `TRUSTSHIELD_AI_PROVIDER`.
