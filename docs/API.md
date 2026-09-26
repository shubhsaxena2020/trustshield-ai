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
    "ml": {"available": true, "model_name": "trustshield_text_v3", "model_version": "phase3-1"},
    "ocr": {"available": true, "provider": "tesseract"},
    "qr": true,
    "web_sandbox": false
  }
}
```

`modules.ml.model_name` reports the active model: `trustshield_text_v3` by default, `trustshield_text_v1` when rolled back with `TRUSTSHIELD_ML_MODEL=trustshield_text_v1`.

`modules.ocr.available` reflects the selected provider. Its provider is normally `tesseract`, or `mock` when `TRUSTSHIELD_OCR_PROVIDER=mock` is set. The mock is a demo/test provider and is not real OCR.

When `TRUSTSHIELD_ML=off`, the health response keeps the documented
`available`, `model_name`, and `model_version` fields and reports:

```json
{"available": false, "reason": "disabled_by_env", "model_name": null, "model_version": null}
```

When enabled, health reuses the pipeline's cached classifier; it does not hash
or load the model on every request.

### `POST /api/v1/analyze/text`

JSON request:

```json
{"text": "message to analyze"}
```

The text field is limited by the API model. The result uses `input_type: "text"`.

### `POST /api/v1/analyze/url`

JSON request:

```json
{"url": "https://example.com/path", "sandbox": false}
```

The URL is parsed and analyzed as a string. It is not fetched. The result uses `input_type: "url"`.

Set `sandbox: true` to request the opt-in isolated browser probe. If
`ENABLE_WEB_SANDBOX` is disabled, the request remains static-only and reports
`modules.web_sandbox.status: unavailable`. When enabled, valid requests return
HTTP `202` with `status: sandboxing`; poll `GET /api/v1/analysis/{id}` until
the result is `completed` or `partial`. External browsing remains blocked
unless `TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL=true`; tests use a private,
programmatic fixture allow-list that the API does not accept from callers.

### `POST /api/v1/analyze/image`

Multipart request with one field:

```text
file=<PNG, JPEG, or WebP image>
```

The server validates the decoded image, then runs the selected OCR provider and sends extracted text through the shared text/static URL pipeline. If Tesseract is unavailable, the HTTP request can still succeed with a structured `unable_to_verify` result and `modules.ocr.status: "unavailable"`.
OCR text is analyzable when normalization leaves at least one alphanumeric token, regardless of character count. Very short OCR text runs through text and URL rules but includes the limitation `Limited context: the text is very short, so confidence is reduced.`; empty or punctuation-only OCR remains `unable_to_verify`.

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
  "status": "completed|partial|failed|sandboxing|queued",
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

With the Phase 4 default fusion, `modules.fusion` is an additive optional
module. Its data shape is:

```json
{
  "status": "completed",
  "detail": "",
  "data": {
    "strategy": "ml_aware_weighted",
    "version": "phase4-1",
    "strength_class_counts": {"weak": 0, "moderate": 1, "strong": 0},
    "agreement": "agree|ml_only|rules_only|both_negative",
    "disagreement": true,
    "drivers": ["moderate_ml"],
    "parameters": {"ml_weight": 0.5, "strong_ml_cut": 0.7}
  }
}
```

Set `TRUSTSHIELD_FUSION=legacy` to omit this optional module and retain the
legacy noisy-OR fusion. The existing `risk.score`, `risk.label`,
`risk.confidence`, evidence, and other module shapes remain unchanged.

## Nested image and QR module data

Each module has `status`, `detail`, and `data`. Module statuses are `completed`, `not_run`, `unavailable`, `failed`, `timeout`, `queued`, or `sandboxing`.

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

Image and QR analyses can also include the shared `text_rules`, `url_static`, `ml`, `reputation`, and `web_sandbox` modules depending on the decoded content. OCR text and analyzable QR text run the text model when it is available. URL-only inputs do not run the text model. With `TRUSTSHIELD_ML=off`, `modules.ml.status` is `not_run`; missing, tampered, corrupt, or incompatible artifacts use `unavailable` and include a reason while the scan still completes.

### `modules.ml`

When completed, `modules.ml.data` contains `model_name`, `model_version`,
`dataset_version`, `dataset_manifest_sha256`, `feature_pipeline_version`,
`calibration_version`, `p_malicious`, `threshold`, `predicted_binary`,
`category`, optional `category_probs`, and `latency_ms`. ML evidence uses
`source: "ml_classifier"` and is explicitly provisional. ML availability does
not raise the risk confidence above the fusion `CONFIDENCE_CAP` of `medium`.

Text, OCR, plain QR payloads, and UPI notes use the same analyzability rule: normalized content must contain at least one run of letters or digits. The `text_rules` module records a `context_quality` factor in its data, increasing linearly with alphanumeric-token count and reaching `1.0` at eight tokens. Below that point confidence is reduced, but the evidence-derived score label is retained; no-token content is `unable_to_verify` with a limitation explaining why.

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

There are no implemented API routes for PaddleOCR, reputation services, Ollama, SQLite persistence, or Hindi OCR. The opt-in web sandbox inspection is described above; the text ML classifier is an internal analysis module, not a separate route; the final test-set evaluation remains pending.
