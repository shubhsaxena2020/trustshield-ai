package ai.trustshield.android.ui.settings

import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.PreferencePersistence
import ai.trustshield.android.data.preferences.Sensitivity
import ai.trustshield.android.data.preferences.SettingsKeys
import ai.trustshield.android.data.preferences.SettingsStorage
import ai.trustshield.android.data.preferences.ThemeMode
import ai.trustshield.android.data.preferences.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused JVM gate for HEAD_ORDERS_PHASE4 item 6 / issue #138, theme half:
 * **"theme light / dark / system persists across relaunch"** (measured PASS on the emulator in
 * `docs/android/evidence/p4-138-settings-verification.md`, §6b — this file is the code-level twin of
 * that measurement).
 *
 * Why a separate file rather than more cases in [PreferenceEffectsTest]: that test proves the
 * six-way resolve matrix once; this one is about the *stored choice surviving a process death*, so
 * every mode is saved, re-opened from a **fresh** [SettingsStorage] instance over the same bytes, and
 * decoded back — including the two cases that only break on a real relaunch (a corrupt value left by
 * an older build, and the residue of an earlier choice).
 *
 * No device, emulator, Robolectric or Compose: [resolve], [load], [save] and
 * [PreferencePersistence] are `android.*`-free, so the whole restart path is exercised here.
 *
 * Nothing user-derived appears in this file: the only "content" is a corrupt enum token plus the
 * synthetic preferences themselves. `ui/theme/Theme.kt` is deliberately not referenced — it returns
 * a Compose `ColorScheme`, which is not constructible from a plain JVM test; the decision that
 * matters is [PreferenceEffects.darkTheme], produced by [resolve].
 */
class ThemePersistenceTest {

    // ---------------------------------------------------------------------------------------
    // Fixture — one in-memory "SharedPreferences file" and throwaway stores over it
    // ---------------------------------------------------------------------------------------

    /**
     * The persisted bytes. Deliberately separate from the store so a **new** store instance can be
     * opened over the same map: that is exactly what a relaunch does (process dies, the file
     * survives, `SharedPreferences` is re-opened).
     */
    private class FakeSettingsFile {
        var entries: MutableMap<String, String?> = LinkedHashMap()
        var writeCount: Int = 0
    }

    /** [SettingsStorage] over a [FakeSettingsFile]; mirrors the real adapter, plus raw inspection. */
    private class InMemorySettingsStorage(
        private val file: FakeSettingsFile = FakeSettingsFile()
    ) : SettingsStorage {

        override fun readAll(): Map<String, String?> = LinkedHashMap(file.entries)

        override fun write(entries: Map<String, String>) {
            file.writeCount++
            // The interface contract: write REPLACES the store, it does not merge.
            file.entries = LinkedHashMap(entries)
        }

        /** The raw persisted string for [key], for asserting the encoding, not just the decode. */
        fun raw(key: String): String? = file.entries[key]

        /** Every persisted entry, for "the theme change touched nothing else" assertions. */
        fun snapshot(): Map<String, String?> = LinkedHashMap(file.entries)

        /** A brand-new store over the same bytes — i.e. the app after `am force-stop` + launch. */
        fun afterRelaunch(): InMemorySettingsStorage = InMemorySettingsStorage(file)

        /** How many times anything has written to this file, across every store over it. */
        fun writes(): Int = file.writeCount
    }

    private fun preferences(
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        language: AppLanguage = AppLanguage.HINGLISH,
        sensitivity: Sensitivity = Sensitivity.BALANCED,
        haptics: Boolean = true,
        reducedMotion: Boolean = false
    ) = UserPreferences(themeMode, language, sensitivity, haptics, reducedMotion)

    // ---------------------------------------------------------------------------------------
    // 1-3: what each mode resolves to on a phone that disagrees with it
    // ---------------------------------------------------------------------------------------

    @Test
    fun defaultThemeModeIsSystemAndResolvesToWhateverThePhoneIs() {
        val defaults = UserPreferences.defaults()
        assertEquals("first launch must follow the phone, not force a theme", ThemeMode.SYSTEM, defaults.themeMode)

        assertTrue(
            "SYSTEM on a dark phone must resolve dark",
            resolve(defaults, systemInDarkTheme = true).darkTheme
        )
        assertFalse(
            "SYSTEM on a light phone must resolve light",
            resolve(defaults, systemInDarkTheme = false).darkTheme
        )

        // The restart path with an empty store is the same thing as a first launch.
        val freshInstall = load(InMemorySettingsStorage(), systemInDarkTheme = true)
        assertTrue("an empty store is Hinglish + SYSTEM, never a forced light theme", freshInstall.darkTheme)
        assertEquals(AppLanguage.HINGLISH, freshInstall.language)
    }

    @Test
    fun darkModeResolvesDarkEvenOnALightPhone() {
        val chosen = preferences(themeMode = ThemeMode.DARK)

        assertTrue(
            "an explicit 'always dark' must win over a light phone",
            resolve(chosen, systemInDarkTheme = false).darkTheme
        )
        // And it is not accidentally an alias for "whatever the phone says".
        assertTrue(resolve(chosen, systemInDarkTheme = true).darkTheme)
    }

