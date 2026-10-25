package ai.trustshield.android.ui.call

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.RiskToneCopy
import ai.trustshield.android.ui.theme.TsMotion
import ai.trustshield.android.ui.theme.TsTokens
import ai.trustshield.android.ui.theme.TrustShieldTheme
import ai.trustshield.android.ui.theme.accent
import ai.trustshield.android.ui.theme.container
import ai.trustshield.android.ui.theme.onContainer
import ai.trustshield.android.ui.theme.onSurface

/*
 * Call Shield screen (work package C05, issue #46).
 *
 * Thin presentation layer: it renders [CallUiState] and raises [CallEvent]s. All behaviour --
 * consent gating, five-chip stage tracking, the low-confidence hold, the haptic single-shot rule,
 * the stop/clear rules -- lives in `CallUiState.kt`, which is unit-tested on the JVM.
 *
 * // SEAM: nothing here opens a microphone. B05 (`input/speech/CallSpeechSession.kt`) will drive
 * // [CallEvent.Start]/[CallEvent.Stop]/[CallEvent.Tick]/[CallEvent.Chunk]; B04 supplies the mic
 * // permission and recogniser availability events. Until those packages land this screen is a
 * // user-visible session shell with an honest, non-live empty state.
 *
 * Honesty rules baked into the copy and layout:
 *  - "profile" of words only, never an accusation against a person;
 *  - risk tone and confidence are always shown as two separate things;
 *  - the warning is a static banner (no pulse, no flash) that stays pinned while active;
 *  - the app never claims to capture the other side of a cellular/VoIP call.
 */

