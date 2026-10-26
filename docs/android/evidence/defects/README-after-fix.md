# Defect evidence — after-fix captures (emulator-5556)

Device: `emulator-5556` (AVD `head-api36`, API 36, 1080×2400, font scale 1.0, light theme).
Build: `android/D-layout` @ `origin/android-finals` (`f026b29`) + this branch's demo-meter band and
haptics-toggle fix. App id `ai.trustshield.android`, `MainActivity`.
Captured with `adb -s emulator-5556 exec-out screencap -p`; every state was driven by real taps /
a real share intent, and the text quoted below is from `uiautomator dump` of the same screen.

## #118 — electricity-bill disconnect scam (was 8/100 LOW)
`d118-after-bill-scam-result.png` — share intent with
`Dear customer, aapka bijli connection aaj raat 9:30 baje kat jayega. Turant is number par call karein 9876543210`
→ **"!! Zyada khatra", Score: 70/100**, confidence "Zyada bharosa", evidence cards with exact quotes:
`Bijli/connection katne ki dhamki` ("bijli connection aaj raat 9:30 baje kat jayega"), `Jaldbaazi`
("aaj raat"), `Personal number par call karne ko kaha`.

`d118-after-legit-bill-low.png` — `Your electricity bill of Rs 1,240 is due on 30 Oct.` →
**"Kam khatra", Score: 0/100**, advice "Yeh message akele khatra nahi dikhata".

## #114 — secrecy rule must not fire on an OTP safety warning
`d114-after-otp-safety-warning.png` — `Rs 5,000 credited to A/c XX0123 on 12-Oct. Avl bal Rs 12,450. Yeh OTP kisi ko na batayein.`
→ **"Kam khatra", Score: 0/100**; the only cards are `Legitimate signal` items (`Credit/balance alert -
koi maang nahi`, and the OTP-sharing advice). The old amber `Kisi ko batane se roka` card is gone.

## #119 — demo meter climbs LOW → MEDIUM → HIGH → CRITICAL, Reset returns to LOW
Call Shield → `Staged demo (live mic nahi)` → the compact row `Peeche / Agli line / Phir se`
(tap `Agli line` at 540,2253):

| shot | meter label | score · confidence |
|---|---|---|
| `d119-after-demo-step0-ready.png` | "Pehli line ke liye taiyaar" (no reading, no segments) | — |
| `d119-after-demo-step1.png` | **Kam** | 10/100 · Samajh: Zyada |
| `d119-after-demo-step2.png` | **Dhyan dein** | 44/100 · Samajh: Zyada |
| `d119-after-demo-step3.png` | **Zyada khatra** | 52/100 · Samajh: Zyada |
| `d119-after-demo-step4.png` | **Turant rukein** (red alert + release copy) | 75/100 · Samajh: Zyada |
| `d119-after-demo-reset.png` | **Kam** — "Reset · KAM. Memory wala transcript saaf ho gaya." | — |

## #115 — no stray "Risk score: N/100" above the Result header
Visible in all four Result shots above: the status bar is followed straight by the `‹ Peeche / Result /
English` row. The score appears only inside the styled summary card (`Score: 70/100`), followed by the
four-segment band.

## Honest notes
- `emulator-5554` belongs to another agent and was never touched; `emulator-5556` was started fresh for
  this capture (`-memory 2048`, `-gpu swiftshader_indirect`) after an earlier instance disappeared.
- `font_scale` was left at `1.3` by an earlier lane and has been restored to `1.0` before capturing.
- These shots are of the layout branch; the scoring behaviour they show is the merged trunk engine.
