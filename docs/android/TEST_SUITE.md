# Test suite and evidence plan

Phase 1 build proof is separate from feature acceptance. Phase 2 workers write focused tests for their packages; Codex reruns them and the entire affected build before merge. Baseline backend suite is the historical 1,280 passed / 7 skipped / 6 warnings, not a fresh Phase 1 run. If `services/` changes later, rerun `.venv\Scripts\python -m pytest -q` and report exact output; do not open forbidden holdouts.

| Layer | Command / method | Required assertions |
|---|---|---|
| Kotlin unit | `./gradlew.bat :app:testDebugUnitTest` | Normalization source spans; stage/negation; score vs confidence; QR three-valid-mismatch state; speech session reset; redaction. |
| API adapter integration | local `MockWebServer`/fixture equivalent, no production requests | HTTP 429/503/timeouts, schema drift, backend disagreement, raw OCR text excluded from logs/history. |
| Instrumented UI | `./gradlew.bat :app:connectedDebugAndroidTest` when device/emulator exists | Compose navigation, share intent, permission denial, offline, Call Stop, QR glare/mismatch, settings and process recreation. |
| Manual phone | `adb install -r`, launch, 10 scripted staged calls, 10 QR re-registration/tamper cycles | Device-dependent Hindi ASR, vibration/sound, camera readability, app background behavior, no raw data retained. |
| Stability/performance | `adb shell monkey -p ai.trustshield.android 500`; cold launch and `dumpsys meminfo`; APK byte size | Zero crashes/ANRs, cold start target <=2.5 s on named mid-range phone, peak PSS target <=250 MB excluding optional ASR model; APK target <=50 MB without optional model. Record device/model if targets miss. |

## Named edge fixtures from report §10 and §3.4

Empty/emoji-only; exactly 20,000 characters; mixed Roman/Devanagari; digits `४५६७`; zero-width and emoji-separated `OTP`; `0TP` and `O.T.P`; blurry/no OCR; bare domain `sbi-kyc.xyz/login`; `sbi.co.in.kyc-verify.xyz`; punycode/homoglyph text; shorteners; `forms.gle` with a credential ask; QR in screenshot; the five legitimate/scam pairs in `BEHAVIOR_SPEC.md`; slow Day 1/3/5 chat; prompt-injection instruction as input data; noisy/misheard “ED”; unsupported language; two-hour call stage persistence; WiFi down; no camera/mic/notification permission; UPI QR glare/occlusion and two registered IDs; wrong phone clock; duplicate/cropped payment screenshot; model/LLM unavailable; PII in logs. Verify no submitted URL or QR destination is requested by network instrumentation.

## No physical device

Try `sdkmanager --list_installed`, `emulator -list-avds`, then a supported Android 36 x86_64 system image only if host virtualization/RAM permit. Run instrumented tests and `adb shell monkey` on the emulator, labeled emulator evidence. If unavailable, Kotlin unit tests, Gradle build and a signed APK hash are valid build proof; UI/performance/installation remain **unverified** and `NEEDS_SHUBH.md` stays open. A screenshot or mock is never called a real-phone test.
