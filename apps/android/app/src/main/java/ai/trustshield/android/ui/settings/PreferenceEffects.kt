package ai.trustshield.android.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.PreferencePersistence
import ai.trustshield.android.data.preferences.SettingsStorage
import ai.trustshield.android.data.preferences.SettingsKeys
import ai.trustshield.android.data.preferences.ThemeMode
import ai.trustshield.android.data.preferences.UserPreferences
import ai.trustshield.android.ui.call.CallLanguage
import ai.trustshield.android.ui.navigation.LocalUserPreferences
import ai.trustshield.android.ui.qr.QrLanguage
import ai.trustshield.android.ui.result.UiLanguage

// =====================================================================================================
// Preference effects (work package P3-17, issue #96) — "Apply saved language, theme and haptics
// consistently".
//
// C04 (`data/preferences/UserPreferences.kt`) owns the *model*: which five choices exist, how they
// encode, what the defaults are, and the guarantee that nothing else can reach the file. It does not
// decide what the app should *do* with them, so until now every screen re-derived that itself:
//
//  * `MainActivity` had its own `when (preferences.themeMode)` block;
//  * `QrGuardRouteHost` mapped `AppLanguage -> QrLanguage` by hand;
//  * `CallShieldRouteHost` mapped `AppLanguage -> CallLanguage` by hand **and** copied `haptics`
//    into the call state;
//  * `ResultRouteHost` took a bare `english: Boolean` and derived `UiLanguage` from it;
//  * the call reducer had its own `enteredHighRisk && hapticsEnabled` haptic gate.
//
// Five independent translations of one stored choice is how a product ends up with an English
// result screen inside a Hinglish Call Shield, or a light-mode dialog over a dark screen. This file
// is the single translation, and it is a pure function so a JVM test can prove all of it — including
// that it survives an app restart (HEAD_ORDERS_PHASE3 item 6, "persist across restart") and that the
// haptic stays optional and single-shot (QUALITY_GATES gate 6, UI_SPEC "Tokens and motion").
//
// ## What this file decides
// | Stored choice | Effect applied |
// |---|---|
// | `ThemeMode.SYSTEM` | follow the phone's dark setting |
// | `ThemeMode.LIGHT` | light, whatever the phone says |
// | `ThemeMode.DARK` | dark, whatever the phone says |
// | `AppLanguage.HINGLISH` | Hinglish copy on QR Guard, Call Shield and Result |
// | `AppLanguage.ENGLISH` | English copy on the same three screens |
// | `haptics` | one short pulse on a *changed* entry into high risk, and nowhere else |
// | `reducedMotion` | stored choice, OR the platform animator switch |
//
// ## What this file never decides
// It does not score, does not pick a risk band, does not touch sensitivity (that is P3-16 /
// `SensitivityPolicy`), does not open a camera or a microphone, and never reads analysed content.
// `resolve` and `hapticFor` are `android.*`-free and take/return plain values, which is what lets
// `PreferenceEffectsTest` cover every rule on a plain JVM instead of on an emulator.
//
// ## What is NOT stored (HEAD_ORDERS §2 rule 4, N01, QUALITY_GATES gate 4)
// Preferences are **choices only**: theme mode, language, sensitivity, haptics, reduced motion. This
// file adds no key of its own — it reads and writes exactly C04's five ([SettingsKeys.ALL]) through
// C04's [PreferencePersistence], so the file can never contain an analysed message, a quote from it,
// a call transcript, an OTP, a PIN, a card number, a phone number, a UPI id, an image, a camera frame
// or a URI. Evidence and transcripts live in memory for the length of one session and die with it;
// they are never an *effect of a preference* and therefore never part of this type.
//
// ## Why the haptic is a user choice and not an automatic alarm
// A vibration that fires on its own on every high-risk reading would be an alarm the user never
// agreed to — it also cannot be turned off by someone who finds it startling, and it would buzz
// repeatedly on a live call because ASR emits chunks several times a second. So the pulse is gated on
// two things at once: the stored `haptics` toggle (C04, surfaced as a labelled switch in Settings) and
// an actual *transition* into high risk (`transitionedToHighRisk`), never a re-evaluation of a state
// that was already high risk, never a per-chunk or per-frame event. Note the deliberate consequence:
// C04 ships the toggle **on** (its own default, `SettingsTest.defaultsKeepHapticsOnAndReducedMotionOff`),
// because a silent-on-first-launch default reads as a broken feature to a first-time user — the
// opt-out is one tap away in Settings, and even when the toggle is on nothing fires without the
// risk band changing. If the owner ever wants the opposite first-launch default that is a one-line
// change in C04's model, not here.
//
// ## Adoption (one line each; those files are owned by other packages, so this package does not edit
// them — see the PR's "remaining adoption points")
//  * `MainActivity`: replace the local `when (preferences.themeMode)` block with
//    `val effects = rememberPreferenceEffects()` and pass `effects.darkTheme` to `TrustShieldTheme`.
//  * `QrGuardRouteHost`: `QrUiState(language = effects.qrLanguage)` instead of the hand-written `if`.
//  * `CallShieldRouteHost`: `CallUiState(language = effects.callLanguage, hapticsEnabled =
//    effects.hapticsEnabled, reducedMotion = effects.reducedMotion)`.
//  * `ResultRouteHost`: `UiLanguage.of(...)` becomes `effects.resultLanguage`, and the `english`
//    parameter can be dropped.
//  * `CallShieldReducer.applyHaptic`: compute `transitionedToHighRisk = next.tone == RiskTone.RED &&
//    previous.tone != RiskTone.RED` and gate on `hapticFor(effects, transitionedToHighRisk)` so the
//    rule has one implementation.
// =====================================================================================================

