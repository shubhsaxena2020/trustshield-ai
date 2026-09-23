# TrustShield demo fixtures

These are **SYNTHETIC test/demo data** with fake entities. They are generated locally by `scripts/make_demo_fixtures.py`; no network access is used.

- `kyc_screenshot.png` — fake SBI KYC/OTP chat with a suspicious link
- `job_screenshot.png` — fake job offer requesting a registration fee
- `legit_screenshot.png` — genuine-looking debit alert warning never to share OTP
- `qr_suspicious_url.png` — fake SBI KYC URL payload
- `qr_benign.png` — Wikipedia QR payload
- `qr_text.png` — plain-text QR payload
- `qr_upi.png` — synthetic UPI payment QR payload

The screenshot PNGs contain their own text in the `trustshield_demo_text` metadata chunk for the opt-in mock OCR provider. Their pixels also contain rendered text for later real-Tesseract testing.