    @Test
    fun lightModeResolvesLightEvenOnADarkPhone() {
        val chosen = preferences(themeMode = ThemeMode.LIGHT)

        assertFalse(
            "an explicit 'always light' must win over a dark phone",
            resolve(chosen, systemInDarkTheme = true).darkTheme
        )
        assertFalse(resolve(chosen, systemInDarkTheme = false).darkTheme)

        // Guard: exactly three modes today, so the matrix above is the complete set.
        assertEquals("a fourth ThemeMode needs a resolve() branch and a case here", 3, ThemeMode.entries.size)
    }

    // ---------------------------------------------------------------------------------------
    // 4-7: the relaunch path — save, kill the process, re-open a fresh store
    // ---------------------------------------------------------------------------------------

    @Test
    fun everyThemeModeRoundTripsThroughARelaunch() {
        for (mode in ThemeMode.entries) {
            val file = FakeSettingsFile()
            save(preferences(themeMode = mode), InMemorySettingsStorage(file))

            // The process dies here. A new store opens the same bytes.
            val reloaded = PreferencePersistence(InMemorySettingsStorage(file).afterRelaunch()).load()

            assertEquals("$mode must come back as itself after a relaunch", mode, reloaded.themeMode)
            assertEquals(
                "$mode must be persisted as its canonical enum name",
                mode.name,
                InMemorySettingsStorage(file).raw(SettingsKeys.THEME_MODE)
            )

            // And the reloaded choice must still drive the theme, not just decode correctly.
            assertEquals(
                "$mode must still resolve the same way after a relaunch",
                resolve(preferences(themeMode = mode), systemInDarkTheme = true).darkTheme,
                resolve(reloaded, systemInDarkTheme = true).darkTheme
            )
        }
    }

    @Test
    fun aCorruptPersistedThemeDecodesToSystemInsteadOfCrashing() {
        val file = FakeSettingsFile()
        // What a bad build or a half-written file looks like: a token that is not a ThemeMode.
        InMemorySettingsStorage(file).write(mapOf(SettingsKeys.THEME_MODE to "rainbow"))

        val reloaded = PreferencePersistence(InMemorySettingsStorage(file).afterRelaunch()).load()

        assertEquals("an unknown token must fall back to SYSTEM, not throw", ThemeMode.SYSTEM, reloaded.themeMode)

        // SYSTEM is proven by behaviour, not just by the enum value: the phone decides again.
        assertTrue(
            "a recovered store must still follow a dark phone",
            load(InMemorySettingsStorage(file).afterRelaunch(), systemInDarkTheme = true).darkTheme
        )
        assertFalse(
            "and must not be stuck dark on a light phone",
            load(InMemorySettingsStorage(file).afterRelaunch(), systemInDarkTheme = false).darkTheme
        )
    }

    @Test
    fun aLowercasePersistedThemeDecodesCaseInsensitively() {
        val file = FakeSettingsFile()
        InMemorySettingsStorage(file).write(mapOf(SettingsKeys.THEME_MODE to "dark"))
        assertEquals(
            "values are matched case-insensitively after trimming",
            ThemeMode.DARK,
            PreferencePersistence(InMemorySettingsStorage(file).afterRelaunch()).load().themeMode
        )

        val padded = FakeSettingsFile()
        InMemorySettingsStorage(padded).write(mapOf(SettingsKeys.THEME_MODE to "  LiGhT  "))
        val paddedReloaded = PreferencePersistence(InMemorySettingsStorage(padded).afterRelaunch()).load()
        assertEquals(ThemeMode.LIGHT, paddedReloaded.themeMode)
        assertFalse(
            "a lowercase 'light' is a real forced-light choice, not SYSTEM",
            load(InMemorySettingsStorage(padded).afterRelaunch(), systemInDarkTheme = true).darkTheme
        )
    }

    @Test
    fun switchingThemeTwiceThenRelaunchingKeepOnlyTheLastChoice() {
        // Drive it the way the screen does: two SelectTheme events through the pure reducer, each
        // followed by the persistence the host performs.
        val storage = InMemorySettingsStorage()
        var state = SettingsState.from(UserPreferences.defaults())

        state = state.reduce(SettingsEvent.SelectTheme(ThemeMode.DARK))
        save(state.preferences, storage)
        assertEquals(
            "the first tap must be on disk before the second",
            ThemeMode.DARK.name,
            storage.raw(SettingsKeys.THEME_MODE)
        )

        state = state.reduce(SettingsEvent.SelectTheme(ThemeMode.LIGHT))
        save(state.preferences, storage)

        // Relaunch: a fresh store over the same bytes.
        val relaunched = storage.afterRelaunch()
        val reloaded = PreferencePersistence(relaunched).load()
        assertEquals("only the last choice may survive", ThemeMode.LIGHT, reloaded.themeMode)
        assertEquals("no stale DARK may linger next to it", ThemeMode.LIGHT.name, relaunched.raw(SettingsKeys.THEME_MODE))
        assertFalse(
            "LIGHT must still beat a dark phone after the relaunch",
            load(relaunched, systemInDarkTheme = true).darkTheme
        )
        assertEquals(
            "a replacing write leaves exactly the five allowlisted keys",
            SettingsKeys.ALL.toSet(),
            relaunched.readAll().keys
        )
    }

