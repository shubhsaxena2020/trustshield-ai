# Detection fixtures - Phase 4 items 4 and 5 (issue #137, lane "rules/fixtures")

**These are synthetic rule fixtures, not an accuracy measurement.** Every row below is a hand-written
message run through the real on-device engine to show whether a rule fires and lands in the expected
band. Per-category detection rates below describe only these hand-written cases; there is no holdout,
sampling frame or representative labelled corpus, so these rates are not estimates of real-world recall,
precision, specificity or F1. The repository's measured model numbers remain separate:
model numbers are unchanged and still stand exactly as recorded in `FINAL-REPORT.md` and
`HEAD_ORDERS_ANDROID.md`: holdout recall 82.1 / specificity 78.3 / precision 82.1 / F1 82.1 (the 90%
target is **not** met), routing audit 79/79 with 7 false alarms, 1,280 Python tests.

Scope: PHASE4 item 4 (>= 40 rows across every named family plus its legitimate twin, run through the
engine, honest PASS/MISS) and item 5 (the three exact Devanagari / mixed-script probes). No URL in any
row is opened, resolved or fetched - the engine treats a link as inert characters, and the fixtures only
ever hand over text. No holdout file, model artifact, `.env` or trained model was touched.

## How to reproduce

```bash
cd apps/android
./gradlew :app:testDebugUnitTest --tests "ai.trustshield.android.core.fixtures.*"
```

The test class is `apps/android/app/src/test/java/ai/trustshield/android/core/fixtures/DetectionFixturesTest.kt`;
the inventory is `DetectionFixtures.kt` in the same package. The test prints one machine-readable line per
row and fails on any band mismatch, so the table below cannot silently drift from the engine:

```
ROW|<id>|<family>|<language>|<kind>|<expected>|<measured>|<score>|<confidence>|<PASS|MISS>|<stages>
TEXT|<id>|<message>
SUMMARY|total=<n>|pass=<n>|miss=<n>
```

This document was generated from that output (JUnit XML `system-out`, 2026-10-26), not typed by hand:

```python
import glob, xml.etree.ElementTree as ET
for p in glob.glob("app/build/test-results/testDebugUnitTest/TEST-*fixtures*.xml"):
    root = ET.parse(p).getroot()
    for out in root.iter("system-out"):
        print("\n".join(l for l in (out.text or "").splitlines() if l.startswith(("ROW|", "TEXT|"))))
```

Engine path exercised, unmodified and unstubbed: `LocalScanPipeline.analyzeText` -> `LocalScorer`
(fusion, weights, noisy-OR, bands, confidence) -> `HinglishNormalizer` + `StageRules` +
`LegitimateIndicators` + `DigitalArrestSignals`. Risk score and confidence stay separate fields, and every
row is also asserted to have **zero** evidence quotes that do not verify against the analysed text
(`AnalysisResult.unverifiableEvidence` empty).

## Result: 167 rows, 167 PASS, 0 MISS

167 rows total: 88 scam rows and 79 legitimate rows. The original 47 rows remain unchanged and green (28 scam, 19 legitimate, including the three exact probes and four band-ladder controls). The 120 new rows add 60 scams and 60 legitimate controls across ten requested categories.

The new set labels every row by category, language, and kind. Each category has six scam and six legitimate rows, with two rows each in English, Hinglish, and Hindi (Devanagari). New scam rows pass when score is at least 25 (CAUTION or higher); new legitimate rows pass only at score 24 or below with no verified ask. The original 47 rows continue to assert their exact spec-derived bands.

| New fixture category | Scam detected | Scam total | Fixture detection rate | Legitimate false positives | Legitimate total | Result |
|---|---:|---:|---:|---:|---:|---|
| Digital arrest | 6 | 6 | 100% (6/6) | 0 | 6 | PASS |
| KYC/bank | 6 | 6 | 100% (6/6) | 0 | 6 | PASS |
| Electricity/bill disconnection | 6 | 6 | 100% (6/6) | 0 | 6 | PASS |
| UPI collect/refund | 6 | 6 | 100% (6/6) | 0 | 6 | PASS |
| Job offers | 6 | 6 | 100% (6/6) | 0 | 6 | PASS |
| Parcel/customs | 6 | 6 | 100% (6/6) | 0 | 6 | PASS |
| Loan apps | 6 | 6 | 100% (6/6) | 0 | 6 | PASS |
| OTP sharing | 6 | 6 | 100% (6/6) | 0 | 6 | PASS |
| Investment/crypto | 6 | 6 | 100% (6/6) | 0 | 6 | PASS |
| Lottery | 6 | 6 | 100% (6/6) | 0 | 6 | PASS |
| **Total** | **60** | **60** | **100% (60/60)** | **0** | **60** | **PASS** |

The IDs are `N01-S01..S06` / `N01-L01..L06` through `N10-S01..S06` / `N10-L01..L06`. The exact synthetic message text is kept in `DetectionExpansion.kt`; the test emits each text and its measured label/score so the report can be regenerated from JUnit XML. No claim is made that 100% on these 60 selected examples meets the separate 90% model holdout target.

