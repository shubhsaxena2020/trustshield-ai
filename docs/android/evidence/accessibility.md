# Accessibility evidence — Phase 3 (P3-18, issue #97)

Measured on the integrated `android-finals` build (`8e31f50`, debug APK installed on emulator
`emulator-5554`) on 26 Oct 2026. This file separates what was **measured here**, what was **read
from source**, and what **remains unverified**. Nothing below is a claim about a physical phone.

Companion files: `device-run.md` (journeys, device identity, defect log),
`p3-18-screen-transcript.txt` (every visible string, screen by screen), `accessibility_audit.py`
(the static audit script, re-runnable), and `p3-18-*.png` (screenshots).

## 1. Method

1. **Static audit** — `accessibility_audit.py` counts the accessibility affordances each screen
   declares in source (`contentDescription`, `heading()`, `semantics { }`, `TsTokens.MinTouchTarget`,
   button types, fixed `.height(<48)dp`, `maxLines = 1`, `verticalScroll`) and flags user-visible
   strings containing development wording.
2. **Semantics at runtime** — the `Phase3JourneysTest` suite drives the real screens through
   Compose semantics (text, content descriptions, toggle state) and asserts on the merged semantics
   tree. Passing journeys are therefore evidence that the labels below are really exposed.
3. **Layout at scale** — the system font scale was set to 1.3 and the app was cold-started; the
   accessibility tree and a screenshot were captured (`p3-18-scan-font13-english.png`).
4. **Device dump** — `wm size` / `wm density` give the real viewport used for the capture.

## 2. Static audit (measured)

Command, from `apps/android/app/src/main/java/ai/trustshield/android`:

```
python docs/android/evidence/accessibility_audit.py
```

| screen | lines | contentDescription | heading() | semantics{} | MinTouchTarget | buttons/toggles | fixed .height(<48dp) | maxLines=1 |
|---|---|---|---|---|---|---|---|---|
| Home / Scan (`ui/home/ScanScreen.kt`) | 807 | 8 | 3 | 9 | 6 | 5 | 0 | 1 |
| Result (`ui/result/ResultScreen.kt`) | 504 | 5 | 1 | 3 | 4 | 4 | 0 | 0 |
| Call Shield (`ui/call/CallShieldScreen.kt`) | 867 | 2 | 0 | 0 | 13 | 12 | 1 | 0 |
| QR Guard (`ui/qr/QrGuardScreen.kt`) | 647 | 2 | 3 | 5 | 10 | 11 | 0 | 0 |
| Settings (`ui/settings/SettingsScreen.kt`) | 430 | 2 | 0 | 0 | 7 | 5 | 0 | 0 |
| **totals** | | **19** | **7** | **17** | **40** | **37** | **1** | **1** |

* The single `.height(10.dp)` is the four-segment risk meter in `CallShieldScreen.kt:425` — a
  decorative graphic with no click action, not a touch target. **No interactive element below 48 dp
  was found by the audit.**
* The one `maxLines = 1` is a single-line label, not body copy.
* `Placeholders.kt` (which shipped the Phase 2 “Yeh screen abhi ban rahi hai …” strings, five of
  them) is **gone** from this tree; the audit reports it as absent and finds no development wording
  in any shipped string.

## 3. What the screen reader meets (measured via semantics)

* Every screen has a heading (`heading()` on 7 nodes) so TalkBack users can jump by heading:
  Scan title, QR Guard title + expected-merchant panel, Result title.
* Actions carry explicit descriptions where the visible label is not the whole story — e.g.
  “Clipboard se message paste karein”, “Message hata kar field khaali karein”,
  “Message ko isi device par check karein, internet ke bina”, “Bhasha aur sensitivity ki settings
  kholein”, “Example bharein: SBI KYC”.
* `liveRegion` is set on 2 nodes (risk/alert updates announce themselves instead of requiring a
  re-scan of the screen).
