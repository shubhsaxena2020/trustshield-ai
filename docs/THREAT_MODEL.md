# Threat model

TrustShield accepts attacker-controlled text, URLs, QR payloads, and image bytes. The current design favors local static analysis and explicit uncertainty. It does not claim to prove that content is safe.

## IMPLEMENTED controls

| Threat | Mitigation | Status |
|---|---|---|
| Image decompression bomb | Pillow bomb warnings/errors are converted into a structured rejection; decoded dimensions and pixel count are checked before extraction. | Implemented and covered by tests |
| Polyglot image or wrong declared MIME | The filename is not used to select a decoder. The server decodes the bytes, checks the actual format, and rejects a declared MIME mismatch. | Implemented and covered by tests |
| Oversized upload | The API reads only up to the configured upload limit plus a detection byte; the validator also checks decoded dimensions. | Implemented and covered by tests |
| Corrupt or undecodable image | Pillow decoding errors become HTTP 422 with an explicit error code; OCR/QR extraction is not reached. | Implemented and covered by tests |
| Malicious URL in a QR code | OpenCV decodes locally. URL payloads go to string-only static URL analysis; destinations are never fetched. | Implemented and covered by tests |
| `javascript:` or `data:` QR payload | The payload is classified as a URL-like payload and routed to the static analyzer as an unsupported scheme. It is never launched. | Implemented and covered by tests |
| UPI QR as a payment attack vector | UPI values are parsed structurally and surfaced as evidence and fields. No payment app, payment intent, or payment action is launched. | Implemented and covered by tests |
| OCR text containing instructions or prompt injection | OCR output is treated as untrusted data. It is normalized and passed to deterministic rules/static URL analysis; it is not executed as instructions. | Implemented |
| Mock-provider misuse | The provider name is exposed, the UI displays the demo warning, and the provider reads only the explicit demo metadata field. It is documented as test/demo-only. | Implemented; operational discipline still required |
| URL SSRF if a future fetch is added | `validate_public_url` blocks disallowed schemes, internal names, private/link-local/metadata destinations, numeric-IP tricks, credentials, and private DNS results. | Guard implemented and tested; no current fetch path |
| Raw content leakage through result storage | Raw image bytes are never stored. Structured results, including extracted OCR text when present, remain in a bounded in-memory store of 200 entries and are lost on restart. | Implemented |
| Model-artifact loading trust | The loader confines metadata/artifact paths below the project model directories, rejects non-`.joblib` paths, verifies the recorded SHA-256 before loading, checks the recorded sklearn version, and converts load/inference failures into `ml=unavailable`. | Implemented |
| Dataset poisoning or misleading public labels | v1 keeps only the two retained SMS sources, uses supplied per-row audits, records provenance/confidence, caps legitimate rows before grouping, and excludes the unaudited Hugging Face bulk labels. Remaining public ham outside the reviewed samples is explicitly marked not individually audited. | Implemented with documented residual limitation |
| Prompt/text injection in input | Not applicable to the model path: input text is feature data for deterministic rules/TF-IDF inference, not instructions to an LLM or tool executor. | Not applicable |
| ML evasion / style shortcut | Validation includes source-name breakdown, hard legitimate controls, synthetic holdout assignment, calibrated threshold selection, manual FP/FN review, and saved-prediction leakage sensitivity analysis. Obfuscation, near-duplicate template overlap, and unseen scam patterns remain residual risks. | Partially mitigated; leakage-aware test interpretation documented |

## PLANNED or not enabled

- A web sandbox for inspecting page content is not enabled. The current application does not fetch QR or user-supplied URLs.
- External reputation providers are not configured.
- The final test-set ML evaluation is frozen and was accessed once by the coordinator. Further reporting reads saved predictions only; the primary numbers remain affected by cross-split near-duplicate templates.
- Ollama explanations, SQLite persistence, PaddleOCR, and Hindi OCR are not implemented.

## Phase 5 web-sandbox controls

The sandbox is disabled unless `ENABLE_WEB_SANDBOX=true`, and external browsing
also requires `TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL=true`. The API never imports
Playwright; a scrubbed child process owns the ephemeral browser, proxy, temp
directory, and bounded telemetry. The guard rejects non-HTTP(S), credentials,
numeric-IP tricks, private/reserved/mapped/link-local/multicast/metadata
addresses, and non-80/443 ports in production. Every DNS answer is validated
before the proxy connects directly to the selected validated address, and
Playwright revalidates requests and redirect destinations.

The worker observes forms but never fills or submits them; non-GET/HEAD traffic
is aborted. Dialogs and popups are dismissed/closed, downloads are quarantined,
hashed, capped, and deleted, visible text is bounded and marked untrusted, and
worker failures reduce confidence through `partial`/`timeout` module status.
The explicit loopback fixture allowance is accepted only in test mode and only
through the test harness constructor; it is not an API field.

Residual risks: a Chromium exploit can still escape an application-level
worker; this Windows development setup has no Docker/microVM profile, kernel
egress firewall, reputation service, malware detonation, or persistent
screenshot policy. `--no-sandbox` is deliberately not used. Production must
add OS/container isolation and resource controls before enabling external
browsing.

## Residual gaps

- There is no rate limiting. An exposed deployment needs an edge/API rate limiter and request-level abuse controls.
- Analysis results are held in memory only. A process restart loses them, and the store is not a multi-instance persistence layer.
- Tesseract parses untrusted image data in the API process. The current validation limits exposure, but production hardening should isolate OCR work and enforce process/resource limits.
- OCR can misread text, and QR decoding can fail or select the first payload when multiple codes are present. The result therefore keeps provider/decoder warnings and separate confidence rather than claiming certainty.
- OCR text shorter than the shared 15-character minimum follows the pasted-text path and can yield `unable_to_verify` rather than a completed analysis.
- The mock provider can make a fixture look successfully extracted without recognising its pixels. It must never be used to assess real-world OCR quality.

## PLANNED: isolated OCR worker boundary

The following security boundary is planned and is not implemented in this phase:

`API -> isolated OCR worker (separate process/container, no network, resource limits, temp-dir cleanup, timeouts) -> Tesseract`

Arbitrary user images should not be parsed inside the main API process. Image decoding and OCR are complex parsers of untrusted bytes; a separate process or container can enforce resource and timeout limits, deny network access, clean temporary files, and contain a parser compromise without taking down the API process.

## Sandbox known limitations (Phase 5 MVP)

- The loopback egress proxy has no per-job authentication; any local process could use its ephemeral port while a job runs. It still validates every destination, so it cannot reach private ranges, but a shared host should add a proxy token before production use.
- The sandbox is process-isolated (scrubbed-environment subprocess, temp dir, project-local Chromium), not container or VM isolated. Chromium runs with WebRTC disabled and non-proxied UDP blocked.
- The web UI does not yet request sandbox analysis or poll the `sandboxing` status; it is API-only.
- `ENABLE_WEB_SANDBOX` and `TRUSTSHIELD_SANDBOX_ALLOW_EXTERNAL` default to off; live external browsing has not been exercised.
- Job dispatch is one thread per accepted job behind a thread-safe concurrency gate and a per-client rate limit; there is no persistent queue.