    // ---------------------------------------------------------------------------------------
    // 8: reduced motion is OR-ed with the platform animator switch (UI_SPEC, same function)
    // ---------------------------------------------------------------------------------------

    @Test
    fun platformAnimatorSwitchForcesReducedMotionEvenWhenTheStoredToggleIsOff() {
        val toggleOff = preferences(reducedMotion = false)

        assertTrue(
            "animator scale zero must remove motion without the user finding our switch",
            resolve(toggleOff, systemInDarkTheme = false, systemAnimatorsEnabled = false).reducedMotion
        )
        assertFalse(
            "with the animators on, an off toggle stays off",
            resolve(toggleOff, systemInDarkTheme = false, systemAnimatorsEnabled = true).reducedMotion
        )
        assertTrue(
            "the stored toggle still works on its own",
            resolve(
                toggleOff.copy(reducedMotion = true),
                systemInDarkTheme = false,
                systemAnimatorsEnabled = true
            ).reducedMotion
        )

        // The platform switch is a motion floor only: it must not leak into the theme decision.
        assertFalse(
            "animator scale zero must not flip the theme",
            resolve(toggleOff, systemInDarkTheme = false, systemAnimatorsEnabled = false).darkTheme
        )
        assertTrue(
            "DARK plus animator scale zero is still dark",
            resolve(
                preferences(themeMode = ThemeMode.DARK),
                systemInDarkTheme = false,
                systemAnimatorsEnabled = false
            ).darkTheme
        )
    }

    // ---------------------------------------------------------------------------------------
    // 9: changing the theme must not move language, sensitivity or the toggles
    // ---------------------------------------------------------------------------------------

    @Test
    fun changingTheThemeDoesNotTouchLanguageOrSensitivity() {
        val storage = InMemorySettingsStorage()
        val before = preferences(
            themeMode = ThemeMode.SYSTEM,
            language = AppLanguage.ENGLISH,
            sensitivity = Sensitivity.HIGH,
            haptics = false,
            reducedMotion = true
        )
        save(before, storage)

        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)) {
            save(before.copy(themeMode = mode), storage)
            val reloaded = PreferencePersistence(storage.afterRelaunch()).load()

            assertEquals("theme '$mode' must be the one field that moved", mode, reloaded.themeMode)
            assertEquals(AppLanguage.ENGLISH, reloaded.language)
            assertEquals(Sensitivity.HIGH, reloaded.sensitivity)
            assertFalse("haptics is an independent toggle", reloaded.haptics)
            assertTrue("reduced motion is an independent toggle", reloaded.reducedMotion)

            // Cross-check the raw encoding, so a decoder that silently re-defaults a neighbour
            // field cannot hide behind an equal-looking object.
            val raw = storage.afterRelaunch()
            assertEquals(AppLanguage.ENGLISH.name, raw.raw(SettingsKeys.LANGUAGE))
            assertEquals(Sensitivity.HIGH.name, raw.raw(SettingsKeys.SENSITIVITY))
        }
    }

    @Test
    fun aThemeChangeRewritesExactlyOnePersistedEntry() {
        val storage = InMemorySettingsStorage()
        save(preferences(themeMode = ThemeMode.DARK, language = AppLanguage.ENGLISH), storage)
        val before = storage.snapshot()

        save(preferences(themeMode = ThemeMode.LIGHT, language = AppLanguage.ENGLISH), storage)
        val after = storage.snapshot()

        val changed = after.filter { (key, value) -> before[key] != value }.keys
        assertEquals(
            "only settings.theme_mode may change when the user changes the theme",
            setOf(SettingsKeys.THEME_MODE),
            changed
        )
        assertEquals(before.keys, after.keys)
        assertNotEquals(before[SettingsKeys.THEME_MODE], after[SettingsKeys.THEME_MODE])
    }

    // ---------------------------------------------------------------------------------------
    // SYSTEM is stored, not the resolved answer — the phone may change between launches
    // ---------------------------------------------------------------------------------------

    @Test
    fun systemModeKeepsFollowingThePhoneAcrossRelaunches() {
        val storage = InMemorySettingsStorage()
        save(preferences(themeMode = ThemeMode.SYSTEM), storage)
        val writesAfterSave = storage.writes()

        // Launch 1: phone in night mode. Launch 2, same file: phone in day mode. A build that
        // persisted the *resolved* boolean instead of SYSTEM would be stuck dark here.
        assertTrue(load(storage.afterRelaunch(), systemInDarkTheme = true).darkTheme)
        assertFalse(load(storage.afterRelaunch(), systemInDarkTheme = false).darkTheme)

        assertEquals(
            "the stored token stays SYSTEM; only the resolution follows the phone",
            ThemeMode.SYSTEM.name,
            storage.afterRelaunch().raw(SettingsKeys.THEME_MODE)
        )
        assertEquals("resolving at launch must not rewrite the store", writesAfterSave, storage.writes())
    }
}
