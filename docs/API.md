# API

Base URL for local development: `http://localhost:8000`.

Every successful analysis endpoint returns the versioned `AnalysisResult` shape. Raw image bytes are never stored. The server retains each structured result, including OCR `raw_text` and `normalized_text` when present, in a bounded in-memory store of 200 results; entries are lost on restart.

## IMPLEMENTED endpoints

### `GET /api/v1/health`

Returns capability information:

```json
{
  "status": "ok",
  "schema_version": "1.0",
  "modules": {
    "text": true,
    "url_static": true,
    "ml": false,
    "ocr": {"available": true, "provider": "tesseract"},
    "qr": true,
    "web_sandbox": false
  }
}
```

`modules.ocr.available` reflects the selected provider. Its provider is normally `tesseract`, or `mock` when `TRUSTSHIELD_OCR_PROVIDER=mock` is set. The mock is a demo/test provider and is not real OCR.

### `POST /api/v1/analyze/text`

JSON request:

```json
{"text": "message to analyze"}
```

The text field is limited by the API model. The result uses `input_type: "text"`.

### `POST /api/v1/analyze/url`

JSON request:

```json
{"url": "https://example.com/path"}
```

The URL is parsed and analyzed as a string. It is not fetched. The result uses `input_type: "url"`.

### `POST /api/v1/analyze/image`

Multipart request with one field:

```text
file=<PNG, JPEG, or WebP image>
```

The server validates the decoded image, then runs the selected OCR provider and sends extracted text through the shared text/static URL pipeline. If Tesseract is unavailable, the HTTP request can still succeed with a structured `unable_to_verify` result and `modules.ocr.status: "unavailable"`.
OCR text shorter than the shared 15-character minimum follows the same rule as pasted text: text rules fail and the image can resolve to `unable_to_verify`.

### `POST /api/v1/analyze/qr`

Multipart request with the same `file` field and supported image formats. The server validates the image, decodes a QR locally with OpenCV, classifies the payload, and analyzes it without fetching or launching it.

### `GET /api/v1/analysis/{analysis_id}`

Returns a previously stored `AnalysisResult`, or HTTP 404 when the ID is not in the bounded in-memory store.

## Common response shape

```json
{
  "schema_version": "1.0",
  "analysis_id": "...",
  "created_at": "...",
  "input_type": "text|url|image|qr",
  "status": "completed|partial|failed",
  "risk": {
    "score": 0,
    "label": "low_risk|caution|suspicious|high_risk|unable_to_verify",
    "confidence": "high|medium|low"
  },
  "classification": {"primary": "...", "secondary": []},
  "evidence": [],
  "modules": {},
  "explanation": "...",
  "recommended_actions": [],
  "limitations": []
}
```

`score` is an implementation output, not an accuracy or probability claim. Evidence entries contain detector source, category, severity, detector confidence, title, description, and optional metadata.

## Nested image and QR module data

Each module has `status`, `detail`, and `data`. Module statuses are `completed`, `not_run`, `unavailable`, `failed`, or `timeout`.

### `modules.ocr`

For an image analysis, `modules.ocr` has this data shape:

```json
{
  "status": "completed",
  "detail": "Tesseract OCR completed.",
  "data": {
    "provider": "tesseract|mock",
    "provider_version": "...",
    "confidence": 87.5,
    "raw_text": "engine output",
    "normalized_text": "text passed to text rules",
    "text": "engine output",
    "warnings": [],
    "preprocessing": "current",
    "tesseract_cmd_source": "env:TESSERACT_CMD|path|default-location",
    "image": {"width": 1500, "height": 900, "format": "PNG"}
  }
}
```

`provider_version` and `confidence` can be `null`; confidence is the mean of usable word-level engine confidences on a 0–100 scale. The real Tesseract provider invokes the text and confidence paths separately, so one request can launch two engine processes; if the confidence path fails, extracted text is retained with `confidence: null` and a warning. `raw_text` is the bounded engine output (up to 20,000 characters), and `text` is its backwards-compatible alias. `normalized_text` is the exact normalized string sent to the text rules, also bounded. `preprocessing` records the selected mode: `none`, `current`, `gray`, `gray_autocontrast`, or `gray_resize2x`. `tesseract_cmd_source` is only a source label and never exposes a filesystem path: `env:TESSERACT_CMD`, `path`, or `default-location`. The mock provider reports a demo warning and does not produce a pixel-recognition confidence. A failed/unavailable OCR module has the same fields where available and an explanatory `detail`.

### `modules.qr`

For a QR analysis, `modules.qr` has this data shape:

```json
{
  "status": "completed",
  "detail": "QR payload decoded.",
  "data": {
    "decoder": "opencv",
    "payload": "https://example.com/path",
    "payload_type": "url|upi|text|other",
    "warnings": []
  }
}
```

The payload is bounded in the response. A decoded UPI payload adds:

```json
{
  "upi": {
    "payee_vpa": "merchant@upi",
    "payee_name": "Demo Store",
    "amount": "125.50",
    "currency": "INR",
    "note": "Order payment",
    "merchant_code": "5812",
    "mode": "02"
  }
}
```

UPI values can be `null` when absent or when an oversized field is discarded; malformed values may remain visible alongside warning evidence. A failed decoder returns `status: "failed"`, `payload_type: null`, and a detail explaining that no QR was detected. Multiple detected codes produce a warning and the first decoded payload is analyzed.

Image and QR analyses can also include the shared `text_rules`, `url_static`, `ml`, `reputation`, and `web_sandbox` modules depending on the decoded content. In the current implementation, ML is unavailable, reputation is unavailable when a URL is present, and the web sandbox is not run.

## Image upload errors

The image and QR endpoints return FastAPI's JSON error form, normally `{"detail": "..."}`:

- `413 upload_too_large`: the request exceeds the configured `MAX_UPLOAD_SIZE` limit.
- `415 unsupported_mime`: the declared MIME type is not PNG, JPEG, or WebP.
- `415 unsupported_format`: the decoded format is not supported.
- `415 mime_mismatch`: the declared MIME type does not match the decoded image.
- `422 empty_image`: the upload has no bytes.
- `422 undecodable_image`: the bytes are not a valid decodable image.
- `422 decompression_bomb`: Pillow rejects the image as unsafe to decode.
- `422 invalid_dimensions` or `422 dimensions_too_large`: decoded dimensions are invalid or exceed the image safety limits.

FastAPI validation, including a missing multipart `file`, also uses HTTP 422. The client-side file checks do not replace server-side validation.

## PLANNED

There are no implemented API routes for PaddleOCR, ML classification, web sandbox inspection, reputation services, Ollama, SQLite persistence, or Hindi OCR. They are planned extensions and are not advertised by the current health response.
