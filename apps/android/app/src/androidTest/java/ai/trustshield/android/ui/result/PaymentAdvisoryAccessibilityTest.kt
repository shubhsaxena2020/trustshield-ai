package ai.trustshield.android.ui.result

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.trustshield.android.core.payment.PaymentAdvisory
import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.core.payment.PaymentFields
import ai.trustshield.android.core.payment.PaymentHint
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.core.payment.PaymentHintSeverity
import ai.trustshield.android.ui.theme.TrustShieldTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Adversarial semantics, contrast, target-size and announcement checks for the advisory card. */
@RunWith(AndroidJUnit4::class)
class PaymentAdvisoryAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val confirmation = PaymentAdvisoryCopy.CONFIRMATION.hinglish
    private val hints = listOf(
        PaymentHint(PaymentHintKind.URGENCY_PRESSURE, PaymentHintSeverity.WARNING),
        PaymentHint(PaymentHintKind.PAYEE_NOT_REGISTERED, PaymentHintSeverity.CAUTION, maskedPayee = "dem***@upi")
    )

    private fun render(verdict: PaymentAdvisoryVerdict, dark: Boolean = false, cardModifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
        compose.setContent {
            TrustShieldTheme(darkTheme = dark) {
                PaymentAdvisoryCard(
                    advisory = PaymentAdvisory(true, PaymentFields(), hints, verdict),
                    language = UiLanguage.HINGLISH,
                    modifier = cardModifier
                )
            }
        }
        compose.waitForIdle()
    }

    private fun luminance(color: androidx.compose.ui.graphics.Color): Double {
        fun channel(value: Float): Double {
            val c = value.toDouble()
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private fun contrast(foreground: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color): Double {
        val values = listOf(luminance(foreground), luminance(background)).sortedDescending()
        return (values[0] + 0.05) / (values[1] + 0.05)
    }

    @Test
    fun mergedDescriptionIncludesEveryHintExactlyOnceAndConfirmationLast() {
        render(PaymentAdvisoryVerdict.STOP)
        val pieces = listOf(
            PaymentAdvisoryCopy.SECTION_TITLE.hinglish,
            PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP).hinglish,
            PaymentAdvisoryCopy.verdictAdvice(PaymentAdvisoryVerdict.STOP).hinglish
        ) + hints.map { PaymentAdvisoryCopy.forHint(it).hinglish } + confirmation
        val description = pieces.joinToString(". ")
        val node = compose.onNodeWithContentDescription(description)
        node.assertExists()
        assertEquals("confirmation must be the final spoken clause", description.length - confirmation.length, description.lastIndexOf(confirmation))
        val clausePositions = pieces.map(description::indexOf)
        assertTrue("spoken clauses must follow visual order", clausePositions.zipWithNext().all { (first, next) -> first >= 0 && first < next })
        hints.forEach { hint -> assertEquals(1, Regex(Regex.escape(PaymentAdvisoryCopy.forHint(hint).hinglish)).findAll(description).count()) }
        compose.onNodeWithText("dem-recipient@upi", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun emptyHintsStillAnnounceMandatoryConfirmationAndNoSuccessClaim() {
        compose.setContent {
            TrustShieldTheme {
                PaymentAdvisoryCard(PaymentAdvisory(true, PaymentFields(), emptyList(), PaymentAdvisoryVerdict.CLEAR), UiLanguage.HINGLISH)
            }
        }
        val expected = listOf(
            PaymentAdvisoryCopy.SECTION_TITLE.hinglish,
            PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.CLEAR).hinglish,
            PaymentAdvisoryCopy.verdictAdvice(PaymentAdvisoryVerdict.CLEAR).hinglish,
            confirmation
        ).joinToString(". ")
        compose.onNodeWithContentDescription(expected).assertExists()
        assertFalse(expected.contains("safe", ignoreCase = true))
        assertFalse(expected.contains("paid", ignoreCase = true))
        compose.onNodeWithText(confirmation, useUnmergedTree = true).assertExists()
    }

    @Test
    fun advisoryIsInformationalNotAnInteractiveControlSoNoTouchTargetIsRequired() {
        render(PaymentAdvisoryVerdict.CHECK)
        // The card has no action; it must not masquerade as a tappable control with an undersized hitbox.
        compose.onNodeWithContentDescription(confirmation, substring = true).assertExists()
            .assertDoesNotHaveClickActionCompat()
    }

    @Test
    fun stopTextContrastMeetsNormalTextMinimumInLightAndDarkThemes() {
        listOf(false, true).forEach { dark ->
            render(PaymentAdvisoryVerdict.STOP, dark)
            compose.onNodeWithText(PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP).hinglish, useUnmergedTree = true)
                .assertExists()
            val scheme = if (dark) ai.trustshield.android.ui.theme.TsColorSchemes.Dark else ai.trustshield.android.ui.theme.TsColorSchemes.Light
            assertTrue("STOP headline contrast below 4.5:1 in dark=$dark", contrast(scheme.error, scheme.surface) >= 4.5)
            assertTrue("body text contrast below 4.5:1 in dark=$dark", contrast(scheme.onSurface, scheme.surface) >= 4.5)
        }
    }

    @Test
    fun advisoryCardHasNoInteractiveAction() {
        render(PaymentAdvisoryVerdict.CHECK)
        compose.onNodeWithContentDescription(confirmation, substring = true).assertExists()
            .assertDoesNotHaveClickActionCompat()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotHaveClickActionCompat() {
        var hasClick = false
        try {
            performSemanticsAction(SemanticsActions.OnClick) { hasClick = true }
        } catch (_: AssertionError) {
            hasClick = false
        }
        assertFalse("informational advisory must not expose a click action", hasClick)
    }
}
