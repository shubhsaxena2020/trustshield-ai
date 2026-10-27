package ai.trustshield.android.data.preferences

import ai.trustshield.android.ui.call.CallLanguage
import ai.trustshield.android.ui.qr.QrLanguage
import ai.trustshield.android.ui.result.UiLanguage
import ai.trustshield.android.ui.result.pick
import ai.trustshield.android.ui.settings.SettingText
import ai.trustshield.android.ui.settings.SettingsCopy
import ai.trustshield.android.ui.settings.resolve
import ai.trustshield.android.ui.theme.ToneCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the **language** half of HEAD_ORDERS_PHASE4 item 6 / issue #138 ("Settings
 * really work"): one stored [AppLanguage] choice must (a) survive a relaunch, (b) degrade to the
 * shipping Hinglish default when the persisted bytes are unreadable, and (c) reach *every* screen's
 * own language type from that single value.
 *
 * Scope note. `SettingsTest` already covers the generic codec matrix and
 * `PreferenceEffectsTest` covers the per-screen mapping matrix. This file is narrower on purpose:
 * it is the persistence + localisation contract for language only, so a regression in the language
 * toggle has one obvious place to look. It re-states a few defaults deliberately rather than
 * inheriting them, because the product rule being guarded (Hinglish is the default, English is an
 * explicit opt-in — MISSION/BEHAVIOR_SPEC §2 rule 6) is the acceptance criterion itself.
 *
 * Deliberately no device, no emulator, no Robolectric: [UserPreferences], [PreferencePersistence],
 * [SettingText], `resolve` and [ToneCopy.pick] are all android-free by construction.
 *
 * Privacy. Only synthetic fixtures already present in this repo are used. Nothing here writes
 * message text, quotes, OTP/PIN/card data, phone numbers or UPI ids; the stray keys used in
 * [saveWritesOnlyTheAllowlistedKeysAndDeletesAStrayKey] are key *names* from the guard list in
 * [SettingsKeys.FORBIDDEN_KEY_CONCEPTS] holding a placeholder string.
 */
class LanguagePreferenceTest {

    // ---------------------------------------------------------------------------------------
    // Fixtures — one in-memory store, two instances, exactly like a process restart
    // ---------------------------------------------------------------------------------------

    /**
     * In-memory [SettingsStorage] over a caller-owned map.
     *
     * [write] **replaces** the whole contents (the documented [SettingsStorage] contract), so
     * handing the same backing map to a second instance models an app relaunch faithfully: the
     * first instance is discarded and the new one re-reads whatever the old one committed.
     */
    private class FakeStore(
        private val backing: MutableMap<String, String?> = LinkedHashMap()
    ) : SettingsStorage {

        var writeCount: Int = 0
            private set

        override fun readAll(): Map<String, String?> = LinkedHashMap(backing)

        override fun write(entries: Map<String, String>) {
            writeCount++
            // Contract: write replaces the entire store; nothing outside `entries` survives.
            backing.clear()
            backing.putAll(entries)
        }

        /** Snapshot of what the "file" currently holds. */
        fun contents(): Map<String, String?> = LinkedHashMap(backing)

        /** Seeds a value directly, i.e. as an older/foreign build would have left it. */
        fun put(key: String, value: String?) {
            backing[key] = value
        }

        /** The next process opening the same "file". */
        fun relaunch(): FakeStore = FakeStore(backing)
    }

    private fun preferences(language: AppLanguage) = UserPreferences(language = language)

    // ---------------------------------------------------------------------------------------
    // 1. Shipping default
    // ---------------------------------------------------------------------------------------

    @Test
    fun shippingDefaultLanguageFollowsThePhoneAndFallsBackToHinglish() {
        // Three independent spellings of "the default" must agree, so a stray construction-site
        // default (e.g. `UserPreferences(language = ENGLISH)`) cannot hide behind the companion.
        assertEquals("constructor default", AppLanguage.SYSTEM, UserPreferences().language)
        assertEquals("companion default", AppLanguage.SYSTEM, UserPreferences.defaults().language)
        assertEquals("DEFAULTS value", AppLanguage.SYSTEM, UserPreferences.DEFAULTS.language)

        // An empty store — a first launch — is the same thing.
        assertEquals(AppLanguage.SYSTEM, PreferencePersistence(FakeStore()).load().language)

        // And it is the product default on screen, not just in the model.
        val effects = resolve(
            preferences = PreferencePersistence(FakeStore()).load(),
            systemInDarkTheme = false
        )
        assertEquals(QrLanguage.HINGLISH, effects.qrLanguage)
        assertEquals(CallLanguage.HINGLISH, effects.callLanguage)
        assertEquals(UiLanguage.HINGLISH, effects.resultLanguage)
    }

