package ai.trustshield.android.data.preferences

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin Android adapter for [PreferencePersistence] (work package C04, issue #45).
 *
 * This is the **only** file in the settings package that touches `android.*`. It holds no rules:
 * every decision (which keys exist, how values are parsed, what defaults are) lives in the pure
 * `UserPreferences.kt`. The adapter's entire job is to hand a `SharedPreferences` file to
 * [PreferencePersistence] as a [SettingsStorage].
 *
 * `SharedPreferences` was chosen because it is already on the classpath and needs no new
 * dependency. DataStore is the better long-term home (typed keys, no ANR-prone `commit`, real
 * transactional writes); it is **not** on the classpath and build files are owned by Codex, so the
 * upgrade is reported in the PR rather than performed here.
 *
 * Privacy: the backing file is app-private (`MODE_PRIVATE`) and contains only the five
 * [SettingsKeys.ALL] entries. [commit] is used instead of [apply] because a settings write is a
 * user-initiated, one-at-a-time action where the extra strictness is worth ~1 ms.
 */
class SharedPreferencesUserPreferencesStore private constructor(
    private val prefs: SharedPreferences
) : SettingsStorage {

    private val persistence = PreferencePersistence(this)

    override fun readAll(): Map<String, String?> =
        prefs.all.mapValues { (_, value) -> value as? String }

    /** Replaces the whole file: stray or legacy keys are dropped, not carried forward. */
    override fun write(entries: Map<String, String>) {
        val editor = prefs.edit().clear()
        for ((key, value) in entries) {
            if (SettingsKeys.isAllowed(key)) {
                editor.putString(key, value)
            }
        }
        editor.commit()
    }

    fun load(): UserPreferences = persistence.load()

    fun save(preferences: UserPreferences): UserPreferences = persistence.save(preferences)

    fun reset(): UserPreferences = persistence.reset()

    /** Keys currently on disk. Exposed for the settings screen's own diagnostic use and for review. */
    fun storedKeys(): Set<String> = prefs.all.keys.toSet()

    companion object {

        /** App-private file name. Never shared, never backed up by this class. */
        const val FILE_NAME: String = "trustshield_user_preferences"

        fun open(context: Context): SharedPreferencesUserPreferencesStore {
            val appContext = context.applicationContext ?: context
            val prefs = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            return SharedPreferencesUserPreferencesStore(prefs)
        }

        /** Test/preview hook: wrap an existing file handle. */
        fun wrap(prefs: SharedPreferences): SharedPreferencesUserPreferencesStore =
            SharedPreferencesUserPreferencesStore(prefs)
    }
}
