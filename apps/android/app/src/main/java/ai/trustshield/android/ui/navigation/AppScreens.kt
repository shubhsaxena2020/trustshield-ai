package ai.trustshield.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.UserPreferences
import ai.trustshield.android.ui.call.CallLanguage
import ai.trustshield.android.ui.call.CallEvent
import ai.trustshield.android.ui.call.CallShieldReducer
import ai.trustshield.android.ui.call.CallShieldScreen
import ai.trustshield.android.ui.call.CallUiState
import ai.trustshield.android.ui.qr.QrGuardScreen
import ai.trustshield.android.ui.qr.QrEvent
import ai.trustshield.android.ui.qr.QrLanguage
import ai.trustshield.android.ui.qr.QrReducer
import ai.trustshield.android.ui.qr.QrUiState
import ai.trustshield.android.ui.result.ResultFixtures
import ai.trustshield.android.ui.result.ResultPresenter
import ai.trustshield.android.ui.result.ResultScreen
import ai.trustshield.android.ui.result.UiLanguage
import ai.trustshield.android.ui.settings.SettingsScreen
import ai.trustshield.android.ui.settings.SettingsState
import ai.trustshield.android.ui.settings.reduce

/**
 * App-level settings, hoisted so the theme at the root and every screen read the *same* preferences.
 *
 * C04 owns the model and the persistence; C01 owns this wiring. The default is inert (product
 * defaults, writes discarded) so a `@Preview` or a screen rendered on its own cannot crash.
 */
val LocalUserPreferences: ProvidableCompositionLocal<UserPreferences> =
    staticCompositionLocalOf { UserPreferences.defaults() }

/** Persists a changed [UserPreferences]. Provided by `MainActivity`; a no-op in previews. */
val LocalPreferencesSink: ProvidableCompositionLocal<(UserPreferences) -> Unit> =
    staticCompositionLocalOf { {} }

/**
 * Result destination host.
 *
 * SEAM: the state still comes from [ResultFixtures]. A01 and A05 are merged, but the app-level
 * analysis flow is not connected. Replace the fixture with engine output when that flow is wired.
 * The fixture is labelled "hand-written fixture, not model output" in [ResultFixtures.FIXTURE_LABEL]
 * so it can never be mistaken for a measured result.
 */
@Composable
fun ResultRouteHost(english: Boolean) {
    val nav = LocalNavActions.current
    val prefs = LocalUserPreferences.current
    val sink = LocalPreferencesSink.current
    val state = remember { ResultPresenter.build(ResultFixtures.digitalArrestInput) }
    var language by remember(english) { mutableStateOf(UiLanguage.of(english)) }

    ResultScreen(
        state = state,
        language = language,
        onToggleLanguage = {
            language =
                if (language == UiLanguage.ENGLISH) UiLanguage.HINGLISH else UiLanguage.ENGLISH
            sink(
                prefs.copy(
                    language = if (language == UiLanguage.ENGLISH) AppLanguage.ENGLISH else AppLanguage.HINGLISH
                )
            )
        },
        // SEAM: no external actions exist yet (no dialer, no browser -- analysed content is never
        // opened). Actions are rendered and inert until a package owns them.
        onAction = {},
        onBack = { nav.back() }
    )
}

/**
 * Call Shield destination host.
 *
 * SEAM: B04/B05 are merged, but this host does not connect their capability or speech session. No
 * microphone opens and no ASR runs. The screen shows the consent gate and honest "no recogniser"
 * state; [CallShieldReducer] is driven only by explicit user events. This is *audible speech while
 * the app is visible* -- never the remote side of a cellular or VoIP call.
 */
@Composable
fun CallShieldRouteHost() {
    val prefs = LocalUserPreferences.current
    val sink = LocalPreferencesSink.current
    var state by remember {
        mutableStateOf(
            CallUiState(
                language =
                    if (prefs.language == AppLanguage.ENGLISH) {
                        CallLanguage.ENGLISH
                    } else {
                        CallLanguage.HINGLISH
                    },
                hapticsEnabled = prefs.haptics,
                reducedMotion = prefs.reducedMotion
            )
        )
    }

    CallShieldScreen(
        state = state,
        onEvent = { event ->
            state = CallShieldReducer.reduce(state, event)
            if (event is CallEvent.SetLanguage) {
                sink(
                    prefs.copy(
                        language = if (event.language == CallLanguage.ENGLISH) {
                            AppLanguage.ENGLISH
                        } else {
                            AppLanguage.HINGLISH
                        }
                    )
                )
            }
        },
        // Both fallbacks are explicitly not live ASR; they exist so a denied mic is never a dead end.
        onOpenStagedDemo = {},
        onTypeTranscript = {}
    )
}

/**
 * QR Guard destination host.
 *
 * SEAM: B02 (UPI parser), B03 (camera) and B06 (watcher) are merged, but not connected to this
 * screen. No camera is opened and no QR payload is resolved or opened -- payloads are inert data.
 * The registered-payee setup flow works locally; the watch loop waits for B03/B06 to emit
 * `QrEvent.ValidFrame`/`UnreadableFrame`.
 */
@Composable
fun QrGuardRouteHost() {
    val prefs = LocalUserPreferences.current
    val sink = LocalPreferencesSink.current
    var state by remember {
        mutableStateOf(
            QrUiState(
                language =
                    if (prefs.language == AppLanguage.ENGLISH) {
                        QrLanguage.ENGLISH
                    } else {
                        QrLanguage.HINGLISH
                    }
            )
        )
    }

    QrGuardScreen(
        state = state,
        onEvent = { event ->
            state = QrReducer.reduce(state, event)
            if (event is QrEvent.SetLanguage) {
                sink(
                    prefs.copy(
                        language = if (event.language == QrLanguage.ENGLISH) {
                            AppLanguage.ENGLISH
                        } else {
                            AppLanguage.HINGLISH
                        }
                    )
                )
            }
        }
    )
}

/**
 * Settings destination host: the one place preferences are edited.
 *
 * C04's reducer is pure; this host performs the single side effect (persisting through
 * [LocalPreferencesSink]) only when the preference values actually changed, so `RequestReset` /
 * `DismissNotice` never touch the file. Persistence stores the five setting values only -- never any
 * analysed text, quote, transcript, OTP, PIN, phone or UPI id.
 */
@Composable
fun SettingsRouteHost() {
    val prefs = LocalUserPreferences.current
    val sink = LocalPreferencesSink.current
    var state by remember { mutableStateOf(SettingsState.from(prefs)) }

    SettingsScreen(
        state = state,
        onEvent = { event ->
            val next = state.reduce(event)
            if (next.preferences != state.preferences) {
                sink(next.preferences)
            }
            state = next
        }
    )
}
