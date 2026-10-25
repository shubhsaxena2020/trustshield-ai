# Android Phase 1 plan — two-minute read

- Goal: by 30 Oct, an installable Android APK with Hinglish scam checks, foreground Call Shield, Android share receiver and phone-camera QR watcher. No website or production work.
- Choice: native Kotlin + Compose, minSdk 26, SDK 36, JDK 17. Phase 1 built a hello-world APK; exact build evidence is in `STATE.md`/`TOOLS_AND_MCP.md`.
- P0 stays small: local deterministic normalizer/rules/evidence and polished, accessible UI; optional backend API enrichment. Hinglish and Call Shield cannot be cut.
- Call Shield analyses consensual microphone speech while the app is visible. It cannot promise capture of a cellular/VoIP call's remote audio. Device on-device Hindi ASR support is unverified until a phone test; Vosk small Hindi is a fallback candidate.
- QR Guard uses the phone camera as an attended watcher, not an unattended ESP32 replacement. Three valid payee mismatches alert; glare/no decode yields an unreadable state.
- The report's PWA share target becomes Android `ACTION_SEND`/`SEND_MULTIPLE`. Payment screenshot checks are advisory P1. LLM extractor, model retrain, Telegram, WhatsApp, email and notification listener are roadmap.
- Privacy blocker: existing signed-in Supabase history stores a raw-ish 120-character summary and full result JSON, including OCR text. The Android app must avoid authenticated history until corrected and audited; no blanket zero-storage claim for current backend.
- Day 1: deterministic core, UI shell, speech probe, share and QR contracts. Day 2: vertical integration, device tests, hardening. Day 3: buffer/rehearsal/final APK. Work packages assign 3 Hermes sessions × 9 slots with exclusive paths.
- Metrics remain historical: holdout recall 82.1%, specificity 78.3%, precision 82.1%, F1 82.1%; 90% target missed. Rule fixture results are not model accuracy.
- Phase 2 is on hold for Claude Code review. Review work-package cut line and real-phone availability before launch.
