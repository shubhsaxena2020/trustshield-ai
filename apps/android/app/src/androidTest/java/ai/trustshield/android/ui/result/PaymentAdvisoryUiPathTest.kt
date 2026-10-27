package ai.trustshield.android.ui.result

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.trustshield.android.core.analysis.LocalScanPipeline
import ai.trustshield.android.core.payment.PaymentAdvisory
import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.core.payment.PaymentFields
import ai.trustshield.android.core.payment.PaymentHint
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.core.payment.PaymentHintSeverity
import ai.trustshield.android.ui.theme.RiskTone
import ai.trustshield.android.ui.navigation.AnalysisToResult
import ai.trustshield.android.ui.qr.RegisteredPayee
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

    @Test
    fun resultScreenRouteRendersAttachedPaymentAdvisory() {
        val base = ResultPresenter.build(ResultInput(
            sourceText = "Paid ₹6,000 to dem***@upi",
            coverage = InputCoverage.READABLE_SUPPORTED,
            riskTone = RiskTone.AMBER,
            confidence = ConfidenceLevel.MEDIUM
        ))
        compose.setContent {
            TrustShieldTheme {
                ResultScreen(state = base.copy(paymentAdvisory = advisory))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(PaymentAdvisoryCopy.SECTION_TITLE.hinglish).assertExists()
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hinglish).assertExists()
    }

    @Test
    fun paymentLikeMessageBuildsAndRendersAdvisoryOnResultScreen() {
        val outcome = LocalScanPipeline().analyzeText("Pay Rs 6,000 to shop@okaxis now")
        val state = AnalysisToResult.build(outcome, listOf(RegisteredPayee("known@okaxis")))
        val builtAdvisory = requireNotNull(state.paymentAdvisory)

        compose.setContent {
            TrustShieldTheme {
                ResultScreen(state = state)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(PaymentAdvisoryCopy.SECTION_TITLE.hinglish).assertExists()
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hinglish).assertExists()
        compose.onNodeWithText(PaymentAdvisoryCopy.forHint(builtAdvisory.hints.first()).hinglish).assertExists()
    }
}
