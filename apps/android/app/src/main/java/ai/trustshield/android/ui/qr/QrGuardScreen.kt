package ai.trustshield.android.ui.qr

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.ToneCopy
import ai.trustshield.android.ui.theme.TrustShieldTheme
import ai.trustshield.android.ui.theme.TsTokens
import ai.trustshield.android.ui.theme.accent
import ai.trustshield.android.ui.theme.container
import ai.trustshield.android.ui.theme.onContainer
import ai.trustshield.android.ui.theme.onSurface

/**
 * QR Guard screen (work package C06, issue #47).
 *
 * THIN RENDERER ONLY: every rule lives in [QrUiState] / [QrReducer] (pure Kotlin, unit tested). This
 * file renders state and raises [QrEvent]s. The split exists because there is no Compose UI-test
 * dependency on the classpath (Codex-owned), so logic inside a composable could not be tested at all.
 *
 * What it always shows (UI_SPEC: "QR Guard presents setup/expected merchant, active camera, valid
 * mismatch count, unreadable/occluded state and PIN-gated re-register"):
 *  - expected merchant(s) with the UPI id MASKED, plus the "a printed name is spoofable" warning;
 *  - camera status as label + icon + text (never colour only);
 *  - the consecutive valid mismatch count and the occlusion timer as clearly different things —
 *    mismatch is evidence, "QR nahi dikh raha" is not;
 *  - the PIN-gated re-register flow;
 *  - guidance for permission-denied and no-camera;
 *  - the mandatory "pakka confirmation" line, verbatim.
 *
 * This screen still never opens a camera and never resolves a QR/URI destination: the camera, the
 * runtime CAMERA permission, B06's watcher and the simulate-scan demo live in `QrGuardHost.kt`, which
 * renders this screen with a [QrUiState] it derives from the watcher. Rendering stays a pure function
 * of state, which is why every rule below is still unit-testable on a bare JVM.
 */
