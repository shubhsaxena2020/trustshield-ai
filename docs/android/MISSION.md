# Android mission — 30 October 2026

Ship an installable, consent-based TrustShield Android APK for a three-minute expo demo. The app gives a deterministic, evidence-backed scam assessment in simple Hinglish or English for typed/shared text, a QR viewed through the phone camera, and a foreground Call Shield microphone session. Work is limited to the `android-finals` branch; the website and production remain untouched.

## Scope and non-goals

The two non-cuttable capabilities are Hinglish normalization/rules and Call Shield. A P0 release also needs a readable risk/evidence screen, local offline rules, a share receiver, privacy controls, and a phone QR watcher. The existing FastAPI analysis API may add richer results when available, but a network outage must not remove the local warning path. Chrome extension, iPhone, hardware ESP32/Raspberry Pi, autonomous call interception, arbitrary notification surveillance, Telegram/WhatsApp/email integrations, model retraining and a new accuracy claim are outside the 30 October APK.

## Acceptance

By 30 October: `apps/android/gradlew.bat :app:assembleDebug` and unit tests pass; a named APK is installable and launches on a real Android phone; the team's staged Hinglish scam call produces an evidence-backed warning, while a benign call does not; share, QR sticker replacement and offline paths run on device; permission denial does not crash; all demo copy states limits. If a phone is unavailable, report build and emulator evidence separately and mark real-device acceptance open. Existing 1,280 Python tests must remain green for any backend change. Do not imply that this Phase 1 scaffold itself meets product acceptance.

Phase 1 ends at a reviewed plan and a built hello-world Compose APK, then awaits Claude Code review before feature work.