    // ---------------------------------------------------------------------------------------
    // 2. ENGLISH survives a relaunch (save -> process death -> load on a fresh instance)
    // ---------------------------------------------------------------------------------------

    @Test
    fun englishRoundTripsThroughPersistenceAcrossARelaunch() {
        val store = FakeStore()

        val saved = PreferencePersistence(store).save(preferences(AppLanguage.ENGLISH))
        assertEquals(AppLanguage.ENGLISH, saved.language)
        assertEquals("save must have written the file once", 1, store.writeCount)

        // The process dies here; a brand-new store instance opens the same file.
        val afterRelaunch = PreferencePersistence(store.relaunch()).load()
        assertEquals(AppLanguage.ENGLISH, afterRelaunch.language)
        assertEquals(saved, afterRelaunch)
        // Only the language moved; the other four choices must be untouched defaults.
        assertEquals(UserPreferences.defaults().copy(language = AppLanguage.ENGLISH), afterRelaunch)

        // The relaunched process renders English on all three screens from that one value.
        val effects = resolve(preferences = afterRelaunch, systemInDarkTheme = true)
        assertEquals(UiLanguage.ENGLISH, effects.resultLanguage)
        assertEquals(QrLanguage.ENGLISH, effects.qrLanguage)
        assertEquals(CallLanguage.ENGLISH, effects.callLanguage)
    }

    // ---------------------------------------------------------------------------------------
    // 3. Tolerant decoding of the persisted value
    // ---------------------------------------------------------------------------------------

    @Test
    fun lowerCaseAndPaddedPersistedValueDecodesToEnglish() {
        // A hand-edited file, a migrated store or a debug override can all hand us ' english '.
        val store = FakeStore()
        store.put(SettingsKeys.LANGUAGE, " english ")

        assertEquals(AppLanguage.ENGLISH, PreferencePersistence(store).load().language)
        // Same value through the codec directly, so this is the decoder's rule, not storage's.
        assertEquals(
            AppLanguage.ENGLISH,
            UserPreferences.fromMap(mapOf(SettingsKeys.LANGUAGE to " english ")).language
        )
        // Decoding must not rewrite the file behind the user's back.
        assertEquals(0, store.writeCount)
    }

    // ---------------------------------------------------------------------------------------
    // 4 + 5. Garbage and missing values fall back instead of throwing
    // ---------------------------------------------------------------------------------------

    @Test
    fun garbagePersistedLanguageDecodesToTheHinglishDefaultWithoutThrowing() {
        // Never `ENGLISH`: an unreadable language must degrade to the product default, so a
        // corrupted store can never silently switch the whole app out of Hinglish.
        val garbage = listOf(
            "KLINGON",
            "EN",
            "englishh",
            "0",
            "\u0000",
            "HINGLISH_ENGLISH",
            "null",
            "{}",
            ""
        )
        for (value in garbage) {
            val store = FakeStore()
            store.put(SettingsKeys.LANGUAGE, value)

            val loaded = PreferencePersistence(store).load()
            assertEquals("garbage '$value' must fall back to SYSTEM", AppLanguage.SYSTEM, loaded.language)
            assertEquals(
                "garbage '$value' must leave the rest at defaults",
                UserPreferences.defaults(),
                loaded
            )
            // And the whole app stays in Hinglish rather than half-switching.
            val effects = resolve(preferences = loaded, systemInDarkTheme = true)
            assertEquals(QrLanguage.HINGLISH, effects.qrLanguage)
            assertEquals(CallLanguage.HINGLISH, effects.callLanguage)
            assertEquals(UiLanguage.HINGLISH, effects.resultLanguage)
        }
    }

    @Test
    fun missingLanguageKeyDecodesToTheDefault() {
        // Nothing stored at all.
        assertEquals(AppLanguage.SYSTEM, PreferencePersistence(FakeStore()).load().language)

        // Every other key present, language simply absent (an older build that predates the
        // toggle). The stored theme must still be honoured — only language falls back.
        val store = FakeStore()
        store.put(SettingsKeys.THEME_MODE, "DARK")
        store.put(SettingsKeys.SENSITIVITY, "HIGH")
        store.put(SettingsKeys.HAPTICS, "false")
        store.put(SettingsKeys.REDUCED_MOTION, "true")
        assertFalse("precondition: no language key is present", store.contents().containsKey(SettingsKeys.LANGUAGE))

        val loaded = PreferencePersistence(store).load()
        assertEquals(AppLanguage.SYSTEM, loaded.language)
        assertEquals(ThemeMode.DARK, loaded.themeMode)
        assertEquals(Sensitivity.HIGH, loaded.sensitivity)
        assertFalse("an unrelated stored choice must not be reset by the missing language", loaded.haptics)
    }

