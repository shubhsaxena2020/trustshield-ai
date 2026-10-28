package ai.trustshield.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
import ai.trustshield.android.core.model.RiskLabel
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
import ai.trustshield.android.ui.history.MetadataCheck
import ai.trustshield.android.ui.history.MetadataCheckHistory
import ai.trustshield.android.ui.onboarding.OnboardingScreen
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The single activity. It owns nothing but the theme and the navigation host: no scoring, no network,
 * no persistence. Everything visible is a `ui/navigation` or `ui/<Area>Screen` composable, which is what
 * keeps the deterministic engine testable on a plain JVM.
 */
class MainActivity : ComponentActivity() {

    private data class StartupLoad(
        val preferenceStore: SharedPreferencesUserPreferencesStore,
        val preferences: UserPreferences,
        val onboardingComplete: Boolean,
        val requestedEntry: AppRoute?,
        val incomingText: String?,
        val incomingImageUri: String?,
        val entrySequence: Int
    )

    /**
     * Destination requested by the intent that launched (or re-entered) this activity — a share
     * intake or shortcut. `null` when the intent carries no [AppRoute.EXTRA_ROUTE]; the host then
     * starts at Home.
     */
    private class RetainedUiState {
        val requestedEntry = mutableStateOf<AppRoute?>(null)
        val entrySequence = mutableIntStateOf(0)
        val incomingText = mutableStateOf<String?>(null)
        val incomingImageUri = mutableStateOf<String?>(null)
        val resultOutcome = mutableStateOf<ScanOutcome?>(null)
        val visibleRoute = mutableStateOf(AppRoute.HOME)
        val scanState = mutableStateOf(ScanInputState())
        val registeredPayees = mutableStateOf(emptyList<ai.trustshield.android.ui.qr.RegisteredPayee>())
        val shareBusy = mutableStateOf(false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var shareJob: Job? = null
    }
    private lateinit var retainedUi: RetainedUiState

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val retainedAcrossConfiguration = lastCustomNonConfigurationInstance is RetainedUiState
        retainedUi = lastCustomNonConfigurationInstance as? RetainedUiState ?: RetainedUiState()
        if (!retainedAcrossConfiguration) {
            retainedUi.entrySequence.intValue = savedInstanceState?.getInt(STATE_ENTRY_SEQUENCE) ?: 0
        }
        val restoredRoute = AppRoute.fromWire(savedInstanceState?.getString(STATE_VISIBLE_ROUTE))
        val restoredWithoutOutcome = restoredRoute == AppRoute.RESULT && retainedUi.resultOutcome.value == null
        // Edge-to-edge from API 26 up: bar icon colours follow the system light/dark setting, so the
        // status bar is never light-on-light. Insets are consumed by AppNavHost (safeDrawingPadding).
        enableEdgeToEdge()
        // Resolve the stored theme before Compose draws its first frame, avoiding a default-theme flash.
        val initialPreferences = SharedPreferencesUserPreferencesStore.open(applicationContext).load()
        // C04's store. It persists five setting values and nothing else; no analysed text, quote,
        // transcript, OTP, PIN, phone number or UPI id can reach it (see UserPreferences tests).
        setContent {
            // Seed the first composition from the synchronous preference snapshot; startup work below
            // refreshes the remaining entry and onboarding metadata off the main thread.
            var preferenceStore by remember { mutableStateOf<SharedPreferencesUserPreferencesStore?>(null) }
            var preferences: UserPreferences by remember { mutableStateOf(initialPreferences) }
            var startupReady by remember { mutableStateOf(false) }
            var onboardingComplete by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                val startup = withContext(Dispatchers.IO) {
                    val store = SharedPreferencesUserPreferencesStore.open(applicationContext)
                    val readIncomingIntent = savedInstanceState == null || !retainedAcrossConfiguration
                    val parsedSharedText = if (readIncomingIntent) sharedText(intent) else null
                    val parsedSharedImageUri = if (readIncomingIntent) sharedImageUri(intent) else null
                    var loadedEntry = routeFromIntent(intent)
                    var loadedSequence = retainedUi.entrySequence.intValue
                    if (savedInstanceState == null && (parsedSharedText != null || parsedSharedImageUri != null)) {
                        loadedEntry = AppRoute.HOME
                        loadedSequence++
                    }
                    val explicitRoute = routeFromIntent(intent)
                    if (restoredWithoutOutcome && parsedSharedText == null && parsedSharedImageUri == null &&
                        (explicitRoute == null || explicitRoute == AppRoute.RESULT)
                    ) {
                        // A process-death restore cannot recover the in-memory verdict without
                        // persisting the scanned text. Return Home instead of restoring an empty
                        // Result destination.
                        loadedEntry = AppRoute.HOME
                        loadedSequence++
                    }
                    StartupLoad(
                        store,
                        store.load(),
                        applicationContext.getSharedPreferences(ONBOARDING_PREFERENCES, MODE_PRIVATE)
                            .getBoolean(ONBOARDING_COMPLETE, false),
                        if (savedInstanceState == null || !retainedAcrossConfiguration) loadedEntry else retainedUi.requestedEntry.value,
                        if (savedInstanceState == null || !retainedAcrossConfiguration) parsedSharedText else null,
                        if (savedInstanceState == null || !retainedAcrossConfiguration) parsedSharedImageUri else null,
                        loadedSequence
                    )
                }
                preferenceStore = startup.preferenceStore
                preferences = startup.preferences
                onboardingComplete = startup.onboardingComplete
                if (savedInstanceState == null || !retainedAcrossConfiguration) {
                    retainedUi.requestedEntry.value = startup.requestedEntry
                    retainedUi.incomingText.value = startup.incomingText
                    retainedUi.incomingImageUri.value = startup.incomingImageUri
                    retainedUi.entrySequence.intValue = startup.entrySequence
                }
                startupReady = true
            }
            var scanState by retainedUi.scanState
            var historyStore by remember { mutableStateOf<MetadataCheckHistory?>(null) }
            var historyEntries by remember { mutableStateOf(emptyList<MetadataCheck>()) }
            LaunchedEffect(Unit) {
                val (store, entries) = withContext(Dispatchers.IO) {
                    MetadataCheckHistory(applicationContext).let { it to it.read() }
                }
                historyStore = store
                historyEntries = entries
            }

