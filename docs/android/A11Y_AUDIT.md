# Accessibility audit — Wave 1 (issue #97)

**Status: source audit + test plan. NOT a spoken-TalkBack pass, NOT a device run.**
No Gradle, no `adb`, no emulator and no screenshot were used to produce this file. Every
utterance below is *derived from the Compose source*, not recorded from a device. Section 7 states
exactly what that leaves unproven.

| | |
|---|---|
| Commit audited | `8c3d24b` (`Merge pull request #160 … P6-U1-language`), branch `android-finals` |
| Worktree | `C:\Users\shubh\trustshield-android-p4\agent3\wt-laneC-20261027-1930` |
| Date | 2026-10-27 |
| Method | read-only source read + `git show 8c3d24b:<path>` |
| Kotlin written by this lane | **none** |
| Evidence directory | `docs/android/evidence/a11y/` (protocol: `evidence/a11y/README.md`) |
| Issue | #97 — remains **open** after this document |

---

## 1. Method and scope

**Read, not run.** I read these files at `8c3d24b` and mapped every `semantics { }` block,
`contentDescription`, `heading()`, `liveRegion`, `stateDescription`, `Role`, size modifier and
string literal that a screen reader or voice-control user would meet:

```
ui/home/ScanScreen.kt            825 lines
ui/result/ResultScreen.kt        529
ui/call/CallShieldScreen.kt      878
ui/qr/QrGuardScreen.kt           734
ui/settings/SettingsScreen.kt    506
ui/navigation/AppScreens.kt      807   (app chrome: destination row, demo experience)
ui/theme/Color.kt                263   (TsTokens: MinTouchTarget, spacing, motion)
ui/theme/RiskToneSemantics.kt    115   (ToneCopy + the non-colour tone contract)
ui/theme/Type.kt                 103   (14/16/20/28 sp scale)
```

Out of scope, and therefore not audited: `ui/call/CallDemo*` fixtures beyond the chrome in
`AppScreens.kt`, `ui/qr/QrGuardHost.kt` (camera wiring), `ui/home/*Scan*` OCR plumbing, the
Android Manifest, and anything on `apps/web`.

**What a defect row means.** Each row is a real call site, not a copied guideline: `file:line` points
at the exact modifier or literal at `8c3d24b`. "What TalkBack says today" is what the semantics tree
*should* produce given that code — a prediction from the API contract, not a recording.

**Severity.**

| level | meaning |
|---|---|
| HIGH | a screen-reader user cannot complete the task, or a critical alert arrives silently |
| MEDIUM | the task is possible but the announcement is wrong, duplicated or mis-leading, or the control's purpose is unknowable |
| LOW | polish, voice-control, or wording hygiene |

**Status vocabulary.**

| value | meaning |
|---|---|
| `OPEN` | defect present at `8c3d24b` **and** still present in the working tree when this file was written |
| `FIXED-BY-WAVE-1` | defect present at `8c3d24b`, and the fix is visible in the **uncommitted** working tree of this worktree at the time of writing |

`FIXED-BY-WAVE-1` is a source observation only. Nothing in this lane compiled, ran or heard those
fixes; they are another child's Kotlin, mid-flight in the same worktree. The lane lead must re-derive
this column against the merged tree before #97 closes, and only a screenshot or a recorded
spoken-TalkBack transcript (section 8) may promote a row to PASS.

**Counts at `8c3d24b`** (grep, per file):

| screen | `heading()` | `liveRegion` | `contentDescription` | `mergeDescendants` |
|---|---|---|---|---|
| Home/Scan | 3 | 2 | 10 | 4 |
| Result | 3 | **0** | 5 | 4 |
| Call Shield | **0** | **0** | 2 | 3 |
| QR Guard | 3 | **0** | 3 | **0** |
| Settings | **0** | **0** | 2 | 2 |
| App chrome | 0 | 0 | 4 | 0 |

Two whole destinations (Call Shield, Settings) had no heading at all, and three of the five
destinations had no live region — including both screens whose job is to interrupt the user.

---

## 2. Per-screen defects

### 2.1 Home / Scan — `ui/home/ScanScreen.kt`

