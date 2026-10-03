# API

Written for: frontend developers and anyone calling the service. Base path `/api/v1`. Interactive docs are disabled in
staging and production and available only in development.

## Analysis

| Method | Path | Input | Returns |
|---|---|---|---|
| POST | `/api/v1/analyze/text` | JSON `{"text": string}`, max 20,000 chars | `AnalysisResult` |
| POST | `/api/v1/analyze/url` | JSON `{"url": string}`, max 2,048 chars | `AnalysisResult` |
| POST | `/api/v1/analyze/image` | multipart `file` (screenshot) | `AnalysisResult` |
| POST | `/api/v1/analyze/qr` | multipart `file` (QR image) | `AnalysisResult` |
| GET | `/api/v1/analysis/{analysis_id}` | — | a previously stored `AnalysisResult`, if still in the TTL store |

`AnalysisResult` carries `risk` (score, label, confidence), `classification`, `evidence` (each item with source,
category, severity, confidence, explanation), `modules` (status of each detector), `limitations`, and
`recommended_actions`.

Image and QR routes are rate-limited more tightly (heavy class). Analysis routes are limited per client, and uploads are
capped by `MAX_UPLOAD_SIZE` (default 5 MiB).

## Chat

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/chat` | JSON `message`, optional `context` (a previous analysis), `context_signature`, `history` |
| POST | `/api/v1/chat/upload` | multipart version with an optional image |

A chat response has `kind` (`chat`, `analysis`, `followup`), an `intent`, a `reply`, and, for analyses, the full
`analysis` plus a `context_signature`. The signature is what lets a follow-up be trusted; an unsigned or altered
context is discarded. See CHAT_ASSISTANT.md.

## Health

| Path | Purpose |
|---|---|
| `/healthz` | liveness |
| `/readyz` | ready only when the ML model and OCR are available |
| `/api/v1/health` | capabilities, including model name and OCR provider |

## Errors

Validation failures return 422 with messages that never echo the input. Oversize bodies return 413, rate limits 429
with `Retry-After`, timeouts 504, and an overloaded heavy queue 503. Responses carry a request ID.
