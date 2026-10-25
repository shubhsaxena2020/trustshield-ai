# Android requirements

IDs are stable issue/test references. `R` refers to sections of `SOURCE_REPORT_senior_dev.md`; H refers to `HEAD_ORDERS_ANDROID.md`.

## Functional

| ID | Requirement | Source | Priority |
|---|---|---|---|
| F01 | Analyse typed and `ACTION_SEND`/`SEND_MULTIPLE` shared text, links and images without following a submitted link or URI destination. Bounded `content://` bytes may be read for explicit shares. | R1, R9.1; H2 | P0 |
| F02 | Normalize Devanagari digits, zero-width separators, OTP obfuscations and common Roman-Hindi variants before local rules; keep the original quote span for evidence. | R5; H2 | P0 |
| F03 | Apply deterministic five-stage rules, including a strong ask gate, negation and legitimate indicators. An unsupported language or unreadable input produces low confidence/Unable to Verify. | R3, R5; H2 | P0 |
| F04 | Show risk score, confidence, evidence, actions and limitations distinctly. Evidence has a verified input quote and the agreed Android `{id,module,title_hinglish,detail,severity,strength,quote}` shape. | R1, R3, R4.2; H2 | P0 |
| F05 | Foreground Call Shield asks for mic permission/consent, transcribes speech on device when supported, keeps a bounded in-memory rolling transcript and session stage state, then warns only on corroborated stages. Clear session on stop. | R6; H0, H2 | P0 |
| F06 | Support `hi-IN` and `en-IN`/English recognition selection. Explicitly show recognizer unavailable, uncertain transcription, offline model missing, and permission denied states. | R5–6; H0 | P0 |
| F07 | Camera QR watcher registers one or more expected merchant `pa` values after local PIN/confirmation, compares decoded UPI payees for three consecutive valid mismatches, and marks unreadable/occluded separately. Never launch the QR payload. | R7; H0 | P0 |
| F08 | Offer light/dark/system theme, Hinglish/English, sensitivity presets that adjust only documented local thresholds, and optional haptics. Store preferences without raw scan content. | H0; R1 | P0 |
| F09 | Implement an adapter for `/api/v1/analyze/text`, `/url`, `/image`, `/qr` as optional enrichment with explicit backend/offline status; convert current schema to the Android evidence schema without inventing fields. Keep all Android API enrichment disabled until both signed-in history and server memory retention pass the privacy audit. | Repo API; H3 | P0 |
| F10 | Support cropped payment screenshot checks as advisory only and always direct user to bank/UPI history for payment confirmation. | R8 | P1 |
| F11 | Notification listener must be explicit opt-in, on-device, filtered and independently removable; never promise coverage of all notifications. | R9.5, R14 | P2 |
| F12 | Telegram bot, official WhatsApp Cloud API, email ingestion, playbook LLM and multilingual model are separate roadmap packages; none may decide verdict or access forbidden holdouts. | R4, R9, R14; H2 | P2 |

## Non-functional

| ID | Requirement | Source |
|---|---|---|
| N01 | No raw audio/image/message persistence. Mask OTP, PIN, card, phone, UPI IDs before logging; exclude input from analytics/crash reports. Existing Supabase history conflicts with this promise and must be corrected or avoided before authenticated API use. | R1 golden rules; H2; `services/history/store.py` |
| N02 | Local rules and QR parsing work offline. No claim that OS speech recognition is offline until the selected device passes an offline test. | H4; Android speech APIs |
| N03 | No LLM verdict. Any future LLM fields need schema validation and normalized exact-quote membership; unsupported fields drop. | R4.2; H2 |
| N04 | Deterministic fixtures include the five legitimate/scam pairs, mixed scripts, adversarial URL text, QR glare, bad OCR/ASR, permission denial, network loss, and long calls. | R3.4, R6–10; H2 |
| N05 | Keep UI responsive, accessible, and interruptible; do camera/recognition work off the main thread. Measure cold start, memory, APK size and a no-crash stress run on available hardware. | H0, H4 |
| N06 | Report baseline sealed-holdout recall 82.1%, specificity 78.3%, precision 82.1%, F1 82.1%; 90% goal unmet. New Hinglish fixture passes are rule tests, not model accuracy. | H2; `FINAL-REPORT.md` |
