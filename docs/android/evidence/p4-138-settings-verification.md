# Phase 4 item 6 / issue #138 — Settings really change behaviour

Measured on **one** API 36 emulator (`emulator-5554`, AVD `trustshield-api36`,
`-memory 2048 -no-snapshot`, `adb emu kill` afterwards), IST 2026-10-26 21:23–22:45.

Two builds were used, both built here with `-Xmx2048m`:

| Build | Source | x86_64 APK SHA-256 |
|---|---|---|
| before the fix | `android/P4-settings` rebased on `00efd52` (`android-finals`), unmodified app code | `9A8E9406DD8105A468BDF75800DB1E3C55702085F6C123084AE2D4ADB2DE3097` |
| after the fix | same base + the `CallShieldRouteHost` stored-choice fix in this branch | `72211F624831FB7C8540269F047EBB5F4D941190F0234E1399F9F0A5B270C2EE` |

`9A8E94…` is byte-identical to the artifact the head recorded for the merged tree, so the pre-fix
measurements describe shipped `android-finals` behaviour, not a local variant.

## Result

| # | Acceptance criterion | Result | Measured evidence |
|---|---|---|---|
| 6a | Language toggle flips **every** screen, no mixed English/Hinglish headers | **FAIL before fix → PASS for Call Shield after fix; still OPEN on Result** | Home / Settings / QR Guard render fully in both languages. Call Shield rendered **Hinglish while the app was in English** (defect D1, fixed here — see below). The Result screen's own copy flips, but every evidence title/detail stays Hinglish (defect D2, **not fixed**, routed to the rules/model lane). |
| 6b | Theme light / dark / system persists | **PASS** | `Always dark` → restart → still dark; `Always light` → restart → still light; `Match phone (system)` + `cmd uimode night yes` → app dark, `night no` → app light. |
| 6c | Sensitivity moves a borderline message's band **measurably** | **PASS** | Same message, three sensitivities: **LOW 40/100 "Kam khatra" (CAUTION)**, **BALANCED 70/100 "Zyada khatra" (HIGH_RISK)**, **HIGH 70/100 (HIGH_RISK)**. Band changes CAUTION → HIGH_RISK. |
| 6d | Critical haptics suppressed when the toggle is off | **PASS** | Demo ladder reached **75/100 CRITICAL** in every run. Haptics ON → exactly **1** new `ai.trustshield.android … performHapticFeedback(constant=0)` (`HEAVY_CLICK`, 36 ms) at the Critical step. Haptics OFF → **0** new entries. Repeated on the **fixed** build: 1 new entry at 22:41:06 (ON) and 0 new entries (OFF). |

## What was driven, exactly

Synthetic text only; no personal data was typed, and no analysed message was written to any file.

- Language pass: Home, Result, Call Shield, QR Guard and Settings were captured in **both** languages
  from the same install (`Settings → Bhasha / Language`).
- Theme pass: each theme mode was selected in Settings, captured, then the app was
  `am force-stop`-ed and relaunched and captured again.
- Sensitivity pass: borderline message
  `Main SBI head office se bol raha hoon. Aapka block ho gaya hai. Jo OTP aaya hai woh batao.`
  (authority + threat + ask, i.e. a verified ask with **two** corroborating stages — the exact case
  `BEHAVIOR_SPEC` says LOW needs three stages for) scored once per sensitivity setting.
- Haptics pass: Call Shield → `Staged demo (not live mic)` → `Next line` ×4, with
  `adb shell dumpsys vibrator_manager` read before and after each run.

Screenshots (all 1080×2400 captures, downscaled to 540×1200 for the repo):

| File | Shows |
|---|---|
| `p4-138-home-hinglish.png`, `p4-138-home-english.png` | Home in both languages |
| `p4-138-settings-hinglish.png`, `p4-138-settings-english.png` | Settings in both languages |
| `p4-138-result-hinglish.png` | Result, Hinglish |
| `p4-138-result-english-mixed-evidence.png` | Result, English — defect **D2**: English headers, Hinglish evidence titles |
| `p4-138-callshield-defect-before-fix.png` | Call Shield while the app was in English — defect **D1** |
| `p4-138-callshield-english-after-fix.png` | Same screen, same preference, after the fix |
| `p4-138-callshield-hinglish.png` | Call Shield, Hinglish |
| `p4-138-qr-english.png`, `p4-138-qr-hinglish.png` | QR Guard in both languages |
| `p4-138-theme-dark-after-restart.png` | `Always dark`, captured after a process restart |
| `p4-138-theme-light-after-restart.png` | `Always light`, captured after a process restart |
| `p4-138-theme-system-device-dark.png` | `Match phone (system)` with the device in night mode |
| `p4-138-theme-system-device-light.png` | `Match phone (system)` with night mode off |
| `p4-138-sensitivity-low-caution.png` | 40/100, caution |
| `p4-138-sensitivity-balanced-highrisk.png` | 70/100, high risk |
| `p4-138-sensitivity-high-highrisk.png` | 70/100, high risk |
| `p4-138-haptics-on-critical.png` | Demo at 75/100 Critical with haptics ON (pre-fix build) |
| `p4-138-haptics-off-critical.png` | Demo at 75/100 Critical with haptics OFF (pre-fix build) |
| `p4-138-haptics-on-critical-after-fix.png` | Same, on the fixed build — 1 new vibration |
| `p4-138-haptics-off-critical-after-fix.png` | Same, on the fixed build — 0 new vibrations |
| `p4-138-vibrator-on.txt`, `p4-138-vibrator-off.txt` | Raw `dumpsys vibrator_manager` lines for both runs |
| `p4-138-settings-restored-defaults.png` | Settings returned to the shipping defaults afterwards |

## Defect D1 — Call Shield ignored the stored language and haptics toggle (FIXED)

