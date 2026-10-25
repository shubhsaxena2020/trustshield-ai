# Owner-only inputs and deadlines

| Need | Deadline (IST) | Why | Fallback if unavailable |
|---|---|---|---|
| Android phone with USB debugging, cable, unlocked sideload prompt, Android version/model and permission to run staged mic/camera tests | **27 Oct, 12:00** | Real APK install, Hindi ASR capability, camera/QR, vibration, size/memory and Call Shield cannot be accepted from build alone. | Emulator if available; otherwise build-only handoff with device acceptance open. |
| Two volunteers and consent to speak staged Hindi/English scam and benign call scripts near the phone | **28 Oct, 12:00** | Ten reproducible calls, false-alarm review and real-room latency. No raw audio retained. | Team reads scripted lines; report staged demo rather than real call capture. |
| Merchant QR values or permission to use synthetic demo UPI IDs and two printed QR cards/stickers | **28 Oct, 12:00** | Demonstrate camera watcher replacement and re-register flow safely. | Synthetic UPI payloads on paper; no real payment or UPI launch. |
| Decision on optional ASR model download/size if the phone has no on-device Hindi service | **27 Oct, 18:00** after capability probe | 42 MB Vosk Hindi candidate may enlarge APK and need license/latency test. | Keep Call Shield with supported device/staged microphone input; label phone-specific limitation. |
| Telegram bot token, Meta WhatsApp access/payment, dedicated email inbox, hardware purchase | **No deadline for 30 Oct APK** | These are P2 roadmap features outside this phase. | No integration or purchase; no secret requested or committed. |

The APK can be planned and built without an answer to these items. Real-phone and ASR quality claims remain open until measured. Claude Code must accept the Phase 1 plan before feature work starts.
