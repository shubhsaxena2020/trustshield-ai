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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
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
import ai.trustshield.android.ui.home.ImageScanController
import ai.trustshield.android.ui.home.OcrRecognizer
import ai.trustshield.android.ui.home.rememberImageScanLauncher
import ai.trustshield.android.ui.home.isContentUri
import ai.trustshield.android.input.share.ContentResolverByteSource
import ai.trustshield.android.input.share.MlKitTextRecognizer
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
import ai.trustshield.android.ui.call.CallShieldHost
import ai.trustshield.android.input.speech.CallSpeechSession
import ai.trustshield.android.input.speech.SpeechSessionCoordinator
import ai.trustshield.android.input.speech.SpeechCapabilityProbe
import ai.trustshield.android.input.speech.SpeechCapabilityRules
import ai.trustshield.android.input.speech.SpeechLanguage
import ai.trustshield.android.input.speech.SpeechConsent
import ai.trustshield.android.input.speech.SpeechCapabilityReport
import ai.trustshield.android.input.speech.MicPermission as SpeechMicPermission
import ai.trustshield.android.input.speech.UtteranceSink
import ai.trustshield.android.input.speech.AndroidSpeechRecognizerHandle
import ai.trustshield.android.ui.qr.QrGuardScreen
import ai.trustshield.android.ui.qr.QrGuardHost
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
    onCheck: (String) -> Unit = {},
    onImageResult: (ScanOutcome) -> Unit = {},
    sharedImageUri: String? = null,
    onSharedImageConsumed: () -> Unit = {}
) {
    val nav = LocalNavActions.current
    val clipboard = LocalClipboardManager.current
    when (route) {
        AppRoute.HOME -> HomeRouteHost(
            english = english,
            scanState = scanState,
            onScanEvent = onScanEvent,
            onCheck = { text -> onCheck(text); nav.navigate(AppRoute.RESULT) },
            onImageResult = { outcome -> onImageResult(outcome); nav.navigate(AppRoute.RESULT) },
            sharedImageUri = sharedImageUri,
            onSharedImageConsumed = onSharedImageConsumed,
            clipboardText = { clipboard.getText()?.text },
            onOpenSettings = { nav.navigate(AppRoute.SETTINGS) },
            onOpenCall = { nav.navigate(AppRoute.CALL_SHIELD) },
            onOpenQr = { nav.navigate(AppRoute.QR_GUARD) }
        )
        AppRoute.RESULT -> ResultRouteHost(english, resultOutcome)
        AppRoute.CALL_SHIELD -> CallShieldRouteHost()
        AppRoute.QR_GUARD -> QrGuardRouteHost()
        AppRoute.SETTINGS -> SettingsRouteHost()
    }
}

