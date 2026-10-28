package ai.trustshield.android.data.preferences

/**
 * User preferences for TrustShield AI (work package C04, issue #45).
 *
 * **This file is deliberately pure Kotlin.** It has no `android.*` and no `androidx.compose.*`
 * imports, so every rule in it — codec, clamping, bounds, persistence filtering — is exercised by
 * plain JVM unit tests (`SettingsTest`). The Android `SharedPreferences` adapter lives in the
 * sibling file `UserPreferencesStore.kt` and holds no logic of its own.
 *
 * ## Privacy contract (HEAD_ORDERS §2 rule 4, QUALITY_GATES gate 4)
 * Settings persist **choices only**: theme mode, language, sensitivity, haptics, reduced motion.
 * Nothing derived from an analysed message may ever reach this store — no message text, transcript,
 * quote, phone number, UPI id, OTP, PIN, CVV, card number or evidence payload. That is enforced
 * structurally rather than by convention:
 *
 *  - [SettingsKeys.ALL] is the complete, closed set of keys the codec can emit;
 *  - [UserPreferences.toMap] can only ever emit those keys;
 *  - [PreferencePersistence.save] replaces the whole backing store, so a stray or legacy key is
 *    deleted rather than retained;
 *  - `SettingsTest` asserts the persisted key set contains none of the forbidden concepts.
 *
 * Adding a field here means editing [SettingsKeys.ALL] as well, which is exactly the review point
 * where a sensitive field would have to be argued for.
 */

/** Theme selection. Mirrors the Material 3 light/dark choice plus "follow the phone". */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * Language of user-facing copy. New installs follow the system language; unsupported locales resolve
 * to Hinglish. English, Hinglish, and Hindi can also be selected explicitly.
 */
enum class AppLanguage {
    SYSTEM,
    HINGLISH,
    ENGLISH,
    HINDI
}

enum class VoiceRecognitionChoice { AUTOMATIC, OFFLINE_ONLY, ONLINE_PREFERRED }

/** Resolve the persisted choice for rendering without replacing SYSTEM in storage. */
fun resolveLanguage(language: AppLanguage, systemLanguageTag: String?): AppLanguage {
    if (language != AppLanguage.SYSTEM) return language
    val tag = systemLanguageTag?.trim()?.lowercase().orEmpty()
    return when {
        tag == "hi" || tag.startsWith("hi-") -> AppLanguage.HINDI
        tag == "en" || tag.startsWith("en-") -> AppLanguage.ENGLISH
        else -> AppLanguage.HINGLISH
    }
}

/**
 * How readily the deterministic engine escalates an alert.
 *
 * Sensitivity moves **thresholds only** — it never rewrites a score and it can never suppress a
 * verified strong ask (see [SensitivityPolicy]).
 */
enum class Sensitivity {
    LOW,
    BALANCED,
    HIGH
}

/**
 * Score cut-offs used to decide when a deterministic raw score (0..100, produced by the local
 * scorer) becomes a caution or a high-risk presentation.
 *
 * Presentation only: this type carries no verdict and does not exist in the domain layer. The real
 * engine owns the score; these two integers are the only thing settings contributes to it.
 */
data class RiskThresholds(
    val cautionAt: Int,
    val highRiskAt: Int
) {
    /** True when a score reaches the caution band. */
    fun reachesCaution(score: Int): Boolean = score >= cautionAt

    /** True when a score reaches the high-risk band. */
    fun reachesHighRisk(score: Int): Boolean = score >= highRiskAt
}

/**
 * Documented, bounded mapping from [Sensitivity] to thresholds, plus the hard floor that makes
 * "turn a verified strong ask into safe" impossible.
 *
 * ### Bounds (documented on purpose — the product promise is bounded customisation)
 * | Sensitivity | cautionAt | highRiskAt | Effect |
 * |---|---|---|---|
 * | `LOW`      | 45 | 80 | Fewer alerts. Clear, multi-signal scams only. |
 * | `BALANCED` | 35 | 70 | Default. The calibrated band. |
 * | `HIGH`     | 25 | 58 | More alerts; some extra warnings are expected and acceptable. |
 *
 * `cautionAt` must stay inside [CAUTION_BOUNDS] (10..60) and `highRiskAt` inside
 * [HIGH_RISK_BOUNDS] (50..95), with `highRiskAt > cautionAt`; `LOW.cautionAt >
 * BALANCED.cautionAt > HIGH.cautionAt` so higher sensitivity really is more alert-happy.
 *
 * ### The floor
 * [STRONG_ASK_FLOOR_SCORE] = 55. Any raw score for an input containing a **verified strong ask**
 * (OTP/PIN/CVV request, money transfer, remote access, APK install) is raised to at least 55. 55 is
 * above every `cautionAt`, so such an input can never be presented as safe — at any sensitivity.
 * 55 is below `HIGH.highRiskAt` (58), so an ask **alone** still does not become high-risk without
 * corroboration, which is what `docs/android/BEHAVIOR_SPEC.md` requires.
 */
