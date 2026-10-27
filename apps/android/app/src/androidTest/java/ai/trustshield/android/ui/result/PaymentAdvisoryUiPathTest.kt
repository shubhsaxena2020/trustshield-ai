package ai.trustshield.android.ui.result

import androidx.compose.ui.test.assertDoesNotExist
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** UI-path coverage for advisory display, mismatch caution, and final disclaimer. */
@RunWith(AndroidJUnit4::class)
class PaymentAdvisoryUiPathTest {
    @get:Rule
    val compose = createComposeRule()

    private val advisory = PaymentAdvisory(
        isPaymentScreenshot = true,
        fields = PaymentFields(),
        // Mismatch plus urgency exercise distinct advisory guidance in the rendered card.
        hints = listOf(
            PaymentHint(PaymentHintKind.PAYEE_NOT_REGISTERED, PaymentHintSeverity.CAUTION, maskedPayee = "dem***@upi"),
            PaymentHint(PaymentHintKind.URGENCY_PRESSURE, PaymentHintSeverity.WARNING)
        ),
        verdict = PaymentAdvisoryVerdict.CHECK
    )

    private fun render(language: UiLanguage = UiLanguage.HINGLISH) {
        compose.setContent {
            TrustShieldTheme {
                PaymentAdvisoryCard(advisory = advisory, language = language)
            }
        }
        compose.waitForIdle()
    }

    private fun spokenDescription(language: UiLanguage): String {
        fun PaymentAdvisoryCopy.Localized.pickForTest(): String = when (language) {
            UiLanguage.HINGLISH -> hinglish
            UiLanguage.ENGLISH -> english
            UiLanguage.HINDI -> hindi
        }
        return buildString {
            append(PaymentAdvisoryCopy.SECTION_TITLE.pickForTest()).append(". ")
            append(PaymentAdvisoryCopy.verdictHeadline(advisory.verdict).pickForTest()).append(". ")
            append(PaymentAdvisoryCopy.verdictAdvice(advisory.verdict).pickForTest())
            advisory.hints.forEach { hint ->
                append(". ").append(PaymentAdvisoryCopy.forHint(hint).pickForTest())
            }
            append(". ").append(PaymentAdvisoryCopy.CONFIRMATION.pickForTest())
        }
    }

    @Test
    fun cardDisplaysMismatchAndAdviceThenConfirmationLast() {
        render()

        val visibleTexts = listOf(
            PaymentAdvisoryCopy.SECTION_TITLE.hinglish,
            PaymentAdvisoryCopy.verdictHeadline(advisory.verdict).hinglish,
            PaymentAdvisoryCopy.verdictAdvice(advisory.verdict).hinglish,
            PaymentAdvisoryCopy.severityGlyph(advisory.hints[0]),
            PaymentAdvisoryCopy.forHint(advisory.hints[0]).hinglish,
            PaymentAdvisoryCopy.severityGlyph(advisory.hints[1]),
            PaymentAdvisoryCopy.forHint(advisory.hints[1]).hinglish,
            PaymentAdvisoryCopy.CONFIRMATION.hinglish
        )
        visibleTexts.forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertExists() }
        val disclaimer = PaymentAdvisoryCopy.CONFIRMATION.hinglish
        assertEquals(visibleTexts.last(), disclaimer)

        // TalkBack uses the card's explicit merged description; hint text remains in engine order.
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hinglish, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(spokenDescription(UiLanguage.HINGLISH)).assertExists()

    }

    @Test
    fun mergedTalkBackDescriptionAnnouncesVerdictHintsAndConfirmationInOrder() {
        render()
        val expected = spokenDescription(UiLanguage.HINGLISH)
        compose.onNodeWithContentDescription(expected).assertExists()
        assertTrue(expected.endsWith(PaymentAdvisoryCopy.CONFIRMATION.hinglish))
    }

    @Test
    fun hindiCardKeepsConfirmationAndDoesNotExposeRawPayee() {
        render(UiLanguage.HINDI)
        compose.onNodeWithText(PaymentAdvisoryCopy.CONFIRMATION.hindi, useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(spokenDescription(UiLanguage.HINDI)).assertExists()
        compose.onNodeWithText("dem-recipient@upi", useUnmergedTree = true).assertDoesNotExist()
        assertTrue(spokenDescription(UiLanguage.HINDI).endsWith(PaymentAdvisoryCopy.CONFIRMATION.hindi))
    }
}
