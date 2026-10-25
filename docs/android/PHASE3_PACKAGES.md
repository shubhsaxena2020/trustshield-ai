# Android Phase 3 packages

Created after the main checkout fast-forwarded to `2de982c8f2cbe02e00cd4b2b074df4600b5da135`. Phase 3 assigns 18 new `android`/`p0` issues. Every package has exclusive file ownership; do not edit another package's paths. Codex owns `app/build.gradle.kts`, `AndroidManifest.xml`, launcher/splash resources, and cross-package review fixes.

## Session lanes

- `agent1`: P3-01 through P3-06 (scan, OCR, result and navigation).
- `agent2`: P3-07 through P3-12 (share intake and Call Shield).
- `agent3`: P3-13 through P3-18 (QR Guard, settings and polish/accessibility).
- Each fresh session may delegate up to 9 children. Assign one writer per package in a separate worktree; keep the exclusive file paths below and rebase package branches on current `android-finals`.
- P3-18 is integration dependent; start with accessibility evidence planning, then run the emulator journeys after the upstream packages merge.

## Package ownership and acceptance

| ID / issue | Scope | Exclusive files | Dependencies | Done when |
|---|---|---|---|---|
| [P3-01 #80](https://github.com/shubhsaxena2020/trustshield-ai/issues/80) | Build the real scan input screen | `apps/android/app/src/main/java/ai/trustshield/android/ui/home/ScanScreen.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/ui/home/ScanScreenTest.kt` | P3-02, P3-06 | Multiline paste field, paste/clear, bounded character count with truncation notice, example chips, offline check action, and complete empty/error states. |
| [P3-02 #81](https://github.com/shubhsaxena2020/trustshield-ai/issues/81) | Wire the offline local analysis pipeline | `apps/android/app/src/main/java/ai/trustshield/android/core/analysis/LocalScanPipeline.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/core/analysis/LocalScanPipelineTest.kt` | A01-A05 | Run normalization, stage rules, legitimate indicators and A05 fusion fully offline; keep risk separate from confidence and exact source quotes; fail to Unable to Verify when unsupported. |
| [P3-03 #82](https://github.com/shubhsaxena2020/trustshield-ai/issues/82) | Add seven Hinglish scan regression fixtures | `apps/android/app/src/test/java/ai/trustshield/android/core/analysis/DemoMessagesTest.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/core/analysis/DemoMessages.kt` | P3-02 | Cover SBI KYC/OTP, digital arrest, electricity bill, job fee, legitimate OTP, credit alert and delivery OTP; assert sensible scam/legit bands and report misses honestly. |
| [P3-04 #83](https://github.com/shubhsaxena2020/trustshield-ai/issues/83) | Add bundled Latin and Devanagari OCR adapter | `apps/android/app/src/main/java/ai/trustshield/android/input/share/MlKitTextRecognizer.kt`<br>`apps/android/app/src/androidTest/java/ai/trustshield/android/input/share/MlKitTextRecognizerTest.kt` | P3-02 | Recognize Latin and Devanagari with bundled ML Kit; honor B07 bounds; keep bitmap/text ephemeral and never log or persist content. |
| [P3-05 #84](https://github.com/shubhsaxena2020/trustshield-ai/issues/84) | Add image selection and ephemeral OCR handoff | `apps/android/app/src/main/java/ai/trustshield/android/ui/home/ImageScanLauncher.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/ui/home/ImageScanLauncherTest.kt` | P3-02,P3-04 | Pick/share an image using URI grants, pass through B07 bounds and OCR into the same local pipeline, clear in-memory image data after analysis. |
| [P3-06 #85](https://github.com/shubhsaxena2020/trustshield-ai/issues/85) | Connect navigation, share entry and real results | `apps/android/app/src/main/java/ai/trustshield/android/MainActivity.kt`<br>`apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppNav.kt`<br>`apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppRoute.kt`<br>`apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/ui/navigation/ScanFlowTest.kt` | P3-01,P3-02,P3-05,P3-07,P3-08 | Wire home check/share/image actions to the real pipeline and ResultScreen; handle fresh and re-entered intents; no fixture-only route remains. |
| [P3-07 #86](https://github.com/shubhsaxena2020/trustshield-ai/issues/86) | Parse Android text and image share intents safely | `apps/android/app/src/main/java/ai/trustshield/android/input/share/ShareIntentReader.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/input/share/ShareIntentReaderTest.kt` | B01,B07 | Handle SEND and SEND_MULTIPLE text/image payloads, URI grants and malformed/empty input; never open destinations or persist raw content. |
| [P3-08 #87](https://github.com/shubhsaxena2020/trustshield-ai/issues/87) | Add ephemeral share-to-analysis coordinator | `apps/android/app/src/main/java/ai/trustshield/android/input/share/ShareIngestCoordinator.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/input/share/ShareIngestCoordinatorTest.kt` | P3-02,P3-04,P3-07 | Normalize shared text or image OCR into the same local pipeline, obey shared byte budget, clear payload references after use. |
| [P3-09 #88](https://github.com/shubhsaxena2020/trustshield-ai/issues/88) | Bind B04/B05 to the Android speech recognizer | `apps/android/app/src/main/java/ai/trustshield/android/input/speech/AndroidSpeechRecognizerAdapter.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/input/speech/AndroidSpeechRecognizerAdapterTest.kt` | B04,B05 | Probe capability first; use on-device recognizer only when verified, otherwise disclose cloud-capable path; release recognizer and discard transcript when stopped. |
| [P3-10 #89](https://github.com/shubhsaxena2020/trustshield-ai/issues/89) | Wire Call Shield live session and risk meter | `apps/android/app/src/main/java/ai/trustshield/android/ui/call/CallShieldHost.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/ui/call/CallShieldHostTest.kt` | P3-02,P3-09 | Connect consent, B05 transcript events, A05 stage persistence and local analysis to live stage chips/risk meter; visible foreground only, stop/clear works. |
| [P3-11 #90](https://github.com/shubhsaxena2020/trustshield-ai/issues/90) | Add Call Shield demo script mode | `apps/android/app/src/main/java/ai/trustshield/android/ui/call/CallDemoScript.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/ui/call/CallDemoScriptTest.kt` | A05 | Typed/tap-through report section 6 script advances Low to Medium to High to Critical using deterministic A05 signals; works with microphone denied and makes no live-call capture claim. |
| [P3-12 #91](https://github.com/shubhsaxena2020/trustshield-ai/issues/91) | Add Call Shield readiness and honest limits states | `apps/android/app/src/main/java/ai/trustshield/android/ui/call/CallShieldReadiness.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/ui/call/CallShieldReadinessTest.kt` | P3-09,P3-10 | Explain permission denial, unavailable recognizer, interruption, and speech privacy mode; no false LISTENING state or unsupported cellular/VoIP capture claim. |
| [P3-13 #92](https://github.com/shubhsaxena2020/trustshield-ai/issues/92) | Implement CameraX and bundled ML Kit QR adapter | `apps/android/app/src/main/java/ai/trustshield/android/input/qr/CameraXBarcodeDecoder.kt`<br>`apps/android/app/src/androidTest/java/ai/trustshield/android/input/qr/CameraXBarcodeDecoderTest.kt` | B02,B03 | Bind CameraX lifecycle/preview/analysis to B03 and bundled B02 barcode payload parsing; close every proxy and never retain or log pixels/payloads. |
| [P3-14 #93](https://github.com/shubhsaxena2020/trustshield-ai/issues/93) | Wire QR Guard screen and camera permission recovery | `apps/android/app/src/main/java/ai/trustshield/android/ui/qr/QrGuardHost.kt`<br>`apps/android/app/src/androidTest/java/ai/trustshield/android/ui/qr/QrGuardHostTest.kt` | P3-13,B06 | Connect setup/watch/re-register screen to camera, B06 watcher and runtime CAMERA rationale/denied/permanent-denied recovery; unreadable is never tamper. |
| [P3-15 #94](https://github.com/shubhsaxena2020/trustshield-ai/issues/94) | Add deterministic QR Guard simulation path | `apps/android/app/src/main/java/ai/trustshield/android/input/qr/SimulatedQrFixtures.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/input/qr/SimulatedQrFixturesTest.kt` | B02,B06 | Provide safe demo payloads for matching, mismatch and unreadable flows without opening destinations or claiming unreadable means tampered. |
| [P3-16 #95](https://github.com/shubhsaxena2020/trustshield-ai/issues/95) | Connect sensitivity setting to scoring behavior | `apps/android/app/src/main/java/ai/trustshield/android/data/preferences/SensitivityPolicy.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/data/preferences/SensitivityPolicyTest.kt` | P3-02,A05,C04 | Apply persisted bounded sensitivity to A05/local pipeline with tested low/medium/high bounds; avoid claims beyond deterministic rule behavior. |
| [P3-17 #96](https://github.com/shubhsaxena2020/trustshield-ai/issues/96) | Apply saved language, theme and haptics consistently | `apps/android/app/src/main/java/ai/trustshield/android/ui/settings/PreferenceEffects.kt`<br>`apps/android/app/src/test/java/ai/trustshield/android/ui/settings/PreferenceEffectsTest.kt` | C02,C04 | Persist and apply Hinglish/English copy, light/dark/system theme and haptic toggle across app restart; haptic feedback is opt-in and content is never stored. |
| [P3-18 #97](https://github.com/shubhsaxena2020/trustshield-ai/issues/97) | Verify accessibility and end-to-end emulator journeys | `apps/android/app/src/androidTest/java/ai/trustshield/android/Phase3JourneysTest.kt`<br>`docs/android/evidence/accessibility.md`<br>`docs/android/evidence/device-run.md` | P3-01-P3-17 | Exercise scan/share/image, Call Shield demo, simulated QR, settings/restart and offline mode; record TalkBack labels, 48dp targets, font scale 1.3, screenshots, crash/ANR status and honest gaps. |

## Integration order

1. Codex foundation is already in the main checkout: bundled ML Kit Latin/Devanagari text recognition and barcode scanning, CameraX, AndroidX splash and Compose instrumentation dependencies, CAMERA/RECORD_AUDIO declarations, SEND/SEND_MULTIPLE filters, and adaptive launcher icons.
2. Build the independent cores first: P3-02, P3-07, P3-09, P3-13, P3-15, P3-16 and P3-17.
3. Integrate scan/share/call/QR adapters against those cores; merge one reviewed PR at a time and keep `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` green.
4. Merge P3-06 navigation/result routing after its input flows are available. Finish P3-18 on the integrated emulator build.

## Shared rules

- No URL or QR destination is fetched, opened, resolved or executed. Sharing a link only parses its text.
- The local deterministic engine owns every verdict. Evidence quotes must match input; risk and confidence remain distinct; unsupported or unreadable content yields Unable to Verify.
- No raw message, transcript, image, URI, OTP, PIN, phone number or UPI ID is logged, written to disk, or added to preferences. Image bytes remain in bounded memory only.
- Call Shield observes only speech available to Android SpeechRecognizer under the displayed capability/consent mode. No cellular/VoIP capture or deepfake detection claim.
- Keep backend enrichment disabled by default. Use synthetic demo fixtures and report any fixture miss without changing metrics.
- Package PRs must identify the issue, exact owned files, test commands/output, screenshots for UI flows, privacy behavior and known gaps. Codex reviews fixes and merges in dependency order.

## Issue index

- P3-01: #80 — Android Phase 3: Build the real scan input screen
- P3-02: #81 — Android Phase 3: Wire the offline local analysis pipeline
- P3-03: #82 — Android Phase 3: Add seven Hinglish scan regression fixtures
- P3-04: #83 — Android Phase 3: Add bundled Latin and Devanagari OCR adapter
- P3-05: #84 — Android Phase 3: Add image selection and ephemeral OCR handoff
- P3-06: #85 — Android Phase 3: Connect navigation, share entry and real results
- P3-07: #86 — Android Phase 3: Parse Android text and image share intents safely
- P3-08: #87 — Android Phase 3: Add ephemeral share-to-analysis coordinator
- P3-09: #88 — Android Phase 3: Bind B04/B05 to the Android speech recognizer
- P3-10: #89 — Android Phase 3: Wire Call Shield live session and risk meter
- P3-11: #90 — Android Phase 3: Add Call Shield demo script mode
- P3-12: #91 — Android Phase 3: Add Call Shield readiness and honest limits states
- P3-13: #92 — Android Phase 3: Implement CameraX and bundled ML Kit QR adapter
- P3-14: #93 — Android Phase 3: Wire QR Guard screen and camera permission recovery
- P3-15: #94 — Android Phase 3: Add deterministic QR Guard simulation path
- P3-16: #95 — Android Phase 3: Connect sensitivity setting to scoring behavior
- P3-17: #96 — Android Phase 3: Apply saved language, theme and haptics consistently
- P3-18: #97 — Android Phase 3: Verify accessibility and end-to-end emulator journeys
