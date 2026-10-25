# Top Android risks (25 Oct assessment)

Probability is qualitative and may change after a target phone is available.

| Risk | Probability / impact | Mitigation and decision gate |
|---|---|---|
| Hindi on-device SpeechRecognizer absent/poor on judge phone | High / high | Capability probe on owner's phone by 27 Oct; airplane-mode and ten scripted utterances; Vosk small Hindi candidate only after measured timing/RAM, labeled staged-speech fallback. |
| Ordinary app cannot capture remote side of phone/VoIP calls | High / high | Scope Call Shield to foreground audible microphone speech; never claim automatic remote capture. |
| Three-day schedule makes 27-agent merge conflicts and incomplete integration likely | High / high | Exclusive file ownership, dependency-ready launches, Codex-only integration, P0 freeze after Day 2, full Day 3 buffer. |
| Existing API/Supabase history violates no-content-storage promise | Confirmed / high | Avoid authenticated history from Android; audit `input_summary`, `result_json`, OCR and server result store; require verified correction before privacy claim. |
| Current API evidence lacks quote/strength/Hinglish title | Confirmed / medium | Local evidence contract; do not invent adapter fields; show provenance and limits. |
| QR camera cannot replace unattended ESP32 watcher | High / medium | Attended watcher UX, explicit camera-on state, no background/Telegram claim. |
| Compose dependency drift needs SDK 37 | Confirmed first attempt / medium | Pin compatible BOM 2025.04.01, AGP 8.13, wrapper 8.14.3; build proof passed. |
| No physical device/emulator attached | Confirmed at Phase 1 / high | Owner phone deadline; build artifact separately from install/runtime acceptance. |
| False alarms on real bank OTP/credit and noisy ASR | High / high | Five legitimate/scam pairs, negation tests, corroboration gate, low-confidence state. |
| Sideload, mic and camera permission friction in expo | Medium / high | Install a day early, permission walkthrough, no background capture dependency, backup demo clearly labeled. |
| WhatsApp pricing/onboarding claims drift | Medium / low for APK | Cut integration; recheck official rate card before later purchase; no “free reply” promise in demo. |
| Optional large model exceeds APK/memory/start budgets | Medium / high | Do not bundle unmeasured Whisper; use device OS service first, then measured Vosk trial. |
| Existing whole-repo CI is red on Python locks unrelated to Phase 1 | Confirmed / high for later merges | Issue #54 tracks missing `defusedxml` in `requirements-lock.txt` and dependency-audit findings in `requirements-api.lock.txt`. Android build/lint passed locally; keep remote CI status explicit until separate repair. |
