# Android three-minute demo script

Use synthetic text only. The app analyzes locally; it does not contact a bank, open a shared URL, make a payment, capture cellular/VoIP audio, or retain the message after the in-memory result is cleared.

## 0:00â€“0:25 â€” Set the scope

Open TrustShield and say: â€œIâ€™ll paste sample messages into the on-device checker. The risk score and confidence are different things; the quotes show which exact parts of the message triggered the result.â€

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

Open Call Shield, choose **Staged demo (live mic nahi)**, and tap **Agli line** four times. The deterministic A05 readings move through **LOW (10/100) → MEDIUM (44/100) → HIGH (52/100) → CRITICAL (75/100)**. Confidence is a separate reading. The parcel/drugs line includes the report's own "Aap digital arrest mein hain" marker; point out the quoted words actually analyzed. These are engine outputs, not scores set by the screen. Reset returns to LOW and clears the in-memory transcript. Evidence: `evidence/p3-119-demo-step1.png` through `p3-119-demo-step4.png`, plus `evidence/p3-119-demo-reset.png`.

## 2:35â€“3:00 â€” Show QR simulation and Settings

Open QR Guard, choose **Simulate scan**, and show its explicit simulation disclosure and mismatch result. Then open Settings and briefly show language, theme, sensitivity and haptics controls. A force-stop/relaunch check confirmed saved preferences reload. Current light and dark theme screenshots show readable content and system bars: `evidence/p3-125-settings-light-window-bars.png` and `evidence/p3-125-settings-dark-window-bars.png`.

## Honest limits to state if asked

- A correctly quoted SEND run with the full 174-character sample produced 75/100 High and four verified quotes on `emulator-5554`. The earlier Unable to Verify observation (#120) was caused by PowerShell stripping the remote shell argument quotes, so only the first eight characters reached the app; #120 is closed as a harness error. In PowerShell, pass the full remote `am start` command as one quoted argument.
- The emulator speech service returned `ERR_INTERNET_DISCONNECTED`; live ASR produced no transcript in that run. Call Shield only processes speech recognized while the app is visibly active. It does not capture the remote side of cellular or VoIP calls.
- The emulator's CameraX preview is synthetic. QR simulation is a demo path; physical QR decode is not claimed from that preview.
- The P3-18 emulator journeys now pass, including Result and Settings Activity recreation. Issue #97 remains open for manual TalkBack traversal, 320 dp / 200% font-scale coverage, physical-device checks, and other owner-dependent evidence; see `evidence/accessibility.md` and `evidence/device-run.md`.
- The app's deterministic risk score is not a probability or an accuracy claim. Unsupported or unreadable content may return Unable to Verify.
- Do not enter real OTPs, PINs, payment details, phone numbers, UPI IDs or personal messages during the demo.
