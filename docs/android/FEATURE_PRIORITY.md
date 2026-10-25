# Feature priority and hard cut

Effort is implementation plus focused tests/review in **agent-hours**, not elapsed hours. A 9-child session does not turn nine dependent tasks into one hour. Demo value is relative (1–5). If P0 slips, move P1/P2 below the line; **Hinglish and Call Shield remain P0** but any unverified live-call claim is removed.

| Report feature | Priority | Agent-h | Risk | Demo | 30 Oct decision |
|---|---:|---:|---|---:|---|
| R5 Hinglish normalizer, mixed script, legitimate negation | P0 | 18 | medium | 5 | Ship local rules with regression pairs; no new model metric |
| R6 Call Shield stage logic and foreground speech | P0 | 28 | high | 5 | Ship visible mic demo; test target phone's offline recognition; staged speech fallback |
| R7 QR Tamper Guard | P0 | 20 | high | 5 | **Phone camera is the watcher**; visible continuous scan and 3 valid mismatches; no unattended hardware/Telegram claim |
| R9.1 Share from Android apps | P0 | 12 | medium | 5 | Native Android share intents, bounded text/images; website PWA cut |
| R3 five-stage/evidence/legit pairing | P0 | 20 | medium | 5 | Local deterministic core; stage persistence for call |
| R1 custom theme/language/sensitivity/haptics | P0 | 10 | low | 3 | Small documented settings, no user-configured safety bypass |
| R8 payment screenshot advisory | P1 | 18 | high | 3 | Only if P0 green; no payment verification claim |
| R4.2 playbook LLM quote extractor | P1 | 16 | high | 2 | Schema+quote verifier only after deterministic P0, no LLM verdict |
| R4.3 multilingual model/retraining | P2 | 60+ | high | 2 | Roadmap; no fresh sealed 300-real-message holdout or RAM benchmark |
| R4.4 crowdsourced data/game | P2 | 25+ | high | 1 | Roadmap; consent and labels required |
| R9.2 Telegram bot | P2 | 20 | high | 2 | Roadmap; requires token/server, outside APK |
| R9.3 WhatsApp Cloud API | P2 | 30+ | high | 2 | Roadmap; official API, pricing/onboarding uncertain |
| R9.4 forwarded email | P2 | 18 | medium | 1 | Roadmap; headers often lost on normal forward |
| R9.5 notification listener | P2 | 24 | high | 2 | Roadmap, explicit opt-in/device-policy gate |
| R7 ESP32/Pi hardware, Telegram alarm | P2 | 35+ | high | 4 | Roadmap; owner chose phone APK; phone watcher has narrower coverage |
| R14 iPhone, extension, reputation graph/report shortcuts | P2 | 60+ | high | 1 | Roadmap; no website/production work |

**Hard cut:** after P0 passes device/build/privacy gates, accept P1 only if it does not consume the final 24-hour buffer. Freeze new features at end of Day 2; Day 3 is fixes, device rehearsal and artifact handoff. Never lower a gate to claim a feature shipped.
