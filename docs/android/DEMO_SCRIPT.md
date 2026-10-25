# Android three-minute demo script

Use synthetic text only. The app analyzes locally; it does not contact a bank, open a shared URL, make a payment, capture cellular/VoIP audio, or retain the message after the in-memory result is cleared.

## 0:00â€“0:25 â€” Set the scope

Open TrustShield AI and say: â€œIâ€™ll paste sample messages into the on-device checker. The risk score and confidence are different things; the quotes show which exact parts of the message triggered the result.â€

## 0:25â€“1:05 â€” Show a scam-shaped message

Paste this synthetic sample into Home and tap **Check karein**:

```text
SBI Aapka KYC 24 ghante main band ho jaayega Link par KYC update karo aur OTP batao
```

The P3-06 emulator run produced **70/100, High**. Point out the separate confidence, source quotes, limitations and the 1930 / cybercrime.gov.in next steps. Evidence: `evidence/p3-06-integrated-paste-result.png`.

## 1:05â€“1:35 â€” Show a legitimate control

Clear the field, paste a synthetic bank OTP safety notice, and check it. The P3-06 emulator run scored the tested legitimate bank OTP **18/100, Low**. Explain that a message mentioning an OTP is not automatically treated as a scam. This is a control example, not a guarantee for every bank message. Evidence: `evidence/p3-06-integrated-share-legit.png`.

## 1:35â€“2:05 â€” Show an image scan

Choose a synthetic screenshot with visible Latin or Devanagari scam text using the image picker. Wait for on-device ML Kit OCR and show the resulting local analysis. The verified sample produced **70/100, High**. The selected image is handled ephemerally. Evidence: `evidence/p3-06-integrated-image-ocr-result.png`.

## 2:05â€“2:35 â€” Show Call Shield demo mode

Open Call Shield and choose **Demo script mode**. Tap through the report's four lines and explain the A05 output honestly: the current deterministic script measures **LOW, LOW, LOW, CRITICAL**, rather than four distinct Low/Medium/High/Critical levels. Do not relabel or invent a score. The expected four-rung ladder remains open in issue #90.

## 2:35â€“3:00 â€” Show QR simulation and Settings

Open QR Guard, choose **Simulate scan**, and show its explicit simulation disclosure and mismatch result. Then open Settings and briefly show language, theme, sensitivity and haptics controls. Do not claim the settings survive a force-stop/relaunch until the pending P3-18 restart check is complete.

## Honest limits to state if asked

- A SEND text run has a later unresolved observation (#120): one emulator run reached Result but displayed Unable to Verify although JVM analysis of the same text produced HIGH_RISK. Reproduction and diagnosis are pending; do not use SEND as a success claim until rechecked.
- The emulator speech service returned `ERR_INTERNET_DISCONNECTED`; live ASR produced no transcript in that run. Call Shield only processes speech recognized while the app is visibly active. It does not capture the remote side of cellular or VoIP calls.
- The emulator's CameraX preview is synthetic. QR simulation is a demo path; physical QR decode is not claimed from that preview.
- P3-18 accessibility journeys, 1.3x font scale and true process-restart verification remain open. Current evidence and any gaps are tracked in `STATE.md` and issue #97.
- The app's deterministic risk score is not a probability or an accuracy claim. Unsupported or unreadable content may return Unable to Verify.
- Do not enter real OTPs, PINs, payment details, phone numbers, UPI IDs or personal messages during the demo.