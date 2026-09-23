# API

Base URL for local development: `http://localhost:8000`.

Every successful analysis endpoint returns the versioned `AnalysisResult` shape. The server keeps the result in a bounded in-memory store and does not persist raw input content.

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
    "text": "extracted text",
    "warnings": [],
    "image": {"width": 1500, "height": 900, "format": "PNG"}
  }
}
```

`provider_version` and `confidence` can be `null`. The mock provider reports a demo warning and does not produce a pixel-recognition confidence. Extracted text is bounded in the response. A failed/unavailable OCR module has the same fields where available and an explanatory `detail`.

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
