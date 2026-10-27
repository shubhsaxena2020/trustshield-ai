# Review — UI / state / navigation / i18n / accessibility

Slice: `ui/home`, `ui/result`, `ui/settings`, `ui/theme`, `ui/navigation`, the copy layer
(`ToneCopy`, `SettingText`, `HindiCopy`, per-screen `*Copy` objects) and `res/values`.
Base: `android/P8-review` (d32fcda). READ-ONLY review; nothing but this file was written.
Method: read every file in the slice plus `MainActivity.kt`, `UserPreferences.kt`,
`PreferenceEffects.kt`; cross-checked the copy tables mechanically (parsed every `ToneCopy(` /
`SettingText(` call and every `HindiCopy` key, then diffed the English keys against the table
instead of eyeballing 300 lines). Nothing was compiled or run.

**Counts: P0 = 0, P1 = 3, P2 = 9** (12 verified findings, 3 unverified suspicions).

The strongest signals: the Hindi copy table is keyed by English text, and the paths that build
their English at runtime (the limitations section, one menu item) silently fall back to English;
two of the three accessibility/state bugs below are `remember` vs `rememberSaveable` and
`remember(<value the state itself changes>)` mistakes rather than design choices.

---

### UI-01 — Hindi Result screen renders the whole "Limitations" section in English
Severity: P1
Where: `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt:258`, `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/LocalEvidenceAdapter.kt:102-113`, `apps/android/app/src/main/java/ai/trustshield/android/ui/theme/HindiCopy.kt:5-300`
Scenario: `AnalysisToResult.build` maps the engine's limitation lines with
`ToneCopy(it, LocalEvidenceAdapter.limitation(it))`. `ToneCopy.hindi` defaults to
`HindiCopy.forEnglish(english)`, an exact-string map lookup. `LocalEvidenceAdapter.limitation`
emits seven sentences ("The message was too long, so only its beginning was checked.",
"No text was available to check.", "The text was too short for a reliable check.", "The audio was
unclear…", "No scam pattern was found. That does not prove the message is 100% safe.",
"Older call content left the analysis window…", "Some parts of the message may not be fully
represented in this result.") and none of them is a key in `HindiCopy` (checked all 294 keys).
So with language = Hindi, every scan renders `ResultScreen`'s `LimitationsSection`
(`ResultScreen.kt:121`) in English, while the header/summary above it is Devanagari. This is the
section that carries the safety caveats ("does not prove the message is 100% safe"), so a Hindi
reader gets exactly the part that must not be misread in the wrong language. The
`LIMIT_UNSUPPORTED_SCRIPT_PREFIX` variant is built by concatenation at runtime, so it can never
be keyed at all.
Fix: give the limitation strings first-class Hindi copy — either add the seven exact sentences to
`HindiCopy`, and for the script-prefix case change `limitation()` to return a `ToneCopy` with an
explicit `hindi = "यह भाषा अभी पूरी तरह समर्थित नहीं है: " + suffix` instead of relying on lookup.
Minimal version: add the 6 static keys to `HindiCopy` and make `limitation` return `ToneCopy`
only for the prefix branch.
Failing test: `ui/theme/UiCopyGuardTest.everyToneCopyHasDevanagariWhenHindiIsSelected` (new) —
build the state via `AnalysisToResult.build` for a >20,000-char input, then assert
`state.limitations.none { it.pick(UiLanguage.HINDI) == it.english }`.

### UI-02 — TalkBack is never told the verdict when the Result screen appears
Severity: P1
Where: `apps/android/app/src/main/java/ai/trustshield/android/ui/result/ResultScreen.kt:157-191` (`ResultTopBar`), `:194-238` (`RiskHeader`), `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt:303`
Scenario: Tapping "Check (offline)" runs `onCheck(text); nav.navigate(AppRoute.RESULT)`
(`AppScreens.kt:303`). On the new screen the verdict travels only as
`Modifier.semantics { contentDescription = state.screenDescription(...) }` on the title `Text`
(`ResultScreen.kt:175`) and on `RiskHeader`'s merged card. There is no `liveRegion`, no
`focusRequester`/`requestFocus`, and no `announceForAccessibility` anywhere in `ui/` (grep:
`liveRegion` appears once, in `ScanScreen.kt:754`, for the error banner only). A screen change
drops TalkBack's focus, so the first thing announced on arrival is the first focusable node — the
`‹ Back` `TextButton` at `ResultScreen.kt:162`. A blind user therefore gets no verdict until they
swipe down, which is the one thing UI_SPEC's "TalkBack announces risk **and** confidence before
evidence" is there to guarantee, and the same gap exists for the unable-to-verify banner.
Fix: add `Modifier.focusRequester(headerFocus).focusable()` to the `RiskHeader` surface plus
`LaunchedEffect(Unit) { headerFocus.requestFocus() }` in `ResultScreen`, so the merged risk +
confidence description is announced once on entry (do not use `LiveRegionMode.Assertive` on the
card itself — it re-announces on every recomposition).
Failing test: `ui/result/ResultScreenTest.talkBackAnnouncesTheVerdictOnEntryWithoutASwipe` —
assert the new `ResultScreenSemantics` node flagged `hasVerdictAnnouncement` is present and
`focused` immediately after composition, and that it is not the Back button.

### UI-03 — Reset confirmation can never be shown: `remember(prefs)` re-keys the Settings state
Severity: P1
Where: `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt:952-968`, `apps/android/app/src/main/java/ai/trustshield/android/ui/settings/SettingsState.kt:244-249`, `apps/android/app/src/main/java/ai/trustshield/android/ui/settings/SettingsScreen.kt:227-234`
Scenario: `SettingsRouteHost` holds `var state by remember(prefs) { mutableStateOf(SettingsState.from(prefs)) }`
and its event handler does `sink(next.preferences)` **before** `state = next`. Steps: Settings →
"Default par reset karein" → confirm. `reduce(ConfirmReset)` returns
`SettingsState(preferences = defaults, notice = RESET_APPLIED)`; `sink` writes defaults to
`MainActivity.preferences`, which is a `mutableStateOf` read by `LocalUserPreferences`. The next
recomposition sees a different `prefs` key, re-runs the `remember` block, and rebuilds
`state = SettingsState.from(prefs)` — i.e. `notice = NONE`. The "Defaults lag gaye." banner
(`SettingsScreen.kt:227`) is therefore unreachable in the running app, and the same re-key
discards `RESET_REQUESTED` (the confirmation dialog) if anything else changes a preference while
the dialog is open. `SettingsNotice.RESET_APPLIED` is dead state as wired.
Fix: stop keying the whole state on `prefs`. Keep only the transient notice in local state:
`var notice by rememberSaveable { mutableStateOf(SettingsNotice.NONE) }` and derive
`val state = SettingsState(prefs, notice)`; have the handler update `notice` and persist
separately. (This also fixes UI-11's rotation loss for the dialog.)
Failing test: `ui/settings/SettingsTest.resetConfirmationSurvivesThePersistedPreferenceChange` —
feed `SettingsState.from(prefs)` through `reduce(ConfirmReset)`, apply the returned preferences to
a fake sink, re-derive the screen state the way the host does, and assert
`resetApplied == true`.

---

### UI-04 — A precise "Score: N/100" is drawn on the Result card, contradicting the band-only design and the test that guards it
Severity: P2
Where: `apps/android/app/src/main/java/ai/trustshield/android/ui/result/ResultScreen.kt:227-233`, `apps/android/app/src/main/java/ai/trustshield/android/ui/result/ResultUiState.kt:132-133` and `:315`, `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt:187-192`
Scenario: `AnalysisToResult` passes `riskScore = outcome.risk.score` (null only for
`UNABLE_TO_VERIFY`), `ResultPresenter` stores it in `RiskBand.score`, and `RiskHeader` draws
`ToneCopy("Score: $score/100", "Score: $score/100", "स्कोर: $score/100")` whenever `risk.score != null`.
So every normal scan shows a two-digit figure. That is the opposite of the contract stated three
times in the same slice: `ResultScreen`'s class doc ("a coarse band with no figure… nothing is
drawn"), `RiskScoreSpeech`'s doc ("it is never passed to a `Text`"), and UI_SPEC's "avoid score
animation that implies fabricated precision". The guard test cannot catch it:
`ResultScreenStrayTextTest` looks for the literal `Risk score` / the
`risk score :? N/100` shape, and `ResultUiState.visibleStrings` *legitimately* lists
`"Score: $it/100"` (`:315`), so `talkBackStillHearsTheScoreWhileNothingDrawsIt` passes while the
screen draws exactly the number it claims is invisible.
Fix: delete the `risk.score?.let { … }` block at `ResultScreen.kt:227-233` (the score already
reaches TalkBack through `screenDescription`), and add `"Score:"` to `RiskScoreSpeech`'s
`isStrayScoreLine` predicate so the guard covers the wording actually used.
Failing test: `ui/result/ResultScreenStrayTextTest.noFixtureStateCanDrawAScoreFigure` — assert no
string in `state.visibleStrings(language)` matches `(?i)score\s*:?\s*\d+\s*/\s*100`.

### UI-05 — Call Shield's offline banner uses Material 3's default lavender/pink `tertiaryContainer`
Severity: P2
Where: `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt:525-540`, `apps/android/app/src/main/java/ai/trustshield/android/ui/theme/Theme.kt:24-52` and `:54-82`
Scenario: `TsColorSchemes.Light` / `.Dark` override `tertiary` and `onTertiary` but never
`tertiaryContainer` / `onTertiaryContainer`, and `TsPalette` defines no tertiary container tokens.
`CallShieldRouteHost` draws the "on-device recogniser is Hindi-only" banner with
`color = colorScheme.tertiaryContainer, contentColor = onTertiaryContainer`, so that banner is
M3 baseline lavender (`#FFD8E4`/`#31111D`) in light mode and rose (`#633B48`/`#FFD8E4`) in dark
mode — the only place in the app that is not on a TrustShield token, and visibly off-brand next
to the blue/green/amber/red scheme. `ThemeTest` cannot catch it because it asserts the pairs the
scheme *does* define.
Fix: use an existing themed pair for that informational banner (`secondaryContainer` /
`onSecondaryContainer`, as `UnableToVerifyBanner` already does), or add
`tertiaryContainer`/`onTertiaryContainer` to `TsPalette` for both modes and pin them in
`ThemeTest`.
Failing test: `ui/theme/ThemeTest.schemeDefinesNoMaterialBaselineTertiaryContainer` — assert
`TsColorSchemes.of(true).tertiaryContainer == TsPalette.<new dark token>` (and the light pair), so
a fall-through to the M3 default fails.

### UI-06 — Typed / pasted message is destroyed by rotation
Severity: P2
Where: `apps/android/app/src/main/java/ai/trustshield/android/MainActivity.kt:113`, `:213-217`, `:220-224`
Scenario: `var scanState by remember { mutableStateOf(ScanInputState()) }` lives in the
`setContent` composition. A configuration change (rotate, unfold, font-scale change) recreates the
activity and drops it, so a user who pastes a long SMS and rotates loses it and must paste again.
The same file deliberately preserves the *result* across the same event
(`onRetainCustomNonConfigurationInstance()` returns `resultOutcome`, `:213`), so the behaviour is
inconsistent: the screen that can still be re-derived (`Result`) is kept, and the one holding the
only copy of the user's input is discarded. `rememberSaveable` would fix the rotate case but put
raw message text into the saved-state Bundle, which the privacy invariants avoid.
Fix: hold `ScanInputState` in a `ViewModel` (survives configuration change, dies with the
process, never serialised) and read it from the composition; keep `onSaveInstanceState` as it is.
Failing test: `ui/navigation/ScanFlowTest.scanInputSurvivesConfigurationChangeButNotProcessDeath` —
with a fake `ViewModelStore`, recreate the owner and assert `state.text` is unchanged, then clear
the store and assert it is empty.

### UI-07 — `DeviceCheckLine`'s unweighted value can squeeze the label to zero width at large font scale
Severity: P2
Where: `apps/android/app/src/main/java/ai/trustshield/android/ui/settings/SettingsScreen.kt:302-311`
Scenario: `Row { Text(label, Modifier.weight(1f)); Text(value) }`. In a `Row` the weighted child is
measured last from the *remaining* width, while the unweighted `value` is measured first against
the row's full width. Long values exist: `DeviceCheckCopy.permission` / `localeStatus` return
"Not checked on this Android version" → "इस Android संस्करण पर जाँच नहीं हुई", and the ABI line is
a free-form string. At 360 dp with fontScale 1.5 those values consume the row, so the label
("Camera permission" → "कैमरा अनुमति") is laid out at ~0 dp and disappears — the value is readable
but the user no longer knows what it describes. `EvidenceCard`'s header row
(`ResultScreen.kt:370-388`: glyph + severity label + `Spacer(weight(1f))` + module name) has the
same ordering and the same risk in Hindi.
Fix: give the value a `Modifier.weight(2f, fill = false)` (or `widthIn(max = …)`) so both sides
share the width and both wrap; same change in `EvidenceCard` for the module text.
Failing test: `ui/settings/SettingsTest.deviceCheckRowKeepsBothSidesMeasuredAtLargeFontScale` —
assert both `Text`s in `DeviceCheckLine` carry a weight (source-level check, as the project has no
Compose UI-test dependency).

### UI-08 — Three copy entries have no Devanagari, so Hindi mode shows English
Severity: P2
Where: `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppNav.kt:157`, `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt:389`, `apps/android/app/src/main/java/ai/trustshield/android/ui/call/CallShieldCopy.kt:35`
Scenario: `ToneCopy("Phone ki bhasha", "Use system language")` — key "Use system language" is not
in `HindiCopy` (the table has "Use phone language" instead), so the first item of the app-wide
language menu (`AppNav.kt:162-186`, drawn on **every** screen) reads "Use system language" in
Latin script while the other three items are "हिंग्लिश / अंग्रेज़ी / हिन्दी". Likewise
`ToneCopy("QR Guard", "QR Guard")` on Home has no entry, and
`ToneCopy("Listening - no speech heard yet", …)` in `CallShieldCopy`. Product names may
legitimately stay Latin, but the first two of these are *sentences*, not names, so a Hindi user
gets a mixed-script menu. (Checked mechanically over all 328 `ToneCopy(` call sites: 218 resolve
through a `HindiCopy` key and 82 pass Devanagari explicitly = 300 with Hindi; 20 build the English
at runtime and so can never be keyed; 8 literal English strings have no key — two of them are the
`Use system language` and `Listening - no speech heard yet` above, the other six are `QR Guard`
×2, `TrustShield AI`, `Camera panel — `, `Registered: {id}`, `Scan another QR or start watching.`
and are in the QR slice.)
Fix: change `AppNav.kt:157` to `ToneCopy("Phone ki bhasha", "Use system language", "फ़ोन की भाषा का
उपयोग करें")` and add the missing key/sentence for the Call Shield line; add
`ToneCopy.hindi.isNotBlank()` assertions per screen object rather than relying on the single
`HindiCopy` table.
Failing test: `ui/theme/UiCopyGuardTest.sharedLanguageMenuHasDevanagariForEveryOption` — assert
`languageOptions.all { it.second.forLanguage(AppLanguage.HINDI).any(Char::isDevanagari) || it.first
== AppLanguage.HINGLISH }`.

### UI-09 — Result carries two different language controls that disagree
Severity: P2
Where: `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppNav.kt:162-186`, `apps/android/app/src/main/java/ai/trustshield/android/ui/result/ResultScreen.kt:177-190`, `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt:210-225`
Scenario: `AppNavHost` draws a full language `DropdownMenu` above `content(...)` on every route, so
Result shows that dropdown *and* its own `TextButton`. In English mode the dropdown reads "English"
(selected) while the in-card button reads "Hinglish"; in Hindi mode the dropdown reads "हिन्दी" and
the in-card button reads "हिंग्लिश". Both change the same preference, one via the dropdown and one
via a cycle (`onToggleLanguage`: HINGLISH→ENGLISH→HINDI→HINGLISH), so from Hindi a user needs two
taps to reach English and cannot tell from the two labels which control does what. Call Shield's
staged demo has a third cycler with the same effect.
Fix: drop the in-card `TextButton` from `ResultTopBar` (the shared dropdown already covers the
destination, per I18N_AUDIT Phase 6), or make the cycle a menu. Keep one affordance per screen.
Failing test: `ui/navigation/NavTest.resultRouteExposesExactlyOneLanguageControl` — assert the
`ResultScreen` call site in `AppScreens.kt` no longer passes `onToggleLanguage`.

### UI-10 — A `MutableState` is written during composition in `CallShieldRouteHost`
Severity: P2
Where: `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt:581`, `:689-719`
Scenario: `offlineFailureAction = { host.onStop(); offlineMode = false; state = host.state }` is an
unguarded assignment of a `mutableStateOf` in the composable body, executed on every
recomposition of the Call Shield route (it sits at the top level of the `else` branch, not inside
a `LaunchedEffect`). Writing snapshot state during composition is the pattern Compose's own
docs forbid: it invalidates the composition that is running, and the write closes over `host`,
`offlineMode` and `state`, so whether it re-invalidates depends on lambda identity rather than on
any rule the code states. The neighbouring `remember(host) { session.setUtteranceSink(...) }` at
`:689` has the same shape — a side effect performed inside a `remember` calculation.
Fix: `LaunchedEffect(host, offlineMode) { offlineFailureAction = … }` or, better, make it a
`val` derived from `host` inside a `rememberUpdatedState`d holder and invoke it from the
recogniser callback only.
Failing test: `ui/call/CallScreenTest.noStateIsWrittenDuringCallShieldComposition` — source-level
guard: the body of `CallShieldRouteHost` may not assign an `offline*` `var` outside a
`LaunchedEffect`/`DisposableEffect` block.

### UI-11 — Registered payees live in a `remember` in the nav host and are lost on rotation
Severity: P2
Where: `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppScreens.kt:296` and `:314`, `apps/android/app/src/main/java/ai/trustshield/android/ui/result/ResultScreen.kt:109-111`
Scenario: `var registeredPayees by remember { mutableStateOf(emptyList<RegisteredPayee>()) }` is
filled only when `QR_GUARD` is composed, and `ResultRouteHost(language, resultOutcome,
registeredPayees)` then feeds `AnalysisToResult.build(outcome, payees)` → `PaymentAdvisoryCard`.
Because `QrGuardHost` never composes while Result is visible, a rotation rebuilds the list as
`emptyList()` while `resultOutcome` survives via `onRetainCustomNonConfigurationInstance`; the
`remember(outcome, payees)` in `ResultRouteHost` re-runs and the advisory silently re-renders
without the payee comparison it had seconds earlier (mismatch/match evidence disappears, so a
shopkeeper's screenshot of the QR result can change after rotating the phone). Note
`data/preferences/RegisteredPayeeStore` already exists with a SharedPreferences adapter and is
referenced by nothing in `ui/`.
Fix: hoist the payees the way preferences are hoisted — a `mutableStateOf` in `MainActivity`
(or a `ViewModel`), seeded from `RegisteredPayeeStore`, instead of a `remember` inside
`TrustShieldRouteContent`.
Failing test: `ui/navigation/AnalysisToResultPaymentAdvisoryTest.advisoryKeepsPayeeComparisonAcrossRotation`
— build with a payee list, rebuild with the empty list, assert
`paymentAdvisory == before` (i.e. that the host, not the screen, owns the list).

### UI-12 — Changing the phone language while the app is open never recomposes
Severity: P2
Where: `apps/android/app/src/main/java/ai/trustshield/android/ui/theme/RiskToneSemantics.kt:16`, `apps/android/app/src/main/java/ai/trustshield/android/ui/navigation/AppNav.kt:152-155`, `apps/android/app/src/main/java/ai/trustshield/android/MainActivity.kt:163-164`
Scenario: When `language == SYSTEM`, the rendered language comes from
`resolveLanguage(language, Locale.getDefault().toLanguageTag())`, evaluated inside
`ToneCopy.forLanguage` and passed down from `MainActivity` / `AppNavHost` as a plain parameter.
`Locale.getDefault()` is not snapshot state and the activity does not declare
`android:configChanges="locale|layoutDirection"`, so a locale change *does* recreate the activity —
but a locale change delivered without recreation (per-app language change on API 33+, or a
`LocaleManager` change from Settings on some OEM builds) leaves every screen on the old language
until some unrelated state changes, because nothing observes the locale.
Fix: read the system tag from a snapshot-backed source in one place, e.g.
`val systemTag by remember { mutableStateOf(Locale.getDefault().toLanguageTag()) }` refreshed from
`LocalConfiguration.current` (`val cfg = LocalConfiguration.current; val tag = cfg.locales[0].toLanguageTag()`),
and pass that one value everywhere instead of calling `Locale.getDefault()` at each call site.
Failing test: `ui/settings/PreferenceEffectsTest.systemLanguageChangeIsObservedByTheCopyLayer` —
assert `resolveLanguage(AppLanguage.SYSTEM, "hi-IN")` is what every screen receives when the
configuration locale is `hi-IN` (i.e. that the value flows from one observed source).

---

## Unverified suspicions

1. **`rememberPreferenceEffects()` resolves SYSTEM to Hinglish.** `PreferenceEffects.kt:249-260`
   calls `resolve(preferences, systemInDarkTheme, animatorsEnabled)` without `systemLanguageTag`,
   whose default is `null` → `resolveLanguage(SYSTEM, null)` → `HINGLISH`. On a `hi-IN` phone with
   the default `SYSTEM` setting this disagrees with `MainActivity`/`AppNav`/`ResultUiState.of`,
   which all pass the real locale tag. No caller uses `rememberPreferenceEffects()` today (grep),
   so nothing is broken now — but adopting it as the file's own KDoc instructs would put a Hinglish
   Call Shield inside a Hindi shell. I did not classify it as a finding because the path is
   currently dead.
2. **`SettingText.forLanguage`'s `AppLanguage.SYSTEM -> english` branch is unreachable** —
   `resolveLanguage` never returns `SYSTEM`. Harmless today; it hides the fact that Settings
   resolves through a second, separate code path from `ToneCopy.forLanguage`.
3. **`ImageScanActions.english`** (`ImageScanLauncher.kt:729/826`) is set from the boolean
   `english` parameter, but the host renders image errors with the `AppLanguage` overload
   (`AppScreens.kt:377-378`). Hindi mode therefore looks correct today; the boolean path is only
   a trap for the next caller (e.g. a share-entry label printed from `actions.english`). I did not
   find a live wrong-language render from it.
