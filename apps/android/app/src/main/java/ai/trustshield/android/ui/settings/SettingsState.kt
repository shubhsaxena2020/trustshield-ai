package ai.trustshield.android.ui.settings

import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.Sensitivity
import ai.trustshield.android.data.preferences.ThemeMode
import ai.trustshield.android.data.preferences.UserPreferences
import ai.trustshield.android.data.preferences.resolveLanguage
import ai.trustshield.android.ui.theme.ToneCopy
import java.util.Locale

/**
 * Pure state + reducer for the Settings screen (work package C04, issue #45).
 *
 * No `android.*` and no `androidx.compose.*` imports: everything the screen decides — which option
 * is selected, whether the reset confirmation is up, what the notice says — is decided here and
 * covered by `SettingsTest`. `SettingsScreen.kt` only renders this state and forwards
 * [SettingsEvent]s.
 */

/** Bilingual copy: simple Hinglish plus an English variant (HEAD_ORDERS §2 rule 6). */
data class SettingText(val hinglish: String, val english: String) {
    fun forLanguage(language: AppLanguage): String = when (
        resolveLanguage(language, Locale.getDefault().toLanguageTag())
    ) {
        AppLanguage.SYSTEM -> english
        AppLanguage.HINGLISH -> hinglish
        AppLanguage.ENGLISH -> english
        AppLanguage.HINDI -> ToneCopy(hinglish, english).hindi
    }
}

/**
 * Every user-facing string on the Settings screen, in both languages.
 *
 * There is deliberately **no** "turn off all warnings" string, and no copy that could be read as
 * one. Sensitivity copy explicitly says warnings cannot be switched off.
 */
object SettingsCopy {

    val screenTitle = SettingText("Settings", "Settings")
    val screenSubtitle = SettingText(
        "App ko apne hisaab se set karein.",
        "Set the app up the way you like."
    )

    val themeSection = SettingText("Theme", "Theme")
    val themeSystem = SettingText("Phone jaisa (system)", "Match phone (system)")
    val themeSystemDetail = SettingText(
        "Phone ke light/dark mode ko follow karega.",
        "Follows your phone's light/dark mode."
    )
    val themeLight = SettingText("Hamesha light", "Always light")
    val themeDark = SettingText("Hamesha dark", "Always dark")

    val languageSection = SettingText("Bhasha / Language", "Language")
    val languageSystem = SettingText("Phone ki bhasha", "Use phone language")
    val languageHinglish = SettingText("Hinglish", "Hinglish")
    val languageEnglish = SettingText("English", "English")
    val languageHindi = SettingText("Hindi", "Hindi")
    val languageHint = SettingText(
        "Phone ki bhasha default hai. Hinglish, English ya Hindi yahan chunein.",
        "The system language is selected by default. Choose Hinglish, English or Hindi here."
    )

    val sensitivitySection = SettingText("Sensitivity", "Sensitivity")
    val sensitivityLow = SettingText("Kam alerts — LOW", "Fewer alerts — LOW")
    val sensitivityLowDetail = SettingText(
        "Sirf saaf, strong pattern par alert. Kuch mild cases miss ho sakte hain.",
        "Alerts only on clear, strong patterns. Some mild cases may be missed."
    )
    val sensitivityBalanced = SettingText("Balanced — default", "Balanced — default")
    val sensitivityBalancedDetail = SettingText(
        "Recommended. Strong pattern par alert, bina panic ke.",
        "Recommended. Alerts on strong patterns without over-warning."
    )
    val sensitivityHigh = SettingText("Zyada alerts — HIGH", "More alerts — HIGH")
    val sensitivityHighDetail = SettingText(
        "Zyada cheezein alert karengi, isliye kuch extra warnings aa sakti hain.",
        "More things will alert, so expect some extra warnings."
    )

    /**
     * The product rule, in the user's own words: sensitivity changes how often you are warned, and
     * a verified strong ask is never shown as safe.
     */
    val sensitivitySafetyNote = SettingText(
        "Important: sensitivity sirf alert ki likelihood badalti hai. Verified strong ask (OTP, " +
            "paise, remote access) kabhi \"safe\" nahi dikhega — warnings poori tarah band karne " +
            "ka koi option nahi hai.",
        "Important: sensitivity only changes how often you are warned. A verified strong ask " +
            "(OTP, money, remote access) is never shown as \"safe\" — there is no option to turn " +
            "warnings off completely."
    )

    val hapticsSection = SettingText("Haptics", "Haptics")
    val hapticsLabel = SettingText("High risk par halka vibrate", "Vibrate on high risk")
    val hapticsDetail = SettingText(
        "Sirf ek baar, jab risk naya high ho — har line par nahi.",
        "Once only, when a risk newly turns high — not on every line."
    )

    val reducedMotionSection = SettingText("Reduced motion", "Reduced motion")
    val reducedMotionLabel = SettingText("Animations kam karein", "Reduce animations")
    val reducedMotionDetail = SettingText(
        "Transitions turant honge. Koi pulsing ya flashing alert nahi.",
        "Transitions become instant. No pulsing or flashing alerts."
    )

    val resetAction = SettingText("Default par reset karein", "Reset to defaults")
    val resetDialogTitle = SettingText("Reset to defaults?", "Reset to defaults?")
    val resetDialogBody = SettingText(
        "Theme, bhasha, sensitivity, haptics aur reduced motion default par chale jayenge. " +
            "Yeh settings koi message ya data save nahi karti, isliye delete karne ke liye " +
            "kuch nahi hai.",
        "Theme, language, sensitivity, haptics and reduced motion go back to defaults. These " +
            "settings store no message or data, so there is nothing to delete."
    )
    val resetConfirm = SettingText("Reset karein", "Reset")
    val resetCancel = SettingText("Cancel", "Cancel")
    val resetApplied = SettingText("Defaults lag gaye.", "Defaults applied.")

