# User workflow

## IMPLEMENTED

1. Start the API and web app, then open `http://localhost:3000`.
2. Choose one of four tabs: **Text**, **URL**, **Screenshot**, or **QR Code**.
3. For Text, paste a message. For URL, paste a link. The UI states that the link will not be opened.
4. For Screenshot or QR Code, choose or drag in a PNG, JPEG, or WebP image. The UI previews the file and sends it as multipart field `file`.
5. Press **Analyze Suspicious Content**.
6. Read the common result: risk label, score, confidence, primary category, explanation, expandable evidence, recommended actions, module coverage, limitations, and technical JSON.
7. Screenshot results also show the OCR provider, extracted text, OCR warnings, and image metadata. QR results show decoder, payload type, decoded payload, warnings, and parsed UPI details when present.

### Screenshot analysis

The API validates the image before OCR. With the default Tesseract provider, real OCR requires a separately installed Tesseract binary. If it is unavailable, the API returns an explicit `unable_to_verify` result with an OCR module status of `unavailable`. The UI may also show an OCR availability notice from `/api/v1/health`.

For a deterministic demo, set `TRUSTSHIELD_OCR_PROVIDER=mock` and upload a generated screenshot fixture. The mock reads the fixture's `trustshield_demo_text` metadata; it is not pixel-based OCR.

### QR analysis

QR payloads are decoded locally. A URL payload is analyzed as a string by the static analyzer and is never fetched. A UPI payload is parsed into payment fields and warning evidence, but no payment app or payment action is launched. Plain text is analyzed by the shared text rules when it is long enough; other payloads are shown as non-web data.

## PLANNED

The current workflow does not include a web sandbox, reputation lookup, ML classification, Ollama explanation, SQLite persistence, PaddleOCR, or Hindi OCR. These remain planned capabilities.