            fun recordCheck(outcome: ScanOutcome) {
                val entry = MetadataCheck(
                    checkedAtMillis = System.currentTimeMillis(),
                    verdict = outcome.result.risk.label,
                    score = outcome.risk.score.takeUnless { outcome.result.risk.label == RiskLabel.UNABLE_TO_VERIFY }
                )
                historyStore?.add(entry)
                historyEntries = (listOf(entry) + historyEntries).take(30)
            }

            LaunchedEffect(retainedUi.entrySequence.intValue, retainedUi.incomingText.value, retainedUi.shareBusy.value, startupReady) {
                if (!startupReady) return@LaunchedEffect
                if (retainedUi.shareBusy.value) return@LaunchedEffect
                val shared = retainedUi.incomingText.value ?: return@LaunchedEffect
                // Consume before analysis so a configuration change cannot run the same share twice.
                retainedUi.incomingText.value = null
                val sequence = retainedUi.entrySequence.intValue
                retainedUi.shareBusy.value = true
                scanState = reduce(scanState, ScanInputEvent.TextChanged(shared))
                val sensitivity = when (preferences.sensitivity) {
                    ai.trustshield.android.data.preferences.Sensitivity.LOW -> LocalScorer.Sensitivity.LOW
                    ai.trustshield.android.data.preferences.Sensitivity.BALANCED -> LocalScorer.Sensitivity.STANDARD
                    ai.trustshield.android.data.preferences.Sensitivity.HIGH -> LocalScorer.Sensitivity.HIGH
                }
                retainedUi.shareJob = retainedUi.scope.launch {
                    try {
                        val outcome = when (val share = withContext(Dispatchers.Default) {
                            ShareIngestCoordinator(
                                pipeline = LocalScanPipeline(sensitivity = sensitivity)
                            ).ingest(ShareIngestRequest.Text(shared))
                        }) {
                            is ShareIngestOutcome.Analysed -> share.outcome
                            else -> null
                        }
                        if (sequence == retainedUi.entrySequence.intValue) {
                            retainedUi.resultOutcome.value = outcome
                            retainedUi.requestedEntry.value = AppRoute.RESULT
                            retainedUi.entrySequence.intValue++
                        }
                    } finally {
                        retainedUi.shareBusy.value = false
                        retainedUi.shareJob = null
                    }
                }
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
                        preferences = updated
                        val activeStore = preferenceStore
                        activeStore?.save(updated)
                    }
                ) {
                    // The back stack itself is restored by AppNavHost via rememberSaveable, so
                    // process recreation returns the user to the same screen; `requestedEntry` only
                    // matters when nothing was saved, or when a genuinely new intent arrives.
                    if (!startupReady) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Image(
                                painter = painterResource(R.drawable.trustshield_logo_icon),
                                contentDescription = "TrustShield AI",
                                contentScale = ContentScale.Fit,
                                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
                                modifier = Modifier.size(96.dp)
                            )
                        }
                    } else if (!onboardingComplete) {
                        OnboardingScreen(
                            language = preferences.language,
                            onLanguageChange = { language ->
                                val updated = preferences.copy(language = language)
                                preferences = updated
                                preferenceStore?.save(updated)
                            },
                            onComplete = {
                                getSharedPreferences(ONBOARDING_PREFERENCES, MODE_PRIVATE).edit()
                                    .putBoolean(ONBOARDING_COMPLETE, true)
                                    .apply()
                                onboardingComplete = true
                            }
                        )
                    } else AppNavHost(entry = retainedUi.requestedEntry.value, entrySequence = retainedUi.entrySequence.intValue) { route ->
                        SideEffect { retainedUi.visibleRoute.value = route }
                        TrustShieldRouteContent(
                            route = route,
                            language = resolveLanguage(preferences.language, Locale.getDefault().toLanguageTag()),
                            english = resolveLanguage(preferences.language, Locale.getDefault().toLanguageTag()) == AppLanguage.ENGLISH,
                            scanState = scanState,
                            resultOutcome = retainedUi.resultOutcome.value,
                            sharedImageUri = retainedUi.incomingImageUri.value,
                            onSharedImageConsumed = { retainedUi.incomingImageUri.value = null },
                            onImageResult = { retainedUi.resultOutcome.value = it; recordCheck(it) },
                            historyEntries = historyEntries,
                            registeredPayees = retainedUi.registeredPayees.value,
                            onRegisteredPayeesChanged = { retainedUi.registeredPayees.value = it },
                            onClearHistory = {
                                historyStore?.clear()
                                historyEntries = emptyList()
                            },
                            onScanEvent = { event -> scanState = reduce(scanState, event) },
                            onCheck = { text ->
                                val sensitivity = when (preferences.sensitivity) {
                                    ai.trustshield.android.data.preferences.Sensitivity.LOW -> LocalScorer.Sensitivity.LOW
                                    ai.trustshield.android.data.preferences.Sensitivity.BALANCED -> LocalScorer.Sensitivity.STANDARD
                                    ai.trustshield.android.data.preferences.Sensitivity.HIGH -> LocalScorer.Sensitivity.HIGH
                                }
                                LocalScanPipeline(sensitivity = sensitivity).analyzeText(text).also { outcome ->
                                    retainedUi.resultOutcome.value = outcome
                                    recordCheck(outcome)
                                }
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
        retainedUi.incomingText.value = sharedText(intent)
        retainedUi.incomingImageUri.value = sharedImageUri(intent)
        if (retainedUi.incomingText.value != null) {
            retainedUi.requestedEntry.value = AppRoute.HOME
            retainedUi.entrySequence.intValue++
        } else if (retainedUi.incomingImageUri.value != null) {
            retainedUi.requestedEntry.value = AppRoute.HOME
            retainedUi.entrySequence.intValue++
        }
        routeFromIntent(intent)?.let { route ->
            retainedUi.requestedEntry.value = route
            retainedUi.entrySequence.intValue++
        }
    }

    /** Keep the in-memory result through configuration changes; it is never written to saved state. */
    override fun onRetainCustomNonConfigurationInstance(): Any? = retainedUi

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_ENTRY_SEQUENCE, retainedUi.entrySequence.intValue)
        outState.putString(STATE_VISIBLE_ROUTE, retainedUi.visibleRoute.value.wireName)
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
        const val ONBOARDING_PREFERENCES = "trustshield_onboarding"
        const val ONBOARDING_COMPLETE = "complete"
        const val STATE_ENTRY_SEQUENCE = "trustshield.entry_sequence"
        const val STATE_VISIBLE_ROUTE = "trustshield.visible_route"
    }
}
