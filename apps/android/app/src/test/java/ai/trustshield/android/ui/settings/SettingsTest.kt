package ai.trustshield.android.ui.settings

import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.PreferencePersistence
import ai.trustshield.android.data.preferences.RiskThresholds
import ai.trustshield.android.data.preferences.Sensitivity
import ai.trustshield.android.data.preferences.SensitivityPolicy
import ai.trustshield.android.data.preferences.SettingsKeys
import ai.trustshield.android.data.preferences.SettingsStorage
import ai.trustshield.android.data.preferences.ThemeMode
import ai.trustshield.android.data.preferences.UserPreferences
import ai.trustshield.android.data.preferences.resolveLanguage
import ai.trustshield.android.ui.theme.ToneCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for work package C04 (issue #45): settings model, codec, persistence filtering,
 * sensitivity bounds and the reset flow.
 *
 * No device, emulator or Robolectric: everything under test is android-free by construction.
 */
class SettingsTest {

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    /** In-memory [SettingsStorage] standing in for SharedPreferences. */
    private class FakeStorage(initial: Map<String, String?> = emptyMap()) : SettingsStorage {
        var contents: MutableMap<String, String?> = LinkedHashMap(initial)
        var writeCount: Int = 0

        override fun readAll(): Map<String, String?> = LinkedHashMap(contents)

        override fun write(entries: Map<String, String>) {
            writeCount++
            // Contract: write replaces the whole store.
            contents = LinkedHashMap(entries)
        }
    }

    private fun preferences(
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        language: AppLanguage = AppLanguage.SYSTEM,
        sensitivity: Sensitivity = Sensitivity.BALANCED,
        haptics: Boolean = true,
        reducedMotion: Boolean = false
    ) = UserPreferences(themeMode, language, sensitivity, haptics, reducedMotion)

    // ---------------------------------------------------------------------------------------
    // Defaults
    // ---------------------------------------------------------------------------------------

    @Test
    fun defaultsUseSystemLanguageBalancedSensitivityAndSystemTheme() {
        val d = UserPreferences.defaults()
        assertEquals(AppLanguage.SYSTEM, d.language)
        assertEquals(Sensitivity.BALANCED, d.sensitivity)
        assertEquals(ThemeMode.SYSTEM, d.themeMode)
    }

    @Test
    fun systemLanguageResolvesHindiEnglishAndOtherLocalesToProductOptions() {
        assertEquals(AppLanguage.HINDI, resolveLanguage(AppLanguage.SYSTEM, "hi-IN"))
        assertEquals(AppLanguage.ENGLISH, resolveLanguage(AppLanguage.SYSTEM, "en-US"))
        assertEquals(AppLanguage.HINGLISH, resolveLanguage(AppLanguage.SYSTEM, "fr-FR"))
        assertEquals(AppLanguage.HINGLISH, resolveLanguage(AppLanguage.SYSTEM, null))
        assertEquals(AppLanguage.HINGLISH, resolveLanguage(AppLanguage.HINGLISH, "en-US"))
    }

    @Test
    fun defaultsKeepHapticsOnAndReducedMotionOff() {
        val d = UserPreferences.defaults()
        assertTrue("haptics default on", d.haptics)
        assertFalse("reduced motion default off", d.reducedMotion)
        assertEquals(UserPreferences.DEFAULTS, d)
    }

    // ---------------------------------------------------------------------------------------
    // Codec
    // ---------------------------------------------------------------------------------------

    @Test
    fun everyCombinationRoundTripsThroughTheCodec() {
        var checked = 0
        for (theme in ThemeMode.values()) {
            for (language in AppLanguage.values()) {
                for (sensitivity in Sensitivity.values()) {
                    for (haptics in booleanArrayOf(true, false)) {
                        for (reducedMotion in booleanArrayOf(true, false)) {
                            val original = preferences(
                                themeMode = theme,
                                language = language,
                                sensitivity = sensitivity,
                                haptics = haptics,
                                reducedMotion = reducedMotion
                            )
                            val decoded = UserPreferences.fromMap(original.toMap())
                            assertEquals("round trip for $original", original, decoded)
                            checked++
                        }
                    }
                }
            }
        }
        assertEquals(ThemeMode.entries.size * AppLanguage.entries.size * Sensitivity.entries.size * 2 * 2, checked)
    }

    @Test
    fun toMapIsDeterministicAndEmitsKeysInDocumentedOrder() {
        val p = preferences(
            themeMode = ThemeMode.DARK,
            language = AppLanguage.ENGLISH,
            sensitivity = Sensitivity.HIGH,
            haptics = false,
            reducedMotion = true
        )
        val first = p.toMap()
        val second = p.toMap()
        assertEquals(first, second)
        assertEquals(SettingsKeys.ALL, first.keys.toList())
    }

    @Test
    fun defaultsEncodeToTheExactCanonicalString() {
        val encoded = UserPreferences.defaults().toMap()
            .entries
            .joinToString("|") { "${it.key}=${it.value}" }
        assertEquals(
            "settings.theme_mode=SYSTEM" +
                "|settings.language=SYSTEM" +
                "|settings.sensitivity=BALANCED" +
                "|settings.haptics=true" +
                "|settings.reduced_motion=false" +
                "|settings.voice_recognition=AUTOMATIC" +
                "|settings.spoken_verdict_muted=false" +
                "|settings.spoken_verdict_rate=1.0",
            encoded
        )
    }

    @Test
    fun everyDecodedValueIsACanonicalEnumNameOrBoolean() {
        val map = preferences(
            themeMode = ThemeMode.DARK,
            language = AppLanguage.ENGLISH,
            sensitivity = Sensitivity.LOW
        ).toMap()
        assertEquals(setOf("SYSTEM", "LIGHT", "DARK"), ThemeMode.values().map { it.name }.toSet())
        assertTrue(map[SettingsKeys.THEME_MODE] in setOf("SYSTEM", "LIGHT", "DARK"))
        assertTrue(map[SettingsKeys.LANGUAGE] in setOf("SYSTEM", "HINGLISH", "ENGLISH", "HINDI"))
        assertTrue(map[SettingsKeys.SENSITIVITY] in setOf("LOW", "BALANCED", "HIGH"))
        assertTrue(map[SettingsKeys.HAPTICS] in setOf("true", "false"))
        assertTrue(map[SettingsKeys.REDUCED_MOTION] in setOf("true", "false"))
        assertTrue(map[SettingsKeys.VOICE_RECOGNITION] in ai.trustshield.android.data.preferences.VoiceRecognitionChoice.values().map { it.name })
        assertTrue(map[SettingsKeys.SPOKEN_VERDICT_MUTED] in setOf("true", "false"))
        assertTrue(map[SettingsKeys.SPOKEN_VERDICT_RATE]?.toFloat()?.let { it in 0.75f..1.25f } == true)
    }

    @Test
    fun unknownAndLegacyKeysAreIgnoredSafely() {
        val legacy = mapOf(
            "settings.theme" to "DARK",                 // pre-rename key
            "prefs.language" to "ENGLISH",              // another legacy shape
            "legacy.haptics_enabled" to "false",
            "settings.unread_count" to "12",
            "settings.haptics" to "false"               // the only real key present
        )
        val decoded = UserPreferences.fromMap(legacy)
        assertEquals(UserPreferences.defaults().copy(haptics = false), decoded)
        assertEquals(UserPreferences.defaults().themeMode, decoded.themeMode)
        assertEquals(UserPreferences.defaults().language, decoded.language)
    }

    @Test
    fun corruptValuesClampToDefaultsWithoutCrashing() {
        val corrupt = listOf(
            "",
            "   ",
            "banana",
            "999",
            "-1",
            "null",
            "\u0000",
            "{\"theme\":\"dark\"}",
            "SYSTEM;DROP TABLE settings",
            "DARK\nLIGHT"
        )
        for (garbage in corrupt) {
            val map = mapOf(
                SettingsKeys.THEME_MODE to garbage,
                SettingsKeys.LANGUAGE to garbage,
                SettingsKeys.SENSITIVITY to garbage,
                SettingsKeys.HAPTICS to garbage,
                SettingsKeys.REDUCED_MOTION to garbage
            )
            val decoded = UserPreferences.fromMap(map)
            assertEquals("garbage '$garbage' must clamp to defaults", UserPreferences.defaults(), decoded)
        }
    }

    @Test
    fun nullValuesFallBackToDefaults() {
        val map = mapOf<String, String?>(
            SettingsKeys.THEME_MODE to null,
            SettingsKeys.LANGUAGE to null,
            SettingsKeys.SENSITIVITY to null,
            SettingsKeys.HAPTICS to null,
            SettingsKeys.REDUCED_MOTION to null
        )
        assertEquals(UserPreferences.defaults(), UserPreferences.fromMap(map))
    }

    @Test
    fun knownValuesAreAcceptedCaseInsensitivelyAndTrimmed() {
        val map = mapOf(
            SettingsKeys.THEME_MODE to "  dark ",
            SettingsKeys.LANGUAGE to "english",
            SettingsKeys.SENSITIVITY to "High",
            SettingsKeys.HAPTICS to " FALSE "
        )
        assertEquals(
            preferences(
                themeMode = ThemeMode.DARK,
                language = AppLanguage.ENGLISH,
                sensitivity = Sensitivity.HIGH,
                haptics = false
            ),
            UserPreferences.fromMap(map)
        )
    }

    @Test
    fun emptyMapIsTheDefaultSettings() {
        assertEquals(UserPreferences.defaults(), UserPreferences.fromMap(emptyMap()))
    }

    @Test
    fun normalizationIsIdempotent() {
        val once = preferences(
            themeMode = ThemeMode.LIGHT,
            language = AppLanguage.ENGLISH,
            sensitivity = Sensitivity.LOW,
            haptics = false,
            reducedMotion = true
        ).normalized()
        assertEquals(once, once.normalized())
    }

    // ---------------------------------------------------------------------------------------
    // Persistence filtering (the privacy proof)
    // ---------------------------------------------------------------------------------------

    @Test
    fun persistedKeySetIsExactlyTheDocumentedFive() {
        assertEquals(8, SettingsKeys.ALL.size)
        assertEquals(SettingsKeys.ALL.size, SettingsKeys.ALL.toSet().size)
        assertEquals(SettingsKeys.ALL.toSet(), UserPreferences.defaults().toMap().keys)
    }

    @Test
    fun persistedKeySetContainsNoSensitiveConcept() {
        // Guard: a future `settings.last_message_text` (or OTP/UPI/phone/transcript field) fails here.
        for (key in SettingsKeys.ALL) {
            val lowered = key.lowercase()
            for (concept in SettingsKeys.FORBIDDEN_KEY_CONCEPTS) {
                assertFalse(
                    "persisted key '$key' must not carry the '$concept' concept",
                    lowered.contains(concept)
                )
            }
        }
    }

    @Test
    fun everyPersistedKeyIsInTheAllowlistAndNothingElseIs() {
        val stored = FakeStorage()
        val persistence = PreferencePersistence(stored)
        persistence.save(preferences(language = AppLanguage.ENGLISH))

        assertEquals(SettingsKeys.ALL.toSet(), stored.contents.keys)
        for (key in stored.contents.keys) {
            assertTrue("'$key' must be allowlisted", SettingsKeys.isAllowed(key))
        }
        assertFalse(SettingsKeys.isAllowed("settings.message_text"))
        assertFalse(SettingsKeys.isAllowed("settings.last_quote"))
        assertFalse(SettingsKeys.isAllowed(""))
    }

    @Test
    fun saveReplacesTheWholeStoreAndDeletesStraySensitiveKeys() {
        // Simulate a bad/older build that wrote analysed content into the settings file.
        val stored = FakeStorage(
            mapOf(
                "settings.last_message_text" to "Jo OTP aaya hai woh batayein",
                "settings.quote" to "abhi turant karein",
                "settings.phone" to "+919876543210",
                "settings.upi" to "scammer@ybl",
                "settings.otp" to "482913",
                "settings.transcript" to "hello",
                "settings.haptics" to "false"
            )
        )
        val persistence = PreferencePersistence(stored)
        persistence.save(UserPreferences.defaults())

        assertEquals(SettingsKeys.ALL.toSet(), stored.contents.keys)
        val joined = stored.contents.entries.joinToString("|") { "${it.key}=${it.value}" }
        assertFalse(joined.contains("OTP aaya", ignoreCase = true))
        assertFalse(joined.contains("scarlm", ignoreCase = true))
        assertFalse(joined.contains("+919876543210"))
        assertFalse(joined.contains("scammer@ybl"))
        assertFalse(joined.contains("482913"))
    }

    @Test
    fun loadOfCorruptStoreDegradesToDefaultsWithoutThrowing() {
        val stored = FakeStorage(
            mapOf(
                SettingsKeys.THEME_MODE to "PURPLE",
                SettingsKeys.LANGUAGE to "KLINGON",
                SettingsKeys.SENSITIVITY to "999999999999999999999",
                SettingsKeys.HAPTICS to "",
                SettingsKeys.REDUCED_MOTION to "maybe"
            )
        )
        val loaded = PreferencePersistence(stored).load()
        assertEquals(UserPreferences.defaults(), loaded)
        // Loading must not rewrite the file behind the user's back.
        assertEquals(0, stored.writeCount)
    }

    @Test
    fun saveThenLoadRoundTripsThroughStorage() {
        val stored = FakeStorage()
        val persistence = PreferencePersistence(stored)
        val saved = preferences(
            themeMode = ThemeMode.DARK,
            language = AppLanguage.ENGLISH,
            sensitivity = Sensitivity.HIGH,
            haptics = false,
            reducedMotion = true
        )
        persistence.save(saved)
        assertEquals(saved, persistence.load())
    }

    @Test
    fun resetRestoresDefaultsInTheModel() {
        val stored = FakeStorage()
        val persistence = PreferencePersistence(stored)
        persistence.save(
            preferences(
                themeMode = ThemeMode.DARK,
                language = AppLanguage.ENGLISH,
                sensitivity = Sensitivity.LOW,
                haptics = false,
                reducedMotion = true
            )
        )
        val afterReset = persistence.reset()
        assertEquals(UserPreferences.defaults(), afterReset)
        assertEquals(UserPreferences.defaults(), persistence.load())
        assertEquals(AppLanguage.SYSTEM, persistence.load().language)
        assertEquals(Sensitivity.BALANCED, persistence.load().sensitivity)
        assertEquals(ThemeMode.SYSTEM, persistence.load().themeMode)
    }

    @Test
    fun resetIsIdempotent() {
        val persistence = PreferencePersistence(FakeStorage())
        val once = persistence.reset()
        val twice = persistence.reset()
        assertEquals(once, twice)
        assertEquals(UserPreferences.defaults(), twice)
    }

    // ---------------------------------------------------------------------------------------
    // Sensitivity bounds and the strong-ask floor
    // ---------------------------------------------------------------------------------------

    @Test
    fun everySensitivityStaysInsideDocumentedBounds() {
        for (sensitivity in Sensitivity.values()) {
            val t: RiskThresholds = SensitivityPolicy.thresholds(sensitivity)
            assertTrue(
                "$sensitivity cautionAt ${t.cautionAt} out of ${SensitivityPolicy.CAUTION_BOUNDS}",
                t.cautionAt in SensitivityPolicy.CAUTION_BOUNDS
            )
            assertTrue(
                "$sensitivity highRiskAt ${t.highRiskAt} out of ${SensitivityPolicy.HIGH_RISK_BOUNDS}",
                t.highRiskAt in SensitivityPolicy.HIGH_RISK_BOUNDS
            )
            assertTrue("$sensitivity highRiskAt must exceed cautionAt", t.highRiskAt > t.cautionAt)
            assertTrue("$sensitivity must be within bounds", SensitivityPolicy.isWithinBounds(sensitivity))
        }
    }

    @Test
    fun sensitivityReallyMovesAlertLikelihood() {
        // Lower thresholds for higher sensitivity: strict monotonicity, so HIGH warns at least as
        // often as BALANCED, which warns at least as often as LOW, at every score.
        val low = SensitivityPolicy.thresholds(Sensitivity.LOW)
        val balanced = SensitivityPolicy.thresholds(Sensitivity.BALANCED)
        val high = SensitivityPolicy.thresholds(Sensitivity.HIGH)

        assertTrue("LOW cautionAt > BALANCED cautionAt", low.cautionAt > balanced.cautionAt)
        assertTrue("BALANCED cautionAt > HIGH cautionAt", balanced.cautionAt > high.cautionAt)
        assertTrue("LOW highRiskAt > BALANCED highRiskAt", low.highRiskAt > balanced.highRiskAt)
        assertTrue("BALANCED highRiskAt > HIGH highRiskAt", balanced.highRiskAt > high.highRiskAt)

        for (score in SensitivityPolicy.MIN_SCORE..SensitivityPolicy.MAX_SCORE) {
            // Lower threshold == warns more often. HIGH must be a superset of BALANCED, which must
            // be a superset of LOW: anything that warns at a stricter setting also warns at a
            // looser one.
            if (balanced.reachesCaution(score)) {
                assertTrue("HIGH must warn whenever BALANCED does at $score", high.reachesCaution(score))
            }
            if (low.reachesCaution(score)) {
                assertTrue("BALANCED must warn whenever LOW does at $score", balanced.reachesCaution(score))
            }
            if (balanced.reachesHighRisk(score)) {
                assertTrue("HIGH must escalate whenever BALANCED does at $score", high.reachesHighRisk(score))
            }
            if (low.reachesHighRisk(score)) {
                assertTrue("BALANCED must escalate whenever LOW does at $score", balanced.reachesHighRisk(score))
            }
        }

        // And the knob visibly does something: a mid score is silent at LOW but warns at HIGH.
        val midScore = high.cautionAt
        assertTrue(high.reachesCaution(midScore))
        assertFalse(low.reachesCaution(midScore))
    }

    @Test
    fun rawScoresAreClampedIntoRange() {
        assertEquals(SensitivityPolicy.MIN_SCORE, SensitivityPolicy.adjustScore(-999, hasVerifiedStrongAsk = false))
        assertEquals(SensitivityPolicy.MIN_SCORE, SensitivityPolicy.adjustScore(-1, hasVerifiedStrongAsk = false))
        assertEquals(SensitivityPolicy.MAX_SCORE, SensitivityPolicy.adjustScore(101, hasVerifiedStrongAsk = false))
        assertEquals(SensitivityPolicy.MAX_SCORE, SensitivityPolicy.adjustScore(Int.MAX_VALUE, hasVerifiedStrongAsk = false))
        assertEquals(42, SensitivityPolicy.adjustScore(42, hasVerifiedStrongAsk = false))
    }

    @Test
    fun verifiedStrongAskCanNeverBePresentedAsSafeAtAnySensitivity() {
        for (sensitivity in Sensitivity.values()) {
            val thresholds = SensitivityPolicy.thresholds(sensitivity)
            for (rawScore in intArrayOf(-5, 0, 1, 10, 20, 30, 40, 54)) {
                val adjusted = SensitivityPolicy.adjustScore(rawScore, hasVerifiedStrongAsk = true)
                assertTrue(
                    "strong ask at raw $rawScore must not read as safe under $sensitivity",
                    thresholds.reachesCaution(adjusted)
                )
                assertTrue(adjusted >= SensitivityPolicy.STRONG_ASK_FLOOR_SCORE)
            }
        }
    }

    @Test
    fun strongAskAloneDoesNotBecomeHighRisk() {
        // BEHAVIOR_SPEC: a validated ask alone is at most caution until another stage corroborates.
        for (sensitivity in Sensitivity.values()) {
            val thresholds = SensitivityPolicy.thresholds(sensitivity)
            val askOnly = SensitivityPolicy.adjustScore(0, hasVerifiedStrongAsk = true)
            assertFalse(
                "ask alone must stay below high risk under $sensitivity",
                thresholds.reachesHighRisk(askOnly)
            )
        }
    }

    @Test
    fun strongAskFloorNeverLowersAnAlreadyHigherScore() {
        for (rawScore in intArrayOf(60, 75, 90, 100)) {
            assertEquals(rawScore, SensitivityPolicy.adjustScore(rawScore, hasVerifiedStrongAsk = true))
        }
    }

    @Test
    fun lowSensitivityStillLiftsAStrongAskAboveTheFloor() {
        val adjusted = SensitivityPolicy.adjustScore(12, hasVerifiedStrongAsk = true)
        assertEquals(SensitivityPolicy.STRONG_ASK_FLOOR_SCORE, adjusted)
        assertTrue(SensitivityPolicy.thresholds(Sensitivity.LOW).reachesCaution(adjusted))
    }

    // ---------------------------------------------------------------------------------------
    // Reducer: selection, reset confirmation, notices
    // ---------------------------------------------------------------------------------------

    @Test
    fun initialStateMirrorsPreferences() {
        val state = SettingsState(preferences(themeMode = ThemeMode.DARK))
        assertEquals(ThemeMode.DARK, state.themeMode)
        assertFalse(state.resetConfirmationVisible)
        assertFalse(state.resetApplied)
        assertFalse(state.isAtDefaults)
        assertTrue(SettingsState().isAtDefaults)
    }

    @Test
    fun eachSelectionEventUpdatesOnlyItsField() {
        val start = SettingsState()

        val theme = start.reduce(SettingsEvent.SelectTheme(ThemeMode.DARK))
        assertEquals(ThemeMode.DARK, theme.themeMode)
        assertEquals(AppLanguage.SYSTEM, theme.language)
        assertEquals(Sensitivity.BALANCED, theme.sensitivity)
        assertTrue(theme.haptics)

        val language = start.reduce(SettingsEvent.SelectLanguage(AppLanguage.ENGLISH))
        assertEquals(AppLanguage.ENGLISH, language.language)
        assertEquals(ThemeMode.SYSTEM, language.themeMode)

        val sensitivity = start.reduce(SettingsEvent.SelectSensitivity(Sensitivity.HIGH))
        assertEquals(Sensitivity.HIGH, sensitivity.sensitivity)

        val haptics = start.reduce(SettingsEvent.SetHaptics(false))
        assertFalse(haptics.haptics)

        val motion = start.reduce(SettingsEvent.SetReducedMotion(true))
        assertTrue(motion.reducedMotion)
    }

    @Test
    fun requestResetOnlyOpensTheConfirmation() {
        val state = SettingsState(preferences(themeMode = ThemeMode.DARK))
            .reduce(SettingsEvent.RequestReset)
        assertTrue(state.resetConfirmationVisible)
        assertEquals(ThemeMode.DARK, state.themeMode)  // nothing applied yet
        assertEquals(SettingsNotice.RESET_REQUESTED, state.notice)
    }

    @Test
    fun cancelResetLeavesPreferencesUntouched() {
        val dirty = SettingsState(
            preferences = preferences(
                themeMode = ThemeMode.DARK,
                language = AppLanguage.ENGLISH,
                sensitivity = Sensitivity.LOW,
                haptics = false,
                reducedMotion = true
            )
        )
        val state = dirty.reduce(SettingsEvent.RequestReset).reduce(SettingsEvent.CancelReset)
        assertEquals(dirty.preferences, state.preferences)
        assertEquals(SettingsNotice.NONE, state.notice)
        assertFalse(state.resetConfirmationVisible)
    }

    @Test
    fun confirmResetRequiresAnOpenConfirmation() {
        val dirty = SettingsState(preferences(themeMode = ThemeMode.DARK, haptics = false))
        val stray = dirty.reduce(SettingsEvent.ConfirmReset)
        assertEquals("a stray ConfirmReset must not wipe settings", dirty, stray)
    }

    @Test
    fun confirmResetAppliesDefaultsAndShowsNotice() {
        val state = SettingsState(
            preferences = preferences(
                themeMode = ThemeMode.DARK,
                language = AppLanguage.ENGLISH,
                sensitivity = Sensitivity.HIGH,
                haptics = false,
                reducedMotion = true
            )
        )
            .reduce(SettingsEvent.RequestReset)
            .reduce(SettingsEvent.ConfirmReset)

        assertEquals(UserPreferences.defaults(), state.preferences)
        assertEquals(AppLanguage.SYSTEM, state.language)
        assertEquals(Sensitivity.BALANCED, state.sensitivity)
        assertEquals(ThemeMode.SYSTEM, state.themeMode)
        assertTrue(state.haptics)
        assertFalse(state.reducedMotion)
        assertEquals(SettingsNotice.RESET_APPLIED, state.notice)
        assertTrue(state.resetApplied)
        assertTrue(state.isAtDefaults)
    }

    @Test
    fun dismissNoticeClearsTransientStateOnly() {
        val afterReset = SettingsState()
            .reduce(SettingsEvent.RequestReset)
            .reduce(SettingsEvent.ConfirmReset)
        val cleared = afterReset.reduce(SettingsEvent.DismissNotice)
        assertEquals(SettingsNotice.NONE, cleared.notice)
        assertEquals(afterReset.preferences, cleared.preferences)
    }

    @Test
    fun selectionClearsAPendingConfirmation() {
        val state = SettingsState()
            .reduce(SettingsEvent.RequestReset)
            .reduce(SettingsEvent.SelectTheme(ThemeMode.LIGHT))
        assertEquals(SettingsNotice.NONE, state.notice)
        assertEquals(ThemeMode.LIGHT, state.themeMode)
    }

    @Test
    fun changedSelectionIsDetectableSoTheCallerKnowsToPersist() {
        val start = SettingsState()
        val changed = start.reduce(SettingsEvent.SelectSensitivity(Sensitivity.HIGH))
        assertNotEquals(start.preferences, changed.preferences)
    }

    // ---------------------------------------------------------------------------------------
    // Copy / product guards
    // ---------------------------------------------------------------------------------------

    @Test
    fun systemIsTheDefaultLanguageAndExplicitChoicesResolve() {
        val state = SettingsState()
        assertEquals(AppLanguage.SYSTEM, state.language)
        assertEquals(
            SettingsCopy.screenTitle.forLanguage(
                resolveLanguage(AppLanguage.SYSTEM, java.util.Locale.getDefault().toLanguageTag())
            ),
            state.text(SettingsCopy.screenTitle)
        )

        val hinglish = state.reduce(SettingsEvent.SelectLanguage(AppLanguage.HINGLISH))
        assertEquals(SettingsCopy.screenTitle.hinglish, hinglish.text(SettingsCopy.screenTitle))
        val english = state.reduce(SettingsEvent.SelectLanguage(AppLanguage.ENGLISH))
        assertEquals(
            SettingsCopy.screenTitle.english,
            english.text(SettingsCopy.screenTitle)
        )
        val hindi = state.reduce(SettingsEvent.SelectLanguage(AppLanguage.HINDI))
        assertEquals("सेटिंग्स", hindi.text(SettingsCopy.screenTitle))
        assertEquals("जानकारी", ToneCopy("Jaankari", "Information").forLanguage(AppLanguage.HINDI))
    }

    @Test
    fun everyCopyStringHasBothLanguageVariants() {
        for (setting in SettingsCopy.all()) {
            assertTrue("Hinglish copy must not be blank: $setting", setting.hinglish.isNotBlank())
            assertTrue("English copy must not be blank: $setting", setting.english.isNotBlank())
        }
    }

    @Test
    fun narrativeCopyIsGenuinelyTranslatedNotJustDuplicated() {
        // Short section labels are the same word in both languages ("Theme", "Settings"); the copy
        // that actually explains something must be written twice, once per language.
        val narrative = listOf(
            SettingsCopy.screenSubtitle,
            SettingsCopy.languageSection,
            SettingsCopy.languageHint,
            SettingsCopy.sensitivityLowDetail,
            SettingsCopy.sensitivityHighDetail,
            SettingsCopy.sensitivitySafetyNote,
            SettingsCopy.hapticsDetail,
            SettingsCopy.reducedMotionDetail,
            SettingsCopy.resetDialogBody,
            SettingsCopy.resetApplied,
            SettingsCopy.privacyNote
        )
        for (setting in narrative) {
            assertNotEquals("copy must be translated: $setting", setting.hinglish, setting.english)
        }
    }

    @Test
    fun noCopyOffersATurnOffAllWarningsPreset() {
        val banned = listOf(
            "turn off all",
            "disable all",
            "all warnings off",
            "sab warnings band",
            "warnings band karein",
            "alert band karein",
            "no warnings mode",
            "silent mode"
        )
        for (setting in SettingsCopy.all()) {
            for (variant in listOf(setting.hinglish, setting.english)) {
                val lowered = variant.lowercase()
                for (phrase in banned) {
                    assertFalse(
                        "copy '$variant' must not offer the '$phrase' preset",
                        lowered.contains(phrase)
                    )
                }
            }
        }
    }

    @Test
    fun sensitivityCopyStatesThatWarningsCannotBeSwitchedOff() {
        val note = SettingsCopy.sensitivitySafetyNote
        val hinglish = note.hinglish.lowercase()
        val english = note.english.lowercase()

        assertTrue("Hinglish note must say there is no off switch", hinglish.contains("band karne"))
        assertTrue("Hinglish note must mention the no-option rule", hinglish.contains("koi option nahi"))
        assertTrue("Hinglish note must name the strong-ask guarantee", hinglish.contains("safe"))
        assertTrue("English note must say there is no option", english.contains("no option"))
        assertTrue("English note must say never safe", english.contains("never"))
    }

    @Test
    fun privacyNoteTellsTheUserNoSensitiveDataIsStored() {
        val hinglish = SettingsCopy.privacyNote.hinglish.lowercase()
        val english = SettingsCopy.privacyNote.english.lowercase()
        for (word in listOf("otp", "phone", "upi")) {
            assertTrue("Hinglish privacy note must mention $word", hinglish.contains(word))
            assertTrue("English privacy note must mention $word", english.contains(word))
        }
    }

    @Test
    fun preferenceModelHasNoUndocumentedFlag() {
        // Structural guard: the persisted model has exactly the documented fields, so no
        // "warningsEnabled = false" style flag can be added without failing here and in
        // [persistedKeySetContainsNoSensitiveConcept].
        val fields = UserPreferences::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf("themeMode", "language", "sensitivity", "haptics", "reducedMotion", "voiceRecognition", "spokenVerdictMuted", "spokenVerdictRate"),
            fields
        )
    }

    @Test
    fun sensitivityHasExactlyThreeBoundedModes() {
        assertEquals(
            listOf(Sensitivity.LOW, Sensitivity.BALANCED, Sensitivity.HIGH),
            Sensitivity.values().toList()
        )
        // No "OFF" mode exists anywhere in the enum.
        assertFalse(Sensitivity.values().any { it.name.contains("OFF") })
    }

    @Test
    fun voiceChoicesMuteAndRatePersistWithinBounds() {
        val chosen = UserPreferences.defaults().copy(
            voiceRecognition = ai.trustshield.android.data.preferences.VoiceRecognitionChoice.OFFLINE_ONLY,
            spokenVerdictMuted = true,
            spokenVerdictRate = 1.25f,
        )
        assertEquals(chosen, UserPreferences.fromMap(chosen.toMap()))
        assertEquals(
            0.75f,
            UserPreferences.fromMap(mapOf(SettingsKeys.SPOKEN_VERDICT_RATE to "0.1")).spokenVerdictRate,
        )
    }
}
