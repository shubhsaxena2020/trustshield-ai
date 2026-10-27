package ai.trustshield.android.ui.result

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.trustshield.android.core.payment.PaymentAdvisory
import ai.trustshield.android.core.payment.PaymentAdvisoryVerdict
import ai.trustshield.android.core.payment.PaymentFields
import ai.trustshield.android.core.payment.PaymentHint
import ai.trustshield.android.core.payment.PaymentHintKind
import ai.trustshield.android.core.payment.PaymentHintSeverity
import ai.trustshield.android.ui.theme.TrustShieldTheme
import ai.trustshield.android.ui.theme.RiskTone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Result-screen Compose path coverage for advisory visibility, accessibility, and disclaimer. */
@RunWith(AndroidJUnit4::class)
class PaymentAdvisoryComposePathTest {
    @get:Rule
    val compose = createComposeRule()

    private val advisory = PaymentAdvisory(
        isPaymentScreenshot = true,
        fields = PaymentFields(),
        hints = listOf(
            PaymentHint(PaymentHintKind.PAYEE_NOT_REGISTERED, PaymentHintSeverity.CAUTION, maskedPayee = "dem***@upi"),
            PaymentHint(PaymentHintKind.URGENCY_PRESSURE, PaymentHintSeverity.WARNING)
        ),
        verdict = PaymentAdvisoryVerdict.CHECK
    )

    private fun render(language: UiLanguage = UiLanguage.HINGLISH) {
        val state = ResultPresenter.build(ResultFixtures.bankCreditInput).copy(paymentAdvisory = advisory)
        compose.setContent {
            TrustShieldTheme { ResultScreen(state = state, language = language) }
        }
        compose.waitForIdle()
    }

    @Test
    fun resultPathShowsAdvisoryAndMandatoryConfirmationLastInItsAnnouncement() {
        render()
        val title = PaymentAdvisoryCopy.SECTION_TITLE.hinglish
        val headline = PaymentAdvisoryCopy.verdictHeadline(advisory.verdict).hinglish
        val advice = PaymentAdvisoryCopy.verdictAdvice(advisory.verdict).hinglish
        val confirmation = PaymentAdvisoryCopy.CONFIRMATION.hinglish

        compose.onNodeWithText(title, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(headline, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(confirmation, useUnmergedTree = true).assertExists()
        val description = buildString {
            append(title).append(". ").append(headline).append(". ").append(advice)
            advisory.hints.forEach { hint ->
                append(". ").append(PaymentAdvisoryCopy.severityGlyph(hint))
                    .append(". ").append(PaymentAdvisoryCopy.forHint(hint).hinglish)
            }
            append(". ").append(confirmation)
        }
        compose.onNodeWithContentDescription(description).assertExists()
    }

    @Test
    fun resultPathUsesHindiAccessibleCardAndDoesNotExposeRawPayee() {
        render(UiLanguage.HINDI)
        val description = buildString {
            append(PaymentAdvisoryCopy.SECTION_TITLE.hindi).append(". ")
            append(PaymentAdvisoryCopy.verdictHeadline(advisory.verdict).hindi).append(". ")
            append(PaymentAdvisoryCopy.verdictAdvice(advisory.verdict).hindi)
            advisory.hints.forEach { hint ->
                append(". ").append(PaymentAdvisoryCopy.severityGlyph(hint))
                    .append(". ").append(PaymentAdvisoryCopy.forHint(hint).hindi)
            }
            append(". ").append(PaymentAdvisoryCopy.CONFIRMATION.hindi)
        }
        compose.onNodeWithContentDescription(description).assertExists()
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hindi, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("dem-recipient@upi", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun actualResultPathKeepsRiskAndConfidenceAlongsidePaymentCaution() {
        render()
        val result = ResultPresenter.build(ResultFixtures.bankCreditInput).copy(paymentAdvisory = advisory)
        // This checks ResultScreen attachment, not just the standalone advisory card.
        compose.onNodeWithText(PaymentAdvisoryCopy.SECTION_TITLE.hinglish, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hinglish, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(result.risk.label.hinglish, useUnmergedTree = true).assertExists()
        compose.onNodeWithText(result.confidence.label.hinglish, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(result.screenDescription(UiLanguage.HINGLISH)).assertExists()
        advisory.hints.forEach { hint ->
            compose.onNodeWithText(PaymentAdvisoryCopy.severityGlyph(hint), useUnmergedTree = true).assertExists()
            compose.onNodeWithText(PaymentAdvisoryCopy.forHint(hint).hinglish, useUnmergedTree = true).assertExists()
        }
        compose.onNodeWithText("dem-recipient@upi", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun advisoryAnnouncementEndsExactlyAtConfirmationAndMakesNoFraudClaim() {
        render()
        val confirmation = PaymentAdvisoryCopy.CONFIRMATION.hinglish
        val description = buildString {
            append(PaymentAdvisoryCopy.SECTION_TITLE.hinglish).append(". ")
            append(PaymentAdvisoryCopy.verdictHeadline(advisory.verdict).hinglish).append(". ")
            append(PaymentAdvisoryCopy.verdictAdvice(advisory.verdict).hinglish)
            advisory.hints.forEach { hint ->
                append(". ").append(PaymentAdvisoryCopy.severityGlyph(hint))
                    .append(". ").append(PaymentAdvisoryCopy.forHint(hint).hinglish)
            }
            append(". ").append(confirmation)
        }
        compose.onNodeWithContentDescription(description).assertExists()
        assertEquals(description.length - confirmation.length, description.lastIndexOf(confirmation))
        compose.onNodeWithText("Fraud", ignoreCase = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Scammer", ignoreCase = true, useUnmergedTree = true).assertDoesNotExist()
        assertTrue(advisory.verdict == PaymentAdvisoryVerdict.CHECK)
    }
}
