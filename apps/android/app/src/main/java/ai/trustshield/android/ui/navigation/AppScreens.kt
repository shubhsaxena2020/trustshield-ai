package ai.trustshield.android.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import android.view.HapticFeedbackConstants
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
import ai.trustshield.android.ui.call.CallStage
import ai.trustshield.android.ui.call.CallShieldCopy
import ai.trustshield.android.ui.call.ConfidenceLevel as CallConfidenceLevel
import ai.trustshield.android.ui.call.DemoRiskPosition
import ai.trustshield.android.ui.call.DemoSpeaker
import ai.trustshield.android.ui.call.DemoStep
import ai.trustshield.android.ui.call.SessionPhase
import ai.trustshield.android.ui.call.forLanguage
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
import ai.trustshield.android.ui.theme.TsTokens
import ai.trustshield.android.ui.theme.accent
import ai.trustshield.android.ui.theme.container
import ai.trustshield.android.ui.theme.onContainer
import ai.trustshield.android.ui.theme.onSurface
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
    var demoProgress by remember(demoRunner) { mutableStateOf(demoRunner.progress()) }
    var demoOpen by remember { mutableStateOf(false) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
    }

    // One handler for the language toggle, shared by the live screen and the demo screen, so the
    // value the reducer holds and the value persisted to Settings can never disagree.
    val applyLanguage: (CallLanguage) -> Unit = { next ->
        state = CallShieldReducer.reduce(state, CallEvent.SetLanguage(next))
        sink(
            prefs.copy(
                language = if (next == CallLanguage.ENGLISH) AppLanguage.ENGLISH else AppLanguage.HINGLISH
            )
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
    if (demoOpen) {
        // Issue #119 defect B: demo mode is its own screen, not a panel stacked under the live one.
        // Nothing that cannot act in demo mode is rendered here: no Start/Pause, no disabled Stop bar
        // (that dead block was the ~45% of the screen that clipped the meter), no live status card.
        CallDemoScreen(
            progress = demoProgress,
            language = state.language,
            hapticsEnabled = prefs.haptics,
            onAdvance = { demoProgress = demoRunner.advance() },
            onBack = { demoProgress = demoRunner.back() },
            onReset = { demoProgress = demoRunner.reset() },
            onClose = { demoOpen = false },
            onToggleLanguage = {
                applyLanguage(
                    if (state.language == CallLanguage.HINGLISH) CallLanguage.ENGLISH else CallLanguage.HINGLISH
                )
            }
        )
    } else Column(modifier = Modifier.fillMaxSize()) {
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
                is CallEvent.SetLanguage -> applyLanguage(event.language)
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
        onOpenStagedDemo = {
            // The demo is typed only. Leaving a live session listening behind a script would be
            // dishonest, so a listening/paused session is stopped (its transcript cleared) first.
            if (state.phase == SessionPhase.LISTENING || state.phase == SessionPhase.PAUSED) {
                host.onStop()
                state = host.state
            }
            demoOpen = true
        },
        onTypeTranscript = null
    )
    OutlinedButton(
        onClick = { demoOpen = true },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp)
    ) { Text(CallDemoLayout.entryAction.forLanguage(state.language)) }
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

// -------------------------------------------------------------------------------------------------
// Demo-script mode: pure presentation model (issue #119 defect B)
// -------------------------------------------------------------------------------------------------

/*
 * Why this model exists (issue #119, defect B)
 * --------------------------------------------
 * On the unfixed build demo mode was a panel stacked *under* the live Call Shield screen, so one
 * screen showed: the live header + status card + live Stop bar (disabled -- nothing to stop in a
 * typed demo) + a "Close demo script" button + a caption + a Back/Next/Reset row. On the emulator
 * that block took 31-45% of the height and clipped the `Khatre ka level` card (86 px visible instead
 * of 528 px) and the stage cards, and its header/buttons were English while the body was Hinglish.
 *
 * The fix is a layout contract a JVM test can hold us to, so it cannot drift back:
 *
 *  * demo mode is its OWN screen (`CallDemoScreen`): it renders no control that cannot act in demo
 *    mode -- no Start/Pause, no Stop bar, no live status card;
 *  * exactly three controls in one compact thumb row, each >= [MIN_TOUCH_TARGET_DP] dp: [DemoControl];
 *  * the meter is the FIRST section ([DemoSection.METER]) and sits inside the scroll area above that
 *    compact row, so nothing of fixed height can clip it;
 *  * every string is a [ToneCopy] pair and is rendered for exactly one language (Settings toggle);
 *  * the critical alert text and every chip quote come from the engine ([DemoStep]), never from here.
 *
 * Nothing in this file computes a score, picks a position, or invents a quote: it maps [DemoStep]
 * fields to strings. Pure Kotlin -- no `android.*` and no `androidx.compose.*` -- which is why
 * `CallDemoLayoutTest` runs on a plain JVM.
 */

/** The demo screen's sections, top to bottom. The meter is first by contract. */
enum class DemoSection { METER, STAGES, SCRIPT_LINE, LIMITS }

/** Every control the demo screen may render. There is deliberately no STOP or session control. */
enum class DemoControl { BACK, NEXT, RESET }

/** One rendered control: its label for the active language and whether it can act right now. */
data class DemoControlSpec(val control: DemoControl, val label: String, val enabled: Boolean)

/** Header strings for one language. */
data class DemoHeader(
    val title: String,
    val subtitle: String,
    val exitLabel: String,
    val languageToggleLabel: String,
)

/**
 * The `Khatre ka level` card, ready to render.
 *
 * @property scoreText the engine's score, verbatim, or null before the first line (an empty demo is
 *   not a reading and must never be shown as `0/100`).
 * @property position the engine's meter position, for the 4-segment band.
 * @property bandFilled how many of the four segments are filled (0 before the first line).
 */
data class DemoMeterReading(
    val heading: String,
    val scoreText: String?,
    val scoreOutOf: String,
    val position: DemoRiskPosition,
    val positionLabel: String,
    val bandFilled: Int,
    val confidenceText: String,
    val explanation: String,
    val stepCounter: String?,
    val tone: RiskTone,
    val isPreStart: Boolean,
    val contentDescription: String,
)

/**
 * One of the five stage chips.
 *
 * @property quoteText the exact quoted line the engine accepted for this stage, or null when the
 *   stage is not lit (or is lit but its words have left the rolling window -- then [note] says so and
 *   no quote is invented).
 */
data class DemoChipReading(
    val stage: CallStage,
    val glyph: String,
    val label: String,
    val lit: Boolean,
    val statusText: String,
    val quoteText: String?,
    val litWithoutLiveQuote: Boolean,
    val note: String?,
)

/** The line that produced this step, for the presenter. */
data class DemoLineReading(
    val heading: String,
    val speakerLabel: String,
    val text: String,
    val note: String?,
    val heldNote: String?,
)

/**
 * The demo screen's copy, ordering, control set and meter mapping -- in one testable place.
 *
 * Language rule (issue #119 nit): every user-facing string is a [ToneCopy] pair and the screen
 * renders one variant only, chosen by the Settings toggle. Engine-owned demo data that exists in
 * Hinglish only ([DemoStep.line]`noteHinglish`, [DemoStep.HELD_ASK_NOTE]) is *not* mixed into the
 * English screen; it is omitted there instead (see [line]).
 */
object CallDemoLayout {

    /** Minimum touch target, dp. Used verbatim by the sticky control row. */
    const val MIN_TOUCH_TARGET_DP: Int = 48

    /** The four meter positions, one per segment. */
    const val BAND_SEGMENTS: Int = 4

    // ---- copy (Hinglish default, English toggle) -------------------------------------------------

    val entryAction = ToneCopy("Demo dikhaayein", "Open demo script")
    val title = ToneCopy("Demo: khatre ka level", "Demo script: risk level")
    val subtitle = ToneCopy(
        "Sirf typed demo: na mic, na call recording.",
        "Typed demo only: no microphone, no call recording."
    )
    val exitAction = ToneCopy("Demo band karein", "Close demo script")
    val backAction = ToneCopy("Peeche", "Back")
    val nextAction = ToneCopy("Agli line", "Next line")
    val resetAction = ToneCopy("Shuru se", "Reset")
    // Short on purpose: this label shares the three-way compact row (~104 dp per button at 360 dp
    // width with 16 dp side padding and 8 dp gaps), so the longer "Script poora hua" would ellipsize.
    val scriptFinished = ToneCopy("Script khatam", "Finished")
    val confidenceLabel = ToneCopy("Confidence (alag reading)", "Confidence (separate reading)")
    val preStartPosition = ToneCopy("Abhi koi reading nahi", "No reading yet")
    val preStartBody = ToneCopy(
        "'Agli line' dabate hi pehli line locally score hoti hai. Tab tak meter neeche hai, yeh verdict nahi hai.",
        "The first line is scored locally as soon as you tap 'Next line'. Until then the meter is simply low; that is not a verdict."
    )
    val chipsHeading = ToneCopy(
        "Stage chips: har lit chip ke saath engine ki exact line",
        "Stage chips: each lit chip carries the engine's exact line"
    )
    val chipWithoutLiveQuote = ToneCopy(
        "Mil chuka, par is step par uski exact line window se bahar hai.",
        "Found earlier, but its exact line has left the window at this step."
    )
    val lineHeading = ToneCopy("Abhi ki line", "Current line")
    val limitsHeading = ToneCopy("Is demo ki honest limits", "Honest limits of this demo")
    val callerLabel = ToneCopy("Call karne wala", "Caller")
    val victimLabel = ToneCopy("Aap", "You")
    val systemLabel = ToneCopy("Yeh app", "This app")

    /**
     * The demo's honest limits, as a language pair.
     *
     * `CallDemoScript.honestLimits` is the engine's Hinglish-only source list; the screen needs both
     * languages, so the pairs live here. `CallDemoLayoutTest` pins that the same four facts are
     * stated (typed demo / deterministic meter / no deepfake check / no audio kept).
     */
    val limits: List<ToneCopy> = listOf(
        ToneCopy(
            "Yeh typed demo hai, asli call record nahi hoti.",
            "This is a typed demo; no real call is recorded."
        ),
        ToneCopy(
            "Meter sirf engine ke deterministic rules se badalta hai.",
            "The meter moves only from the engine's deterministic rules."
        ),
        ToneCopy(
            "Deepfake awaaz yahan check nahi hoti.",
            "Deepfake audio is not checked here."
        ),
        ToneCopy(
            "Koi audio save nahi hota; reset karne par transcript clear ho jata hai.",
            "No audio is saved; resetting clears the transcript."
        ),
        ToneCopy(
            "Score aur confidence do alag readings hain.",
            "Score and confidence are two separate readings."
        ),
    )

    /** Every pair above, for the one-language test. */
    val allCopy: Map<String, ToneCopy> = linkedMapOf(
        "entryAction" to entryAction,
        "title" to title,
        "subtitle" to subtitle,
        "exitAction" to exitAction,
        "backAction" to backAction,
        "nextAction" to nextAction,
        "resetAction" to resetAction,
        "scriptFinished" to scriptFinished,
        "confidenceLabel" to confidenceLabel,
        "preStartPosition" to preStartPosition,
        "preStartBody" to preStartBody,
        "chipsHeading" to chipsHeading,
        "chipWithoutLiveQuote" to chipWithoutLiveQuote,
        "lineHeading" to lineHeading,
        "limitsHeading" to limitsHeading,
        "callerLabel" to callerLabel,
        "victimLabel" to victimLabel,
        "systemLabel" to systemLabel,
    ) + limits.mapIndexed { index, copy -> "limits[$index]" to copy }

    // ---- structure -------------------------------------------------------------------------------

    /** Section order, top to bottom. [DemoSection.METER] is first: the meter is never below content. */
    fun sections(): List<DemoSection> =
        listOf(DemoSection.METER, DemoSection.STAGES, DemoSection.SCRIPT_LINE, DemoSection.LIMITS)

    fun header(language: CallLanguage): DemoHeader = DemoHeader(
        title = title.forLanguage(language),
        subtitle = subtitle.forLanguage(language),
        exitLabel = exitAction.forLanguage(language),
        languageToggleLabel = CallShieldCopy.languageSwitch.forLanguage(language),
    )

    fun limitLines(language: CallLanguage): List<String> = limits.map { it.forLanguage(language) }

    /**
     * The three controls, always in this order and always exactly these three, so the compact row
     * cannot grow a disabled Stop/session control back into demo mode.
     */
    fun controls(progress: DemoProgress, language: CallLanguage): List<DemoControlSpec> {
        val anySteps = progress.steps.isNotEmpty()
        return listOf(
            DemoControlSpec(DemoControl.BACK, backAction.forLanguage(language), anySteps),
            DemoControlSpec(
                DemoControl.NEXT,
                if (progress.finished) scriptFinished.forLanguage(language) else nextAction.forLanguage(language),
                !progress.finished
            ),
            DemoControlSpec(DemoControl.RESET, resetAction.forLanguage(language), anySteps),
        )
    }

    /** The meter card for [step], or the pre-start state when the demo has not started. */
    fun meter(step: DemoStep?, progress: DemoProgress, language: CallLanguage): DemoMeterReading {
        val english = language == CallLanguage.ENGLISH
        val heading = CallShieldCopy.riskHeading.forLanguage(language)
        if (step == null) {
            val label = preStartPosition.forLanguage(language)
            val body = preStartBody.forLanguage(language)
            return DemoMeterReading(
                heading = heading,
                scoreText = null,
                scoreOutOf = "/100",
                position = progress.position,
                positionLabel = label,
                bandFilled = 0,
                confidenceText = confidenceLabel.forLanguage(language) + ": " + noValue,
                explanation = body,
                stepCounter = null,
                tone = toneOf(progress.position),
                isPreStart = true,
                contentDescription = heading + ". " + label + ". " + body,
            )
        }
        val confidence = CallShieldCopy.confidenceLevel(confidenceOf(step)).forLanguage(language)
        val confidenceText = confidenceLabel.forLanguage(language) + ": " + confidence
        return DemoMeterReading(
            heading = heading,
            scoreText = step.score.toString(),
            scoreOutOf = "/100",
            position = step.position,
            positionLabel = step.position.label(english),
            bandFilled = step.position.ordinal + 1,
            confidenceText = confidenceText,
            explanation = step.position.explanation(english),
            stepCounter = stepCounter(step.index),
            tone = toneOf(step.position),
            isPreStart = false,
            contentDescription = heading + ": " + step.position.label(english) + ", " +
                step.score + "/100. " + confidenceText + ". " + step.position.explanation(english),
        )
    }

    /** The five stage chips, in playbook order, for [step] (all unlit before the first line). */
    fun chips(step: DemoStep?, language: CallLanguage): List<DemoChipReading> {
        val byStage = step?.chips?.associateBy { it.stage }.orEmpty()
        return CallStage.ordered.map { stage ->
            val chip = byStage[stage]
            val lit = chip?.lit == true
            val quotes = chip?.quotes.orEmpty()
            DemoChipReading(
                stage = stage,
                glyph = CallShieldCopy.stageGlyph(stage),
                label = CallShieldCopy.stageLabel(stage).forLanguage(language),
                lit = lit,
                statusText = (if (lit) CallShieldCopy.stageFound else CallShieldCopy.stageNotFound)
                    .forLanguage(language),
                quoteText = quotes.lastOrNull(),
                litWithoutLiveQuote = lit && quotes.isEmpty(),
                note = if (lit && quotes.isEmpty()) chipWithoutLiveQuote.forLanguage(language) else null,
            )
        }
    }

    /**
     * The critical alert, verbatim from [DemoStep.alert] (report section 6's alert text), or null.
     * This is the only source of the alarm sentence; nothing is composed here.
     */
    fun alert(step: DemoStep?, language: CallLanguage): String? =
        step?.alertText(language == CallLanguage.ENGLISH)

    /** The line that produced [step], or null before the first line. */
    fun line(step: DemoStep?, language: CallLanguage): DemoLineReading? {
        if (step == null) return null
        val english = language == CallLanguage.ENGLISH
        val speaker = when (step.line.speaker) {
            DemoSpeaker.SCAMMER -> callerLabel
            DemoSpeaker.VICTIM -> victimLabel
            DemoSpeaker.SYSTEM -> systemLabel
        }
        return DemoLineReading(
            heading = lineHeading.forLanguage(language),
            speakerLabel = speaker.forLanguage(language),
            text = step.line.text,
            // Engine-owned Hinglish-only presenter notes are shown in Hinglish only: on the English
            // screen an omitted note is honest, a mixed-language sentence is not.
            note = if (english) null else step.line.noteHinglish,
            heldNote = if (english) null else step.heldNote,
        )
    }

    /** `Line 2 / 4`, or null when the index is out of range. */
    fun stepCounter(index: Int): String? {
        if (index < 0 || index >= CallDemoScript.size) return null
        return "Line ${index + 1} / ${CallDemoScript.size}"
    }

    /** Meter tone: LOW is green, MEDIUM amber, HIGH and CRITICAL red. Nothing else is consulted. */
    fun toneOf(position: DemoRiskPosition): RiskTone = when (position) {
        DemoRiskPosition.LOW -> RiskTone.GREEN
        DemoRiskPosition.MEDIUM -> RiskTone.AMBER
        DemoRiskPosition.HIGH -> RiskTone.RED
        DemoRiskPosition.CRITICAL -> RiskTone.RED
    }

    private fun confidenceOf(step: DemoStep): CallConfidenceLevel = when (step.confidence) {
        ai.trustshield.android.core.model.Confidence.LOW -> CallConfidenceLevel.LOW
        ai.trustshield.android.core.model.Confidence.MEDIUM -> CallConfidenceLevel.MEDIUM
        ai.trustshield.android.core.model.Confidence.HIGH -> CallConfidenceLevel.HIGH
    }

    /** Shown where a reading does not exist yet; not a score and not a zero. */
    private const val noValue = "\u2014"
}

// -------------------------------------------------------------------------------------------------
// Demo-script mode: the screen
// -------------------------------------------------------------------------------------------------

/**
 * The demo screen: meter first, compact thumb row last, no control that cannot act.
 *
 * Reads its section order, strings, choices and meter values from [CallDemoLayout]; this function is
 * layout only, so the contract is testable without a device (see `CallDemoLayoutTest`).
 */
@Composable
private fun CallDemoScreen(
    progress: DemoProgress,
    language: CallLanguage,
    hapticsEnabled: Boolean,
    onAdvance: () -> Unit,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onToggleLanguage: () -> Unit,
) {
    val view = LocalView.current
    val step = progress.current
    val header = CallDemoLayout.header(language)
    val meter = CallDemoLayout.meter(step, progress, language)
    val chips = CallDemoLayout.chips(step, language)
    val alert = CallDemoLayout.alert(step, language)
    val controls = CallDemoLayout.controls(progress, language)
    val line = CallDemoLayout.line(step, language)
    val limits = CallDemoLayout.limitLines(language)

    // Acceptance 4: one pulse on the transition INTO critical, keyed on the step -- never on a chip,
    // a quote or a recomposition, and only when haptics are enabled in Settings.
    LaunchedEffect(step?.index, step?.haptics, hapticsEnabled) {
        if (step?.haptics == true && hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TsTokens.SpacingLg)
                    .padding(top = TsTokens.SpacingMd),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = header.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = header.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        onClick = onToggleLanguage,
                        modifier = Modifier.heightIn(min = CallDemoLayout.MIN_TOUCH_TARGET_DP.dp)
                    ) { Text(header.languageToggleLabel, style = MaterialTheme.typography.bodySmall) }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.heightIn(min = CallDemoLayout.MIN_TOUCH_TARGET_DP.dp)
                    ) { Text(header.exitLabel, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Spacer(Modifier.height(TsTokens.SpacingSm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Pinned, full width, edge to edge: the critical alert must not be below the fold.
            alert?.let { DemoAlertBanner(text = it, language = language) }

            // Scrollable content above the compact row: the meter can never be clipped by chrome.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = TsTokens.SpacingLg)
            ) {
                Spacer(Modifier.height(TsTokens.SpacingMd))
                DemoMeterCard(meter) // sections()[0]
                Spacer(Modifier.height(TsTokens.SpacingMd))
                Text(
                    text = CallDemoLayout.chipsHeading.forLanguage(language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(TsTokens.SpacingSm))
                chips.forEach { chip ->
                    DemoChipRow(chip)
                    Spacer(Modifier.height(TsTokens.SpacingSm))
                }
                line?.let { DemoLineCard(it) }
                Spacer(Modifier.height(TsTokens.SpacingMd))
                Text(
                    text = CallDemoLayout.limitsHeading.forLanguage(language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(TsTokens.SpacingXs))
                limits.forEach { limit ->
                    Text(
                        text = "\u2022 " + limit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(TsTokens.SpacingLg))
            }

            DemoControlRow(controls = controls, onBack = onBack, onNext = onAdvance, onReset = onReset)
        }
    }
}

@Composable
private fun DemoAlertBanner(text: String, language: CallLanguage) {
    val tone = RiskTone.RED
    val heading = DemoRiskPosition.CRITICAL.label(language == CallLanguage.ENGLISH)
    val noAccusation = CallShieldCopy.noAccusation.forLanguage(language)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = heading + ". " + text + " " + noAccusation
            },
        color = tone.container(),
        contentColor = tone.onContainer()
    ) {
        Column(Modifier.padding(TsTokens.SpacingLg)) {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(TsTokens.SpacingXs))
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(TsTokens.SpacingSm))
            Text(text = noAccusation, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DemoMeterCard(reading: DemoMeterReading) {
    val tone = reading.tone
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = reading.contentDescription },
        shape = RoundedCornerShape(TsTokens.CornerLarge),
        color = tone.container(),
        contentColor = tone.onContainer()
    ) {
        Column(Modifier.padding(TsTokens.SpacingLg)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = reading.heading, style = MaterialTheme.typography.bodySmall)
                reading.stepCounter?.let { counter ->
                    Text(text = counter, style = MaterialTheme.typography.bodySmall)
                }
            }
            reading.scoreText?.let { score ->
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = score,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = reading.scoreOutOf,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = TsTokens.SpacingXs, bottom = TsTokens.SpacingSm)
                    )
                }
            }
            Text(
                text = reading.positionLabel,
                style = if (reading.isPreStart) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                fontWeight = FontWeight.Bold
            )
            // Four segments = the engine's four positions; the band is a shape for the position,
            // never a fabricated probability.
            Row(Modifier.fillMaxWidth().padding(vertical = TsTokens.SpacingSm)) {
                repeat(CallDemoLayout.BAND_SEGMENTS) { index ->
                    if (index > 0) Spacer(Modifier.width(TsTokens.SpacingXs))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                            .background(
                                color = if (index < reading.bandFilled) {
                                    tone.accent()
                                } else {
                                    tone.onContainer().copy(alpha = 0.25f)
                                },
                                shape = RoundedCornerShape(TsTokens.CornerSmall)
                            )
                    )
                }
            }
            Text(
                text = reading.confidenceText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(TsTokens.SpacingXs))
            Text(text = reading.explanation, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DemoChipRow(chip: DemoChipReading) {
    val tone = if (chip.lit) RiskTone.AMBER else RiskTone.NEUTRAL
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { stateDescription = chip.statusText },
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        color = tone.container(),
        contentColor = tone.onSurface(),
        border = BorderStroke(
            1.dp,
            if (chip.lit) tone.accent() else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CallDemoLayout.MIN_TOUCH_TARGET_DP.dp)
                .padding(TsTokens.SpacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(TsTokens.MinTouchTarget)
                    .background(
                        color = if (chip.lit) tone.accent() else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(TsTokens.CornerSmall)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chip.glyph,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (chip.lit) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(TsTokens.SpacingMd))
            Column(Modifier.weight(1f)) {
                Text(
                    text = chip.label + " \u2014 " + chip.statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                chip.quoteText?.let { quote ->
                    Spacer(Modifier.height(TsTokens.SpacingXs))
                    Text(
                        text = "\u201C" + quote + "\u201D",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
                chip.note?.let { note ->
                    Spacer(Modifier.height(TsTokens.SpacingXs))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DemoLineCard(line: DemoLineReading) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(Modifier.padding(TsTokens.SpacingLg)) {
            Text(
                text = line.heading + " \u2014 " + line.speakerLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(TsTokens.SpacingXs))
            Text(text = line.text, style = MaterialTheme.typography.bodyMedium)
            line.note?.let { note ->
                Spacer(Modifier.height(TsTokens.SpacingXs))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            line.heldNote?.let { held ->
                Spacer(Modifier.height(TsTokens.SpacingXs))
                Text(
                    text = held,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** One compact thumb-reachable row: Back / Next line / Reset, each >= 48 dp and nothing else. */
@Composable
private fun DemoControlRow(
    controls: List<DemoControlSpec>,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onReset: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TsTokens.SpacingLg, vertical = TsTokens.SpacingSm),
            horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm)
        ) {
            controls.forEach { spec ->
                val onClick = when (spec.control) {
                    DemoControl.BACK -> onBack
                    DemoControl.NEXT -> onNext
                    DemoControl.RESET -> onReset
                }
                val modifier = Modifier
                    .weight(1f)
                    .heightIn(min = CallDemoLayout.MIN_TOUCH_TARGET_DP.dp)
                val padding = PaddingValues(horizontal = TsTokens.SpacingSm, vertical = 0.dp)
                if (spec.control == DemoControl.NEXT) {
                    Button(
                        onClick = onClick,
                        enabled = spec.enabled,
                        modifier = modifier,
                        contentPadding = padding
                    ) {
                        Text(spec.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                } else {
                    OutlinedButton(
                        onClick = onClick,
                        enabled = spec.enabled,
                        modifier = modifier,
                        contentPadding = padding
                    ) {
                        Text(spec.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    }
                }
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