**Symptom (measured).** With `settings.language = ENGLISH` persisted across `am force-stop`, Home,
Settings and QR Guard all rendered English, but Call Shield rendered Hinglish copy *and* its language
button still offered "English" — i.e. the screen believed it was in Hinglish.
Screenshot: `p4-138-callshield-defect-before-fix.png`, hierarchy text included
`Sirf woh awaaz jo phone ke mic tak pahunchti hai …`, `Pehle anumati`, `Backend check: band`,
`Session shuru karein`.

**Cause.** `CallShieldRouteHost` owns a `CallShieldHost` that is constructed before the screen reads
any preference, so `host.state` carries `CallUiState`'s field defaults (`language = HINGLISH`,
`hapticsEnabled = true`). Every path that copied it verbatim — `CallEvent.Start/Stop/Pause/Resume`,
the ASR utterance sink, the `ON_PAUSE`/`ON_RESUME` lifecycle observer and the `LaunchedEffect` that
seeds capability — reset the two stored choices. The same reset re-armed the haptics gate inside the
session state, which is a second route to a Critical pulse with the toggle off (the demo path was
already gated in #119B; the live session state was not).

**Fix.** New pure function `CallUiState.withStoredChoices(language, hapticsEnabled, reducedMotion)`
(`ui/call/CallStoredChoices.kt`) plus one `synced(...)` helper in `CallShieldRouteHost` that every
host snapshot now goes through. Session content (score, tone, confidence, chips, transcript,
`hapticPulses`) is passed through untouched — the sync re-applies presentation choices only and
never re-scores. Tests: `CallStoredChoicesTest` (6 JVM tests), including the regression, the “repeated
host snapshots cannot walk the choices back” case, and the pulse-counter preservation that would
otherwise re-fire the vibration.

**After fix (same device, same persisted preference).** `p4-138-callshield-english-after-fix.png`:
`Only audio that reaches the phone microphone …`, `Permission first`, `I understand, give
permission`, `Backend check: off`, `Start session`, `Stop — stop listening`, and the button now
correctly offers `Hinglish`.

## Defect D2 — Result screen keeps Hinglish evidence text in English mode (OPEN, not fixed here)

Measured: with English selected, the Result screen shows English chrome
(`What we found (with exact quotes)`, `Information`, `Strong signal`, `Exact quote`, `High risk`) over
Hinglish evidence titles and details:

```
Account band karne ki dhamki
Message account, KYC, SIM ya card band/suspend hone ki dhamki deta hai. …
Jaldbaazi (time pressure)
OTP/PIN maanga gaya
```

Evidence: `p4-138-result-english-mixed-evidence.png` (Hinglish twin: `p4-138-result-hinglish.png`).

Root cause is structural, not a missing translation in the UI: `core/model/Evidence` carries exactly
one `titleHinglish` + `detail` pair (BEHAVIOR_SPEC §2 rule 3 schema), and the 35 distinct titles live
in `StageRules`, `LegitimateIndicators` and `DigitalArrestSignals`. A correct fix therefore belongs
to the evidence model / rules lane (adding an English variant per rule) rather than to a
presentation-layer string map, and that lane is being edited concurrently for P0 items 4 and 5. It is
reported here with exact copy so the owner of that lane can take it; the settings lane did not
introduce it and cannot fix it without a cross-lane model change.

## Observations (out of this lane, not changed)

- Home shows two overlapping empty-state lines at once: `There is no text to check yet. Paste or type
  a message first.` and `An empty message cannot be checked. Paste or type some text first.`
  (visible in the Hinglish and English Home captures).
- In dark theme the `Examples (synthetic samples)` heading is markedly dimmer than the surrounding
  body copy (visible in `p4-138-theme-dark-after-restart.png`).
- `QR Guard`, like Call Shield, renders its own camera-permission panel before the phone grants
  CAMERA; both languages were correct.

## Gaps and honest limits

- **Live ASR was not measurable on this emulator.** The speech service returns
  `ERR_INTERNET_DISCONNECTED` (recorded in Phase 3), so the haptics half of D1 is proven by the state
  rule and `CallStoredChoicesTest`, not by an emulator vibration on the live path. The *measured*
  haptic result is the staged-demo path, which is the path a demo/judge flow uses.
- Emulator vibration proves the platform haptic call (`performHapticFeedback(constant=0)`,
  `HEAVY_CLICK`, 36 ms), not what a real motor feels like on a phone.
- Captures are at the default font scale and 411 dp width; 200 % font scale, 320 dp width and spoken
  TalkBack traversal remain open under issue #97.
- Scores are deterministic engine outputs, not an accuracy or probability claim.
- Settings persistence evidence comes from the UI plus the store itself; the file holds exactly the
  five allowlisted keys and no analysed content:

```
<String name="settings.sensitivity">BALANCED</String>
<String name="settings.haptics">true</String>
<String name="settings.reduced_motion">false</String>
<String name="settings.language">HINGLISH</String>
<String name="settings.theme_mode">SYSTEM</String>
```

The device was returned to the shipping defaults afterwards (`SYSTEM` theme, Hinglish, Balanced,
haptics on, device night mode off) — `p4-138-settings-restored-defaults.png`.

## Follow-up: English Result copy

The Result-language gap above is fixed in the presentation adapter. English mode now receives
translated evidence titles/details and local actions/limitations; the original Hinglish fields and
verified quotes remain unchanged. See `p4-138-english-result-copy.md` for the focused RED/GREEN
record. On the PR branch after the fix, `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
passed: **1,097 JVM tests, 0 failures**, including 3 adapter tests.

This does not close the full settings item yet: a post-fix emulator screenshot/spot check, reduced
motion behavior, and reset confirmation measurement remain open. No physical phone was used.
