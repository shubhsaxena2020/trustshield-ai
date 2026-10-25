package ai.trustshield.android.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import ai.trustshield.android.core.analysis.ScanOutcome
import ai.trustshield.android.core.model.Confidence
import ai.trustshield.android.core.model.RiskLabel
import ai.trustshield.android.core.score.LocalScorer
import ai.trustshield.android.data.preferences.AppLanguage
import ai.trustshield.android.data.preferences.UserPreferences
import ai.trustshield.android.data.preferences.Sensitivity
import ai.trustshield.android.ui.home.ScanInputEvent
import ai.trustshield.android.ui.home.ScanInputState
import ai.trustshield.android.ui.home.ScanScreen
import ai.trustshield.android.ui.result.ActionPriority
import ai.trustshield.android.ui.result.ConfidenceLevel
import ai.trustshield.android.ui.result.EvidenceSeverity
import ai.trustshield.android.ui.result.InputCoverage
import ai.trustshield.android.ui.result.ResultActionInput
import ai.trustshield.android.ui.result.ResultEvidenceInput
import ai.trustshield.android.ui.result.ResultInput
import ai.trustshield.android.ui.call.CallLanguage
import ai.trustshield.android.ui.call.RecognizerStatus
import ai.trustshield.android.ui.call.CallEvent
import ai.trustshield.android.ui.call.CallShieldReducer
import ai.trustshield.android.ui.call.CallShieldScreen
import ai.trustshield.android.ui.call.CallUiState
import ai.trustshield.android.ui.call.CallDemoRunner
import ai.trustshield.android.ui.call.CallDemoScript
import ai.trustshield.android.ui.call.DemoProgress
import ai.trustshield.android.ui.qr.QrGuardScreen
import ai.trustshield.android.ui.qr.QrEvent
import ai.trustshield.android.ui.qr.CameraUiStatus
import ai.trustshield.android.ui.qr.QrReducer
import ai.trustshield.android.input.qr.SimulatedQrFixtures
import ai.trustshield.android.input.qr.CameraXQrScanner
import ai.trustshield.android.input.qr.CameraPermissionState
import ai.trustshield.android.input.qr.AnalyzerEvent
import ai.trustshield.android.input.qr.UpiQrParser
import ai.trustshield.android.ui.qr.QrLanguage
import ai.trustshield.android.ui.qr.QrUiState
import ai.trustshield.android.ui.result.ResultPresenter
import ai.trustshield.android.ui.result.ResultScreen
import ai.trustshield.android.ui.result.UiLanguage
import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.ToneCopy
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
 * The state comes from the in-memory local analysis result; no sample fixture is used in the app.
 */
@Composable
fun ResultRouteHost(english: Boolean, outcome: ScanOutcome?) {
    val nav = LocalNavActions.current
    val prefs = LocalUserPreferences.current
    val sink = LocalPreferencesSink.current
    val state = remember(outcome) { outcome?.let(AnalysisToResult::build) }
    var language by remember(english) { mutableStateOf(UiLanguage.of(english)) }

    if (state == null || outcome == null) return

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (outcome.result.risk.label == RiskLabel.UNABLE_TO_VERIFY) {
                "Risk score: Unable to Verify"
            } else {
                (if (english) "Risk score: " else "Risk score: ") + "${outcome.risk.score}/100"
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
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
        onBack = { nav.back() },
        modifier = Modifier.weight(1f)
    )
    }
}

