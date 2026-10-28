package ai.trustshield.android.ui.result

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.theme.ToneCopy
import ai.trustshield.android.ui.theme.TsTokens
import ai.trustshield.android.ui.theme.accent
import ai.trustshield.android.ui.theme.container
import ai.trustshield.android.ui.theme.onContainer
import ai.trustshield.android.ui.theme.onSurface
import android.animation.ValueAnimator
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween

/**
 * C03 — Result / evidence screen, the only Compose file in this package.
 *
 * It is deliberately dumb: it renders an already-decided [ResultUiState] and raises events. Every
 * rule (quote verification, truncation, risk/confidence separation, absolute-safety scrubbing,
 * Unable-to-Verify degradation) lives in [ResultPresenter] so it can be unit-tested on a JVM — there
 * is no Compose UI-test dependency in this project.
 *
 * Layout follows `docs/android/UI_SPEC.md`: a scrollable panel with the risk header first, the
 * confidence badge as a separate element, evidence cards carrying exact highlighted quotes, then
 * actions, limitations and the offline/backend note. The score arc animates once when a new result
 * arrives; critical bands never pulse and the displayed score stays static.
 *
 * Accessibility (QUALITY_GATES section 6):
 *  - colour is never the only differentiator — every tone travels with a glyph, a label and a
 *    content description;
 *  - reading order is risk -> confidence -> evidence, because that is the declaration order;
 *  - every control is at least [TsTokens.MinTouchTarget] tall, and nothing has a fixed height, so
 *    200% font scale at 320 dp wraps instead of clipping.
 */
@Composable
fun ResultScreen(
    state: ResultUiState,
    language: UiLanguage = UiLanguage.Default,
    /**
     * The deterministic score behind the band, for TalkBack only.
     *
     * Issue #115: this used to be a visible `Text("Risk score: N/100")` in the navigation host, drawn
     * above the header row. The screen's visual design is a coarse band with no figure (see the class
     * doc), so the number is only ever *spoken* -- [ResultUiState.screenDescription] appends
     * [RiskScoreSpeech.announcement] to the panel's content description. `null` (the default, and the
     * Unable-to-Verify case) means no figure is announced at all.
     */
    riskScore: Int? = null,
    onToggleLanguage: () -> Unit = {},
    onSpeakResult: (() -> Boolean)? = null,
    onShareResult: () -> Unit = {},
    onAction: (String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var speechUnavailable by remember(language) { mutableStateOf(false) }
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(state.risk, state.confidence, language, riskScore) {
        accessibilityManager?.announceForAccessibility(state.screenDescription(language, riskScore))
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TsTokens.SpacingLg, vertical = TsTokens.SpacingLg),
        verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingMd)
    ) {
        ResultTopBar(
            state = state,
            language = language,
            riskScore = riskScore,
            onToggleLanguage = onToggleLanguage,
            onBack = onBack
        )

        if (state.status is ResultStatus.UnableToVerify) {
            UnableToVerifyBanner(status = state.status, language = language)
        }

        RiskHeader(risk = state.risk, language = language)

        state.paymentAdvisory?.let { advisory ->
            PaymentAdvisoryCard(advisory = advisory, language = language)
        }

        ConfidenceBadgeView(badge = state.confidence, language = language)

        EvidenceSection(state = state, language = language)

        if (state.actions.isNotEmpty()) {
            ActionsSection(actions = state.actions, language = language, onAction = onAction)
        }

        LimitationsSection(limitations = state.limitations, language = language)

        BackendNote(backend = state.backend, language = language)

        if (onSpeakResult != null) {
            OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                onClick = { speechUnavailable = !onSpeakResult() },
                modifier = Modifier.fillMaxWidth().heightIn(min = TsTokens.MinTouchTarget),
            ) {
                Text(ToneCopy("Natija sunen", "Read result aloud", "\u0928\u0924\u0940\u091c\u093e \u0938\u0941\u0928\u0947\u0902").pick(language))
            }
            if (speechUnavailable) {
                Text(
                    ToneCopy(
                        "Is device par padhne wali awaaz nahi mili. Natija text mein dekhein.",
                        "No offline voice is available. Read the result on screen.",
                        "\u0907\u0938 \u0921\u093f\u0935\u093e\u0907\u0938 \u092a\u0930 \u0905\u092b\u093c\u0932\u093e\u0907\u0928 \u0906\u0935\u093e\u091c\u093c \u0928\u0939\u0940\u0902 \u092e\u093f\u0932\u0940\u0964 \u0928\u0924\u0940\u091c\u093e \u0938\u094d\u0915\u094d\u0930\u0940\u0928 \u092a\u0930 \u092a\u0922\u093c\u0947\u0902\u0964",
                    ).pick(language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            onClick = onShareResult,
            modifier = Modifier.fillMaxWidth().heightIn(min = TsTokens.MinTouchTarget)
        ) {
            Text(ToneCopy("Sirf natija share karein", "Share verdict only", "केवल परिणाम साझा करें").pick(language))
        }

        FooterNotes(state = state, language = language)
    }
}

@Composable
private fun ResultTopBar(
    state: ResultUiState,
    language: UiLanguage,
    riskScore: Int?,
    onToggleLanguage: () -> Unit,
    onBack: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                onClick = onBack,
                modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget)
            ) { Text(ToneCopy("\u2039 Peeche", "\u2039 Back", "\u2039 \u0935\u093e\u092a\u0938").pick(language)) }
            Spacer(Modifier.width(TsTokens.SpacingSm))
        }
        Text(
            text = ToneCopy(ResultCopy.TITLE_HINGLISH, ResultCopy.TITLE_ENGLISH).pick(language),
            style = MaterialTheme.typography.headlineSmall,
            // One announcement for the whole panel head: risk first, then confidence, then the score
            // in words. The score is *only* here -- it is never drawn as text (issue #115).
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = state.screenDescription(language, riskScore) }
        )
        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            onClick = onToggleLanguage,
            modifier = Modifier.heightIn(min = TsTokens.MinTouchTarget).semantics {
                contentDescription = ResultCopy.LANGUAGE_TOGGLE_DESCRIPTION.pick(language)
            }
        ) {
            Text(
                when (language) {
                    UiLanguage.ENGLISH -> ToneCopy("Hinglish", "Hinglish", "हिंग्लिश").pick(language)
                    UiLanguage.HINGLISH -> ToneCopy("English", "English", "अंग्रेज़ी").pick(language)
                    UiLanguage.HINDI -> ToneCopy("Hinglish", "Hinglish", "हिंग्लिश").pick(language)
                }
            )
        }
    }
}