@Composable
private fun HomeRouteHost(
    english: Boolean,
    scanState: ScanInputState,
    onScanEvent: (ScanInputEvent) -> Unit,
    onCheck: (String) -> Unit,
    onImageResult: (ScanOutcome) -> Unit,
    sharedImageUri: String?,
    onSharedImageConsumed: () -> Unit,
    clipboardText: () -> String?,
    onOpenSettings: () -> Unit,
    onOpenCall: () -> Unit,
    onOpenQr: () -> Unit,
) {
    val context = LocalContext.current
    val sensitivity = when (LocalUserPreferences.current.sensitivity) {
        Sensitivity.LOW -> LocalScorer.Sensitivity.LOW
        Sensitivity.BALANCED -> LocalScorer.Sensitivity.STANDARD
        Sensitivity.HIGH -> LocalScorer.Sensitivity.HIGH
    }
    val recognizer = remember { MlKitTextRecognizer() }
    DisposableEffect(recognizer) { onDispose { recognizer.close() } }
    val controller = remember(recognizer, sensitivity) {
        ImageScanController(
            pipeline = ai.trustshield.android.core.analysis.LocalScanPipeline(sensitivity = sensitivity),
            recognizer = OcrRecognizer { bytes, mime -> recognizer.recognize(bytes, mime) },
            readBytes = { raw ->
                if (isContentUri(raw)) ContentResolverByteSource(context.contentResolver, Uri.parse(raw)) else null
            }
        )
    }
    var imageError by remember(controller) { mutableStateOf<ai.trustshield.android.ui.home.ImageScanError?>(null) }
    val actions = rememberImageScanLauncher(controller, english) { imageState ->
        imageError = imageState.error
        imageState.outcome?.let(onImageResult)
    }
    LaunchedEffect(sharedImageUri) {
        val image = sharedImageUri ?: return@LaunchedEffect
        actions.acceptSharedUri(image)
        onSharedImageConsumed()
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ScanScreen(
            state = scanState,
            english = english,
            onEvent = onScanEvent,
            onCheck = onCheck,
            onPickImage = { actions.launch() },
            onOpenSettings = onOpenSettings,
            modifier = Modifier.weight(1f),
            clipboardText = clipboardText
        )
        imageError?.let { error ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(error.message(english), color = MaterialTheme.colorScheme.error)
                Text(error.nextStep(english), style = MaterialTheme.typography.bodySmall)
            }
        }
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onOpenCall, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Call Shield") }
            OutlinedButton(onClick = onOpenQr, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("QR Guard") }
        }
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
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val session = remember { CallSpeechSession(consentGranted = true) }
    var capability by remember {
        mutableStateOf(SpeechCapabilityRules.evaluate(SpeechCapabilityProbe.probe(context), SpeechLanguage.DEFAULT))
    }
    val coordinator = remember(session) {
        SpeechSessionCoordinator(session) { _ ->
            AndroidSpeechRecognizerHandle(context, session, capability, SpeechLanguage.DEFAULT)
        }
    }
    val host = remember(coordinator) { CallShieldHost(coordinator) }
    val prefs = LocalUserPreferences.current
    val sink = LocalPreferencesSink.current
    var consent by remember { mutableStateOf(SpeechConsent.initial()) }
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var state by remember(host) {
        mutableStateOf(
            host.state.copy(
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
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
    }

    Column(modifier = Modifier.fillMaxSize()) {
    CallShieldScreen(
        state = state,
        modifier = Modifier.weight(1f),
        onEvent = { event ->
            when (event) {
                CallEvent.GiveConsent -> {
                    consent = consent.grant()
                    state = CallShieldReducer.reduce(state, event)
                }
                CallEvent.MicPermissionGranted -> permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
                CallEvent.Start -> {
                    host.onCapability(consent, capability, if (micGranted) ai.trustshield.android.ui.call.MicPermission.GRANTED else ai.trustshield.android.ui.call.MicPermission.DENIED)
                    host.onStartRequested()
                    state = host.state
                }
                CallEvent.Stop -> { host.onStop(); state = host.state }
                CallEvent.Pause -> { host.onHostPaused(); state = host.state }
                CallEvent.Resume -> { host.onStartRequested(); state = host.state }
                is CallEvent.SetLanguage -> {
                    state = CallShieldReducer.reduce(state, event)
                    sink(prefs.copy(language = if (event.language == CallLanguage.ENGLISH) AppLanguage.ENGLISH else AppLanguage.HINGLISH))
                }
                is CallEvent.SetHaptics -> {
                    state = CallShieldReducer.reduce(state, event)
                    sink(prefs.copy(haptics = event.enabled))
                }
                is CallEvent.SetReducedMotion -> {
                    state = CallShieldReducer.reduce(state, event)
                    sink(prefs.copy(reducedMotion = event.reducedMotion))
                }
                else -> state = CallShieldReducer.reduce(state, event)
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
    LaunchedEffect(consent, micGranted) {
        val mic = if (micGranted) SpeechMicPermission.Granted else SpeechMicPermission.Denied
        capability = SpeechCapabilityRules.evaluate(SpeechCapabilityProbe.probe(context, mic), SpeechLanguage.DEFAULT)
        host.onCapability(consent, capability, if (micGranted) ai.trustshield.android.ui.call.MicPermission.GRANTED else ai.trustshield.android.ui.call.MicPermission.DENIED)
        state = host.state.copy(
            language = if (prefs.language == AppLanguage.ENGLISH) CallLanguage.ENGLISH else CallLanguage.HINGLISH,
            hapticsEnabled = prefs.haptics,
            reducedMotion = prefs.reducedMotion
        )
    }
    remember(host) {
        session.setUtteranceSink(UtteranceSink { utterance ->
            if (utterance.isFinal) host.onUtterance(utterance.text, utterance.confidence, utterance.tsMs)
            state = host.state
        })
        host
    }
    DisposableEffect(lifecycleOwner, host) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { host.onHostPaused(); state = host.state }
                Lifecycle.Event.ON_RESUME -> { host.onHostResumed(); state = host.state }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            session.setUtteranceSink(null)
            host.onCleared()
        }
    }
    LaunchedEffect(host) {
        while (true) {
            delay(1_000)
            host.onTick(state.elapsedSeconds + 1)
            state = host.state
        }
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
    QrGuardHost()
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