| id | file:line @8c3d24b | what TalkBack says today | what it should say | sev | status |
|---|---|---|---|---|---|
| SC-1 | `ScanScreen.kt:508-512` | "TrustShield AI, image" as the very first stop, then "Scan karein" | nothing — the logo is decorative and the title immediately follows; a brand-name image stop costs a swipe and says nothing | LOW | OPEN |
| SC-2 | `ScanScreen.kt:543-550` | counter is a `Text` with `contentDescription = "Characters: " + "1,234 / 20,000"`; the slash is not spoken, so it is heard as two unrelated numbers, and depending on merge it may also be appended to the field's own announcement | "Characters used: 1,234 of 20,000" — one spoken sentence, either inside the field or as its own stop, never both | LOW | OPEN |
| SC-3 | `ScanScreen.kt:551-557` | pasting >20,000 characters silently drops the tail; the truncation notice appears with no `liveRegion` and no focus move | the notice is announced politely the moment it appears: "Message bahut lamba hai - sirf pehle 20,000 characters check honge"; a user must never get a verdict on a body of text they did not know was cut | MEDIUM | OPEN |
| SC-4 | `ScanScreen.kt:572, 582, 655, 680, 708` | `contentDescription` **replaces** the visible label: Clear announces "Message hata kar field khaali karein" (the word "Clear" is never spoken); Check announces "Message ko isi device par check karein, internet ke bina" (not "Check karein (offline)"); Settings announces "Bhasha aur sensitivity ki settings kholein" | the visible label must be contained in the accessible name (WCAG 2.5.3 Label in Name): "Clear. Message hata kar field khaali karein", "Check karein (offline). Verdict isi phone par banta hai", so a voice-control user who reads the button aloud can activate it | MEDIUM | OPEN |
| SC-5 | `ScanScreen.kt:618-639` | four example chips in a `horizontalScroll` row; chips that are off-screen are still in the tree, so traversal enters a horizontally scrolling container mid-screen | either the row wraps at narrow widths, or the row exposes the chips as one group with a "scrolls sideways" hint — a screen-reader user otherwise believes the last examples do not exist | LOW | OPEN |
| SC-6 | `ScanScreen.kt:643-670` | a disabled `Button` in Material 3 carries no click action, so TalkBack reaches "Check karein (offline), disabled" and never says why | "disabled" is accompanied by the reason: "Check abhi nahi chal sakta: Khaali message check nahi ho sakta…" | MEDIUM | **FIXED-BY-WAVE-1** (`stateDescription` with availability + blocked reason) |
| SC-7 | `ScanScreen.kt:745-755` | the error banner is a polite live region on every composition, including a blank message | armed only when it has something to say | LOW | **FIXED-BY-WAVE-1** (conditional `announce`) |
| SC-8 | `ScanScreen.kt:527-560` | the message field is `minLines = 6`; at 200 % font scale six lines of body text plus label and supporting text consume roughly half of a 320 dp × 640 dp viewport, pushing "Check karein (offline)" below the fold (arithmetic estimate — **not measured**) | the primary action stays visible-or-obviously-reachable at 1.5× and 2.0×; the field shrinks to 3–4 lines instead of holding six | MEDIUM | OPEN |

### 2.2 Result — `ui/result/ResultScreen.kt`

| id | file:line @8c3d24b | what TalkBack says today | what it should say | sev | status |
|---|---|---|---|---|---|
| RS-1 | `ResultScreen.kt:138-146`, `166-171`, `234-240` | the panel title carries `contentDescription = screenDescription(...)` — risk band, confidence **and** score in one breath — and then `RiskHeader` announces the risk again and `ConfidenceBadgeView` announces the confidence again: the first three stops are two announcements repeated | one summary stop, then evidence; repetition of the two most important facts is what makes a 3-card result take 20+ swipes | MEDIUM | **FIXED-BY-WAVE-1** (summary moved to the merged verdict block; bar title demoted to decoration) |
| RS-2 | `ResultScreen.kt:138-146` vs `522-527` | the screen title is not a `heading()`; only the section titles are, so heading navigation starts at "Evidence" and the title is skipped | the verdict block is the first heading — a screen-reader user jumps verdict → evidence → actions | MEDIUM | **FIXED-BY-WAVE-1** (`heading()` on the merged `RiskHeader`) |
| RS-3 | `ResultScreen.kt:329-372, 381-408` | an evidence card is **7 focus stops** (glyph, severity label, module, title, detail, quote caption, quote text); three cards = 21 swipes, and the quote arrives with no statement of which signal it supports | one stop per card: severity → category → title → detail → "Exact quote: …" (with the shortened marker) | MEDIUM | **FIXED-BY-WAVE-1** (`mergeDescendants` + `evidenceCardDescription`) |
| RS-4 | `ResultScreen.kt:457-462` | each limitation reads "•" as its own stop, then the sentence | no bullet node; the bullet is decoration (`clearAndSetSemantics {}`) or is prepended to the text | LOW | OPEN |
| RS-5 | `ResultScreen.kt:132-135` | back button label is the literal `"\u2039 Peeche"` — TalkBack reads the single-left-angle-quote glyph, or says nothing and leaves a bare "Peeche" | "Peeche, button" with no typographic glyph in the accessible name | LOW | OPEN |
| RS-6 | `ResultScreen.kt:147-151` | the language toggle's visible text ("English" / "Hinglish") is replaced by `contentDescription = "Bhasha badlein"`, so the control never says which language it will switch **to**, and a voice-control user saying the visible word "English" does not match | "Bhasha badlein: English mein badlein" (visible label first), so the name and the visible text agree | MEDIUM | OPEN |
| RS-7 | `ResultScreen.kt:68-75` vs `197-203` | the file doc and issue #115 both claim the score is "only ever *spoken*, never drawn", but `RiskHeader` draws `Text("Score: N/100")`; the drawn figure is then swallowed by the merged block, so a reviewer reading the doc cannot reconcile the two channels | one honest statement: either the figure is drawn and the doc says so, or it is not drawn | LOW | OPEN (doc wording corrected in the working tree; the drawn `Text` remains) |

### 2.3 Call Shield — `ui/call/CallShieldScreen.kt`

