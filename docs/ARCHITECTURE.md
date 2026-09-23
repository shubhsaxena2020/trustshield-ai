# Architecture

TrustShield processes content through modality-specific extraction, shared deterministic analysis, evidence fusion, and a common result schema:

```text
input -> validate -> extract -> normalize/analyze -> evidence -> fusion -> explanation -> AnalysisResult
```

## IMPLEMENTED

### Shared result and analysis layers

- `services/analysis/evidence/models.py` defines structured evidence with source, category, severity, detector confidence, description, and optional metadata.
- `services/analysis/pipeline.py` orchestrates all input types. Text extracted from an image or QR payload is sent through the same normalization and text-rule pipeline used by direct text input.
- `services/analysis/rules/text_rules.py` detects deterministic scam signals, including credential requests, urgency, fake-job/payment signals, and negation-aware security advice.
- `services/analysis/url_analysis/static.py` analyzes URLs as strings. It checks schemes, domains, brand/domain relationships, IP hosts, ports, userinfo, lookalikes, and file extensions without fetching the destination.
- `services/analysis/fusion/engine.py` combines evidence into a risk score and label, while calculating a separate `high`, `medium`, or `low` confidence from module coverage and quality. OCR quality is included in that confidence calculation.
- `services/analysis/explanation/template.py` produces a deterministic explanation from evidence and limitations. The explanation layer does not decide the verdict.
- `services/analysis/fusion/result.py` returns schema version `1.0` with `risk`, `classification`, `evidence`, `modules`, `explanation`, `recommended_actions`, and `limitations`.

### Screenshot path

1. `image_validation.py` reads the bounded upload, ignores the filename as a decoder hint, checks the declared MIME type, decodes the actual image format, checks dimensions, handles EXIF orientation, and produces an RGB image.
2. `ocr.py` selects the provider from `TRUSTSHIELD_OCR_PROVIDER`; the default is `TesseractProvider`, and `MockOCRProvider` is explicitly intended for tests/demos.
3. The OCR module records provider, provider version, extracted text, provider confidence when available, warnings, and image dimensions/format.
4. OCR text is passed to the shared text and static URL analyzers. If OCR is unavailable, fails, or returns no text, the result is structured as unable to verify rather than treated as verified content.

### QR path

1. The same image validation runs before QR decoding.
2. `qr.py` uses `OpenCVQRDecoder` with several local image variants and reports the first payload when multiple codes are detected, with a warning.
3. Payloads are classified as `url`, `upi`, `text`, or `other`.
4. URL payloads go to static URL analysis and are never fetched. `javascript:` and `data:` payloads are routed into the URL analyzer as unsupported schemes; they are not launched.
5. UPI payloads are parsed structurally into fields such as payee VPA, payee name, amount, currency, note, merchant code, and mode. A UPI payment-request evidence item is emitted; no payment action is performed.
6. Plain text payloads go through the shared text rules when long enough. Other payloads receive non-web-payload evidence and are not executed.

### API, storage, and UI

- `services/api/main.py` exposes FastAPI routes for text, URL, image, QR, health, and retrieving an analysis by ID.
- Results are kept in a bounded in-memory `OrderedDict`; raw upload bytes are not persisted.
- `apps/web/app/page.tsx` provides Text, URL, Screenshot, and QR Code tabs. `FileUpload.tsx` performs client-side extension/type and size checks, while the server repeats validation.
- `OcrPanel.tsx`, `QrPanel.tsx`, and `ResultDashboard.tsx` display module status, extracted/decoded data, warnings, evidence, limitations, and technical JSON.
- `demo/fixtures` and `scripts/make_demo_fixtures.py` provide synthetic screenshots and QR images generated locally.

## PLANNED

The following are future seams or product work, not current execution paths:

- `PaddleOCRProvider` and Hindi OCR.
- A trained ML classifier.
- An isolated web sandbox for destination inspection.
- External reputation lookups.
- Ollama-backed explanations.
- SQLite metadata persistence.

The repository contains an SSRF validation module for a future fetch path, but the current URL and QR flows do not fetch destinations.
