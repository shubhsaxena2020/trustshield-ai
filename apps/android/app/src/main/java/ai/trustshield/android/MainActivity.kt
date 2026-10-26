package ai.trustshield.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.SharedPreferencesUserPreferencesStore
import ai.trustshield.android.data.preferences.ThemeMode
import ai.trustshield.android.data.preferences.UserPreferences
import ai.trustshield.android.data.preferences.resolveLanguage
import ai.trustshield.android.core.analysis.LocalScanPipeline
import ai.trustshield.android.core.analysis.ScanOutcome
import ai.trustshield.android.core.score.LocalScorer
import ai.trustshield.android.input.share.ShareIntentReader
import ai.trustshield.android.input.share.ShareIntentRead
import ai.trustshield.android.input.share.SharedContent
import ai.trustshield.android.input.share.ShareIngestCoordinator
import ai.trustshield.android.input.share.ShareIngestOutcome
import ai.trustshield.android.input.share.ShareIngestRequest
import ai.trustshield.android.ui.home.ScanInputEvent
import ai.trustshield.android.ui.home.ScanInputState
import ai.trustshield.android.ui.home.reduce
import ai.trustshield.android.ui.navigation.AnalysisToResult
import ai.trustshield.android.ui.navigation.AppNavHost
import ai.trustshield.android.ui.navigation.AppRoute
import ai.trustshield.android.ui.navigation.LocalPreferencesSink
import ai.trustshield.android.ui.navigation.LocalUserPreferences
import ai.trustshield.android.ui.navigation.TrustShieldRouteContent
import ai.trustshield.android.ui.theme.TrustShieldTheme
import java.util.Locale

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
    private var incomingText: String? by mutableStateOf(null)
    private var incomingImageUri: String? by mutableStateOf(null)
    private var resultOutcome: ScanOutcome? by mutableStateOf(null)
    private var visibleRoute: AppRoute by mutableStateOf(AppRoute.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        entrySequence = savedInstanceState?.getInt(STATE_ENTRY_SEQUENCE) ?: 0
        resultOutcome = lastCustomNonConfigurationInstance as? ScanOutcome
        val restoredRoute = AppRoute.fromWire(savedInstanceState?.getString(STATE_VISIBLE_ROUTE))
        val restoredWithoutOutcome = restoredRoute == AppRoute.RESULT && resultOutcome == null
        // Edge-to-edge from API 26 up: bar icon colours follow the system light/dark setting, so the
        // status bar is never light-on-light. Insets are consumed by AppNavHost (safeDrawingPadding).
        enableEdgeToEdge()
        requestedEntry = routeFromIntent(intent)
        incomingText = sharedText(intent)
        incomingImageUri = sharedImageUri(intent)
        if (incomingText != null) {
            requestedEntry = AppRoute.HOME
            entrySequence++
        } else if (incomingImageUri != null) {
            requestedEntry = AppRoute.HOME
            entrySequence++
        }
        val explicitRoute = routeFromIntent(intent)
        if (restoredWithoutOutcome && incomingText == null && incomingImageUri == null &&
            (explicitRoute == null || explicitRoute == AppRoute.RESULT)
        ) {
            // A process-death restore cannot recover the in-memory verdict without persisting the
            // scanned text. Return to Home instead of restoring an empty Result destination.
            requestedEntry = AppRoute.HOME
            entrySequence++
        }

        // C04's store. It persists five setting values and nothing else; no analysed text, quote,
        // transcript, OTP, PIN, phone number or UPI id can reach it (see UserPreferences tests).
        val store = SharedPreferencesUserPreferencesStore.open(applicationContext)

        setContent {
            // Hoisted so the theme at the root and every screen read the same preferences (C01
            // wiring for C04's model). Read once on the main thread: a five-key file.
            var preferences: UserPreferences by remember { mutableStateOf(store.load()) }
            var scanState by remember { mutableStateOf(ScanInputState()) }

            LaunchedEffect(entrySequence, incomingText) {
                val shared = incomingText ?: return@LaunchedEffect
                scanState = reduce(scanState, ScanInputEvent.TextChanged(shared))
                val sensitivity = when (preferences.sensitivity) {
                    ai.trustshield.android.data.preferences.Sensitivity.LOW -> LocalScorer.Sensitivity.LOW
                    ai.trustshield.android.data.preferences.Sensitivity.BALANCED -> LocalScorer.Sensitivity.STANDARD
                    ai.trustshield.android.data.preferences.Sensitivity.HIGH -> LocalScorer.Sensitivity.HIGH
                }
                resultOutcome = when (val share = ShareIngestCoordinator(
                    pipeline = LocalScanPipeline(sensitivity = sensitivity)
                ).ingest(ShareIngestRequest.Text(shared))) {
                    is ShareIngestOutcome.Analysed -> share.outcome
                    else -> null
                }
                incomingText = null
                requestedEntry = AppRoute.RESULT
                entrySequence++
            }

            val dark = when (preferences.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            TrustShieldTheme(darkTheme = dark) {
                SideEffect {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
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
                        SideEffect { visibleRoute = route }
                        TrustShieldRouteContent(
                            route = route,
                            language = resolveLanguage(preferences.language, Locale.getDefault().toLanguageTag()),
                            english = resolveLanguage(preferences.language, Locale.getDefault().toLanguageTag()) == AppLanguage.ENGLISH,
                            scanState = scanState,
                            resultOutcome = resultOutcome,
                            sharedImageUri = incomingImageUri,
                            onSharedImageConsumed = { incomingImageUri = null },
                            onImageResult = { resultOutcome = it },
                            onScanEvent = { event -> scanState = reduce(scanState, event) },
                            onCheck = { text ->
                                val sensitivity = when (preferences.sensitivity) {
                                    ai.trustshield.android.data.preferences.Sensitivity.LOW -> LocalScorer.Sensitivity.LOW
                                    ai.trustshield.android.data.preferences.Sensitivity.BALANCED -> LocalScorer.Sensitivity.STANDARD
                                    ai.trustshield.android.data.preferences.Sensitivity.HIGH -> LocalScorer.Sensitivity.HIGH
                                }
                                resultOutcome = LocalScanPipeline(sensitivity = sensitivity).analyzeText(text)
                            }
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
        incomingText = sharedText(intent)
        incomingImageUri = sharedImageUri(intent)
        if (incomingText != null) {
            requestedEntry = AppRoute.HOME
            entrySequence++
        } else if (incomingImageUri != null) {
            requestedEntry = AppRoute.HOME
            entrySequence++
        }
        routeFromIntent(intent)?.let { route ->
            requestedEntry = route
            entrySequence++
        }
    }

    /** Keep the in-memory result through configuration changes; it is never written to saved state. */
    override fun onRetainCustomNonConfigurationInstance(): Any? = resultOutcome

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_ENTRY_SEQUENCE, entrySequence)
        outState.putString(STATE_VISIBLE_ROUTE, visibleRoute.wireName)
        super.onSaveInstanceState(outState)
    }

    private fun routeFromIntent(intent: Intent?): AppRoute? =
        AppRoute.fromWire(intent?.getStringExtra(AppRoute.EXTRA_ROUTE))

    private fun sharedText(intent: Intent?): String? {
        if (intent == null) return null
        val shareIntent = if (intent.action == Intent.ACTION_SEND_MULTIPLE && intent.type == "text/plain") {
            Intent(intent).setAction(Intent.ACTION_SEND)
        } else intent
        val read = ShareIntentReader(this).read(shareIntent) as? ShareIntentRead.Accepted ?: return null
        val accepted = read.share.accept()
        return when (val content = accepted.content) {
            is SharedContent.Text, is SharedContent.Link, is SharedContent.TextAndLink ->
                accepted.analysableOrNull()?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun sharedImageUri(intent: Intent?): String? {
        if (intent == null) return null
        val read = ShareIntentReader(this).read(intent) as? ShareIntentRead.Accepted ?: return null
        return when (val content = read.share.accept().content) {
            is SharedContent.Images -> content.image.uriString
            is SharedContent.MultipleImages -> content.images.firstOrNull()?.uriString
            else -> null
        }
    }

    private companion object {
        const val STATE_ENTRY_SEQUENCE = "trustshield.entry_sequence"
        const val STATE_VISIBLE_ROUTE = "trustshield.visible_route"
    }
}