/**
 * The concrete, already-decided presentation effects for one frame of the app.
 *
 * Every field is a decision, not a preference: a caller cannot accidentally "interpret" a
 * [ThemeMode] again, and cannot render QR copy in a different language from Call copy. The stored
 * choice stays visible as [language] (the Settings screen shows it), while the three per-screen
 * language enums are derived from that one field — see [qrLanguage], [callLanguage] and
 * [resultLanguage] — so they are structurally incapable of disagreeing.
 *
 * Carries no content by construction: four fields, all presentation. `PreferenceEffectsTest`
 * asserts that field set reflectively, so a future "lastAnalysedText" cannot be smuggled in here.
 */
data class PreferenceEffects(
    /** Resolved dark/light after [ThemeMode] and the phone's own setting were combined. */
    val darkTheme: Boolean,
    /** The stored copy choice; the per-screen enums below are derived from it. */
    val language: AppLanguage,
    /** Whether the user left the haptic toggle on. Off means silent, always. */
    val hapticsEnabled: Boolean,
    /** Stored preference OR the platform animator switch — see [resolve]. */
    val reducedMotion: Boolean
) {

    /** Copy selector for QR Guard. */
    val qrLanguage: QrLanguage
        get() = when (language) {
            AppLanguage.HINGLISH -> QrLanguage.HINGLISH
            AppLanguage.ENGLISH -> QrLanguage.ENGLISH
        }

    /** Copy selector for Call Shield. */
    val callLanguage: CallLanguage
        get() = when (language) {
            AppLanguage.HINGLISH -> CallLanguage.HINGLISH
            AppLanguage.ENGLISH -> CallLanguage.ENGLISH
        }

    /** Copy selector for the Result screen. */
    val resultLanguage: UiLanguage
        get() = when (language) {
            AppLanguage.HINGLISH -> UiLanguage.HINGLISH
            AppLanguage.ENGLISH -> UiLanguage.ENGLISH
        }
}

/**
 * Turns the persisted [preferences] into the effects the app must apply.
 *
 * Pure and total: no `android.*`, no I/O, no randomness, no reading of the clock. The same
 * `(preferences, systemInDarkTheme, systemAnimatorsEnabled)` always yields the same result, which is
 * what makes "the theme did not flicker" and "the language did not drift" testable rather than
 * hopeful.
 *
 * @param preferences the loaded [UserPreferences]; its defaults are the shipping defaults
 *   (Hinglish, BALANCED, SYSTEM), so an empty or corrupt store lands here as Hinglish + follow the
 *   phone, never as an English dark screen.
 * @param systemInDarkTheme the phone's current setting, normally `isSystemInDarkTheme()`. Only read
 *   when [ThemeMode.SYSTEM] is stored — a forced LIGHT/DARK choice wins over the phone.
 * @param systemAnimatorsEnabled the platform's animator switch (`ValueAnimator.areAnimatorsEnabled()`),
 *   i.e. developer options "Animator duration scale = off". UI_SPEC requires animator scale zero to
 *   remove non-essential transitions, so this is OR-ed with the stored toggle: a user who turned
 *   animations off at the OS level does not have to find this app's switch as well. It defaults to
 *   `true` because a host that does not know the platform value must not slow the app down for
 *   everyone.
 */