@Composable
fun QrGuardScreen(
    state: QrUiState,
    onEvent: (QrEvent) -> Unit,
    modifier: Modifier = Modifier,
    emulatorCameraOff: Boolean = false,
) {
    val dark = isSystemInDarkTheme()
    val lang = state.language
    val signal = state.signal
    val signalCopy = QrSignalCopy.of(signal)

    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(TsTokens.SpacingLg),
            verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingMd)
        ) {
            Text(
                text = QrStrings.title.text(lang),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = QrStrings.subtitle.text(lang),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LanguageToggle(language = lang, onEvent = onEvent)

            CameraStatusPanel(state = state, dark = dark, lang = lang, emulatorCameraOff = emulatorCameraOff)

            SignalBanner(signal = signal, presentation = signalCopy, state = state, dark = dark, lang = lang)

            if (state.guidance != QrGuidance.NONE) {
                GuidancePanel(guidance = state.guidance, lang = lang, dark = dark)
            }

            ExpectedMerchantPanel(state = state, dark = dark, lang = lang, onEvent = onEvent)

            ActionRow(state = state, lang = lang, onEvent = onEvent)

            if (state.phase == QrPhase.RE_REGISTER) {
                ReRegisterPanel(state = state, lang = lang, dark = dark, onEvent = onEvent)
            }

            CounterPanel(state = state, lang = lang, dark = dark)

            MandatoryConfirmationBanner(lang = lang, dark = dark)

            Text(
                text = if (lang == QrLanguage.HINGLISH) {
                    "Demo scan nakli QR data use karta hai. Asli QR camera se scan hota hai."
                } else {
                    "Demo scan uses sample QR data. Real QR codes are scanned with the camera."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ------------------------------------------------------------------------------- small components ----

private fun QrTone.toRiskTone(): RiskTone = when (this) {
    QrTone.NEUTRAL -> RiskTone.NEUTRAL
    QrTone.GREEN -> RiskTone.GREEN
    QrTone.AMBER -> RiskTone.AMBER
    QrTone.RED -> RiskTone.RED
}

/**
 * Colour + shaped glyph + visible label. The glyph is a real second channel, so the state stays
 * distinguishable with colour removed (QUALITY_GATES #6: alerts never colour-only; no Material icon
 * font is on the classpath — same fallback pattern as C02's `RiskTonePresentation.glyph`).
 */
@Composable
private fun IconBadge(glyph: String, tone: RiskTone, dark: Boolean) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(tone.accent(dark), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (dark) Color(0xFF10141C) else Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LanguageToggle(language: QrLanguage, onEvent: (QrEvent) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = QrStrings.languageLabel.text(language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LanguageChip(
            label = QrStrings.hinglish,
            selected = language == QrLanguage.HINGLISH,
            onClick = { onEvent(QrEvent.SetLanguage(QrLanguage.HINGLISH)) }
        )
        LanguageChip(
            label = QrStrings.english,
            selected = language == QrLanguage.ENGLISH,
            onClick = { onEvent(QrEvent.SetLanguage(QrLanguage.ENGLISH)) }
        )
    }
}

@Composable
private fun LanguageChip(label: ToneCopy, selected: Boolean, onClick: () -> Unit) {
    val minHeight = Modifier.heightIn(min = TsTokens.MinTouchTarget)
    if (selected) {
        Button(onClick = onClick, modifier = minHeight) {
            Text(label.hinglish, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = minHeight) {
            Text(label.hinglish, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CameraStatusPanel(
    state: QrUiState,
    dark: Boolean,
    lang: QrLanguage,
    emulatorCameraOff: Boolean,
) {
    val tone = when (state.camera) {
        CameraUiStatus.ACTIVE -> if (emulatorCameraOff) RiskTone.AMBER else RiskTone.GREEN
        CameraUiStatus.PERMISSION_DENIED, CameraUiStatus.NO_CAMERA -> RiskTone.AMBER
        CameraUiStatus.UNKNOWN -> RiskTone.NEUTRAL
    }
    val label = when (state.camera) {
        CameraUiStatus.ACTIVE -> if (emulatorCameraOff) QrStrings.cameraEmulatorOff else QrStrings.cameraActive
        CameraUiStatus.PERMISSION_DENIED -> QrStrings.cameraDenied
        CameraUiStatus.NO_CAMERA -> QrStrings.cameraNone
        CameraUiStatus.UNKNOWN -> QrStrings.cameraUnknown
    }
    val glyph = when (state.camera) {
        CameraUiStatus.ACTIVE -> if (emulatorCameraOff) "\u2298" else "\u25CF"
        CameraUiStatus.PERMISSION_DENIED -> "\u2715"
        CameraUiStatus.NO_CAMERA -> "\u2715"
        CameraUiStatus.UNKNOWN -> "\u2026"
    }
    Panel(tone = tone, dark = dark) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBadge(glyph = glyph, tone = tone, dark = dark)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = QrStrings.cameraHeading.text(lang),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(text = label.text(lang), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SignalBanner(
    signal: QrSignal,
    presentation: QrSignalPresentation,
    state: QrUiState,
    dark: Boolean,
    lang: QrLanguage
) {
    val tone = presentation.tone.toRiskTone()
    val alert = state.mismatchAlert
    Panel(tone = tone, dark = dark) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm),
            verticalAlignment = Alignment.Top
        ) {
            IconBadge(glyph = presentation.glyph, tone = tone, dark = dark)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = presentation.label.text(lang),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = presentation.detail.text(lang),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (signal == QrSignal.MISMATCH) {
                    Text(
                        text = QrStrings.stopPaymentNow.text(lang),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (alert != null) {
                        // Masked ids only — an alert can be read by TalkBack or screenshotted.
                        Text(
                            text = ToneCopy(
                                "Mila: ${alert.observedMasked}  |  Expected: ${alert.expectedMasked.joinToString(", ")}",
                                "Found: ${alert.observedMasked}  |  Expected: ${alert.expectedMasked.joinToString(", ")}",
                                "मिला: ${alert.observedMasked}  |  अपेक्षित: ${alert.expectedMasked.joinToString(", ")}"
                            ).text(lang),
                            style = MaterialTheme.typography.bodySmall
                        )
                        val observedName = alert.observedName
                        if (observedName != null) {
                            Text(
                                text = ToneCopy(
                                    "QR par likha naam: $observedName",
                                    "Name printed on QR: $observedName",
                                    "QR पर लिखा नाम: $observedName"
                                ).text(lang),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                if (signal == QrSignal.UNREADABLE_TIMEOUT) {
                    Text(
                        text = QrStrings.occlusionNotTamper.text(lang),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun GuidancePanel(guidance: QrGuidance, lang: QrLanguage, dark: Boolean) {
    val tone = when (guidance) {
        QrGuidance.MISMATCH_ACTION -> RiskTone.RED
        QrGuidance.REGISTER_PAYEE, QrGuidance.NONE -> RiskTone.NEUTRAL
        else -> RiskTone.AMBER
    }
    val text = QrGuidanceCopy.of(guidance).text(lang)
    if (text.isBlank()) return
    Panel(tone = tone, dark = dark) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExpectedMerchantPanel(
    state: QrUiState,
    dark: Boolean,
    lang: QrLanguage,
    onEvent: (QrEvent) -> Unit
) {
    val editable = state.phase == QrPhase.SETUP ||
        (state.phase == QrPhase.RE_REGISTER && state.pinGate.open)
    var address by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    Panel(tone = RiskTone.NEUTRAL, dark = dark) {
        Text(
            text = QrStrings.expectedHeading.text(lang),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        if (state.payees.isEmpty()) {
            Text(
                text = QrStrings.expectedEmpty.text(lang),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        state.payees.forEach { payee ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    // Masked only: the full UPI id never reaches the screen.
                    Text(
                        text = payee.maskedHandle,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    val displayName = payee.displayName
                    if (displayName != null) {
                        Text(text = displayName, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (editable) {
                    OutlinedButton(
                        onClick = { onEvent(QrEvent.RemovePayee(payee.handle)) },
                        modifier = Modifier
                            .heightIn(min = TsTokens.MinTouchTarget)
                            .semantics {
                                contentDescription = QrStrings.removePayee.text(lang) + ": " + payee.maskedHandle
                            }
                    ) {
                        Text(QrStrings.removePayee.text(lang), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Text(
            text = QrStrings.payeeNameWarning.text(lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = QrStrings.maskedNote.text(lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (editable) {
            // Manual entry: the honest fallback when the camera is refused or absent, or when the
            // shopkeeper would rather type the id. Scanning feeds the same event through the host.
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(QrStrings.addPayeeHint.text(lang)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(QrStrings.addPayeeName.text(lang)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    onEvent(QrEvent.RegisterPayee(payeeAddress = address, payeeName = name))
                    address = ""
                    name = ""
                },
                modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
            ) { Text(QrStrings.addPayee.text(lang)) }
        }
    }
}

@Composable
private fun ActionRow(state: QrUiState, lang: QrLanguage, onEvent: (QrEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm)) {
        when (state.phase) {
            QrPhase.SETUP, QrPhase.STOPPED -> {
                Button(
                    onClick = { onEvent(QrEvent.StartWatch()) },
                    enabled = state.canStartWatch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TsTokens.MinTouchTarget)
                ) { Text(QrStrings.startWatch.text(lang)) }
                if (!state.canStartWatch) {
                    Text(
                        text = if (state.payees.isEmpty()) {
                            QrStrings.setupNeedsPayee.text(lang)
                        } else {
                            QrStrings.setupNeedsCamera.text(lang)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.phase == QrPhase.STOPPED) {
                    Text(
                        text = QrStrings.stoppedNote.text(lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            QrPhase.WATCH -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm)
                ) {
                    OutlinedButton(
                        onClick = { onEvent(QrEvent.Stop) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = TsTokens.MinTouchTarget)
                    ) { Text(QrStrings.stopWatch.text(lang)) }
                    HoldToReRegister(
                        label = QrStrings.reRegister,
                        lang = lang,
                        onHeld = { onEvent(QrEvent.RequestReRegister) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (state.mismatchAlert != null) {
                    Button(
                        onClick = { onEvent(QrEvent.AcknowledgeMismatch) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = TsTokens.MinTouchTarget)
                    ) { Text(QrStrings.acknowledge.text(lang)) }
                }
            }

            QrPhase.RE_REGISTER -> {
                TextButton(
                    onClick = { onEvent(QrEvent.CloseReRegister) },
                    modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
                ) { Text(QrStrings.pinCancel.text(lang)) }
            }
        }
    }
}

/**
 * Re-register trigger: a **press and hold for five seconds**, then the PIN.
 *
 * B06 (`QrWatcherPolicy.reRegisterLongPressMs`) owns the rule; a plain tap must never open the gate,
 * which is why this control has no `onClick` at all — a tap produces no event, and only a completed
 * hold raises [QrEvent.RequestReRegister]. The hold is also why the control is a plain bordered box
 * rather than a Material `Button`: a `Button`'s own click detector consumes the gesture before a
 * parent could time it.
 *
 * Accessibility: the control carries `Role.Button` and a content description that spells out the
 * hold, so TalkBack announces it as actionable; TalkBack's double-tap-and-hold performs the same
 * gesture. The PIN — not the hold — is the secret part of the gate.
 */
@Composable
private fun HoldToReRegister(
    label: ToneCopy,
    lang: QrLanguage,
    onHeld: () -> Unit,
    modifier: Modifier = Modifier
) {
    var holding by remember { mutableStateOf(false) }
    val currentOnHeld by rememberUpdatedState(onHeld)
    val shape = RoundedCornerShape(TsTokens.CornerSmall)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TsTokens.MinTouchTarget)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .pointerInput(Unit) {
                    // The hold is timed with the monotonic clock instead of a timer coroutine: an
                    // `AwaitPointerEventScope` is a *restricted* suspension scope, so a `delay` or a
                    // `launch` from another dispatcher is not available inside the gesture. Measuring
                    // the press and only firing on release keeps the rule exactly "held for 5 s".
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val downAt = SystemClock.uptimeMillis()
                        holding = true
                        val up = waitForUpOrCancellation()
                        holding = false
                        if (up != null && SystemClock.uptimeMillis() - downAt >= RE_REGISTER_HOLD_MS) {
                            currentOnHeld()
                        }
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = label.text(lang) + ". " + QrStrings.reRegisterHold.text(lang)
                }
                .padding(horizontal = TsTokens.SpacingSm, vertical = TsTokens.SpacingSm),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (holding) QrStrings.reRegisterHolding.text(lang) else label.text(lang),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center
            )
        }
        Text(
            text = QrStrings.reRegisterHold.text(lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ReRegisterPanel(
    state: QrUiState,
    lang: QrLanguage,
    dark: Boolean,
    onEvent: (QrEvent) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    Panel(tone = RiskTone.NEUTRAL, dark = dark) {
        Text(
            text = QrStrings.reRegister.text(lang),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        Text(text = QrStrings.reRegisterHint.text(lang), style = MaterialTheme.typography.bodySmall)

        if (state.pinGate.open) {
            Text(
                text = QrStrings.pinUnlocked.text(lang),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        } else {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it },
                label = { Text(QrStrings.pinLabel.text(lang)) },
                singleLine = true,
                enabled = !state.pinGate.lockedOut,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = QrStrings.pinLabel.text(lang) }
            )
            Button(
                onClick = {
                    onEvent(QrEvent.SubmitPin(pin))
                    pin = ""
                },
                enabled = !state.pinGate.lockedOut,
                modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
            ) { Text(QrStrings.pinSubmit.text(lang)) }

            when (state.pinGate.lastError) {
                QrPinError.WRONG_PIN -> Text(
                    text = QrStrings.pinWrong.text(lang),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RiskTone.RED.onSurface(dark)
                )
                QrPinError.LOCKED_OUT -> Text(
                    text = QrStrings.pinLockedOut.text(lang),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RiskTone.AMBER.onSurface(dark)
                )
                QrPinError.NOT_CONFIGURED -> Text(
                    text = QrStrings.pinNotConfigured.text(lang),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RiskTone.AMBER.onSurface(dark)
                )
                QrPinError.NONE -> Unit
            }
        }
    }
}

@Composable
private fun CounterPanel(state: QrUiState, lang: QrLanguage, dark: Boolean) {
    val observed = state.lastObserved
    Panel(tone = RiskTone.NEUTRAL, dark = dark) {
        Text(
            text = QrStrings.mismatchCounterLabel.text(lang) + ": " + state.mismatchProgress,
            style = MaterialTheme.typography.bodyLarge
        )
        val occludedFor = state.occlusion
        Text(
            text = QrStrings.occlusionTimerLabel.text(lang) + ": " +
                (if (occludedFor != null) (occludedFor.elapsedMs / 1000L).toString() + "s" else "-"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = QrStrings.occlusionNotTamper.text(lang),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (observed != null) {
            val verdict = if (observed.matched) "match" else "mismatch"
            Text(
                text = observed.maskedHandle + " (" + verdict + ")",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MandatoryConfirmationBanner(lang: QrLanguage, dark: Boolean) {
    Surface(
        color = RiskTone.NEUTRAL.container(dark),
        contentColor = RiskTone.NEUTRAL.onContainer(dark),
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        border = BorderStroke(1.dp, RiskTone.NEUTRAL.accent(dark)),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = QrStrings.mandatoryConfirmation.text(lang) }
    ) {
        Text(
            text = QrStrings.mandatoryConfirmation.text(lang),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(TsTokens.SpacingMd)
        )
    }
}

@Composable
private fun Panel(
    tone: RiskTone,
    dark: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = tone.container(dark),
        contentColor = tone.onContainer(dark),
        shape = RoundedCornerShape(TsTokens.CornerLarge),
        border = BorderStroke(1.dp, tone.accent(dark)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(TsTokens.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs),
            content = content
        )
    }
}

// -------------------------------------------------------------------------------------- previews ----

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun QrGuardPreviewWatching() {
    TrustShieldTheme {
        QrGuardScreen(
            state = QrUiState(
                phase = QrPhase.WATCH,
                camera = CameraUiStatus.ACTIVE,
                payees = listOf(
                    RegisteredPayee("dukaan.ravi@okaxis", "Sharma Kirana"),
                    RegisteredPayee("sharmakirana@ybl", "Sharma Kirana")
                ),
                consecutiveMismatches = 1,
                lastObserved = ObservedQr("9876543210@ybl", "Sharma Kirana", matched = false, atMs = 1_000L)
            ),
            onEvent = {}
        )
    }
}
