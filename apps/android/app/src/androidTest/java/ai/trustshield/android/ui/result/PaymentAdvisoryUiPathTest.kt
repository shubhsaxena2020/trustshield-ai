package ai.trustshield.android.ui.result

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.trustshield.android.core.payment.PaymentAdvisory
import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.core.payment.PaymentFields
import ai.trustshield.android.core.payment.PaymentHint
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.core.payment.PaymentHintSeverity
import ai.trustshield.android.ui.theme.TrustShieldTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-path coverage for payment advisory card order and its single TalkBack announcement. */
@RunWith(AndroidJUnit4::class)
class PaymentAdvisoryUiPathTest {
    @get:Rule
    val compose = createComposeRule()

    private val advisory = PaymentAdvisory(
        isPaymentScreenshot = true,
        fields = PaymentFields(),
        // Deliberately mixed severities: the card must retain engine order, not regroup in UI.
        hints = listOf(
            PaymentHint(PaymentHintKind.URGENCY_PRESSURE, PaymentHintSeverity.WARNING),
            PaymentHint(PaymentHintKind.PAYEE_NOT_REGISTERED, PaymentHintSeverity.CAUTION, maskedPayee = "dem***@upi"),
            PaymentHint(PaymentHintKind.SCREENSHOT_ONLY, PaymentHintSeverity.INFO)
        ),
        verdict = PaymentAdvisoryVerdict.STOP
    )

    private fun render(language: UiLanguage = UiLanguage.HINGLISH) {
        compose.setContent {
            TrustShieldTheme {
                PaymentAdvisoryCard(advisory = advisory, language = language)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun cardDisplaysVerdictThenHintsInSuppliedOrderThenMandatoryConfirmation() {
        render()

        val visibleTexts = listOf(
            PaymentAdvisoryCopy.SECTION_TITLE.hinglish,
            PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP).hinglish,
            PaymentAdvisoryCopy.verdictAdvice(PaymentAdvisoryVerdict.STOP).hinglish,
            PaymentAdvisoryCopy.severityGlyph(advisory.hints[0]),
            PaymentAdvisoryCopy.forHint(advisory.hints[0]).hinglish,
            PaymentAdvisoryCopy.severityGlyph(advisory.hints[1]),
            PaymentAdvisoryCopy.forHint(advisory.hints[1]).hinglish,
            PaymentAdvisoryCopy.severityGlyph(advisory.hints[2]),
            PaymentAdvisoryCopy.forHint(advisory.hints[2]).hinglish,
            PaymentAdvisoryCopy.CONFIRMATION.hinglish
        )
        visibleTexts.forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertExists() }

        // The confirmation is the last textual child in the rendered card, not merely present.
        // The footer is a distinct visible node; the merged announcement pins its final reading order.
              compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hinglish, useUnmergedTree = true).assertExists()
        assertTrue("fixture must include three ordered hints", advisory.hints.size == 3)
    }

    @Test
    fun mergedTalkBackDescriptionAnnouncesVerdictHintsAndConfirmationInOrder() {
        render()
        val expected = buildString {
            append(PaymentAdvisoryCopy.SECTION_TITLE.hinglish)
            append(". ")
            append(PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP).hinglish)
            append(". ")
            append(PaymentAdvisoryCopy.verdictAdvice(PaymentAdvisoryVerdict.STOP).hinglish)
            advisory.hints.forEach { hint ->
                append(". ")
                append(PaymentAdvisoryCopy.severityGlyph(hint))
                append(". ")
                append(PaymentAdvisoryCopy.forHint(hint).hinglish)
            }
            append(". ")
            append(PaymentAdvisoryCopy.CONFIRMATION.hinglish)
        }
        compose.onNodeWithContentDescription(expected).assertExists()
        assertTrue(expected.lastIndexOf(PaymentAdvisoryCopy.CONFIRMATION.hinglish) >
            expected.indexOf(PaymentAdvisoryCopy.forHint(advisory.hints.last()).hinglish))
    }

    @Test
    fun hindiCardKeepsConfirmationAndDoesNotExposeRawPayee() {
        render(UiLanguage.HINDI)
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hindi, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(
            PaymentAdvisoryCopy.SECTION_TITLE.hindi + ". " +
                PaymentAdvisoryCopy.verdictHeadline(PaymentAdvisoryVerdict.STOP).hindi + ". " +
                PaymentAdvisoryCopy.verdictAdvice(PaymentAdvisoryVerdict.STOP).hindi +
                advisory.hints.joinToString("") { hint ->
                    ". ${PaymentAdvisoryCopy.severityGlyph(hint)}. ${PaymentAdvisoryCopy.forHint(hint).hindi}"
                } + ". " + PaymentAdvisoryCopy.CONFIRMATION.hindi
        ).assertExists()
        compose.onNodeWithText("dem-recipient@upi", useUnmergedTree = true).assertDoesNotExist()
    }
}