    // ---------------------------------------------------------------------------------------
    // 6. save() is a replacing write over the closed key set
    // ---------------------------------------------------------------------------------------

    @Test
    fun saveWritesOnlyTheAllowlistedKeysAndDeletesAStrayKey() {
        val store = FakeStore()
        // What a bad or older build could have left behind: a key outside the allowlist.
        store.put("settings.last_message_text", "<stray placeholder>")
        store.put(SettingsKeys.LANGUAGE, "HINGLISH")
        assertTrue(store.contents().containsKey("settings.last_message_text"))

        PreferencePersistence(store).save(preferences(AppLanguage.ENGLISH))

        val stored = store.contents()
        assertEquals("only C04's five keys may be written", SettingsKeys.ALL.toSet(), stored.keys)
        assertEquals("no more than five keys", 5, stored.size)
        assertFalse(
            "the replacing write must delete a stray key, not keep it",
            stored.containsKey("settings.last_message_text")
        )
        for (key in stored.keys) {
            assertTrue("'$key' must be allowlisted", SettingsKeys.isAllowed(key))
        }
        assertEquals(AppLanguage.ENGLISH.name, stored[SettingsKeys.LANGUAGE])
    }

    // ---------------------------------------------------------------------------------------
    // 7. One stored choice -> three screen language types, all English
    // ---------------------------------------------------------------------------------------

    @Test
    fun englishResolvesIntoEveryScreenLanguageType() {
        val hinglish = resolve(preferences(AppLanguage.HINGLISH), systemInDarkTheme = false)
        val english = resolve(preferences(AppLanguage.ENGLISH), systemInDarkTheme = false)

        // One block, four assertions: the stored choice and the three derived enums it drives.
        assertEquals(AppLanguage.ENGLISH, english.language)
        assertEquals(QrLanguage.ENGLISH, english.qrLanguage)
        assertEquals(CallLanguage.ENGLISH, english.callLanguage)
        assertEquals(UiLanguage.ENGLISH, english.resultLanguage)

        // The toggle actually moved something: Hinglish resolves to Hinglish on all three.
        assertEquals(QrLanguage.HINGLISH, hinglish.qrLanguage)
        assertEquals(CallLanguage.HINGLISH, hinglish.callLanguage)
        assertEquals(UiLanguage.HINGLISH, hinglish.resultLanguage)
        assertNotEquals("the two settings must not resolve identically", hinglish, english)

        // The resolving function must not pick English from anywhere else: theme stays default.
        assertEquals(UserPreferences.defaults().themeMode, english.darkTheme.let { UserPreferences.defaults().themeMode })
        assertFalse("SYSTEM theme on a light phone must stay light", english.darkTheme)
    }

    // ---------------------------------------------------------------------------------------
    // 8. SettingText: the per-string half of the toggle
    // ---------------------------------------------------------------------------------------

    @Test
    fun settingTextForLanguageReturnsTheMatchingVariant() {
        val sample = SettingText(hinglish = "Bhasha badlein", english = "Switch language")

        assertEquals("Switch language", sample.forLanguage(AppLanguage.ENGLISH))
        assertEquals("Bhasha badlein", sample.forLanguage(AppLanguage.HINGLISH))

        // The real Settings copy must obey the same rule for every string, so no screen shows a
        // mixed pair. `languageHint` is the string a user reads exactly when they use this toggle.
        for (setting in SettingsCopy.all()) {
            assertEquals(
                "forLanguage(ENGLISH) must yield the english variant: $setting",
                setting.english,
                setting.forLanguage(AppLanguage.ENGLISH)
            )
            assertEquals(
                "forLanguage(HINGLISH) must yield the hinglish variant: $setting",
                setting.hinglish,
                setting.forLanguage(AppLanguage.HINGLISH)
            )
        }
        assertNotEquals(
            "the language hint must genuinely be translated",
            SettingsCopy.languageHint.hinglish,
            SettingsCopy.languageHint.english
        )
    }

    // ---------------------------------------------------------------------------------------
    // 9. ToneCopy.pick: the Result screen's variant, including the blank-English fallback
    // ---------------------------------------------------------------------------------------

    @Test
    fun toneCopyPickUsesEnglishAndFallsBackToHinglishWhenEnglishIsBlank() {
        // Documented behaviour of `ToneCopy.pick` (ui/result/ResultUiState.kt): HINGLISH always
        // yields the hinglish field; ENGLISH yields the english field, or hinglish when that field
        // is blank. The fallback exists so a missing translation never renders an empty line.
        val full = ToneCopy(hinglish = "Khatra", english = "Risk")
        assertEquals("Risk", full.pick(UiLanguage.ENGLISH))
        assertEquals("Khatra", full.pick(UiLanguage.HINGLISH))
        assertEquals("hinglish is the default argument", "Khatra", full.pick())

        val untranslated = ToneCopy(hinglish = "Khatra", english = "")
        assertEquals("blank english must not blank the line", "Khatra", untranslated.pick(UiLanguage.ENGLISH))

        val whitespaceOnly = ToneCopy(hinglish = "Khatra", english = "   ")
        assertEquals("whitespace-only english counts as blank", "Khatra", whitespaceOnly.pick(UiLanguage.ENGLISH))

        // English never leaks into Hinglish mode, however complete the pair is.
        assertEquals("Khatra", full.pick(UiLanguage.HINGLISH))
        assertNotEquals(full.pick(UiLanguage.HINGLISH), full.pick(UiLanguage.ENGLISH))
    }

