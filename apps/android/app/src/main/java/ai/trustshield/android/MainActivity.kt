package ai.trustshield.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.SharedPreferencesUserPreferencesStore
import ai.trustshield.android.data.preferences.ThemeMode
import ai.trustshield.android.data.preferences.UserPreferences
import ai.trustshield.android.ui.navigation.AppNavHost
import ai.trustshield.android.ui.navigation.AppRoute
import ai.trustshield.android.ui.navigation.LocalPreferencesSink
import ai.trustshield.android.ui.navigation.LocalUserPreferences
import ai.trustshield.android.ui.navigation.TrustShieldRouteContent
import ai.trustshield.android.ui.theme.TrustShieldTheme

/**
 * The single activity. It owns nothing but the theme and the navigation host: no scoring, no network,
 * no persistence. Everything visible is a `ui/navigation` or `ui/<Area>Screen` composable, which is what
 * keeps the deterministic engine testable on a plain JVM.
 */
class MainActivity : ComponentActivity() {

    /**
     * Destination requested by the intent that launched (or re-entered) this activity — a share
     * intake or shortcut. `null` when the intent carries no [AppRoute.EXTRA_ROUTE]; the host then
     * starts at Home.
     */
    private var requestedEntry: AppRoute? by mutableStateOf(null)
    private var entrySequence by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entrySequence = savedInstanceState?.getInt(STATE_ENTRY_SEQUENCE) ?: 0
        // Edge-to-edge from API 26 up: bar icon colours follow the system light/dark setting, so the
        // status bar is never light-on-light. Insets are consumed by AppNavHost (safeDrawingPadding).
        enableEdgeToEdge()
        requestedEntry = routeFromIntent(intent)

        // C04's store. It persists five setting values and nothing else; no analysed text, quote,
        // transcript, OTP, PIN, phone number or UPI id can reach it (see UserPreferences tests).
        val store = SharedPreferencesUserPreferencesStore.open(applicationContext)

        setContent {
            // Hoisted so the theme at the root and every screen read the same preferences (C01
            // wiring for C04's model). Read once on the main thread: a five-key file.
            var preferences: UserPreferences by remember { mutableStateOf(store.load()) }

            val dark = when (preferences.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            TrustShieldTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalUserPreferences provides preferences,
                    LocalPreferencesSink provides { updated: UserPreferences ->
                        preferences = store.save(updated)
                    }
                ) {
                    // The back stack itself is restored by AppNavHost via rememberSaveable, so
                    // process recreation returns the user to the same screen; `requestedEntry` only
                    // matters when nothing was saved, or when a genuinely new intent arrives.
                    AppNavHost(entry = requestedEntry, entrySequence = entrySequence) { route ->
                        TrustShieldRouteContent(
                            route = route,
                            english = preferences.language == AppLanguage.ENGLISH
                        )
                    }
                }
            }
        }
    }

    /**
     * Re-entry path when the system hands a second intent to a live activity (manifest `launchMode`
     * is Codex-owned). With the stock `standard` launch mode the activity is recreated instead and
     * [onCreate] handles it.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFromIntent(intent)?.let { route ->
            requestedEntry = route
            entrySequence++
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_ENTRY_SEQUENCE, entrySequence)
        super.onSaveInstanceState(outState)
    }

    private fun routeFromIntent(intent: Intent?): AppRoute? =
        AppRoute.fromWire(intent?.getStringExtra(AppRoute.EXTRA_ROUTE))

    private companion object {
        const val STATE_ENTRY_SEQUENCE = "trustshield.entry_sequence"
    }
}