    val privacyNote = SettingText(
        "Yahan sirf aapke choices save hote hain — koi message, quote, OTP, phone number ya UPI " +
            "id nahi.",
        "Only your choices are saved here — no message, quote, OTP, phone number or UPI id."
    )
    val dismissOk = SettingText("Theek hai", "OK")
    val aboutSection = SettingText("TrustShield AI ke baare mein", "About TrustShield AI")
    val aboutBody = SettingText(
        "Sandigdh messages ko isi device par check karein. Natija samajhne ke liye evidence dekhein.",
        "Check suspicious messages on this device. Review the evidence to understand the result."
    )

    /** All copy, for tests that guard against a "disable all warnings" preset creeping in. */
    fun all(): List<SettingText> = listOf(
        screenTitle, screenSubtitle,
        themeSection, themeSystem, themeSystemDetail, themeLight, themeDark,
        languageSection, languageSystem, languageHinglish, languageEnglish, languageHindi, languageHint,
        sensitivitySection, sensitivityLow, sensitivityLowDetail,
        sensitivityBalanced, sensitivityBalancedDetail,
        sensitivityHigh, sensitivityHighDetail, sensitivitySafetyNote,
        hapticsSection, hapticsLabel, hapticsDetail,
        reducedMotionSection, reducedMotionLabel, reducedMotionDetail,
        resetAction, resetDialogTitle, resetDialogBody, resetConfirm, resetCancel, resetApplied,
        privacyNote, dismissOk, aboutSection, aboutBody
    )
}

/** Transient screen state that is not persisted. */
enum class SettingsNotice {
    NONE,
    RESET_REQUESTED,
    RESET_APPLIED
}

/**
 * Everything the Settings screen needs to render.
 *
 * [preferences] is the persisted truth; [notice] is throwaway UI state (reset confirmation /
 * confirmation toast) and is intentionally not part of [UserPreferences].
 */
data class SettingsState(
    val preferences: UserPreferences = UserPreferences.defaults(),
    val notice: SettingsNotice = SettingsNotice.NONE
) {
    val language: AppLanguage get() = preferences.language
    val themeMode: ThemeMode get() = preferences.themeMode
    val sensitivity: Sensitivity get() = preferences.sensitivity
    val haptics: Boolean get() = preferences.haptics
    val reducedMotion: Boolean get() = preferences.reducedMotion

    /** True while the destructive reset confirmation is on screen. */
    val resetConfirmationVisible: Boolean get() = notice == SettingsNotice.RESET_REQUESTED

    /** True briefly after a reset so the screen can acknowledge it. */
    val resetApplied: Boolean get() = notice == SettingsNotice.RESET_APPLIED

    val isAtDefaults: Boolean get() = preferences == UserPreferences.defaults()

    /** Copy resolved for the currently selected language. */
    fun text(setting: SettingText): String = setting.forLanguage(language)

    companion object {
        fun from(preferences: UserPreferences): SettingsState = SettingsState(preferences)
    }
}

/** Every action the Settings screen can raise. */
sealed interface SettingsEvent {

    data class SelectTheme(val themeMode: ThemeMode) : SettingsEvent
    data class SelectLanguage(val language: AppLanguage) : SettingsEvent
    data class SelectSensitivity(val sensitivity: Sensitivity) : SettingsEvent
    data class SetHaptics(val enabled: Boolean) : SettingsEvent
    data class SetReducedMotion(val enabled: Boolean) : SettingsEvent

    /** Opens the reset confirmation. Does not change any preference. */
    data object RequestReset : SettingsEvent

    /** Dismisses the confirmation, leaving preferences untouched. */
    data object CancelReset : SettingsEvent

    /** Applies defaults. No-op unless a reset was actually requested first. */
    data object ConfirmReset : SettingsEvent

    /** Acknowledges the "defaults applied" notice. */
    data object DismissNotice : SettingsEvent
}

/**
 * Pure reducer. Total function: every event, in every state, yields a valid state.
 *
 * The caller persists [SettingsState.preferences] when it changes; the reducer itself performs no
 * I/O so it stays testable on the JVM.
 */
fun SettingsState.reduce(event: SettingsEvent): SettingsState = when (event) {
    is SettingsEvent.SelectTheme ->
        copy(preferences = preferences.copy(themeMode = event.themeMode), notice = SettingsNotice.NONE)

    is SettingsEvent.SelectLanguage ->
        copy(preferences = preferences.copy(language = event.language), notice = SettingsNotice.NONE)

    is SettingsEvent.SelectSensitivity ->
        copy(
            preferences = preferences.copy(sensitivity = event.sensitivity),
            notice = SettingsNotice.NONE
        )

    is SettingsEvent.SetHaptics ->
        copy(preferences = preferences.copy(haptics = event.enabled), notice = SettingsNotice.NONE)

    is SettingsEvent.SetReducedMotion ->
        copy(
            preferences = preferences.copy(reducedMotion = event.enabled),
            notice = SettingsNotice.NONE
        )

    SettingsEvent.RequestReset ->
        copy(notice = SettingsNotice.RESET_REQUESTED)

    SettingsEvent.CancelReset ->
        copy(notice = SettingsNotice.NONE)

    SettingsEvent.ConfirmReset ->
        if (notice == SettingsNotice.RESET_REQUESTED) {
            SettingsState(
                preferences = UserPreferences.defaults(),
                notice = SettingsNotice.RESET_APPLIED
            )
        } else {
            // A stray ConfirmReset (e.g. replayed event after rotation) must never wipe settings.
            this
        }

    SettingsEvent.DismissNotice ->
        copy(notice = SettingsNotice.NONE)
}