Each `expected` band is the one the behaviour spec mandates for that shape, written down **before** the
row was measured:

* `ASK` + >= 2 other independent stages => `HIGH_RISK` (BEHAVIOR_SPEC "Rule gates");
* `ASK` + exactly 1 other stage => `CAUTION` (`35..49`), `ASK` alone => `CAUTION` (`25..45`) - `LocalScorer`
  rules 5, so a bare ask is never rendered safe and never accused on its own;
* no verified ask and no strong signal => `LOW_RISK` watch (`0..24`, rules 1/6);
* no verified ask but a validated `DigitalArrestSignals` marker plus >= 3 other stages => `SUSPICIOUS`
  (`50..69`); plus >= 2 => `CAUTION` (`25..49`);
* every legitimate twin => `LOW_RISK` (`<= 24`) **and** no verified ask (report §3.4).

## All rows

| ID | Family | Language | Row | Expected band | Measured band | Score | Stages detected | Result |
|---|---|---|---|---|---|---|---|---|
| F01 | KYC link + OTP | Hinglish | SCAM | HIGH_RISK | HIGH_RISK | 70 | THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F02 | KYC link + OTP | English | SCAM | HIGH_RISK | HIGH_RISK | 70 | THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F03 | KYC link + OTP | English | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F04 | Digital arrest text | Hinglish | SCAM | HIGH_RISK | HIGH_RISK | 80 | AUTHORITY, THREAT_OR_REWARD, URGENCY, SECRECY, ASK | PASS |
| F05 | Digital arrest text | Hindi (Devanagari) | SCAM | HIGH_RISK | HIGH_RISK | 80 | AUTHORITY, THREAT_OR_REWARD, URGENCY, SECRECY, ASK | PASS |
| F06 | Digital arrest text | English | LEGIT | LOW_RISK | LOW_RISK | 24 | AUTHORITY, THREAT_OR_REWARD | PASS |
| F07 | Electricity/gas/SIM disconnect | Hinglish | SCAM | HIGH_RISK | HIGH_RISK | 70 | THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F08 | Electricity/gas/SIM disconnect | English | SCAM | HIGH_RISK | HIGH_RISK | 75 | AUTHORITY, THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F09 | Electricity/gas/SIM disconnect | English | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F10 | Job registration fee | Hinglish | SCAM | HIGH_RISK | HIGH_RISK | 70 | THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F11 | Job registration fee | Hindi (Devanagari) | SCAM | HIGH_RISK | HIGH_RISK | 70 | THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F12 | Job registration fee | English | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F13 | Lottery/KBC | Hinglish | SCAM | HIGH_RISK | HIGH_RISK | 75 | THREAT_OR_REWARD, URGENCY, SECRECY, ASK | PASS |
| F14 | Lottery/KBC | English | SCAM | CAUTION | CAUTION | 45 | THREAT_OR_REWARD, ASK | PASS |
| F15 | Lottery/KBC | Hinglish | LEGIT | LOW_RISK | LOW_RISK | 22 | THREAT_OR_REWARD | PASS |
| F16 | Courier/customs OTP | Hinglish | SCAM | HIGH_RISK | HIGH_RISK | 75 | AUTHORITY, THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F17 | Courier/customs OTP | English | SCAM | HIGH_RISK | HIGH_RISK | 70 | AUTHORITY, THREAT_OR_REWARD, ASK | PASS |
| F18 | Courier/customs OTP | English | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F19 | APK install | Hinglish | SCAM | HIGH_RISK | HIGH_RISK | 70 | THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F20 | APK install | Hindi (Devanagari) | SCAM | HIGH_RISK | HIGH_RISK | 70 | THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F21 | APK install | English | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F22 | Remote access app | Hinglish | SCAM | HIGH_RISK | HIGH_RISK | 70 | AUTHORITY, URGENCY, ASK | PASS |
| F23 | Remote access app | English | SCAM | CAUTION | CAUTION | 37 | AUTHORITY, ASK | PASS |
| F24 | Remote access app | Hinglish | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F25 | UPI receive QR / PIN-to-receive | Hinglish | SCAM | HIGH_RISK | HIGH_RISK | 70 | THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F26 | UPI receive QR / PIN-to-receive | English | SCAM | CAUTION | CAUTION | 30 | ASK | PASS |
| F27 | UPI receive QR / PIN-to-receive | Hinglish | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F28 | Refund-me-back | Hinglish | SCAM | CAUTION | CAUTION | 45 | THREAT_OR_REWARD, ASK | PASS |
| F29 | Refund-me-back | Hindi (Devanagari) | SCAM | CAUTION | CAUTION | 30 | ASK | PASS |
| F30 | Refund-me-back | English | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F31 | Crypto/investment hook | Hinglish | SCAM | HIGH_RISK | HIGH_RISK | 70 | THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F32 | Crypto/investment hook | Hindi (Devanagari) | SCAM | HIGH_RISK | HIGH_RISK | 70 | THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F33 | Crypto/investment hook | English | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F34 | Aadhaar/PAN update | Hinglish | SCAM | HIGH_RISK | HIGH_RISK | 75 | AUTHORITY, THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F35 | Aadhaar/PAN update | Hindi (Devanagari) | SCAM | HIGH_RISK | HIGH_RISK | 70 | THREAT_OR_REWARD, URGENCY, ASK | PASS |
| F36 | Aadhaar/PAN update | English | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F37 | Legit guard: Devanagari anti-APK advisory | Hindi (Devanagari) | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F38 | Legit guard: delivery SMS with the agent's mobile | English | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F39 | Legit guard: Hindi delivery OTP at the door | Hindi (Devanagari) | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| F40 | Legit guard: routine electricity bill support number | English | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| P01 | Probe: Devanagari OTP notice | Hindi (Devanagari) | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |
| P02 | Probe: mixed-script SBI account block | Mixed (Roman + Devanagari) | LEGIT | LOW_RISK | LOW_RISK | 22 | THREAT_OR_REWARD | PASS |
| P03 | Probe: Devanagari Aadhaar/digital-arrest | Hindi (Devanagari) | SCAM | SUSPICIOUS | SUSPICIOUS | 52 | AUTHORITY, THREAT_OR_REWARD, SECRECY | PASS |
| C01 | Control: bare credential ask | Hinglish | SCAM | CAUTION | CAUTION | 30 | ASK | PASS |
| C02 | Control: threat only, no ask | Hinglish | SCAM | LOW_RISK | LOW_RISK | 22 | THREAT_OR_REWARD | PASS |
| C03 | Control: authority only, no ask | English | SCAM | LOW_RISK | LOW_RISK | 10 | AUTHORITY | PASS |
| C04 | Control: OTP warning (report §3.4 row 1) | English | LEGIT | LOW_RISK | LOW_RISK | 0 | (none) | PASS |

