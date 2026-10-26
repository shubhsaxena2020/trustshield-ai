# TrustShield phone acceptance — 30 minutes

**Run only after the owner confirms the Android phone is connected by USB debugging.** This plan is not permission to start phone work during the hold. Keep the printed QR card on paper; never confirm or send a payment. Use a demo-only recipient and zero rupees.

Prerequisites: Android 8.0+ (API 26+), USB debugging authorized, one device in `adb devices`, release `arm64-v8a` APK built, and `demo_upi_qr_cards.pdf` printed at 100% scale. Root is the repository directory. The app package is `ai.trustshield.android`.

| Time | Step and exact command | PASS / FAIL |
|---|---|---|
| 0:00–2:00 | Connect and identify: `adb devices -l` then `adb -s <SERIAL> shell getprop ro.product.cpu.abi` and `adb -s <SERIAL> shell getprop ro.build.version.release`. Expected ABI `arm64-v8a`; record model, Android version, and serial suffix only. | PASS / FAIL: __________ |
| 2:00–4:00 | Install: `pwsh -File docs/android/phone-kit/scripts/phone_install.ps1 -Serial <SERIAL>`; then `adb -s <SERIAL> shell monkey -p ai.trustshield.android 1`. Confirm TrustShield opens and the icon/splash are correct. | PASS / FAIL: __________ |
| 4:00–5:00 | Capability snapshot: open Settings → Device check. Confirm Android version, ABI, Hindi recognizer and `hi-IN` support, Camera permission, and Microphone permission are shown without granting anything implicitly. | PASS / FAIL: __________ |
| 5:00–6:00 | Grant the requested runtime permissions: `pwsh -File docs/android/phone-kit/scripts/phone_grant_permissions.ps1 -Serial <SERIAL>`. Reopen Device check and confirm camera/mic are Granted. | PASS / FAIL: __________ |
| 6:00–9:00 | Hindi speech probe: `adb -s <SERIAL> shell am start -a android.speech.action.RECOGNIZE_SPEECH --es android.speech.extra.LANGUAGE_MODEL free_form --es android.speech.extra.LANGUAGE hi-IN --es android.speech.extra.PROMPT "Hindi probe: kal bank se call aaya"`. If recognizer UI opens, say: **“Aapka bank account band ho jayega. OTP kisi ko mat batana; branch ke official number par khud call karein.”** Record whether text was returned and the recognized words. Then use Call Shield live demo and check that its capability disclosure matches Device check. If no Hindi recognizer exists, record FAIL for locale support and show the app's simulate/demo fallback; do not install a recognizer during this run. | PASS / FAIL: __________ |
| 9:00–13:00 | QR: open QR Guard, register the printed **Expected** synthetic VPA `demo-recipient@upi` using the app's setup flow, then scan the matching card once. Scan the three cards marked **Mismatch 1/2/3**, one at a time. Expect no alert for match and the tamper warning only after three consecutive mismatches. Never tap a pay/intent confirmation. | PASS / FAIL: __________ |
| 13:00–16:00 | Call Shield: open the live session with explicit consent and microphone permission. Say the Hindi probe above, observe the transcript and risk progression, then stop the session. The mic indicator must stop and transcript-derived state must clear. If live recognition is unavailable, use the labeled demo ladder. | PASS / FAIL: __________ |
| 16:00–18:00 | Offline: enable airplane mode with `adb -s <SERIAL> shell cmd connectivity airplane-mode enable`; run a pasted scam/legit pair and **Simulate scan**. Confirm the local result and simulation still work. Restore connectivity with `adb -s <SERIAL> shell cmd connectivity airplane-mode disable`. | PASS / FAIL: __________ |
| 18:00–21:00 | Cold start, package size, battery: `adb -s <SERIAL> shell am force-stop ai.trustshield.android`; `adb -s <SERIAL> shell am start -W -n ai.trustshield.android/.MainActivity`; record `TotalTime`. Then `adb -s <SERIAL> shell pm path ai.trustshield.android` and `adb -s <SERIAL> shell dumpsys batterystats --reset`; use the app normally for 60 seconds, then `adb -s <SERIAL> shell dumpsys batterystats ai.trustshield.android`. Record installed APK bytes, cold start ms, and battery delta if exposed. | PASS / FAIL: __________ |
| 21:00–23:00 | TalkBack quick pass: enable via the phone's Accessibility settings; swipe through Home, Result, QR Guard, Call Shield, Settings. Confirm each actionable control has a useful spoken label, reading order is sensible, and risk is conveyed in text as well as color. Disable TalkBack afterward. | PASS / FAIL: __________ |
| 23:00–25:00 | Font scale 1.3x: `adb -s <SERIAL> shell settings put system font_scale 1.3`; relaunch and inspect Home, Result, QR Guard, Call Shield, Settings for clipped actions or unreadable cards. Restore: `adb -s <SERIAL> shell settings put system font_scale 1.0`. | PASS / FAIL: __________ |
| 25:00–29:00 | Stability: `pwsh -File docs/android/phone-kit/scripts/phone_monkey.ps1 -Serial <SERIAL> -Events 2000 -Seeds 4242,7,99`. Script saves per-seed logs and checks app crashes/ANRs; do not interact with the screen during the run. | PASS / FAIL: __________ |
| 29:00–30:00 | Collect a privacy-filtered report: `pwsh -File docs/android/phone-kit/scripts/phone_collect_logs.ps1 -Serial <SERIAL>`. Record final verdict and any log path. | PASS / FAIL: __________ |

## Run record

- Date/time: __________  Device/model: __________  Android: __________  ABI: __________
- Recognizer service: __________  Hindi `hi-IN`: PASS / FAIL / NOT AVAILABLE
- Install/version: __________  APK bytes: __________  Cold start ms: __________  Battery delta: __________
- Monkey: 4242 PASS/FAIL ____  7 PASS/FAIL ____  99 PASS/FAIL ____
- QR match: ____  mismatch 1: ____  mismatch 2: ____  mismatch 3 / tamper alert: ____
- TalkBack: ____  1.3x font: ____  airplane mode: ____  Call Shield: ____
- Owner: __________  Head reviewer: __________  Follow-up issue(s): __________

