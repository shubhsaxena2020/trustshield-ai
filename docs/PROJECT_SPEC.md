# TrustShield AI — Project Spec

**Team:** Scam Scan · **Event:** PSIT Tech Expo 2026 · **Tagline:** Think Before You Trust.

## Product definition

TrustShield analyzes suspicious text, screenshots, URLs, and QR codes before the user interacts. Independent detectors emit structured evidence; fusion produces a risk score and separate confidence; the explanation layer restates evidence in plain language.

## Non-negotiables

- Detection is deterministic rules plus static URL analysis in the current implementation. The LLM never decides a verdict.
- Risk score and confidence are separate. `unable_to_verify` is a valid outcome.
- Mocked or unavailable modules are labelled as such.
- No accuracy or performance numbers are claimed in project documentation.
- Unknown URLs are never opened in the user's normal browser; QR payloads are not launched; malware is never executed on the development machine.

## Status — IMPLEMENTED

| Capability | Status |
|---|---|
| Text normalization and scam rules | Implemented (Phase 1) |
| URL static analysis and brand/domain logic | Implemented (Phase 1) |
| SSRF guard for a future fetch path | Implemented and tested; no current URL fetch |
| Evidence schema, fusion, confidence, and template explanation | Implemented |
| FastAPI health, text, URL, image, QR, and analysis retrieval | Implemented |
| Image validation and safety checks | Implemented (Phase 2) |
| OCRProvider, TesseractProvider, and demo-only MockOCRProvider | Implemented (Phase 2) |
| OpenCV QR decoding and payload classification | Implemented (Phase 2) |
| UPI structural parsing without payment execution | Implemented (Phase 2) |
| OCR-quality-aware confidence | Implemented (Phase 2) |
| Frontend Text, URL, Screenshot, and QR Code tabs | Implemented (Phase 2) |
| OCR/QR result panels and technical JSON | Implemented (Phase 2) |
| Synthetic local demo fixtures and generator | Implemented (Phase 2) |

## Status — PLANNED

| Capability | Status |
|---|---|
| PaddleOCR provider | Planned |
| Hindi OCR | Planned |
| ML classifier and evaluation | Planned |
| Isolated web sandbox | Planned |
| External reputation providers | Planned |
| Ollama-backed explanation support | Planned |
| SQLite persistence | Planned; current store is bounded in-memory |
