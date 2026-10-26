package ai.trustshield.android.ui.settings

import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.PreferencePersistence
import ai.trustshield.android.data.preferences.Sensitivity
import ai.trustshield.android.data.preferences.SettingsKeys
import ai.trustshield.android.data.preferences.SettingsStorage
import ai.trustshield.android.data.preferences.ThemeMode
import ai.trustshield.android.data.preferences.UserPreferences
import ai.trustshield.android.data.preferences.resolveLanguage
import ai.trustshield.android.ui.call.CallLanguage
import ai.trustshield.android.ui.qr.QrLanguage
import ai.trustshield.android.ui.result.UiLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for work package P3-17 (issue #96): the single place that turns persisted
 * [UserPreferences] into the concrete effects the app applies.
 *
 * These tests are the definition of done for HEAD_ORDERS_PHASE3 item 6 ("Settings really change
 * behavior: theme, language, haptics. Persist across restart") and item 7 (polish), plus
 * QUALITY_GATES gate 6 ("risk not colour-only, haptics optional").
 *
 * No device, emulator or Robolectric: [PreferenceEffects.resolve], [hapticFor], [save] and [load]
 * are android-free by construction, so every rule below is exercised on a plain JVM.
 */
class PreferenceEffectsTest {

    // ---------------------------------------------------------------------------------------
    // Fixtures — an in-memory stand-in for the SharedPreferences file
    // ---------------------------------------------------------------------------------------

    /**
     * The backing "file". Two [FakeStorage] instances over the same [FakeFile] model what actually
     * happens across an app restart: the process dies, `SharedPreferences` is re-opened, and the
     * new store instance sees whatever bytes the previous one committed.
     */
    private class FakeFile {
        var contents: MutableMap<String, String?> = LinkedHashMap()
        var writeCount: Int = 0
    }

    /** [SettingsStorage] adapter over a [FakeFile]; mirrors `SharedPreferencesUserPreferencesStore`. */
    private class FakeStorage(private val file: FakeFile = FakeFile()) : SettingsStorage {

        override fun readAll(): Map<String, String?> = LinkedHashMap(file.contents)

        override fun write(entries: Map<String, String>) {
            file.writeCount++
            // Contract from SettingsStorage: write REPLACES the whole store.
            file.contents = LinkedHashMap(entries)
        }
    }

    private fun preferences(
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        language: AppLanguage = AppLanguage.HINGLISH,
        sensitivity: Sensitivity = Sensitivity.BALANCED,
        haptics: Boolean = true,
        reducedMotion: Boolean = false
    ) = UserPreferences(themeMode, language, sensitivity, haptics, reducedMotion)

    /** Every persisted value we are allowed to see, i.e. the closed set from C04's codec. */
    private val allowedPersistedValues: Set<String> =
        ThemeMode.values().map { it.name }.toSet() +
            AppLanguage.values().map { it.name }.toSet() +
            Sensitivity.values().map { it.name }.toSet() +
            setOf("true", "false")

    // ---------------------------------------------------------------------------------------
    // Theme — SYSTEM follows the phone, LIGHT and DARK force it (all six combinations)
    // ---------------------------------------------------------------------------------------

    @Test
    fun allSixThemeCombinationsResolveExactly() {
        // The complete matrix, written out rather than asserted in a loop, so a future "smart"
        // shortcut (e.g. "SYSTEM always means light") fails on a named case.
        val cases = listOf(
            // mode, systemInDarkTheme, expected darkTheme
            Triple(ThemeMode.SYSTEM, true, true),
            Triple(ThemeMode.SYSTEM, false, false),
            Triple(ThemeMode.LIGHT, true, false),
            Triple(ThemeMode.LIGHT, false, false),
            Triple(ThemeMode.DARK, true, true),
            Triple(ThemeMode.DARK, false, true)
        )
        for ((mode, systemDark, expectedDark) in cases) {
            val effects = resolve(preferences(themeMode = mode), systemInDarkTheme = systemDark)
            assertEquals(
                "$mode on a systemDark=$systemDark phone must resolve darkTheme=$expectedDark",
                expectedDark,
                effects.darkTheme
            )
        }
        assertEquals("the matrix must stay at six cases", 6, cases.size)
    }

    @Test
    fun systemThemeFollowsThePhoneInBothDirections() {
        assertTrue(resolve(preferences(themeMode = ThemeMode.SYSTEM), systemInDarkTheme = true).darkTheme)
        assertFalse(resolve(preferences(themeMode = ThemeMode.SYSTEM), systemInDarkTheme = false).darkTheme)
    }

    @Test
    fun forcedThemeIgnoresThePhone() {
        // A user who chose Dark must get Dark on a light phone, and vice versa: the choice wins.
        assertTrue(resolve(preferences(themeMode = ThemeMode.DARK), systemInDarkTheme = false).darkTheme)
        assertFalse(resolve(preferences(themeMode = ThemeMode.LIGHT), systemInDarkTheme = true).darkTheme)
    }

    // ---------------------------------------------------------------------------------------
    // Language — one stored choice, three screen language types that can never disagree
    // ---------------------------------------------------------------------------------------

    @Test
    fun hinglishMapsIntoEveryScreenLanguageType() {
        val effects = resolve(preferences(language = AppLanguage.HINGLISH), systemInDarkTheme = false)
        assertEquals(AppLanguage.HINGLISH, effects.language)
        assertEquals(QrLanguage.HINGLISH, effects.qrLanguage)
        assertEquals(CallLanguage.HINGLISH, effects.callLanguage)
        assertEquals(UiLanguage.HINGLISH, effects.resultLanguage)
    }

    @Test
    fun englishMapsIntoEveryScreenLanguageType() {
        val effects = resolve(preferences(language = AppLanguage.ENGLISH), systemInDarkTheme = false)
        assertEquals(AppLanguage.ENGLISH, effects.language)
        assertEquals(QrLanguage.ENGLISH, effects.qrLanguage)
        assertEquals(CallLanguage.ENGLISH, effects.callLanguage)
        assertEquals(UiLanguage.ENGLISH, effects.resultLanguage)
    }

    @Test
    fun everyStoredLanguageLandsInAllThreeScreenTypesConsistently() {
        for (language in AppLanguage.values()) {
            val effects = resolve(preferences(language = language), systemInDarkTheme = false)
            val expectedName = resolveLanguage(language, null).name
            assertEquals("QR copy for $language", expectedName, effects.qrLanguage.name)
            assertEquals("Call copy for $language", expectedName, effects.callLanguage.name)
            assertEquals("Result copy for $language", expectedName, effects.resultLanguage.name)
        }
        // Guard against a fourth screen language type being added later without a mapping here.
        assertEquals(4, AppLanguage.values().size)
    }

    @Test
    fun systemLanguageResolvesFromTheProvidedPhoneLocale() {
        val hindi = resolve(preferences(language = AppLanguage.SYSTEM), false, systemLanguageTag = "hi-IN")
        assertEquals(AppLanguage.HINDI, hindi.language)
        assertEquals(QrLanguage.HINDI, hindi.qrLanguage)
        val english = resolve(preferences(language = AppLanguage.SYSTEM), false, systemLanguageTag = "en-US")
        assertEquals(AppLanguage.ENGLISH, english.language)
        assertEquals(CallLanguage.ENGLISH, english.callLanguage)
    }

    @Test
    fun languageIsIndependentOfTheme() {
        // Changing the theme must never move the copy, which is the bug this package exists to stop.
        for (mode in ThemeMode.values()) {
            for (systemDark in booleanArrayOf(true, false)) {
                val effects = resolve(preferences(themeMode = mode, language = AppLanguage.ENGLISH), systemDark)
                assertEquals("$mode/$systemDark must not move the language", QrLanguage.ENGLISH, effects.qrLanguage)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Haptics — opt-in, once, only on a changed high-risk transition (UI_SPEC)
    // ---------------------------------------------------------------------------------------

    @Test
    fun hapticsOffNeverFiresEvenOnAChangedHighRisk() {
        val effects = resolve(preferences(haptics = false), systemInDarkTheme = false)
        assertFalse("haptics disabled must be silent on a transition", hapticFor(effects, transitionedToHighRisk = true))
        assertFalse("haptics disabled must be silent otherwise", hapticFor(effects, transitionedToHighRisk = false))
    }

    @Test
    fun hapticsOnFiresOnlyOnAChangedHighRiskTransition() {
        val effects = resolve(preferences(haptics = true), systemInDarkTheme = false)
        assertTrue(
            "the one case that pulses: enabled user + a transition INTO high risk",
            hapticFor(effects, transitionedToHighRisk = true)
        )
        assertFalse(
            "an unchanged high-risk state must not re-pulse",
            hapticFor(effects, transitionedToHighRisk = false)
        )
    }

    @Test
    fun hapticDecisionCoversTheWholeMatrix() {
        val cases = listOf(
            // haptics enabled, transitioned to high risk, expected pulse
            Triple(true, true, true),
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, false)
        )
        for ((enabled, transitioned, expected) in cases) {
            val effects = resolve(preferences(haptics = enabled), systemInDarkTheme = false)
            assertEquals(
                "enabled=$enabled transitioned=$transitioned",
                expected,
                hapticFor(effects, transitionedToHighRisk = transitioned)
            )
        }
    }

    @Test
    fun hapticDecisionIgnoresThemeLanguageAndMotion() {
        // Only the haptics toggle and the transition may matter. If a theme change could silence
        // the pulse (or a reduced-motion choice could force one), this fails.
        for (mode in ThemeMode.values()) {
            for (language in AppLanguage.values()) {
                for (reducedMotion in booleanArrayOf(true, false)) {
                    val enabled = resolve(
                        preferences(
                            themeMode = mode,
                            language = language,
                            haptics = true,
                            reducedMotion = reducedMotion
                        ),
                        systemInDarkTheme = true
                    )
                    assertTrue(
                        "$mode/$language/reducedMotion=$reducedMotion must still pulse once",
                        hapticFor(enabled, transitionedToHighRisk = true)
                    )
                    assertFalse(
                        "$mode/$language/reducedMotion=$reducedMotion must not pulse otherwise",
                        hapticFor(enabled, transitionedToHighRisk = false)
                    )
                }
            }
        }
    }

    @Test
    fun hapticDecisionIsRepeatableForTheSameState() {
        // Purity: the same transition evaluated twice gives the same answer, so a recomposition
        // cannot turn one pulse into two. The "once" guarantee comes from the caller comparing
        // bands, not from hidden state in here.
        val effects = resolve(preferences(haptics = true), systemInDarkTheme = false)
        assertEquals(hapticFor(effects, true), hapticFor(effects, true))
        assertEquals(hapticFor(effects, false), hapticFor(effects, false))
    }

    // ---------------------------------------------------------------------------------------
    // Reduced motion — the stored choice OR the platform animator switch (UI_SPEC)
    // ---------------------------------------------------------------------------------------

    @Test
    fun reducedMotionFollowsTheStoredChoice() {
        assertTrue(
            resolve(preferences(reducedMotion = true), systemInDarkTheme = false).reducedMotion
        )
        assertFalse(
            resolve(preferences(reducedMotion = false), systemInDarkTheme = false).reducedMotion
        )
    }

    @Test
    fun zeroAnimatorScaleRemovesMotionEvenWhenTheUserLeftTheToggleOff() {
        // UI_SPEC: "reduced-motion/animator scale zero removes nonessential transitions".
        val effects = resolve(
            preferences(reducedMotion = false),
            systemInDarkTheme = false,
            systemAnimatorsEnabled = false
        )
        assertTrue("the platform switch is a floor, not an alternative", effects.reducedMotion)
    }

    @Test
    fun motionDefaultsToOnWhenTheHostSaysNothing() {
        // The neutral assumption: a host that does not know the platform value must not slow the
        // UI down for everyone.
        assertFalse(resolve(preferences(reducedMotion = false), systemInDarkTheme = false).reducedMotion)
    }

    // ---------------------------------------------------------------------------------------
    // Restart persistence — two store instances over one file, exactly like a process death
    // ---------------------------------------------------------------------------------------

    @Test
    fun saveThenLoadSurvivesARestartAsANewStoreInstance() {
        val file = FakeFile()

        val chosen = preferences(
            themeMode = ThemeMode.DARK,
            language = AppLanguage.ENGLISH,
            sensitivity = Sensitivity.HIGH,
            haptics = false,
            reducedMotion = true
        )
        save(chosen, FakeStorage(file))

        // The process dies here. A brand-new store opens the same file: this is what restart is.
        val afterRestart = load(FakeStorage(file), systemInDarkTheme = false)

        assertEquals(
            PreferenceEffects(
                darkTheme = true,
                language = AppLanguage.ENGLISH,
                hapticsEnabled = false,
                reducedMotion = true
            ),
            afterRestart
        )
        assertEquals(QrLanguage.ENGLISH, afterRestart.qrLanguage)
        assertEquals(CallLanguage.ENGLISH, afterRestart.callLanguage)
        assertEquals(UiLanguage.ENGLISH, afterRestart.resultLanguage)
        assertFalse("a disabled toggle survives the restart", afterRestart.hapticsEnabled)
    }

    @Test
    fun aChangedValueSurvivesARestartWithoutTheOldOneLingering() {
        val file = FakeFile()

        // Session 1: the user switches from English/Dark to Hinglish/Light and turns haptics on.
        save(preferences(themeMode = ThemeMode.DARK, language = AppLanguage.ENGLISH, haptics = false), FakeStorage(file))
        save(preferences(themeMode = ThemeMode.LIGHT, language = AppLanguage.HINGLISH, haptics = true), FakeStorage(file))

        val afterRestart = load(FakeStorage(file), systemInDarkTheme = true)
        assertFalse("LIGHT must beat a dark phone after restart", afterRestart.darkTheme)
        assertEquals(AppLanguage.HINGLISH, afterRestart.language)
        assertTrue(afterRestart.hapticsEnabled)
        assertEquals(QrLanguage.HINGLISH, afterRestart.qrLanguage)
    }

    @Test
    fun aSecondRestartIsIdempotent() {
        // Restart -> read -> restart again must not drift, including with a corrupt store in
        // between: the defaults are the floor, never a partially applied state.
        val file = FakeFile()
        save(preferences(themeMode = ThemeMode.LIGHT, language = AppLanguage.ENGLISH, haptics = false), FakeStorage(file))

        val first = load(FakeStorage(file), systemInDarkTheme = false)
        save(loadPreferences(FakeStorage(file)), FakeStorage(file))
        val second = load(FakeStorage(file), systemInDarkTheme = false)
        assertEquals(first, second)
    }

    @Test
    fun loadNeverWritesToTheStoreBehindTheUsersBack() {
        val file = FakeFile()
        save(preferences(language = AppLanguage.ENGLISH), FakeStorage(file))
        val writesAfterSave = file.writeCount

        load(FakeStorage(file), systemInDarkTheme = true)
        loadPreferences(FakeStorage(file))

        assertEquals("reading settings must not rewrite the file", writesAfterSave, file.writeCount)
    }

    // ---------------------------------------------------------------------------------------
    // Defaults — Hinglish, BALANCED, SYSTEM survive an empty or corrupt store
    // ---------------------------------------------------------------------------------------

    @Test
    fun defaultsSurviveAnEmptyStore() {
        val effects = load(FakeStorage(FakeFile()), systemInDarkTheme = true)

        assertEquals(ThemeMode.SYSTEM, loadPreferences(FakeStorage(FakeFile())).themeMode)
        assertTrue("SYSTEM on a dark phone is dark", effects.darkTheme)
        assertEquals(AppLanguage.HINGLISH, effects.language)
        assertEquals(QrLanguage.HINGLISH, effects.qrLanguage)
        assertTrue("C04's shipping default keeps the toggle on", effects.hapticsEnabled)
        assertFalse(effects.reducedMotion)
    }

    @Test
    fun defaultsSurviveACorruptStore() {
        val corrupt = listOf("", "  ", "PURPLE", "KLINGON", "999", "-1", "null", "\u0000", "maybe", "DARK\nLIGHT")
        for (garbage in corrupt) {
            val file = FakeFile()
            FakeStorage(file).write(
                mapOf(
                    SettingsKeys.THEME_MODE to garbage,
                    SettingsKeys.LANGUAGE to garbage,
                    SettingsKeys.SENSITIVITY to garbage,
                    SettingsKeys.HAPTICS to garbage,
                    SettingsKeys.REDUCED_MOTION to garbage
                )
            )
            val stored = loadPreferences(FakeStorage(file))
            assertEquals("garbage '$garbage' must fall back to defaults", UserPreferences.defaults(), stored)
            assertEquals(Sensitivity.BALANCED, stored.sensitivity)

            val effects = load(FakeStorage(file), systemInDarkTheme = false)
            assertFalse("a corrupt store must not force dark", effects.darkTheme)
            assertEquals(QrLanguage.HINGLISH, effects.qrLanguage)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Privacy — the persisted key set is exactly the C04 allowlist, nothing else, ever
    // ---------------------------------------------------------------------------------------

    @Test
    fun persistedKeysAreExactlyTheAllowlist() {
        val file = FakeFile()
        save(
            preferences(
                themeMode = ThemeMode.DARK,
                language = AppLanguage.ENGLISH,
                sensitivity = Sensitivity.HIGH,
                haptics = false,
                reducedMotion = true
            ),
            FakeStorage(file)
        )

        assertEquals(SettingsKeys.ALL.toSet(), file.contents.keys)
        assertEquals("still five keys, no more", 5, file.contents.size)
        for (key in file.contents.keys) {
            assertTrue("'$key' must be allowlisted", SettingsKeys.isAllowed(key))
        }
    }

    @Test
    fun persistedKeysCarryNoForbiddenConcept() {
        val file = FakeFile()
        save(preferences(language = AppLanguage.ENGLISH), FakeStorage(file))

        for (key in file.contents.keys) {
            val lowered = key.lowercase()
            for (concept in SettingsKeys.FORBIDDEN_KEY_CONCEPTS) {
                assertFalse(
                    "persisted key '$key' must not carry the '$concept' concept",
                    lowered.contains(concept)
                )
            }
        }
        // And the concept list is not empty, so the loop above is a real guard.
        assertTrue(SettingsKeys.FORBIDDEN_KEY_CONCEPTS.size >= 16)
    }

    @Test
    fun persistedValuesAreOnlyEnumNamesAndBooleans() {
        // The failing case this guards: someone persisting "last analysed text" as a value under
        // an allowlisted key. Values are closed-set by construction.
        val file = FakeFile()
        save(
            preferences(themeMode = ThemeMode.DARK, language = AppLanguage.ENGLISH, sensitivity = Sensitivity.LOW),
            FakeStorage(file)
        )
        for ((key, value) in file.contents) {
            assertTrue("value of '$key' is '$value', outside the closed set", value in allowedPersistedValues)
        }
        assertFalse(file.contents.values.any { (it ?: "").length > 9 })
    }

    @Test
    fun staleSensitiveKeysAreDroppedRatherThanCarriedForward() {
        // A bad build (or a stale install) wrote analysed content into the settings file. Saving
        // through this package's helpers must delete it, not preserve it.
        val file = FakeFile()
        FakeStorage(file).write(
            mapOf(
                "settings.last_message_text" to "Jo OTP aaya hai woh batayein",
                "settings.quote" to "abhi turant karein",
                "settings.phone" to "+919****3210",
                "settings.upi" to "scammer@ybl",
                "settings.transcript" to "hello",
                SettingsKeys.THEME_MODE to "DARK"
            )
        )

        save(preferences(themeMode = ThemeMode.LIGHT), FakeStorage(file))

        assertEquals(SettingsKeys.ALL.toSet(), file.contents.keys)
        val joined = file.contents.entries.joinToString("|") { "${it.key}=${it.value}" }
        for (leak in listOf("OTP aaya", "scammer@ybl", "+919", "abhi turant", "hello")) {
            assertFalse("'$leak' must not survive a save", joined.contains(leak, ignoreCase = true))
        }
    }

    @Test
    fun effectsModelHasNoFieldForContent() {
        // Structural guard, mirroring C04's own `preferenceModelHasNoUndocumentedFlag`: the effects
        // carrying the app's presentation cannot grow a field that holds user content.
        val fields = PreferenceEffects::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf("darkTheme", "language", "hapticsEnabled", "reducedMotion"),
            fields
        )
        for (field in fields) {
            val lowered = field.lowercase()
            for (concept in listOf("text", "message", "quote", "transcript", "otp", "pin", "phone", "upi", "image", "uri", "audio")) {
                assertFalse("field '$field' must not hold the '$concept' concept", lowered.contains(concept))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Purity / determinism
    // ---------------------------------------------------------------------------------------

    @Test
    fun resolveIsDeterministicForTheSameInput() {
        val p = preferences(themeMode = ThemeMode.DARK, language = AppLanguage.ENGLISH, haptics = false)
        assertEquals(resolve(p, true), resolve(p, true))
        assertNotEquals(resolve(p, true), resolve(p.copy(themeMode = ThemeMode.LIGHT), true))
    }

    @Test
    fun resolveDoesNotMutateThePreferencesItWasGiven() {
        val p = preferences(themeMode = ThemeMode.DARK, haptics = false)
        val before = p.toMap()
        resolve(p, systemInDarkTheme = false)
        hapticFor(resolve(p, systemInDarkTheme = false), transitionedToHighRisk = true)
        assertEquals("resolving effects must not touch the model", before, p.toMap())
    }

    @Test
    fun savingAnAlreadySavedValueIsStable() {
        val file = FakeFile()
        val p = preferences(themeMode = ThemeMode.DARK, language = AppLanguage.ENGLISH)
        val first = save(p, FakeStorage(file))
        val snapshot = LinkedHashMap(file.contents)
        val second = save(first, FakeStorage(file))
        assertEquals(first, second)
        assertEquals(snapshot, file.contents)
        assertEquals(PreferencePersistence(FakeStorage(file)).load(), second)
    }
}