/** Adapts the real local engine output to the Result screen's quote-verifying presentation model. */
object AnalysisToResult {
    fun build(outcome: ScanOutcome) = ResultPresenter.build(
        ResultInput(
            sourceText = outcome.analyzedText,
            coverage = if (outcome.result.risk.label == RiskLabel.UNABLE_TO_VERIFY) {
                InputCoverage.UNREADABLE
            } else InputCoverage.READABLE_SUPPORTED,
            riskTone = when (outcome.result.risk.label) {
                RiskLabel.LOW_RISK -> RiskTone.GREEN
                RiskLabel.CAUTION -> RiskTone.AMBER
                RiskLabel.SUSPICIOUS, RiskLabel.HIGH_RISK -> RiskTone.RED
                RiskLabel.UNABLE_TO_VERIFY -> RiskTone.NEUTRAL
            },
            confidence = when (outcome.result.risk.confidence) {
                Confidence.LOW -> ConfidenceLevel.LOW
                Confidence.MEDIUM -> ConfidenceLevel.MEDIUM
                Confidence.HIGH -> ConfidenceLevel.HIGH
            },
            evidence = outcome.result.evidence.map { item ->
                ResultEvidenceInput(
                    id = item.id,
                    module = when (item.module) {
                        "stage_rules" -> "Pattern check"
                        "legit_indicators" -> "Legitimate signal"
                        "local_fusion" -> "Local analysis"
                        else -> "Local check"
                    },
                    titleHinglish = item.titleHinglish,
                    titleEnglish = item.titleHinglish,
                    detailHinglish = item.detail,
                    detailEnglish = item.detail,
                    severity = when (item.strength) {
                        ai.trustshield.android.core.model.Strength.WEAK -> EvidenceSeverity.INFO
                        ai.trustshield.android.core.model.Strength.MODERATE -> EvidenceSeverity.WATCH
                        ai.trustshield.android.core.model.Strength.STRONG -> EvidenceSeverity.STRONG
                    },
                    quote = item.quote.orEmpty()
                )
            },
            actions = outcome.result.actions.mapIndexed { index, line ->
                ResultActionInput(
                    id = "local-action-$index",
                    labelHinglish = line,
                    labelEnglish = line,
                    detailHinglish = "Yeh agla kadam aapke faisle ke liye hai.",
                    detailEnglish = "This next step is for you to consider.",
                    priority = if (index == 0) ActionPriority.PRIMARY else ActionPriority.SECONDARY
                )
            },
            limitations = outcome.result.limitations.map { ToneCopy(it, it) },
            backendAvailable = false
        )
    )
}

/** Route map for the shipped screens. No preview-only or placeholder destination is reachable. */
@Composable
fun TrustShieldRouteContent(
    route: AppRoute,
    english: Boolean = false,
    scanState: ScanInputState = ScanInputState(),
    resultOutcome: ScanOutcome? = null,
    onScanEvent: (ScanInputEvent) -> Unit = {},
    onCheck: (String) -> Unit = {}
) {
    val nav = LocalNavActions.current
    val clipboard = LocalClipboardManager.current
    when (route) {
        AppRoute.HOME -> Column(modifier = Modifier.fillMaxSize()) {
            ScanScreen(
                state = scanState,
                english = english,
                onEvent = onScanEvent,
                onCheck = { text ->
                    onCheck(text)
                    nav.navigate(AppRoute.RESULT)
                },
                onPickImage = {},
                onOpenSettings = { nav.navigate(AppRoute.SETTINGS) },
                modifier = Modifier.weight(1f),
                clipboardText = { clipboard.getText()?.text }
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { nav.navigate(AppRoute.CALL_SHIELD) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("Call Shield") }
                OutlinedButton(
                    onClick = { nav.navigate(AppRoute.QR_GUARD) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("QR Guard") }
            }
        }
        AppRoute.RESULT -> ResultRouteHost(english, resultOutcome)
        AppRoute.CALL_SHIELD -> CallShieldRouteHost()
        AppRoute.QR_GUARD -> QrGuardRouteHost()
        AppRoute.SETTINGS -> SettingsRouteHost()
    }
}

/**
 * Call Shield destination host.
 *
 * Call Shield handles speech heard while the app is visible; Android cannot capture the remote side
 * of a cellular or VoIP call through this screen.
 */
@Composable
fun CallShieldRouteHost() {
    val prefs = LocalUserPreferences.current
    val sink = LocalPreferencesSink.current
    var state by remember {
        mutableStateOf(
            CallUiState(
                recognizer = RecognizerStatus.UNAVAILABLE,
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
    val demoSensitivity = when (prefs.sensitivity) {
        Sensitivity.LOW -> LocalScorer.Sensitivity.LOW
        Sensitivity.BALANCED -> LocalScorer.Sensitivity.STANDARD
        Sensitivity.HIGH -> LocalScorer.Sensitivity.HIGH
    }
    val demoRunner = remember(demoSensitivity) { CallDemoRunner(sensitivity = demoSensitivity) }
    var demoProgress by remember { mutableStateOf(demoRunner.progress()) }
    var demoOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
    CallShieldScreen(
        state = state,
        modifier = Modifier.weight(1f),
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
        onOpenStagedDemo = { demoOpen = true },
        onTypeTranscript = null
    )
    OutlinedButton(
        onClick = { demoOpen = !demoOpen },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp)
    ) { Text(if (demoOpen) "Close demo script" else "Demo script mode") }
    if (demoOpen) {
        CallDemoScriptPanel(
            progress = demoProgress,
            onAdvance = { demoProgress = demoRunner.advance() },
            onBack = { demoProgress = demoRunner.back() },
            onReset = { demoProgress = demoRunner.reset() }
        )
    }
    }
}

@Composable
private fun CallDemoScriptPanel(
    progress: DemoProgress,
    onAdvance: () -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit
) {
    val step = progress.steps.lastOrNull()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Scripted demo only. No microphone or call recording.", style = MaterialTheme.typography.bodySmall)
        if (step != null) {
            Text("${step.position.labelHinglish} · ${step.score}/100 · ${step.confidence}", style = MaterialTheme.typography.titleMedium)
            Text(step.line.text, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Tap through the sample lines; each step is scored locally.", style = MaterialTheme.typography.bodyMedium)
        }
        Text(CallDemoScript.honestLimits.take(3).joinToString(" "), style = MaterialTheme.typography.bodySmall)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, enabled = progress.steps.isNotEmpty()) { Text("Back") }
            Button(onClick = onAdvance, enabled = !progress.finished) { Text(if (progress.finished) "Finished" else "Next line") }
            OutlinedButton(onClick = onReset) { Text("Reset") }
        }
    }
}