    // ---------------------------------------------------------------------------------------
    // 10. Privacy guard: persisting a language cannot smuggle content
    // ---------------------------------------------------------------------------------------

    @Test
    fun persistedSettingsKeysCarryNoForbiddenContentConcept() {
        val store = FakeStore()
        PreferencePersistence(store).save(preferences(AppLanguage.ENGLISH))
        val stored = store.contents()

        // Save above is the only writer, so what it produced is the full persisted key set.
        assertEquals(SettingsKeys.ALL.toSet(), stored.keys)

        for (key in stored.keys) {
            val lowered = key.lowercase()
            for (concept in SettingsKeys.FORBIDDEN_KEY_CONCEPTS) {
                assertFalse(
                    "persisted key '$key' must not carry the '$concept' concept",
                    lowered.contains(concept)
                )
            }
        }

        // The guard list itself must be substantive, otherwise the loop proves nothing.
        assertTrue(
            "FORBIDDEN_KEY_CONCEPTS must stay a real guard",
            SettingsKeys.FORBIDDEN_KEY_CONCEPTS.size >= 16
        )

        // And the language *value* is a bare enum name — not a free-text field that could carry a
        // message, a quote or an account number alongside the choice.
        val persistedLanguage = stored[SettingsKeys.LANGUAGE]
        assertEquals(AppLanguage.ENGLISH.name, persistedLanguage)
        assertTrue(
            "the persisted language must be one of the enum names only: '$persistedLanguage'",
            persistedLanguage == AppLanguage.HINGLISH.name || persistedLanguage == AppLanguage.ENGLISH.name
        )
        for (concept in SettingsKeys.FORBIDDEN_KEY_CONCEPTS) {
            assertFalse(
                "persisted language value must not carry the '$concept' concept",
                (persistedLanguage ?: "").lowercase().contains(concept)
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Supporting behaviour: every language round-trips, and switching back replaces the old value
    // ---------------------------------------------------------------------------------------

    @Test
    fun everyLanguageSurvivesPersistenceAndResolvesConsistently() {
        for (language in AppLanguage.values()) {
            val store = FakeStore()
            PreferencePersistence(store).save(UserPreferences(language = language, themeMode = ThemeMode.DARK))

            val afterRelaunch = PreferencePersistence(store.relaunch()).load()
            assertEquals("$language must survive the relaunch", language, afterRelaunch.language)
            // The neighbouring choice is unaffected: language and theme are independent keys.
            assertEquals(ThemeMode.DARK, afterRelaunch.themeMode)

            val effects = resolve(preferences = afterRelaunch, systemInDarkTheme = false)
            val resolvedLanguage = if (language == AppLanguage.SYSTEM) AppLanguage.HINGLISH else language
            assertEquals("QR copy for $language", resolvedLanguage.name, effects.qrLanguage.name)
            assertEquals("Call copy for $language", resolvedLanguage.name, effects.callLanguage.name)
            assertEquals("Result copy for $language", resolvedLanguage.name, effects.resultLanguage.name)
        }
        assertEquals(setOf(AppLanguage.SYSTEM, AppLanguage.HINGLISH, AppLanguage.ENGLISH, AppLanguage.HINDI), AppLanguage.values().toSet())
    }

    @Test
    fun switchingBackToHinglishReplacesThePersistedEnglishValue() {
        val store = FakeStore()
        val persistence = PreferencePersistence(store)

        persistence.save(preferences(AppLanguage.ENGLISH))
        assertEquals(AppLanguage.ENGLISH.name, store.contents()[SettingsKeys.LANGUAGE])

        persistence.save(preferences(AppLanguage.HINGLISH))

        // Exactly one language value survives — no "ENGLISH" remnant can be read back later.
        assertEquals(AppLanguage.HINGLISH.name, store.contents()[SettingsKeys.LANGUAGE])
        assertEquals(AppLanguage.HINGLISH, store.relaunch().readAll().let { UserPreferences.fromMap(it).language })
        assertEquals("Hinglish", SettingsCopy.languageHinglish.forLanguage(AppLanguage.HINGLISH))
    }
}
