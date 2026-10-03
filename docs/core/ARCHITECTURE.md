# Architecture

Written for: engineers working on the code.

## Principle: two systems, kept apart

The **security core** decides the verdict. The **conversational layer** explains it. The conversational layer never
changes a score, a label or an evidence item, and a language model is never the thing that decides whether a message is
fraud. This separation is the reason the system can be trusted, and it is enforced in code, not only in documentation.

## Pipeline

```
input (text | URL | image | QR)
  -> normalisation (text, URL parsing, OCR, QR decode)
  -> detectors
       rules        deterministic scam patterns, URL structure, brand/domain checks
       ML           trustshield_text_v3 classifier (text only)
  -> evidence       every finding is an Evidence item: source, category, severity, confidence
  -> fusion         severity-weighted noisy-OR risk score, band, classification, confidence
  -> AnalysisResult (the only thing the rest of the system reads)
  -> chat layer     explains the AnalysisResult; grounding checks reject any reply that
                    claims more than the evidence supports
```

## Layout

| Path | Responsibility |
|---|---|
| `services/analysis/` | detection, evidence, fusion, normalisation, OCR, QR, URL analysis, ML model loading |
| `services/chat/` | intent routing, conversational replies, explanation providers, signed context |
| `services/api/` | FastAPI app, settings, startup checks, rate limits, result store |
| `services/analysis/fusion/engine.py` | the legacy fusion path, kept as-is |
| `services/analysis/fusion/strategies.py` | the active pluggable strategy (`ml_aware_weighted`) |
| `apps/web/` | Next.js frontend |
| `models/release/` | the released model artifact and its manifests |
| `scripts/` | operational tools: training, evaluation, release verification, preflight, smoke tests |
| `archive/research/` | one-off research scripts, kept for reference, not run |
| `tests/` | unit, integration, security and end-to-end tests |

## Design rules that are load-bearing

- **Static-only URL analysis.** The system never opens the destination of a link. Anything that would require doing so
  (the sandboxed browser) was removed; see DECISIONS.md.
- **Stateless by default.** Results are held in a bounded in-memory store with a TTL. Uploaded images are processed in
  memory and not stored.
- **Honest confidence.** Confidence reflects coverage and agreement, and is intentionally capped; see DETECTION.md.
- **Fail visibly.** A missing model or OCR binary fails startup in staging and production instead of degrading silently.

## Known gaps

- Fusion confidence is capped at `medium` by a hardcoded clamp that is not yet lifted. See DETECTION.md and DECISIONS.md.
- Rate limits are in memory and per process.
