# Demo plan

The demo uses synthetic local fixtures only. Do not use live scam sites, live payment QR codes, or real personal data.

## IMPLEMENTED demo flow

### Prepare

1. Start the API and web app as described in the root README.
2. For deterministic screenshot results, set `TRUSTSHIELD_OCR_PROVIDER=mock` before starting the API. This provider reads fixture metadata and is not real OCR.
3. If fixtures are missing or need refreshing, run `.venv\Scripts\python scripts\make_demo_fixtures.py`.
4. Open the Screenshot or QR Code tab and upload one fixture at a time.

### Screenshot fixtures

| Fixture | Demo action | Expected qualitative behaviour |
|---|---|---|
| `kyc_screenshot.png` | Screenshot tab | Mock OCR exposes the synthetic KYC message. Evidence should include credential request/pressure and a claimed-bank/domain mismatch for the suspicious link. Show the OCR warning and explain that the result is based on metadata in mock mode. |
| `job_screenshot.png` | Screenshot tab | Mock OCR exposes the fake job message. Evidence should include fake-job, payment-request, and job-fee signals. |
| `legit_screenshot.png` | Screenshot tab; use as the legitimate control | OCR exposes the debit alert and the instruction never to share an OTP/PIN. It should not produce a credential-request signal or a high-risk phishing verdict. Treat this as a control example, not as a promise that every legitimate message will be classified correctly. |

### QR fixtures

| Fixture | Demo action | Expected qualitative behaviour |
|---|---|---|
| `qr_suspicious_url.png` | QR Code tab | OpenCV decodes a URL. Static analysis should expose the suspicious bank-impersonation/domain-mismatch evidence. The URL is displayed/analyzed but never fetched. |
| `qr_benign.png` | QR Code tab | OpenCV decodes a normal Wikipedia URL. No claimed-brand mismatch is expected. The destination is still not fetched. |
| `qr_text.png` | QR Code tab | The payload is classified as text, shown as non-web data, and is not launched. If long enough, it also enters the shared text rules. |
| `qr_upi.png` | QR Code tab | The payload is classified as UPI and parsed into the demo payee, amount, currency, note, merchant code, and mode. Payment-request evidence is shown; no payment action occurs. |

The UI result is evidence-first: show the risk label, separate confidence, evidence categories, module statuses, warnings, limitations, and technical JSON. Do not present a numeric score as an accuracy or probability claim, and do not promise any score for a fixture.

## Mock versus real OCR

With `TRUSTSHIELD_OCR_PROVIDER=mock`, the screenshot fixtures are deterministic because their PNG metadata contains the source text. The mock does not recognise pixels and is not a production OCR provider. To demonstrate the real path, unset the variable, set `TESSERACT_CMD` to the intended executable, and rerun the same screenshot fixtures. The validation benchmark records the selected binary and version, and the canonical OCR module records raw/normalized text, preprocessing, confidence, and command-source provenance. Real OCR output, warnings, and confidence can differ; if Tesseract is unavailable, the expected result is `unable_to_verify` with OCR status `unavailable`.

## PLANNED demo extensions

Future demos may cover PaddleOCR/Hindi OCR, a trained ML classifier, isolated web inspection, reputation lookups, Ollama explanations, and SQLite-backed history. None of those are part of the current demo.
