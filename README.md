# TrustShield AI — Team Scam Scan

*Think Before You Trust.* TrustShield AI is a privacy-first multimodal cyber-fraud and phishing risk analyzer for text, URLs, screenshots, and QR codes. It is the PSIT Tech Expo 2026 project.

Phase 2 is implemented for screenshot OCR and QR decoding. The system reports structured evidence, a risk label, and a separate confidence level. It does not make accuracy or performance claims.

## IMPLEMENTED

- Text normalization, deterministic scam rules, static URL analysis, evidence, fusion, explanations, and recommended actions.
- Image validation before extraction. PNG, JPEG, and WebP are decoded and checked against the declared MIME type, upload-size limit, dimensions, and Pillow decoding safeguards.
- `OCRProvider`, `TesseractProvider`, and `MockOCRProvider`. Tesseract is the default provider. The mock provider is for tests and deterministic demos only; it reads the `trustshield_demo_text` PNG metadata field and does not recognise text from pixels.
- `QRDecoder` and `OpenCVQRDecoder`, payload classification, static URL analysis, `javascript:`/`data:` routing to the static analyzer, and structural UPI parsing.
- OCR-quality-aware confidence: reported OCR confidence affects fusion confidence; providers without a confidence value use a conservative quality factor.
- FastAPI endpoints for text, URL, screenshot, QR, health, and retrieving a bounded in-memory analysis result.
- Next.js UI tabs for Text, URL, Screenshot, and QR Code, plus OCR/QR result panels and technical JSON.
- Synthetic, local-only demo fixtures under `demo/fixtures`.

## PLANNED

These are not active capabilities in the current code:

- `PaddleOCRProvider` and Hindi OCR.
- A trained ML classifier.
- An isolated web sandbox.
- External reputation providers.
- Ollama-backed explanation support.
- SQLite persistence.

## Setup

From PowerShell in the repository root:

```powershell
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
```

### Optional Tesseract OCR on Windows

Tesseract is not installed automatically by this project. For real screenshot OCR, manually install a Windows Tesseract build from UB Mannheim. Add the Tesseract installation directory to `PATH`, or point TrustShield at the executable:

```powershell
$env:TESSERACT_CMD = 'C:\Program Files\Tesseract-OCR\tesseract.exe'
```

The provider also checks `C:\Program Files\Tesseract-OCR\tesseract.exe` by default. If Tesseract is unavailable, `/api/v1/analyze/image` returns an explicit structured result with `risk.label` set to `unable_to_verify` and `modules.ocr.status` set to `unavailable`. No OCR recognition is performed in that case.

### Deterministic demo OCR

To use the opt-in demo provider:

```powershell
$env:TRUSTSHIELD_OCR_PROVIDER = 'mock'
```

This is not real recognition. The mock reads text embedded in the generated screenshot metadata, so it should only be used with the synthetic fixtures or tests. It must not be treated as evidence that arbitrary pixels were read correctly.

Regenerate the synthetic fixtures locally, without network access:

```powershell
.venv\Scripts\python scripts\make_demo_fixtures.py
```

## Run the tests

```powershell
.venv\Scripts\python -m pytest -q
```

## Run the API

```powershell
.venv\Scripts\python -m uvicorn services.api.main:app --reload --port 8000
```

The API is served at `http://localhost:8000`. See [docs/API.md](docs/API.md) for the request and response contracts.

## Run the web app

In a second terminal:

```powershell
cd apps/web
npm install
npm run dev
```

The web app is served at `http://localhost:3000` and calls the API at `http://localhost:8000` by default. The browser UI does not open submitted URLs; QR destinations are analyzed as payloads only.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Workflow](docs/WORKFLOW.md)
- [API](docs/API.md)
- [Threat model](docs/THREAT_MODEL.md)
- [Demo plan](docs/DEMO_PLAN.md)
- [Project specification](docs/PROJECT_SPEC.md)