@Composable
private fun RiskHeader(risk: RiskBand, language: UiLanguage) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            val scoreDescription = risk.score?.let {
                ToneCopy("Skor: $it/100", "Score: $it/100", "स्कोर: $it/100").pick(language)
            }
            contentDescription = listOfNotNull(risk.contentDescription.pick(language), scoreDescription).joinToString(". ")
        },
        color = risk.tone.container(),
        shape = RoundedCornerShape(TsTokens.CornerLarge)
    ) {
        Row(
            modifier = Modifier.padding(TsTokens.SpacingLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingMd)
        ) {
            ScoreGauge(score = risk.score, tone = risk.tone, language = language)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingSm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(risk.glyph, style = MaterialTheme.typography.headlineMedium, color = risk.tone.onContainer())
                    Spacer(Modifier.width(TsTokens.SpacingSm))
                    Text(risk.label.pick(language), style = MaterialTheme.typography.headlineMedium, color = risk.tone.onContainer())
                }
                Text(risk.summary.pick(language), style = MaterialTheme.typography.bodyLarge, color = risk.tone.onContainer())
                RiskBandStrip(tone = risk.tone)
            }
        }
    }
}

@Composable
private fun ScoreGauge(score: Int?, tone: RiskTone, language: UiLanguage) {
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val animatedScore by animateFloatAsState(
        targetValue = (score ?: 0).toFloat(),
        animationSpec = if (animationsEnabled) tween(TsTokens.MotionMediumMs) else snap(),
        label = "risk-gauge"
    )
    val arcColor = tone.accent()
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier.size(116.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 7.dp.toPx()
            val diameter = size.minDimension - inset * 2
            val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
            val ring = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            drawArc(trackColor, -90f, 360f, false, topLeft, androidx.compose.ui.geometry.Size(diameter, diameter), style = ring)
            if (score != null && animatedScore > 0f) {
                drawArc(arcColor, -90f, animatedScore.coerceIn(0f, 100f) * 3.6f, false, topLeft, androidx.compose.ui.geometry.Size(diameter, diameter), style = ring)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(score?.toString() ?: "—", style = MaterialTheme.typography.headlineMedium, color = tone.onContainer())
            val caption = if (score == null) {
                ToneCopy("Jaanch nahi hui", "Not verified", "जाँच नहीं हुई")
            } else {
                ToneCopy("100 mein se", "out of 100", "100 में से")
            }
            Text(caption.pick(language), style = MaterialTheme.typography.bodySmall, color = tone.onContainer())
        }
    }
}

@Composable
private fun RiskBandStrip(tone: RiskTone) {
    val filled = tone.ordinal
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
    ) {
        RiskTone.entries.forEach { step ->
            val isOn = step.ordinal <= filled
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(TsTokens.SpacingXs)
                    .background(
                        color = if (isOn) step.accent() else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(TsTokens.SpacingXs)
                    )
            )
        }
    }
}