fun resolve(
    preferences: UserPreferences,
    systemInDarkTheme: Boolean,
    systemAnimatorsEnabled: Boolean = true
): PreferenceEffects = PreferenceEffects(
    darkTheme = when (preferences.themeMode) {
        // The phone's setting is only consulted here; LIGHT and DARK are explicit user intent.
        ThemeMode.SYSTEM -> systemInDarkTheme
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    },
    // HINGLISH is C04's default, so an unreadable store can only ever produce the product default.
    language = when (preferences.language) {
        AppLanguage.HINGLISH -> AppLanguage.HINGLISH
        AppLanguage.ENGLISH -> AppLanguage.ENGLISH
    },
    hapticsEnabled = preferences.haptics,
    reducedMotion = preferences.reducedMotion || !systemAnimatorsEnabled
)

/**
 * Whether to fire the one haptic pulse for this state change.
 *
 * True only when **both** hold: the user opted in, and the risk band actually changed *to* high risk.
 * Everything else is silent — an unchanged high-risk state (a second suspicious utterance, a
 * recomposition, the same evidence re-read), a non-high-risk state, and every case where the toggle
 * is off.
 *
 * The caller supplies [transitionedToHighRisk] rather than two risk bands, because only the owner of
 * the state machine knows what "changed" means for it (`RiskTone.RED` entered from anything else);
 * keeping it a `Boolean` keeps this rule free of the call/result domain types and testable in
 * isolation. Because the function is pure, calling it twice for the same state gives the same answer,
 * so the "never twice per state" guarantee comes from the caller comparing bands — not from hidden
 * mutable state here.
 *
 * Deliberately NOT affected by language, theme or reduced motion: a user in dark mode, in English,
 * with reduced motion on, gets the same single pulse they agreed to.
 */
fun hapticFor(effects: PreferenceEffects, transitionedToHighRisk: Boolean): Boolean =
    effects.hapticsEnabled && transitionedToHighRisk

/**
 * Reads the stored preferences through C04's codec, without resolving them.
 *
 * Separate from [load] so a host that only needs the Settings screen's editable state (or that must
 * re-save what it read) is not forced to invent a `systemInDarkTheme` value it does not have.
 */
fun loadPreferences(storage: SettingsStorage): UserPreferences = PreferencePersistence(storage).load()

/**
 * The restart path: read the file C04 persisted and resolve it into effects.
 *
 * `systemInDarkTheme` is a required parameter rather than defaulting to `false`, because guessing
 * "light" here would silently override a dark phone for every user who never opened Settings. A host
 * that genuinely has no system value is in a preview, and Compose's own
 * `isSystemInDarkTheme()` default covers that case — see [rememberPreferenceEffects].
 *
 * Corrupt or absent values degrade to C04's defaults inside [PreferencePersistence.load], so this
 * function never throws and never leaves the app half-themed: an empty file resolves to Hinglish,
 * BALANCED, SYSTEM, exactly as a first launch.
 */
fun load(storage: SettingsStorage, systemInDarkTheme: Boolean): PreferenceEffects =
    resolve(loadPreferences(storage), systemInDarkTheme = systemInDarkTheme)

/**
 * Persists [preferences] through C04's allowlist-filtered writer and returns what was actually
 * stored.
 *
 * Returns the *saved* value rather than the input so a caller can immediately feed the result back
 * into [resolve] and trust that what it renders is what is on disk (C04 clamps and normalises as it
 * writes). Only C04's five keys can reach the file; anything else already there — a stray key from an
 * older build, or analysed content written by a bug — is deleted by the replacing write.
 */
fun save(preferences: UserPreferences, storage: SettingsStorage): UserPreferences =
    PreferencePersistence(storage).save(preferences)

/**
 * Compose convenience: the effects for the current frame, from the hoisted preferences C01 provides.
 *
 * Contains no decisions — it reads three values the composition already has (the app's preferences,
 * the phone's dark setting, the platform animator switch) and hands them to [resolve]. Everything the
 * UI must obey is therefore still in one pure function, and this helper stays a one-liner a host can
 * call without threading parameters through the navigation graph.
 *
 * `ValueAnimator.areAnimatorsEnabled()` (API 26, our `minSdk`) is the documented equivalent of
 * "animator duration scale is zero"; it is re-read on each composition and is part of the `remember`
 * key, so switching the platform setting is picked up on the next recomposition rather than being
 * frozen for the process lifetime.
 */
@Composable
fun rememberPreferenceEffects(): PreferenceEffects {
    val preferences = LocalUserPreferences.current
    val systemInDarkTheme = isSystemInDarkTheme()
    val animatorsEnabled = android.animation.ValueAnimator.areAnimatorsEnabled()
    return remember(preferences, systemInDarkTheme, animatorsEnabled) {
        resolve(
            preferences = preferences,
            systemInDarkTheme = systemInDarkTheme,
            systemAnimatorsEnabled = animatorsEnabled
        )
    }
}