## The three PHASE4 item 5 probes (exact strings, UTF-8)

| probe | string | measured | what is asserted beyond the band |
|---|---|---|---|
| P01 | `आपका ओटीपी ४५६७ है` | 0 / LOW_RISK | normalizer maps `ओटीपी` -> `otp` and Devanagari `४५६७` -> `4567`; digits asserted by code point (U+096A..U+096D) in the test, so the intended script cannot silently degrade |
| P02 | `Aapka SBI खाता block हो गया hai` | 22 / LOW_RISK | mixed Roman + Devanagari is a *supported* pair: never `UNABLE_TO_VERIFY`; `THREAT_OR_REWARD` present from `खाता ... block`; every quote an exact substring of the mixed-script source |
| P03 | `आपका आधार गिरफ़्तारी के लिए इस्तेमाल हुआ है, सुप्रीम कोर्ट का आदेश है। किसी को मत बताना, कमरे में अकेले रहो।` | 52 / SUSPICIOUS | `THREAT_OR_REWARD` from `गिरफ़्तारी`, `SECRECY` from `किसी को मत बताना`, plus the `सुप्रीम कोर्ट ... आदेश` marker: the no-ask ladder lands in `SUSPICIOUS` (`50..69`) |

## Fixture texts (synthetic, no real person's data)

