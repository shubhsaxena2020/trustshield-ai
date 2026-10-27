# Android UI specification

Compose app, portrait-first, 360–430 dp reference width; tablet uses a centered max-width content column. Five primary destinations: **Check**, **Call Shield**, **QR Guard**, **History**, and **Settings**. Result is a secondary destination in the Check journey. Check accepts typed or pasted text and shows Share intake review. Its Check action remains pinned above bottom navigation as supporting content scrolls. Result is a scrollable risk panel with separate confidence badge, grouped evidence cards containing exact highlighted quotes, practical actions and limitations. Call Shield presents consent, mic/offline status, active timer, five stage chips, risk meter, a large Stop control, and a sticky warning. QR Guard presents setup/expected merchant, active camera, valid mismatch count, unreadable/occluded state and PIN-gated re-register. Settings contains theme, language, sensitivity and haptics; never exposes a “turn off all warnings” preset.

## Tokens and motion

Accent `#2F6BFF`; risk green `#16845B`, amber `#B86B00`, red `#C73642`. Use label+icon+text as well as color, with accessible dark counterparts and >=4.5:1 normal-text contrast. Use Material 3 system typography at 14/16/20/28 sp, minimum 48 dp touch targets, 8 dp spacing grid and 12–20 dp corner radii. Motion: 180–240 ms risk/stage transitions, no pulsing critical alert; reduced-motion/animator scale zero removes nonessential transitions. Haptic pattern only on changed high-risk state and only when enabled; no repeated vibration per ASR chunk. Avoid score animation that implies fabricated precision.

## Copy and states

Default Hinglish, optional English. Sample: “OTP mat dein. Is message mein code maanga gaya hai: ‘jo OTP aaya hai batao’.” High-risk warning: “Digital arrest scam ka pattern mila. Call kaat kar official number se verify karein; paise bheje hon to 1930 par call karein.” Do not claim a real caller is a scammer from authority words alone. `Unable to Verify` says what failed and the safest next step. Never “100% safe.”

| State | UI response |
|---|---|
| Empty/emoji-only | “Check karne layak text nahi mila”; leave scan action disabled until content exists. |
| Offline | Local rules remain available; clearly mark backend checks unavailable and confidence impact. |
| Mic denied or ASR unavailable | Explain device permission/support; offer typed transcript or staged speech demo path, without labeling it live ASR. |
| Noisy/low ASR confidence | “Awaaz saaf nahi”; hold alert until corroboration; show retry. |
| Camera denied/no decode | Explain grant/retry or better light/angle; never call no-decode “tamper”. |
| Network timeout/429 | Local result stays visible; backend enrichment labeled unavailable with retry. |
| Shared personal photo | Review before scan, discard after processing, and no thumbnail/history persistence. |
| Unsupported language | “Is bhasha ko abhi poori tarah check nahi kar sakte”; `Unable to Verify` while static URL signals may be shown. |

## Polish and accessibility acceptance

No clipped content at 320 dp or 200% font scale; keyboard and IME do not hide actions; TalkBack announces risk **and** confidence before evidence; camera and mic states have clear semantic labels; app survives rotation/process recreation without resurrecting raw call content; Back and Stop always end a session; no spinner without timeout or explanation; permission is requested at point of use and revocation is handled. Capture real-device screenshots for light/dark, Hinglish/English, large text, offline, no camera and warning states. Existing `apps/web` copy/design supplied naming and restraint only; no website file is changed.