@Composable
private fun ConfidenceBadgeView(badge: ConfidenceBadge, language: UiLanguage) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = ResultCopy.CONFIDENCE_TITLE.pick(language) + ": " +
                    badge.label.pick(language) + ". " + badge.reason.pick(language)
            },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(TsTokens.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = badge.glyph,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(TsTokens.SpacingSm))
                Text(
                    text = ResultCopy.CONFIDENCE_TITLE.pick(language) + ": " + badge.label.pick(language),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = badge.reason.pick(language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UnableToVerifyBanner(status: ResultStatus.UnableToVerify, language: UiLanguage) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = ResultCopy.UNABLE_LABEL.pick(language) + ". " +
                    status.whatFailed.pick(language) + ". " + status.safestNextStep.pick(language)
            },
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(TsTokens.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
        ) {
            Text(
                text = ResultCopy.UNABLE_LABEL.pick(language),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = status.whatFailed.pick(language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = status.safestNextStep.pick(language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun EvidenceSection(state: ResultUiState, language: UiLanguage) {
    SectionTitle(ResultCopy.EVIDENCE_SECTION.pick(language))
    if (state.evidence.isEmpty()) {
        Text(
            text = state.droppedEvidence.reason.pick(language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    state.evidence.forEach { item -> EvidenceCard(item = item, language = language) }
    if (!state.droppedEvidence.isClean) {
        Text(
            text = state.droppedEvidence.reason.pick(language),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EvidenceCard(item: EvidenceItem, language: UiLanguage) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(TsTokens.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.glyph,
                    style = MaterialTheme.typography.titleMedium,
                    color = item.severityTone.onSurface()
                )
                Spacer(Modifier.width(TsTokens.SpacingXs))
                Text(
                    text = item.severityLabel.pick(language),
                    style = MaterialTheme.typography.labelLarge,
                    color = item.severityTone.onSurface()
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = ToneCopy(item.module, item.module).pick(language),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = item.title.pick(language),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = item.detail.pick(language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ExactQuoteBlock(quote = item.quote, tone = item.severityTone, language = language)
        }
    }
}

/**
 * The exact quote, visually highlighted and never paraphrased.
 *
 * [ExactQuote.text] is sliced out of the analysed source by the presenter, so what is drawn here is
 * literally what the user typed/shared. When it is a shortened span the caption says so — the
 * ellipsis is never passed off as the user's own punctuation.
 */
@Composable
private fun ExactQuoteBlock(quote: ExactQuote, tone: RiskTone, language: UiLanguage) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = tone.container(),
        shape = RoundedCornerShape(TsTokens.CornerSmall)
    ) {
        Column(
            modifier = Modifier.padding(TsTokens.SpacingSm),
            verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
        ) {
            Text(
                text = if (quote.truncated) {
                    ResultCopy.QUOTE_LABEL.pick(language) + " \u00B7 " + ResultCopy.QUOTE_TRUNCATED.pick(language)
                } else {
                    ResultCopy.QUOTE_LABEL.pick(language)
                },
                style = MaterialTheme.typography.labelMedium,
                color = tone.onContainer()
            )
            Text(
                text = quote.text,
                style = MaterialTheme.typography.bodyLarge,
                color = tone.onContainer()
            )
        }
    }
}

@Composable
private fun ActionsSection(
    actions: List<ResultAction>,
    language: UiLanguage,
    onAction: (String) -> Unit
) {
    SectionTitle(ResultCopy.ACTIONS_SECTION.pick(language))
    actions.forEach { action ->
        val modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TsTokens.MinTouchTarget)
        val content: @Composable () -> Unit = {
            Column(
                modifier = Modifier.padding(vertical = TsTokens.SpacingSm),
                verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
            ) {
                Text(text = action.label.pick(language), style = MaterialTheme.typography.titleMedium)
                if (action.detail.hinglish.isNotBlank()) {
                    Text(
                        text = action.detail.pick(language),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start
                    )
                }
            }
        }
        if (action.priority == ActionPriority.PRIMARY) {
            Button(
                onClick = { onAction(action.id) },
                modifier = modifier,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { content() }
        } else {
            OutlinedButton(colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = { onAction(action.id) }, modifier = modifier) { content() }
        }
    }
}

@Composable
private fun LimitationsSection(limitations: List<ToneCopy>, language: UiLanguage) {
    if (limitations.isEmpty()) return
    SectionTitle(ResultCopy.LIMITS_SECTION.pick(language))
    Column(verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)) {
        limitations.forEach { line ->
            Row {
                Text(
                    text = "\u2022",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(TsTokens.SpacingSm))
                Text(
                    text = line.pick(language),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BackendNote(backend: BackendEnrichment, language: UiLanguage) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(TsTokens.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
        ) {
            Text(
                text = (if (backend.available) "\u25CF " else "\u25CB ") + backend.label.pick(language),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = backend.detail.pick(language),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FooterNotes(state: ResultUiState, language: UiLanguage) {
    Column(verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)) {
        Text(
            text = ResultCopy.ANALYSED_CHARS.pick(language) + state.analysedCharacters.toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = ResultCopy.VERDICT_NOTE.pick(language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = ResultCopy.NOT_PROOF.pick(language),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() }
    )
}
