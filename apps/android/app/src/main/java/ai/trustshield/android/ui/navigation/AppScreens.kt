package ai.trustshield.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import ai.trustshield.android.ui.call.DemoRiskPosition
import ai.trustshield.android.ui.call.CallShieldHost
import ai.trustshield.android.ui.call.CallShieldCopy
import ai.trustshield.android.ui.call.withStoredChoices
import ai.trustshield.android.ui.call.CallStage
import ai.trustshield.android.ui.call.forLanguage
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
import ai.trustshield.android.ui.theme.container
import ai.trustshield.android.ui.theme.onContainer
import ai.trustshield.android.ui.theme.accent
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
    // Issue #115: this host used to draw a `Text("Risk score: N/100")` *before* the panel, so a bare
    // debug line sat above the header row, in the status-bar zone. The number is deliberately not part
    // of the visual design -- the styled card carries a coarse four-step band, never a figure
    // (UI_SPEC "Tokens and motion": no number implies precision the local rules do not have). The
    // score therefore travels as a TalkBack-only label: nothing is drawn above the header row, and the
    // panel's single announcement still gives the number to a screen reader.
    // A score is only announced when the engine could actually verify the band; Unable to Verify
    // stays a refusal to state a figure, not a `0/100`.
    val announcedScore: Int? =
        if (outcome.result.risk.label == RiskLabel.UNABLE_TO_VERIFY) null else outcome.risk.score
    ResultScreen(
        state = state,
        language = language,
        riskScore = announcedScore,
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
            backendAvailable = false,
            riskScore = outcome.risk.score.takeUnless {
                outcome.result.risk.label == RiskLabel.UNABLE_TO_VERIFY
            }
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
    // Issue #138. `CallShieldHost` owns the call session, not the user's presentation choices, and
    // it is built before this screen reads any preference — so `host.state` always carries
    // `CallUiState`'s defaults (HINGLISH, haptics on). Copying it verbatim (start / stop / pause /
    // resume / ASR utterance / the lifecycle observer below) therefore reset the screen to Hinglish
    // and re-enabled haptics behind an English, haptics-off preference. Measured on `emulator-5554`
    // with `settings.language = ENGLISH` persisted across a force-stop: the screen rendered Hinglish
    // and its language button still offered "English". Every host snapshot now goes through `synced`
    // so the stored choices survive; the session's own fields are passed through untouched.
    //
    // The three values are `rememberUpdatedState`-backed on purpose: the 1-second tick loop and the
    // utterance sink are launched once and keep their closure, so a plain `prefs` capture would let a
    // later tick write a stale language back over a change made after the effect started.
    val storedLanguage by rememberUpdatedState(
        if (prefs.language == AppLanguage.ENGLISH) CallLanguage.ENGLISH else CallLanguage.HINGLISH
    )
    val storedHaptics by rememberUpdatedState(prefs.haptics)
    val storedReducedMotion by rememberUpdatedState(prefs.reducedMotion)
    fun synced(from: CallUiState): CallUiState =
        from.withStoredChoices(storedLanguage, storedHaptics, storedReducedMotion)
    var consent by remember { mutableStateOf(SpeechConsent.initial()) }
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var state by remember(host) {
        mutableStateOf(
            synced(host.state.copy(recognizer = RecognizerStatus.UNAVAILABLE))
        )
    }
    val demoSensitivity = when (prefs.sensitivity) {
        Sensitivity.LOW -> LocalScorer.Sensitivity.LOW
        Sensitivity.BALANCED -> LocalScorer.Sensitivity.STANDARD
        Sensitivity.HIGH -> LocalScorer.Sensitivity.HIGH
    }
    val demoRunner = remember(demoSensitivity) { CallDemoRunner(sensitivity = demoSensitivity) }
    var demoProgress by remember(demoRunner) { mutableStateOf(demoRunner.progress()) }
    var demoOpen by remember { mutableStateOf(false) }
    var demoStarted by remember { mutableStateOf(false) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
    }

    if (demoOpen) {
        CallDemoExperience(
            progress = demoProgress,
            english = prefs.language == AppLanguage.ENGLISH,
            hapticsEnabled = prefs.haptics,
            onToggleLanguage = {
                sink(prefs.copy(language = if (prefs.language == AppLanguage.ENGLISH) AppLanguage.HINGLISH else AppLanguage.ENGLISH))
            },
            onClose = { demoOpen = false },
            onAdvance = { demoStarted = true; demoProgress = demoRunner.advance() },
            onBack = { demoProgress = demoRunner.back() },
            onReset = { demoStarted = true; demoProgress = demoRunner.reset() },
            showResetLow = demoStarted && demoProgress.steps.isEmpty()
        )
    } else {
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
                    state = synced(host.state)
                }
                CallEvent.Stop -> { host.onStop(); state = synced(host.state) }
                CallEvent.Pause -> { host.onHostPaused(); state = synced(host.state) }
                CallEvent.Resume -> { host.onStartRequested(); state = synced(host.state) }
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
        onClick = { demoOpen = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp)
    ) {
        Text(
            CallShieldCopy.stagedDemo.forLanguage(
                if (prefs.language == AppLanguage.ENGLISH) CallLanguage.ENGLISH else CallLanguage.HINGLISH
            )
        )
    }
    }
    LaunchedEffect(consent, micGranted) {
        val mic = if (micGranted) SpeechMicPermission.Granted else SpeechMicPermission.Denied
        capability = SpeechCapabilityRules.evaluate(SpeechCapabilityProbe.probe(context, mic), SpeechLanguage.DEFAULT)
        host.onCapability(consent, capability, if (micGranted) ai.trustshield.android.ui.call.MicPermission.GRANTED else ai.trustshield.android.ui.call.MicPermission.DENIED)
        state = synced(host.state)
    }
    remember(host) {
        session.setUtteranceSink(UtteranceSink { utterance ->
            if (utterance.isFinal) host.onUtterance(utterance.text, utterance.confidence, utterance.tsMs)
            state = synced(host.state)
        })
        host
    }
    DisposableEffect(lifecycleOwner, host) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> { host.onHostPaused(); state = synced(host.state) }
                Lifecycle.Event.ON_RESUME -> { host.onHostResumed(); state = synced(host.state) }
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
            state = synced(host.state)
        }
    }
    }
}