* Risk state is never colour-only: the Result screen renders a tone **word** (“Zyada khatra”), a
  glyph (`!!`), and the explanatory sentence; QR Guard renders a glyph plus label plus text for
  camera, mismatch and occlusion states. This is read from source and visible in
  `p3-18-result-light-hinglish.png` and `p3-18-qr-simulated.png`.
* Confidence is announced as its own sentence ("Bharosa: Zyada bharosa. Saare dikhaye gaye quotes
  source text se match hue.") and is a different node from the risk band — the separation the
  spec requires is also an accessibility property, because a screen reader hears both.

## 4. Font scale 1.3 (measured)

* `settings put system font_scale 1.3`, cold start, capture:
  `p3-18-scan-font13-english.png`. The accessibility tree still exposes 32 strings on the Home
  screen (33 at 1.0), i.e. **no copy disappeared**; the two strings that scroll out of the tree at
  1.3 are the Call Shield / QR Guard entry rows, which are reachable by scrolling.
* Result screen at 1.3 is **not** claimed here — see defect D1 in `device-run.md`: the Result screen
  renders empty after a configuration change, so the scale check on that screen could not be
  completed. This is an honest gap, not a pass.

## 5. Touch targets and viewport

* Emulator viewport: `wm size = 1080x2400`, `wm density = 420` → **411 dp wide**, i.e. a normal
  phone width. The 320 dp narrow-phone case in `QUALITY_GATES` gate 6 was **not** exercised.
* 40 `MinTouchTarget` (48 dp) moduli across the five screens; primary actions, language chips,
  example chips, demo controls and the settings rows all use it.

## 6. Contrast (read from source, not re-measured)

`ui/theme/Color.kt` documents the measured ratios of the shipping tokens: risk green `#16845B`
4.68:1 on white, risk red `#C73642` 5.21:1, amber `#B86B00` 4.09:1 (graphics only on light, with
`RiskAmberTextLight` 6.17:1 for text), accent `#2F6BFF` 4.27:1 (graphics) / `AccentTextLight`
6.51:1 (text), and dark counterparts at 8.02:1 … 10.25:1. **These numbers were not re-measured in
this package**; they are the values the theme's own audit recorded, and the amber/accent split
exists precisely because the spec hexes miss 4.5:1 for normal text on light.

## 7. Honest gaps

| gap | status |
|---|---|
| Manual TalkBack pass (spoken traversal, focus order, custom actions) | **not performed.** Semantics are asserted programmatically; no human or spoken-output verification was done, and none is claimed. |
| 320 dp width and 200 % font scale (`QUALITY_GATES` gate 6 wording) | **not tested.** Only 411 dp / 1.3x was run. |
| Result screen at large font | **blocked by defect D1** (blank screen after recreation). |
| Reduced-motion behaviour (Settings toggle → animation scale) | toggle exists and is persisted (`settingsJourney`); the visual effect was **not** measured. |
| Haptics | opt-in toggle verified persisted; the vibration itself is **unmeasurable on the emulator**. |
| Call Shield and Settings expose only 2 `contentDescription` sites each | most of their controls are plain text buttons/switches with visible labels (acceptable), but a TalkBack pass should confirm the risk meter and the five stage chips are announced usefully. |
| Landscape / tablet layout | **not tested.** |


## 8. Current result-recreation and theme status (2026-10-26)

The D1 rotation/configuration defect described above was fixed in PR #122 (`f026b29`). The dedicated Result/Settings recreation instrumentation passed 2/2, and the re-enabled Result recreation journey passed as part of the 9/9 connected P3-18 suite. This does not verify process-death recovery of the result: by design the in-memory verdict is discarded and the user returns Home, so raw content is never persisted.

PR #125 (`f23a870`) now paints the root window using the selected app theme and sets status/navigation icon appearance from that theme. The light and dark device captures are `p3-125-settings-light-window-bars.png` and `p3-125-settings-dark-window-bars.png`.

Still unverified: a manual spoken TalkBack pass, 320 dp width, 200% font scale, reduced-motion visual behavior, physical-phone behavior, and landscape/tablet layout. Issue #97 remains open for these gaps; no accessibility-complete claim is made.