| id | file:line @8c3d24b | what TalkBack says today | what it should say | sev | status |
|---|---|---|---|---|---|
| CS-1 | `CallShieldScreen.kt:199-204` | the sticky warning — the one element that must interrupt — has **no `liveRegion`** and is never focused. A digital-arrest pattern that lights up mid-call changes the screen in silence; the user only finds it if they happen to be swiping | the banner is a **polite-then-assertive** live region and receives accessibility focus when the tone escalates (once per changed high-risk state, never per ASR chunk — the existing single-shot haptic rule is the precedent) | **HIGH** | OPEN |
| CS-2 | `CallShieldScreen.kt:199-222` | the banner's merged `contentDescription = body + " " + noAccusation` **drops the tone label** that is drawn inside it ("Zyada khatra"), so the alert text is heard without its risk word | "Zyada khatra. <warning> <no-accusation>" — risk first, exactly as `RiskToneSemantics.kt:28` requires for every composite | **HIGH** | OPEN |
| CS-3 | `CallShieldScreen.kt` (0 occurrences) | no `heading()` anywhere on the destination: heading navigation does nothing, and the screen title "Call Shield" is an ordinary text stop | title is a heading; Risk, Stages, Degraded state are sub-headings, so a user can jump straight to the stage list | MEDIUM | OPEN (0 headings still) |
| CS-4 | `CallShieldScreen.kt:395-401` vs `447-460` | the risk meter merges risk **and** confidence into one `contentDescription` and thereby swallows both explainer lines (`confidenceExplainer`, `riskExplainer`) — the two sentences that say what the band means are never spoken by any node | risk and confidence are two stops (the file's own contract at line 377 says "two separate readings, never merged"), each with its explainer readable | MEDIUM | OPEN |
| CS-5 | `CallShieldScreen.kt:506-511` + `532` | stage chip announces the status twice — once from the child text `label + " — " + statusText` and again from `stateDescription = statusText` | one sentence per chip: stage → found/not yet → the heard line → the explanation | MEDIUM | **FIXED-BY-WAVE-1** (explicit `contentDescription = spoken`) |
| CS-6 | `CallShieldScreen.kt:317-328` | the timer is two unrelated stops ("Samay", then "0:12") and the figure is re-read on every recomposition while focused, once per second | one merged, *polled* node: phase, elapsed in whole minutes, risk, confidence — read at focus time, never pushed | LOW | **FIXED-BY-WAVE-1** (`SessionStatusSummary`) |
| CS-7 | `CallShieldScreen.kt:786-800` | `GlyphBadge` is a fixed `.size(48.dp)` box around a 20–28 sp glyph; at 200 % the glyph clips, and in the sticky warning that clipped box is the **only** icon channel for the tone | `sizeIn(minWidth = minHeight = 48.dp)` so the icon channel survives large text | MEDIUM | **FIXED-BY-WAVE-1** (`sizeIn`) |
| CS-8 | `CallShieldScreen.kt:729-758` (base) | the Stop bar sat **last** in the layout, below a scrollable body — the fastest way out of a live session was the furthest control | Stop is the first control after the title chrome, outside the scroll, with an announced label that says what stops | MEDIUM | **FIXED-BY-WAVE-1** (moved above the warning; `role = Role.Button` + spoken label) |

### 2.4 QR Guard — `ui/qr/QrGuardScreen.kt`

No Wave-1 change had landed in this file when this was written (734 lines at both `8c3d24b` and in
the working tree), so every row is `OPEN`.

| id | file:line @8c3d24b | what TalkBack says today | what it should say | sev | status |
|---|---|---|---|---|---|
| QR-1 | `QrGuardScreen.kt:267-330` | `SignalBanner` — the mismatch / "stop payment now" state — has no `liveRegion`. A valid mismatch that appears while the user is holding the phone up to a QR **makes no sound** | polite live region on tone change, once per changed signal, with the masked handles that are already in the banner | **HIGH** | OPEN |
| QR-2 | `QrGuardScreen.kt:517-574` | the re-register control carries `role = Role.Button` but **no click action** — it listens through `pointerInput`. TalkBack announces "Re-register. 5 second dabaye rakhein, button", double-tap does nothing, and double-tap-and-hold does not deliver the raw down/up pair the `awaitEachGesture` block waits for. The file comment at 512-514 claims the opposite; that claim is unverified and almost certainly wrong | a semantics `onClick` (or custom action) that opens the PIN gate, since the PIN — not the hold — is the secret; the hold stays the sighted shortcut | **HIGH** | OPEN |
| QR-3 | `QrGuardScreen.kt:168-183` | `IconBadge` is a fixed `.size(32.dp)` circle around a 20 sp glyph — at 200 % it clips — and it is not marked decorative, so the camera panel opens with a stop that reads "●" (or "black circle") / "✕" / "…" | `clearAndSetSemantics {}` on the badge, and a minimum rather than fixed size so the glyph never clips | MEDIUM | OPEN |
| QR-4 | `QrGuardScreen.kt:211-222` | the two language chips expose **no selected state**: the only difference is a filled vs outlined `Button`, so TalkBack hears "Hinglish, button" / "English, button" with no indication which is active; the label is also always `label.hinglish` | `selectable(role = Role.RadioButton)` in a `selectableGroup`, and the label resolved for the current language | MEDIUM | OPEN |
| QR-5 | `QrGuardScreen.kt:663-665` | `val verdict = if (observed.matched) "match" else "mismatch"` — an English literal announced inside the counter panel in Hinglish and Hindi modes | a `ToneCopy` pair from the copy layer, announced in the current language | LOW | OPEN |
| QR-6 | `QrGuardScreen.kt:607-610` | the PIN field sets `contentDescription` on the `OutlinedTextField`, replacing the field's own semantics; the masked-entry feedback ("3 characters entered") is likely lost | the label stays a `label`, not a `contentDescription`; if the digit count must be hidden, say so in the label instead of overriding the field | MEDIUM | OPEN (verify on device — see §7) |
| QR-7 | `QrGuardScreen.kt:674-691` | `MandatoryConfirmationBanner` sets `contentDescription` on a `Surface` that also renders the identical sentence as a child `Text` — the same sentence is two consecutive stops | one node (`mergeDescendants`, or `clearAndSetSemantics` on the child) | LOW | OPEN |
| QR-8 | `QrGuardScreen.kt:248-263, 276, 645` | heading navigation is title → expected merchant → re-register; the camera panel and the signal panel — the two state panels that matter — are not headings | camera and signal are headings (`heading()`), so a user can jump to the live state instead of swiping past setup copy | MEDIUM | OPEN |

### 2.5 Settings — `ui/settings/SettingsScreen.kt`

| id | file:line @8c3d24b | what TalkBack says today | what it should say | sev | status |
|---|---|---|---|---|---|
| ST-1 | `SettingsScreen.kt:88-92`, `319-323` | **zero** headings on the screen: neither the title nor any of the six section titles is a `heading()`, so heading navigation is dead and 11 radio rows must be swiped through linearly | title is the top-level heading, each section title a sub-heading | MEDIUM | **FIXED-BY-WAVE-1** (title + section titles) |
| ST-2 | `SettingsScreen.kt:223-230, 433-461` | after "Reset" is confirmed, the confirmation banner is appended at the **very bottom**, below the About section, with no live region and no focus move — TalkBack announces nothing and the confirmation is off-screen | announced politely, or the dialog itself carries the outcome before it closes | MEDIUM | OPEN |
| ST-3 | `SettingsScreen.kt:259-295` | the device-check panel hardcodes English labels ("Device check", "ABI", "Speech recognizer", "Camera permission", "Microphone permission") in every language, so a Hindi user hears English mid-screen | every visible string through the copy layer | MEDIUM | **FIXED-BY-WAVE-1** (`SettingsA11yCopy`) |
| ST-4 | `SettingsScreen.kt:298-306` | each device-check line is two unrelated stops ("Android" … "14 (API 34)"); six lines = twelve swipes with no pairing | one merged stop per line: "Android. 14 (API 34)" | LOW | **FIXED-BY-WAVE-1** (merged `Column`, stacked) |
| ST-5 | `SettingsScreen.kt:199-210` | the Reset button is disabled at defaults and says nothing about why (the only disabled control on the screen with no explanation) | "already at defaults" as a state description, matching the pattern the Check button now uses | LOW | OPEN |
| ST-6 | `SettingsScreen.kt:337-377, 383-420` | radio/switch rows set `contentDescription` *and* rely on `.selectable()` / `.toggleable()` for role and state on the same merged node; the description then duplicates the child texts it replaced | correct only if the device announces "…, selected, radio button" / "…, on, switch"; **unverified** — see §7, this is the first thing the TalkBack pass must confirm | LOW | OPEN (verify) |

### 2.6 App chrome — `ui/navigation/AppScreens.kt`

| id | file:line @8c3d24b | what TalkBack says today | what it should say | sev | status |
|---|---|---|---|---|---|
| CH-1 | `ScanScreen.kt:704-711` + `AppScreens.kt:352-362` | Settings is a text button at the **end of a two-screen scroll**, while Call Shield and QR Guard are in a fixed row below the scroll area. Same three destinations, two different navigation models; the fixed row carries no role and no "you are here" state | one place for destinations, with `Role.Tab`/selected state, at a stable position | MEDIUM | OPEN |
| CH-2 | `AppScreens.kt:346-351` | after an image is picked and OCR fails, the error is a plain `Text` pair below the scan column, with no live region — the failure is silent and may be below the fold | polite live region plus "Dhyan dein" label, as the in-field `ErrorBanner` already does | MEDIUM | OPEN |
| CH-3 | `AppScreens.kt:580-622` | the demo card sets `contentDescription = "CRITICAL risk, 75 out of 100"` on a `Surface` that does **not** merge, so the reader stops on the description and then on the children — the score and confidence are heard twice, in two different wordings | one merged node per card, score and confidence once | MEDIUM | OPEN |
| CH-4 | `AppScreens.kt:625-633`, `651-661` | the demo alert card and every demo stage chip also set `contentDescription` on non-merging surfaces whose children render the same string — same sentence twice | `mergeDescendants` on both | LOW | OPEN |
| CH-5 | `AppScreens.kt:479-488` | the Call Shield destination offers the staged demo twice with two different labels (in-screen `RecognizerCard` "Staged demo" and a separate outlined button below the screen), both reachable | one entry point per capability | LOW | OPEN |

**Totals:** 8 + 7 + 8 + 8 + 6 + 5 = **42 rows**: 4 HIGH, 23 MEDIUM, 15 LOW — of which
**12 are FIXED-BY-WAVE-1** (source only) and **30 remain OPEN**, including all 4 HIGH rows.

---

## 3. What the audit says about the five states most likely to hurt a real user

1. **A critical alert arrives in silence.** Call Shield (`CS-1`) and QR Guard (`QR-1`) are the two
   screens whose entire purpose is to interrupt, and neither has a live region. This is the single
   biggest accessibility defect in the app and it is *invisible* to a sighted reviewer.
2. **The re-register gate is probably unusable with TalkBack on** (`QR-2`). A `role = Button` with no
   click action is announced as actionable and is not.
3. **Large text is untested at the extreme** (`SC-8`, `QR-3`, `CS-7`): one fixed 32 dp glyph box and
   one 6-line minimum field are the known pressure points; nothing has been rendered at 320 dp / 200 %.
4. **Voice control cannot say the visible label** on five buttons (`SC-4`, `RS-6`) because
   `contentDescription` replaced the text.
5. **Two destinations had no heading at all** (`CS-3`, `ST-1`), which is why the spoken scripts in §6
   are worth more than a screenshot: a screenshot cannot show this.

---

## 4. Emulator matrix to be executed

Nothing in this section has been run. Every cell starts **not run**. Protocol, lock, file naming and
the PASS rule live in `docs/android/evidence/a11y/README.md`.

### 4.1 Device and build under test

| field | value |
|---|---|
| AVD | `trustshield-api36` (Android 16, API 36, `google_apis`, x86_64) |
| default viewport | `wm size 1080x2400`, `wm density 420` → **411 dp** wide |
| package | `ai.trustshield.android` |
| serial | `emulator-5554` — **always pass `-s emulator-5554`; never set `ANDROID_SERIAL` to, or run any command against, the owner's phone `63b0de52`** |

### 4.2 The variables

| variable | values | why |
|---|---|---|
| font scale | `1.0`, `1.3`, `1.5`, `2.0` | gate 6 names 1.3 and 2.0; 1.5 is the interpolation where buttons start to wrap |
| theme | light, dark (app setting) — plus `SYSTEM` resolved both ways | app owns the theme, so the toggle is the test, not the system setting |
| width | `320 dp` (narrow phone), `360 dp` (reference) | 320 dp is the gate-6 width and the one never exercised |
| orientation | portrait, landscape | the manifest locks neither, and no `configChanges` is declared, so rotation recreates the activity — result retention (#122) must survive it |
| TalkBack | on | the only way to test the spoken order in §6 |
| reduced motion | `animator_duration_scale 0` (+ app "reduced motion" on) | `SystemMotion` reads the platform scale **once at launch**, so each change needs a cold start |

### 4.3 Exact commands and how to restore each

Run all of them from a shell where `adb` is on `PATH`; prefix everything with `-s emulator-5554`.

```bash
# ---- before anything -------------------------------------------------------
adb -s emulator-5554 devices -l                          # exactly one emulator, no phone
adb -s emulator-5554 shell getprop ro.boot.qemu.avd_name  # expect trustshield-api36
adb -s emulator-5554 shell wm size                        # expect Physical size: 1080x2400
adb -s emulator-5554 shell wm density                     # expect Physical density: 420
adb -s emulator-5554 shell settings get system font_scale  # expect 1.0
adb -s emulator-5554 shell settings get global animator_duration_scale  # expect 1.0

# ---- font scale ------------------------------------------------------------
adb -s emulator-5554 shell settings put system font_scale 1.3
#   applies live to the running activity; force a clean read:
adb -s emulator-5554 shell am force-stop ai.trustshield.android
adb -s emulator-5554 shell am start -n ai.trustshield.android/.MainActivity
# RESTORE (default is 1.0):
adb -s emulator-5554 shell settings put system font_scale 1.0

# ---- width (320 dp / 360 dp at 420 dpi: dp = px / 2.625) --------------------
adb -s emulator-5554 shell wm size 840x2400    # 320 dp
adb -s emulator-5554 shell wm size 945x2400    # 360 dp
adb -s emulator-5554 shell wm size             # verify "Override size: 840x2400"
# RESTORE (back to the AVD's own size):
adb -s emulator-5554 shell wm size reset
# note: wm size does NOT change wm density — do not "fix" it by changing density,
# because that changes the dp width you just set.

# ---- reduced motion --------------------------------------------------------
adb -s emulator-5554 shell settings put global animator_duration_scale 0
adb -s emulator-5554 shell settings put global transition_animation_scale 0
adb -s emulator-5554 shell settings put global window_animation_scale 0
adb -s emulator-5554 shell am force-stop ai.trustshield.android   # SystemMotion reads it at launch
adb -s emulator-5554 shell am start -n ai.trustshield.android/.MainActivity
# RESTORE (all three to 1):
adb -s emulator-5554 shell settings put global animator_duration_scale 1
adb -s emulator-5554 shell settings put global transition_animation_scale 1
adb -s emulator-5554 shell settings put global window_animation_scale 1

# ---- orientation -----------------------------------------------------------
adb -s emulator-5554 shell settings put system accelerometer_rotation 0
adb -s emulator-5554 shell settings put system user_rotation 1    # 0 = portrait, 1 = landscape
# RESTORE (give the sensor back):
adb -s emulator-5554 shell settings put system accelerometer_rotation 1

# ---- TalkBack --------------------------------------------------------------
adb -s emulator-5554 shell pm list packages | grep -i talkback   # is it installed at all?
adb -s emulator-5554 shell settings put secure enabled_accessibility_services \
  com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
adb -s emulator-5554 shell settings put secure accessibility_enabled 1
adb -s emulator-5554 shell settings get secure enabled_accessibility_services   # verify it took
# RESTORE:
adb -s emulator-5554 shell settings put secure accessibility_enabled 0
adb -s emulator-5554 shell settings delete secure enabled_accessibility_services
```

Two honesty notes on the TalkBack command: the service component name above is the stock one, but a
`google_apis` (not `playstore`) image may not ship TalkBack at all — check with `pm list packages`
first, and if it is absent, installing Accessibility Suite is an **owner action**, not something this
lane may claim. And `settings put` succeeding is not proof TalkBack speaks; the proof is a listener
hearing it.

### 4.4 Session plan

| session | font | theme | width | orientation | TalkBack | reduced motion | purpose |
|---|---|---|---|---|---|---|---|
| M1 baseline | 1.0 | light + dark | 360 dp | portrait | off | off | reference screenshots for all five screens |
| M2 large text | 1.3, 1.5 | light + dark | 320 dp | portrait | off | off | gate-6 wording at the intermediate steps |
| M3 extreme | 2.0 | light + dark | 320 dp | portrait **and** landscape | off | off | clipping, below-the-fold actions, rotation |
| M4 spoken | 1.0 | light + dark | 360 dp | portrait | **on** | off | the scripts in §6, transcribed by a human |
| M5 motion | 1.0 | light | 360 dp | portrait | on and off | **on** | no transition on a risk/stage change; nothing pulses |
| M6 rotation | 1.0 | light | 360 dp | portrait → landscape → portrait | off | off | result retained, session state retained, nothing resurrected |

### 4.5 Required capture cells

`not run` in every row until a screenshot with the exact §README filename exists.

| screen | 1.0/360/light | 1.0/360/dark | 1.3/320/light | 1.5/320/light | 2.0/320/light | 2.0/320/dark | 1.0/360/landscape | 2.0/320/landscape |
|---|---|---|---|---|---|---|---|---|
| Home / Scan | not run | not run | not run | not run | not run | not run | not run | not run |
| Result (scam = HIGH) | not run | not run | not run | not run | not run | not run | not run | not run |
| Result (legit = LOW) | not run | not run | not run | not run | not run | not run | not run | not run |
| Call Shield (consent gate) | not run | not run | not run | not run | not run | not run | not run | not run |
| Call Shield (CRITICAL) | not run | not run | not run | not run | not run | not run | not run | not run |
| QR Guard (setup) | not run | not run | not run | not run | not run | not run | not run | not run |
| QR Guard (mismatch) | not run | not run | not run | not run | not run | not run | not run | not run |
| Settings | not run | not run | not run | not run | not run | not run | not run | not run |

**40 cells, 0 captured.** A row may only leave `not run` with a file in
`docs/android/evidence/a11y/` or a transcript named `talkback-<screen>-<date>.txt`.

### 4.6 Per-cell pass criteria

A cell passes only if all of these are true, judged from the screenshot:

- no text is clipped, ellipsised, or overlapping; no `…` appears anywhere the source does not put one;
- every primary action on the screen is fully visible, or is reachable by one obvious scroll with a
  visible hint that more content follows;
- touch targets ≥ 48 dp in **both** dimensions (the wave-1 `widthIn(min = MinTouchTarget)` additions
  exist precisely because `heightIn` alone let a short label produce a narrow target);
- risk is still distinguishable with colour removed (glyph + word + sentence);
- nothing pulses (two screenshots 1 s apart must be identical apart from the timer).

---

## 5. Spoken-order script — how to use it

The scripts in §6 are the **acceptance reference for this lane**. A reviewer enables TalkBack, opens
the screen in the stated state, and swipes forward from the top, writing down each utterance.
Matching the script = pass. Any extra stop, missing stop, or wrong wording maps to a defect id in §2,
so a mismatch is *evidence about a specific row*, not a vague failure.

Wording conventions:

- `«»` marks what TalkBack adds itself: `, button`, `, selected`, `, disabled`, `, edit box`.
- Utterances are **predicted from source**. Where a row is `OPEN`, the script gives the **target**
  wording and the row's defect id, so a disagreeing recording is attributed correctly.
- Hinglish is the default language. English and Hindi differ only in the copy strings; the order is
  identical.
- `≈` marks a stop whose exact wording depends on runtime state (scores, quotes, ephemeral counters).

---

## 6. Spoken-order scripts per screen

### 6.1 Home / Scan — empty state, Hinglish

| # | utterance | note |
|---|---|---|
| 1 | "TrustShield AI, image" | **SC-1** — should be silent |
| 2 | "Scan karein" | heading |
| 3 | "Koi bhi suspicious message, link ya SMS text yahan paste karein. Verdict device par hi banta hai." | |
| 4 | "Local rules internet ke bina bhi chalte hain. Online checks abhi available nahi hai, isliye result sirf device par bane rules se aata hai." | |
| 5 | "Message yahan paste karein«, edit box». Poora message jaisa aaya hai waisa hi chipkayein" + supporting "Characters: 0 / 20,000" | **SC-2** — whether the counter lands here or as stop 6 must be recorded; today it is its own `contentDescription` node |
| 6 | "Clipboard se message paste karein«, button»" | **SC-4** — "Paste" is never spoken |
| 7 | "Message hata kar field khaali karein«, button, disabled»" | **SC-4** — "Clear" is never spoken |
| 8 | "Check karne layak text nahi mila. Pehle message paste ya type karein." | |
| 9 | "Examples (nakli sample)" | heading |
| 10 | "Yeh sirf demo ke liye banaye gaye nakli messages hain. Inme kisi ka asli data nahi hai." | |
| 11 | "Example bharein: SBI KYC«, button»" | **SC-5** — row scrolls sideways |
| 12 | "Example bharein: Bijli bill«, button»" | |
| 13 | "Example bharein: Bank credit«, button»" | |
| 14 | "Example bharein: Delivery OTP«, button»" | |
| 15 | "Message ko isi device par check karein, internet ke bina«, button, disabled». Check abhi nahi chal sakta: Khaali message check nahi ho sakta…" | **SC-6** — target wording (wave-1); at 8c3d24b the reason clause is missing |
| 16 | "Verdict isi phone par banta hai — internet ki zarurat nahi." | |
| 17 | "Screenshot chunein; OCR text padh kar wahi local check chalega«, button»" | **SC-4** — visible label "Screenshot se check karein" is not in the name |
| 18 | "Screenshot ka text OCR se nikala jata hai. OCR kabhi galat padh sakta hai, isliye bharosa kam ho sakta hai." | |
| 19 | "Kisi bhi app se Share karke bhejein — message yahan khud aa jayega." | |
| 20 | "Aapka message sirf isi phone par check hota hai. App use save, share ya upload nahi karta." | |
| 21 | "Bhasha aur sensitivity ki settings kholein«, button»" | **SC-4 / CH-1** — bottom of a long scroll |
| 22 | "Call Shield«, button»" | chrome row, outside the scroll |
| 23 | "QR Guard«, button»" | |

**After pasting >20,000 characters**, one extra utterance must arrive *by itself*: "Message bahut
lamba hai - sirf pehle 20,000 characters check honge" (**SC-3** — today nothing is announced).

**After a failed paste**, the error banner must be announced without a swipe: "Dhyan dein. Clipboard
khaali hai ya text copy nahi hua… Type karte hi yeh error hat jayega." (polite live region, works today).

### 6.2 Result — HIGH risk, 3 evidence cards, Hinglish

| # | utterance | note |
|---|---|---|
| 1 | "Peeche«, button»" | **RS-5** — today the "\u2039" glyph is in this label |
| 2 | "Result" | decoration in the target design (no summary) — **RS-1** |
| 3 | "Bhasha badlein«, button»" | **RS-6** — never says which language it switches to |
| 4 | "Zyada khatra — turant rukhein aur verify karein. Zyada bharosa. Risk score 72 me se 100«, heading»" | the single verdict stop: risk → score → confidence, one utterance (**RS-1/RS-2**) |
| 5 | "Bharosa: Zyada bharosa. Saare dikhaye gaye quotes match hue." | confidence as its own stop, after the verdict |
| 6 | "Evidence" | heading |
| 7 | "Zyada. Digital arrest pattern. Pattern check. <detail>. Exact quote: <quote>" | one stop per card (**RS-3**) |
| 8 | "…" | one stop per remaining card, same shape |
| 9 | "Kya aap kar sakte hain" | heading |
| 10 | "<first action label>. <detail>«, button»" | primary action |
| 11 | "<next action label>. <detail>«, button»" | |
| 12 | "Kya yeh check nahi kar sakta" | heading |
| 13 | "•" | **RS-4** — should not exist |
| 14 | "<limitation sentence>" | one per limitation, alternating 13/14 |
| 15 | "Backend: offline" (label + detail) | `BackendNote` |
| 16 | "Itne characters check hue: <N>" | |
| 17 | "…verdict note…" | |
| 18 | "…not proof…" | |

**The acceptance line for this screen:** steps 4 and 5 must both be heard before step 6. Anything
where the quote arrives without "Exact quote:" or without its severity fails **RS-3**.

### 6.3 Call Shield — consent gate → listening → CRITICAL

| # | utterance | note |
|---|---|---|
| 1 | "Call Shield" | **CS-3** — target: heading, so heading navigation works |
| 2 | "…subtitle…" | |
| 3 | "English«, button»" (or "Hinglish") | language toggle shows the *next* language |
| 4 | "Stop — sunna band karein, mic band ho jata hai«, button, disabled»" | **CS-8** — target: Stop is the first control after the chrome |
| 5 | "Sunna shuru karein ke liye pehle consent dein…" (consent title) | consent card, only while `NOT_GIVEN` |
| 6 | "…consent body…" | |
| 7 | "<consent action>«, button»" | |
| 8 | "Mic band hai" / "Sun raha hai" + disclosure + "Backend check abhi nahi hai" | `StatusCard` |
| 9 | "Sun raha hai. Ek minute se kam. Khatra: Kam khatra. Bharosa: …" | **CS-6** — target: one polled summary, whole minutes |
| 10 | "Pause karein«, button»" (or Start/Resume) | |
| 11 | "Khatra" + "Kam khatra. Kam khatra — sirf sanket kam mile. Bharosa: <level>" | **CS-4** — today risk and confidence are one node and the two explainers are never spoken |
| 12 | "…confidence explainer…" | never reached today (**CS-4**) |
| 13 | "…risk explainer…" | never reached today (**CS-4**) |
| 14 | "Yeh stages kyun dekhe jaate hain" | |
| 15 | "Authority. Nahi mila. …explanation…" | **CS-5** — target: one sentence per chip, status once |
| 16 | "…" | ×5 chips |
| 17 | "<footer: clear-transcript hint, stop hint, honesty note>" | three stops |
| 18 | "Stop — sunna band karein, mic band ho jata hai«, button»" | if the target order is not yet merged, Stop appears here instead of at 4 |

**The decisive test on this screen:** start a session with a scripted/scam chunk that flips the tone
to CRITICAL, then **do nothing for 10 seconds**. A listener must hear the warning announce itself:
"Zyada khatra. <warning>. <no-accusation>" (**CS-1/CS-2**). If the room stays silent, both rows fail —
that is the whole point of the lane.

### 6.4 QR Guard — watching, then a valid mismatch

| # | utterance | note |
|---|---|---|
| 1 | "QR Guard" | heading |
| 2 | "…subtitle…" | |
| 3 | "Bhasha" | static label |
| 4 | "Hinglish«, button»" | **QR-4** — no selected state |
| 5 | "English«, button»" | |
| 6 | "●" or "black circle" | **QR-3** — target: silent |
| 7 | "Camera" | |
| 8 | "Camera chal raha hai" (or denied/none/off) | |
| 9 | "<signal label>" | **QR-8** — target: heading |
| 10 | "<signal detail>" | |
| 11 | "<guidance, when present>" | |
| 12 | "Expected merchant" | heading |
| 13 | "<masked upi handle>" | |
| 14 | "<display name, when set>" | |
| 15 | "…printed-name warning…" | |
| 16 | "…masked note…" | |
| 17 | "Scan band karein«, button»" | |
| 18 | "Re-register. 5 second dabaye rakhein«, button»" | **QR-2** — double-tap does nothing today |
| 19 | "Mismatch count: N" | |
| 20 | "QR nahi dikh raha: N s" | |
| 21 | "…occlusion-not-tamper note…" | |
| 22 | "…last observed handle (match/mismatch)…" | **QR-5** — English literal inside Hinglish |
| 23 | "Pakka confirmation sirf aapke bank SMS ya UPI app ki history mein milega…" | must be verbatim |
| 24 | "Demo scan nakli QR data use karta hai. Asli QR camera se scan hota hai." | |

**Decisive tests:** (a) with `Simulate scan` producing a *valid mismatch*, the banner must announce
itself with no swipe (**QR-1**); (b) with TalkBack on, a **double-tap** on stop 18 must open the PIN
gate, and if the hold is kept, a **double-tap-and-hold** must also reach it (**QR-2**) — record which,
if either, works.

### 6.5 Settings — Hinglish

| # | utterance | note |
|---|---|---|
| 1 | "Settings" | heading (target; **ST-1**) |
| 2 | "…subtitle…" | |
| 3 | "Theme" | section heading (**ST-1**) |
| 4 | "System. Phone ki setting follow karein«, radio button, selected»" | **ST-6** — record whether "selected" is really appended |
| 5 | "Light«, radio button, not selected»" | |
| 6 | "Dark«, radio button, not selected»" | |
| 7 | "Bhasha" | |
| 8 | "System…" / "Hinglish…" / "English…" / "Hindi…" | four radios, one group |
| 9 | "…language hint…" | |
| 10 | "Sensitivity" | |
| 11 | "Kam / Balanced / Zyada" + detail | three radios |
| 12 | "…sensitivity safety note…" | |
| 13 | "Haptics" → "<label>. <detail>«, switch, off»" | **ST-6** |
| 14 | "Reduced motion" → "<label>. <detail>«, switch, off»" | |
| 15 | "Device check" | section heading |
| 16 | "Device ki jaankari dekhein«, button»" | **ST-3** — target: translated |
| 17 | "About" → "…about body…" | |
| 18 | "Reset…«, button, disabled»" | **ST-5** — no reason given |
| 19 | "…privacy note…" | |

**Decisive test:** confirm reset. The confirmation must be announced without swiping to the bottom of
the screen (**ST-2**).

### 6.6 App chrome — the demo experience

| # | utterance | note |
|---|---|---|
| 1 | "Demo band karein«, button»" | |
| 2 | "English«, button»" (or "Hinglish") | |
| 3 | "CRITICAL risk, 75 out of 100" | **CH-3** — then the children repeat it |
| 4 | "Call Shield · Script demo · 3 / 9" | |
| 5 | "<position label>" | |
| 6 | "<risk meter, 4 of 4>" | |
| 7 | "75/100 · Samajh: Zyada" | duplicate of 3 |
| 8 | "<position explanation>" | |
| 9 | "<alert text>" | **CH-4** — announced twice |
| 10 | "Yeh sanket kyun" | |
| 11 | "<stage>. mila. <quote>" | **CH-4** — announced twice |
| 12 | "«step line»" | |
| 13 | "<honest limits>" | |
| 14 | "Peeche«, button»" · "Agli line«, button»" · "Phir se«, button»" | |

---

## 7. Limitations — what this document does not prove

1. **A source audit is not a spoken-TalkBack test.** Every utterance in §6 is derived from the
   semantics the code declares. Whether TalkBack *actually* says it — merge behaviour of
   `contentDescription` versus child text, whether `stateDescription` is appended, whether a
   `Surface` with a description and non-merged children produces one stop or two — is exactly what
   §4 is for. **Zero utterances were heard while writing this.**
2. **No screenshot exists yet.** The `evidence/a11y/` directory contains a protocol, not captures.
   All 40 matrix cells are `not run`.
3. **Nothing was compiled or run.** No Gradle, no `adb`, no emulator: another lane owns the build and
   the single emulator. The `FIXED-BY-WAVE-1` rows are uncommitted source written by other children in
   the same worktree, mid-flight, and unverified — they may not compile.
4. **Camera and microphone states are unverified.** QR Guard's camera panel and Call Shield's mic
   panel were read as state machines, not observed. Camera-denied, no-camera, unreadable/occluded and
   permission-denied on a real sensor are untested.
5. **Devanagari announcements are unverified.** `ToneCopy.hindi` resolves through `HindiCopy`, and
   TalkBack's Hindi TTS quality for Hinglish-transliterated strings ("Khatra", "Bharosa") cannot be
   predicted from source at all. Whether Hindi copy is even complete for the strings in §6 is a
   different audit (`I18N_AUDIT.md`).
6. **Physical-phone verification is owner-only.** Nothing here is a claim about a real device; the
   owner's phone (`63b0de52`) was not touched, per the lane rules.
7. **Contrast was not re-measured.** `ui/theme/Color.kt` documents ratios (green 4.68:1, red 5.21:1,
   amber 4.09:1 graphics / 6.17:1 text via `RiskAmberTextLight`, accent 4.27:1 / 6.51:1 via
   `AccentTextLight`). Those are the theme's own recorded numbers; this audit took them as given.
8. **Haptics are unmeasurable on the emulator**, and the vibration-vs-reduced-motion interaction was
   not examined.
9. **Landscape and tablet layouts are untested**; the manifest locks no orientation and declares no
   `configChanges`, so rotation recreates the activity — retention is covered by #122's instrumentation,
   not by this document.
10. **RTL and text scaling beyond 2.0×** are out of scope.

---

## 8. When a row may be marked PASS

Only by the protocol in `docs/android/evidence/a11y/README.md`: a screenshot file whose name matches
the convention and exists in the repository, **or** a human-transcribed spoken-TalkBack recording
naming the row id. A source read can promote a row to `FIXED-BY-WAVE-1`, never to PASS.

## 9. What #97 still needs after this document

- The four HIGH rows are the blockers and none of them is fixed: `CS-1`, `CS-2` (silent critical
  alert), `QR-1` (silent mismatch), `QR-2` (re-register unreachable with TalkBack).
- A TalkBack listener on the emulator, one session, five scripts, transcribed (§6).
- The 40-cell screenshot matrix at 320 dp / 1.3× / 1.5× / 2.0× / light / dark / landscape (§4.5).
- The owner's physical-phone pass, which this project cannot substitute for.
