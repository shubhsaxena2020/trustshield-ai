# TrustShield AI — Project Spec

**Team:** Scam Scan · **Event:** PSIT Tech Expo 2026 · **Tagline:** Think Before You Trust.

## Product definition

TrustShield analyzes suspicious text, screenshots, URLs, and QR codes before the user interacts. Independent detectors emit structured evidence; fusion produces a risk score and separate confidence; the explanation layer restates evidence in plain language.

## Non-negotiables

- Detection is deterministic rules plus static URL analysis and a calibrated text classifier used as provisional evidence. No LLM decides a verdict at request time.
- Risk score and confidence are separate. `unable_to_verify` is a valid outcome.
- Mocked or unavailable modules are labelled as such.
- No real-world fraud-detection accuracy or performance claims are made; the recorded dataset metrics are confined to the Phase 3 report, and OCR measurements are confined to the recorded validation benchmark.
- Unknown URLs are never opened in the user's normal browser; QR payloads are not launched; malware is never executed on the development machine.

## Status — PHASE 3 CLOSED

Phase 3 text ML integration and the single coordinator-owned v1 held-out test
evaluation are closed. The v1 test record is frozen under
`models/evaluation/test_v1_frozen/`; its original files remain under
`models/evaluation/test/` for existing scripts and documentation. Future model
evaluation must use a new `trustshield-text-v2-holdout` and a new access log.

| Capability | Status |
|---|---|
| Text normalization and scam rules | Implemented (Phase 1) |
| URL static analysis and brand/domain logic | Implemented (Phase 1) |
| SSRF guard for a future fetch path | Implemented and tested; no current URL fetch |
| Evidence schema, fusion, confidence, and template explanation | Implemented |
| FastAPI health, text, URL, image, QR, and analysis retrieval | Implemented |
| Image validation and safety checks | Implemented (Phase 2) |
| OCRProvider, real TesseractProvider, and strictly demo/test-only MockOCRProvider | Implemented (Phase 2.1) |
| OpenCV QR decoding and payload classification | Implemented (Phase 2) |
| UPI structural parsing without payment execution | Implemented (Phase 2) |
| OCR-quality-aware confidence and OCR provenance | Implemented (Phase 2.1) |
| Synthetic OCR corpus, WER/CER/token metrics, and real-Tesseract benchmark | Implemented (Phase 2.1) |
| Frontend Text, URL, Screenshot, and QR Code tabs | Implemented (Phase 2) |
| OCR/QR result panels and technical JSON | Implemented (Phase 2) |
| Synthetic local demo fixtures and generator | Implemented (Phase 2) |
| Dataset v1 curation, caps, provenance, and leakage assertions | Closed (Phase 3; v1 provenance recorded) |
| Calibrated ML classifier, safe loading, pipeline/API integration, and evaluation | Closed (Phase 3; v1 model/threshold frozen) |
| Final-test reporting from saved predictions, calibration, cluster bootstrap, slices, and manual review | Closed (Phase 3 reporting; no re-tuning; v1 test frozen) |

## Status — PLANNED

| Capability | Status |
|---|---|
| PaddleOCR provider | Planned |
| Hindi OCR | Planned |
| Isolated web sandbox | Planned |
| External reputation providers | Planned |
| Ollama-backed explanation support | Planned |
| SQLite persistence | Planned; current store is bounded in-memory |