@Composable
fun CallShieldScreen(
    state: CallUiState,
    onEvent: (CallEvent) -> Unit,
    modifier: Modifier = Modifier,
    // Fallbacks required by UI_SPEC for denied mic / missing recogniser. They are callbacks only:
    // no speech code exists in this package, and neither path is labelled live ASR.
    onOpenStagedDemo: (() -> Unit)? = null,
    onTypeTranscript: (() -> Unit)? = null
) {
    val view = LocalView.current

    // SEAM: the reducer guarantees at most one pulse per changed high-risk state, so this effect
    // cannot fire per ASR chunk. Re-keying on the counter (not on the tone) keeps it single-shot.
    LaunchedEffect(state.hapticPulses) {
        if (state.hapticPulses > 0 && state.hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Header(state = state, onEvent = onEvent)

            // Sticky warning: outside the scroll area, so it stays visible while the user reads.
            state.alert?.let { alert -> WarningBanner(alert = alert, state = state) }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = TsTokens.SpacingLg)
            ) {
                Spacer(Modifier.height(TsTokens.SpacingMd))
                if (state.consent == ConsentState.NOT_GIVEN) {
                    ConsentCard(state = state, onEvent = onEvent)
                    Spacer(Modifier.height(TsTokens.SpacingMd))
                }
                StatusCard(state = state, onEvent = onEvent)
                Spacer(Modifier.height(TsTokens.SpacingMd))

                // Accessibility order is risk -> confidence -> evidence (QUALITY_GATES sec 6).
                RiskMeter(state = state)
                Spacer(Modifier.height(TsTokens.SpacingMd))

                StageChips(state = state)
                Spacer(Modifier.height(TsTokens.SpacingMd))

                HeldAlertCard(state = state, onEvent = onEvent)
                PermissionCard(state = state, onEvent = onEvent, onTypeTranscript = onTypeTranscript)
                RecognizerCard(state = state, onOpenStagedDemo = onOpenStagedDemo, onTypeTranscript = onTypeTranscript)
                UnableToVerifyCard(state = state)

                Spacer(Modifier.height(TsTokens.SpacingMd))
                Footer(state = state)
                Spacer(Modifier.height(TsTokens.SpacingXl))
            }

            StopBar(state = state, onEvent = onEvent)
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Header
// -------------------------------------------------------------------------------------------------

@Composable
private fun Header(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val language = state.language
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TsTokens.SpacingLg)
            .padding(top = TsTokens.SpacingLg)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = CallShieldCopy.title.forLanguage(language),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = CallShieldCopy.subtitle.forLanguage(language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = {
                    onEvent(
                        CallEvent.SetLanguage(
                            if (language == CallLanguage.HINGLISH) CallLanguage.ENGLISH else CallLanguage.HINGLISH
                        )
                    )
                },
                modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
            ) {
                Text(CallShieldCopy.languageSwitch.forLanguage(language))
            }
        }
        Spacer(Modifier.height(TsTokens.SpacingSm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// -------------------------------------------------------------------------------------------------
// Sticky warning banner (static: no pulsing, no flashing)
// -------------------------------------------------------------------------------------------------

@Composable
private fun WarningBanner(alert: CallAlert, state: CallUiState) {
    val language = state.language
    val presentation = RiskToneCopy.of(alert.tone)
    val body = CallShieldCopy.warning(alert.severity).forLanguage(language)
    val noAccusation = CallShieldCopy.noAccusation.forLanguage(language)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = body + " " + noAccusation
            },
        color = alert.tone.container(),
        contentColor = alert.tone.onContainer()
    ) {
        Column(Modifier.padding(TsTokens.SpacingLg)) {
            Row(verticalAlignment = Alignment.Top) {
                GlyphBadge(
                    glyph = presentation.glyph,
                    background = alert.tone.accent(),
                    foreground = Color.White
                )
                Spacer(Modifier.width(TsTokens.SpacingMd))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = presentation.label.forLanguage(language),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = alert.tone.onContainer()
                    )
                    Spacer(Modifier.height(TsTokens.SpacingXs))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = alert.tone.onContainer()
                    )
                    Spacer(Modifier.height(TsTokens.SpacingSm))
                    Text(
                        text = noAccusation,
                        style = MaterialTheme.typography.bodySmall,
                        color = alert.tone.onContainer()
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Consent gate
// -------------------------------------------------------------------------------------------------

@Composable
private fun ConsentCard(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val language = state.language
    SectionCard {
        Text(
            text = CallShieldCopy.consentTitle.forLanguage(language),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(TsTokens.SpacingSm))
        Text(
            text = CallShieldCopy.consentBody.forLanguage(language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(TsTokens.SpacingMd))
        Button(
            onClick = { onEvent(CallEvent.GiveConsent) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TsTokens.MinTouchTarget)
        ) {
            Text(CallShieldCopy.consentAction.forLanguage(language))
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Status: mic, offline/backend label, active timer, session controls
// -------------------------------------------------------------------------------------------------

@Composable
private fun StatusCard(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val language = state.language
    val micLabel = when {
        state.permission == MicPermission.DENIED -> CallShieldCopy.micDenied
        state.phase == SessionPhase.LISTENING -> CallShieldCopy.micListening
        state.phase == SessionPhase.PAUSED -> CallShieldCopy.micPaused
        else -> CallShieldCopy.micOff
    }

    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = micLabel.forLanguage(language),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(TsTokens.SpacingXs))
                Text(
                    text = CallShieldCopy.analysisLocal.forLanguage(language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = CallShieldCopy.backendOff.forLanguage(language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = CallShieldCopy.timerLabel.forLanguage(language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatElapsed(state.elapsedSeconds),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(TsTokens.SpacingMd))

        when (state.phase) {
            SessionPhase.LISTENING -> OutlinedButton(
                onClick = { onEvent(CallEvent.Pause) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TsTokens.MinTouchTarget)
            ) { Text(CallShieldCopy.pause.forLanguage(language)) }

            SessionPhase.PAUSED -> Button(
                onClick = { onEvent(CallEvent.Resume) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TsTokens.MinTouchTarget)
            ) { Text(CallShieldCopy.resume.forLanguage(language)) }

            SessionPhase.IDLE -> Button(
                onClick = { onEvent(CallEvent.Start) },
                enabled = state.canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TsTokens.MinTouchTarget)
            ) { Text(CallShieldCopy.start.forLanguage(language)) }

            SessionPhase.STOPPED -> Button(
                onClick = { onEvent(CallEvent.Start) },
                enabled = state.canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TsTokens.MinTouchTarget)
            ) { Text(CallShieldCopy.startNew.forLanguage(language)) }
        }

        if (state.consent == ConsentState.GIVEN && state.phase == SessionPhase.IDLE) {
            Spacer(Modifier.height(TsTokens.SpacingSm))
            Text(
                text = CallShieldCopy.consentGiven.forLanguage(language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Risk meter + confidence (two separate readings, never merged)
// -------------------------------------------------------------------------------------------------

@Composable
private fun RiskMeter(state: CallUiState) {
    val language = state.language
    val presentation = RiskToneCopy.of(state.tone)
    val toneLabel = presentation.label.forLanguage(language)
    val toneDescription = presentation.contentDescription.forLanguage(language)
    val confidenceLabel = CallShieldCopy.confidenceLevel(state.confidence).forLanguage(language)
    val filled = state.tone.ordinal + 1

    val containerColor by animateColorAsState(
        targetValue = state.tone.container(),
        animationSpec = tween(durationMillis = TsMotion.duration(TsTokens.MotionMediumMs, state.reducedMotion)),
        label = "riskContainer"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$toneLabel. $toneDescription. " +
                    CallShieldCopy.confidenceHeading.forLanguage(language) + ": " + confidenceLabel
            },
        shape = RoundedCornerShape(TsTokens.CornerLarge),
        color = containerColor,
        contentColor = state.tone.onContainer()
    ) {
        Column(Modifier.padding(TsTokens.SpacingLg)) {
            Text(
                text = CallShieldCopy.riskHeading.forLanguage(language),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(TsTokens.SpacingXs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlyphBadge(
                    glyph = presentation.glyph,
                    background = state.tone.accent(),
                    foreground = Color.White
                )
                Spacer(Modifier.width(TsTokens.SpacingMd))
                Text(
                    text = toneLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(TsTokens.SpacingMd))

            // Four static segments: a shape for the tone, not a fabricated score.
            Row(Modifier.fillMaxWidth()) {
                repeat(4) { index ->
                    if (index > 0) Spacer(Modifier.width(TsTokens.SpacingXs))
                    val on = index < filled
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(10.dp)
                            .background(
                                color = if (on) state.tone.accent() else state.tone.onContainer().copy(alpha = 0.25f),
                                shape = RoundedCornerShape(TsTokens.CornerSmall)
                            )
                    )
                }
            }

            Spacer(Modifier.height(TsTokens.SpacingMd))

            Text(
                text = CallShieldCopy.confidenceHeading.forLanguage(language) + ": " + confidenceLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = CallShieldCopy.confidenceExplainer.forLanguage(language),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(TsTokens.SpacingSm))
            Text(
                text = CallShieldCopy.riskExplainer.forLanguage(language),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Five stage chips (always exactly five, label + glyph + text)
// -------------------------------------------------------------------------------------------------

@Composable
private fun StageChips(state: CallUiState) {
    val language = state.language
    Column(verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm)) {
        Text(
            text = CallShieldCopy.whyStages.forLanguage(language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        state.stages.forEach { chip ->
            StageChipRow(chip = chip, language = language, reducedMotion = state.reducedMotion)
        }
    }
}

@Composable
private fun StageChipRow(chip: StageChip, language: CallLanguage, reducedMotion: Boolean) {
    val tone = if (chip.lit) RiskTone.AMBER else RiskTone.NEUTRAL
    val label = CallShieldCopy.stageLabel(chip.stage).forLanguage(language)
    val detail = CallShieldCopy.stageDetail(chip.stage).forLanguage(language)
    val statusText = if (chip.lit) {
        CallShieldCopy.stageFound.forLanguage(language)
    } else {
        CallShieldCopy.stageNotFound.forLanguage(language)
    }

    val containerColor by animateColorAsState(
        targetValue = tone.container(),
        animationSpec = tween(durationMillis = TsMotion.duration(TsTokens.MotionMediumMs, reducedMotion)),
        label = "stageContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (chip.lit) tone.accent() else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(durationMillis = TsMotion.duration(TsTokens.MotionShortMs, reducedMotion)),
        label = "stageBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                stateDescription = statusText
            },
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        color = containerColor,
        contentColor = tone.onSurface(),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TsTokens.MinTouchTarget)
                .padding(TsTokens.SpacingMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlyphBadge(
                glyph = CallShieldCopy.stageGlyph(chip.stage),
                background = if (chip.lit) tone.accent() else MaterialTheme.colorScheme.surfaceVariant,
                foreground = if (chip.lit) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(TsTokens.SpacingMd))
            Column(Modifier.weight(1f)) {
                Text(
                    text = label + " — " + statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                chip.quote?.let { quote ->
                    Spacer(Modifier.height(TsTokens.SpacingXs))
                    Text(
                        text = "\u201C" + quote + "\u201D",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Degraded-state cards
// -------------------------------------------------------------------------------------------------

@Composable
private fun HeldAlertCard(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    if (state.heldAlert == null && state.audioQuality != AudioQuality.UNCLEAR) return
    val language = state.language
    SectionCard(alertTone = true) {
        Text(
            text = CallShieldCopy.lowConfidenceTitle.forLanguage(language),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(TsTokens.SpacingSm))
        Text(
            text = CallShieldCopy.lowConfidenceBody.forLanguage(language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(TsTokens.SpacingMd))
        OutlinedButton(
            onClick = { onEvent(CallEvent.RetryListening) },
            modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
        ) {
            Text(CallShieldCopy.retry.forLanguage(language))
        }
    }
}

@Composable
private fun PermissionCard(
    state: CallUiState,
    onEvent: (CallEvent) -> Unit,
    onTypeTranscript: (() -> Unit)?
) {
    if (state.permission != MicPermission.DENIED) return
    val language = state.language
    SectionCard(alertTone = true) {
        Text(
            text = CallShieldCopy.permissionDeniedTitle.forLanguage(language),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(TsTokens.SpacingSm))
        Text(
            text = CallShieldCopy.permissionDeniedBody.forLanguage(language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(TsTokens.SpacingMd))
        Row(horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm)) {
            Button(
                onClick = { onEvent(CallEvent.MicPermissionGranted) },
                modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
            ) { Text(CallShieldCopy.grantPermission.forLanguage(language)) }
            onTypeTranscript?.let { open ->
                OutlinedButton(
                    onClick = open,
                    modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
                ) { Text(CallShieldCopy.typedTranscript.forLanguage(language)) }
            }
        }
    }
}

@Composable
private fun RecognizerCard(
    state: CallUiState,
    onOpenStagedDemo: (() -> Unit)?,
    onTypeTranscript: (() -> Unit)?
) {
    if (state.recognizer != RecognizerStatus.UNAVAILABLE) return
    val language = state.language
    SectionCard(alertTone = true) {
        Text(
            text = CallShieldCopy.recognizerUnavailableTitle.forLanguage(language),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(TsTokens.SpacingSm))
        Text(
            text = CallShieldCopy.recognizerUnavailableBody.forLanguage(language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(TsTokens.SpacingMd))
        Row(horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm)) {
            onTypeTranscript?.let { open ->
                Button(
                    onClick = open,
                    modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
                ) { Text(CallShieldCopy.typedTranscript.forLanguage(language)) }
            }
            onOpenStagedDemo?.let { open ->
                OutlinedButton(
                    onClick = open,
                    modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
                ) { Text(CallShieldCopy.stagedDemo.forLanguage(language)) }
            }
        }
    }
}

@Composable
private fun UnableToVerifyCard(state: CallUiState) {
    val reason = state.unableToVerifyReason
    // Permission/recogniser have their own, more specific cards (see above).
    if (reason != UnableToVerifyReason.UNREADABLE_AUDIO &&
        reason != UnableToVerifyReason.UNSUPPORTED_LANGUAGE &&
        reason != UnableToVerifyReason.NO_SPEECH_YET
    ) {
        return
    }
    if (state.confidence != ConfidenceLevel.UNABLE_TO_VERIFY) return
    // Idle/idle-with-no-speech messaging is only useful while a session is running.
    if (state.phase != SessionPhase.LISTENING && state.phase != SessionPhase.PAUSED) return
    val language = state.language
    SectionCard {
        Text(
            text = CallShieldCopy.unableToVerifyTitle.forLanguage(language),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(TsTokens.SpacingSm))
        Text(
            text = CallShieldCopy.unableToVerifyBody(reason).forLanguage(language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Footer(state: CallUiState) {
    val language = state.language
    Column {
        if (state.phase == SessionPhase.STOPPED) {
            Text(
                text = CallShieldCopy.stoppedTitle.forLanguage(language),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = CallShieldCopy.stoppedBody.forLanguage(language),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(TsTokens.SpacingSm))
        }
        Text(
            text = CallShieldCopy.clearTranscriptHint.forLanguage(language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(TsTokens.SpacingXs))
        Text(
            text = CallShieldCopy.stopHint.forLanguage(language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(TsTokens.SpacingXs))
        Text(
            text = CallShieldCopy.honestyNote.forLanguage(language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Stop bar (always visible, large target)
// -------------------------------------------------------------------------------------------------

@Composable
private fun StopBar(state: CallUiState, onEvent: (CallEvent) -> Unit) {
    val language = state.language
    val active = state.phase == SessionPhase.LISTENING || state.phase == SessionPhase.PAUSED
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TsTokens.SpacingLg)
                .padding(vertical = TsTokens.SpacingMd)
        ) {
            Button(
                onClick = { onEvent(CallEvent.Stop) },
                enabled = active,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
            ) {
                Text(
                    text = CallShieldCopy.stop.forLanguage(language),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Small shared pieces
// -------------------------------------------------------------------------------------------------

@Composable
private fun SectionCard(alertTone: Boolean = false, content: @Composable () -> Unit) {
    val color = if (alertTone) {
        RiskTone.AMBER.container()
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        color = color
    ) {
        Column(Modifier.padding(TsTokens.SpacingLg)) { content() }
    }
}

/**
 * Icon channel for tones and stages. C02 supplies a text glyph and an icon *name*; the extended
 * Material icon set is not on this app's classpath, so the glyph is rendered here. This keeps the
 * alert non-colour-only (label + icon + text) without adding a dependency.
 */
@Composable
private fun GlyphBadge(glyph: String, background: Color, foreground: Color) {
    Box(
        modifier = Modifier
            .size(TsTokens.MinTouchTarget)
            .background(color = background, shape = RoundedCornerShape(TsTokens.CornerSmall)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = foreground
        )
    }
}

// -------------------------------------------------------------------------------------------------
// Previews (design review only; they are NOT screenshots and NOT device evidence)
// -------------------------------------------------------------------------------------------------

private fun previewState(): CallUiState = CallUiState()

private fun previewHighRisk(language: CallLanguage): CallUiState {
    var state = CallUiState(consent = ConsentState.GIVEN, language = language)
    state = CallShieldReducer.reduce(state, CallEvent.MicPermissionGranted)
    state = CallShieldReducer.reduce(state, CallEvent.RecognizerAvailable)
    state = CallShieldReducer.reduce(state, CallEvent.Start)
    state = CallShieldReducer.reduce(state, CallEvent.Tick(64))
    state = CallShieldReducer.reduce(
        state,
        CallEvent.Chunk(
            SpeechChunk(
                asrConfidence = 0.93f,
                text = "Case clear karne ke liye 50,000 bhejo",
                signals = listOf(
                    StageSignal(CallStage.AUTHORITY, "Main Mumbai Cyber Crime se Inspector Rathore bol raha hoon"),
                    StageSignal(CallStage.SECRECY_ISOLATION, "Yeh confidential case hai, kisi ko mat batana"),
                    StageSignal(
                        CallStage.ASK,
                        "Case clear karne ke liye 50,000 RBI verification account mein bhejo",
                        moneyOrCredentialAsk = true
                    )
                )
            )
        )
    )
    return state
}

private fun previewLowConfidence(): CallUiState {
    var state = previewHighRisk(CallLanguage.HINGLISH).copy(stages = emptyStageChips())
    state = CallShieldReducer.reduce(state, CallEvent.Tick(12))
    return CallShieldReducer.reduce(
        state,
        CallEvent.Chunk(
            SpeechChunk(
                asrConfidence = 0.28f,
                text = "kuch saaf nahi suna",
                signals = listOf(
                    StageSignal(CallStage.AUTHORITY, "main CBI se"),
                    StageSignal(CallStage.SECRECY_ISOLATION, "kisi ko mat batana"),
                    StageSignal(CallStage.ASK, "50,000 bhejo", moneyOrCredentialAsk = true)
                )
            )
        )
    )
}

@Preview(name = "Consent gate (Hinglish)", showBackground = true, widthDp = 360)
@Composable
private fun PreviewConsentGate() {
    TrustShieldTheme { CallShieldScreen(state = previewState(), onEvent = {}) }
}

@Preview(name = "High risk (English)", showBackground = true, widthDp = 360)
@Composable
private fun PreviewHighRiskEnglish() {
    TrustShieldTheme { CallShieldScreen(state = previewHighRisk(CallLanguage.ENGLISH), onEvent = {}) }
}

@Preview(name = "Low ASR confidence hold (Hinglish)", showBackground = true, widthDp = 360)
@Composable
private fun PreviewLowConfidence() {
    TrustShieldTheme { CallShieldScreen(state = previewLowConfidence(), onEvent = {}) }
}

@Preview(name = "High risk (dark, 320dp)", showBackground = true, widthDp = 320)
@Composable
private fun PreviewHighRiskDark() {
    TrustShieldTheme(darkTheme = true) {
        CallShieldScreen(state = previewHighRisk(CallLanguage.HINGLISH), onEvent = {})
    }
}
