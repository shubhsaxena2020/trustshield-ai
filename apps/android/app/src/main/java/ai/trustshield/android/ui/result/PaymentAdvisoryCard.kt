package ai.trustshield.android.ui.result

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import ai.trustshield.android.core.payment.PaymentAdvisory
import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.ui.theme.TsTokens

/**
 * Payment advisory card for the Result screen — renders an already-decided [PaymentAdvisory].
 *
 * Three properties are load-bearing and must survive any edit here:
 *
 *  1. **Every hint is soft.** The hints come from the payment engine and describe what a screenshot
 *     looked like; they are hints to slow down and check, never a finding about a person. This
 *     composable adds no interpretation of its own — it draws [PaymentAdvisory.hints] in the order
 *     the engine produced (WARNING, then CAUTION, then INFO) and nothing else.
 *  2. **A payment screenshot never proves settlement.** Nothing on this card may claim that money
 *     moved, and no text here says a payee is a fraudster. [PaymentAdvisoryVerdict.CLEAR] means "no
 *     payment-specific warning signal found", not "safe to pay".
 *  3. **The confirmation line is mandatory and always drawn last.** [PaymentAdvisoryCopy.CONFIRMATION]
 *     sits in its own bordered container as the final child of the card, and it is also the last
 *     clause of the card's merged content description, so TalkBack speaks it last. It is rendered
 *     even when [PaymentAdvisory.hints] is empty — never conditional, never folded into another card.
 *
 * Accessibility (docs/android/UI_SPEC.md): the whole card is one merged semantics node whose
 * description reads as verdict headline, then each hint, then the confirmation line; colour is never
 * the only signal — [PaymentAdvisoryVerdict.STOP] carries a glyph ("!!") and a visible headline in
 * addition to [MaterialTheme.colorScheme.error]; every hint travels with its own severity glyph; no
 * fixed heights and no `maxLines` anywhere, so hint text wraps instead of clipping at 200% font
 * scale on a 320 dp screen. Nothing here animates — UI_SPEC forbids a pulsing critical alert — and
 * there are no buttons or click handlers on this card.
 */
@Composable
fun PaymentAdvisoryCard(
    advisory: PaymentAdvisory,
    language: UiLanguage,
    modifier: Modifier = Modifier
) {
    val sectionTitle = PaymentAdvisoryCopy.SECTION_TITLE.pick(language)
    val headline = PaymentAdvisoryCopy.verdictHeadline(advisory.verdict).pick(language)
    val advice = PaymentAdvisoryCopy.verdictAdvice(advisory.verdict).pick(language)
    val confirmation = PaymentAdvisoryCopy.CONFIRMATION.pick(language)
    val qrAmountSummary = PaymentAdvisoryCopy.qrAmountSummary(advisory.fields)?.pick(language)
    val hintRows = advisory.hints.map { hint ->
        PaymentAdvisoryCopy.severityGlyph(hint) to PaymentAdvisoryCopy.forHint(hint).pick(language)
    }

    // Verdict first, then the hints in engine order, then the mandatory line last. The join order is
    // the reading order, so a single TalkBack statement ends on the confirmation.
    val spokenDescription = buildString {
        append(sectionTitle)
        append(". ")
        append(headline)
        append(". ")
        append(advice)
        qrAmountSummary?.let { append(". "); append(it) }
        hintRows.forEach { row ->
            append(". ")
            append(row.first)
            append(". ")
            append(row.second)
        }
        append(". ")
        append(confirmation)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = spokenDescription
            },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(TsTokens.SpacingMd),
            verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)
        ) {
            Text(
                text = sectionTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }
            )

            AdvisoryVerdictBlock(verdict = advisory.verdict, headline = headline, advice = advice)

            qrAmountSummary?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }

            hintRows.forEach { row ->
                AdvisorySeverityRow(
                    glyph = row.first,
                    text = row.second,
                    glyphColor = MaterialTheme.colorScheme.onSurface,
                    textColor = MaterialTheme.colorScheme.onSurface
                )
            }

            // MUST stay the last element drawn: the mandatory confirmation line.
            AdvisoryConfirmationLine(text = confirmation)
        }
    }
}

/**
 * Verdict glyph, headline and advice.
 *
 * The glyph is text so the verdict survives without colour; [PaymentAdvisoryVerdict.STOP] additionally
 * switches both texts to [MaterialTheme.colorScheme.error] (>=4.5:1 on the card surface), but the
 * visible headline remains the primary signal.
 */
@Composable
private fun AdvisoryVerdictBlock(
    verdict: PaymentAdvisoryVerdict,
    headline: String,
    advice: String
) {
    val isStop = verdict == PaymentAdvisoryVerdict.STOP
    val contentColor = if (isStop) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Column(verticalArrangement = Arrangement.spacedBy(TsTokens.SpacingXs)) {
        AdvisorySeverityRow(
            glyph = advisoryVerdictGlyph(verdict),
            text = headline,
            glyphColor = contentColor,
            textColor = contentColor,
            textStyle = MaterialTheme.typography.titleMedium
        )
        Text(
            text = advice,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor
        )
    }
}

/**
 * Glyph per verdict. Deliberately the same ASCII-style marks the risk tones use (`✓`, `!`, `!!`) so
 * a device without an emoji/icon font still shows a readable, unambiguous mark.
 */
private fun advisoryVerdictGlyph(verdict: PaymentAdvisoryVerdict): String = when (verdict) {
    PaymentAdvisoryVerdict.CLEAR -> "\u2713"
    PaymentAdvisoryVerdict.CHECK -> "!"
    PaymentAdvisoryVerdict.STOP -> "!!"
}

/**
 * One hint: severity glyph, then the hint text.
 *
 * The text takes the remaining width via `weight(1f)` and has no `maxLines`, so a long hint wraps to
 * as many lines as it needs; the glyph stays top-aligned with the first line.
 */
@Composable
private fun AdvisorySeverityRow(
    glyph: String,
    text: String,
    glyphColor: Color,
    textColor: Color,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleMedium,
            color = glyphColor
        )
        Spacer(Modifier.width(TsTokens.SpacingSm))
        Text(
            text = text,
            style = textStyle,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * The mandatory confirmation line — its own bordered container, always last.
 *
 * `secondaryContainer` / `onSecondaryContainer` keeps it visually distinct from the card body so it
 * never reads as another hint, and it is never merged into the verdict or a hint row.
 */
@Composable
private fun AdvisoryConfirmationLine(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(TsTokens.CornerSmall),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(TsTokens.SpacingSm)
        )
    }
}