object SensitivityPolicy {

    const val MIN_SCORE: Int = 0
    const val MAX_SCORE: Int = 100

    /** Lowest score a verified strong ask may ever be displayed with. */
    const val STRONG_ASK_FLOOR_SCORE: Int = 55

    val CAUTION_BOUNDS: IntRange = 10..60
    val HIGH_RISK_BOUNDS: IntRange = 50..95

    fun thresholds(sensitivity: Sensitivity): RiskThresholds = when (sensitivity) {
        Sensitivity.LOW -> RiskThresholds(cautionAt = 45, highRiskAt = 80)
        Sensitivity.BALANCED -> RiskThresholds(cautionAt = 35, highRiskAt = 70)
        Sensitivity.HIGH -> RiskThresholds(cautionAt = 25, highRiskAt = 58)
    }

    /**
     * Clamps a raw engine score into [MIN_SCORE]..[MAX_SCORE] and, when [hasVerifiedStrongAsk],
     * lifts it to at least [STRONG_ASK_FLOOR_SCORE].
     *
     * // SEAM: `rawScore` and `hasVerifiedStrongAsk` come from the local deterministic scorer
     * // (package A05, `core/score`). Until A05 lands this is a pure Int/Boolean contract, so
     * // nothing here invents a domain verdict type.
     */
    fun adjustScore(rawScore: Int, hasVerifiedStrongAsk: Boolean): Int {
        val clamped = rawScore.coerceIn(MIN_SCORE, MAX_SCORE)
        return if (hasVerifiedStrongAsk) maxOf(clamped, STRONG_ASK_FLOOR_SCORE) else clamped
    }

    /** True when [sensitivity] is within the documented bounds and internally consistent. */
    fun isWithinBounds(sensitivity: Sensitivity): Boolean {
        val t = thresholds(sensitivity)
        return t.cautionAt in CAUTION_BOUNDS &&
            t.highRiskAt in HIGH_RISK_BOUNDS &&
            t.highRiskAt > t.cautionAt
    }
}

/**
 * The exact preference keys this app may write. Closed set by design — see the file header.
 */
object SettingsKeys {

    const val THEME_MODE = "settings.theme_mode"
    const val LANGUAGE = "settings.language"
    const val SENSITIVITY = "settings.sensitivity"
    const val HAPTICS = "settings.haptics"
    const val REDUCED_MOTION = "settings.reduced_motion"
    const val VOICE_RECOGNITION = "settings.voice_recognition"
    const val SPOKEN_VERDICT_MUTED = "settings.spoken_verdict_muted"
    const val SPOKEN_VERDICT_RATE = "settings.spoken_verdict_rate"

    /** Complete allowlist, in the deterministic order used by [UserPreferences.toMap]. */
    val ALL: List<String> = listOf(
        THEME_MODE,
        LANGUAGE,
        SENSITIVITY,
        HAPTICS,
        REDUCED_MOTION,
        VOICE_RECOGNITION,
        SPOKEN_VERDICT_MUTED,
        SPOKEN_VERDICT_RATE
    )

    /**
     * Concepts that must never appear in a persisted key. Used by `SettingsTest` as a guard: if a
     * future change adds `settings.last_message_text`, that test fails before the code ships.
     */
    val FORBIDDEN_KEY_CONCEPTS: List<String> = listOf(
        "text",
        "message",
        "transcript",
        "quote",
        "otp",
        "pin",
        "cvv",
        "card",
        "phone",
        "number",
        "upi",
        "evidence",
        "audio",
        "history",
        "contact",
        "raw"
    )

    fun isAllowed(key: String): Boolean = ALL.contains(key)
}

/**
 * Immutable settings model. Defaults follow the system language, with BALANCED sensitivity and
 * SYSTEM theme, haptics on, reduced motion off.
 */