1. **F01** (KYC link + OTP, Hinglish, scam) - `Dear customer, aapka SBI KYC 24 ghante mein band ho jayega. Turant is link par KYC update karein: bit.ly/3xYz9Q`
2. **F02** (KYC link + OTP, English, scam) - `SBI ALERT: Your account will be suspended today. Complete KYC verification at http://sbi-kyc-verify.xyz/update within 2 hours or the account will be closed.`
3. **F03** (KYC link + OTP, English, legitimate) - `Your KYC is due. Visit your branch or update via net banking.`
4. **F04** (Digital arrest text, Hinglish, scam) - `Main CBI officer bol raha hoon. Aapke naam par money laundering ka case darj hai, aap digital arrest mein hain. Video call par rahiye. Kisi ko mat batayein. Turant 2 ghante mein Rs 50,000 verification account mein bhejo.`
5. **F05** (Digital arrest text, Hindi (Devanagari), scam) - `मैं पुलिस अधिकारी बोल रहा हूँ। आपके पार्सल में ड्रग्स मिला है और आपका गिरफ़्तारी वारंट जारी है। किसी को मत बताना। अभी ₹25,000 भेजो।`
6. **F06** (Digital arrest text, English, legitimate) - `RBI advisory: no agency makes arrests over a video call and no one will ask you for money to close a case. Report such calls on 1930.`
7. **F07** (Electricity/gas/SIM disconnect, Hinglish, scam) - `Dear customer, aapka bijli connection aaj raat 9:30 baje kat jayega. Turant is number par call karein 9876543210`
8. **F08** (Electricity/gas/SIM disconnect, English, scam) - `TRAI notice: your SIM card will be deactivated in 24 hours. Call 8899776655 now to verify your number or it will be blocked permanently.`
9. **F09** (Electricity/gas/SIM disconnect, English, legitimate) - `Your electricity bill of Rs 1,240 is due on 30 Oct. Pay through the official app or at any payment centre.`
10. **F10** (Job registration fee, Hinglish, scam) - `Congratulations! Aap work from home job ke liye select ho gaye hain, salary Rs 25,000 monthly. Registration fee Rs 499 abhi is UPI par bhejo: jobpay@ybl`
11. **F11** (Job registration fee, Hindi (Devanagari), scam) - `आपको घर बैठे नौकरी मिलेगी, रोज ₹2000 कमाओ। रजिस्ट्रेशन फीस ₹300 अभी भेजो।`
12. **F12** (Job registration fee, English, legitimate) - `Interview scheduled for 28 Oct, 11:00 am at our Andheri office. Please carry a photo ID. Registration is free.`
13. **F13** (Lottery/KBC, Hinglish, scam) - `KBC lucky draw mein aapka number select hua hai, aapne 25 lakh jeete hain! Prize paane ke liye Rs 6,500 processing fee abhi bhejo. Kisi ko mat batana.`
14. **F14** (Lottery/KBC, English, scam) - `Congratulations! You won a prize of Rs 9,00,000 in the Amazon lucky draw. Pay Rs 4,999 clearance charges by UPI to receive your prize.`
15. **F15** (Lottery/KBC, Hinglish, legitimate) - `SBI alert: lottery ya prize ke naam par koi paisa maange to kabhi mat bhejo. Bank kabhi prize ke liye fee nahi maangta.`
16. **F16** (Courier/customs OTP, Hinglish, scam) - `Courier se bol rahe hain, aapke parcel mein illegal saman mila hai. Customs clearance ke liye Rs 2,500 abhi bhejo aur OTP phone par bata do.`
17. **F17** (Courier/customs OTP, English, scam) - `Your parcel is on hold at customs. Pay Rs 1,499 duty now or it will be returned. Share the OTP you receive with our delivery agent.`
18. **F18** (Courier/customs OTP, English, legitimate) - `Delivery OTP 4521, share only with the agent at your door`
19. **F19** (APK install, Hinglish, scam) - `SBI KYC pending hai. Turant yeh app install karo: sbi-kyc-update.apk , warna account band ho jayega.`
20. **F20** (APK install, Hindi (Devanagari), scam) - `आपका खाता बंद हो जाएगा। तुरंत यह ऐप डाउनलोड करें: sbi-kyc.apk`
21. **F21** (APK install, English, legitimate) - `Install the BHIM app from Google Play to check your balance. Never install APK files that someone sends you on WhatsApp.`
22. **F22** (Remote access app, Hinglish, scam) - `Sir main SBI customer care se bol raha hoon. AnyDesk download karke screen share karein, main abhi account theek kar deta hoon.`
23. **F23** (Remote access app, English, scam) - `This is HDFC bank support. Install QuickSupport so we can verify your account remotely.`
24. **F24** (Remote access app, Hinglish, legitimate) - `Kisi ke kehne par AnyDesk ya TeamViewer install mat karo. Hamari bank kabhi screen share nahi maangti.`
25. **F25** (UPI receive QR / PIN-to-receive, Hinglish, scam) - `Rs 2,000 cashback milega. Turant is QR ko scan karein aur UPI PIN daalein, paisa turant aa jayega.`
26. **F26** (UPI receive QR / PIN-to-receive, English, scam) - `Scan this QR code to receive your refund of Rs 4,999 and enter your UPI PIN.`
27. **F27** (UPI receive QR / PIN-to-receive, Hinglish, legitimate) - `Rs 500 received from Rahul Kumar via UPI. Yaad rahe: paise receive karne ke liye UPI PIN kabhi nahi lagta.`
28. **F28** (Refund-me-back, Hinglish, scam) - `Sir galti se aapke account mein Rs 4,000 bhej diye. Please wapas bhej do isi number par, warna police complaint kar dunga.`
29. **F29** (Refund-me-back, Hindi (Devanagari), scam) - `गलती से आपके खाते में ₹5,000 भेज दिए हैं, कृपया वापस भेज दो।`
30. **F30** (Refund-me-back, English, legitimate) - `Rs 5,000 credited to A/c XX1234. Avl bal Rs 12,340`
31. **F31** (Crypto/investment hook, Hinglish, scam) - `Crypto trading mein 30% monthly return guaranteed hai. Sirf aaj ke liye turant Rs 5,000 is app par deposit karein.`
32. **F32** (Crypto/investment hook, Hindi (Devanagari), scam) - `क्रिप्टो में निवेश करो, 40% मुनाफा पक्का है। आज ही ₹10,000 भेजो।`
33. **F33** (Crypto/investment hook, English, legitimate) - `Market update: bitcoin is down 4% today. Invest only through registered advisors and remember that no return is guaranteed.`
34. **F34** (Aadhaar/PAN update, Hinglish, scam) - `Income Tax Department alert: aapka Aadhaar PAN se link nahi hai, isliye account block ho jayega. Aaj hi apna Aadhaar number aur OTP WhatsApp par bhejo.`
35. **F35** (Aadhaar/PAN update, Hindi (Devanagari), scam) - `आधार कार्ड अपडेट करने के लिए तुरंत अपना आधार नंबर और ओटीपी भेजें। वरना खाता बंद हो जाएगा।`
36. **F36** (Aadhaar/PAN update, English, legitimate) - `UIDAI: Aadhaar update is free at any Aadhaar centre. Book a slot at uidai.gov.in and carry one proof of address.`
37. **F37** (Legit guard: Devanagari anti-APK advisory, Hindi (Devanagari), legitimate) - `सावधान: किसी भी अनजान APK फाइल को डाउनलोड करने से बचें।`
38. **F38** (Legit guard: delivery SMS with the agent's mobile, English, legitimate) - `Your parcel is out for delivery today between 2-5 pm. Call 9876543210 if the address is wrong.`
39. **F39** (Legit guard: Hindi delivery OTP at the door, Hindi (Devanagari), legitimate) - `डिलीवरी ओटीपी 4521, दरवाज़े पर एजेंट को बताएं।`
40. **F40** (Legit guard: routine electricity bill support number, English, legitimate) - `Your electricity bill is due tomorrow. Call 9876543210 if you have questions.`
40. **P01** (Probe: Devanagari OTP notice, Hindi (Devanagari), legitimate) - `आपका ओटीपी ४५६७ है`
41. **P02** (Probe: mixed-script SBI account block, Mixed (Roman + Devanagari), legitimate) - `Aapka SBI खाता block हो गया hai`
42. **P03** (Probe: Devanagari Aadhaar/digital-arrest, Hindi (Devanagari), scam) - `आपका आधार गिरफ़्तारी के लिए इस्तेमाल हुआ है, सुप्रीम कोर्ट का आदेश है। किसी को मत बताना, कमरे में अकेले रहो।`
43. **C01** (Control: bare credential ask, Hinglish, scam) - `Bhai jo code aaya woh bhej de.`
44. **C02** (Control: threat only, no ask, Hinglish, scam) - `Aapka account band ho jayega.`
45. **C03** (Control: authority only, no ask, English, scam) - `This is TRAI. Your number will be disconnected.`
46. **C04** (Control: OTP warning (report §3.4 row 1), English, legitimate) - `123456 is your OTP for txn of Rs 500. Do not share it with anyone. -SBI`

## Legitimate twins and guards - the false-positive fence

| ID | Legitimate message (report §3.4 family) | Score | Band | Verified ask |
|---|---|---|---|---|
| F03 | KYC link + OTP (English) | 0 | LOW_RISK | no |
| F06 | Digital arrest text (English) | 24 | LOW_RISK | no |
| F09 | Electricity/gas/SIM disconnect (English) | 0 | LOW_RISK | no |
| F12 | Job registration fee (English) | 0 | LOW_RISK | no |
| F15 | Lottery/KBC (Hinglish) | 22 | LOW_RISK | no |
| F18 | Courier/customs OTP (English) | 0 | LOW_RISK | no |
| F21 | APK install (English) | 0 | LOW_RISK | no |
| F24 | Remote access app (Hinglish) | 0 | LOW_RISK | no |
| F27 | UPI receive QR / PIN-to-receive (Hinglish) | 0 | LOW_RISK | no |
| F30 | Refund-me-back (English) | 0 | LOW_RISK | no |
| F33 | Crypto/investment hook (English) | 0 | LOW_RISK | no |
| F36 | Aadhaar/PAN update (English) | 0 | LOW_RISK | no |
| F37 | Legit guard: Devanagari anti-APK advisory (Hindi (Devanagari)) | 0 | LOW_RISK | no |
| F38 | Legit guard: delivery SMS with the agent's mobile (English) | 0 | LOW_RISK | no |
| F39 | Legit guard: Hindi delivery OTP at the door (Hindi (Devanagari)) | 0 | LOW_RISK | no |
| F40 | Legit guard: routine electricity bill support number (English) | 0 | LOW_RISK | no |
| P01 | Probe: Devanagari OTP notice (Hindi (Devanagari)) | 0 | LOW_RISK | no |
| P02 | Probe: mixed-script SBI account block (Mixed (Roman + Devanagari)) | 22 | LOW_RISK | no |
| C04 | Control: OTP warning (report §3.4 row 1) (English) | 0 | LOW_RISK | no |

Every one of these stays `LOW_RISK` at `<= 24` with **no verified ask**. Two of them (`F37`, `F38`,
`F39`) were added after an independent review of this lane's own rule changes, because the new
Devanagari ask verbs and the new English callback rule are exactly the kind of change that starts
accusing advice and logistics. `F40` was added after review found that routine bill support could be
mistaken for a high-stakes callback ask:

* `F37` - a cyber advisory that says *do not* download an unknown APK. Without the anti-download skip it
  measured `CAUTION 30` (the advisory names `download` + `APK`, the same two tokens a scam names).
* `F38` - an ordinary delivery SMS that carries the delivery agent's own mobile. Without the
  `HIGH_STAKES_CALLBACK_CONTEXT` requirement it measured `CAUTION 30`; a courier SMS with an agent's number
  is the most common Indian SMS and must stay in the watch band.
* `F39` - the Hindi form of "share the OTP only with the agent at your door". Without extending the
  delivery-agent skip to Devanagari it measured `CAUTION 30`, i.e. the report §3.4 row-5 legit twin was
  protected only in English.

## Rules changed in this lane (deterministic, exact quotes, no threshold loosened)

The changes below are limited to deterministic rule matching, source-span evidence, and context guards.
No score weight, band, floor, ceiling, `corroborationMin`, reduction factor or confidence rule changed.

| # | file | change | why (measured) |
|---|---|---|---|
| R1 | `StageRules.kt` `TELL` | add Devanagari imperatives `भेजें / भेजिए / बताएं` | `F35` `आधार नंबर और ओटीपी भेजें` measured `LOW_RISK 24` - the ask verb existed only in Roman script |
| R2 | `StageRules.kt` `PERSONAL_INFO` | same Devanagari verbs in the personal-detail ask | same row; `अपना आधार नंबर भेजें` produced no ask at all |
| R3 | `StageRules.kt` `URGENCY` | add `अभी / अभी ही` | `F11`, `F32` Hindi rows carried Devanagari urgency that the rule did not know (`तुरंत` was listed, `अभी` was not) |
| R4 | `StageRules.kt` threat | `गिरफ़्तार\p{M}*` instead of a bare `गिरफ़्तार` alternative | `P03`'s `गिरफ़्तारी` could **never** match: under `rx()` the trailing `ी` is a word character, so the `\b` after the stem fails. This was a real, silent dead rule for the commonest Hindi spelling |
| R5 | `StageRules.kt` reward | `मुनाफा / मुनाफ़ा / रिटर्न / प्रॉफिट` in the `N% return` pattern | `F32` `40% मुनाफा पक्का है` measured `CAUTION 36` with no reward stage |
| R6 | `StageRules.kt` threat (new rule) | "Ghar baithe kamai ka laalach": work-from-home/job/salary/daily-income promises, `LOW`/`WEAK` | `F10` `Congratulations! ... work from home job ... salary Rs 25,000` and `F11` `रोज ₹2000 कमाओ` produced no stage 2 at all |
| R7 | `StageRules.kt` threat (new rule) | "Warna police/case ki dhamki": `warna / nahi to / वरना` + `police / complaint / FIR / case / legal action`, `LOW`/`WEAK` | `F28` `wapas bhej do ... warna police complaint kar dunga` was read as an ask with no intimidation stage |
| R8 | `UtilityServiceRules.kt` (new rule) | English imperative callback (`Call 8899776655 ...`) against a personal mobile, `HIGH`/`STRONG`, guarded, requiring `HIGH_STAKES_CALLBACK_CONTEXT` and skipping official-number context | `F08` `Call 8899776655 now to verify your number` measured `LOW_RISK 24`: the only item that fired was the `MODERATE` "personal mobile next to a brand" corroboration, which can never lift a band. `F38` keeps the requirement honest |
| R9 | `UtilityServiceRules.kt` KYC-link rule | third ordering: `update verb -> KYC noun -> unofficial link` | `F02` `Complete KYC verification at http://sbi-kyc-verify.xyz/update` measured `LOW_RISK 24` - English puts the verb first, the two existing patterns only had noun-first and link-first |
| R10 | `StageRules.kt` negation | add post-positional `न`, and add combining marks (`\p{M}`) to every negation word boundary | found while fixing `F35`: `न` inside `नंबर` was a standalone "negation" token (a mark is neither a letter nor a number), so `आधार नंबर ... भेजें` was read as *negated* and the ask vanished. The mark-aware boundary is the root fix; the extra `न` is what keeps `ओटीपी किसी को न बताएं` protective (pinned by `SecrecyScopingTest`) |
| R11 | `StageRules.kt` APK rule | `skip = SKIP_ANTI_DOWNLOAD_ADVICE` (`बचें / avoid / beware / do not download ...`) | stops the APK ask accusing an anti-APK advisory (`F37`). Deliberately **not** triggered by a bare "alert"/"warning", because "Warning: account band ho jayega, yeh APK install karo" is a real scam shape |
| R12 | `StageRules.kt` delivery skip | extend the door/agent skip to `दरवाज़े / दरवाजे ... एजेंट` | `F39`: report §3.4 row 5's legit twin was only protected in English |
| R13 | `UtilityServiceRules.kt` | `appointment` added to the official-channel skip | a "call 9876543210 to book an appointment" KYC reminder is an official-channel act, not a callback ask |
| R14 | `UtilityServiceRules.kt` callback ask | Requires an account, credential, verification, suspension, disconnection, or payment stake; bill/due-date wording alone is insufficient | `F40` is the legitimate routine electricity-bill support twin; the existing Hindi cutoff scam remains asserted |
| R15 | `StageRules.kt` money ask | remove fee/duty nouns as standalone asks; recognize directional `pay/transfer/send + amount` | past customs duty and job registration fee controls exposed false asks; the payment must be requested |
| R16 | `ScamOfferRules.kt` | remove past-tense `paid` as an imperative payment verb | a customs duty paid at checkout is not a new request to pay |
| R17 | `DevanagariRules.kt` | add Hindi money nouns/verbs (`शुल्क`, `फीस`, `जुर्माना`, `भेजें`, `जमा करें`, `डालें`, `भुगतान करें`) and number handling | the measured Hindi scam rows needed these forms to retain a directional ask |
| R18 | `StageRules.kt` negation scope | stop a preceding prohibition at `and` / `और` / `but` / `लेकिन`; treat `नहीं तो` as conditional | “किसी को मत बताना और ... रुपये भेजो” contains two actions; “भेजें नहीं तो केस दर्ज होगा” is a threat tail |
| R19 | `StageRules.kt` OTP ask / delivery skip | recognize “read the OTP to me” and allow the explicit courier-agent-at-door control | the English phone request is an ask, while handing the code to a nearby delivery agent is a legitimate flow |
| R20 | `StageRules.kt` personal-info ask | recognize requests for a seed/recovery phrase or private key | the investment fixture asks for wallet credentials without changing the score threshold |
| R21 | `HinglishNormalizer.kt` obfuscation whitelist | fold spaced `b l o c k` and leetspeak `acc0unt`, `bl0ck`, `c0de`, `kar0` to their known words while preserving raw spans | existing end-to-end obfuscation cases require threat plus ask evidence from these exact spellings |
| R22 | `StageRules.kt` money ask | allow up to 48 characters between a directional pay/transfer/send verb and an explicit amount | “Pay courier and GST charges of ₹4,999” is a requested payment, while an unrequested duty mention stays non-ask |
| R23 | `UtilityServiceRules.kt` utility threat | recognize explicit English “power will be cut” / “electricity will be disconnected” wording | the new English disconnection row was otherwise invisible to the threat stage |

Two expectations were corrected rather than "made green" by a rule, and both corrections are recorded in
the fixture source:

* `F17` (courier/customs OTP, English) was expected `CAUTION` and measures `HIGH_RISK 70`. The authority
  pattern matches a bare agency noun, so `on hold at customs` is a genuine third independent stage next to
  the parcel threat and the OTP ask. The engine is right and the expectation was wrong; the row now
  expects `HIGH_RISK` with that reasoning in a comment. (Side effect worth knowing: a *legitimate* courier
  message that names customs would also collect an authority stage - `F18` escapes only because it does
  not.)
* `F28` (refund-me-back, Hinglish) was expected `HIGH_RISK` and measures `CAUTION 45`. With R7 the message
  has exactly one other stage, and rule 5 caps "ask + one other stage" at `35..49` on purpose: a friend,
  or a hacked friend, can also ask for money back. `F29` (the Hindi twin) is `CAUTION 30`, ask alone.

## Audit trail: the first measured run, before any rule changed

The first run of this inventory (43 rows, no rule edits) measured **33 PASS / 10 MISS**. That run is what
drove the fixes above; keeping it visible is the point of an honest fixture table:

| row | measured then | cause |
|---|---|---|
| F02 | LOW 24 | English verb-first KYC link (R9) |
| F08 | LOW 24 | English imperative callback missing (R8) |
| F10 | CAUTION 36 | no reward stage for a job/daily-income promise (R6) |
| F11 | CAUTION 30 | no Hindi urgency `अभी`, no Hindi reward verb (R3, R6) |
| F17 | HIGH 70 | *not* a rule gap - the expectation was wrong (see above) |
| F20 | LOW 24 | Devanagari `डाउनलोड` not an APK verb (R1 family) |
| F28 | CAUTION 30 | no intimidation stage for `warna police complaint` (R7), and the ask-alone ceiling |
| F32 | CAUTION 36 | `मुनाफा` not a reward noun (R5) |
| F35 | LOW 24 | Devanagari ask verbs missing (R1, R2) - and, underneath it, R10 |
| P03 | CAUTION 38 | `गिरफ़्तारी` could never match the threat rule (R4) |

## Known bounds (stated, not hidden)

1. **A fee noun alone is not a payment request.** The base money rule requires a directional ask rather
   than treating `registration fee` or `customs duty` as an ask by itself. The negative control
   `There is no application or registration fee.` stays LOW_RISK, and `no` is deliberately not a global
   negation token: `No problem sir, OTP batao` must still retain its ask. Explicit negation is scoped to
   the action, and conjunctions such as `and` / `और` end the preceding action's scope.
2. **`SKIP_OFFICIAL_NUMBER` contains `\b1[89]\d{2}\b`** to recognise 1800/1860/1930 helplines. A
   four-digit number between 1800 and 1999 anywhere within ~24 characters of a callback ask (an amount
   like `Rs 1900`, a year) therefore voids that ask. Pre-existing, not introduced here, and now more
   reachable because R8 gives that rule more to fire on. Recorded, not fixed, because tightening it to
   real helpline shapes is a separate change with its own tests.
3. **Bare agency nouns count as authority.** `customs`, `police`, `CBI`, `TRAI` in a message is stage 1 by
   itself (`F17`). This is spec-consistent ("authority alone: weak/watch") but it means a *legitimate*
   customs/courier message collects a weak stage; it can only matter once an ask is also present.
4. **A composed Hindi recruitment message can reach `HIGH_RISK`.** "selected for a work-from-home job,
   salary 25,000, send your Aadhaar number, call 9876543210" now collects reward + personal-info ask and
   lands at the floor. That is intended - it is a textbook document-harvesting job scam - but it is the
   shape most likely to annoy a genuine recruiter, and it is worth watching on a real phone.
5. **Legit twins have no headroom in two rows.** `F06` (RBI advisory that this app's rules read as
   authority + threat) sits at exactly `24`, the `LOW_RISK` ceiling. Any future relaxation of
   `NO_ASK_CEILING`, or one more marker in that sentence, moves it.
6. **This is rule coverage, not accuracy.** 167 synthetic rows with hand-written expectations cannot
   estimate a false-positive rate on real traffic. The category rates above are limited to six selected
   scam/control pairs per family. A real 300-message holdout is not possible in this
   window (HEAD_ORDERS_ANDROID section 2.7); the honest reporting stays "rule/normaliser tests".

## Final verification run (2026-10-28)

Commands ran from `apps/android` in the isolated `android/P7-A-detection` worktree. Gradle used
`--no-daemon -Dorg.gradle.jvmargs=-Xmx2g` and the configured Android SDK. The connected journey used only
the already-running shared `emulator-5554` (`head-api36`); no physical device was targeted.

| Command | Result |
|---|---|
| `.\gradlew.bat :app:testDebugUnitTest --no-daemon '-Dorg.gradle.jvmargs=-Xmx2g'` | PASS — 1,192 tests, 0 skipped, 0 failures, 0 errors (61 JUnit XML report classes). Tail: `BUILD SUCCESSFUL in 10m 10s`; `25 actionable tasks: 1 executed, 24 up-to-date`. |
| `.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=ai.trustshield.android.Phase3JourneysTest' --no-daemon '-Dorg.gradle.jvmargs=-Xmx2g'` | PASS — `Phase3JourneysTest` 11/11, 0 skipped, 0 failed on `head-api36(AVD) - 16`. Tail: `BUILD SUCCESSFUL in 1m 20s`. |
| `.\gradlew.bat :app:assembleDebug --no-daemon '-Dorg.gradle.jvmargs=-Xmx2g'` | PASS — `BUILD SUCCESSFUL in 12s`; arm64-only APK at `apps/android/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (86,665,802 bytes), SHA-256 `aba8534b37a4babae6c6dd78822b2817840061431a176d9429da2101e2d22c03`. |

The fixture-specific run ended `SUMMARY|total=167|pass=167|miss=0`. The 47 pre-existing cases remain
green with exact expected bands, including electricity-disconnection F07 at 70 and the bank-credit control
at 0. The expanded synthetic categories are summarized above; these values do not establish real-world
accuracy or satisfy the separate 90% holdout-recall goal.

## Contract and privacy notes

* Every message above is invented for this table. No real person's message, number, OTP, account or name
  appears, and none of the URLs is opened, resolved, fetched or followed - they are inert strings in a
  synthetic text.
* The risk score and the confidence are two separate fields in every row and stay that way; a truncated,
  short or unsupported-script message caps confidence and never the score. No row is `UNABLE_TO_VERIFY`
  (the test asserts that a readable row never is).
* No `100% safe` claim is made anywhere: `LOW_RISK` rows carry the engine's own watch-level limitation
  copy, and the legitimate rows still state why they are watch-level rather than safe.

## Utility disconnection regression controls (#147)

The six Hinglish electricity-disconnection cases from PR #158 remain covered by `LegitScamPairsTest`. Each must score at least 60, include a threat stage and callback ask, and quote the disconnection wording and bill-update action from the input. The legitimate bank credit control must remain exactly 0.

| Case | Text | Minimum score |
|---|---|---:|
| Original | Aapka bijli connection aaj raat 9:30 baje kaat diya jayega. Turant is number par call karein 9876543210 aur bill update karein. | 60 |
| Katega | Aapka bijli connection aaj raat katega. Turant 9876543210 par call karein aur bill update kar dein. | 60 |
| Band | Aaj raat aapka bijli connection band kar diya jayega. Bill update karne ke liye call karein 9876543210. | 60 |
| Cut | Aapka electricity connection aaj raat cut ho jayega. Bill update karein aur 9876543210 par call karein. | 60 |
| Last date | Bijli bill ki last date aaj hai, warna connection kaat diya jayega. Bill update karne ko call karein 9876543210. | 60 |
| Last date paraphrase | Aaj last date hai: aapka power connection cut ho jayega. Bill update karein, call karein 9876543210. | 60 |
| Legitimate bank credit | Rs 5,000 credited to A/c XX1234. Avl bal Rs 12,340. Never share OTP. | 0 |