@Composable
private fun CallDemoExperience(
    progress: DemoProgress,
    english: Boolean,
    hapticsEnabled: Boolean,
    onToggleLanguage: () -> Unit,
    onClose: () -> Unit,
    onAdvance: () -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
    showResetLow: Boolean
) {
    val view = LocalView.current
    val step = progress.steps.lastOrNull()
    val tone = when (step?.position ?: DemoRiskPosition.LOW) {
        DemoRiskPosition.LOW -> RiskTone.GREEN
        DemoRiskPosition.MEDIUM -> RiskTone.AMBER
        DemoRiskPosition.HIGH, DemoRiskPosition.CRITICAL -> RiskTone.RED
    }

    // The demo vibrates only when the engine says "this step enters CRITICAL" AND the user has haptics
    // switched on in Settings (issue #119 defect B review: the screen used to buzz even when haptics
    // were disabled). `step.haptics` is true only on the transition into CRITICAL, so a repeated
    // CRITICAL step never re-fires.
    LaunchedEffect(step?.index, step?.haptics, hapticsEnabled) {
        if (hapticsEnabled && step?.haptics == true) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (english) "Close demo" else "Demo band karein")
            }
            TextButton(onClick = onToggleLanguage, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (english) "Hinglish" else "English")
            }
        }

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = step?.let { "${it.position.label(english)} risk, ${it.score} out of 100" }
                        ?: if (showResetLow) "Low risk, transcript cleared" else if (english) "Demo ready" else "Demo taiyaar hai"
                },
                color = tone.container(),
                contentColor = tone.onContainer(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (english) "Call Shield · Demo · ${step?.let { "Step ${it.index + 1} of ${CallDemoScript.size}" } ?: "Ready"}"
                        else "Call Shield · Script demo · ${step?.let { "${it.index + 1} / ${CallDemoScript.size}" } ?: "Taiyaar"}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        step?.position?.label(english)
                            ?: if (showResetLow) DemoRiskPosition.LOW.label(english)
                            else if (english) "Ready for line 1" else "Pehli line ke liye taiyaar",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    DemoPositionBand(
                        position = step?.position ?: if (showResetLow) DemoRiskPosition.LOW else null,
                        tone = tone,
                        english = english,
                    )
                    if (step != null) {
                        val confidence = when (step.confidence) {
                            ai.trustshield.android.core.model.Confidence.HIGH -> if (english) "High" else "Zyada"
                            ai.trustshield.android.core.model.Confidence.MEDIUM -> if (english) "Medium" else "Theek-thaak"
                            ai.trustshield.android.core.model.Confidence.LOW -> if (english) "Low" else "Kam"
                        }
                        Text(
                            "${step.score}/100 · ${if (english) "Confidence" else "Samajh"}: $confidence",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(step.position.explanation(english), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            step?.alertText(english)?.let { alert ->
                Surface(
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = alert },
                    color = RiskTone.RED.container(),
                    contentColor = RiskTone.RED.onContainer(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(alert, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            if (step == null) {
                Text(
                    if (showResetLow) {
                        if (english) "Reset · LOW. The in-memory transcript was cleared." else "Reset · KAM. Memory wala transcript saaf ho gaya."
                    } else if (english) {
                        "Tap Next line to run the deterministic A05 script."
                    } else {
                        "A05 script chalane ke liye Agli line dabayein."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text(if (english) "Why this step" else "Yeh sanket kyun", style = MaterialTheme.typography.titleSmall)
                step.chips.forEach { chip ->
                    val chipTone = if (chip.lit) RiskTone.AMBER else RiskTone.NEUTRAL
                    val stageLabel = CallShieldCopy.stageLabel(chip.stage).forLanguage(
                        if (english) CallLanguage.ENGLISH else CallLanguage.HINGLISH
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = buildString {
                                append(stageLabel)
                                append(
                                    if (english) {
                                        if (chip.lit) ", found" else ", not found"
                                    } else {
                                        if (chip.lit) ", mila" else ", nahi mila"
                                    }
                                )
                                chip.quotes.forEach { append(" $it") }
                            }
                        },
                        color = chipTone.container(),
                        contentColor = chipTone.onContainer(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                stageLabel + if (chip.lit) " · ${if (english) "Found" else "Mila"}" else " · ${if (english) "Not found" else "Nahi mila"}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            chip.quotes.forEach { quote ->
                                Text("“$quote”", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text("“${step.line.text}”", style = MaterialTheme.typography.bodyMedium)
            }

            Text(
                if (english) {
                    "This typed demo is not a real call recording. The meter follows deterministic local rules. Deepfake voices are not checked."
                } else {
                    CallDemoScript.honestLimits.take(3).joinToString(" ")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onBack, enabled = progress.steps.isNotEmpty(), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (english) "Back" else "Peeche")
            }
            Button(onClick = onAdvance, enabled = !progress.finished, modifier = Modifier.weight(1.25f).heightIn(min = 48.dp)) {
                Text(if (english) "Next line" else "Agli line")
            }
            OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (english) "Reset" else "Phir se")
            }
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

/**
 * How many of the four meter segments are filled for [position].
 *
 * `0` before the demo runs (no reading yet, so no segment - the card must never show a fabricated
 * "0/100"), otherwise one segment per rung: LOW 1, MEDIUM 2, HIGH 3, CRITICAL 4. Pure and
 * deterministic: it reads the engine's position and invents nothing.
 */
internal fun demoBandSegments(position: DemoRiskPosition?): Int = when (position) {
    null -> 0
    DemoRiskPosition.LOW -> 1
    DemoRiskPosition.MEDIUM -> 2
    DemoRiskPosition.HIGH -> 3
    DemoRiskPosition.CRITICAL -> 4
}

/**
 * The four-position band of the demo meter (issue #119 acceptance: the meter must visibly climb
 * Low -> Medium -> High -> Critical). Colour is never the only signal - the rung label above it and
 * the content description carry the same information for TalkBack.
 */
@Composable
private fun DemoPositionBand(position: DemoRiskPosition?, tone: RiskTone, english: Boolean) {
    val filled = demoBandSegments(position)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics {
                contentDescription = if (position == null) {
                    if (english) "Risk meter empty" else "Khatre ka meter khali hai"
                } else {
                    "${position.label(english)}, ${filled} of 4"
                }
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(4) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (index < filled) tone.onContainer()
                        else tone.onContainer().copy(alpha = 0.20f),
                    ),
            )
        }
    }
}