data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val sensitivity: Sensitivity = Sensitivity.BALANCED,
    val haptics: Boolean = true,
    val reducedMotion: Boolean = false,
    val voiceRecognition: VoiceRecognitionChoice = VoiceRecognitionChoice.AUTOMATIC,
    val spokenVerdictMuted: Boolean = false,
    val spokenVerdictRate: Float = 1.0f
) {

    /** Deterministic, ordered, string-only encoding. The only shape that is ever persisted. */
    fun toMap(): Map<String, String> = linkedMapOf(
        SettingsKeys.THEME_MODE to themeMode.name,
        SettingsKeys.LANGUAGE to language.name,
        SettingsKeys.SENSITIVITY to sensitivity.name,
        SettingsKeys.HAPTICS to haptics.toString(),
        SettingsKeys.REDUCED_MOTION to reducedMotion.toString(),
        SettingsKeys.VOICE_RECOGNITION to voiceRecognition.name,
        SettingsKeys.SPOKEN_VERDICT_MUTED to spokenVerdictMuted.toString(),
        SettingsKeys.SPOKEN_VERDICT_RATE to spokenVerdictRate.coerceIn(0.75f, 1.25f).toString()
    )

    /**
     * Idempotent normalisation: parse our own encoding back. Unknown or out-of-range persisted
     * values become defaults instead of throwing, so a corrupted store can never brick the app.
     */
    fun normalized(): UserPreferences = fromMap(toMap())

    companion object {

        /** The one default instance. [defaults] is a function for call-site clarity. */
        val DEFAULTS: UserPreferences = UserPreferences()

        fun defaults(): UserPreferences = DEFAULTS

        /**
         * Tolerant decoder. Never throws:
         *  - `null`, blank or unknown values fall back to the field default;
         *  - unknown / legacy keys are ignored entirely;
         *  - values are matched case-insensitively after trimming.
         */
        fun fromMap(raw: Map<String, String?>): UserPreferences {
            val base = DEFAULTS
            return UserPreferences(
                themeMode = parseEnum(raw[SettingsKeys.THEME_MODE], base.themeMode),
                language = parseEnum(raw[SettingsKeys.LANGUAGE], base.language),
                sensitivity = parseEnum(raw[SettingsKeys.SENSITIVITY], base.sensitivity),
                haptics = parseBoolean(raw[SettingsKeys.HAPTICS], base.haptics),
                reducedMotion = parseBoolean(raw[SettingsKeys.REDUCED_MOTION], base.reducedMotion),
                voiceRecognition = parseEnum(raw[SettingsKeys.VOICE_RECOGNITION], base.voiceRecognition),
                spokenVerdictMuted = parseBoolean(raw[SettingsKeys.SPOKEN_VERDICT_MUTED], base.spokenVerdictMuted),
                spokenVerdictRate = raw[SettingsKeys.SPOKEN_VERDICT_RATE]?.toFloatOrNull()
                    ?.takeIf { it.isFinite() }?.coerceIn(0.75f, 1.25f) ?: base.spokenVerdictRate
            )
        }

        private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T {
            val candidate = raw?.trim()?.uppercase() ?: return fallback
            if (candidate.isEmpty()) return fallback
            return enumValues<T>().firstOrNull { it.name == candidate } ?: fallback
        }

        private fun parseBoolean(raw: String?, fallback: Boolean): Boolean =
            when (raw?.trim()?.lowercase()) {
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> fallback
            }
    }
}

/**
 * The minimal storage primitive the settings codec needs. Pure (no `android.*`) so the persistence
 * path can be unit-tested with an in-memory fake.
 *
 * Contract: [write] **replaces** the entire contents of the store. Anything not in the supplied
 * map must not survive, which is what makes "only these keys, ever" true in practice.
 */
interface SettingsStorage {
    fun readAll(): Map<String, String?>
    fun write(entries: Map<String, String>)
}

/**
 * Save/load logic for preferences, independent of the storage engine.
 *
 * Deliberately thin, but it is the only place that decides what gets written: it always writes
 * exactly [UserPreferences.toMap], filtered through [SettingsKeys.isAllowed].
 */
class PreferencePersistence(private val storage: SettingsStorage) {

    /** Reads current settings; corrupt or legacy content degrades to defaults. */
    fun load(): UserPreferences = UserPreferences.fromMap(storage.readAll())

    /** Writes the full allowlisted map, replacing whatever was there before. */
    fun save(preferences: UserPreferences): UserPreferences {
        val payload = preferences.normalized().toMap()
            .filterKeys { SettingsKeys.isAllowed(it) }
        storage.write(payload)
        return preferences.normalized()
    }

    /** Restores shipping defaults and returns them. */
    fun reset(): UserPreferences = save(UserPreferences.defaults())
}