/**
 * QR Guard destination host.
 *
 * Simulation is explicitly labelled and runs through the same reducer as decoded frames. Payloads
 * remain inert data; no destination is opened.
 */
@Composable
fun QrGuardRouteHost() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
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
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        state = QrReducer.reduce(state, if (granted) QrEvent.PermissionGranted else QrEvent.PermissionDenied)
    }
    val scanner = remember(context) {
        CameraXQrScanner(context, onEvent = { event ->
            val atMs = android.os.SystemClock.elapsedRealtime()
            state = when (event) {
                is AnalyzerEvent.Decoded -> {
                    val decoded = UpiQrParser.parse(event.payload)
                    val payee = decoded.payeeVpa
                    if (decoded.isUpi && payee != null) {
                        QrReducer.reduce(state, QrEvent.ValidFrame(payee, decoded.payeeName, atMs))
                    } else QrReducer.reduce(state, QrEvent.UnreadableFrame(atMs))
                }
                is AnalyzerEvent.Unreadable -> QrReducer.reduce(state, QrEvent.UnreadableFrame(atMs))
                is AnalyzerEvent.NoDecode -> state
                else -> state
            }
        })
    }
    DisposableEffect(scanner, previewView, cameraGranted, lifecycleOwner) {
        val preview = previewView
        if (preview != null && cameraGranted) {
            state = QrReducer.reduce(state, QrEvent.PermissionGranted)
            scanner.start(lifecycleOwner, preview.surfaceProvider, CameraPermissionState.Granted)
        }
        onDispose { scanner.stop() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (cameraGranted) {
            AndroidView(
                factory = { view -> PreviewView(view).also { previewView = it } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 280.dp)
            )
        } else {
            OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Enable camera")
            }
        }
    QrGuardScreen(
        state = state,
        onEvent = { event ->
            state = QrReducer.reduce(state, event)
            if (event is QrEvent.SetLanguage) {
                val language = if (event.language == QrLanguage.ENGLISH) AppLanguage.ENGLISH else AppLanguage.HINGLISH
                sink(prefs.copy(language = language))
            }
        },
        onSimulateScan = {
            state = QrUiState(language = state.language)
            state = QrReducer.reduce(state, QrEvent.RegisterPayee(SimulatedQrFixtures.MERCHANT_PAYEE, "Demo shop"))
            state = QrReducer.reduce(state, QrEvent.PermissionGranted)
            state = QrReducer.reduce(state, QrEvent.StartWatch(1L))
            repeat(3) { index ->
                state = QrReducer.reduce(state, QrEvent.ValidFrame(
                    payeeAddress = SimulatedQrFixtures.STICKER_PAYEE,
                    payeeName = "Demo shop",
                    atMs = (index + 1L) * 1000L
                ))
            }
            state = state.copy(camera = CameraUiStatus.UNKNOWN)
        }
    )
    }
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
