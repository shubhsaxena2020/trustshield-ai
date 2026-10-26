# Defect evidence — "before" captures (D5-prep)

Real captures from **emulator-5556**, 1080x2400, density 420, API 36, `font_scale=1.0`, airplane-mode
not required (the app is offline-only). No product code was changed for any of these.

| | |
|---|---|
| app | `ai.trustshield.android` / `ai.trustshield.android.MainActivity` |
| build | `android-finals` @ **f2f307e** (`./gradlew :app:assembleDebug`) |
| APK installed | `apps/android/app/build/outputs/apk/debug/app-x86_64-debug.apk` |
| APK sha256 | `f5ecc52d0f58e5aa6c3dd29897b208de98a4b256c1353bfd0076ac8e5d0b0e72` |
| captured | 26-Oct-2026, 04:34–04:47 IST |
| harness | `~/AppData/Local/hermes/profiles/agent2/cache/scratch/defect-evidence.sh` + `uitap.py` (outside the repo) |
| cheat-sheet | `~/AppData/Local/hermes/profiles/agent2/cache/scratch/CHEATSHEET-defect-evidence.md` |

## What each PNG proves

### #118 — electricity-bill disconnect scam scores 8/100 (false negative)

- `d118-before-bill-scam-home.png` — the exact report message typed into Home, keyboard dismissed:
  `Dear customer, aapka bijli connection aaj raat 9:30 baje kat jayega. Turant is number par call karein 9876543210`
- `d118-before-bill-scam-result.png` — verdict **8/100 · "Kam khatra"**, a single `Jaankari / Jaldbaazi
  (time pressure)` card quoting `aaj raat`, and the advice line "Yeh message akele khatra nahi dikhata".
  Expected after the fix: Medium or higher (per issue #118).

### #114 — secrecy rule false-fires on a legitimate bank OTP warning

- `d114-before-otp-secrecy-card.png` — chip **"Bank credit"**
  (`Rs 5,000 credited to A/c XX0123 on 12-Oct. Avl bal Rs 12,450. Yeh OTP kisi ko na batayein.`),
  verdict **10/100 · "Kam khatra"**, and the **first evidence card is the amber "Dhyan dein /
  Kisi ko batane se roka"** quoting `kisi ko na` — while further down the same screenshot shows the
  **"Legitimate signal: OTP/code share karne se mana kiya gaya hai"** card. The two contradict.

### #115 — stray visible "Risk score: N/100" above the Result header

- `d115-before-result-stray-score.png` — chip **"SBI KYC"**, verdict **70/100 · "Zyada khatra"**;
  a plain-text line `Risk score: 70/100` sits above the `‹ Peeche | Result | English` header row
  (bounds `[53,154][383,203]` in the dump). The number appears in no styled card.
- The same stray line is visible in `d118-before-bill-scam-result.png` (`8/100`) and
  `d114-before-otp-secrecy-card.png` (`10/100`), so three independent scores show it.

### #119 — Call Shield demo meter does not move; sticky panel hides it

- `d119-before-demo-layout.png` — demo mode just entered: the consent/session block still occupies the
  top, the sticky bottom block starts at the disabled `Stop — sunna band karein` bar (y≈1666 of 2400,
  ~31 % of the screen) and the `Khatre ka level` card is already clipped.
- `d119-before-demo-meter-step{1,2,3,4}.png` — after 1–4 taps on `Next line`:
  caption `Kam · 10/100 · HIGH` → `Kam · 10/100 · HIGH` → `Dhyan dein · 37/100 · HIGH` →
  **`Turant rukein · 70/100 · HIGH`**, while the meter card's own semantics stay
  `Jaankari — koi khatra nahi mila. Confidence: Verify nahi ho paya` (level 1 of 4) at every step.
- Measured at step 4: meter card `[42,1450][1038,1536]` = **86 px** tall vs `[42,1450][1038,1978]`
  = 528 px on the non-demo Call Shield screen → ~84 % of the card is hidden behind the sticky block.

### Flow shots

- `flow1-home-118-text-entered.png` — Home with the #118 message in the field (112/20,000), keypad
  dismissed, before tapping Check.
- `flow2-home-114-chip-filled.png` — Home after the "Bank credit" example chip filled the field.
- `flow3-share-intent-result-118.png` — the **share-intent** path (`ACTION_SEND` + `EXTRA_TEXT`) landing
  straight on the Result screen with the same 8/100 verdict — one command, no taps.

## Commands used (copy-pasteable)

```bash
export ANDROID_HOME="C:/Users/shubh/AppData/Local/Android/Sdk"
ADB="$ANDROID_HOME/platform-tools/adb.exe"
A() { "$ADB" -s emulator-5556 "$@"; }          # always -s emulator-5556

# install the build under test
A install -r -g apps/android/app/build/outputs/apk/debug/app-x86_64-debug.apk

# #118 — type the message, hide the IME, Check
A shell am start -n ai.trustshield.android/.MainActivity -W
A shell input tap 540 800                       # message field [42,615][1038,1152]
A shell input text 'Dear%scustomer,%saapka%sbijli%sconnection%saaj%sraat%s9:30%sbaje%skat%sjayega.%sTurant%sis%snumber%spar%scall%skarein%s9876543210'
sleep 2 && A shell input keyevent 4             # BACK hides Gboard (ESC/111 did not)
A shell input tap 540 1787                      # "Check karein (offline)" — RE-DUMP first, it moves
sleep 3

# #114 / #115 — example chips, no typing needed
A shell input tap 633 1757                      # chip "Bank credit"   (150 1757 = SBI KYC)
A shell input tap 540 1787                      # Check
sleep 3

# #119 — Call Shield demo script
A shell am start -n ai.trustshield.android/.MainActivity --es ai.trustshield.android.extra.ROUTE call_shield -W
sleep 3
A shell input tap 540 2263                      # "Demo script mode"
sleep 2
# "Next line" moves between taps ([346,2227][512,2290] → [283,2196][575,2322]) — re-dump each time
A shell input tap 429 2259                      # x4, 2 s apart

# share-intent path (one command to a scored Result)
A shell am start -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT "'<synthetic message>'" \
    -n ai.trustshield.android/.MainActivity -W
sleep 3

# screenshot
A exec-out screencap -p > shot.png

# derive coordinates instead of guessing
A shell uiautomator dump /sdcard/ui.xml && A shell cat /sdcard/ui.xml
```

Use `defect-evidence.sh all <outdir>` instead of the raw commands: it re-dumps before every tap,
chunks the typing and verifies the exact text, pins `font_scale` to 1.0, and refuses to type or shoot
when another app owns the foreground.

## Caveats the "after" pass must know

- **The emulator is shared and volatile.** During this session another lane uninstalled the app
  (`Activity class does not exist`), installed a newer build mid-run (bill-scam score became 20/100
  and the stray score line disappeared), left `font_scale` at **1.3**, opened Calendar/Gmail on top of
  the app, and finally wedged the device (adb connects, every `shell` command returns empty) while its
  `qemu` process still runs. Re-install + hash-verify + foreground-check before every capture.
- Screenshots above were taken 04:34–04:47, immediately after installing the sha256 above at 04:33;
  the foreign build was first observed at 04:50, after the last capture.
- `uiautomator dump` returns `[0,0][0,0]` for off-screen nodes — never tap those numbers.
- These are "before" shots. Nothing here claims the app is safe or that any fix has landed.
